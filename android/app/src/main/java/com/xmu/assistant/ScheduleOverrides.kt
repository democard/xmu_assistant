package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ScheduleCourseOverride(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val room: String,
    val teacher: String,
    val weeks: String,
)

/** Local corrections to a course arrangement, kept separate from the official timetable cache. */
internal class ScheduleOverrideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun apply(termCode: String, group: XmuScheduleGroup): XmuScheduleGroup {
        val override = readAll().firstOrNull { it.matches(termCode, group) }?.value ?: return group
        return applyScheduleOverride(group, override)
    }

    @Synchronized
    fun has(termCode: String, group: XmuScheduleGroup): Boolean =
        readAll().any { it.matches(termCode, group) }

    @Synchronized
    fun save(termCode: String, original: XmuScheduleGroup, value: ScheduleCourseOverride) {
        require(value.weekday in 1..7) { "星期必须在 1—7 之间" }
        require(value.startSection in 1..11) { "开始节次必须在 1—11 之间" }
        require(value.endSection in value.startSection..11) { "结束节次不能早于开始节次" }
        val all = readAll().toMutableList()
        all.removeAll { it.matches(termCode, original) }
        all += StoredScheduleOverride(
            termCode = termCode,
            courseName = original.courseName,
            originalWeekday = original.weekday,
            originalStartSection = original.startSection,
            originalEndSection = original.endSection,
            value = value.copy(
                room = value.room.trim(),
                teacher = value.teacher.trim(),
                weeks = value.weeks.trim(),
            ),
        )
        writeAll(all)
    }

    @Synchronized
    fun remove(termCode: String, original: XmuScheduleGroup): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeAll { it.matches(termCode, original) }
        if (removed) writeAll(all)
        return removed
    }

    private fun readAll(): List<StoredScheduleOverride> {
        val raw = prefs.getString(KEY_OVERRIDES, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val rows = JSONArray(raw)
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val value = row.optJSONObject("value") ?: continue
                    val weekday = value.optInt("weekday")
                    val startSection = value.optInt("startSection")
                    val endSection = value.optInt("endSection")
                    if (weekday !in 1..7 || startSection !in 1..11 || endSection !in startSection..11) continue
                    add(
                        StoredScheduleOverride(
                            termCode = row.optString("termCode"),
                            courseName = row.optString("courseName"),
                            originalWeekday = row.optInt("originalWeekday"),
                            originalStartSection = row.optInt("originalStartSection"),
                            originalEndSection = row.optInt("originalEndSection"),
                            value = ScheduleCourseOverride(
                                weekday = weekday,
                                startSection = startSection,
                                endSection = endSection,
                                room = value.optString("room"),
                                teacher = value.optString("teacher"),
                                weeks = value.optString("weeks"),
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<StoredScheduleOverride>) {
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(
                JSONObject()
                    .put("termCode", item.termCode)
                    .put("courseName", item.courseName)
                    .put("originalWeekday", item.originalWeekday)
                    .put("originalStartSection", item.originalStartSection)
                    .put("originalEndSection", item.originalEndSection)
                    .put(
                        "value",
                        JSONObject()
                            .put("weekday", item.value.weekday)
                            .put("startSection", item.value.startSection)
                            .put("endSection", item.value.endSection)
                            .put("room", item.value.room)
                            .put("teacher", item.value.teacher)
                            .put("weeks", item.value.weeks),
                    ),
            )
        }
        prefs.edit().putString(KEY_OVERRIDES, rows.toString()).apply()
    }

    private data class StoredScheduleOverride(
        val termCode: String,
        val courseName: String,
        val originalWeekday: Int,
        val originalStartSection: Int,
        val originalEndSection: Int,
        val value: ScheduleCourseOverride,
    ) {
        fun matches(term: String, group: XmuScheduleGroup): Boolean =
            termCode == term &&
                normalizeCourseTitle(courseName) == normalizeCourseTitle(group.courseName) &&
                originalWeekday == group.weekday &&
                originalStartSection == group.startSection &&
                originalEndSection == group.endSection
    }

    private companion object {
        const val PREFS_NAME = "xmu_schedule_overrides"
        const val KEY_OVERRIDES = "course_arrangements_v1"
    }
}

internal fun applyScheduleOverride(group: XmuScheduleGroup, value: ScheduleCourseOverride): XmuScheduleGroup {
    val room = value.room.takeIf(String::isNotBlank)
    val teacher = value.teacher.takeIf(String::isNotBlank)
    return group.copy(
        weekday = value.weekday,
        startSection = value.startSection,
        endSection = value.endSection,
        startTime = scheduleSectionStartTime(value.startSection, group.startTime),
        endTime = scheduleSectionEndTime(value.endSection, group.endTime),
        rooms = listOfNotNull(room),
        teachers = listOfNotNull(teacher),
        weeks = value.weeks,
        variants = listOf(XmuScheduleVariant(room.orEmpty(), teacher.orEmpty(), value.weeks)),
    )
}

internal fun scheduleSectionStartTime(section: Int, fallback: Int): Int = when (section) {
    1 -> 800
    2 -> 850
    3 -> 1010
    4 -> 1100
    5 -> 1430
    6 -> 1520
    7 -> 1630
    8 -> 1720
    9 -> 1900
    10 -> 1950
    11 -> 2040
    else -> fallback
}

internal fun scheduleSectionEndTime(section: Int, fallback: Int): Int = when (section) {
    1 -> 845
    2 -> 935
    3 -> 1055
    4 -> 1145
    5 -> 1515
    6 -> 1605
    7 -> 1715
    8 -> 1805
    9 -> 1945
    10 -> 2035
    11 -> 2125
    else -> fallback
}
