package com.xmu.assistant

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate

/** 考试安排数据模型（纯数据，不含任何账号个人信息）。 */
data class XmuExam(
    /** 考试任务 id（用于提醒去重）。 */
    val id: String,
    /** 课程名（中文）。 */
    val courseName: String,
    /** 考试日期，如 2026-06-14。 */
    val date: String,
    /** 时间段，如 08:00-10:00。 */
    val timeRange: String,
    /** 教室，线上考试为空。 */
    val room: String,
    /** 线下/线上。 */
    val mode: String,
    /** 考试名称（学期期末考试等）。 */
    val examName: String,
)

/** 未安排考试的课程（普适：不含个人信息）。 */
data class XmuExamUnarranged(
    val courseName: String,
)

/** 一个学期的考试汇总：已排考试 + 未安排课程数。 */
data class XmuTermExamSummary(
    val termCode: String,
    val termLabel: String,
    val exams: List<XmuExam>,
    val unarranged: List<XmuExamUnarranged>,
)

internal class ExamSessionExpiredException : IllegalStateException("教务登录已过期")

internal class ExamNetworkException(cause: Throwable) :
    IllegalStateException("考试安排网络连接失败", cause)

internal class ExamResponseException(message: String = "考试安排接口返回格式异常", cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** 登录/恢复在途时考试请求被拒（风控红线：不并发打两个身份域），提示稍候重试而非引导重登。 */
internal class ExamLoginInProgressException : IllegalStateException("登录处理中，请稍候")

/** 探测结果：有效学期列表（新→旧，最近在前）+ 最近有效学期数据（probe 已拉取，供上层复用，省一次请求）
 *  + 窗口内全部有效学期数据（供上层整体缓存，切学期/跨学期提醒聚合复用）。 */
internal data class ExamProbeResult(
    val terms: List<String>,
    val latest: XmuTermExamSummary?,
    val summaries: Map<String, XmuTermExamSummary> = emptyMap(),
)

/** 考试安排客户端：复用教务会话（cookie），会话过期时与课表/成绩同构地安全续登。 */
// 学期代码：2025-2026-2（学年-学年-学期号）；逐行/逐学期解析路径上复用同一编译结果
private val TERM_CODE_FULL_PATTERN = Regex("""^(\d{4})-(\d{4})-(\d)$""")
private val TERM_CODE_STRICT_PATTERN = Regex("""^(\d{4})-(\d{4})-([123])$""")
private val TIME_RANGE_PATTERN = Regex("""(\d{2}:\d{2})-(\d{2}:\d{2})""")
private val STUDENT_ID_YEAR_PATTERN = Regex("""^\d{3}(20\d{2})\d+$""")

internal class XmuExamClient(
    private val cookieHeader: String,
    private val username: String = "",
    private val password: String = "",
    private val mayRelogin: () -> Boolean = { true },
    private val transport: QueryHttpTransport = OkHttpQueryTransport(),
    private val base64Encoder: (ByteArray) -> String = {
        android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
    },
) {
    // 考试应用（studentWdksapApp）是独立的 jwapp 应用：登录后需先打开应用入口，
    // 由服务端下发应用级会话 cookie（成绩/课表应用在各自刷新链路里已初始化过）。
    private val jar = XmuScoreCookieJar().apply { seed(cookieHeader) }
    // 跨线程可见性：并行首拉/窗口探测多线程共读写（续登后复位），@Volatile 保证可见
    @Volatile
    private var appOpened = false

    /** 会话续登后的最新 cookie（供上层持久化）。 */
    fun currentCookie(): String = jar.header()

    /** 考试链路诊断日志（精简）：JVM 单测无 Android Log（抛 Stub），静默降级；release 不发（DEBUG 门控）。 */
    private fun examDebug(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d("XmuExamDebug", message) }
    }

    /**
     * 本地时间推断的当前学期（含第三学期，如 2026-08 → 2025-2026-3）。
     * 仅作回退路径（教务学期列表/学号不可用）的探测起点；主路径以教务列表为准。
     */
    fun currentTermCode(today: LocalDate = LocalDate.now()): String {
        val year = today.year
        val month = today.monthValue
        return when (month) {
            // 1-6 月：当前学年的第二学期（1-2 月属上一学年第二学期）
            in 1..6 -> "${year - 1}-${year}-2"
            // 7-8 月：第三学期（夏季短学期）
            in 7..8 -> "${year - 1}-${year}-3"
            // 9-12 月：本学年的第一学期
            else -> "$year-${year + 1}-1"
        }
    }

    /**
     * 拉取某学期考试安排（已排 + 未安排）。
     * 只读查询；会话过期时内部自动安全续登（进程级单飞门，与课表/成绩同构），
     * 网络/格式异常分类明确不触发重登（风控红线）。
     */
    fun fetchTermExams(termCode: String): XmuTermExamSummary? {
        // 参照成绩 fetchScores 的「并行首拉 + 统一续登一次 + 全部重试」模式：
        // 两个接口并行请求；任一判定会话过期 → 统一续登一次（进程级单飞门保护）
        // → 两个接口都用新 cookie 重试。避免并行线程各自抢门续登
        // （一个成功一个被拒导致整体失败、新 cookie 无法写回的死循环）。
        val first = requestTermData(termCode)
        examDebug("fetch: term=$termCode first=[${first.joinToString { it.exceptionOrNull()?.javaClass?.simpleName ?: "ok" }}]")
        if (first.any { it.exceptionOrNull() is ExamSessionExpiredException }) {
            forceAcademicLoginOnce()
            appOpened = false
            return buildTermSummary(termCode, requestTermData(termCode))
        }
        return buildTermSummary(termCode, first)
    }

    /** 并行拉取已排 + 未安排（单学期从 2 个串行 RTT 减到 1 个）。 */
    private fun requestTermData(termCode: String): List<Result<out List<*>>> =
        boundedParallelMap(
            items = listOf("exams", "unarranged"),
            maxParallel = 2,
        ) { kind ->
            when (kind) {
                "exams" -> runCatching<List<XmuExam>> {
                    parseExams(post("cxxsksap.do", mapOf("XNXQDM" to termCode, "pageSize" to "100", "pageNumber" to "1")))
                }
                else -> runCatching<List<XmuExamUnarranged>> {
                    parseUnarranged(post("cxyxkwapkwdkc.do", mapOf("XNXQDM" to termCode, "pageSize" to "100", "pageNumber" to "1")))
                }
            }
        }

    /** 汇总两接口结果：会话/网络异常明确抛出；格式异常也抛出（不伪装成"无数据"）；真空学期返回 null。 */
    private fun buildTermSummary(termCode: String, results: List<Result<out List<*>>>): XmuTermExamSummary? {
        @Suppress("UNCHECKED_CAST")
        val exams = (results[0] as Result<List<XmuExam>>).getOrElse { error ->
            if (error is ExamSessionExpiredException || error is ExamNetworkException ||
                error is AcademicLoginBlockedException
            ) throw error
            // 格式/解析异常是服务端数据问题：明确抛出（上层显示"接口返回异常"），
            // 绝不静默成"暂无安排"；只有合法 JSON 且 rows 空才视为真空学期。
            throw ExamResponseException(cause = error)
        }
        @Suppress("UNCHECKED_CAST")
        val unarranged = (results[1] as Result<List<XmuExamUnarranged>>).getOrElse { error ->
            if (error is ExamSessionExpiredException || error is ExamNetworkException ||
                error is AcademicLoginBlockedException
            ) throw error
            throw ExamResponseException(cause = error)
        }
        if (exams.isEmpty() && unarranged.isEmpty()) return null
        return XmuTermExamSummary(
            termCode = termCode,
            termLabel = termLabel(termCode),
            exams = exams,
            unarranged = unarranged,
        )
    }

    /** 完整 CAS 续登一次（进程级单飞门，与成绩/课表同构）；被拒 → 区分原因抛出。 */
    private fun forceAcademicLoginOnce(): String = try {
        examDebug("relogin: start storedLen=${jar.header().length}")
        val renewed = XmuScoreAutoQueryClient(
            username = username,
            password = password,
            cookieHeader = jar.header(),
            transport = transport,
            base64Encoder = base64Encoder,
            mayRelogin = mayRelogin,
        ).forceAcademicLogin()
        jar.clear()
        jar.seed(renewed)
        examDebug("relogin: renewedLen=${renewed.length}")
        renewed
    } catch (error: MainSessionExpiredException) {
        if (mayRelogin()) {
            // 会话世代仍有效但单飞门被占（其他模块 academic 登录在途）：
            // 不引导用户重登（避免重复登录触发风控），提示稍候重试。
            throw ExamResponseException("其他操作正在登录教务，请稍候重试", error)
        }
        // 会话世代已变化（登出/重新登录）：明确会话过期
        throw ExamSessionExpiredException()
    }

    /**
     * 探测有效学期（返回新→旧，最近在前）：
     * - 主路径：教务学期列表（xnxqcx.do）取最近学期为窗口上界；
     *   学号可解析时窗口下界 = 入学年份第一学期（不探测未入学学期，省请求）
     * - 列表拉取失败若是会话过期：与 fetchTermExams 同构，续登一次后重试
     *   （否则回退到本地推断窗口，会漏掉列表才有的未来学期）
     * - 回退路径（列表/学号不可用）：本地时间推断 + 连续空白停止
     * 有效判据 = 已排考试或已选课未安排任一非空（底线：读到已选课未安排即显示）。
     */
    fun probeValidTerms(
        maxProbe: Int = 6,
        blankLimit: Int = 2,
        today: LocalDate = LocalDate.now(),
        knownTerms: List<String> = emptyList(),
    ): ExamProbeResult {
        val enrollmentYear = enrollmentYearFromStudentId(username)
        // 窗口上界上限：本地当前学期往后 2 个学期。
        // 教务列表会预置很远的未来学期（实测到 2027-2028），直接取最远会
        // 白白探测多个未来空学期；本地时间只用于"收窄预置"，学期本身仍以列表为准。
        val upperBound = termPlus(currentTermCode(today), 2)
        var academicTerms: List<String>
        var listError: Throwable? = null
        try {
            academicTerms = fetchTermCodes()
        } catch (error: Throwable) {
            listError = error
            academicTerms = emptyList()
        }
        if (academicTerms.isEmpty() && listError is ExamNetworkException) {
            // 网络抖动（非会话过期）：同 cookie 有限重试一次（不触发 CAS 登录，风控红线），
            // 避免新学期数据因单次网络抖动滞后 6h（shouldReProbe 节流内不再重探）
            academicTerms = runCatching { fetchTermCodes() }.getOrDefault(emptyList())
        }
        if (academicTerms.isEmpty() && listError is AcademicLoginBlockedException) {
            // 登录被服务端拒绝/限流（登录页异常）：不再尝试探测与续登（冷却窗口内禁止重试），
            // 直接上抛，让上层显示"登录过于频繁"而不是继续打请求。
            throw listError
        }
        if (academicTerms.isEmpty() && listError is ExamSessionExpiredException) {
            // 列表为空且是会话过期（旧 cookie）：续登一次后重试，与业务接口同构。
            // 重试后仍会话过期 → 明确抛出（上层走恢复）；续登后网络/格式失败 → 回退本地窗口降级
            // （历史学期仍可显示），不静默吞掉"续登后仍失败"的会话问题。
            forceAcademicLoginOnce()
            appOpened = false
            academicTerms = try {
                fetchTermCodes()
            } catch (retryError: ExamSessionExpiredException) {
                throw retryError
            } catch (retryError: Throwable) {
                emptyList()
            }
        }
        val recent = selectRecentTerm(academicTerms, upperBound)
        return if (recent != null && enrollmentYear != null) {
            probeEnrollmentWindow(enrollmentYear, recent, knownTerms)
        } else {
            probeWithBlankStop(recent ?: currentTermCode(today), maxProbe, blankLimit)
        }
    }

    /** 教务学期列表（全部学期，含最新；结构：datas.xnxqcx.rows[].DM）。 */
    fun fetchTermCodes(): List<String> =
        parseTermCodes(post("xnxqcx.do", emptyMap()))

    /**
     * 学号窗口探测：窗口 = [入学年份第一学期, 教务最近学期]，学期并行拉取，
     * 命中（已排或未安排非空）记入；返回新→旧 + 最近有效学期数据。
     * 收敛（省请求）：
     * - 下界 = 最近已知有效学期往前 2 个（更早的学期列表稳定，保持缓存不重复探测；
     *   首次探测（knownTerms 空）仍覆盖完整窗口）
     * - 已知有效学期（knownTerms，已在缓存）跳过探测，只探新增/边界学期
     * 会话过期处理（参照 fetchScores firstWave→login→retry 模式）：
     * 首轮并行拉取 → 任一学期判会话过期 → **统一续登一次** → 仅重试失败学期。
     * 注意：窗口层必须用「不内嵌续登」的低层拉取（requestTermData），
     * 否则并发学期会各自抢单飞门（一个成功其余被拒导致该学期数据丢失）。
     */
    private fun probeEnrollmentWindow(
        enrollmentYear: Int,
        recent: String,
        knownTerms: List<String>,
    ): ExamProbeResult {
        val fullWindow = termsBetween(enrollmentYear, recent) // 新→旧
        // 收敛下界：最近已知有效学期往前 2 个学期；更早学期不重探（数据在缓存，列表稳定）
        val newestKnown = knownTerms.firstOrNull()
        val lowerBound = newestKnown?.let { termMinus(it, 2) } ?: fullWindow.last()
        val window = fullWindow.filter { it >= lowerBound }
        val fresh = window.filterNot { it in knownTerms }
        val results = parallelProbe(fresh)
        val expired = results.indices.filter { results[it].exceptionOrNull() is ExamSessionExpiredException }
        if (expired.isNotEmpty()) {
            // 统一续登一次（进程级单飞门保护），成功后仅重试会话过期的学期
            forceAcademicLoginOnce()
            appOpened = false
            val retried = parallelProbe(expired.map { fresh[it] })
            retried.forEachIndexed { index, result -> results[expired[index]] = result }
        }
        // 已知有效学期（含收敛窗口之外的）全部保留（数据在缓存，切换器/跨学期提醒仍需）；
        // 新探测结果按窗口顺序并入，整体按新→旧排序。
        // 注意：knownTerms 保留项不重探（教务删除后滞留到学期跌出缓存槽，属已知权衡——
        // 目标学期每次进入都会单独重探，可自愈当前视图）。
        val valid = mutableListOf<Pair<String, XmuTermExamSummary?>>()
        knownTerms.filter { it in fullWindow || it >= lowerBound }.forEach { term ->
            if (valid.none { it.first == term }) valid += term to null
        }
        window.forEach { term ->
            if (term !in knownTerms) {
                val index = fresh.indexOf(term)
                if (index >= 0) {
                    val summary = results[index].getOrNull()
                    if (summary != null) valid += term to summary
                }
            }
        }
        valid.sortByDescending { it.first } // YYYY-YYYY-S 字典序 = 时间序，最近在前
        val summaries = valid.mapNotNull { (term, summary) -> summary?.let { term to it } }.toMap()
        // latest：最近有效学期数据（优先取非空摘要；knownTerms 保留项无新数据时为 null，
        // 上层有 summaries[target] ?: fetchTermExams(target) 兜底）
        val latest = valid.firstOrNull { it.second != null }?.second
        return ExamProbeResult(valid.map { it.first }, latest, summaries)
    }

    /** 并行拉取学期数据（仅请求不续登：续登由窗口层统一处理，避免并发抢单飞门）。 */
    private fun parallelProbe(terms: List<String>): MutableList<Result<XmuTermExamSummary?>> =
        boundedParallelMap(
            items = terms,
            maxParallel = MAX_PARALLEL_PROBE,
        ) { term ->
            runCatching { buildTermSummary(term, requestTermData(term)) }
        }.toMutableList()

    /** 回退探测：从起点往前串行，连续 blankLimit 个空白学期提前停止；返回新→旧。 */
    private fun probeWithBlankStop(start: String, maxProbe: Int, blankLimit: Int): ExamProbeResult {
        val candidates = recentTermCodes(start, maxProbe)
        val valid = mutableListOf<Pair<String, XmuTermExamSummary?>>()
        var blankStreak = 0
        for (term in candidates) {
            val summary = fetchTermExams(term)
            if (summary != null) {
                valid += term to summary
                blankStreak = 0
            } else {
                blankStreak += 1
                if (blankStreak >= blankLimit) break
            }
        }
        val summaries = valid.mapNotNull { (term, summary) -> summary?.let { term to it } }.toMap()
        return ExamProbeResult(valid.map { it.first }, valid.firstOrNull()?.second, summaries)
    }

    /** 从 recent 往前退到入学年份第一学期之间的全部学期（新→旧；含第三学期）。 */
    private fun termsBetween(enrollmentYear: Int, recent: String): List<String> {
        val match = TERM_CODE_FULL_PATTERN.find(recent) ?: return listOf(recent)
        // 入学年份第一学期：2025 年入学 → 2025-2026-1（2025 年 9 月开学）
        val minTerm = "$enrollmentYear-${enrollmentYear + 1}-1"
        var year = match.groupValues[1].toInt()
        var sem = match.groupValues[3].toInt()
        val result = mutableListOf<String>()
        while (true) {
            val term = "$year-${year + 1}-$sem"
            if (term < minTerm) break
            result += term
            sem -= 1
            if (sem < 1) {
                sem = 3
                year -= 1
            }
        }
        return result
    }

    /** 取教务学期列表中最接近但不晚于 upperBound 的合法学期（学期号仅 1/2/3，过滤异常条目）。 */
    private fun selectRecentTerm(terms: List<String>, upperBound: String): String? {
        return terms.mapNotNull { code ->
            TERM_CODE_STRICT_PATTERN.matchEntire(code)?.let { match ->
                val year = match.groupValues[1].toInt()
                val semester = match.groupValues[3].toInt()
                Triple(year * 10 + semester, year, code)
            }
        }.filter { it.third <= upperBound }
            .maxWithOrNull(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })?.third
    }

    /** 当前学期往后 n 个学期（未来方向；学期号 1→2→3→下学年 1）。 */
    private fun termPlus(current: String, n: Int): String {
        val match = TERM_CODE_FULL_PATTERN.find(current) ?: return current
        var year = match.groupValues[1].toInt()
        var sem = match.groupValues[3].toInt()
        repeat(n) {
            sem += 1
            if (sem > 3) {
                sem = 1
                year += 1
            }
        }
        return "$year-${year + 1}-$sem"
    }

    /** 当前学期往前 n 个学期（历史方向；学期号 3→2→1→上学年 3）。 */
    private fun termMinus(current: String, n: Int): String {
        val match = TERM_CODE_FULL_PATTERN.find(current) ?: return current
        var year = match.groupValues[1].toInt()
        var sem = match.groupValues[3].toInt()
        repeat(n) {
            sem -= 1
            if (sem < 1) {
                sem = 3
                year -= 1
            }
        }
        return "$year-${year + 1}-$sem"
    }

    private fun post(path: String, form: Map<String, String>): String {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val url = "https://jw.xmu.edu.cn/jwapp/sys/studentWdksapApp/modules/wdksap/$path"
        // 与成绩/课表同构：请求前确保应用会话（打开应用入口，跟随重定向）
        ensureAppOpened()
        val startedAt = System.currentTimeMillis()
        val response = request(url, "POST", body)
        examDebug("REQ POST $path -> ${response.code} ${System.currentTimeMillis() - startedAt}ms")
        // 风控红线：只有"真会话失效"（401/403/重定向到统一登录域/登录页特征）才判过期触发续登；
        // 5xx、非鉴权 4xx、服务端坏页一律归为响应异常，绝不触发 CAS 重登（与成绩格式/会话严格区分同构）。
        if (response.isSessionExpired()) {
            throw ExamSessionExpiredException()
        }
        if (response.code !in 200..299) {
            throw ExamResponseException("考试安排接口暂时不可用")
        }
        return response.body
    }

    private companion object {
        const val MAX_REDIRECTS = 8
        // 窗口探测学期并发：窗口小（4-6 个学期），2 学期并发 × 每学期内部 2 接口
        // = 最多 4 个在途请求，不超过成绩模块 5 并发先例（风控余量一致）。
        const val MAX_PARALLEL_PROBE = 2
    }

    /** 打开考试应用入口，建立应用级会话。双检锁保证单飞；失败不阻断（保持 false，下次重试打开）。 */
    private fun ensureAppOpened() {
        if (appOpened) return
        synchronized(this) {
            if (appOpened) return
            try {
                examDebug("appOpen: start")
                val opened = follow("https://jw.xmu.edu.cn/jwapp/sys/studentWdksapApp/*default/index.do")
                examDebug("appOpen: -> ${opened.code}")
                // 会话建立成功才置位：避免"半初始化"被数据请求消费而间接逼出续登
                appOpened = true
            } catch (error: Throwable) {
                examDebug("appOpen: failed ${error.javaClass.simpleName}")
                // 忽略：初始化失败时直接尝试接口请求
            }
        }
    }

    /** 跟随重定向链（与成绩客户端 follow 同构）。 */
    private fun follow(
        url: String,
        method: String = "GET",
        body: String = "",
    ): QueryHttpResponse {
        var current = url
        var nextMethod = method
        var nextBody = body
        repeat(MAX_REDIRECTS) {
            val response = request(current, nextMethod, nextBody)
            if (response.code in 300..399 && !response.location.isNullOrBlank()) {
                current = java.net.URL(java.net.URL(current), response.location).toString()
                nextMethod = "GET"
                nextBody = ""
                return@repeat
            }
            return response
        }
        // 重定向循环耗尽：停在统一登录域 = 会话过期（CASTGC 也失效）；教务域内部循环属数据性问题。
        // 除 CAS 的 ids 域外，同时识别 TronClass 侧 Keycloak 身份域（c-identity /auth/realms/xmu/），
        // 与 SessionHealth.IDENTITY_MARKERS 对齐，防登录页落在未覆盖的域时误判为"跳转次数过多"。
        val lowered = current.lowercase()
        if ("ids.xmu.edu.cn" in lowered || "c-identity.xmu.edu.cn" in lowered ||
            "authserver/login" in lowered || "/auth/realms/xmu/" in lowered
        ) {
            throw ExamSessionExpiredException()
        }
        throw ExamResponseException("教务跳转次数过多")
    }

    private fun request(
        url: String,
        method: String = "GET",
        body: String = "",
    ): QueryHttpResponse {
        val headers = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
            "Connection" to "keep-alive",
            "Referer" to "https://jw.xmu.edu.cn/jwapp/sys/studentWdksapApp/*default/index.do",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15",
        )
        if (method != "GET") {
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            headers["Accept"] = "application/json, text/plain, */*"
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
        }
        jar.header().takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        val response = try {
            transport.execute(
                QueryHttpRequest(
                    url = url,
                    method = method,
                    headers = headers,
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    body = body,
                    operation = NetworkOperation.EXAM,
                ),
            )
        } catch (error: IOException) {
            throw ExamNetworkException(error)
        }
        jar.read(response.headers)
        return response
    }

    private fun QueryHttpResponse.isSessionExpired(): Boolean =
        code == 401 || code == 403 || isLoginPage()

    private fun QueryHttpResponse.isLoginPage(): Boolean {
        val redirect = location.orEmpty().lowercase()
        if ("ids.xmu.edu.cn" in redirect || "authserver/login" in redirect) return true
        // HTML 页只有带登录页特征才算会话失效；普通服务端坏页/维护页不是（避免误触发 CAS 重登）
        val page = body.lowercase()
        return page.trimStart().let { it.startsWith("<!doctype html") || it.startsWith("<html") } &&
            listOf("pwdencryptsalt", "authserver/login", "ids.xmu.edu.cn", "请先登录", "未登录").any { it in page }
    }

    private fun parseExams(body: String): List<XmuExam> {
        val root = runCatching { JSONObject(body) }.getOrElse { throw ExamResponseException(cause = it) }
        val rows = root.optJSONObject("datas")
            ?.optJSONObject("cxxsksap")
            ?.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("KSRWID").ifBlank { row.optString("KSDM") }
            if (id.isBlank()) return@mapNotNull null
            val date = row.optString("KSRQ")
            val timeRange = parseTimeRange(row.optString("KSSJMS"))
            XmuExam(
                id = id,
                courseName = row.optString("KCM").ifBlank { row.optString("YWKCM") },
                date = date,
                timeRange = timeRange,
                room = row.optString("JASMC").ifBlank { "线上考试" },
                mode = row.optString("KSXS_DISPLAY").ifBlank { "线下" },
                examName = row.optString("KSMC").ifBlank { "考试" },
            )
        }
    }

    private fun parseUnarranged(body: String): List<XmuExamUnarranged> {
        val root = runCatching { JSONObject(body) }.getOrElse { throw ExamResponseException(cause = it) }
        val rows = root.optJSONObject("datas")
            ?.optJSONObject("cxyxkwapkwdkc")
            ?.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("KCM").ifBlank { row.optString("KCMC").ifBlank { row.optString("courseName") } }
            if (name.isBlank()) return@mapNotNull null
            XmuExamUnarranged(courseName = name)
        }
    }

    private fun parseTermCodes(body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrElse { throw ExamResponseException(cause = it) }
        val rows = root.optJSONObject("datas")
            ?.optJSONObject("xnxqcx")
            ?.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("DM")?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseTimeRange(kssjms: String): String {
        // KSSJMS 形如 "2026-06-14 08:00-10:00(星期日)"
        val match = TIME_RANGE_PATTERN.find(kssjms)
        return if (match != null) "${match.groupValues[1]}-${match.groupValues[2]}" else ""
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun termLabel(termCode: String): String {
        val match = TERM_CODE_FULL_PATTERN.find(termCode) ?: return termCode
        val semester = when (match.groupValues[3]) {
            "1" -> "第一学期"
            "2" -> "第二学期"
            else -> "第三学期"
        }
        return "${match.groupValues[1]}-${match.groupValues[2]}学年 $semester"
    }

    /** 从当前学期往前生成 maxProbe 个候选学期（最近在前）。 */
    private fun recentTermCodes(current: String, maxProbe: Int): List<String> {
        val match = TERM_CODE_FULL_PATTERN.find(current) ?: return listOf(current)
        val startYear = match.groupValues[1].toInt()
        val semester = match.groupValues[3].toInt()
        val result = mutableListOf<String>()
        var year = startYear
        var sem = semester
        repeat(maxProbe) {
            result += "$year-${year + 1}-$sem"
            sem -= 1
            if (sem < 1) {
                sem = 3
                year -= 1
            }
        }
        return result
    }
}

/** 从学号提取入学年份（厦大本科学号：3 位学院码 + 4 位年份 + 7 位序号）。
 *  普适：格式不匹配时返回 null（上层回退到探测窗口）。 */
internal fun enrollmentYearFromStudentId(studentId: String): Int? {
    val match = STUDENT_ID_YEAR_PATTERN.find(studentId) ?: return null
    return match.groupValues[1].toInt()
}

/**
 * 考试列表是否发生变化：数量或关键字段（课程/日期/时间/教室）不同即视为变化。
 * 悄悄检查（进入页面静默对比）的核心判定。
 */
internal fun examsChanged(old: XmuTermExamSummary, new: XmuTermExamSummary): Boolean {
    if (old.exams.size != new.exams.size) return true
    if (old.unarranged.size != new.unarranged.size) return true
    val keyOf: (XmuExam) -> String = {
        "${it.id}|${it.courseName}|${it.date}|${it.timeRange}|${it.room}|${it.mode}|${it.examName}"
    }
    val oldKeys = old.exams.map(keyOf).toSet()
    val newKeys = new.exams.map(keyOf).toSet()
    if (oldKeys != newKeys) return true
    val oldUn = old.unarranged.map { it.courseName }.toSet()
    val newUn = new.unarranged.map { it.courseName }.toSet()
    return oldUn != newUn
}
