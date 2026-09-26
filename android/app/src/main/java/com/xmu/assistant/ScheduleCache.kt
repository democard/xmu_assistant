package com.xmu.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 课表缓存格式版本。
 * - 2：接口不再传 ZC 参数（服务端会按周过滤导致互补周次段的课程丢失），
 *      旧版缓存（1）里的记录可能缺周一/周二等课程，旧缓存一律按空处理。
 * - 3：新增 inferredCalendars（按学期缓存的反推日历），旧缓存读不出该字段
 *      不影响课程数据，仍可正常使用，因此不强制失效。
 */
const val SCHEDULE_CACHE_VERSION = 3

/** 课表缓存文件名（明文，位于 filesDir）。课表不含学号/密码等敏感信息。 */
private const val SCHEDULE_CACHE_FILE = "schedule_cache.json"

private const val TAG = "ScheduleCache"

data class XmuScheduleSnapshot(
    val entries: List<XmuScheduleEntry> = emptyList(),
    val termCode: String = "",
    val updatedAtMillis: Long = 0L,
    val schemaVersion: Int = SCHEDULE_CACHE_VERSION,
    /** 按学期缓存的反推日历（getZcxx 当前周次 + 当天日期推算），支持多学期切换。 */
    val inferredCalendars: Map<String, XmuAcademicCalendar> = emptyMap(),
)

fun xmuScheduleSnapshotToJson(snapshot: XmuScheduleSnapshot): String =
    JSONObject()
        .put("schemaVersion", snapshot.schemaVersion)
        .put("termCode", snapshot.termCode)
        .put("updatedAtMillis", snapshot.updatedAtMillis)
        .put("entries", JSONArray(snapshot.entries.map(::xmuScheduleEntryToJson)))
        .put(
            "inferredCalendars",
            JSONObject(snapshot.inferredCalendars.mapValues { (_, calendar) ->
                xmuCalendarToJson(calendar)
            }),
        )
        .toString()

fun xmuScheduleSnapshotFromJson(value: String): XmuScheduleSnapshot = runCatching {
    if (value.isBlank()) return@runCatching XmuScheduleSnapshot()
    val root = JSONObject(value)
    // 版本 1 是 ZC=1 时代的数据（缺互补周次段课程），必须失效重拉；
    // 版本 2+ 的课程数据格式相同，可正常读取（版本 3 只是新增字段）。
    if (root.optInt("schemaVersion", 1) < 2) {
        return@runCatching XmuScheduleSnapshot()
    }
    val termCode = root.optString("termCode")
    val rows = root.optJSONArray("entries") ?: JSONArray()
    val entries = ArrayList<XmuScheduleEntry>(rows.length())
    for (index in 0 until rows.length()) {
        // 单条坏记录只跳过该条，不清空整表（旧缓存写了半条坏数据不至于全丢）
        val entry = rows.optJSONObject(index)?.let { obj ->
            runCatching { xmuScheduleEntryFromJson(obj) }.getOrNull()
        } ?: continue
        if (entry.termCode != termCode) continue
        entries += entry
    }
    val calendars = HashMap<String, XmuAcademicCalendar>()
    root.optJSONObject("inferredCalendars")?.let { calJson ->
        calJson.keys().forEach { key ->
            calJson.optJSONObject(key)?.let { value ->
                xmuCalendarFromJson(value)?.takeIf { it.termCode == key }?.let { calendars[key] = it }
            }
        }
    }
    XmuScheduleSnapshot(
        entries = entries,
        termCode = termCode,
        updatedAtMillis = root.optLong("updatedAtMillis", 0L),
        schemaVersion = root.optInt("schemaVersion", SCHEDULE_CACHE_VERSION),
        inferredCalendars = calendars,
    )
}.getOrDefault(XmuScheduleSnapshot())

/**
 * 从明文文件读取课表缓存。
 * 课表数据（课程名/教室/教师/周次）不含敏感信息，明文存储比加密
 * SharedPreferences 读写快得多，且避免大 JSON 反复加解密。
 */
fun loadScheduleSnapshotFromFile(context: Context): XmuScheduleSnapshot =
    loadScheduleSnapshotFromFile(File(context.filesDir, SCHEDULE_CACHE_FILE))

/** 把课表缓存原子写入明文文件。 */
fun saveScheduleSnapshotToFile(context: Context, snapshot: XmuScheduleSnapshot) {
    saveScheduleSnapshotToFile(File(context.filesDir, SCHEDULE_CACHE_FILE), snapshot)
}

/** 从指定文件读取课表缓存（纯函数，便于单元测试）。 */
fun loadScheduleSnapshotFromFile(file: File): XmuScheduleSnapshot {
    if (!file.exists() || file.length() == 0L) return XmuScheduleSnapshot()
    return runCatching {
        xmuScheduleSnapshotFromJson(file.readText())
    }.getOrDefault(XmuScheduleSnapshot())
}

