package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模拟成绩回归测试：绩点换算表边界、单行输入解析过滤、汇总合并口径
 * （模拟课计入四项统计但不计入已修总学分；无效行静默跳过）。
 */
class XmuScoreSimulationTest {

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

    // ---- 换算表（厦大官方 4.0 分制）边界 ----

    @Test
    fun `grade point table band lower bounds map to their grade`() {
        val cases = mapOf(
            100.0 to 4.0, 95.0 to 4.0, 90.0 to 4.0, // A+/A 同为 4.0
            89.0 to 3.7, 85.0 to 3.7,
            84.0 to 3.3, 81.0 to 3.3,
            80.0 to 3.0, 78.0 to 3.0,
            77.0 to 2.7, 75.0 to 2.7,
            74.0 to 2.3, 72.0 to 2.3,
            71.0 to 2.0, 68.0 to 2.0,
            67.0 to 1.5, 64.0 to 1.5,
            63.0 to 1.0, 60.0 to 1.0,
            59.0 to 0.0, 0.0 to 0.0,
        )
        cases.forEach { (score, expected) ->
            assertEquals("score=$score", expected, xmuSimulatedGradePoint(score), 1e-9)
        }
    }

    @Test
    fun `fractional scores fall into the band below the next threshold`() {
        // 分数段按 >= 下界判定：94.9 仍属 90-94 档（4.0），84.9 属 81-84 档，59.9 已不及格
        assertEquals(4.0, xmuSimulatedGradePoint(94.9), 1e-9)
        assertEquals(3.3, xmuSimulatedGradePoint(84.9), 1e-9)
        assertEquals(0.0, xmuSimulatedGradePoint(59.9), 1e-9)
    }

    // ---- 单行输入解析与无效过滤 ----

    @Test
    fun `valid row converts score to grade point and joins statistics without completed credit`() {
        val sim = xmuSimulatedScoreRecord("92", "3")
        assertNotNull(sim)
        assertEquals(92.0, sim!!.score!!, 1e-9)
        assertEquals(4.0, sim.gradePoint!!, 1e-9)
        assertEquals(3.0, sim.credit, 1e-9)
        assertEquals(true, sim.countsForStatistics)
        assertEquals(false, sim.countsForCompletedCredit)
    }

    @Test
    fun `blank or non numeric rows are ignored`() {
        assertNull(xmuSimulatedScoreRecord("", ""))
        assertNull(xmuSimulatedScoreRecord("  ", " "))
        assertNull(xmuSimulatedScoreRecord("abc", "3"))
        assertNull(xmuSimulatedScoreRecord("92", "x"))
        assertNull(xmuSimulatedScoreRecord("9 2", "3"))
    }

    @Test
    fun `score out of percent range or non positive credit is ignored`() {
        assertNull(xmuSimulatedScoreRecord("100.5", "3"))
        assertNull(xmuSimulatedScoreRecord("101", "2"))
        assertNull(xmuSimulatedScoreRecord("-1", "2"))
        assertNull(xmuSimulatedScoreRecord("92", "0"))
        assertNull(xmuSimulatedScoreRecord("92", "-2"))
    }

    @Test
    fun `NaN and infinity texts never pass validation`() {
        // toDoubleOrNull 会放行 "NaN"/"Infinity"，解析层必须拦下
        assertNull(xmuSimulatedScoreRecord("NaN", "3"))
        assertNull(xmuSimulatedScoreRecord("Infinity", "3"))
        assertNull(xmuSimulatedScoreRecord("92", "NaN"))
        assertNull(xmuSimulatedScoreRecord("92", "Infinity"))
    }

    @Test
    fun `boundary scores 0 and 100 are accepted by the row parser`() {
        // 0-100 为闭区间：两端合法（换算表 0→0.0 / 100→4.0），解析层不得误拒
        val zero = xmuSimulatedScoreRecord("0", "2")
        assertNotNull(zero)
        assertEquals(0.0, zero!!.score!!, 1e-9)
        assertEquals(0.0, zero.gradePoint!!, 1e-9)
        val full = xmuSimulatedScoreRecord("100", "1.5")
        assertNotNull(full)
        assertEquals(100.0, full!!.score!!, 1e-9)
        assertEquals(4.0, full.gradePoint!!, 1e-9)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val sim = xmuSimulatedScoreRecord(" 88 ", " 2.5 ")
        assertEquals(88.0, sim!!.score!!, 1e-9)
        assertEquals(3.7, sim.gradePoint!!, 1e-9)
        assertEquals(2.5, sim.credit, 1e-9)
    }

    // ---- 汇总合并 ----

    @Test
    fun `no simulation rows keeps the real summary untouched`() {
        val records = listOf(record(credit = 2.0, score = 90.0, gradePoint = 4.0))
        assertEquals(xmuScoreSummary(records), xmuSimulatedSummary(records, emptyList()))
    }

