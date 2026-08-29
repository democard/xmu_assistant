package com.xmu.assistant

import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页登录/登出/停监控动作协调器（出层自 MainScreen 的 onClick 巨型 lambda，
 * 约 200 行）。组合作用域普通类（不引入 ViewModel），依赖经构造注入，与
 * SectionState 模式一致；函数体逐字保留（仅 return@HomePage 适配为 return）。
 *
 * 只读现值以方法参数传入：调用转发 lambda 每次重组重建，等价于出层前对
 * 组合帧参数的捕获；异步完成回调读取最新值仍走函数式注入（busyNow /
 * monitorTransitionInProgressNow / monitorTransitionIdNow）。
 */
internal class HomeActions(
    private val activity: ComponentActivity,
    private val workScope: CoroutineScope,
    private val settings: AssistantSettings,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val pendingModuleRefresh: PendingModuleRefresh,
    private val scores: ScoreSectionState,
    private val schedule: ScheduleSectionState,
    private val exam: ExamSectionState,
    private val rollcall: RollcallSectionState,
    private val courseware: CoursewareSectionState,
    private val show: (String) -> Unit,
    private val showWarning: (String) -> Unit,
    private val showError: (String) -> Unit,
    private val setBusy: (String) -> Unit,
    private val busyNow: () -> String,
    private val setAccountTransitionInProgress: (Boolean) -> Unit,
    private val monitorWrites: MonitorWritePath,
    private val monitorTransitionInProgressNow: () -> Boolean,
    private val monitorTransitionIdNow: () -> Long,
    private val setAcademicCache: (AcademicCacheSnapshot) -> Unit,
    private val courseWrites: CoursesWritePath,
    private val setPendingSessionRetry: (ModuleReadRetry?) -> Unit,
    private val setCookieHeader: (String) -> Unit,
    private val setLoggedIn: (Boolean) -> Unit,
    private val setStartupSessionReady: (Boolean) -> Unit,
    private val clearLoggedOutUi: () -> Unit,
    private val refreshMonitorHealth: () -> Unit,
) {
    // 写路径组解构回原名（B6 第二批）：方法体既有调用点零改动。
    private val setMonitorTransitionId = monitorWrites.setMonitorTransitionId
    private val setMonitorTransitionInProgress = monitorWrites.setMonitorTransitionInProgress
    private val setMonitorLastCheckMillis = monitorWrites.setMonitorLastCheckMillis
    private val setMonitorConsecutiveFailures = monitorWrites.setMonitorConsecutiveFailures
    private val setMonitorLastError = monitorWrites.setMonitorLastError
    private val setMonitorRunning = monitorWrites.setMonitorRunning
    private val setCourses = courseWrites.setCourses
    private val setSelectedCourseId = courseWrites.setSelectedCourseId
    private val setCoursesLoading = courseWrites.setCoursesLoading
    private val setCoursesRefreshError = courseWrites.setCoursesRefreshError
    fun onLogin(
        username: String,
        password: String,
        accountTransitionInProgress: Boolean,
        monitorTransitionId: Long,
    ) {
        if (accountTransitionInProgress) return
        if (username.isBlank() || password.isBlank()) {
            showWarning("请输入学号和密码")
            return
        }
        val accountUsername = username
        val accountPassword = password
        ProcessSessionRecovery.coordinator.invalidate()
        // 手动登录与自动恢复共用同一把进程级单飞锁：
        // tryStartAutoLogin 成功才允许发请求，失败说明 autoLogin/recover 在途，
        // 直接拒绝，杜绝双身份域并发登录（CAS 风控红线）。
        val loginToken = ProcessSessionRecovery.coordinator.tryStartAutoLogin()
        if (loginToken == null) {
            show("已有登录进行中，请稍候")
            return
        }
        val loginAttempt = sessionEpoch.beginLoginAttempt(
            sessionOwner,
            accountUsername,
            accountPassword,
        ) ?: run {
            ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken)
            return
        }
        setAccountTransitionInProgress(true)
        setBusy(BusyStates.LOGGING_IN)
        workScope.launch(Dispatchers.IO) {
            runCatching { TronclassLogin().login(accountUsername, accountPassword) }
                .onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        try {
                            if (!sessionEpoch.completeLogin(loginAttempt, username, password)) {
                                // completeLogin 刚以同一实参判定失败（属主/世代/凭据不匹配
                                // 均不可逆，重查 acceptsLoginAttempt 恒 false——原内层分支是
                                // 死代码已删）；按钮复位由 finally 无条件兜底
                                return@withContext
                            }
                            settings.username = accountUsername
                            settings.password = accountPassword
                            settings.cookieHeader = result.cookieHeader
                            // 换账号登录：清掉上一个账号的教务(ids/jw) cookie 与全部数据残留
                            // （成绩持久化、课表、考试、提醒、桌面小卡片、课程缓存），
                            // 否则 B 账号直接看到 A 的成绩/课表/考试，A 的提醒继续对 B 触发（串号/串提醒）。
                            settings.scoreCookieHeader = ""
                            settings.scoreRecordsJson = ""
                            settings.scoreUpdatedAtMillis = 0L
                            settings.academicCacheJson = ""
                            scores.clearAll()
                            schedule.clearAll()
                            deleteScheduleSnapshotFile(activity)
                            // 最近十次签到缓存按账号绑定，换号一并删除（防串号）
                            deleteRollcallHistoryCacheFile(activity)
                            ScheduleWidgetData.clear(activity)
                            ScheduleWidgetProvider.refreshAll(activity)
                            setAcademicCache(AcademicCacheSnapshot())
                            setCourses(emptyList())
                            setSelectedCourseId(null)
                            courseware.clearAll()
                            // 换号登录：考试缓存/提醒随账号会话清空（防串号串提醒）
                            exam.clearAll()
                            // 与 clearLoggedOutUi 对齐的 transient 状态（两条路径共用语义，
                            // 换号不残留旧账号的 loading/error/监控/待刷新状态）
                            rollcall.clearAll()
                            setCoursesLoading(false)
                            setCoursesRefreshError("")
                            pendingModuleRefresh.clear()
                            setPendingSessionRetry(null)
                            setMonitorTransitionId(monitorTransitionId + 1)
                            setMonitorTransitionInProgress(false)
                            setMonitorLastCheckMillis(0L)
                            setMonitorConsecutiveFailures(0)
                            setMonitorLastError("")
                            // 持久化监控健康键同步清零（clearSession 同款）：换号不调
                            // clearSession，refreshMonitorHealth 会从 prefs 回读旧账号值
                            settings.monitorLastCheckMillis = 0L
                            settings.monitorConsecutiveFailures = 0
                            settings.monitorLastError = ""
                            // 按学期手动周次校准随账号清理（登出路径同款，防 B 账号
                            // 继承 A 的基准导致周次错位）
                            settings.clearManualAcademicWeeks()
                            settings.markLoginSucceeded()
                            setCookieHeader(result.cookieHeader)
                            setLoggedIn(true)
                            setStartupSessionReady(true)
                            if (settings.monitorDesired) {
                                activity.startForegroundService(Intent(activity, RollcallMonitorService::class.java))
                                setMonitorRunning(true)
                            }
                            refreshMonitorHealth()
                            setAccountTransitionInProgress(false)
                            setBusy(BusyStates.IDLE)
                            show("登录成功")
                        } finally {
                            ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken)
                            // 无条件复位（与 onFailure 分支同款）：completeLogin 拒绝的
                            // early-return 也必须恢复按钮，否则 accountTransitionInProgress
                            // 永真 → 首页登录/退出/监控全部死锁
                            setAccountTransitionInProgress(false)
                            if (busyNow() == BusyStates.LOGGING_IN) setBusy(BusyStates.IDLE)
                        }
                    }
                }
                .onFailure {
                    withContext(Dispatchers.Main) {
                        try {
                            if (!sessionEpoch.acceptsLoginAttempt(loginAttempt, username, password)) {
                                if (sessionEpoch.acceptsLoginAttempt(
                                        loginAttempt,
                                        accountUsername,
                                        accountPassword,
                                    )
                                ) {
                                    setAccountTransitionInProgress(false)
                                    setBusy(BusyStates.IDLE)
                                    showWarning("登录信息已更改，请重新登录")
                                }
                                return@withContext
                            }
                            setAccountTransitionInProgress(false)
                            setBusy(BusyStates.IDLE)
                            showError("登录失败：${friendlyMessage(it)}")
                        } finally {
                            ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken)
                            // 无条件复位：early-return 路径（epoch 不匹配）也必须恢复按钮，
                            // 否则 accountTransitionInProgress 永真 → 首页登录/退出/监控全部死锁。
                            setAccountTransitionInProgress(false)
                            if (busyNow() == BusyStates.LOGGING_IN) setBusy(BusyStates.IDLE)
                        }
                    }
                }
        }
    }

    fun onLogout(accountTransitionInProgress: Boolean) {
        if (!sessionEpoch.isOwnerActive(sessionOwner) || accountTransitionInProgress) {
            return
        }
        RollcallMonitorService.requestInvalidateActiveRun()
        sessionEpoch.invalidate(sessionOwner)
        ProcessSessionRecovery.coordinator.invalidate()
        settings.markUserLoggedOut()
        settings.clearSession()
        clearLoggedOutUi()
        setAccountTransitionInProgress(true)
        setBusy(BusyStates.LOGGING_OUT)
        workScope.launch(Dispatchers.IO) {
            RollcallMonitorService.awaitActiveRunQuiescence()
            // 最终取消考试提醒：quiescence 完成晚于任何在途提醒重排，
            // 与 clearLoggedOutUi 的立即取消双保险，确保登出后旧账号闹钟不残留
            ExamReminder.cancelAll(activity)
            withContext(Dispatchers.Main) {
                if (!sessionEpoch.isOwnerActive(sessionOwner)) return@withContext
                // 契约测试要求：quiescence 屏障后再清一次 session——屏障期间被放行的
                // 健康写可能把 cookie 重新写回，重清才能封死「会话复活」（非冗余）
                settings.clearSession()
                setAccountTransitionInProgress(false)
                if (busyNow() == BusyStates.LOGGING_OUT) setBusy(BusyStates.IDLE)
                show("已退出登录")
            }
        }
    }

    fun onStopMonitor(
        monitorTransitionInProgress: Boolean,
        monitorTransitionId: Long,
    ) {
        if (monitorTransitionInProgress) return
        // 注意：monitorTransitionId 是参数快照（重组时更新），
        // 必须先算新值再写，随后用同一新值作本轮的 stop 身份；
        // 不能在 setter 之后再读参数（读到的是旧值，会令
        // monitorTransitionId != stopTransitionId 恒成立 → 永不复位）。
        val nextTransitionId = monitorTransitionId + 1
        setMonitorTransitionId(nextTransitionId)
        val stopTransitionId = nextTransitionId
        RollcallMonitorService.requestInvalidateActiveRun()
        settings.monitorDesired = false
        activity.stopService(Intent(activity, RollcallMonitorService::class.java))
        MonitorControlTileService.requestResync(activity)
        setMonitorRunning(false)
        setMonitorTransitionInProgress(true)
        setBusy(BusyStates.PAUSING_MONITOR)
        workScope.launch(Dispatchers.IO) {
            RollcallMonitorService.awaitActiveRunQuiescence()
            withContext(Dispatchers.Main) {
                if (!sessionEpoch.isOwnerActive(sessionOwner) ||
                    !monitorTransitionInProgressNow() ||
                    monitorTransitionIdNow() != stopTransitionId
                ) {
                    return@withContext
                }
                setMonitorTransitionInProgress(false)
                if (busyNow() == BusyStates.PAUSING_MONITOR) setBusy(BusyStates.IDLE)
                refreshMonitorHealth()
                show("监控已暂停")
            }
        }
    }
}
