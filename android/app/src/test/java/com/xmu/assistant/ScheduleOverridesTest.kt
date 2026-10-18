package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleOverridesTest {
    @Test
    fun `override updates visible arrangement and derives section times`() {
        val original = XmuScheduleGroup(
            weekday = 2,
            startSection = 3,
            endSection = 4,
            startTime = 1010,
            endTime = 1145,
            courseName = "数学建模",
            rooms = listOf("坤銮楼 B207"),
            teachers = listOf("张老师"),
            weeks = "第 1—16 周",
        )

        val changed = applyScheduleOverride(
            original,
            ScheduleCourseOverride(4, 7, 8, "学武楼 A305", "李老师 · 教学班 02", "第 2—15 周"),
        )

        assertEquals(4, changed.weekday)
        assertEquals(7, changed.startSection)
        assertEquals(8, changed.endSection)
        assertEquals(1630, changed.startTime)
        assertEquals(1805, changed.endTime)
        assertEquals(listOf("学武楼 A305"), changed.rooms)
        assertEquals(listOf("李老师 · 教学班 02"), changed.teachers)
        assertEquals("第 2—15 周", changed.weeks)
    }
}
