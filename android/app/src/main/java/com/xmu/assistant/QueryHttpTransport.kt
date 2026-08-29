package com.xmu.assistant

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal data class QueryHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "",
    val body: String = "",
    val operation: NetworkOperation = NetworkOperation.UNKNOWN,
)

internal data class QueryHttpResponse(
    val url: String,
    val code: Int,
    val location: String?,
    val body: String,
    val headers: Map<String, List<String>>,
)

internal fun interface QueryHttpTransport {
    fun execute(request: QueryHttpRequest): QueryHttpResponse
}

internal class OkHttpQueryTransport(
    private val client: OkHttpClient = XmuHttpClients.query,
) : QueryHttpTransport {
    override fun execute(request: QueryHttpRequest): QueryHttpResponse {
        val methodUpper = request.method.uppercase()
        val methodRequiresBody = methodUpper in setOf("POST", "PUT", "PATCH")
        // GET/HEAD 禁止携带 body（OkHttp 对 method("GET", body) 直接抛 IllegalArgumentException），
        // 防御性忽略；需要 body 的方法即使空 body 也要显式给空（method("POST", null) 同样抛 IAE）
        val methodPermitsBody = methodUpper !in setOf("GET", "HEAD")
        val requestBody = when {
            methodRequiresBody -> request.body.toRequestBody(
                request.contentType.takeIf(String::isNotBlank)?.toMediaType(),
            )
            request.body.isNotEmpty() && methodPermitsBody -> request.body.toRequestBody(
                request.contentType.takeIf(String::isNotBlank)?.toMediaType(),
            )
            else -> null
        }
        val builder = Request.Builder()
            .url(request.url)
            .method(request.method, requestBody)
            .tag(NetworkOperation::class.java, request.operation)
        NetworkTimingContextScope.currentFor(request.operation)?.let {
            builder.tag(NetworkTimingContext::class.java, it)
        }
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        return client.newCall(builder.build()).execute().use { response ->
            QueryHttpResponse(
                url = request.url,
                code = response.code,
                location = response.header("Location"),
                body = response.body?.string().orEmpty(),
                headers = response.headers.toMultimap(),
            )
        }
    }
}
