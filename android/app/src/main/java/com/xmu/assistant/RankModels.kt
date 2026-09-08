package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun rankDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Includes corrected marks, credits and retakes, not just the number of courses. */
internal fun rankScoreFingerprint(records: List<XmuScoreRecord>): String = rankDigest(
    records.map { xmuScoreRecordsToJson(listOf(it)) }.sorted().joinToString("\n"),
)

internal data class RankRange(val id: String, val name: String)
internal data class RankRecord(val id: String, val participants: Int, val rangeId: String = "", val calculatedAt: String = "")
internal data class RankNumbers(val position: Int, val participants: Int)
internal data class RankResult(
    val recordId: String, val position: Int?, val participants: Int?, val rangeName: String,
    val requestedAt: Long, val obtainedAt: Long, val calculatedAt: String,
    val fingerprint: String, val pdfBase64: String,
)
internal data class RankPending(
    val beforeIds: Set<String>, val range: RankRange, val requestedAt: Long,
    val fingerprint: String, val recordId: String = "",
)
internal data class RankCache(val owner: String, val result: RankResult? = null, val pending: RankPending? = null)

internal fun rankNumbersFromText(text: String): RankNumbers? {
    val normalized = text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ")
    val matches = Regex("total number of students in the major is\\s+([0-9,]+)\\s*,?\\s*with a GPA rank of\\s+([0-9,]+)", RegexOption.IGNORE_CASE)
        .findAll(normalized).mapNotNull {
            val total = it.groupValues[1].replace(",", "").toIntOrNull() ?: return@mapNotNull null
            val rank = it.groupValues[2].replace(",", "").toIntOrNull() ?: return@mapNotNull null
            if (rank in 1..total) RankNumbers(rank, total) else null
        }.distinct().toList()
    return matches.singleOrNull()
}

internal fun rankCacheToJson(cache: RankCache): String = JSONObject().apply {
    put("owner", cache.owner)
    cache.result?.let { r -> put("result", JSONObject().apply {
        put("id", r.recordId); put("rank", r.position); put("total", r.participants)
        put("range", r.rangeName); put("requested", r.requestedAt); put("obtained", r.obtainedAt)
        put("calculated", r.calculatedAt); put("fingerprint", r.fingerprint); put("pdf", r.pdfBase64)
    }) }
    cache.pending?.let { p -> put("pending", JSONObject().apply {
        put("before", JSONArray(p.beforeIds.toList())); put("rangeId", p.range.id); put("rangeName", p.range.name)
        put("requested", p.requestedAt); put("fingerprint", p.fingerprint); put("id", p.recordId)
    }) }
}.toString()

internal fun rankCacheFromJson(json: String, owner: String): RankCache = runCatching {
    val root = JSONObject(json)
    if (root.getString("owner") != owner) return RankCache(owner)
    val result = root.optJSONObject("result")?.let {
        RankResult(it.getString("id"), if (it.isNull("rank")) null else it.getInt("rank"),
            if (it.isNull("total")) null else it.getInt("total"), it.getString("range"),
            it.getLong("requested"), it.getLong("obtained"), it.optString("calculated"),
            it.getString("fingerprint"), it.getString("pdf"))
    }
    val pending = root.optJSONObject("pending")?.let {
        val before = it.getJSONArray("before")
        RankPending((0 until before.length()).map { i -> before.getString(i) }.toSet(),
            RankRange(it.getString("rangeId"), it.getString("rangeName")), it.getLong("requested"),
            it.getString("fingerprint"), it.optString("id"))
    }
    RankCache(owner, result, pending)
}.getOrDefault(RankCache(owner))

/** Never pick the first historical record or guess among simultaneous new requests. */
internal fun identifyRankRecord(records: List<RankRecord>, pending: RankPending): RankRecord? {
    if (pending.recordId.isNotBlank()) return records.singleOrNull { it.id == pending.recordId }
    val candidates = records.filter { it.id !in pending.beforeIds && (it.rangeId.isBlank() || it.rangeId == pending.range.id) }
    check(candidates.size <= 1) { "发现多条新申请，无法确认本次记录。请在教务系统核对后继续。" }
    return candidates.singleOrNull()
}
