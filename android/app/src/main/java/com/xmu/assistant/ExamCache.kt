package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 考试安排本地缓存（纯考试数据，不含学号/账号/Cookie 等个人信息）。
 *
 * 设计目标（响应速度优先 + 悄悄检查模式）：
 * - 按学期多槽缓存：切学期立即显示该学期上次结果，无需等待网络
 * - 有效学期列表缓存：进入页面立即显示学期切换器，后台悄悄重新探测
 * - 记录保存时刻，超过 freshness 阈值视为过期，进页面时悄悄检查
 * - 兼容旧版单槽缓存（data/term 字段）迁移
 */
internal object ExamCache {
    private const val PREFS = "exam_cache"
    private const val KEY_JSON = "data"
    private const val KEY_TERM = "term"
    private const val KEY_SAVED_EPOCH_DAY = "saved_epoch_day"
    private const val KEY_TERMS = "terms"
    private const val KEY_LAST_PROBE = "last_probe_epoch_millis"
    private const val KEY_TERM_PREFIX = "term_"
    private const val FRESHNESS_DAYS = 12L
    // 学期槽上限：最近 12 个学期（约 4 学年）足够日常查看与提醒聚合，超出淘汰最旧
    private const val MAX_TERM_SLOTS = 12

    /** 保存单个学期的考试汇总（多槽：每学期一个 key；超上限时淘汰最旧学期槽）。 */
    fun saveTerm(context: Context, summary: XmuTermExamSummary) {
        val exams = JSONArray()
        summary.exams.forEach { exam ->
            exams.put(
                JSONObject()
                    .put("id", exam.id)
                    .put("courseName", exam.courseName)
                    .put("date", exam.date)
                    .put("timeRange", exam.timeRange)
                    .put("room", exam.room)
                    .put("mode", exam.mode)
                    .put("examName", exam.examName),
            )
        }
        val unarranged = JSONArray()
        summary.unarranged.forEach { item -> unarranged.put(JSONObject().put("courseName", item.courseName)) }
        val payload = JSONObject()
            .put("termCode", summary.termCode)
            .put("termLabel", summary.termLabel)
            .put("exams", exams)
            .put("unarranged", unarranged)
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor
            .putString("$KEY_TERM_PREFIX${summary.termCode}", payload.toString())
            .putString(KEY_TERM, summary.termCode)
            .putLong(KEY_SAVED_EPOCH_DAY, java.time.LocalDate.now().toEpochDay())
        // 淘汰：学期槽只保留最近 MAX_TERM_SLOTS 个（多年累积不会无限膨胀）。
        // 注意并入本次新写入的 key：prefs.all 读的是 apply() 前的快照，不含新键，
        // 否则新增第 13 个学期时 dropLast 删不到任何键、槽位上限失效。
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val termKeys = (prefs.all.keys + "$KEY_TERM_PREFIX${summary.termCode}")
            .filter { it.startsWith(KEY_TERM_PREFIX) }
            .distinct()
            .sorted() // term_YYYY-YYYY-S 前缀一致，字典序 = 时间序（旧在前）
        termKeys.dropLast(MAX_TERM_SLOTS).forEach { editor.remove(it) }
        editor.apply()
    }

    /** 读取指定学期的缓存；兼容旧版单槽缓存（data/term 字段）迁移。 */
    fun loadTerm(context: Context, termCode: String): XmuTermExamSummary? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_TERM_PREFIX$termCode", null)
            ?: prefs.getString(KEY_JSON, null).takeIf { prefs.getString(KEY_TERM, null) == termCode }
            ?: return null
        parseSummary(raw)
    }.getOrNull()

    /** 缓存有效学期列表（新→旧，最近在前）。 */
    fun saveTerms(context: Context, terms: List<String>) {
        val array = JSONArray()
        terms.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TERMS, array.toString())
            .apply()
    }

    /** 读取有效学期列表（新→旧，最近在前）；无列表但有旧版单槽缓存时，用该学期作为初始列表。 */
    fun loadTerms(context: Context): List<String> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TERMS, null)
        if (raw != null) {
            val array = JSONArray(raw)
            return@runCatching (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        }
        prefs.getString(KEY_TERM, null)?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }.getOrDefault(emptyList())

    /** 聚合所有已缓存学期的考试（跨学期提醒调度用）。
     *  去重键：优先考试 id；服务端缺 id 时退化为「课程|日期|时间」复合键——
     *  否则多场无 id 考试共享空 id，会被误合并只剩第一场，提醒随之丢失。 */
    fun loadAllExams(context: Context): List<XmuExam> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = HashSet<String>()
        val result = ArrayList<XmuExam>()
        prefs.all.keys
            .filter { it.startsWith(KEY_TERM_PREFIX) }
            .sorted()
            .forEach { key ->
                val summary = runCatching { parseSummary(prefs.getString(key, null) ?: return@forEach) }.getOrNull()
                    ?: return@forEach
                summary.exams.forEach { exam ->
                    val dedupeKey = exam.id.ifBlank { "${exam.courseName}|${exam.date}|${exam.timeRange}" }
                    if (seen.add(dedupeKey)) result += exam
                }
            }
        return result
    }

    /** 学期列表是否需要重新探测（节流：距上次探测超过 intervalMillis 才重探，默认 6 小时）。 */
    fun shouldReProbe(context: Context, intervalMillis: Long = 6 * 60 * 60 * 1000L): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_PROBE, 0L)
        return last == 0L || System.currentTimeMillis() - last >= intervalMillis
    }

    /** 记录一次学期列表探测（节流基准）。 */
    fun markProbed(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PROBE, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun parseSummary(raw: String): XmuTermExamSummary {
        val root = JSONObject(raw)
        val examRows = root.optJSONArray("exams") ?: JSONArray()
        val exams = ArrayList<XmuExam>(examRows.length())
        for (i in 0 until examRows.length()) {
            val row = examRows.optJSONObject(i) ?: continue
            exams += XmuExam(
                id = row.optString("id"),
                courseName = row.optString("courseName"),
                date = row.optString("date"),
                timeRange = row.optString("timeRange"),
                room = row.optString("room"),
                mode = row.optString("mode"),
                examName = row.optString("examName"),
            )
        }
        val unarrangedRows = root.optJSONArray("unarranged") ?: JSONArray()
        val unarranged = ArrayList<XmuExamUnarranged>(unarrangedRows.length())
        for (i in 0 until unarrangedRows.length()) {
            val row = unarrangedRows.optJSONObject(i) ?: continue
            row.optString("courseName").takeIf(String::isNotBlank)?.let {
                unarranged += XmuExamUnarranged(courseName = it)
            }
        }
        return XmuTermExamSummary(
            termCode = root.optString("termCode"),
            termLabel = root.optString("termLabel"),
            exams = exams,
            unarranged = unarranged,
        )
    }
}
