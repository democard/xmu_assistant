package com.xmu.assistant

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 签到情况模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *   后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态；
 *   会话过期时经 setPendingSessionRetry 挂起一次性安全续登（recoverExpiredModule 处理）。
 * - 请求统一走 "rollcall" 互斥门（RequestGate）。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *   不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class RollcallSectionState(
    private val activity: Activity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val requireLogin: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val setPendingSessionRetry: (ModuleReadRequest) -> Unit,
    eventsInitial: List<RollcallEvent> = emptyList(),
    updatedAtMillisInitial: Long = 0L,
) {
    /** 最近一次拉到的签到事件列表。 */
    var events by mutableStateOf(eventsInitial)
        private set

    /** 最近一次签到刷新成功的时间戳（毫秒）。 */
    var updatedAtMillis by mutableStateOf(updatedAtMillisInitial)
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    /** 强制刷新签到列表。 */
    fun refresh(alreadyRetriedAfterRecovery: Boolean = false): Boolean {
        if (!requireLogin()) return false
        if (!requestGate.tryStart("rollcall")) return false
        refreshError = ""
        loading = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { RollcallEngine(session.cookieHeader).pollOnce() }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            result.onSuccess { list ->
                                refreshError = ""
                                events = list
                                updatedAtMillis = System.currentTimeMillis()
                                show(if (list.isEmpty()) "暂未检测到签到" else "已读取 ${list.size} 条签到")
                            }
                            result.onFailure { error ->
                                if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                                    refreshError = "登录已过期，正在安全续登"
                                    setPendingSessionRetry(ModuleReadRequest.ROLLCALL)
                                } else {
                                    refreshError = refreshFailureMessage(error)
                                    show("签到读取失败：$refreshError")
                                }
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
                // 跳过，finish 放内层会导致 "rollcall" 门永久占用
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish("rollcall")
                }
            }
        }
        return true
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loading = false
    }

    /** 登出/换号清理：清内存状态（持久化由调用方一并清理，与拆分前一致）。 */
    fun clearAll() {
        events = emptyList()
        loading = false
        refreshError = ""
        updatedAtMillis = 0L
    }
}
