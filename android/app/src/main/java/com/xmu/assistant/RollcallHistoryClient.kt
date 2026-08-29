package com.xmu.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * 最近十次签到（历史区块）的数据客户端。
 *
 * 链路拆瀑布：
 * 1. GET /api/profile 取平台内部 id，与课程列表并行准备——课程优先复用调用方
 *    已加载的 List<CourseSummary>（零重复请求），没有才经 CoursewareClient.fetchCourses 兜底；
 * 2. 课程按 semesterCode 降序分学期批，批内 boundedParallelMap(≤4) 拉两个候选端点
 *    的签到列表；每批结束若可排序记录 ≥10（后续学期必然更旧）→ 早停，
 *    典型一个学期 5~8 个请求封顶；
 * 3. 选出最近 10 条后并发(≤4)拉 /api/rollcall/{id}/student_rollcalls 明细，
 *    按本人记录判定准确状态——绝不先显示聚合状态（用户拍板：必须准确）。
 *
 * 会话语义照抄 CoursewareClient.getJson：401/身份域 302 → MainSessionExpiredException；
 * 403 → 资源级失败按普通探测处理（不触发续登，风控红线）。全部只读 GET。
 */
internal class RollcallHistoryClient internal constructor(
    private val cookieHeader: String,
    private val queryTransport: QueryHttpTransport,
    private val executorFactory: (Int) -> ExecutorService,
) {
    private val baseUrl = "https://lnt.xmu.edu.cn"

    /**
     * 一站式拉取（阶段一+阶段二）：等价于 [selectRecentRollcalls] 后接
     * [resolveOwnStatuses]。保留给不需要中间态的调用方与单测使用。
     */
    fun fetchRecentRollcalls(
        username: String,
        preloadedCourses: List<CourseSummary>?,
        historyLimit: Int = ROLLCALL_HISTORY_LIMIT,
    ): List<RollcallHistoryItem> {
        val selected = selectRecentRollcalls(username, preloadedCourses, historyLimit)
        if (selected.isEmpty()) return selected
        return resolveOwnStatuses(selected, username)
    }

    /**
     * 阶段一（选列表）：profile/课程/分批早停/截十，产出每条 ownStatus 为空串的
     * 预览列表——UI 应立即上屏并显示「核实中…」，绝不显示聚合状态。
     */
    fun selectRecentRollcalls(
        username: String,
        preloadedCourses: List<CourseSummary>?,
        historyLimit: Int = ROLLCALL_HISTORY_LIMIT,
    ): List<RollcallHistoryItem> {
        val profile = getJson("$baseUrl/api/profile")
        val studentId = if (profile is JSONObject) {
            firstString(profile, "id", "user_id", "userId", "student_id", "studentId")
        } else ""
        if (studentId.isBlank()) error("平台未返回用户 ID，无法定位本人签到")

        val courses = preloadedCourses?.takeIf { it.isNotEmpty() }
            ?: CoursewareClient(cookieHeader, queryTransport, executorFactory).fetchCourses()
        if (courses.isEmpty()) return emptyList()

        // 学期倒序分批：semesterCode 为空的排最后（视为最旧）
        val batches = courses.groupBy { course ->
            course.semesterCode.ifBlank { course.term }.ifBlank { "" }
        }.entries.sortedByDescending { it.key }

        val collected = mutableListOf<RollcallRecord>()
        for ((_, batch) in batches) {
            if (batch.isEmpty()) continue
            val records = boundedParallelMap(batch, MAX_PARALLEL_COURSES) { course ->
                fetchCourseRollcallRecords(course, studentId)
            }.flatten()
            collected += records
            // 批结束早停：后续学期更旧，凑够可排序的 historyLimit 条即可收手
            if (collected.count { it.sortKeyMillis != null } >= historyLimit) break
        }

        return collected
            .sortedWith(
                compareByDescending<RollcallRecord> { it.sortKeyMillis != null }
                    .thenByDescending { it.sortKeyMillis ?: Long.MIN_VALUE },
            )
            .take(historyLimit)
            .map { record ->
                RollcallHistoryItem(
                    rollcallId = record.rollcallId,
                    courseId = record.courseId,
                    courseTitle = record.courseTitle,
                    type = record.type,
                    timeDisplay = record.timeDisplay,
                    sortKeyMillis = record.sortKeyMillis,
                    ownStatus = "",
                )
            }
    }

    /** 阶段二（核实）：并发 ≤4 拉本人明细原位回填准确状态；会话过期整体上抛。 */
    fun resolveOwnStatuses(items: List<RollcallHistoryItem>, username: String): List<RollcallHistoryItem> {
        if (items.isEmpty()) return items
        val verdicts = boundedParallelMap(items, MAX_PARALLEL_DETAILS) { item ->
            resolveOwnStatus(item.rollcallId, username)
        }
        return items.mapIndexed { index, item -> item.copy(ownStatus = verdicts[index]) }
    }

    /** 单课程的签到记录（两个候选端点依次探测，与桌面 ROLLCALL_ENDPOINT_TEMPLATES 对齐）。 */
    private fun fetchCourseRollcallRecords(course: CourseSummary, studentId: String): List<RollcallRecord> {
        val endpoints = listOf(
            "$baseUrl/api/course/${course.id}/student/$studentId/rollcalls?page=1&page_size=100",
            "$baseUrl/api/course/${course.id}/student/$studentId/rollcalls",
        )
        for (endpoint in endpoints) {
            try {
                val payload = getJson(endpoint)
                return parseRollcallList(payload, course)
            } catch (error: MainSessionExpiredException) {
                throw error
            } catch (_: Throwable) {
                // 该端点失败换短版再试；两个都挂则该课程计 0 条（资源级失败语义）
            }
        }
        return emptyList()
    }

    private fun parseRollcallList(payload: Any, course: CourseSummary): List<RollcallRecord> {
        val rows = unwrapList(payload, "rollcalls", "activities", "data", "items", "list", "results")
        return rows.mapNotNull { raw ->
            val item = raw as? JSONObject ?: return@mapNotNull null
            // id 用 optRealString：显式 null/字面量 "null" 一律归缺失（体检 P1-5 词表）；
            // 无 id 无法查本人明细，整条跳过
            val id = item.optRealString("rollcall_id").ifBlank { item.optRealString("id") }
            if (id.isBlank()) return@mapNotNull null
            val timeRaw = firstString(item, "rollcall_time", "created_at", "start_time", "time")
            val millis = rollcallHistoryTimeMillis(timeRaw)
            RollcallRecord(
                rollcallId = id,
                courseId = course.id,
                courseTitle = course.title.ifBlank { "未知课程" },
                type = rollcallTypeLabel(item),
                timeDisplay = rollcallTimeDisplay(timeRaw, millis),
                sortKeyMillis = millis,
            )
        }
    }

    /** 本人状态判定：时间戳→已签；本人状态词→normalizedRollcallStatus；再否则「未知」。 */
    private fun resolveOwnStatus(rollcallId: String, username: String): String {
        val detail = try {
            getJson("$baseUrl/api/rollcall/$rollcallId/student_rollcalls")
        } catch (error: MainSessionExpiredException) {
            throw error
        } catch (_: Throwable) {
            return STATUS_UNKNOWN
        }
        val own = findOwnStudentRollcall(detail, username) ?: return STATUS_UNKNOWN
        if (
            own.optRealString("updated_at").isNotBlank() ||
            own.optRealString("answered_at").isNotBlank() ||
            own.optRealString("submitted_at").isNotBlank()
        ) {
            return STATUS_SIGNED
        }
        val word = firstString(own, "status", "rollcall_status", "state")
        return if (word.isBlank()) STATUS_UNKNOWN else normalizedRollcallStatus(word)
    }

    private fun findOwnStudentRollcall(payload: Any, username: String): JSONObject? {
        val students = unwrapList(payload, "student_rollcalls", "students", "data", "items", "list")
        for (raw in students) {
            val student = raw as? JSONObject ?: continue
            val userNo = firstString(student, "user_no", "username", "student_no", "number", "account")
            if (username.isNotBlank() && userNo == username) return student
            if (student.optBoolean("is_current_user") || student.optBoolean("is_self")) return student
        }
        return null
    }

    /** 与 CoursewareClient.getJson 同语义的 GET（401/身份域 302 → 类型化异常）。 */
    private fun getJson(url: String): Any {
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Accept" to "application/json, text/plain, */*",
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        val response = queryTransport.execute(
            QueryHttpRequest(url = url, method = "GET", headers = headers, operation = NetworkOperation.ROLLCALL_STATUS),
        )
        if (response.code == 401) throw MainSessionExpiredException()
        if (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) {
            throw MainSessionExpiredException()
        }
        if (response.code !in 200..299) error("网络失败")
        return runCatching {
            if (response.body.trimStart().startsWith("[")) JSONArray(response.body) else JSONObject(response.body)
        }.getOrElse { error("网络失败") }
    }

    private fun unwrapList(value: Any, vararg keys: String): List<Any> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
            is JSONObject -> {
                for (key in keys) {
                    val nested = value.opt(key)
                    val list = if (nested != null) unwrapList(nested, *keys) else emptyList()
                    if (list.isNotEmpty()) return list
                }
                value.keys().asSequence()
                    .mapNotNull { key -> value.opt(key) }
                    .firstNotNullOfOrNull { nested -> unwrapList(nested, *keys).takeIf { it.isNotEmpty() } }
                    ?: emptyList()
            }
            else -> emptyList()
        }
    }

    /** 课程内待核对的原始记录。 */
    private data class RollcallRecord(
        val rollcallId: String,
        val courseId: String,
        val courseTitle: String,
        val type: String,
        val timeDisplay: String,
        val sortKeyMillis: Long?,
    )

    private companion object {
        const val MAX_PARALLEL_COURSES = 4
        const val MAX_PARALLEL_DETAILS = 4

        /** 签到类型词表与桌面 normalize_rollcall_type 同源（flags 先行，type 字段兜底）。 */
        fun rollcallTypeLabel(item: JSONObject): String {
            if (item.optBoolean("is_radar")) return "雷达签到"
            if (item.optBoolean("is_number")) return "数字签到"
            if (item.optBoolean("is_qrcode") || item.optBoolean("is_qr")) return "二维码签到"
            val rawKeys = arrayOf("rollcall_type", "type", "kind")
            val raw = firstString(item, *rawKeys)
            val lowered = raw.lowercase()
            return when {
                "radar" in lowered -> "雷达签到"
                "number" in lowered -> "数字签到"
                "qr" in lowered -> "二维码签到"
                raw.isBlank() -> "签到"
                else -> raw
            }
        }

        /**
         * 时间解析为排序键（与 remainingSecondsFromDeadline 同约定）：
         * 带偏移按 ISO OffsetDateTime、无偏移按本地时区 LocalDateTime；失败返回 null 排最后。
         */
        fun rollcallHistoryTimeMillis(raw: String): Long? {
            val text = raw.trim()
            if (text.isBlank()) return null
            for (candidate in listOf(text, text.take(19))) {
                val instant = runCatching {
                    java.time.OffsetDateTime.parse(candidate.replace("Z", "+00:00")).toInstant()
                }.recoverCatching {
                    java.time.LocalDateTime.parse(candidate).atZone(java.time.ZoneId.systemDefault()).toInstant()
                }.getOrNull() ?: continue
                return instant.toEpochMilli()
            }
            return null
        }

        /** 展示文本 MM-dd HH:mm；解析失败退回原文（T 换空格、截前 19），再退 "-"。 */
        fun rollcallTimeDisplay(raw: String, millis: Long?): String {
            if (millis != null) {
                val formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                return formatter.format(java.time.Instant.ofEpochMilli(millis))
            }
            return raw.trim().replace('T', ' ').take(19).ifBlank { "-" }
        }

        fun firstString(obj: JSONObject, vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } } ?: ""
    }
}

