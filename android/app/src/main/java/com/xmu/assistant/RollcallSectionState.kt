package com.xmu.assistant

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 签到情况模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *   后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态；
 *   会话过期时经 setPendingSessionRetry 挂起一次性安全续登（recoverExpiredModule 处理）。
 * - 请求统一走互斥门（RequestGate）：进行中签到 "rollcall"、最近十次 "rollcall_history"。
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
    private val showWarning: (String) -> Unit = show,
    private val showError: (String) -> Unit = show,
    private val requireLogin: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    /** 当前登录用户名：本人明细匹配（user_no==username 或 is_current_user）用。 */
    private val username: () -> String,
    /** 调用方已加载的课程列表（复用免重复拉取；空则客户端自行兜底探测）。 */
    private val coursesProvider: () -> List<CourseSummary>,
    private val setPendingSessionRetry: (ModuleReadRetry) -> Unit,
    eventsInitial: List<RollcallEvent> = emptyList(),
    updatedAtMillisInitial: Long = 0L,
    /** 历史客户端工厂（测试注入假传输；生产默认 OkHttp 传输，签名与 CoursewareClient 同风格）。 */
    private val createHistoryClient: (String) -> RollcallHistoryClient = { cookieHeader ->
        RollcallHistoryClient(cookieHeader, OkHttpQueryTransport(), { size -> Executors.newFixedThreadPool(size) })
    },
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

    // ---- 最近十次签到（历史区块，独立门 "rollcall_history"）----

    var historyItems by mutableStateOf<List<RollcallHistoryItem>>(emptyList())
        private set

    var historyLoading by mutableStateOf(false)
        private set

    var historyError by mutableStateOf("")
        private set

    var historyUpdatedAtMillis by mutableStateOf(0L)
        private set

    /** 是否已成功加载过一次（首次进入自动拉取的判定依据）。 */
    var historyLoaded by mutableStateOf(false)
        private set

    /** 强制刷新签到列表。 */
    fun refresh(alreadyRetriedAfterRecovery: Boolean = false): Boolean {
        if (!requireLogin()) return false
        if (!requestGate.tryStart("rollcall")) return false
        refreshError = ""
        loading = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        scope.runModuleRequest(
            requestGate = requestGate,
            gateKey = "rollcall",
            acceptsResult = { sessionEpoch.accepts(session, cookieHeader(), loggedIn()) },
            ioWork = { RollcallEngine(session.cookieHeader).pollOnce() },
            onResult = { result ->
                result.onSuccess { list ->
                    refreshError = ""
                    events = list
                    updatedAtMillis = System.currentTimeMillis()
                    show(if (list.isEmpty()) "暂未检测到签到" else "已读取 ${list.size} 条签到")
                }
                result.onFailure { error ->
                    if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                        refreshError = "登录已过期，正在安全续登"
                        setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.ROLLCALL))
                    } else {
                        refreshError = refreshFailureMessage(error)
                        showError("签到读取失败：$refreshError")
                    }
                }
            },
            releaseLoading = { loading = false },
        )
        return true
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loading = false
        historyLoading = false
    }

    /**
     * 刷新最近十次签到（SWR：先渲缓存再后台刷新原位更新，失败保留缓存只报错）。
     * 独立互斥门 "rollcall_history"，与进行中签到的 "rollcall" 互不阻塞，
     * 顶部「刷新」按钮可让两块并行。
     */
    fun refreshHistory(alreadyRetriedAfterRecovery: Boolean = false): Boolean {
        if (!requireLogin()) return false
        if (!requestGate.tryStart("rollcall_history")) return false
        historyError = ""
        historyLoading = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountId = username()
        scope.launch(Dispatchers.IO) {
            try {
                // SWR 缓存先行：IO 线程读盘 → 有缓存先上屏（含上次更新），
                // 网络回包后原位覆盖；不学 ExamSectionState 构造期同步 IO（体检 P2）
                val cached = runCatching {
                    loadRollcallHistoryCache(rollcallHistoryCacheFile(activity), accountId)
                }.getOrNull()
                if (cached != null && cached.items.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn()) && historyItems.isEmpty()) {
                            historyItems = cached.items
                            historyUpdatedAtMillis = cached.fetchedAtMillis
                        }
                    }
                }
                val result = runCatching {
                    val client = createHistoryClient(session.cookieHeader)
                    // 阶段一：最近十条选中即上屏，每条状态位「核实中…」——绝不显示聚合值
                    val preview = client.selectRecentRollcalls(accountId, coursesProvider())
                    if (preview.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                                historyItems = preview
                            }
                        }
                    }
                    // 阶段二：并发核实本人状态，原位覆盖为准确结论
                    val resolved = client.resolveOwnStatuses(preview, accountId)
                    // 落盘放 IO 线程；失败静默（缓存只是加速路径，不阻塞展示）
                    runCatching {
                        // 写前校验会话世代：登出已删缓存文件，晚到的在途写不得复活
                        // （与同函数 UI 写的 accepts 守卫对齐）
                        if (sessionEpoch.isCurrent(session)) {
                            saveRollcallHistoryCache(
                                rollcallHistoryCacheFile(activity),
                                RollcallHistorySnapshot(accountId, System.currentTimeMillis(), resolved),
                            )
                        }
                    }
                    resolved
                }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            val items = result.getOrNull()
                            if (items != null) {
                                historyError = ""
                                historyItems = items
                                historyUpdatedAtMillis = System.currentTimeMillis()
                                historyLoaded = true
                            } else {
                                // 失败时屏上若停着未核完的预览（状态位空白），如实落为「未知」，
                                // 不让「核实中…」永久悬挂；此前渲染的旧数据/缓存语义不变
                                if (historyItems.any { it.ownStatus.isBlank() }) {
                                    historyItems = historyItems.map { item ->
                                        if (item.ownStatus.isBlank()) item.copy(ownStatus = STATUS_UNKNOWN) else item
                                    }
                                }
                                val error = result.exceptionOrNull()
                                    ?: IllegalStateException("读取失败且未携带原因")
                                if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                                    historyError = "登录已过期，正在安全续登"
                                    setPendingSessionRetry(ModuleReadRetry(ModuleReadRequest.ROLLCALL_HISTORY))
                                } else {
                                    // 失败保留已展示数据只报错（SWR 语义）
                                    historyError = refreshFailureMessage(error)
                                }
                            }
                        }
                    } finally {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            historyLoading = false
                        }
                    }
                }
            } finally {
                // 门释放提到协程最外层（NonCancellable）：与 refresh() 同款防"门永久占用"
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish("rollcall_history")
                }
            }
        }
        return true
    }

    /** 登出/换号清理：清内存状态（持久化由调用方一并清理，与拆分前一致）。 */
    fun clearAll() {
        events = emptyList()
        loading = false
        refreshError = ""
        updatedAtMillis = 0L
        // 历史区块与进行中两块同清（磁盘缓存由调用方 deleteRollcallHistoryCacheFile 一并删）
        historyItems = emptyList()
        historyLoading = false
        historyError = ""
        historyUpdatedAtMillis = 0L
        historyLoaded = false
    }
}
