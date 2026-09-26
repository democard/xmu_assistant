package com.xmu.assistant

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 整快照异步落盘的顺序门：课程列表与不同课程的课件允许并行刷新，完成后都会写同一个
 * academic_cache_json。revision 在主线程接纳新快照时递增；后台写任务串行进入，只有仍是
 * 最新 revision 的任务可以写，避免旧快照晚到覆盖新快照。
 */
internal class LatestSnapshotWriteGate {
    private val latestRevision = AtomicLong(0L)
    private val writeLock = Any()

    fun nextRevision(): Long = latestRevision.incrementAndGet()

    fun currentRevision(): Long = latestRevision.get()

    /** 后台读取只能接替它发起时的版本，不能抢走期间已被前台推进的新版本。 */
    fun advanceIfCurrent(revision: Long): Boolean = latestRevision.compareAndSet(revision, revision + 1)

    /** 清理方等待已进入的同步本地写入结束，再作废其余排队快照后清空持久化。 */
    fun invalidateAndWait(): Long = synchronized(writeLock) { nextRevision() }

    fun persistIfLatest(revision: Long, persist: () -> Unit): Boolean = synchronized(writeLock) {
        if (revision != latestRevision.get()) return@synchronized false
        persist()
        true
    }
}

data class AcademicCacheSnapshot(
    val courses: List<CourseSummary> = emptyList(),
    val coursesUpdatedAtMillis: Long = 0L,
    val coursewareByCourse: Map<String, List<CoursewareUiItem>> = emptyMap(),
    val coursewareUpdatedAtMillis: Map<String, Long> = emptyMap(),
) {
    companion object {
        /** 进程级课件缓存快照：转屏（Activity 重建）复用内存数据，避免课程+课件大 JSON
         *  （可达数百 KB）每次重建都全量重解析；登出/换号清理路径置 null（跟随账号会话）。
         *  与 ScheduleSectionState 的进程级课表快照同范式。 */
        @Volatile
        private var processCache: AcademicCacheSnapshot? = null

        /** MainActivity 与 CoursewareSectionState 的同一持久化通道必须共用一把门。 */
        private val persistenceGate = LatestSnapshotWriteGate()

        fun currentProcessCache(): AcademicCacheSnapshot? = processCache

        fun updateProcessCache(snapshot: AcademicCacheSnapshot?): Long {
            processCache = snapshot
            return persistenceGate.nextRevision()
        }

        fun persistIfLatest(revision: Long, persist: () -> Unit): Boolean =
            persistenceGate.persistIfLatest(revision, persist)
    }
}

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