/** 课表缓存写入锁：persist 后台线程与策略页手动校准（主线程）共用同一 `.tmp` 文件，
 *  不加锁会字节交错导致 torn-write，rename 后永久损坏正式缓存。 */
private val scheduleCacheWriteLock = Any()

/** 原子写入课表缓存到指定文件（纯函数，便于单元测试）。 */
fun saveScheduleSnapshotToFile(file: File, snapshot: XmuScheduleSnapshot) {
    runCatching {
        val json = xmuScheduleSnapshotToJson(snapshot)
        writeScheduleSnapshotAtomically(file, json)
    }.onFailure {
        // 写失败原先全链路静默：磁盘满/权限异常时缓存悄悄陈旧，零线索可查。
        // 只补日志不改控制流（与 RollcallMonitorService 推送失败日志同款风格）。
        Log.w(TAG, "课表缓存写入失败：${it.message}", it)
    }
}

/** 原子写入实现；替换器参数仅供 JVM 测试模拟 rename 失败。 */
internal fun writeScheduleSnapshotAtomically(
    file: File,
    json: String,
    rename: (File, File) -> Boolean = { source, target ->
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrDefault(false)
    },
) {
    synchronized(scheduleCacheWriteLock) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(json)
            // 原子替换，避免写一半崩溃留下损坏缓存。
            var renamed = rename(temp, file)
            if (!renamed) {
                for (attempt in 1..3) {
                    Thread.sleep(20)
                    renamed = rename(temp, file)
                    if (renamed) break
                }
            }
            if (!renamed) {
                // 替换失败时保留正式文件；删除临时文件，避免下次读取到半成品。
                temp.delete()
                throw IOException("schedule cache atomic replace failed")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

/** 删除课表缓存文件（退出登录时调用，避免明文排课信息残留）。
 *  与写入共用同一把锁：退出登录的删除与 persist 后台线程/策略页校准并发时，
 *  避免删除与 `.tmp` 写入交错产生 torn 残留或误重建。 */
fun deleteScheduleSnapshotFile(context: Context) {
    synchronized(scheduleCacheWriteLock) {
        runCatching {
            File(context.filesDir, SCHEDULE_CACHE_FILE).delete()
            File(context.filesDir, "$SCHEDULE_CACHE_FILE.tmp").delete()
        }
    }
}

private fun xmuScheduleEntryToJson(entry: XmuScheduleEntry): JSONObject =
    JSONObject()
        .put("weekday", entry.weekday)
        .put("startSection", entry.startSection)
        .put("endSection", entry.endSection)
        .put("startTime", entry.startTime)
        .put("endTime", entry.endTime)
        .put("courseName", entry.courseName)
        .put("room", entry.room)
        .put("teacher", entry.teacher)
        .put("weeks", entry.weeks)
        .put("termCode", entry.termCode)

private fun xmuScheduleEntryFromJson(value: JSONObject): XmuScheduleEntry =
    XmuScheduleEntry(
        weekday = value.getInt("weekday"),
        startSection = value.getInt("startSection"),
        endSection = value.getInt("endSection"),
        startTime = value.getInt("startTime"),
        endTime = value.getInt("endTime"),
        courseName = value.getString("courseName"),
        room = value.optString("room"),
        teacher = value.optString("teacher"),
        weeks = value.optString("weeks"),
        termCode = value.getString("termCode"),
    )

internal fun xmuCalendarToJson(calendar: XmuAcademicCalendar): JSONObject =
    JSONObject()
        .put("termCode", calendar.termCode)
        .put("academicYearLabel", calendar.academicYearLabel)
        .put("semesterLabel", calendar.semesterLabel)
        .put("startDate", calendar.startDate.toString())
        .put("endDate", calendar.endDate.toString())
        .put("totalWeeks", calendar.totalWeeks)

internal fun xmuCalendarFromJson(value: JSONObject): XmuAcademicCalendar? = runCatching {
    val termCode = value.getString("termCode")
    if (!Regex("[0-9]{4}[123]").matches(termCode)) return@runCatching null
    // 旧缓存未写总周数时保留原默认值；显式坏值不能被 optInt 截断/溢出后当作有效日历。
    val totalWeeks = if (value.has("totalWeeks")) {
        value.opt("totalWeeks")?.toString()?.toIntOrNull() ?: return@runCatching null
    } else {
        18
    }
    if (totalWeeks !in 1..MAX_XMU_WEEK) return@runCatching null
    val calendar = XmuAcademicCalendar(
        termCode = termCode,
        academicYearLabel = value.getString("academicYearLabel"),
        semesterLabel = value.getString("semesterLabel"),
        startDate = java.time.LocalDate.parse(value.getString("startDate")),
        endDate = java.time.LocalDate.parse(value.getString("endDate")),
        totalWeeks = totalWeeks,
    )
    calendar.takeIf { !it.endDate.isBefore(it.startDate) }
}.getOrNull()
