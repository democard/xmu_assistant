package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Widget 专用的「今日课程摘要」缓存。
 *
 * 与课表主缓存（EncryptedSharedPreferences 加密大 JSON）分离：
 * - 只含课程名 / 时间 / 地点 / 周次等非敏感信息，不含学号密码 Cookie；
 * - 明文存储在普通 SharedPreferences，AppWidgetProvider 渲染时无需解密大 JSON；
 * - 在课表刷新成功后由 App 写入，并通知 Widget 更新。
 */
data class ScheduleWidgetCourse(
    val courseName: String,
    val startTime: Int,
    val endTime: Int,
    val startSection: Int,
    val endSection: Int,
    val location: String,
)

data class ScheduleWidgetSnapshot(
    val weekday: Int,
    val week: Int,
    val termCode: String,
    val courses: List<ScheduleWidgetCourse>,
    /** 快照生成时的本地日期（epochDay）：跨日后 Widget 渲染时判断是否还是「今日课程」。 */
    val savedEpochDay: Long = java.time.LocalDate.now().toEpochDay(),
)

object ScheduleWidgetData {
    private const val PREFS = "schedule_widget"
    private const val KEY_JSON = "today_summary"

    fun save(context: Context, snapshot: ScheduleWidgetSnapshot) {
        val json = JSONObject()
            .put("weekday", snapshot.weekday)
            .put("week", snapshot.week)
            .put("termCode", snapshot.termCode)
            .put("savedEpochDay", snapshot.savedEpochDay)
            .put(
                "courses",
                JSONArray(snapshot.courses.map { course ->
                    JSONObject()
                        .put("courseName", course.courseName)
                        .put("startTime", course.startTime)
                        .put("endTime", course.endTime)
                        .put("startSection", course.startSection)
                        .put("endSection", course.endSection)
                        .put("location", course.location)
                }),
            )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, json.toString())
            .apply()
    }

    fun load(context: Context): ScheduleWidgetSnapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return null
        val root = JSONObject(raw)
        val rows = root.optJSONArray("courses") ?: JSONArray()
        val courses = ArrayList<ScheduleWidgetCourse>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            courses += ScheduleWidgetCourse(
                courseName = row.optString("courseName"),
                startTime = row.optInt("startTime"),
                endTime = row.optInt("endTime"),
                startSection = row.optInt("startSection"),
                endSection = row.optInt("endSection"),
                location = row.optString("location"),
            )
        }
        ScheduleWidgetSnapshot(
            weekday = root.optInt("weekday"),
            week = root.optInt("week"),
            termCode = root.optString("termCode"),
            courses = courses,
            // 旧快照无该字段：optLong 默认 0，视为「未知日期」→ Widget 提示刷新
            savedEpochDay = root.optLong("savedEpochDay", 0L),
        )
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JSON)
            .apply()
    }
}
