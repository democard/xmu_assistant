package com.xmu.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*

internal class RankSectionState(
    private val context: Context,
    private val gate: RequestGate,
    private val epoch: SessionEpoch,
    private val owner: SessionOwner,
    private val scope: CoroutineScope,
    private val loggedIn: () -> Boolean,
    private val cookie: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val transitioning: () -> Boolean,
    private val academicCookie: () -> String,
    private val saveCookie: (String) -> Unit,
    private val readCache: () -> String,
    private val writeCache: (String, () -> Boolean) -> Boolean,
    private val show: (String) -> Unit,
    initialScores: List<XmuScoreRecord>,
    private val clientFactory: (String, (String) -> String, () -> Boolean) -> XmuRankClient = { c, renew, active -> XmuRankClient(c, renew, active) },
    /** 等待新记录的轮询节奏：没有可兜底的旧记录时要等满全部机会。可注入以便测试零延迟。 */
    private val pollDelays: List<Long> = listOf(3000, 3000, 8000, 8000, 8000, 8000, 8000),
    /** 已有可兜底记录时的缩短节奏：新记录多半不会到来，尽量少让用户干等。 */
    private val fallbackPollDelays: List<Long> = listOf(3000, 5000, 8000),
) {
    var cache by mutableStateOf(rankCacheFromJson(readCache(), rankDigest(username())))
        private set
    var loading by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var ranges by mutableStateOf(emptyList<RankRange>())
        private set
    var selectedRange by mutableStateOf("")
    private var fingerprint by mutableStateOf(rankScoreFingerprint(initialScores))
    val stale get() = cache.result?.let { it.fingerprint != fingerprint } == true
    val label get() = when {
        loading -> stage
        cache.pending != null -> "等待本次结果"
        stale -> "待更新"
        cache.result?.position != null -> "${cache.result!!.position} / ${cache.result!!.participants}"
        cache.result != null -> "证明已获取 · 排名不可用"
        else -> "尚未获取"
    }

    fun onScoresChanged(records: List<XmuScoreRecord>, complete: Boolean) {
        // Partial semester failures must not mark an otherwise matching result as obsolete.
        if (complete) fingerprint = rankScoreFingerprint(records)
    }

    fun clearAll() {
        cache = RankCache(rankDigest(username()))
        writeCache("", { true })
        ranges = emptyList(); selectedRange = ""; loading = false; stage = ""; error = ""
        fingerprint = rankScoreFingerprint(emptyList())
    }

    fun clearLoadingState() { loading = false }

    fun fetch() {
        if (transitioning()) { show("登录处理中，请稍候"); return }
        if (!loggedIn() || cookie().isBlank()) { show("请先登录"); return }
        if (loading || !gate.tryStart("rankings")) return
        val account = username(); val pass = password()
        val session = epoch.snapshot(owner, cookie())
        val active = { epoch.isCurrent(session) }
        val startFingerprint = fingerprint
        val stored = if (cache.owner == rankDigest(account)) cache else RankCache(rankDigest(account))
        val rangeId = selectedRange
        loading = true; stage = "正在准备"; error = ""
        val client = clientFactory(academicCookie(), { old ->
            XmuScoreAutoQueryClient(account, pass, old, mayRelogin = active).forceAcademicLogin()
        }, active)
        scope.launch(Dispatchers.Main) {
            var working = stored
            suspend fun update(value: RankCache) {
                val json = rankCacheToJson(value)
                val saved = withContext(Dispatchers.IO) { writeCache(json, active) }
                check(saved) { "无法保存申请状态，已停止操作" }
                ensureActive()
                check(active()) { "会话已改变" }
                working = value; cache = value
            }
            suspend fun finishWith(record: RankRecord, pending: RankPending, reuseNote: String?) {
                stage = "正在获取证明"
                val pdf = withContext(Dispatchers.IO) { client.certificate(record.id) }
                stage = "正在读取排名"
                val numbers = withContext(Dispatchers.IO) {
                    runCatching {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        PDDocument.load(pdf).use { document ->
                            check(document.numberOfPages in 1..10)
                            rankNumbersFromText(PDFTextStripper().getText(document))
                        }
                    }.getOrNull()
                }
                // 复用旧记录时沿用该记录原有的成绩指纹（若已持有同一记录），让"待更新"判定仍然可信
                val fingerprintForResult = cache.result?.takeIf { it.recordId == record.id }?.fingerprint
                    ?: pending.fingerprint
                val result = RankResult(record.id, numbers?.position, numbers?.participants, pending.range.name,
                    pending.requestedAt, System.currentTimeMillis(), record.calculatedAt, fingerprintForResult,
                    withContext(Dispatchers.IO) { Base64.encodeToString(pdf, Base64.NO_WRAP) })
                update(working.copy(result = result, pending = null))
                stage = ""
                when {
                    numbers == null -> error = "证明已保存，但未识别到 GPA 排名。可导出原始 PDF 核对；不会把历史排名当成本次结果。"
                    reuseNote != null -> error = reuseNote
                }
            }
            try {
                if (working.pending == null) {
                    val choices = withContext(Dispatchers.IO) { client.ranges() }
                    check(active()) { "会话已改变" }
                    val choice = choices.singleOrNull() ?: choices.singleOrNull { it.id == rangeId }
                    if (choice == null) {
                        ranges = choices
                        stage = "请选择计算范围"
                        return@launch
                    }
                    val snapshot = withContext(Dispatchers.IO) { client.records() }
                    check(active()) { "会话已改变" }
                    // 先探测：所选范围已有完整记录就直接复用并说明原因，绝不重复提交
                    // （教务对已存在的记录会忽略新申请，先申请再等只会白等）
                    val existing = latestCompleteRecordForRange(snapshot, choice.id)
                    if (existing != null) {
                        stage = "已有计算记录，正在读取"
                        finishWith(
                            existing,
                            RankPending(snapshot.map { it.id }.toSet(), choice, System.currentTimeMillis(), startFingerprint),
                            reuseNote = "所选范围已有计算记录（计算于 ${existing.calculatedAt.ifBlank { "未知时间" }}），已直接读取复用，未重复提交申请。",
                        )
                        return@launch
                    }
                    val before = snapshot.map { it.id }.toSet()
                    val pending = RankPending(before, choice, System.currentTimeMillis(), startFingerprint)
                    // Persist BEFORE the POST. Process death or a lost response must never cause an automatic resubmit.
                    update(working.copy(pending = pending))
                    stage = "正在提交申请"
                    try { withContext(Dispatchers.IO) { client.submit(choice.id) } }
                    catch (e: RankSubmitRejected) { update(working.copy(pending = null)); throw e }
                }

                stage = "正在等待计算"
                var fallback: RankRecord? = null
                var ambiguity: String? = null
                var attempt = 0
                while (true) {
                    ensureActive(); check(active()) { "会话已改变" }
                    val pending = working.pending ?: error("申请状态缺失")
                    val records = withContext(Dispatchers.IO) { client.records() }
                    // 多笔新申请并存时无法归因本次申请，但不妨碍兜底采用本范围最近记录
                    val record = try { identifyRankRecord(records, pending) }
                    catch (e: IllegalStateException) { ambiguity = e.message; null }
                    if (record != null) {
                        if (pending.recordId.isBlank()) update(working.copy(pending = pending.copy(recordId = record.id)))
                        if (record.participants > 0) {
                            finishWith(record, pending, reuseNote = null)
                            return@launch
                        }
                    }
                    latestCompleteRecordForRange(records, pending.range.id)?.let { fallback = it }
                    val delays = if (fallback != null) fallbackPollDelays else pollDelays
                    if (attempt >= delays.size) break
                    delay(delays[attempt])
                    attempt++
                }
                val adopt = fallback
                if (adopt != null) {
                    // 服务端未生成新记录（所选范围已有有效记录时申请会被合并/忽略）：采用最近一次
                    // 完整计算，明确标注来源，避免用户无限等待；下次点击仍会重新尝试申请。
                    finishWith(adopt, working.pending ?: error("申请状态缺失"),
                        reuseNote = "教务未生成新记录，已采用所选范围最近一次计算结果（计算于 ${adopt.calculatedAt.ifBlank { "未知时间" }}）。")
                    return@launch
                }
                error = ambiguity ?: "本次计算尚未完成。点击继续查询，不会重复申请。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (active()) error = if (e is java.io.IOException) "网络连接失败；若申请已提交，请继续查询本次结果。" else e.message ?: "获取失败，请稍后重试"
            } finally {
                withContext(NonCancellable) {
                    if (active()) {
                        withContext(Dispatchers.IO) { if (active()) saveCookie(client.cookieHeader()) }
                        loading = false
                    }
                    gate.finish("rankings")
                }
            }
        }
    }

    /** Delete only the private cached PDF; exported documents belong to the user. */
    fun clearPdf() = mutateCache { current -> current.copy(result = current.result?.copy(pdfBase64 = "")) }

    fun abandonPending() = mutateCache { it.copy(pending = null) }

    private fun mutateCache(transform: (RankCache) -> RankCache) {
        if (loading || !gate.tryStart("rankings")) return
        val session = epoch.snapshot(owner, cookie())
        val next = transform(cache)
        loading = true; stage = "正在保存"
        scope.launch(Dispatchers.Main) {
            try {
                val saved = withContext(Dispatchers.IO) { writeCache(rankCacheToJson(next)) { epoch.isCurrent(session) } }
                if (epoch.isCurrent(session)) {
                    if (saved) { cache = next; error = "" } else error = "本地保存失败，请重试"
                }
            } finally { if (epoch.isCurrent(session)) loading = false; gate.finish("rankings") }
        }
    }

    fun exportPdf(uri: Uri, expectedRecord: String) {
        val result = cache.result
        if (result == null || result.recordId != expectedRecord || result.pdfBase64.isBlank() || !loggedIn()) {
            show("证明已改变或清理，请重新选择导出"); return
        }
        val session = epoch.snapshot(owner, cookie())
        scope.launch(Dispatchers.Main) {
            val outcome = withContext(Dispatchers.IO) { runCatching {
                check(epoch.isCurrent(session)) { "账号已改变，导出已停止" }
                val bytes = Base64.decode(result.pdfBase64, Base64.NO_WRAP)
                check(epoch.isCurrent(session)) { "账号已改变，导出已停止" }
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: error("无法写入所选位置")
            } }
            if (epoch.isCurrent(session)) show(if (outcome.isSuccess) "绩点证明已导出" else "导出失败，请重新选择位置")
        }
    }
}