/** 最近十次签到的一行展示数据。ownStatus 空串仅存在于刷新中间态（UI 显示「核实中…」）。 */
data class RollcallHistoryItem(
    val rollcallId: String,
    val courseId: String,
    val courseTitle: String,
    val type: String,
    val timeDisplay: String,
    val sortKeyMillis: Long?,
    val ownStatus: String,
)

internal const val STATUS_SIGNED = "已签"
internal const val STATUS_UNKNOWN = "未知"
internal const val ROLLCALL_HISTORY_LIMIT = 10

/** 历史区块后台重校的新鲜窗口（用户拍板保持 5 分钟；成绩窗亦已放宽为 5 分钟）。 */
internal const val ROLLCALL_HISTORY_FRESHNESS_MILLIS = 300_000L

// ---------------------------------------------------------------------------
// SWR 缓存（课表范式）：files/rollcall_history_cache.json，版本号 + 账号绑定防串号，
// `.tmp`+rename 原子写；进页面先渲缓存再后台刷新原位更新，失败保留缓存只报错。
// ---------------------------------------------------------------------------

/** 缓存结构版本：字段增删不兼容时 +1 使旧缓存整体失效。 */
internal const val ROLLCALL_HISTORY_CACHE_VERSION = 1
private const val ROLLCALL_HISTORY_CACHE_FILE = "rollcall_history_cache.json"
private const val TAG = "RollcallHistoryClient"

