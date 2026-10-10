package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Read-only compatibility; timetable rosters must not be used to infer own attendance. */
internal class RollcallNumberCodeLookup(
    private val cookieHeader: String,
    private val transport: QueryHttpTransport,
) {
    private var courses: JSONArray? = null

    fun complete(
        rollcallId: String,
        detail: Any?,
        courseId: String = "",
        rollcallTime: String = "",
        courseTitle: String = "",
        endTime: String = "",
    ): String {
        findNumberCode(detail)?.let { return it }
        if (rollcallId.isBlank()) return ""
        val metadata = detail as? JSONObject
        val course = metadata?.optJSONObject("course")
        var resolvedCourse = courseId.ifBlank {
            metadata?.optRealString("course_id").orEmpty().ifBlank { course?.optRealString("id").orEmpty() }
        }
        val title = courseTitle.ifBlank {
            metadata?.optRealString("course_title").orEmpty().ifBlank { course?.optRealString("name").orEmpty() }
        }
        if (resolvedCourse.isBlank() && title.isNotBlank() && title != "未知课程") {
            // Scope to enrolled courses, require a unique title; never guess/scan course IDs.
            val enrolled = courses ?: (getJson("/api/my-courses?per_page=1000") as? JSONObject)
                ?.optJSONArray("courses")?.also { courses = it }
            val matches = (0 until (enrolled?.length() ?: 0)).mapNotNull { index ->
                val row = enrolled?.optJSONObject(index) ?: return@mapNotNull null
                val name = firstString(row, "name", "title", "course_title")
                firstString(row, "id", "course_id").takeIf { name == title && it.isNotBlank() }
            }.toSet()
            if (matches.size == 1) resolvedCourse = matches.single()
        }
        if (resolvedCourse.isBlank()) return ""
        val start = rollcallTime.ifBlank { firstString(metadata, "rollcall_time", "start_time", "created_at") }
        val end = endTime.ifBlank { firstString(metadata, "end_time", "deadline") }
        for (date in rollcallCodeDates(start, end)) {
            val query = "course_ids=${URLEncoder.encode(resolvedCourse, "UTF-8")}&rollcall_date=$date"
            val code = matchingTimetableCode(getJson("/api/timetable_rollcalls?$query"), rollcallId, resolvedCourse)
            if (code.isNotBlank()) return code
        }
        return ""
    }

    private fun getJson(path: String): Any? {
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Accept" to "application/json, text/plain, */*",
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        try {
            val response = transport.execute(QueryHttpRequest(
                "https://lnt.xmu.edu.cn$path", "GET", headers, operation = NetworkOperation.ROLLCALL_STATUS,
            ))
            if (response.code == 401 ||
                (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) ||
                isKnownLoginForm(response.body)
            ) throw MainSessionExpiredException()
            // 403 is a resource-level failure, not permission to re-login or submit.
            if (response.code !in 200..299) return null
            return if (response.body.trimStart().startsWith("[")) JSONArray(response.body) else JSONObject(response.body)
        } catch (error: MainSessionExpiredException) {
            throw error
        } catch (_: Exception) {
            return null
        }
    }

    private fun firstString(obj: JSONObject?, vararg keys: String): String =
        keys.firstNotNullOfOrNull { obj?.optRealString(it)?.takeIf(String::isNotBlank) }.orEmpty()
}

internal fun rollcallCodeDates(startTime: String = "", endTime: String = "", now: Instant = Instant.now()): List<String> {
    for ((raw, isStart) in listOf(startTime to true, endTime to false)) {
        val text = raw.trim().replace(' ', 'T')
        val instant = runCatching { OffsetDateTime.parse(text).toInstant() }
            .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.ofHours(8)) }
            .getOrNull() ?: continue
        val day = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        return if (isStart) listOf(day.toString()) else listOf(day.toString(), day.minusDays(1).toString())
    }
    val day = now.atOffset(ZoneOffset.UTC).toLocalDate()
    return listOf(day.toString(), day.minusDays(1).toString())
}

internal fun matchingTimetableCode(payload: Any?, rollcallId: String, courseId: String = ""): String {
    val rows = when (payload) {
        is JSONObject -> payload.optJSONArray("rollcalls")
        is JSONArray -> payload
        else -> null
    } ?: return ""
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val id = row.optRealString("rollcall_id").ifBlank { row.optRealString("id") }
        if (id != rollcallId) continue
        val returnedCourse = row.optRealString("course_id").ifBlank { row.optJSONObject("course")?.optRealString("id").orEmpty() }
        if (courseId.isNotBlank() && returnedCourse.isNotBlank() && returnedCourse != courseId) continue
        if (row.optBoolean("is_radar") || (row.has("is_number") && !row.isNull("is_number") && !row.optBoolean("is_number"))) continue
        // Read only this activity's direct code fields, not nested students/other activities.
        for (key in listOf("number_code", "numberCode", "rollcall_number_code", "rollcallNumberCode")) {
            row.optRealString(key).takeIf(String::isNotBlank)?.let { return it }
        }
    }
    return ""
}
