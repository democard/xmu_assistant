package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩缓存 JSON 编解码往返（2026-08-30 补测债）：
 * 三缓存中唯一缺 roundtrip 守护的一环（对照 AcademicCache/ScheduleCache 先例）。
 * 纯模型层，不触碰成绩链路网络代码。
 */
class XmuScoreModelsRoundtripTest {

    private fun record(
        courseCode: String = "CS1001",
        courseName: String = "数据结构",
        score: Double? = 92.0,
        gradePoint: Double? = 4.0,
        countsForStatistics: Boolean = true,
        countsForCompletedCredit: Boolean = true,
        resultText: String = "通过",
    ) = XmuScoreRecord(
        courseCode = courseCode,
        courseName = courseName,
        term = "2025-2026学年第二学期",
        termCode = "20251",
        credit = 3.0,
        score = score,
        gradePoint = gradePoint,
        gradeMode = "百分制",
        resultText = resultText,
        countsForStatistics = countsForStatistics,
        countsForCompletedCredit = countsForCompletedCredit,
        courseSeq = "01",
    )

    @Test
    fun `records round trip through json with all fields preserved`() {
        val records = listOf(
            record(),
            record(courseCode = "CS1002", courseName = "操作系统", score = 88.0, gradePoint = 3.7),
        )
        val restored = xmuScoreRecordsFromJson(xmuScoreRecordsToJson(records))
        assertEquals(records, restored)
    }

    @Test
    fun `nullable score and grade point survive null round trip`() {
        // 二级制课程无分数/绩点：null 序列化为 JSON null 后必须还原为 null（optNullableDouble 语义）
        val records = listOf(record(score = null, gradePoint = null, resultText = "合格"))
        val restored = xmuScoreRecordsFromJson(xmuScoreRecordsToJson(records))
        assertEquals(null, restored.single().score)
        assertEquals(null, restored.single().gradePoint)
        assertEquals("合格", restored.single().resultText)
    }

    @Test
    fun `statistics and completed credit flags round trip independently`() {
        val records = listOf(
            record(countsForStatistics = false, countsForCompletedCredit = true),
            record(countsForStatistics = true, countsForCompletedCredit = false),
        )
        val restored = xmuScoreRecordsFromJson(xmuScoreRecordsToJson(records))
        assertEquals(false, restored[0].countsForStatistics)
        assertEquals(true, restored[0].countsForCompletedCredit)
        assertEquals(true, restored[1].countsForStatistics)
        assertEquals(false, restored[1].countsForCompletedCredit)
    }

    @Test
    fun `blank input yields empty list and corrupt json degrades to empty`() {
        assertEquals(emptyList<XmuScoreRecord>(), xmuScoreRecordsFromJson(""))
        assertEquals(emptyList<XmuScoreRecord>(), xmuScoreRecordsFromJson("   "))
        assertEquals(emptyList<XmuScoreRecord>(), xmuScoreRecordsFromJson("{not-json"))
    }

    @Test
    fun `legacy records without completed credit flag infer from result text`() {
        // 旧格式缓存无 countsForCompletedCredit：含不及格字样保守不计入（防旧缓存学分虚高）
        val legacyJson = """
            [{"courseCode":"CS1003","courseName":"大学英语","term":"t","termCode":"20241",
              "credit":2.0,"score":55.0,"gradePoint":0.0,"gradeMode":"百分制",
              "resultText":"不及格","countsForStatistics":false,"courseSeq":"01"}]
        """.trimIndent()
        val restored = xmuScoreRecordsFromJson(legacyJson)
        assertEquals(1, restored.size)
        assertEquals(false, restored.single().countsForCompletedCredit)
        val passedJson = legacyJson.replace("不及格", "通过")
        assertTrue(xmuScoreRecordsFromJson(passedJson).single().countsForCompletedCredit)
    }

    @Test
    fun `non object array elements are skipped`() {
        val json = """[{"courseCode":"CS1001","courseName":"n","term":"t","termCode":"c","credit":1.0},"junk",42]"""
        val restored = xmuScoreRecordsFromJson(json)
        assertEquals(1, restored.size)
        assertEquals("CS1001", restored.single().courseCode)
    }
}
