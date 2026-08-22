package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 核心成绩统计逻辑回归测试（此前 xmuScoreSummary 零覆盖）。 */
class XmuScoreSummaryTest {

    private fun record(
        credit: Double,
        score: Double? = null,
        gradePoint: Double? = null,
        countsForStatistics: Boolean = true,
        countsForCompletedCredit: Boolean = true,
    ) = XmuScoreRecord(
        courseCode = "c", courseName = "n", term = "t", termCode = "tc",
        credit = credit, score = score, gradePoint = gradePoint,
        countsForStatistics = countsForStatistics,
        countsForCompletedCredit = countsForCompletedCredit,
    )

    @Test
    fun `empty records produce null stats and zero credits`() {
        val summary = xmuScoreSummary(emptyList())
        assertNull(summary.averageGpa)
        assertNull(summary.weightedGpa)
        assertNull(summary.averageScore)
        assertNull(summary.weightedScore)
        assertEquals(0.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `single record yields identical average and weighted metrics`() {
        val summary = xmuScoreSummary(listOf(record(credit = 2.0, score = 97.0, gradePoint = 4.0)))
        assertEquals(4.0, summary.averageGpa!!, 1e-9)
        assertEquals(4.0, summary.weightedGpa!!, 1e-9)
        assertEquals(97.0, summary.averageScore!!, 1e-9)
        assertEquals(97.0, summary.weightedScore!!, 1e-9)
        assertEquals(2.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `weighted metrics use credit weights while averages are unweighted`() {
        // A: 3 学分 4.0 绩点 90 分；B: 1 学分 2.0 绩点 60 分
        val summary = xmuScoreSummary(
            listOf(
                record(credit = 3.0, score = 90.0, gradePoint = 4.0),
                record(credit = 1.0, score = 60.0, gradePoint = 2.0),
            ),
        )
        assertEquals(3.0, summary.averageGpa!!, 1e-9)      // (4+2)/2
        assertEquals(3.5, summary.weightedGpa!!, 1e-9)     // (4*3 + 2*1)/4
        assertEquals(75.0, summary.averageScore!!, 1e-9)   // (90+60)/2
        assertEquals(82.5, summary.weightedScore!!, 1e-9)  // (90*3 + 60*1)/4
        assertEquals(4.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `non-statistics record is excluded from metrics but still adds completed credit`() {
        val summary = xmuScoreSummary(
            listOf(
                record(credit = 2.0, score = 97.0, gradePoint = 4.0, countsForStatistics = true),
                record(credit = 3.0, score = null, gradePoint = null, countsForStatistics = false, countsForCompletedCredit = true),
            ),
        )
        assertEquals(4.0, summary.averageGpa!!, 1e-9)
        assertEquals(97.0, summary.averageScore!!, 1e-9)
        assertEquals(5.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `zero total credit keeps averages but makes weighted metrics null`() {
        val summary = xmuScoreSummary(listOf(record(credit = 0.0, score = 80.0, gradePoint = 3.0)))
        assertEquals(3.0, summary.averageGpa!!, 1e-9)
        assertEquals(80.0, summary.averageScore!!, 1e-9)
        assertNull(summary.weightedGpa)
        assertNull(summary.weightedScore)
    }

    @Test
    fun `metrics are rounded to two decimals`() {
        val summary = xmuScoreSummary(listOf(record(credit = 3.0, score = 88.0, gradePoint = 3.67)))
        assertEquals(3.67, summary.averageGpa!!, 1e-9)
        // 加权使用 3 学分：同样单记录 → 等于平均
        assertEquals(3.67, summary.weightedGpa!!, 1e-9)
    }
}
