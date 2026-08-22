package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

data class XmuScoreRecord(
    val courseCode: String,
    val courseName: String,
    val term: String,
    val termCode: String,
    val credit: Double,
    val score: Double? = null,
    val gradePoint: Double? = null,
    val gradeMode: String = "",
    val resultText: String = "",
    val countsForStatistics: Boolean = false,
    val countsForCompletedCredit: Boolean = true,
    /** 课序号（同一课程同学期多次开课/重修补考用 KXH 区分；旧缓存缺省为空串）。 */
    val courseSeq: String = "",
)

data class XmuScoreSummary(
    val averageGpa: Double?,
    val weightedGpa: Double?,
    val averageScore: Double?,
    val weightedScore: Double?,
    val completedCredits: Double,
)

fun xmuScoreSummary(records: List<XmuScoreRecord>): XmuScoreSummary {
    val percentRecords = records.filter { it.countsForStatistics && it.score != null && it.gradePoint != null }
    val credits = percentRecords.sumOf { it.credit }
    return XmuScoreSummary(
        averageGpa = percentRecords.takeIf { it.isNotEmpty() }?.let { it.sumOf { record -> record.gradePoint ?: 0.0 }.div(it.size).roundScoreMetric() },
        weightedGpa = if (credits > 0) percentRecords.sumOf { (it.gradePoint ?: 0.0) * it.credit }.div(credits).roundScoreMetric() else null,
        averageScore = percentRecords.takeIf { it.isNotEmpty() }?.let { it.sumOf { record -> record.score ?: 0.0 }.div(it.size).roundScoreMetric() },
        weightedScore = if (credits > 0) percentRecords.sumOf { (it.score ?: 0.0) * it.credit }.div(credits).roundScoreMetric() else null,
        completedCredits = records.filter { it.countsForCompletedCredit }.sumOf { it.credit }.roundScoreMetric(),
    )
}

fun xmuScoreRecordsToJson(records: List<XmuScoreRecord>): String =
    JSONArray(records.map { record ->
        JSONObject()
            .put("courseCode", record.courseCode)
            .put("courseName", record.courseName)
            .put("term", record.term)
            .put("termCode", record.termCode)
            .put("credit", record.credit)
            .put("score", record.score)
            .put("gradePoint", record.gradePoint)
            .put("gradeMode", record.gradeMode)
            .put("resultText", record.resultText)
            .put("countsForStatistics", record.countsForStatistics)
            .put("countsForCompletedCredit", record.countsForCompletedCredit)
            .put("courseSeq", record.courseSeq)
    }).toString()

fun xmuScoreRecordsFromJson(value: String): List<XmuScoreRecord> = runCatching {
    if (value.isBlank()) return@runCatching emptyList()
    val array = JSONArray(value)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        XmuScoreRecord(
            courseCode = item.optString("courseCode"),
            courseName = item.optString("courseName"),
            term = item.optString("term"),
            termCode = item.optString("termCode"),
            credit = item.optDouble("credit"),
            score = item.optNullableDouble("score"),
            gradePoint = item.optNullableDouble("gradePoint"),
            gradeMode = item.optString("gradeMode"),
            resultText = item.optString("resultText"),
            countsForStatistics = item.optBoolean("countsForStatistics", false),
            countsForCompletedCredit = if (item.has("countsForCompletedCredit")) {
                item.optBoolean("countsForCompletedCredit")
            } else {
                // 旧格式缓存无该字段：按结果文本保守推断（含不及格/未通过字样不计入已完成学分，
                // 避免旧缓存学分虚高；新格式缓存直接读取存储值）
                listOf("不及格", "不合格", "不通过", "NP", "FAIL", "W", "DF", "缓考")
                    .none { it in item.optString("resultText") }
            },
            courseSeq = item.optString("courseSeq"),
        )
    }
}.getOrDefault(emptyList())

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun Double.roundScoreMetric(): Double = round(this * 100.0) / 100.0
