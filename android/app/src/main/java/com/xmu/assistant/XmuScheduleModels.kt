package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject

data class XmuScheduleEntry(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: Int,
    val endTime: Int,
    val courseName: String,
    val room: String,
    val teacher: String,
    val weeks: String,
    val termCode: String,
)

data class XmuScheduleGroup(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: Int,
    val endTime: Int,
    val courseName: String,
    val rooms: List<String>,
    val teachers: List<String>,
    val weeks: String,
    val variants: List<XmuScheduleVariant> = emptyList(),
)

data class XmuScheduleVariant(
    val room: String,
    val teacher: String,
    /** 该教学班对应的周次（如 "1-8周"），保留「周次→教室→教师」完整映射。 */
    val weeks: String = "",
)

fun List<XmuScheduleEntry>.groupForDisplay(): List<XmuScheduleGroup> =
    groupBy {
        listOf(
            it.weekday,
            it.startSection,
            it.endSection,
            it.startTime,
            it.endTime,
            it.courseName,
        )
    }
        .values
        .map { entries ->
            val first = entries.first()
            XmuScheduleGroup(
                weekday = first.weekday,
                startSection = first.startSection,
                endSection = first.endSection,
                startTime = first.startTime,
                endTime = first.endTime,
                courseName = first.courseName,
                rooms = entries.map { it.room }.filter(String::isNotBlank).distinct(),
                teachers = entries.map { it.teacher }.filter(String::isNotBlank).distinct(),
                // 同一课程同一时间可能有多条周次互补的记录（如
                // "1-2,7,9-12周" + "3-6,8,13-14周"），合并展示为一段周次文本。
                weeks = entries
                    .map { it.weeks }
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString("；"),
                variants = entries
                    .map { XmuScheduleVariant(room = it.room, teacher = it.teacher, weeks = it.weeks) }
                    .distinct(),
            )
        }
        .sortedWith(
            compareBy<XmuScheduleGroup> { it.weekday }
                .thenBy { it.startSection }
                .thenBy { it.courseName },
        )

internal fun parseXmuScheduleEntries(body: String, termCode: String): List<XmuScheduleEntry> {
    val root = JSONObject(body)
    if (!root.optBoolean("success", false)) {
        error(root.optString("msg").ifBlank { "课表接口返回失败" })
    }
    val rows = root.optJSONArray("pkjgList") ?: JSONArray()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        XmuScheduleEntry(
            weekday = row.weekdayValue("XQ"),
            startSection = row.intValue("KSJCDM"),
            endSection = row.intValue("JSJCDM"),
            startTime = row.timeValue("KSSJ"),
            endTime = row.timeValue("JSSJ"),
            courseName = row.firstNonBlank("KCMC", "KCM", "courseName"),
            room = row.firstNonBlank("JASMC", "JAS", "room"),
            teacher = row.firstNonBlank("JSXM", "JS", "teacher"),
            weeks = row.firstNonBlank("ZCMC", "ZC", "SKZC", "weeks"),
            termCode = termCode,
        )
    }
}

private fun JSONObject.intValue(key: String): Int {
    val value = opt(key)
    return when (value) {
        is Number -> value.toInt()
        else -> value?.toString()?.trim()?.toIntOrNull() ?: 0
    }
}

private fun JSONObject.weekdayValue(key: String): Int {
    val raw = opt(key)?.toString()?.trim().orEmpty()
    raw.toIntOrNull()?.let { return it }
    return when {
        raw.contains("一") -> 1
        raw.contains("二") -> 2
        raw.contains("三") -> 3
        raw.contains("四") -> 4
        raw.contains("五") -> 5
        raw.contains("六") -> 6
        raw.contains("日") || raw.contains("天") -> 7
        else -> 0
    }
}

private fun JSONObject.timeValue(key: String): Int {
    val raw = opt(key)?.toString()?.trim().orEmpty()
    if (raw.contains(':')) {
        val parts = raw.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull()
        val minute = parts.getOrNull(1)?.toIntOrNull()
        if (hour != null && minute != null) return hour * 100 + minute
    }
    return raw.toIntOrNull() ?: 0
}

private fun JSONObject.firstNonBlank(vararg keys: String): String =
    keys.asSequence()
        .map { optString(it).trim() }
        .firstOrNull(String::isNotBlank)
        .orEmpty()

internal fun parseXmuTermCodes(body: String): List<String> {
    val root = JSONObject(body)
    val rows = root.optJSONObject("datas")
        ?.optJSONObject("kfdxnxqcx")
        ?.optJSONArray("rows")
        ?: JSONArray()
    return (0 until rows.length()).mapNotNull { index ->
        rows.optJSONObject(index)?.optString("XNXQDM")?.takeIf(String::isNotBlank)
    }
}

fun formatXmuTime(value: Int): String =
    "%02d:%02d".format(value / 100, value % 100)
