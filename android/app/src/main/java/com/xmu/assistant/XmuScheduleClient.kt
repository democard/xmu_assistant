package com.xmu.assistant

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

internal class ScheduleSessionExpiredException : IllegalStateException("教务登录已过期")

internal class ScheduleNetworkException(cause: Throwable) :
    IllegalStateException("课表网络连接失败", cause)

internal class ScheduleResponseException(message: String = "课表接口返回格式异常", cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class ScheduleTermUnavailableException : IllegalStateException("没有找到可用学年学期")

internal fun normalizeScheduleFailure(error: Throwable): Throwable = when {
    error is ScheduleSessionExpiredException ||
        error is ScheduleNetworkException ||
        error is ScheduleResponseException ||
        error is ScheduleTermUnavailableException -> error
    // 跨模块「真会话失效」类型化异常（成绩/会话恢复模块抛出）：统一归一化，
    // 触发上层续登。不再按错误消息关键词猜测（消息扫描会把非会话的解析/URL 异常
    // 误判为会话过期 → 误触发 CAS 续登，风控红线）。
    error is MainSessionExpiredException ||
        error is ScoreSessionExpiredException -> ScheduleSessionExpiredException()
    error is IOException -> ScheduleNetworkException(error)
    else -> error
}

internal class XmuScheduleClient(
    private val cookieHeader: String,
    private val transport: QueryHttpTransport = OkHttpQueryTransport(),
) {
    /** 课表拉取结果：学期代码、课表条目、系统当前周次（1..19，教学周内有效）。 */
    data class ScheduleFetchResult(
        val termCode: String,
        val entries: List<XmuScheduleEntry>,
        val currentWeek: Int?,
    )

    fun fetchCurrentSchedule(studentNumber: String): ScheduleFetchResult {
        val termCode = selectXmuCurrentTermCode(fetchTermCodes())
            ?: throw ScheduleTermUnavailableException()
        // 已知学期（官方校历表内）不需要当前周次反推，省掉一次网络请求（刷新更快）；
        // 只有未知学期才需要 getZcxx 作为反推锚点，且课表与周次互不依赖，可并行拉取。
        val needCurrentWeek = xmuAcademicCalendarForTerm(termCode) == null
        val entries: List<XmuScheduleEntry>
        val currentWeek: Int?
        if (needCurrentWeek) {
            // 并行：课表 + 系统当前周次（两个独立请求，省一个 RTT）。
            // 用线程各自保存结果与原始异常，避免 Future 把异常包成 ExecutionException
            // 而破坏上层按异常类型判断续登（ScheduleSessionExpiredException）的逻辑。
            // 两个线程都必须捕获异常：会话过期异常绝不逃逸子线程（Android 默认
            // uncaught handler 会终止进程），统一收集后由调用方上抛触发续登。
            val scheduleEntries = java.util.concurrent.atomic.AtomicReference<List<XmuScheduleEntry>>()
            val scheduleFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val week = java.util.concurrent.atomic.AtomicReference<Int>()
            val scheduleThread = kotlin.concurrent.thread(name = "xmu-schedule-fetch", isDaemon = true) {
                try {
                    scheduleEntries.set(fetchSchedule(termCode, studentNumber))
                } catch (error: Throwable) {
                    scheduleFailure.set(error)
                }
            }
            val weekThread = kotlin.concurrent.thread(name = "xmu-schedule-week", isDaemon = true) {
                try {
                    week.set(fetchCurrentWeek(termCode))
                } catch (error: Throwable) {
                    // 降级处理（currentWeek=null）：会话过期异常在此吞掉——
                    // 单端点误判（WAF/代理偶发）不应带动整模块 CAS 续登；
                    // 课表线程结果优先，其失败才按异常类型上抛。异常绝不逃逸子线程。
                }
            }
            scheduleThread.join()
            weekThread.join()
            // 课表线程结果优先：失败则按其异常类型上抛（会话过期 → 上层续登；
            // 网络/格式 → 不触发续登，风控红线）。
            scheduleFailure.get()?.let { throw it }
            entries = scheduleEntries.get() ?: emptyList()
            currentWeek = week.get()
        } else {
            entries = fetchSchedule(termCode, studentNumber)
            currentWeek = null
        }
        return ScheduleFetchResult(termCode, entries, currentWeek)
    }

    /**
     * 获取系统认定的当前教学周。教学周内可靠；寒暑假可能返回 1 或异常值，
     * 由调用方结合官方校历判断是否采信。
     */
    fun fetchCurrentWeek(termCode: String): Int? =
        try {
            parseXmuCurrentWeek(
                post(
                    "/gsapp/sys/wdkbapp/wdkcb/getZcxx.do",
                    mapOf("XNXQDM" to termCode),
                ),
            )
        } catch (error: ScheduleSessionExpiredException) {
            // 注意：fetchCurrentSchedule 的并行 week 线程会降级吞掉本异常（单端点误判
            // 不带动整模块续登，见交接文档坑 22）；此 rethrow 仅对直接调用方有意义。
            throw error
        } catch (error: Throwable) {
            null // 纯网络/解析错误不影响课表展示，静默忽略
        }

    fun fetchTermCodes(): List<String> =
        try {
            parseXmuTermCodes(
                post(
                    "/gsapp/sys/wdkbapp/modules/xskcb/kfdxnxqcx.do",
                    emptyMap(),
                ),
            )
        } catch (error: Throwable) {
            throw rethrowScheduleError(error)
        }

    fun fetchSchedule(termCode: String, studentNumber: String): List<XmuScheduleEntry> =
        try {
            parseXmuScheduleEntries(
                post(
                    "/gsapp/sys/wdkbapp/wdkcb/queryXspkjg.do",
                    mapOf(
                        "XNXQDM" to termCode,
                        "XH" to studentNumber,
                        // 实测（2026-08-14）：服务端会把 ZC 当作周次过滤条件，ZC=1 时
                        // 只返回第 1 周命中的记录，导致 3-6,8,13-14周 这类互补周次段
                        // 的课程（如数据结构周一/周二）在本地按周过滤后丢失。
                        // 网页端不传 ZC，返回全学期 32 条，由客户端 forWeek() 过滤，
                        // 因此这里与网页保持一致：不传 ZC。
                    ),
                ),
                termCode,
            )
        } catch (error: Throwable) {
            throw rethrowScheduleError(error)
        }

    private fun rethrowScheduleError(error: Throwable): Throwable =
        when (val normalized = normalizeScheduleFailure(error)) {
            is ScheduleSessionExpiredException,
            is ScheduleNetworkException,
            is ScheduleResponseException,
            is ScheduleTermUnavailableException,
            // 登录被服务端拒绝/限流：原样传递（防御性——当前课表内部不直接触发登录，
            // 但若未来链路变化，绝不能被包装成"课表接口返回异常"误导）
            is AcademicLoginBlockedException -> normalized
            else -> ScheduleResponseException(cause = error)
        }

    private fun post(path: String, form: Map<String, String>): String {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val response = try {
            transport.execute(
                QueryHttpRequest(
                    url = "https://jw.xmu.edu.cn$path",
                    method = "POST",
                    headers = buildMap {
                        put("Accept", "application/json, text/plain, */*")
                        put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        put("Referer", "https://jw.xmu.edu.cn/new/index.html")
                        if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                    },
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    body = body,
                    operation = NetworkOperation.SCHEDULE,
                ),
            )
        } catch (error: IOException) {
            throw ScheduleNetworkException(error)
        }
        if (response.code == 401 || response.code == 403 || response.isAcademicLoginResponse()) {
            throw ScheduleSessionExpiredException()
        }
        if (response.code !in 200..299) {
            throw ScheduleResponseException("课表接口暂时不可用")
        }
        return response.body
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun QueryHttpResponse.isAcademicLoginResponse(): Boolean {
        val redirect = location.orEmpty().lowercase(Locale.US)
        if ("ids.xmu.edu.cn" in redirect || "authserver/login" in redirect) return true
        // 风控红线：只有「HTML 且带登录页特征」才算会话失效；
        // 服务端坏页/维护页/WAF 页（同样以 <html 开头）不是，绝不误触发 CAS 续登。
        val page = body.lowercase(Locale.US)
        return page.trimStart().let { it.startsWith("<!doctype html") || it.startsWith("<html") } &&
            listOf("pwdencryptsalt", "authserver/login", "ids.xmu.edu.cn", "请先登录", "未登录").any { it in page }
    }
}

/** 学期代码：4 位学年 + 1 位学期（1=秋季，2=春季，3=夏季/短学期）。 */
private val TERM_CODE_PATTERN = Regex("""^(\d{4})([123])$""")

/**
 * 选择当前学期：取「学年学期码」数值最大的学期（最新学期优先）。
 * 1/2/3 都参与排序（3=夏季学期，20263 > 20262，暑假时优先展示短学期课表）。
 * 同一代码出现多次时取靠前的（接口顺序），保证稳定。
 * 非法条目直接过滤（此前只是排序权重最低，接口异常全返垃圾值时会把垃圾码当学期发请求）。
 */
internal fun selectXmuCurrentTermCode(termCodes: List<String>): String? =
    termCodes
        .mapIndexed { index, code -> index to code }
        .filter { (_, code) -> TERM_CODE_PATTERN.matches(code) }
        .maxWithOrNull(
            compareBy<Pair<Int, String>> { (_, code) ->
                val match = TERM_CODE_PATTERN.matchEntire(code)!!
                match.groupValues[1].toInt() * 10 + match.groupValues[2].toInt()
            }.thenBy { (index, _) -> -index },
        )
        ?.second

/**
 * 解析 getZcxx.do 响应的当前周次。教学周内返回 1..25（覆盖 19 周长学期）；
 * 寒暑假等异常情况返回 null（调用方决定是否采信）。
 */
internal fun parseXmuCurrentWeek(body: String): Int? = runCatching {
    val root = JSONObject(body)
    val raw = root.optString("currentZc").toIntOrNull()
    if (raw != null && raw in 1..25) raw else null
}.getOrNull()
