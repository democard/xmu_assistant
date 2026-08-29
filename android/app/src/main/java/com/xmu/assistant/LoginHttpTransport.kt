package com.xmu.assistant

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal data class LoginHttpRequest(
    val url: String,
    val method: String,
    val contentType: String,
    val body: String,
    val cookieHeader: String,
    val operation: NetworkOperation = NetworkOperation.LOGIN,
)

internal data class LoginHttpResponse(
    val url: String,
    val code: Int,
    val location: String?,
    val body: String,
    val headers: Map<String, List<String>>,
)

internal fun interface LoginHttpTransport {
    fun execute(request: LoginHttpRequest): LoginHttpResponse
}

internal class OkHttpLoginTransport(
    private val client: OkHttpClient = XmuHttpClients.login,
) : LoginHttpTransport {
    override fun execute(request: LoginHttpRequest): LoginHttpResponse {
        // POST/PUT/PATCH 必须携带 body（method("POST", null) 抛 IllegalArgumentException），
        // 空 body 时显式给空 body；GET/HEAD 禁止携带 body，防御性忽略
        val methodUpper = request.method.uppercase()
        val methodRequiresBody = methodUpper in setOf("POST", "PUT", "PATCH")
        val methodPermitsBody = methodUpper !in setOf("GET", "HEAD")
        val requestBody = when {
            methodRequiresBody -> request.body.toRequestBody(request.contentType.toMediaType())
            request.body.isNotEmpty() && methodPermitsBody ->
                request.body.toRequestBody(request.contentType.toMediaType())
            else -> null
        }
        val builder = Request.Builder()
            .url(request.url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .method(request.method, requestBody)
            .tag(NetworkOperation::class.java, request.operation)
        NetworkTimingContextScope.currentFor(request.operation)?.let {
            builder.tag(NetworkTimingContext::class.java, it)
        }
        request.cookieHeader.takeIf(String::isNotBlank)?.let {
            builder.header("Cookie", it)
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                LoginHttpResponse(
                    url = request.url,
                    code = response.code,
                    location = response.header("Location"),
                    body = response.body?.string().orEmpty(),
                    headers = response.headers.toMultimap(),
                )
            }
        } catch (error: Throwable) {
            // Never log request bodies: the login flow contains credentials and
            // authorization codes. The exception text is useful because Android
            // includes the concrete host/address and whether this was a
            // connect, TLS, DNS, or read timeout failure.
            Log.e(
                "XmuLogin",
                "${request.method} ${request.url.substringBefore('?')} failed: " +
                    "${error.rootCause().javaClass.simpleName}: ${error.rootCause().message}",
            )
            throw error
        }
    }

    private companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

internal fun Throwable.rootCause(): Throwable {
    var current = this
    var depth = 0
    // 限制深度：仅防自环（cause !== current）挡不住 A→B→A 循环引用，会在日志路径死循环
    while (current.cause != null && current.cause !== current && depth < 32) {
        current = current.cause!!
        depth += 1
    }
    return current
}
