package com.xmu.assistant

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class FileDownloadRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val operation: NetworkOperation = NetworkOperation.DOWNLOAD,
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
) : FileDownloadTransport {
    override fun download(request: FileDownloadRequest, target: File): FileDownloadResult {
        var currentUrl = request.url
        var currentHeaders = request.headers
        // 断点续传：目标 .part 已有字节则带 Range 续传（平台实测返回 206）。
        // Range 不是凭据，跨源跳转剥离 Cookie 时必须保留，否则 CDN 续传失效。
        var resumeFrom = if (target.exists()) target.length() else 0L
        var restartedAfterInvalidRange = false
        var attempt = 0
        while (attempt < MAX_REDIRECTS + (if (restartedAfterInvalidRange) 1 else 0)) {
            attempt += 1
            val headers = if (resumeFrom > 0) {
                currentHeaders + mapOf("Range" to "bytes=$resumeFrom-")
            } else if (restartedAfterInvalidRange) {
                currentHeaders.filterKeys {
                    !it.equals("Range", ignoreCase = true) && !it.equals("If-Range", ignoreCase = true)
                }
            } else {
                currentHeaders
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
                    }
                    currentUrl = next
                    return@use // 继续下一跳
                }
                // 文件已变或断点超出文件末尾时，原 Range 不能恢复：仅从零重试一次。
                // 旧断点先保留，重下失败/挑战页不得破坏已有内容。
                if (it.code == 416 && resumeFrom > 0 && !restartedAfterInvalidRange) {
                    resumeFrom = 0L
                    restartedAfterInvalidRange = true
                    return@use
                }
                val contentTypeHeader = it.header("Content-Type").orEmpty()
                val result = FileDownloadResult(
                    code = it.code,
                    contentType = contentTypeHeader,
                )
                // GET 下载只接纳完整文件或已校验的范围响应。204/202 等状态
                // 不携带可用文件，不能用空正文覆盖旧断点。
                if (it.code == 200 || it.code == 206) {
                    // 体检报告 P0-2：网关/WAF 可能以 200/206 + text/html 返回挑战页或登录页，
                    // application/json 错误载荷同理。这类「非文件载荷」绝不能写入 .part——
                    // 否则重试时会以 HTML 长度作 Range 起点追加真实字节，拼出损坏文件且
                    // 标记「下载成功」。命中时跳过写盘，result 照常返回，由调用方既有的
                    // Content-Type 检查判失败；.part 保持原样（合法断点前缀），续传语义不变。
                    val loweredContentType = contentTypeHeader.lowercase()
                    val nonFilePayload = "text/html" in loweredContentType ||
                        "application/json" in loweredContentType ||
                        "application/xhtml" in loweredContentType
                    if (!nonFilePayload) {
                        val body = checkNotNull(it.body) { "下载响应为空" }
                        // 206 = 服务端确认从断点续传，追加写；
                        // 200（服务端忽略 Range 或文件已变）= 全量覆盖，不可盲目拼接
                        val partialResponse = it.code == 206
                        val appending = resumeFrom > 0 && partialResponse
                        target.parentFile?.mkdirs()
                        if (partialResponse) {
                            val range = parseContentRange(it.header("Content-Range"))
                            check(range != null && range.start == resumeFrom) {
                                "下载范围无效（期望从 $resumeFrom 字节继续）"
                            }
                            check(range.endExclusive <= range.total && range.endExclusive > range.start) {
                                "下载范围无效"
                            }
                            check(it.header("Content-Encoding").orEmpty().equals("identity", ignoreCase = true) ||
                                it.header("Content-Encoding").isNullOrBlank()) {
                                "压缩响应不支持断点续传"
                            }
                            // 先暂存 206 内容，校验通过后再接入已有断点。
                            val chunk = java.io.File.createTempFile(
                                "xmu-download-",
                                ".download-chunk",
                                target.parentFile,
                            )
                            try {
                                body.byteStream().use { input ->
                                    java.io.FileOutputStream(chunk).use { output ->
                                        input.copyTo(output, COPY_BUFFER_BYTES)
                                    }
                                }
                                val expectedChunk = range.endExclusive - range.start
                                val declaredLength = it.header("Content-Length")?.toLongOrNull()
                                check(chunk.length() == expectedChunk &&
                                    (declaredLength == null || declaredLength == expectedChunk)
                                ) { "下载范围数据不完整" }
                                check(range.endExclusive == range.total) { "下载范围未到达文件末尾" }
                                if (restartedAfterInvalidRange) {
                                    Files.move(chunk.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                } else {
                                    java.io.FileOutputStream(target, appending).use { output ->
                                        chunk.inputStream().use { input ->
                                            input.copyTo(output, COPY_BUFFER_BYTES)
                                        }
                                    }
                                }
                            } finally {
                                chunk.delete()
                            }
                        } else if (restartedAfterInvalidRange) {
                            // 416 后的全量响应必须完整校验后替换；短流仍保留原断点。
                            val replacement = File.createTempFile("xmu-download-", ".download-chunk", target.parentFile)
                            try {
                                body.byteStream().use { input ->
                                    replacement.outputStream().use { output ->
                                        input.copyTo(output, COPY_BUFFER_BYTES)
                                    }
                                }
                                val expectedLength = it.header("Content-Length")?.toLongOrNull() ?: -1L
                                check(expectedLength < 0 || replacement.length() == expectedLength) {
                                    "下载不完整（收到 ${replacement.length()}/$expectedLength 字节），已保留原断点"
                                }
                                Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            } finally {
                                replacement.delete()
                            }
                        } else {
                            body.byteStream().use { input ->
                                java.io.FileOutputStream(target, appending).use { output ->
                                    input.copyTo(output, COPY_BUFFER_BYTES)
                                }
                            }
                        }
                        // 短流收尾校验（对齐 PC courseware 短 body 校验）：服务端提前断流
                        // 但连接干净结束时 copyTo 正常收尾，截断 .part 若被调用方 rename
                        // 扶正即损坏文件标记成功。Content-Length 存在时比对（206 按断点
                        // 折算），不符抛错——调用方 catch 保留 .part 供续传（既有失败路径）。
                        val expectedTotal = it.header("Content-Length")?.toLongOrNull() ?: -1L
                        if (expectedTotal >= 0) {
                            val base = if (appending) resumeFrom else 0L
                            val received = target.length() - base
                            if (received != expectedTotal) {
                                throw IllegalStateException(
                                    "下载不完整（收到 $received/$expectedTotal 字节），已保留断点续传记录",
                                )
                            }
                        }
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

        /** 下载流拷贝缓冲：默认 8KB 偏小，64KB 显著减少系统调用次数。 */
        const val COPY_BUFFER_BYTES = 64 * 1024

        private data class ContentRange(
            val start: Long,
            val endExclusive: Long,
            val total: Long,
        )

        private fun parseContentRange(value: String?): ContentRange? {
            val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
                .matchEntire(value?.trim().orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() ?: return null
            if (end < start || total <= end) return null
            return ContentRange(start, end + 1, total)
        }

        /** 有效端口：未显式指定（-1）时按协议默认端口归一化（http=80，https=443）。 */
        fun java.net.URL.effectivePort(): Int =
            if (port != -1) port else if (protocol.equals("https", ignoreCase = true)) 443 else 80
    }
}
