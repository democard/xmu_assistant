package com.xmu.assistant

data class CoursewareCounts(
    val direct: Int,
    val entry: Int,
    val limited: Int,
)

fun courseYears(courses: List<CourseSummary>): List<String> =
    listOf("全部") + courses.mapNotNull { courseYear(it.term) }.distinct()

fun filterCourses(
    courses: List<CourseSummary>,
    selectedYear: String,
    selectedSemester: String,
): List<CourseSummary> = courses.filter { course ->
    // 与 courseYears 用同一归一化函数比较：原始学期名含空格（"2025 - 2026"）时
    // contains("2025-2026") 会静默筛掉全部课程
    (selectedYear == "全部" || courseYear(course.term) == selectedYear) &&
        (selectedSemester == "全部" || courseSemesterLabel(course.semesterCode, course.term) == selectedSemester)
}

fun coursewareCounts(items: List<CoursewareUiItem>): CoursewareCounts {
    var direct = 0
    var entry = 0
    var limited = 0
    items.forEach { item ->
        if (item.referenceId.isNotBlank() || isDirectCoursewareUrl(item.sourceUrl)) direct++ else entry++
        if (item.downloadStatus == COURSEWARE_STATUS_LIMITED) limited++
    }
    return CoursewareCounts(direct = direct, entry = entry, limited = limited)
}

fun coursewareDownloadEnabled(selectedIds: Set<String>, downloadLoading: Boolean): Boolean =
    selectedIds.isNotEmpty() && !downloadLoading

/** 轮询间隔校验：空串视为合法（保存时回退默认值），非空必须为 1-300 的整数。 */
fun pollIntervalSecondsValid(value: String): Boolean {
    if (value.isBlank()) return true
    val parsed = value.toIntOrNull() ?: return false
    return parsed in 1..300
}

/** SMTP 端口校验：逗号分隔的多个端口，每段必须是 1-65535 的整数。空串视为合法（保存时回退默认值）。 */
fun smtpPortsValid(ports: String): Boolean {
    if (ports.isBlank()) return true
    return ports.split(",").all { part ->
        val port = part.trim().toIntOrNull() ?: return false
        port in 1..65535
    }
}

fun retainAvailableCoursewareSelection(
    selectedIds: Set<String>,
    items: List<CoursewareUiItem>,
): Set<String> = selectedIds.intersect(items.map { it.id }.toSet())
