package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTermCoordinateCoursesTest {
    @Test fun `only courses from active semester are available for coordinates`() {
        val courses = listOf(
            CourseSummary("current", "数学建模", term = "2026-2027学年 第一学期", semesterCode = "2026-1"),
            CourseSummary("old", "高等数学", term = "2025-2026学年 第二学期", semesterCode = "2025-2"),
            CourseSummary("fallback", "体育", term = "", semesterCode = ""),
            CourseSummary("unknown-old", "大学英语", term = "", semesterCode = ""),
        )
        val entries = listOf(
            XmuScheduleEntry(2, 3, 4, 1010, 1150, "数学建模", "坤銮楼 B207", "张老师", "1-16周", "20261"),
            XmuScheduleEntry(5, 3, 4, 1010, 1150, "体育", "一期操场", "李老师", "1-16周", "20261"),
        )

        val result = currentTermCoordinateCourses(courses, "20261", entries)

        assertEquals(listOf("current", "fallback"), result.map { it.id })
    }

    @Test fun `no active term never leaks historical courses`() {
        val courses = listOf(CourseSummary("old", "高等数学", semesterCode = "2025-2"))

        assertTrue(currentTermCoordinateCourses(courses, "", emptyList()).isEmpty())
    }

    @Test fun `chinese semester label matches compact timetable code`() {
        val course = CourseSummary("current", "数学建模", term = "2026-2027学年 第一学期")

        assertEquals(listOf(course), currentTermCoordinateCourses(listOf(course), "20261", emptyList()))
    }
}
