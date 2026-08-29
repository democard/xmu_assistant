package com.xmu.assistant

import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 教务 HTTP 公共基座：把「exam/score 确实同构」的部分收敛为一处，使判定不可能再漂移。
 *
 * - redirect follower：MAX_REDIRECTS 循环 + 登录域终止判定；关键域标记做成参数集合，
 *   各客户端传入自己的集合（exam 含 Keycloak 显式化，score 保持现状清单）；
 * - request header 模板与 jar 读写（exam/score 同款模板本体，仅 Referer 不同 → 参数化；
 *   exam 的 POST 变体头经 applyPostHeaders 显式开启）；
 * - form urlEncode/post 编码（exam/schedule 同款 plain-URLEncoder 口径；score 的
 *   encodeFormComponent 带 `*`→%2A 语义不同，保留在其客户端内不并入）。
 *
 * 网络口径红线：超时/UA/Referer/header 集/并发参数一律原值透传，本基座不改变任何
 * 请求形态；异常类型由各客户端经 onLoginTerminus/onExhaustedNonLogin/networkWrapper
 * 注入原样工厂，不让异常语义跨模块串味。
 */
internal class JwHttpBase(
    private val transport: QueryHttpTransport,
    private val jar: XmuScoreCookieJar? = null,
    private val networkWrapper: (IOException) -> Throwable = { it },
) {
    /** form urlEncode/post 编码（exam:409-412 ≡ schedule:156-158 的同款实现）。 */
    fun formEncode(form: Map<String, String>): String =
        form.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * 请求头模板与 jar 读写（score:428-457 同款模板本体）。
     * applyPostHeaders：exam 的 POST 变体头（Content-Type/X-Requested-With 等），
     * score 模板对任何方法不区分——保持各自现状。
     */
    fun request(
        url: String,
        method: String = "GET",
        body: String = "",
        referer: String,
        operation: NetworkOperation,
        applyPostHeaders: Boolean = false,
    ): QueryHttpResponse {
        val headers = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
            "Connection" to "keep-alive",
            "Referer" to referer,
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to JwSessionMarkers.USER_AGENT,
        )
        if (applyPostHeaders && method != "GET") {
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            headers["Accept"] = "application/json, text/plain, */*"
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
        }
        jar?.header()?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        val response = try {
            transport.execute(
                QueryHttpRequest(
                    url = url,
                    method = method,
                    headers = headers,
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    body = body,
                    operation = operation,
                ),
            )
        } catch (error: IOException) {
            throw networkWrapper(error)
        }
        jar?.read(response.headers)
        return response
    }

    /**
     * 跟随重定向链（exam/score follow 同构收编）。重定向循环耗尽时按当前落点
     * 是否命中 loginTerminators 分派两类异常工厂——判定条件本体只有这一份。
     * responseDecorator：score 需要 copy(url = 当前落点) 携带最终 URL，exam 原样返回。
     */
    /** exam 用的原样返回重载（装饰器缺省 = 响应原样）。 */
    fun follow(
        url: String,
        method: String = "GET",
        body: String = "",
        operation: NetworkOperation,
        referer: String,
        maxRedirects: Int,
        loginTerminators: Set<String>,
        onLoginTerminus: (String) -> Throwable,
        onExhaustedNonLogin: () -> Throwable,
    ): QueryHttpResponse =
        followDecorated(url, method, body, operation, referer, maxRedirects, loginTerminators, onLoginTerminus, onExhaustedNonLogin) { _, response -> response }

    /** score 用泛型版本：装饰器把最终落点 URL 装回自身响应类型（copy(url = current)）。 */
    fun <T> followDecorated(
        url: String,
        method: String = "GET",
        body: String = "",
        operation: NetworkOperation,
        referer: String,
        maxRedirects: Int,
        loginTerminators: Set<String>,
        onLoginTerminus: (String) -> Throwable,
        onExhaustedNonLogin: () -> Throwable,
        responseDecorator: (String, QueryHttpResponse) -> T,
    ): T {
        var current = url
        var nextMethod = method
        var nextBody = body
        repeat(maxRedirects) {
            val response = request(current, nextMethod, nextBody, referer = referer, operation = operation)
            if (response.code in 300..399 && !response.location.isNullOrBlank()) {
                current = URL(URL(current), response.location).toString()
                nextMethod = "GET"
                nextBody = ""
                return@repeat
            }
            return responseDecorator(current, response)
        }
        // 重定向循环耗尽：停在登录域 = 会话过期（CASTGC 也失效）；
        // 教务域内部循环属服务端坏跳转（数据性问题），由调用方按数据异常收场。
        val lowered = current.lowercase(Locale.US)
        loginTerminators.find { it in lowered }?.let { throw onLoginTerminus(it) }
        throw onExhaustedNonLogin()
    }
}

