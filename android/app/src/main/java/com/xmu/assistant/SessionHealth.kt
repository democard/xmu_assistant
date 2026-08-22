package com.xmu.assistant

import java.io.IOException
import java.net.URL
import java.util.Locale
import org.json.JSONObject

enum class SessionHealth { VALID, EXPIRED, UNKNOWN }

class MainSessionExpiredException : IllegalStateException("登录已过期")

internal val KNOWN_IDENTITY_HOSTS = setOf("c-identity.xmu.edu.cn", "ids.xmu.edu.cn")

/**
 * 判定 3xx 重定向是否指向统一身份域（= 会话已过期）。
 * 会话过期时平台返回 302 跳身份域而非 401，各业务客户端必须与探活共用同一判定，
 * 否则 302 会被误分类为「网络失败」，自动续登永不触发。
 */
internal fun isIdentityRedirect(requestUrl: String, location: String?): Boolean {
    if (location.isNullOrBlank()) return false
    val host = runCatching {
        URL(URL(requestUrl), location).host.lowercase(Locale.US).trimEnd('.')
    }.getOrNull() ?: return false
    return host in KNOWN_IDENTITY_HOSTS
}

internal class SessionHealthProbe(
    private val transport: QueryHttpTransport = OkHttpQueryTransport(),
    private val url: String = "https://lnt.xmu.edu.cn/api/radar/rollcalls",
) {
    fun check(cookieHeader: String): SessionHealth {
        if (cookieHeader.isBlank()) return SessionHealth.UNKNOWN
        return try {
            val response = transport.execute(
                QueryHttpRequest(
                    url = url,
                    method = "GET",
                    headers = linkedMapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
                        "Accept-Language" to "zh-CN,zh;q=0.9",
                        "Cookie" to cookieHeader,
                    ),
                    operation = NetworkOperation.ROLLCALL_STATUS,
                ),
            )
            when {
                response.code == 401 || response.code == 403 -> SessionHealth.EXPIRED
                response.code in 300..399 && isKnownIdentityRedirect(response) -> SessionHealth.EXPIRED
                response.code !in 200..299 -> SessionHealth.UNKNOWN
                isKnownLoginForm(response.body) -> SessionHealth.EXPIRED
                isRollcallListBody(response.body) -> SessionHealth.VALID
                else -> SessionHealth.UNKNOWN
            }
        } catch (_: IOException) {
            SessionHealth.UNKNOWN
        } catch (_: Throwable) {
            // 防御：非 IO 运行时异常（如解析/JSON 错误）也归为 UNKNOWN，
            // 避免冒泡到 probe 线程直接崩溃（启动探测应永不 crash）。
            SessionHealth.UNKNOWN
        }
    }

    private fun isKnownIdentityRedirect(response: QueryHttpResponse): Boolean =
        isIdentityRedirect(response.url.ifBlank { url }, response.location)

    private fun isKnownLoginForm(body: String): Boolean {
        val page = body.lowercase(Locale.US)
        val hasIdentityMarker = IDENTITY_MARKERS.any(page::contains)
        val hasLoginFormAction = "<form" in page && "action=" in page &&
            LOGIN_ACTION_MARKERS.any(page::contains)
        return hasIdentityMarker && hasLoginFormAction
    }

    /**
     * 判定是否为「签到列表」的正常响应：必须能解析为 JSON 且包含 rollcalls 数组。
     * 仅凭「能解析成 JSON」不足以免签：网关/服务端错误页也可能是 JSON
     * （如 {"code":500,"msg":"..."}），若误判为有效会话会让签到在失效会话上白跑。
     */
    private fun isRollcallListBody(body: String): Boolean = runCatching {
        JSONObject(body).optJSONArray("rollcalls") != null
    }.getOrDefault(false)

    private companion object {
        val IDENTITY_MARKERS = listOf("c-identity.xmu.edu.cn", "ids.xmu.edu.cn", "/auth/realms/xmu/", "pwdencryptsalt")
        val LOGIN_ACTION_MARKERS = listOf("login", "authenticate")
    }
}
