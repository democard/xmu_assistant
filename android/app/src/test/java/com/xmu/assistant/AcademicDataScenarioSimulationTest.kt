package com.xmu.assistant

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed seeds and explicit dates keep malformed-data simulations deterministic and offline. */
class AcademicDataScenarioSimulationTest {
    @Test
    fun `random mixed ranges retain identical weekly results in parsing filtering and indexing`() {
        val random = Random(8675309)
        val expectedByCourse = linkedMapOf<String, Set<Int>>()
        val entries = (1..200).map { course ->
            val expected = mutableSetOf<Int>()
            val expression = (1..3).joinToString(if (course % 2 == 0) "；" else ",") {
                val first = random.nextInt(1, 41)
                val last = if (course % 17 == 0) Int.MAX_VALUE else random.nextInt(first, 61)
                val parity = random.nextInt(3)
                (1..25).filter { it >= first && it <= last && (parity == 2 || it % 2 == parity) }
                    .forEach(expected::add)
                val suffix = when (parity) { 0 -> "（双）"; 1 -> "(单)"; else -> "（单双）" }
                "$first-$last 周$suffix"
            }
            expectedByCourse["course-$course"] = expected
            val parsed = parseXmuWeekExpression(expression)
            assertTrue(parsed.parseable)
            assertEquals(expression, expected, parsed.weeks)
            XmuScheduleEntry(1, 1, 2, 800, 940, "course-$course", "room", "teacher", expression, "20261")
        }
        val index = indexXmuScheduleByWeek(entries)
        for (week in 1..25) {
            val expected = expectedByCourse.filterValues { week in it }.keys.toList()
            assertEquals("week=$week", expected, entries.forWeek(week).map { it.courseName })
            assertEquals("week=$week", expected, index.getValue(week).map { it.courseName })
        }
    }

    @Test
    fun `exam reminder malformed dates and times stay isolated from valid neighboring rows`() {
        val samples = listOf(
            "2026-02-30" to "08:00-10:00",
            "2026-06-14" to "25:00-26:00",
            "2026-06-14" to "08:99-10:00",
            "not-a-date" to "08:00-10:00",
            "2026-06-14" to "待定",
        )
        for ((date, time) in samples) {
            val exam = XmuExam("bad", "异常数据", date, time, "", "", "")
            assertNull(ExamReminder.examReminderTriggerAtMillis(exam, 30))
        }
        val valid = XmuExam("good", "期末考试", "2026-06-14", "00:10-01:40", "", "", "")
        val expected = LocalDate.of(2026, 6, 13).atTime(23, 40)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, ExamReminder.examReminderTriggerAtMillis(valid, 30))
    }

    @Test
    fun `corrupted cached scores round trip then merge valid simulation without poisoning metrics`() {
        val cached = """[
            {"courseCode":"good","credit":3,"score":90,"gradePoint":4,"countsForStatistics":true},
            {"courseCode":"bad","credit":1e999,"score":"NaN","gradePoint":"Infinity","countsForStatistics":true},
            null, "unexpected row"
        ]"""
        val restored = xmuScoreRecordsFromJson(cached)
        val roundTripped = xmuScoreRecordsFromJson(xmuScoreRecordsToJson(restored))
        assertEquals(restored, roundTripped)
        val summary = xmuSimulatedSummary(roundTripped, listOf("80" to "1", "NaN" to "3", "90" to "Infinity"))

        assertEquals(3.5, requireNotNull(summary.averageGpa), 0.0)
        assertEquals(3.75, requireNotNull(summary.weightedGpa), 0.0)
        assertEquals(85.0, requireNotNull(summary.averageScore), 0.0)
        assertEquals(87.5, requireNotNull(summary.weightedScore), 0.0)
        assertEquals(3.0, summary.completedCredits, 0.0)
    }
}
