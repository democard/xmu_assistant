package com.xmu.assistant

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class FileDownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val operation: NetworkOperation = NetworkOperation.DOWNLOAD,
    /** Enables identity- and validator-bound resume for production downloads. */
    val resourceIdentity: String? = null,
)

internal data class FileDownloadResult(
    val code: Int,
    val contentType: String,
)

internal fun interface FileDownloadTransport {
    fun download(request: FileDownloadRequest, target: File): FileDownloadResult
}

internal class OkHttpFileDownloadTransport(
    private val client: OkHttpClient = XmuHttpClients.download,
    private val writeResumeMetadata: (File, String, DownloadEntityValidator?) -> Unit =
        { partial, identity, validator -> DownloadResumeMetadata.write(partial, identity, validator) },
) : FileDownloadTransport {
    override fun download(request: FileDownloadRequest, target: File): FileDownloadResult {
        val resourceIdentity = request.resourceIdentity?.takeIf(String::isNotBlank)
        val safeResume = resourceIdentity != null
        val metadata = if (safeResume) DownloadResumeMetadata.read(target) else null
        val initialLength = if (target.exists()) target.length() else 0L
        var resumeFrom = if (!safeResume || metadata?.canResume(resourceIdentity!!) == true) initialLength else 0L
        var preserveExisting = safeResume && initialLength > 0L && resumeFrom == 0L
        var resumeValidator = metadata?.takeIf { it.canResume(resourceIdentity.orEmpty()) }?.validator
        var currentUrl = request.url
        var currentHeaders = request.headers
        var restartingFullDownload = false
        var attempt = 0
        while (attempt < MAX_REDIRECTS + (if (restartingFullDownload) 1 else 0)) {
            attempt += 1
            val requestHeaders = if (safeResume) {
                currentHeaders.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) } + ("Accept-Encoding" to "identity")
            } else {
                currentHeaders
            }
            val headers = if (resumeFrom > 0) {
                requestHeaders + buildMap {
                    put("Range", "bytes=$resumeFrom-")
                    resumeValidator?.let { put("If-Range", it.value) }
                }
            } else if (restartingFullDownload) {
                requestHeaders.filterKeys {
                    !it.equals("Range", ignoreCase = true) &&
                        !it.equals("If-Range", ignoreCase = true)
                }
            } else {
                requestHeaders
            }
            val response = client.newCall(buildRequest(currentUrl, headers, request)).execute()
            response.use {
                val location = it.header("Location")
                if (it.code in 300..399 && !location.isNullOrBlank()) {
                    val next = java.net.URL(java.net.URL(currentUrl), location).toString()
                    val nextUrl = java.net.URL(next)
                    val currentUrlObj = java.net.URL(currentUrl)
                    // 跨源跳转（协议/主机/端口任一不同，签名地址 → CDN 直链）：剥离 Cookie 等敏感头，
                    // 避免会话凭据外泄给第三方（OkHttp 自动重定向会保留手动 Cookie 头）。
                    // 注意：必须比较 scheme（https→http 降级同 host 也算跨源，防凭据随明文外发）；
                    // 端口需按协议默认端口归一化（https://host 与 https://host:443 是同源，
                    // URL.getPort() 对未显式端口返回 -1，直接比较会误判跨源剥掉 Cookie 导致 401）。
                    if (!nextUrl.protocol.equals(currentUrlObj.protocol, ignoreCase = true) ||
                        !nextUrl.host.equals(currentUrlObj.host, ignoreCase = true) ||
                        nextUrl.effectivePort() != currentUrlObj.effectivePort()
                    ) {
                        currentHeaders = emptyMap()
                        if (safeResume && resumeFrom > 0) {
                            // An entity validator is origin-scoped; never send Range without its If-Range.
                            resumeFrom = 0L
                            resumeValidator = null
                            restartingFullDownload = true
                            preserveExisting = initialLength > 0L
                        }
                    }
                    currentUrl = next
                    return@use // 继续下一跳
                }
                val contentTypeHeader = it.header("Content-Type").orEmpty()
                // A Range request disables OkHttp's transparent gzip decoding.
                // If the server ignores Range and sends encoded full content,
                // retry once without Range and preserve the previous partial.
                val encodedFullResponse = it.code == 200 &&
                    DownloadBodyWriter.isFilePayload(contentTypeHeader) && DownloadBodyWriter.hasEncodedBody(it)
                if (safeResume && encodedFullResponse) {
                    // Production requests require identity bytes for validator-bound resume.
                    // Do not accept a server that ignores identity and returns encoded bytes.
                    error("下载服务器忽略 identity 编码要求，拒绝保存压缩响应")
                }
                if (resumeFrom > 0 && !restartingFullDownload && (it.code == 416 || encodedFullResponse)) {
                    resumeFrom = 0L
                    restartingFullDownload = true
                    preserveExisting = initialLength > 0L
                    return@use
                }
                if (safeResume && it.code == 206) {
                    check(resumeFrom > 0L && resumeValidator != null) {
                        "无匹配资源版本的下载断点，拒绝追加响应"
                    }
                    val responseValidator = DownloadResumeMetadata.validator(it.header("ETag"))
                    if (responseValidator == null || responseValidator != resumeValidator) {
                        resumeFrom = 0L
                        resumeValidator = null
                        restartingFullDownload = true
                        preserveExisting = initialLength > 0L
                        return@use
                    }
                }
                val result = FileDownloadResult(
                    code = it.code,
                    contentType = contentTypeHeader,
                )
                if (it.code in setOf(200, 206) && DownloadBodyWriter.isFilePayload(contentTypeHeader)) {
                    val validator = if (safeResume && it.code == 200) {
                        DownloadResumeMetadata.validator(it.header("ETag"))
                    } else {
                        null
                    }
                    if (safeResume && it.code == 200 && initialLength == 0L) {
                        // For a new/empty partial, record ownership and a strong validator before
                        // the first body byte so a mid-stream failure leaves a resumable prefix.
                        writeResumeMetadata(target, resourceIdentity!!, validator)
                    }
                    DownloadBodyWriter.write(
                        it,
                        target,
                        resumeFrom,
                        preserveOriginal = if (safeResume && it.code == 200) {
                            initialLength > 0L
                        } else {
                            restartingFullDownload || preserveExisting
                        },
                        beforeCommit = if (safeResume && it.code == 200 && initialLength > 0L) {
                            {
                                check(DownloadResumeMetadata.invalidate(target)) {
                                    "无法安全失效旧下载版本，已保留原断点"
                                }
                            }
                        } else {
                            null
                        },
                    )
                    if (safeResume && it.code == 200 && initialLength > 0L) {
                        // The old validator was invalidated immediately before replacing old bytes.
                        // A failed commit therefore leaves new bytes unowned, never paired with old ETag.
                        writeResumeMetadata(target, resourceIdentity!!, validator)
                    }
                }
                return result
            }
        }
        // 重定向次数耗尽（异常的服务端跳转链）：报告失败
        return FileDownloadResult(code = -1, contentType = "")
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        request: FileDownloadRequest,
    ): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .tag(NetworkOperation::class.java, request.operation)
        NetworkTimingContextScope.currentFor(request.operation)?.let {
            builder.tag(NetworkTimingContext::class.java, it)
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private companion object {
        const val MAX_REDIRECTS = 8

        /** 有效端口：未显式指定（-1）时按协议默认端口归一化（http=80，https=443）。 */
        fun java.net.URL.effectivePort(): Int =
            if (port != -1) port else if (protocol.equals("https", ignoreCase = true)) 443 else 80
    }
}