    @Test
    fun `all invalid simulation rows keep the real summary untouched`() {
        val records = listOf(record(credit = 2.0, score = 90.0, gradePoint = 4.0))
        assertEquals(
            xmuScoreSummary(records),
            xmuSimulatedSummary(records, listOf("" to "", "abc" to "1", "105" to "2", "88" to "0")),
        )
    }

    @Test
    fun `single simulation over empty records yields its own metrics and zero completed credit`() {
        val summary = xmuSimulatedSummary(emptyList(), listOf("92" to "3"))
        assertEquals(4.0, summary.averageGpa!!, 1e-9)
        assertEquals(4.0, summary.weightedGpa!!, 1e-9)
        assertEquals(92.0, summary.averageScore!!, 1e-9)
        assertEquals(92.0, summary.weightedScore!!, 1e-9)
        assertEquals(0.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `simulation merges with real records for all four metrics`() {
        // 真实：2 学分 90 分绩点 4.0；模拟：2 学分 80 分（换算绩点 3.0，不计已修学分）
        val summary = xmuSimulatedSummary(
            listOf(record(credit = 2.0, score = 90.0, gradePoint = 4.0)),
            listOf("80" to "2"),
        )
        assertEquals(3.5, summary.averageGpa!!, 1e-9)     // (4.0+3.0)/2
        assertEquals(3.5, summary.weightedGpa!!, 1e-9)    // (4.0*2 + 3.0*2)/4
        assertEquals(85.0, summary.averageScore!!, 1e-9)  // (90+80)/2
        assertEquals(85.0, summary.weightedScore!!, 1e-9) // (90*2 + 80*2)/4
        assertEquals(2.0, summary.completedCredits, 1e-9) // 模拟课不计入已修总学分
    }

    @Test
    fun `valid and invalid rows mixed only count the valid ones`() {
        // 真实：3 学分 88 分绩点 3.7；有效模拟：95 分（4.0/1 学分）与 60 分（1.0/1 学分）
        val summary = xmuSimulatedSummary(
            listOf(record(credit = 3.0, score = 88.0, gradePoint = 3.7)),
            listOf("" to "5", "95" to "1", "bad" to "2", "60" to "1"),
        )
        assertEquals(2.9, summary.averageGpa!!, 1e-9)     // (3.7+4.0+1.0)/3
        assertEquals(3.22, summary.weightedGpa!!, 1e-9)   // (3.7*3 + 4.0 + 1.0)/5
        assertEquals(81.0, summary.averageScore!!, 1e-9)  // (88+95+60)/3
        assertEquals(83.8, summary.weightedScore!!, 1e-9) // (88*3 + 95 + 60)/5
        assertEquals(3.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `duplicate simulation rows each count independently`() {
        // 设计口径：模拟行不按课程去重——同课程添两行（如两次考试预估）各自独立参与统计
        val summary = xmuSimulatedSummary(emptyList(), listOf("80" to "2", "80" to "2"))
        assertEquals(3.0, summary.averageGpa!!, 1e-9)
        assertEquals(3.0, summary.weightedGpa!!, 1e-9)
        assertEquals(80.0, summary.averageScore!!, 1e-9)
        assertEquals(80.0, summary.weightedScore!!, 1e-9)
        assertEquals(0.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `many simulation rows merge with per-row rounding unaffected`() {
        // 极端多行：换算逐行、舍入只在汇总出口一次（与少行同口径）
        val rows = listOf("60" to "1", "64" to "1", "68" to "1", "72" to "1", "75" to "1")
        val summary = xmuSimulatedSummary(emptyList(), rows)
        assertEquals(1.9, summary.averageGpa!!, 1e-9)      // (1.0+1.5+2.0+2.3+2.7)/5
        assertEquals(1.9, summary.weightedGpa!!, 1e-9)     // 学分同为 1，加权=算术
        assertEquals(67.8, summary.averageScore!!, 1e-9)   // (60+64+68+72+75)/5
        assertEquals(67.8, summary.weightedScore!!, 1e-9)  // 学分同为 1，加权=算术
        assertEquals(0.0, summary.completedCredits, 1e-9)
    }

    @Test
    fun `merged metrics are rounded to two decimals`() {
        val summary = xmuSimulatedSummary(
            listOf(record(credit = 3.0, score = 88.0, gradePoint = 3.7)),
            listOf("92" to "2"),
        )
        assertEquals(3.85, summary.averageGpa!!, 1e-9)    // (3.7+4.0)/2
        assertEquals(3.82, summary.weightedGpa!!, 1e-9)   // (3.7*3 + 4.0*2)/5
        assertEquals(90.0, summary.averageScore!!, 1e-9)  // (88+92)/2
        assertEquals(89.6, summary.weightedScore!!, 1e-9) // (88*3 + 92*2)/5
    }
}
