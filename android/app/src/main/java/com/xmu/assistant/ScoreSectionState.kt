package com.xmu.assistant

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 成绩模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *   后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态，
 *   与其它模块共用同一进程级 epoch；续登写回教务 cookie（setScoreCookieHeader）
 *   前同样校验 isCurrent（防登出/换号后旧账号 cookie 写回）。
 * - 存储依赖收窄为函数式注入（scoreCookieHeader/setScoreCookieHeader/scoreRecordsJson/
 *   scoreUpdatedAtMillis）：组合侧绑定 AssistantSettings（加密 prefs），测试注入 fake。
 * - 风控红线：成绩刷新内部可能触发教务 CAS 续登（XmuScoreAutoQueryClient 内部续登），
 *   与 TronClass 手动登录并发会打两个身份域——入口检查 accountTransitionInProgress；
 *   请求统一走 "scores" 互斥门（RequestGate）。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *   不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class ScoreSectionState(
    private val activity: Activity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val accountTransitionInProgress: () -> Boolean,
    private val scoreCookieHeader: () -> String,
    private val setScoreCookieHeader: (String) -> Unit,
    private val setScoreRecordsJson: (String) -> Unit,
    private val setScoreUpdatedAtMillisPref: (Long) -> Unit,
    scoreRecordsInitial: List<XmuScoreRecord> = emptyList(),
    scoreUpdatedAtMillisInitial: Long = 0L,
) {
    /** 成绩记录（内存可观察；持久化在 settings，登出/换号时清）。 */
    var scoreRecords by mutableStateOf(scoreRecordsInitial)
        private set

    /** 最近一次成绩刷新成功的时间戳（毫秒）。 */
    var updatedAtMillis by mutableStateOf(scoreUpdatedAtMillisInitial)
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    /**
     * 强制刷新成绩。登录/恢复在途时禁止并发刷新：成绩刷新内部可能触发 academic CAS 续登，
     * 与 TronClass 登录并发会打两个身份域（风控红线）。
     */
    fun refresh(): Boolean {
        if (accountTransitionInProgress()) {
            show("登录处理中，请稍候")
            return false
        }
        // 登录态检查与 schedule/rollcall/courseware 入口对齐：登出后凭据（学号密码）仍残留，
        // 缺此检查会用残留凭据直接发起教务 CAS 登录——回调虽被 loggedIn 守卫丢弃，
        // 但登录请求已打出（登出后幽灵登录 = 风控暴露）
        if (!loggedIn() || cookieHeader().isBlank()) {
            show("请先登录")
            return false
        }
        if (username().isBlank() || password().isBlank()) {
            show("请先在首页填写学号和密码")
            return false
        }
        if (!requestGate.tryStart("scores")) return false
        refreshError = ""
        loading = true
        val accountUsername = username()
        val accountPassword = password()
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        scope.launch(Dispatchers.IO) {
            try {
                val client = XmuScoreAutoQueryClient(
                    accountUsername,
                    accountPassword,
                    scoreCookieHeader(),
                    mayRelogin = { sessionEpoch.isCurrent(session) },
                )
                val result = runCatching { client.fetchScores() }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn()) &&
                            (username() == accountUsername && password() == accountPassword)
                        ) {
                        result.onSuccess { fetchResult ->
                            val records = fetchResult.records
                            refreshError = ""
                            val updatedAt = System.currentTimeMillis()
                            // 加密 prefs 写大 JSON 挪到后台线程（与课表模块同一约定）：
                            // 主线程只做内存状态更新，避免刷新完成瞬间的加密/磁盘 IO 卡顿。
                            // 写入前校验会话世代仍有效，防止登出后陈旧数据写回（登录态"复活"）。
                            val persistCookie = client.cookieHeader()
                            val persistJson = xmuScoreRecordsToJson(records)
                            scope.launch(Dispatchers.IO) {
                                if (sessionEpoch.isCurrent(session)) {
                                    setScoreCookieHeader(persistCookie)
                                    setScoreRecordsJson(persistJson)
                                    setScoreUpdatedAtMillisPref(updatedAt)
                                }
                            }
                            scoreRecords = records
                            updatedAtMillis = updatedAt
                            show(
                                when {
                                    records.isEmpty() -> "暂无成绩记录"
                                    fetchResult.failedTermNames.isNotEmpty() ->
                                        "成绩已读取 ${records.size} 门，${fetchResult.failedTermNames.size} 个学期读取失败"
                                    else -> "成绩已读取 ${records.size} 门"
                                },
                            )
                        }
                        result.onFailure { error ->
                            // 失败路径也回写续登后的 cookie（受 epoch 保护）：
                            // 避免下次仍用旧 cookie 再触发一次 CAS 续登（风控暴露翻倍）。
                            // 登录被服务端拒绝/限流（AcademicLoginBlockedException）时
                            // cookie 是无效中间态：**不写回**（写回会让下次操作直接 landing 死循环）。
                            if (error !is AcademicLoginBlockedException && sessionEpoch.isCurrent(session)) {
                                setScoreCookieHeader(client.cookieHeader())
                            }
                            Log.e("XmuScoreDebug", "score refresh failed: ${error.javaClass.name}: ${error.message}", error)
                            refreshError = refreshFailureMessage(error)
                            show("成绩读取失败：$refreshError")
                        }
                    }
                    } finally {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            loading = false
                        }
                    }
                }
            } finally {
                // 门释放提到协程最外层（NonCancellable）：协程取消时内层 withContext 整段
                // 跳过，finish 放内层会导致 "scores" 门永久占用
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish("scores")
                }
            }
        }
        return true
    }

    /** 生成成绩长图并分享（后台生成位图，避免主线程卡顿；生成失败明确提示不崩溃）。 */
    fun shareLongImage() {
        val records = scoreRecords
        if (records.isEmpty()) {
            show("请先刷新成绩")
            return
        }
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { renderScoreLongImage(records, xmuScoreSummary(records)) }
                .getOrNull()
            if (bitmap == null) {
                // 长图生成失败（超长成绩单 OOM 等）：明确提示，不崩溃
                withContext(Dispatchers.Main) { show("成绩单过长，生成长图失败") }
                return@launch
            }
            val uri = saveScoreImageToGallery(activity, bitmap)
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                if (uri != null) {
                    shareScoreImage(activity, uri)
                } else {
                    show("生成成绩长图失败")
                }
            }
        }
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loading = false
    }

    /** 登出/换号清理：清内存状态（持久化由调用方一并清理，与拆分前一致）。 */
    fun clearAll() {
        scoreRecords = emptyList()
        loading = false
        refreshError = ""
        updatedAtMillis = 0L
    }
}