internal data class DownloadEntityValidator(val kind: String, val value: String)

/** Sidecar ownership prevents a same-named .part from being reused for another resource. */
internal data class DownloadResumeMetadata(
    val resourceIdentity: String,
    val validator: DownloadEntityValidator?,
) {
    fun canResume(identity: String): Boolean = resourceIdentity == identity && validator != null

    companion object {
        fun sidecar(partial: File): File = File(partial.parentFile, partial.name + ".meta")

        fun read(partial: File): DownloadResumeMetadata? = runCatching {
            val root = JSONObject(sidecar(partial).readText())
            val identity = root.optString("resourceIdentity").takeIf(String::isNotBlank) ?: return null
            val kind = root.optString("validatorKind")
            val value = root.optString("validatorValue")
            val validator = if (kind.isNotBlank() && value.isNotBlank()) DownloadEntityValidator(kind, value) else null
            DownloadResumeMetadata(identity, validator)
        }.getOrNull()

        fun validator(etag: String?): DownloadEntityValidator? {
            val strongEtag = etag?.trim()?.takeIf {
                it.length >= 2 && it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/", ignoreCase = true)
            }
            if (strongEtag != null) return DownloadEntityValidator("etag", strongEtag)
            return null
        }

        fun write(partial: File, identity: String, validator: DownloadEntityValidator?) {
            val sidecar = sidecar(partial)
            val temp = File(sidecar.parentFile, sidecar.name + ".tmp")
            val json = JSONObject()
                .put("resourceIdentity", identity)
                .put("validatorKind", validator?.kind.orEmpty())
                .put("validatorValue", validator?.value.orEmpty())
            try {
                sidecar.parentFile?.mkdirs()
                temp.writeText(json.toString())
                try {
                    Files.move(temp.toPath(), sidecar.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.io.IOException) {
                    Files.move(temp.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
        }

        fun invalidate(partial: File): Boolean = runCatching {
            val metadata = sidecar(partial)
            val temp = File(metadata.parentFile, metadata.name + ".tmp")
            (!metadata.exists() || metadata.delete()) && (!temp.exists() || temp.delete())
        }.getOrDefault(false)
    }
}
