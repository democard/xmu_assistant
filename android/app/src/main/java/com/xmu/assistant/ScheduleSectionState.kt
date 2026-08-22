package com.xmu.assistant

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 进程级课表快照缓存：转屏（Activity 重建）时复用内存数据，避免先清空再异步重载的回退；
 *  登出/换号时清空（跟随账号会话）。 */
@Volatile
internal var processScheduleSnapshot: XmuScheduleSnapshot? = null

/**
 * 课表模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *   后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态；
 *   会话过期时经 setPendingSessionRetry 挂起一次性安全续登（recoverExpiredModule 处理）。
 * - 课表走教务 CAS 身份域：恢复路径（alreadyRetriedAfterRecovery）由组合侧传入
 *   accountTransitionInProgress/busy 读写器统一复位 transition（与拆分前一致）。
 * - 请求统一走 "schedule" 互斥门（RequestGate）。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *   不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class ScheduleSectionState(
    private val activity: Activity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val requireLogin: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val accountTransitionInProgress: () -> Boolean,
    private val setAccountTransitionInProgress: (Boolean) -> Unit,
    private val busy: () -> String,
    private val setBusy: (String) -> Unit,
    private val scoreCookieHeader: () -> String,
    private val setScoreCookieHeader: (String) -> Unit,
    private val manualAcademicWeek: (String) -> Int,
    private val setStartupSessionReady: (Boolean) -> Unit,
    private val setPendingSessionRetry: (ModuleReadRequest) -> Unit,
    private val clearToast: () -> Unit,
    snapshotInitial: XmuScheduleSnapshot = XmuScheduleSnapshot(),
) {
    /** 完整快照（含反推日历）：widget 同步与手动周次校准读取 inferredCalendars。 */
    var cache by mutableStateOf(snapshotInitial)
        private set

    var entries by mutableStateOf(snapshotInitial.entries)
        private set

    var termCode by mutableStateOf(snapshotInitial.termCode)
        private set

    var updatedAtMillis by mutableStateOf(snapshotInitial.updatedAtMillis)
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    /** 强制刷新课表。登录/恢复在途时禁止并发刷新：课表刷新内部可能触发 academic CAS
     *  续登（ensureScheduleSession），与 TronClass 手动登录并发会打两个身份域（风控红线）。 */
    fun refresh(alreadyRetriedAfterRecovery: Boolean = false): Boolean {
        if (accountTransitionInProgress() && !alreadyRetriedAfterRecovery) {
            show("登录处理中，请稍候")
            return false
        }
        if (!requireLogin()) return false
        if (username().isBlank() || password().isBlank()) {
            show("请先在首页填写学号和密码")
            return false
        }
        if (!requestGate.tryStart("schedule")) return false
        loading = true
        refreshError = ""
        val accountUsername = username()
        val accountPassword = password()
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching {
                    fetchScheduleWithNetworkRetry(
                        username = accountUsername,
                        password = accountPassword,
                        scoreCookieHeader = scoreCookieHeader(),
                        mayRelogin = { sessionEpoch.isCurrent(session) },
                    )
                }
                withContext(Dispatchers.Main) {
                    try {
                    if (sessionEpoch.accepts(session, cookieHeader(), loggedIn()) &&
                        username() == accountUsername && password() == accountPassword
                    ) {
                        result.onSuccess { refreshResult ->
                            val updatedAt = System.currentTimeMillis()
                            val newTermCode = refreshResult.termCode
                            val newEntries = refreshResult.entries
                            // 动态反推日历：官方表没有的学期，用系统当前周次 + 当天日期反推，
                            // 校验通过后按学期缓存进快照，下次直接复用。
                            val calendars = HashMap(cache.inferredCalendars)
                            val official = xmuAcademicCalendarForTerm(newTermCode)
                            if (official != null) {
                                // 官方校历表已覆盖该学期：删除旧反推条目，
                                // 防止升级前缓存的过期反推开学日继续生效
                                calendars.remove(newTermCode)
                            } else if (refreshResult.currentWeek != null) {
                                xmuTryInferCalendar(
                                    termCode = newTermCode,
                                    currentWeek = refreshResult.currentWeek,
                                    today = LocalDate.now(),
                                )?.let { calendars[newTermCode] = it }
                            }
                            val snapshot = XmuScheduleSnapshot(
                                entries = newEntries,
                                termCode = newTermCode,
                                updatedAtMillis = updatedAt,
                                inferredCalendars = calendars,
                            )
                            // 文件缓存与加密 prefs 写入挪到后台线程，UI 线程只做内存状态更新，
                            // 避免刷新完成瞬间的主线程磁盘/加密 IO 卡顿。
                            // 写入前校验会话世代仍有效：若用户已退出登录，跳过持久化，
                            // 防止陈旧请求把已清除的 cookie/课表写回（登录态"复活"）。
                            val persistSnapshot = snapshot
                            val persistCookie = refreshResult.jwCookie
                            scope.launch(Dispatchers.IO) {
                                if (sessionEpoch.isCurrent(session)) {
                                    saveScheduleSnapshotToFile(activity, persistSnapshot)
                                    setScoreCookieHeader(persistCookie)
                                }
                            }
                            cache = snapshot
                            termCode = newTermCode
                            entries = newEntries
                            updatedAtMillis = updatedAt
                            refreshError = ""
                            // 进程级快照同步最新网络数据：转屏（Activity 重建）复用，
                            // 避免回退到磁盘旧缓存
                            processScheduleSnapshot = snapshot
                            syncScheduleWidget(
                                activity,
                                newEntries,
                                newTermCode,
                                inferredCalendar = snapshot.inferredCalendars[newTermCode],
                                manualWeek = manualAcademicWeek(newTermCode),
                            )
                            // 恢复路径（alreadyRetriedAfterRecovery）成功时补发"会话已就绪"，
                            // 释放 startup 期间缓存的待刷新模块（与 startStartupAutoLogin 成功回调对齐）。
                            if (alreadyRetriedAfterRecovery) {
                                setStartupSessionReady(true)
                            }
                            show("课表已更新")
                        }
                        result.onFailure { error ->
                            if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                                refreshError = "教务登录已过期，正在安全续登"
                                setPendingSessionRetry(ModuleReadRequest.SCHEDULE)
                            } else {
                                refreshError = refreshFailureMessage(error)
                                if (entries.isEmpty()) {
                                    show("课表读取失败：$refreshError")
                                } else {
                                    // 有缓存可展示时静默失败：清掉旧的失败提示，保留已展示数据
                                    clearToast()
                                }
                            }
                        }
                    }
                } finally {
                    // 恢复路径（alreadyRetriedAfterRecovery）统一复位 transition，
                    // 无论成功/失败都恢复首页按钮可用；普通刷新不触碰该标志。
                    if (alreadyRetriedAfterRecovery) {
                        setAccountTransitionInProgress(false)
                        if (busy() == "会话已过期，正在安全重登") setBusy("")
                    }
                    if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                        loading = false
                    }
                }
            }
            } finally {
                // 门释放提到协程最外层（NonCancellable）：协程取消时内层 withContext 整段
                // 跳过，finish 放内层会导致 "schedule" 门永久占用
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish("schedule")
                }
            }
        }
        return true
    }

    /** 启动时应用磁盘缓存快照（异步加载完成后调用；仅当仍为空快照时由组合侧守卫）。 */
    fun applySnapshot(initial: XmuScheduleSnapshot) {
        cache = initial
        entries = initial.entries
        termCode = initial.termCode
        updatedAtMillis = initial.updatedAtMillis
        processScheduleSnapshot = initial
    }

    /** 启动/转屏时异步加载磁盘缓存并应用（文件 + 旧版加密 prefs 迁移，避免启动首帧主线程磁盘 IO）。
     *  守卫：仅当仍登录且当前仍是空快照才应用（isEligible），防止登出后旧账号缓存复现、
     *  也防止覆盖启动期间的网络刷新数据。 */
    fun loadCachedSnapshotOnStartup(
        activity: Activity,
        settings: AssistantSettings,
        isEligible: () -> Boolean,
    ) {
        scope.launch(Dispatchers.IO) {
            // 优先读明文文件缓存（快、免解密）；文件不存在时回退旧加密 prefs 并迁移
            val fromFile = loadScheduleSnapshotFromFile(activity)
            val initial = if (fromFile.entries.isNotEmpty()) {
                fromFile
            } else {
                val legacy = xmuScheduleSnapshotFromJson(settings.scheduleCacheJson)
                if (legacy.entries.isNotEmpty()) {
                    saveScheduleSnapshotToFile(activity, legacy)
                    // 写回后读回校验，确认文件缓存可用才清理旧版加密残留，避免误删数据
                    if (loadScheduleSnapshotFromFile(activity).entries.isNotEmpty()) {
                        settings.clearScheduleCacheLegacyPref()
                    }
                }
                legacy
            }
            // 文件缓存已可用但旧版加密残留仍在（此前迁移过但未清理）→ 顺手清一次
            if (initial.entries.isNotEmpty() && settings.scheduleCacheJson.isNotBlank()) {
                settings.clearScheduleCacheLegacyPref()
            }
            withContext(Dispatchers.Main) {
                // 会话守卫：仅当仍登录且缓存仍为空才应用——
                // 防止登出后旧账号缓存复现（clearLoggedOutUi 会清内存并删文件），
                // 也防止覆盖启动期间的网络刷新数据。
                if (isEligible() && cache.entries.isEmpty() && termCode.isBlank()) {
                    applySnapshot(initial)
                }
            }
        }
    }

    /** 手动指定周次时写入/替换该学期的反推日历并落盘（与拆分前 onManualWeekSet 一致）。 */
    fun setInferredCalendar(termCode: String, calendar: XmuAcademicCalendar) {
        val calendars = HashMap(cache.inferredCalendars)
        calendars[termCode] = calendar
        val updated = cache.copy(inferredCalendars = calendars)
        cache = updated
        persistSnapshotAsync(updated)
    }

    /** 恢复自动判断周次：移除该学期的手动校准日历并落盘。 */
    fun removeInferredCalendar(termCode: String) {
        if (!cache.inferredCalendars.containsKey(termCode)) return
        val calendars = HashMap(cache.inferredCalendars)
        calendars.remove(termCode)
        val updated = cache.copy(inferredCalendars = calendars)
        cache = updated
        persistSnapshotAsync(updated)
    }

    /** 落盘挪到后台线程：策略页每次校准/清除都在 UI 线程写整个学期 JSON 会卡顿
     *  （刷新路径已约定「避免主线程磁盘 IO」，此处对齐）。 */
    private fun persistSnapshotAsync(snapshot: XmuScheduleSnapshot) {
        val context = activity
        scope.launch(Dispatchers.IO) {
            saveScheduleSnapshotToFile(context, snapshot)
        }
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loading = false
    }

    /** 登出/换号清理：清内存状态并清进程级快照（持久化由调用方一并清理，与拆分前一致）。 */
    fun clearAll() {
        cache = XmuScheduleSnapshot()
        entries = emptyList()
        termCode = ""
        updatedAtMillis = 0L
        loading = false
        refreshError = ""
        processScheduleSnapshot = null
    }
}

