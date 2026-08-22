package com.xmu.assistant

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class XmuScoreAutoQueryClient internal constructor(
    private val username: String,
    private val password: String,
    cookieHeader: String = "",
    private val transport: QueryHttpTransport = OkHttpQueryTransport(),
    private val base64Encoder: (ByteArray) -> String = {
        Base64.encodeToString(it, Base64.NO_WRAP)
    },
    private val executorFactory: (Int) -> ExecutorService = { size ->
        Executors.newFixedThreadPool(size)
    },
    private val mayRelogin: () -> Boolean = { true },
) {
    private val jar = XmuScoreCookieJar().apply { seed(cookieHeader) }
    private val random = SecureRandom()

    fun cookieHeader(): String = jar.header()

    /** 登录链路诊断日志：JVM 单测无 Android Log（抛 Stub），静默降级；release 不发（DEBUG 门控）。 */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d("XmuExamDebug", message) }
    }

    /**
     * 强制建立/续登教务会话（完整 CAS 登录，受进程级单飞门保护），返回最新 cookie。
     * 与成绩链路同构：业务接口判定会话过期后调用；mayRelogin 拒绝时抛 MainSessionExpiredException。
     */
    fun forceAcademicLogin(): String {
        if (!mayRelogin()) throw MainSessionExpiredException()
        loginAndGetToken()
        return jar.header()
    }

    /**
     * Makes sure the existing XMU academic session is usable and returns the
     * cookie jar for other academic services, such as the schedule endpoint.
     */
    fun ensureAcademicSession(): String {
        termsWithSessionFallback()
        return jar.header()
    }

    /**
     * 教务会话保温探测（单请求）：验证既有 Cookie 是否仍有效。
     * 教务会话多为滑动过期——App 启动时轻探一次可保持热态，减少用户打开
     * 成绩/课表页时的完整 CAS 认证链。绝不触发续登（调用方应传 mayRelogin=false
     * 双保险）；返回会话是否有效（网络失败一律视为无效，不区分原因）。
     */
    fun probeAcademicSession(): Boolean =
        runCatching { termsWithSessionFallback().isNotEmpty() }.getOrDefault(false)

    /**
     * Opens the schedule application after establishing the shared academic
     * login. XMU assigns a separate appShow entry to wdkbapp, so a valid score
     * session alone is not sufficient to initialize the schedule application.
     *
     * 提速：不再先探测成绩学期列表（fetchTerms 与课表无关，纯浪费一个请求）；
     * 直接用 appShow 打开课表应用，它自身就能验证会话——会话过期时会重定向
     * 到统一登录页，据此判定是否需要续登。已知学期课表链路从 4 个串行请求
     * 减到 3 个（appShow + 学期列表 + 课表）。
     */
    fun ensureScheduleSession(): String {
        if (jar.header().isBlank()) {
            if (mayRelogin()) loginAndGetToken() else throw MainSessionExpiredException()
            val opened = openScheduleApp()
            // 与已有会话分支同构：登录后仍需确认会话真实落地（密码错误/回登录页时
            // jar 里可能只有 ids cookie 而 jw 会话未建立，不允许返回"已就绪"）。
            if (opened.isScheduleLoginLanding()) throw MainSessionExpiredException()
            return jar.header()
        }
        val opened = openScheduleApp()
        if (opened.isScheduleLoginLanding()) {
            if (!mayRelogin()) throw MainSessionExpiredException()
            loginAndGetToken()
            // 续登后同样校验会话落地：若仍回登录页，说明续登未真正建立 jw 会话，
            // 立即抛会话过期（避免调用方拿"未就绪"的 cookie 继续请求 → 再次触发续登，
            // 造成单次操作两次 CAS 登录，风控暴露翻倍）。
            if (openScheduleApp().isScheduleLoginLanding()) throw MainSessionExpiredException()
        }
        return jar.header()
    }

    private fun openScheduleApp(): ScoreResponse =
        follow(
            "https://jw.xmu.edu.cn/appShow?appId=$SCHEDULE_APP_ID",
            operation = NetworkOperation.SCHEDULE,
        )

    /**
     * Uses only an already-valid academic session. It never sends identity
     * credentials and deliberately reports one opaque failure class instead
     * of attempting a recovery login.
     */
    fun fetchScoresFromExistingSession(): List<XmuScoreRecord> {
        if (jar.header().isBlank()) throw ExistingScoreSessionUnavailable()
        val terms = runCatching { fetchTerms() }.getOrElse { throw ExistingScoreSessionUnavailable() }
            .mapNotNull { term ->
                val termCode = term.optString("XNXQDM")
                termCode.takeIf { it.isNotBlank() }?.let { ScoreTerm(it, term.optString("XNXQDM_DISPLAY")) }
            }
        val results = fetchTermWave(terms)
        if (results.any { it.error != null }) throw ExistingScoreSessionUnavailable()
        return results.flatMap { result ->
            result.rows.orEmpty().map { score -> score.toScoreRecord(result.term.code, result.term.name) }
        }.distinctBy { distinctKeyOf(it) }
    }

    internal fun fetchScores(): ScoreFetchResult {
        val terms = termsWithSessionFallback()
            .mapNotNull { term ->
                val termCode = term.optString("XNXQDM")
                termCode.takeIf { it.isNotBlank() }?.let {
                    ScoreTerm(termCode, term.optString("XNXQDM_DISPLAY"))
                }
            }
        val firstWave = fetchTermWave(terms)
        val failedTerms = firstWave.filter { it.error != null }.map { it.term }
        // 部分成绩项失败时，仅当存在「会话失效类」失败（ScoreSessionExpiredException）才重登重试；
        // 纯网络抖动（IOException）与格式异常（ScoreJsonFormatException）都保持失败：
        //   - IOException：弱网时避免触发重复 CAS 登录（风控红线）
        //   - 格式异常（含 5xx/非鉴权 4xx/服务端坏页）：数据或服务问题，重登也无法修复，登录只会白打一次
        val reloginNeeded = firstWave.any { it.error is ScoreSessionExpiredException }
        val retriedByCode = if (failedTerms.isEmpty() || !reloginNeeded || !mayRelogin()) {
            emptyMap()
        } else {
            loginAndGetToken()
            fetchTermWave(failedTerms).associateBy { it.term.code }
        }
        val failures = mutableListOf<String>()
        var firstFailure: Throwable? = null
        val records = firstWave.flatMap { first ->
            val final = if (first.error == null) first else retriedByCode[first.term.code] ?: first
            val rows = final.rows
            if (rows == null) {
                if (firstFailure == null) firstFailure = final.error
                failures += "${final.term.name.ifBlank { final.term.code }}:${final.error?.message.orEmpty()}"
                emptyList()
            } else {
                rows.map { score -> score.toScoreRecord(final.term.code, final.term.name) }
            }
        }.distinctBy { distinctKeyOf(it) }
        if (records.isEmpty() && failures.isNotEmpty()) {
            // 全部学期失败：抛出第一个真实错误，让上层正确区分网络/会话/格式问题，
            // 而不是统一误报为「格式异常」（弱网时用户会看到错误的失败原因）。
            throw firstFailure ?: error("成绩接口返回格式异常")
        }
        return ScoreFetchResult(records, failures.map { it.substringBefore(":") })
    }

    /** 去重键：含课序号（KXH），同学期同课程重修/补考/多次开课不会被误删。 */
    private fun distinctKeyOf(record: XmuScoreRecord): String =
        "${record.term}|${record.courseCode}|${record.courseSeq}|${record.courseName}|${record.score}|${record.resultText}"

    private fun termsWithSessionFallback(): List<JSONObject> {
        if (jar.header().isBlank()) {
            if (mayRelogin()) loginAndGetToken() else throw MainSessionExpiredException()
            return fetchTerms()
        }
        return try {
            fetchTerms()
        } catch (error: Throwable) {
            // 仅「会话失效」（ScoreSessionExpiredException：401/403/重定向 ids/登录页特征）才续登；
            // 网络抖动/格式异常（含 5xx、非鉴权 4xx、服务端坏页）直接抛出，
            // 避免服务端或网络问题触发重复 CAS 登录（风控红线）；
            // 会话世代已变化（用户已登出 / 已重新登录）也直接抛出，陈旧请求禁止续登。
            if (error is ScoreSessionExpiredException && mayRelogin()) {
                loginAndGetToken()
                return fetchTerms()
            }
            throw error
        }
    }

    private fun fetchTermWave(terms: List<ScoreTerm>): List<TermRowsResult> =
        boundedParallelMap(
            items = terms,
            maxParallel = MAX_PARALLEL_TERMS,
            executorFactory = executorFactory,
        ) { term ->
            runCatching { fetchRows(term.code) }
                .fold(
                    onSuccess = { rows -> TermRowsResult(term, rows, null) },
                    onFailure = { error -> TermRowsResult(term, null, error) },
                )
        }

    private fun JSONObject.toScoreRecord(termCode: String, termName: String): XmuScoreRecord {
        // Copied from docs/vendor/XMUScoreAutoQuery/app.py:
        // if score['DJCJLXDM_DISPLAY'] == '百分制' and ...
        val isPercentScore = optString("DJCJLXDM_DISPLAY") == "百分制"
        val rawScore = optString("ZCJ").trim()
        val numericScore = rawScore.toDoubleOrNull()
        val officialPoint = optString("XFJD").trim().toDoubleOrNull()
        return XmuScoreRecord(
            courseCode = optString("KCH"),
            courseName = optString("KCM"),
            term = termName,
            termCode = termCode,
            credit = optString("XF").toDoubleOrNull() ?: 0.0,
            score = if (isPercentScore) numericScore else null,
            gradePoint = if (isPercentScore) officialPoint else null,
            gradeMode = scoreGradeMode(this),
            resultText = if (isPercentScore) rawScore else nonPercentResultText(this, rawScore),
            countsForStatistics = isPercentScore && numericScore != null && officialPoint != null,
            countsForCompletedCredit = earnsCompletedCredit(this, rawScore),
            // 课序号：同学期同课程多次开课/重修/补考用 KXH 区分（去重键依赖，避免误删合法记录）
            courseSeq = optString("KXH"),
        )
    }

    private fun nonPercentResultText(score: JSONObject, rawScore: String): String {
        if (isTwoLevelMode(score.optString("DJCJLXDM_DISPLAY"))) {
            return if (earnsCompletedCredit(score, rawScore)) "P" else "NP"
        }
        val candidates = listOf(
            "ZCJ_DISPLAY",
            "CJ_DISPLAY",
            "DJCJ_DISPLAY",
            "DJCJ",
            "DJMC",
            "CJ",
        )
        val officialText = candidates.firstNotNullOfOrNull { key ->
            score.optString(key).trim().takeIf { it.isNotBlank() && it.toDoubleOrNull() == null }
        }
        return officialText ?: score.optString("DJCJLXDM_DISPLAY").ifBlank { rawScore.ifBlank { "已通过" } }
    }

    private fun scoreGradeMode(score: JSONObject): String {
        val mode = score.optString("DJCJLXDM_DISPLAY")
        return if (isTwoLevelMode(mode)) "P/NP" else mode
    }

    private fun isTwoLevelMode(mode: String): Boolean =
        mode == "两级制" || mode.equals("P/NP", ignoreCase = true)

    private fun earnsCompletedCredit(score: JSONObject, rawScore: String): Boolean {
        val text = listOf(
            rawScore,
            score.optString("ZCJ_DISPLAY"),
            score.optString("CJ_DISPLAY"),
            score.optString("DJCJ_DISPLAY"),
            score.optString("DJCJ"),
            score.optString("DJMC"),
        ).joinToString(" ")
        if (listOf("不及格", "不合格", "不通过", "NP", "FAIL", "W", "DF", "缓考").any { it in text }) return false
        return rawScore.toDoubleOrNull()?.let { it >= 60.0 } ?: true
    }

    private fun loginAndGetToken() {
        // academic 域 CAS 登录跨模块单飞：成绩/课表/恢复共用同一把进程级门，
        // 已在途则直接抛会话过期（上层走恢复），绝不同时打两个身份域登录（风控红线）。
        if (!ProcessSessionRecovery.coordinator.tryStartAcademicCasLogin()) {
            throw MainSessionExpiredException()
        }
        try {
            // 登录冷却（进程级）：服务端限流（登录页异常/登录失败）后短时间内不再尝试登录，
            // 避免限流窗口内反复打 CAS 登录延长风控（2026-08-17 体检：403 循环 bug 曾导致
            // 同账号短时间 20+ 次登录被 ids 限流，登录页不再返回表单）。
            // 时间源用 System.nanoTime()：单调时钟（用户改系统时间无法绕过冷却，
            // 且 JVM 单测可用——SystemClock 在 JVM 抛 not mocked）。
            val sinceBlocked = System.nanoTime() - lastLoginBlockedAtNanos
            if (sinceBlocked < LOGIN_BLOCKED_COOLDOWN_NANOS) {
                throw AcademicLoginBlockedException("教务登录请求过于频繁，请约 30 分钟后重试")
            }
            jar.clear()
            val oauthLoginUrl = "https://ids.xmu.edu.cn/authserver/login?type=userNameLogin&service=https://jw.xmu.edu.cn/login?service=https://jw.xmu.edu.cn/new/index.html"
            val page = request(oauthLoginUrl)
            val salt = extractInput(page.body, "pwdEncryptSalt", useId = true)
            debugLog("login: page code=${page.code} saltLen=${salt.length} ltLen=${extractInput(page.body, "lt").length} len=${page.body.length}")
            debugLog("login: pageHead=${page.body.take(500).replace('\n', ' ').replace('\r', ' ')}")
            // 登录页缺少密码盐：页面不是正常登录表单（限流提示页/异常页/维护页）。
            // 绝不继续（空盐加密会崩成误导性异常），也绝不在本窗口内重试登录（风控红线）。
            if (salt.isBlank()) {
                lastLoginBlockedAtNanos = System.nanoTime()
                throw AcademicLoginBlockedException("教务登录请求过于频繁，请约 30 分钟后重试")
            }
            val loginData = form(
                "username" to username,
                "password" to encryptAES(password, salt),
                "lt" to extractInput(page.body, "lt"),
                "dllt" to extractInput(page.body, "dllt"),
                "execution" to extractInput(page.body, "execution"),
                "_eventId" to "submit",
                "rmShown" to "1",
            )
            val posted = follow(page.url, method = "POST", body = loginData)
            debugLog("login: posted final=${posted.url.substringAfter("xmu.edu.cn")} code=${posted.code}")
            // 提交后仍停在统一登录页（认证未通过：密码错误/风控拦截/验证码）：
            // 视为登录未完成——冷却 + 类型化抛出（密码错重试无意义，风控拦截重试更危险）。
            if (posted.isScheduleLoginLanding()) {
                lastLoginBlockedAtNanos = System.nanoTime()
                throw AcademicLoginBlockedException("教务登录未完成，请稍后再试")
            }
            // Copied from docs/vendor/XMUScoreAutoQuery/app.py:
            // session.get("https://jw.xmu.edu.cn/appShow?appId=4768574631264620", allow_redirects=True)
            val appShow = follow("https://jw.xmu.edu.cn/appShow?appId=4768574631264620")
            debugLog("login: appShow final=${appShow.url.substringAfter("xmu.edu.cn")} code=${appShow.code} jarLen=${jar.header().length}")
            // 登录成功但应用入口仍回登录页（jw 会话未建立，如 545 中间态）：
            // 同上处理——会话未落地，冷却 + 类型化抛出，绝不把无效 cookie 交回上层。
            if (appShow.isScheduleLoginLanding()) {
                lastLoginBlockedAtNanos = System.nanoTime()
                throw AcademicLoginBlockedException("教务登录未完成，请稍后再试")
            }
        } finally {
            ProcessSessionRecovery.coordinator.finishAcademicCasLogin()
        }
    }

    private fun fetchTerms(): List<JSONObject> =
        rowsFromDataBlock(
            postForm(
                // Copied from docs/vendor/XMUScoreAutoQuery/app.py:
                // session.post("https://jw.xmu.edu.cn/jwapp/sys/cjcx/modules/cjcx/cxycjdxnxq.do", data={"XH": username})
                "https://jw.xmu.edu.cn/jwapp/sys/cjcx/modules/cjcx/cxycjdxnxq.do",
                form("XH" to username),
            ),
            "cxycjdxnxq",
        )

    private fun fetchRows(termCode: String): List<JSONObject> =
        rowsFromDataBlock(
            postForm(
                // Copied from docs/vendor/XMUScoreAutoQuery/app.py:
                // session.post("https://jw.xmu.edu.cn/jwapp/sys/cjcx/modules/cjcx/xscjcx.do", data=parse.urlencode(query_template))
                "https://jw.xmu.edu.cn/jwapp/sys/cjcx/modules/cjcx/xscjcx.do",
                form(
                    "querySetting" to queryTemplate(termCode),
                    "*order" to "-XNXQDM,-KCH,-KXH",
                ),
            ),
            "xscjcx",
        )

    private fun queryTemplate(termCode: String): String {
        val escapedTerm = termCode.replace("\\", "\\\\").replace("'", "\\'")
        return "[{'name': 'SFYX', 'caption': '是否有效', 'linkOpt': 'AND', 'builderList': 'cbl_m_List', 'builder': 'm_value_equal', 'value': '1', 'value_display': '是'}, {'name': 'SHOWMAXCJ', 'caption': '显示最高成绩', 'linkOpt': 'AND', 'builderList': 'cbl_m_List', 'builder': 'm_value_equal', 'value': '0', 'value_display': '否'}, {'name': 'XNXQDM', 'linkOpt': 'AND', 'builder': 'equal', 'value': '$escapedTerm'}]"
    }

    private fun encryptAES(data: String, salt: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(randstr(16).toByteArray(Charsets.UTF_8)),
        )
        return base64Encoder(cipher.doFinal((randstr(64) + data).toByteArray(Charsets.UTF_8)))
    }

    private fun randstr(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (0 until length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun extractInput(html: String, name: String, useId: Boolean = false): String {
        val key = if (useId) "id" else "name"
        return Regex("""$key=["']${Regex.escape(name)}["'][^>]*value=["']([^"']*)["']""")
            .find(html)
            ?.groupValues
            ?.get(1)
            ?: ""
    }

    private fun postForm(url: String, body: String): String {
        val response = follow(url, method = "POST", body = body)
        // 风控红线：只有「真会话失效」（401/403/重定向到统一登录域/登录页特征）才判过期触发续登；
        // 5xx、非鉴权 4xx、服务端坏页一律归为格式异常（ScoreJsonFormatException），绝不触发 CAS 重登。
        if (response.isScoreSessionExpired()) throw ScoreSessionExpiredException()
        if (response.code !in 200..299) throw ScoreJsonFormatException("成绩接口暂时不可用")
        return response.body
    }

    private fun ScoreResponse.isScoreSessionExpired(): Boolean {
        if (code == 401 || code == 403) return true
        val finalUrl = url.lowercase(Locale.US)
        if ("ids.xmu.edu.cn" in finalUrl || "authserver/login" in finalUrl) return true
        val page = body.lowercase(Locale.US)
        return page.trimStart().let { it.startsWith("<!doctype html") || it.startsWith("<html") } &&
            listOf("pwdencryptsalt", "authserver/login", "ids.xmu.edu.cn", "请先登录", "未登录").any { it in page }
    }

    private fun follow(
        url: String,
        method: String = "GET",
        body: String = "",
        operation: NetworkOperation = NetworkOperation.SCORES,
    ): ScoreResponse {
        var current = url
        var nextMethod = method
        var nextBody = body
        repeat(MAX_REDIRECTS) {
            val response = request(current, nextMethod, nextBody, operation)
            if (response.code in 300..399 && !response.location.isNullOrBlank()) {
                current = URL(URL(current), response.location).toString()
                nextMethod = "GET"
                nextBody = ""
                return@repeat
            }
            return response.copy(url = current)
        }
        // 重定向循环耗尽：若终点仍停在统一登录域（ids/authserver），说明是会话过期反复 bounce，
        // 应触发续登（抛会话失效）；若循环发生在教务域内部，属服务端坏跳转（数据性问题），
        // 归为格式异常，避免白打一次 CAS 登录（风控红线）。
        val lowered = current.lowercase(Locale.US)
        if ("ids.xmu.edu.cn" in lowered || "authserver/login" in lowered) {
            throw ScoreSessionExpiredException()
        }
        throw ScoreJsonFormatException("教务跳转次数过多")
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: String = "",
        operation: NetworkOperation = NetworkOperation.SCORES,
    ): ScoreResponse {
        val headers = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
            "Connection" to "keep-alive",
            "Referer" to "https://jw.xmu.edu.cn/new/index.html",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15",
        )
        jar.header().takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        val response = transport.execute(
            QueryHttpRequest(
                url = url,
                method = method,
                headers = headers,
                contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                body = body,
                operation = operation,
            ),
        )
        jar.read(response.headers)
        return ScoreResponse(url, response.code, response.location, response.body)
    }

    private fun rowsFromDataBlock(text: String, blockName: String): List<JSONObject> {
        val root = runCatching {
            JSONTokener(text.trim().removePrefix("\uFEFF")).nextValue() as? JSONObject
        }.getOrElse {
            throw ScoreJsonFormatException("成绩接口返回格式异常")
        } ?: return emptyList()
        val rows = root.optJSONObject("datas")
            ?.optJSONObject(blockName)
            ?.optJSONArray("rows")
            ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            "${encodeFormComponent(key)}=${encodeFormComponent(value)}"
        }

    private fun encodeFormComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("*", "%2A")

    private fun ScoreResponse.isScheduleLoginLanding(): Boolean {
        val finalUrl = url.lowercase(Locale.US)
        if ("ids.xmu.edu.cn" in finalUrl || "authserver/login" in finalUrl) return true
        val page = body.lowercase(Locale.US)
        return page.trimStart().let { it.startsWith("<!doctype html") || it.startsWith("<html") } &&
            listOf("pwdencryptsalt", "authserver/login", "请先登录", "未登录").any { it in page }
    }

    private data class ScoreResponse(val url: String, val code: Int, val location: String?, val body: String)

    private data class ScoreTerm(val code: String, val name: String)

    private data class TermRowsResult(
        val term: ScoreTerm,
        val rows: List<JSONObject>?,
        val error: Throwable?,
    )

    companion object {
        private const val MAX_REDIRECTS = 16
        // 成绩按学期并行拉取的最大并发数。真实场景 6-8 个学期，
        // 3 并发需 2-3 波串行（约 7 秒）；5 并发压到 1-2 波（约 4-5 秒）。
        // 对同一会话 5 个并发请求仍属温和，风控风险低。
        private const val MAX_PARALLEL_TERMS = 5
        private const val SCHEDULE_APP_ID = "4979568947762216"

        /** 登录被服务端拒绝/限流后的进程级冷却：窗口内不再尝试 CAS 登录（防反复打登录延长风控）。
         *  时间源 System.nanoTime()：单调时钟，JVM 单测可用，用户改系统时间无法绕过。 */
        @Volatile
        private var lastLoginBlockedAtNanos = 0L

        private const val LOGIN_BLOCKED_COOLDOWN_NANOS = 30 * 60 * 1_000_000_000L

        /** 测试隔离：重置登录冷却（JVM 单测共享进程级状态，避免用例间污染）。 */
        internal fun clearLoginBlockedForTests() {
            lastLoginBlockedAtNanos = 0L
        }

        internal fun existingSessionOnly(
            cookieHeader: String,
            transport: QueryHttpTransport = OkHttpQueryTransport(),
            executorFactory: (Int) -> ExecutorService = { size -> Executors.newFixedThreadPool(size) },
        ) = XmuScoreAutoQueryClient(
            username = "",
            password = "",
            cookieHeader = cookieHeader,
            transport = transport,
            executorFactory = executorFactory,
        )
    }
}