/** 教务共享常量与登录页判定（批次二：分类器单点化）。 */
internal object JwSessionMarkers {
    /** 桌面/移动共享的移动端 UA（exam/score 模板原值，禁止改动：A7 风控敏感面）。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15"

    // 关键域标记（来源：d518e20 双端会话判定对齐清单 + SessionHealth.IDENTITY_MARKERS）。
    // 域集合原先散落在 exam follow 终止判定 / 各 isLoginPage 私有实现里，已出现漂移
    // （exam 认 Keycloak，score 不认）；集中一处显式化，各客户端按现状取子集传入。
    const val IDS_DOMAIN = "ids.xmu.edu.cn"
    const val AUTHSERVER_LOGIN = "authserver/login"
    const val C_IDENTITY_DOMAIN = "c-identity.xmu.edu.cn"
    const val KEYCLOAK_REALMS = "/auth/realms/xmu/"

    /** exam follow 终止域全集（CAS ids 域 + TronClass Keycloak 身份域）。 */
    val EXAM_FOLLOW_TERMINATORS = setOf(
        IDS_DOMAIN, AUTHSERVER_LOGIN, C_IDENTITY_DOMAIN, KEYCLOAK_REALMS,
    )

    /** score follow 终止域现状清单（未含 Keycloak：成绩链路语义冻结，逐字保持）。 */
    val SCORE_FOLLOW_TERMINATORS = setOf(IDS_DOMAIN, AUTHSERVER_LOGIN)

    /** 登录页 HTML 特征词（exam.isLoginPage ≡ schedule.isAcademicLoginResponse 逐字合并）。 */
    private val LOGIN_PAGE_MARKERS = listOf(
        "pwdencryptsalt", "authserver/login", "ids.xmu.edu.cn", "请先登录", "未登录",
    )

    /**
     * 登录页/身份域响应判定（401/403 状态码由各客户端薄封装补上）。
     * 红线约束：合并只是消除三处实现漂移的可能，判定结果与各客户端
     * 改动前在其自有测试中的行为逐一一致——条件与清单逐字未动。
     */
    fun isAcademicLoginResponse(response: QueryHttpResponse): Boolean {
        val redirect = response.location.orEmpty().lowercase(Locale.US)
        if (IDS_DOMAIN in redirect || AUTHSERVER_LOGIN in redirect) return true
        // HTML 页只有带登录页特征才算会话失效；普通服务端坏页/维护页/WAF 页不是
        // （避免误触发 CAS 重登）。
        val page = response.body.lowercase(Locale.US)
        return page.trimStart().let { it.startsWith("<!doctype html") || it.startsWith("<html") } &&
            LOGIN_PAGE_MARKERS.any { it in page }
    }

    /**
     * 401/403 + 登录页复合会话失效判定（exam/schedule 原两处薄封装逐字等价合并）。
     * 红线：仅消除两处复写的漂移面，条件本体（状态码集合 + 登录页分类器）逐字未动；
     * score 的 isScoreSessionExpired 是独立判定面，冻结不并入。
     */
    fun isAuthFailure(code: Int, response: QueryHttpResponse): Boolean =
        code == 401 || code == 403 || isAcademicLoginResponse(response)
}

/**
 * 教务链路诊断日志单点（exam/score 原各自逐字复制一份，B2 收敛）：
 * JVM 单测无 Android Log（抛 Stub）→ runCatching 静默降级；release 不发（DEBUG 门控）。
 * tag 沿用历史值 "XmuExamDebug"（exam 首创后被 score 复制，保持日志过滤习惯不变）。
 */
internal object JwDebugLog {
    private const val TAG = "XmuExamDebug"

    fun d(message: String) {
        if (BuildConfig.DEBUG) runCatching { android.util.Log.d(TAG, message) }
    }
}