internal suspend fun fetchScheduleWithNetworkRetry(
    username: String,
    password: String,
    scoreCookieHeader: String,
    mayRelogin: () -> Boolean,
): ScheduleRefreshResult {
    var lastFailure: Throwable? = null
    // 首次循环先建立会话（可能续登拿到新 cookie），成功后缓存 jwCookie：
    // - 后续重试直接复用已建立的会话，不再重复 ensureScheduleSession
    //   （网络抖动重试时省掉额外的会话初始化请求）；
    // - 同时避免「会话过期 + 网络抖动」组合下一次手动刷新触发多次 CAS 登录。
    var jwCookie: String? = null
    repeat(3) { attempt ->
        try {
            if (jwCookie == null) {
                val academicSession = XmuScoreAutoQueryClient(
                    username = username,
                    password = password,
                    cookieHeader = scoreCookieHeader,
                    mayRelogin = mayRelogin,
                )
                jwCookie = academicSession.ensureScheduleSession()
            }
            val result = XmuScheduleClient(jwCookie!!)
                .fetchCurrentSchedule(username)
            return ScheduleRefreshResult(
                jwCookie = jwCookie!!,
                termCode = result.termCode,
                entries = result.entries,
                currentWeek = result.currentWeek,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            // 协程取消（Activity 销毁/Worker 取消）必须立即上抛，
            // 不能进入「归一化 + 延迟重试」路径无谓推迟取消响应
            throw error
        } catch (error: Throwable) {
            val normalized = normalizeScheduleFailure(error)
            if (normalized !is ScheduleNetworkException || attempt == 2) {
                throw normalized
            }
            lastFailure = normalized
            delay(1500L * (attempt + 1))
        }
    }
    throw lastFailure ?: IllegalStateException("课表刷新失败")
}

internal data class ScheduleRefreshResult(
    val jwCookie: String,
    val termCode: String,
    val entries: List<XmuScheduleEntry>,
    val currentWeek: Int?,
)
