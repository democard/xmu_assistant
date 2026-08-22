package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject

data class AcademicCacheSnapshot(
    val courses: List<CourseSummary> = emptyList(),
    val coursesUpdatedAtMillis: Long = 0L,
    val coursewareByCourse: Map<String, List<CoursewareUiItem>> = emptyMap(),
    val coursewareUpdatedAtMillis: Map<String, Long> = emptyMap(),
)

fun AcademicCacheSnapshot.withCourses(
    courses: List<CourseSummary>,
    updatedAtMillis: Long,
): AcademicCacheSnapshot = copy(
    courses = courses,
    coursesUpdatedAtMillis = updatedAtMillis,
)

fun AcademicCacheSnapshot.withCourseware(
    courseId: String,
    items: List<CoursewareUiItem>,
    updatedAtMillis: Long,
): AcademicCacheSnapshot = copy(
    coursewareByCourse = coursewareByCourse + (courseId to items),
    coursewareUpdatedAtMillis = coursewareUpdatedAtMillis + (courseId to updatedAtMillis),
)

fun academicCacheToJson(snapshot: AcademicCacheSnapshot): String {
    val courseware = JSONObject()
    snapshot.coursewareByCourse.forEach { (courseId, items) ->
        courseware.put(courseId, JSONArray(items.map(::coursewareToJson)))
    }
    val coursewareTimes = JSONObject()
    snapshot.coursewareUpdatedAtMillis.forEach { (courseId, timestamp) ->
        coursewareTimes.put(courseId, timestamp)
    }
    return JSONObject()
        .put("courses", JSONArray(snapshot.courses.map(::courseToJson)))
        .put("coursesUpdatedAtMillis", snapshot.coursesUpdatedAtMillis)
        .put("coursewareByCourse", courseware)
        .put("coursewareUpdatedAtMillis", coursewareTimes)
        .toString()
}

fun academicCacheFromJson(value: String): AcademicCacheSnapshot = runCatching {
    if (value.isBlank()) return@runCatching AcademicCacheSnapshot()
    val root = JSONObject(value)
    val coursesArray = root.optJSONArray("courses") ?: JSONArray()
    val courses = (0 until coursesArray.length()).mapNotNull { index ->
        coursesArray.optJSONObject(index)?.let(::courseFromJson)
    }
    val coursewareRoot = root.optJSONObject("coursewareByCourse") ?: JSONObject()
    val courseware = buildMap {
        coursewareRoot.keys().forEach { courseId ->
            val items = coursewareRoot.optJSONArray(courseId) ?: return@forEach
            val decoded = ArrayList<CoursewareUiItem>(items.length())
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index)
                    ?.let { runCatching { coursewareFromJson(it) }.getOrNull() }
                if (item == null || item.courseId != courseId) {
                    decoded.clear()
                    break
                }
                decoded += item
            }
            if (decoded.size == items.length()) {
                put(courseId, decoded)
            }
        }
    }
    val coursewareTimesRoot = root.optJSONObject("coursewareUpdatedAtMillis") ?: JSONObject()
    val coursewareTimes = coursewareTimesRoot.keys().asSequence()
        .filter(courseware::containsKey)
        .associateWith { courseId -> coursewareTimesRoot.optLong(courseId, 0L) }
    AcademicCacheSnapshot(
        courses = courses,
        coursesUpdatedAtMillis = root.optLong("coursesUpdatedAtMillis", 0L),
        coursewareByCourse = courseware,
        coursewareUpdatedAtMillis = coursewareTimes,
    )
}.getOrDefault(AcademicCacheSnapshot())

private fun courseToJson(course: CourseSummary): JSONObject = JSONObject()
    .put("id", course.id)
    .put("title", course.title)
    .put("term", course.term)
    .put("semesterCode", course.semesterCode)

private fun courseFromJson(value: JSONObject): CourseSummary = CourseSummary(
    id = value.optString("id"),
    title = value.optString("title"),
    term = value.optString("term"),
    semesterCode = value.optString("semesterCode"),
)

private fun coursewareToJson(item: CoursewareUiItem): JSONObject = JSONObject()
    .put("id", item.id)
    .put("courseId", item.courseId)
    .put("activityId", item.activityId)
    .put("title", item.title)
    .put("filename", item.filename)
    .put("type", item.type)
    .put("moduleName", item.moduleName)
    .put("referenceId", item.referenceId)
    .put("sourceUrl", item.sourceUrl)
    .put("downloadStatus", item.downloadStatus)
    .put("failureReason", item.failureReason)

private fun coursewareFromJson(value: JSONObject): CoursewareUiItem = CoursewareUiItem(
    id = value.optString("id"),
    courseId = value.optString("courseId"),
    activityId = value.optString("activityId"),
    title = value.optString("title"),
    filename = value.optString("filename"),
    type = value.optString("type"),
    moduleName = value.optString("moduleName"),
    referenceId = value.optString("referenceId"),
    sourceUrl = value.optString("sourceUrl"),
    downloadStatus = value.optString("downloadStatus", COURSEWARE_STATUS_AVAILABLE),
    failureReason = value.optString("failureReason"),
)
