package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleLayoutTest {
    private fun course(day: Int, start: Int, end: Int) = XmuScheduleGroup(
        day, start, end, 800, 940, "测试课程", listOf("翔安校区 学武楼 B-205", "思明校区 海韵园 A-301"), emptyList(), "1-16周",
    )

    @Test fun `week grid always preserves seven days and eleven sections`() {
        assertEquals(ScheduleGridAxes((1..7).toList(), (1..11).toList()), scheduleGridAxes())
    }

    @Test fun `classroom summary keeps every distinct room instead of hiding alternatives`() {
        val group = course(1, 1, 2)
        assertEquals("翔安校区 学武楼 B-205\n思明校区 海韵园 A-301", scheduleLocationSummary(group))
        assertEquals("教室未标注", scheduleLocationSummary(group.copy(rooms = listOf("", " "))))
        assertEquals("B-205", scheduleLocationSummary(group.copy(rooms = listOf("B-205", " B-205 "))))
        assertEquals("教室未标注", scheduleLocationSummary(group.copy(rooms = listOf("null", " NULL "))))
    }

    @Test fun `partial and chained overlaps stay in one visible cluster while adjacent lessons remain separate`() {
        val courses = listOf(course(1, 1, 2), course(1, 2, 3), course(1, 3, 4), course(1, 5, 6), course(2, 1, 2))
        val clusters = scheduleGridClusters(courses.reversed())
        assertEquals(listOf(3, 1, 1), clusters.map { it.courses.size })
        assertEquals(1, clusters.first().startSection)
        assertEquals(4, clusters.first().endSection)
        val positions = scheduleGridPlacements(courses)
        assertEquals(listOf(0, 1, 0, 0, 0), positions.map { it.lane })
        assertEquals(courses, positions.map { it.course })
    }
}
