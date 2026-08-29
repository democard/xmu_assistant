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

/**
 * 厦大官方 4.0 分制：百分制成绩 → 绩点（来源：厦门大学本科课程学分绩点计算办法）。
 * 仅供「模拟成绩」输入换算的单一来源纯函数；已有成绩记录的 gradePoint 是平台
 * 直接返回的，不走此表。等级制课程（优/良/中/及格）第一版不支持模拟输入。
 */
fun xmuSimulatedGradePoint(score: Double): Double = when {
    score >= 95.0 -> 4.0 // 95-100 A+
    score >= 90.0 -> 4.0 // 90-94 A
    score >= 85.0 -> 3.7 // 85-89 A-
    score >= 81.0 -> 3.3 // 81-84 B+
    score >= 78.0 -> 3.0 // 78-80 B
    score >= 75.0 -> 2.7 // 75-77 B-
    score >= 72.0 -> 2.3 // 72-74 C+
    score >= 68.0 -> 2.0 // 68-71 C
    score >= 64.0 -> 1.5 // 64-67 C-
    score >= 60.0 -> 1.0 // 60-63 D
    else -> 0.0 // <60 F
}

/**
 * 解析一行「模拟成绩」输入（百分制成绩文本、学分文本）为可参与统计的成绩记录。
 * 空/非数字/成绩超 0-100/学分非正的行返回 null——不参与计算、不打断报错。
 * 模拟课参与均分/绩点统计（countsForStatistics=true），但不计入已修总学分
 * （countsForCompletedCredit=false）；纯本地换算，零网络请求。
 */
fun xmuSimulatedScoreRecord(scoreText: String, creditText: String): XmuScoreRecord? {
    // toDoubleOrNull 会放行 "NaN"/"Infinity"，需 isFinite 一并拦下
    val score = scoreText.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val credit = creditText.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    if (score !in 0.0..100.0 || credit <= 0.0) return null
    return XmuScoreRecord(
        courseCode = "",
        courseName = "模拟成绩",
        term = "",
        termCode = "",
        credit = credit,
        score = score,
        gradePoint = xmuSimulatedGradePoint(score),
        countsForStatistics = true,
        countsForCompletedCredit = false,
    )
}

/**
 * 把模拟行并入真实成绩记录后的整体汇总（复用官方口径 xmuScoreSummary）。
 * 没有有效模拟行时结果与 xmuScoreSummary(records) 完全一致（空模拟=现状）。
 */
fun xmuSimulatedSummary(records: List<XmuScoreRecord>, simulationRows: List<Pair<String, String>>): XmuScoreSummary =
    xmuScoreSummary(records + simulationRows.mapNotNull { (scoreText, creditText) ->
        xmuSimulatedScoreRecord(scoreText, creditText)
    })

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
            // 损坏缓存 credit 非数字时 optDouble 返回 NaN 入库污染统计（credits>0
            // 判断对 NaN 恒 false），fallback 0.0
            credit = item.optDouble("credit", 0.0).takeUnless { it.isNaN() } ?: 0.0,
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
