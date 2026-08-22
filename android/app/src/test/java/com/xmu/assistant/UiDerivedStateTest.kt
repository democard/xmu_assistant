package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDerivedStateTest {
    @Test
    fun `course years prepends all and retains distinct parsed years`() {
        val courses = listOf(
            CourseSummary(id = "course-1", title = "课程一", term = "2025-2026 第一学期"),
            CourseSummary(id = "course-2", title = "课程二", term = "2025-2026 第二学期"),
            CourseSummary(id = "course-3", title = "课程三", term = "无学年"),
        )

        assertEquals(listOf("全部", "2025-2026"), courseYears(courses))
    }

    @Test
    fun `course filter requires both selected year and semester`() {
        val courses = listOf(
            CourseSummary(id = "course-1", title = "课程一", term = "2025-2026 第一学期"),
            CourseSummary(id = "course-2", title = "课程二", term = "2025-2026 第二学期"),
            CourseSummary(id = "course-3", title = "课程三", term = "2024-2025 第一学期"),
        )

        assertEquals(
            listOf("course-1"),
            filterCourses(courses, selectedYear = "2025-2026", selectedSemester = "第一学期").map { it.id },
        )
    }

    @Test
    fun `courseware counts distinguish direct entry and limited items`() {
        val items = listOf(
            coursewareItem(
                id = "direct",
                sourceUrl = "https://platform.example/courseware.pdf",
                downloadStatus = COURSEWARE_STATUS_LIMITED,
            ),
            coursewareItem(id = "entry"),
        )

        assertEquals(CoursewareCounts(direct = 1, entry = 1, limited = 1), coursewareCounts(items))
    }

    @Test
    fun `courseware download requires a selection and idle downloader`() {
        assertFalse(coursewareDownloadEnabled(emptySet(), downloadLoading = false))
        assertFalse(coursewareDownloadEnabled(setOf("item-1"), downloadLoading = true))
        assertTrue(coursewareDownloadEnabled(setOf("item-1"), downloadLoading = false))
    }

    @Test
    fun `courseware refresh retains only selections present in refreshed items`() {
        val refreshedItems = listOf(
            coursewareItem(id = "still-available"),
            coursewareItem(id = "new-item"),
        )

        assertEquals(
            setOf("still-available"),
            retainAvailableCoursewareSelection(
                selectedIds = setOf("still-available", "removed-item"),
                items = refreshedItems,
            ),
        )
    }

    @Test
    fun `poll interval accepts blank and in range integers only`() {
        assertTrue(pollIntervalSecondsValid(""))
        assertTrue(pollIntervalSecondsValid("1"))
        assertTrue(pollIntervalSecondsValid("30"))
        assertTrue(pollIntervalSecondsValid("300"))
        assertFalse(pollIntervalSecondsValid("0"))
        assertFalse(pollIntervalSecondsValid("301"))
        assertFalse(pollIntervalSecondsValid("abc"))
        assertFalse(pollIntervalSecondsValid("30.5"))
        assertFalse(pollIntervalSecondsValid("-5"))
    }

    @Test
    fun `smtp ports accept comma separated in range values only`() {
        assertTrue(smtpPortsValid(""))
        assertTrue(smtpPortsValid("465"))
        assertTrue(smtpPortsValid("465,587"))
        assertTrue(smtpPortsValid(" 465 , 587 "))
        assertTrue(smtpPortsValid("1"))
        assertTrue(smtpPortsValid("65535"))
        assertFalse(smtpPortsValid("0"))
        assertFalse(smtpPortsValid("65536"))
        assertFalse(smtpPortsValid("465,abc"))
        assertFalse(smtpPortsValid("465,587,"))
        assertFalse(smtpPortsValid("465,,587"))
    }

    private fun coursewareItem(
        id: String,
        referenceId: String = "",
        sourceUrl: String = "",
        downloadStatus: String = COURSEWARE_STATUS_AVAILABLE,
    ) = CoursewareUiItem(
        id = id,
        courseId = "course-1",
        activityId = "activity-$id",
        title = "课件 $id",
        filename = "$id.pdf",
        type = "file",
        referenceId = referenceId,
        sourceUrl = sourceUrl,
        downloadStatus = downloadStatus,
    )
}
