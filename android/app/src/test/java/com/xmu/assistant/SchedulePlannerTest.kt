package com.xmu.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePlannerTest {
    @Test
    fun `week parser supports ranges and odd even expressions`() {
        assertEquals((1..16).toSet(), parseXmuWeekExpression("1-16周").weeks)
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), parseXmuWeekExpression("1-15周，单周").weeks)
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), parseXmuWeekExpression("2-16周，双周").weeks)
        assertEquals(setOf(1, 2, 7, 9, 10, 11, 12), parseXmuWeekExpression("1-2,7,9-12周").weeks)
        assertEquals(
            setOf(1, 2, 4, 6, 8, 9, 11, 13),
            parseXmuWeekExpression("1-2,4-6双周,8-9,11-13单周").weeks,
        )
    }

    @Test
    fun `week parser accepts chinese dashes parentheses and a standalone parity suffix`() {
        assertEquals(
            setOf(1, 3, 5, 7, 9, 11, 13, 15),
            parseXmuWeekExpression("第1—16周（单周）").weeks,
        )
        assertEquals(
            setOf(1, 3, 5, 7, 9, 11, 13, 15),
            parseXmuWeekExpression("1-16周,单周").weeks,
        )
        assertEquals(
            setOf(2, 4, 6, 8, 10, 12, 14, 16),
            parseXmuWeekExpression("1～16周/双").weeks,
        )
    }

    @Test
    fun `malformed or blank week metadata remains visible`() {
        assertFalse(parseXmuWeekExpression("").parseable)
        assertFalse(parseXmuWeekExpression("待定").parseable)
        val entry = fixtureEntry(weeks = "待定")
        assertTrue(isXmuScheduleEntryActiveInWeek(entry, 8))
    }

    @Test
    fun `schedule filtering keeps only selected week`() {
        val entries = listOf(
            fixtureEntry(courseName = "全周", weeks = "1-16周"),
            fixtureEntry(courseName = "单周", weeks = "1-15周，单周"),
            fixtureEntry(courseName = "双周", weeks = "2-16周，双周"),
        )

        assertEquals(listOf("全周", "单周"), entries.forWeek(3).map { it.courseName })
        assertEquals(listOf("全周", "双周"), entries.forWeek(4).map { it.courseName })
    }

    @Test
    fun `official fallback calendar calculates before and teaching weeks`() {
        val calendar = xmuAcademicCalendarForTerm("20261")
        assertEquals(LocalDate.of(2026, 9, 7), calendar?.startDate)
        assertEquals(
            XmuTermPhase.BEFORE,
            xmuAcademicWeekFor(calendar, LocalDate.of(2026, 8, 14)).phase,
        )
        assertEquals(
            1,
            xmuAcademicWeekFor(calendar, LocalDate.of(2026, 9, 7)).week,
        )
        assertEquals(
            2,
            xmuAcademicWeekFor(calendar, LocalDate.of(2026, 9, 14)).week,
        )
        assertEquals(
            XmuTermPhase.AFTER,
            xmuAcademicWeekFor(calendar, LocalDate.of(2027, 1, 10)).phase,
        )
    }

    @Test
    fun `unknown term does not invent a week`() {
        assertNull(xmuAcademicCalendarForTerm("unknown"))
        assertEquals(
            XmuTermPhase.UNKNOWN,
            xmuAcademicWeekFor(null, LocalDate.of(2026, 8, 14)).phase,
        )
    }

    @Test
    fun `official calendar covers recent fall and spring terms`() {
        // 覆盖 2023-2026 学年，任何在校生都能得到正确日期
        assertEquals(LocalDate.of(2023, 9, 11), xmuAcademicCalendarForTerm("20231")?.startDate)
        assertEquals(LocalDate.of(2024, 2, 26), xmuAcademicCalendarForTerm("20232")?.startDate)
        assertEquals(LocalDate.of(2024, 9, 2), xmuAcademicCalendarForTerm("20241")?.startDate)
        assertEquals(LocalDate.of(2025, 2, 17), xmuAcademicCalendarForTerm("20242")?.startDate)
        assertEquals(LocalDate.of(2025, 9, 1), xmuAcademicCalendarForTerm("20251")?.startDate)
        assertEquals(LocalDate.of(2026, 3, 2), xmuAcademicCalendarForTerm("20252")?.startDate)
        assertEquals(LocalDate.of(2026, 9, 7), xmuAcademicCalendarForTerm("20261")?.startDate)
        assertEquals(LocalDate.of(2027, 2, 15), xmuAcademicCalendarForTerm("20262")?.startDate)
    }

    @Test
    fun `inferred calendar recovers term start from current week`() {
        // 模拟开学第 3 周（2026-09-21 周一），系统返回 currentZc=3
        val inferred = xmuCalendarInferredFromCurrentWeek(
            termCode = "20261",
            currentWeek = 3,
            today = LocalDate.of(2026, 9, 21),
        )
        assertEquals(LocalDate.of(2026, 9, 7), inferred.startDate)
        assertEquals("2026—2027学年", inferred.academicYearLabel)
        assertEquals("第一学期", inferred.semesterLabel)
        // 第 3 周应该是教学周
        assertEquals(
            XmuTermPhase.DURING,
            xmuAcademicWeekFor(inferred, LocalDate.of(2026, 9, 21)).phase,
        )
        assertEquals(3, xmuAcademicWeekFor(inferred, LocalDate.of(2026, 9, 21)).week)
    }

    @Test
    fun `inferred calendar handles spring and summer terms`() {
        val spring = xmuCalendarInferredFromCurrentWeek(
            termCode = "20272",
            currentWeek = 5,
            today = LocalDate.of(2027, 3, 15),
        )
        assertEquals("第二学期", spring.semesterLabel)
        assertEquals(LocalDate.of(2027, 2, 15), spring.startDate)

        val summer = xmuCalendarInferredFromCurrentWeek(
            termCode = "20273",
            currentWeek = 2,
            today = LocalDate.of(2027, 6, 21),
        )
        assertEquals("夏季学期", summer.semesterLabel)
    }

    @Test
    fun `inference rejects summer holiday false values`() {
        // 暑假（8 月）手动设第 3 周：反推开学日 7/27 落在暑假，应拒绝
        val rejected = xmuTryInferCalendar(
            termCode = "20261",
            currentWeek = 3,
            today = LocalDate.of(2026, 8, 14),
        )
        assertNull(rejected)
    }

    @Test
    fun `inference accepts real term start after term begins`() {
        // 开学第 3 周（9/21 周一），反推开学日 9/7，落在合理窗口，应接受
        val accepted = xmuTryInferCalendar(
            termCode = "20261",
            currentWeek = 3,
            today = LocalDate.of(2026, 9, 21),
        )
        assertEquals(LocalDate.of(2026, 9, 7), accepted?.startDate)
    }

    @Test
    fun `inference still works late in term beyond the fixed sixty day cap`() {
        // 开学第 12 周（11/27 周五），开学日距今约 81 天，超过固定 60 天上限；
        // 窗口随周次自适应（12*7+7=91 天）后应仍能反推成功。
        val accepted = xmuTryInferCalendar(
            termCode = "20261",
            currentWeek = 12,
            today = LocalDate.of(2026, 11, 27),
        )
        assertEquals(LocalDate.of(2026, 9, 7), accepted?.startDate)

        // 第 18 周（学期末）反推也应成功，覆盖整学期。
        val last = xmuTryInferCalendar(
            termCode = "20261",
            currentWeek = 18,
            today = LocalDate.of(2027, 1, 8),
        )
        assertEquals(LocalDate.of(2026, 9, 7), last?.startDate)
    }

    @Test
    fun `adaptive window still rejects a nonsense week during summer`() {
        // 暑假（8/14）系统返回 currentZc=1（暑假不可靠值），即便窗口自适应，
        // 反推开学日 8/10 落在暑假窗口外（秋季应 9 月开学），应拒绝。
        val rejected = xmuTryInferCalendar(
            termCode = "20261",
            currentWeek = 1,
            today = LocalDate.of(2026, 8, 14),
        )
        assertNull(rejected)
    }

    @Test
    fun `summer short term accepts a real june start and rejects august holiday`() {
        // 20273 夏季短学期：6/14（周一）开学，6/22（周二）是第 2 周，反推开学日应回到 6/14
        val accepted = xmuTryInferCalendar(
            termCode = "20273",
            currentWeek = 2,
            today = LocalDate.of(2027, 6, 22),
        )
        assertEquals(LocalDate.of(2027, 6, 14), accepted?.startDate)

        // 暑假 8 月 currentZc=1 反推 8/9（8/14 周六所在周周一）：夏季窗口 6/1-7/31 之外，应拒绝
        val rejected = xmuTryInferCalendar(
            termCode = "20273",
            currentWeek = 1,
            today = LocalDate.of(2027, 8, 14),
        )
        assertNull(rejected)
    }

    @Test
    fun `manual calibration becomes an automatic baseline that advances with dates`() {
        // 手动校准语义：用户指定「今天是第 5 周」（2026-10-05 周一）→ 反推开学日 9/7。
        val calibration = xmuTryInferCalendar(
            termCode = "20261",
            currentWeek = 5,
            today = LocalDate.of(2026, 10, 5),
        )
        assertEquals(LocalDate.of(2026, 9, 7), calibration?.startDate)

        // 校准当天：应显示第 5 周（手动基准生效）
        assertEquals(
            5,
            xmuAcademicWeekFor(calibration, LocalDate.of(2026, 10, 5)).week,
        )
        // 校准后同周内任意一天：仍是第 5 周
        assertEquals(
            5,
            xmuAcademicWeekFor(calibration, LocalDate.of(2026, 10, 9)).week,
        )
        // 跨过周一：自动推进到第 6 周（以手动基准为锚点的自动判断）
        assertEquals(
            6,
            xmuAcademicWeekFor(calibration, LocalDate.of(2026, 10, 12)).week,
        )
        // 学期末仍在自动推进
        assertEquals(
            18,
            xmuAcademicWeekFor(calibration, LocalDate.of(2027, 1, 8)).week,
        )
    }

    @Test
    fun `inferred calendar defaults to nineteen weeks so week nineteen courses survive`() {
        // 表外学期（官方表没有）反推时默认 19 周：第 19 周课程不能被钳制丢失。
        val inferred = xmuCalendarInferredFromCurrentWeek(
            termCode = "20271",
            currentWeek = 3,
            today = LocalDate.of(2027, 9, 20),
        )
        assertEquals(19, inferred.totalWeeks)
        // 第 19 周在学期内，week 计算不被 coerce 到 18
        val week19 = xmuAcademicWeekFor(
            inferred,
            inferred.startDate.plusDays(18L * 7L),
        )
        assertEquals(XmuTermPhase.DURING, week19.phase)
        assertEquals(19, week19.week)

        // xmuTryInferCalendar 默认也是 19 周
        val accepted = xmuTryInferCalendar(
            termCode = "20271",
            currentWeek = 3,
            today = LocalDate.of(2027, 9, 20),
        )
        assertEquals(19, accepted?.totalWeeks)
    }

    private fun fixtureEntry(
        courseName: String = "fixture",
        weeks: String = "1-16周",
    ) = XmuScheduleEntry(
        weekday = 1,
        startSection = 1,
        endSection = 2,
        startTime = 800,
        endTime = 940,
        courseName = courseName,
        room = "C204",
        teacher = "老师",
        weeks = weeks,
        termCode = "20261",
    )
}
