package com.xmu.assistant

import java.io.File
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
        val resumeFrom = if (target.exists()) target.length() else 0L
        var attempt = 0
        while (attempt < MAX_REDIRECTS) {
            attempt += 1
            val headers = if (resumeFrom > 0) {
                currentHeaders + mapOf("Range" to "bytes=$resumeFrom-")
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
                val result = FileDownloadResult(
                    code = it.code,
                    contentType = it.header("Content-Type").orEmpty(),
                )
                if (it.code in 200..299) {
                    val body = checkNotNull(it.body) { "下载响应为空" }
                    // 206 = 服务端确认从断点续传，追加写；
                    // 200（服务端忽略 Range 或文件已变）= 全量覆盖，不可盲目拼接
                    val appending = resumeFrom > 0 && it.code == 206
                    target.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(target, appending).use { output ->
                            input.copyTo(output, COPY_BUFFER_BYTES)
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

        /** 有效端口：未显式指定（-1）时按协议默认端口归一化（http=80，https=443）。 */
        fun java.net.URL.effectivePort(): Int =
            if (port != -1) port else if (protocol.equals("https", ignoreCase = true)) 443 else 80
    }
}