internal class ExistingScoreSessionUnavailable : IllegalStateException()

/** 成绩拉取结果：记录 + 失败学期名列表（部分学期失败时上层可提示，避免静默缺项）。 */
internal data class ScoreFetchResult(
    val records: List<XmuScoreRecord>,
    val failedTermNames: List<String> = emptyList(),
)

/**
 * 登录页异常/被服务端拒绝（如限流提示页、验证码页、维护页——登录页缺少密码盐）。
 * 与"会话失效"严格区分：**绝不触发重试登录**（重试只会增加登录次数，延长风控；
 * 且空盐加密会抛误导性的 IllegalArgumentException）。
 */
internal class AcademicLoginBlockedException(message: String = "教务登录请求过于频繁，请稍后再试") : IllegalStateException(message)

/**
 * 成绩接口「真会话失效」（401/403/重定向到统一登录域/登录页特征）。
 * 与格式/网络异常严格区分：**只有它允许触发 academic CAS 续登**（风控红线：
 * 5xx、非鉴权 4xx、服务端坏页、网络抖动绝不重登）。
 */
internal class ScoreSessionExpiredException : IllegalStateException("成绩接口登录态失效")

/**
 * 成绩接口返回了无法解析的数据（HTML 错误页、JSON 缺字段、非法 UTF 等）。
 * 与"会话失效"严格区分：格式异常属于服务端数据问题，**绝不触发 CAS 重登**
 * （重登也无法修复数据问题，只会白白增加登录次数，触碰风控红线）。
 */
internal class ScoreJsonFormatException(message: String? = null) : IllegalStateException(message)

internal class XmuScoreCookieJar {
    private val cookies = linkedMapOf<String, String>()

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    @Synchronized
    fun seed(header: String) {
        header.split(";")
            .map { it.trim() }
            .filter { "=" in it }
            .forEach { raw -> cookies[raw.substringBefore("=")] = raw }
    }

    @Synchronized
    fun read(headers: Map<String, List<String>>) {
        headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { raw ->
                val pair = raw.substringBefore(";")
                val name = pair.substringBefore("=", "")
                if (name.isNotBlank()) cookies[name] = pair
            }
    }

    @Synchronized
    fun header(): String = cookies.values.joinToString("; ")
}