data class RollcallHistorySnapshot(
    val accountId: String,
    val fetchedAtMillis: Long,
    val items: List<RollcallHistoryItem> = emptyList(),
)

fun rollcallHistoryCacheFile(context: Context): File =
    File(context.filesDir, ROLLCALL_HISTORY_CACHE_FILE)

/** 读缓存：版本不匹配或账号不匹配（防串号）一律返回 null，损坏静默兜底为 null。 */
fun loadRollcallHistoryCache(file: File, accountId: String): RollcallHistorySnapshot? = runCatching {
    if (!file.exists() || file.length() == 0L) return@runCatching null
    val root = JSONObject(file.readText())
    if (root.optInt("version", 0) != ROLLCALL_HISTORY_CACHE_VERSION) return@runCatching null
    if (root.optString("account_id") != accountId) return@runCatching null
    val rows = root.optJSONArray("items") ?: JSONArray()
    val items = ArrayList<RollcallHistoryItem>(rows.length())
    for (index in 0 until rows.length()) {
        val obj = rows.optJSONObject(index) ?: continue
        items += RollcallHistoryItem(
            rollcallId = obj.optString("rollcallId"),
            courseId = obj.optString("courseId"),
            courseTitle = obj.optString("courseTitle"),
            type = obj.optString("type"),
            timeDisplay = obj.optString("timeDisplay"),
            sortKeyMillis = if (obj.isNull("sortKeyMillis") || !obj.has("sortKeyMillis")) {
                null
            } else {
                obj.optLong("sortKeyMillis")
            },
            ownStatus = obj.optString("ownStatus"),
        )
    }
    RollcallHistorySnapshot(
        accountId = accountId,
        fetchedAtMillis = root.optLong("fetched_at", 0L),
        items = items,
    )
}.getOrNull()

