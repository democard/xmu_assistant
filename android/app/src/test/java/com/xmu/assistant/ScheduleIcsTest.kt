package com.xmu.assistant

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleIcsTest {

    // 20251 学期官方校历：开学日 2025-09-01（周一），共 19 周
    private val calendar = XmuAcademicCalendar(
        termCode = "20251",
        academicYearLabel = "2025—2026学年",
        semesterLabel = "第一学期",
        startDate = LocalDate.of(2025, 9, 1),
        endDate = LocalDate.of(2026, 1, 10),
        totalWeeks = 19,
    )

    private fun entry(
        weekday: Int = 1,
        weeks: String = "1-16周",
        courseName: String = "高等数学",
        room: String = "海韵教学楼101",
        teacher: String = "张三",
        startTime: Int = 830,
    ) = XmuScheduleEntry(
        weekday = weekday,
        startSection = 1,
        endSection = 2,
        startTime = startTime,
        endTime = 1020,
        courseName = courseName,
        room = room,
        teacher = teacher,
        weeks = weeks,
        termCode = "20251",
    )

    private fun date(week: Int, weekday: Int): String =
        calendar.startDate.plusDays((week - 1L) * 7L + (weekday - 1L))
            .format(DateTimeFormatter.BASIC_ISO_DATE)

    @Test
    fun `returns null without calendar`() {
        assertNull(buildScheduleIcs(listOf(entry()), null))
    }

    @Test
    fun `empty entries produce bare calendar skeleton`() {
        val text = buildScheduleIcs(emptyList(), calendar)!!
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(text.trimEnd().endsWith("END:VCALENDAR"))
        assertFalse(text.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `continuous weeks emit weekly rule anchored at first week`() {
        val text = buildScheduleIcs(listOf(entry()), calendar)!!
        assertEquals(
            "DTSTART:${date(1, 1)}T083000\r\n",
            text.lineSequence().first { it.startsWith("DTSTART:") } + "\r\n",
        )
        assertEquals(
            "DTEND:${date(1, 1)}T102000\r\n",
            text.lineSequence().first { it.startsWith("DTEND:") } + "\r\n",
        )
        assertEquals(
            "RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO;UNTIL=${date(16, 1)}T235959\r\n",
            text.lineSequence().first { it.startsWith("RRULE:") } + "\r\n",
        )
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(text).count())
    }

    @Test
    fun `odd week expression anchors interval two on odd first week`() {
        val text = buildScheduleIcs(listOf(entry(weeks = "1-16周(单周)")), calendar)!!
        assertEquals(
            "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;UNTIL=${date(15, 1)}T235959\r\n",
            text.lineSequence().first { it.startsWith("RRULE:") } + "\r\n",
        )
        assertEquals(
            "DTSTART:${date(1, 1)}T083000\r\n",
            text.lineSequence().first { it.startsWith("DTSTART:") } + "\r\n",
        )
    }

    @Test
    fun `even week expression anchors interval two on even first week`() {
        val text = buildScheduleIcs(listOf(entry(weeks = "2-16周(双周)", weekday = 3)), calendar)!!
        assertEquals(
            "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=WE;UNTIL=${date(16, 3)}T235959\r\n",
            text.lineSequence().first { it.startsWith("RRULE:") } + "\r\n",
        )
        assertEquals(
            "DTSTART:${date(2, 3)}T083000\r\n",
            text.lineSequence().first { it.startsWith("DTSTART:") } + "\r\n",
        )
    }

    @Test
    fun `gapped weeks split into multiple events skipping the gap`() {
        val text = buildScheduleIcs(listOf(entry(weeks = "1-8周,10-16周")), calendar)!!
        val starts = text.lineSequence().filter { it.startsWith("DTSTART:") }.toList()
        // 缺上的第 9 周不得出现在任何事件锚点里；两段各自锚定第 1 / 第 10 周
        assertEquals("DTSTART:${date(1, 1)}T083000", starts[0])
        assertEquals("DTSTART:${date(10, 1)}T083000", starts[1])
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(text).count())
        assertFalse(text.contains(date(9, 1)))
    }

    @Test
    fun `mixed step weeks split by arithmetic run boundaries`() {
        // 7 与 9 组成步长 2 段（UNTIL 截在第 9 周），10-12 回落步长 1 段
        val runs = splitIcsWeekRuns(listOf(7, 9, 10, 11, 12))
        assertEquals(listOf(IcsWeekRun(7, 9, 2), IcsWeekRun(10, 12, 1)), runs)
    }

    @Test
    fun `unparseable weeks fall back to full term weekly coverage`() {
        val text = buildScheduleIcs(listOf(entry(weeks = "看教务通知上课")), calendar)!!
        val rrule = text.lineSequence().first { it.startsWith("RRULE:") }
        assertTrue(rrule.endsWith("UNTIL=${date(calendar.totalWeeks, 1)}T235959"))
        assertTrue(rrule.contains(";INTERVAL=1;"))
    }

    @Test
    fun `text values escape backslash semicolon comma and newline`() {
        val text = buildScheduleIcs(
            listOf(entry(courseName = "C++,Java\\Lisp", teacher = "李四\n王五;助教")),
            calendar,
        )!!
        val summaryLine = text.lineSequence().first { it.startsWith("SUMMARY:") }
        assertEquals("SUMMARY:C++\\,Java\\\\Lisp", summaryLine)
        val descriptionLine = text.lineSequence().first { it.startsWith("DESCRIPTION:") }
        assertEquals(
            """DESCRIPTION:教师：李四\n王五\;助教\n周次：1-16周""",
            descriptionLine,
        )
    }

    @Test
    fun `blank room omits location line and uses stable uid prefix`() {
        val text = buildScheduleIcs(listOf(entry(room = "")), calendar)!!
        assertFalse(text.contains("LOCATION:"))
        assertTrue(text.contains("@xmu-assistant"))
    }

    @Test
    fun `invalid weekday or times are skipped silently`() {
        val text = buildScheduleIcs(
            listOf(entry(weekday = 0), entry(startTime = 0), entry()),
            calendar,
        )!!
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(text).count())
    }

    @Test
    fun `single week event has no rrule line`() {
        val text = buildScheduleIcs(listOf(entry(weeks = "第5周")), calendar)!!
        assertFalse(text.contains("RRULE:"))
        assertTrue(text.contains("DTSTART:${date(5, 1)}T083000"))
    }
}
