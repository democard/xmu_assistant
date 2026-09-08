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
                    val before = withContext(Dispatchers.IO) { client.records() }.map { it.id }.toSet()
                    val pending = RankPending(before, choice, System.currentTimeMillis(), startFingerprint)
                    // Persist BEFORE the POST. Process death or a lost response must never cause an automatic resubmit.
                    update(working.copy(pending = pending))
                    stage = "正在提交申请"
                    try { withContext(Dispatchers.IO) { client.submit(choice.id) } }
                    catch (e: RankSubmitRejected) { update(working.copy(pending = null)); throw e }
                }
                stage = "正在等待计算"
                repeat(8) { attempt ->
                    ensureActive(); check(active()) { "会话已改变" }
                    val pending = working.pending ?: error("申请状态缺失")
                    val records = withContext(Dispatchers.IO) { client.records() }
                    val record = identifyRankRecord(records, pending)
                    if (record != null) {
                        if (pending.recordId.isBlank()) update(working.copy(pending = pending.copy(recordId = record.id)))
                        if (record.participants > 0) {
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
                            val result = RankResult(record.id, numbers?.position, numbers?.participants, pending.range.name,
                                pending.requestedAt, System.currentTimeMillis(), record.calculatedAt, pending.fingerprint,
                                withContext(Dispatchers.IO) { Base64.encodeToString(pdf, Base64.NO_WRAP) })
                            update(working.copy(result = result, pending = null))
                            stage = ""
                            if (numbers == null) error = "证明已保存，但未识别到 GPA 排名。可导出原始 PDF 核对；不会把历史排名当成本次结果。"
                            return@launch
                        }
                    }
                    if (attempt < 7) delay(if (attempt < 2) 3000 else 8000)
                }
                error = "本次计算尚未完成。点击继续查询，不会重复申请。"
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