private val rollcallHistoryCacheWriteLock = Any()

/** 原子写入（`.tmp`+rename，rename 失败短暂重试，与 ScheduleCache 同姿势）。 */
fun saveRollcallHistoryCache(file: File, snapshot: RollcallHistorySnapshot) {
    runCatching {
        val root = JSONObject()
            .put("version", ROLLCALL_HISTORY_CACHE_VERSION)
            .put("account_id", snapshot.accountId)
            .put("fetched_at", snapshot.fetchedAtMillis)
            .put("items", JSONArray(snapshot.items.map { item ->
                val obj = JSONObject()
                    .put("rollcallId", item.rollcallId)
                    .put("courseId", item.courseId)
                    .put("courseTitle", item.courseTitle)
                    .put("type", item.type)
                    .put("timeDisplay", item.timeDisplay)
                    .put("ownStatus", item.ownStatus)
                if (item.sortKeyMillis != null) obj.put("sortKeyMillis", item.sortKeyMillis)
                obj
            }))
        synchronized(rollcallHistoryCacheWriteLock) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            var renamed = temp.renameTo(file)
            if (!renamed) {
                repeat(3) {
                    Thread.sleep(20)
                    if (temp.renameTo(file)) {
                        renamed = true
                        return@repeat
                    }
                }
            }
            if (!renamed) {
                file.delete()
                file.writeText(root.toString())
            }
        }
    }.onFailure {
        // 写失败原先全链路静默（与 ScheduleCache 同款欠账）：只补日志不改控制流。
        Log.w(TAG, "签到历史缓存写入失败：${it.message}", it)
    }
}

/** 删除历史缓存（登出/换号清理调用，与课表缓存同姿势，共用同一把锁防 torn-write）。 */
fun deleteRollcallHistoryCacheFile(context: Context) {
    synchronized(rollcallHistoryCacheWriteLock) {
        runCatching {
            File(context.filesDir, ROLLCALL_HISTORY_CACHE_FILE).delete()
            File(context.filesDir, "$ROLLCALL_HISTORY_CACHE_FILE.tmp").delete()
        }
    }
}
