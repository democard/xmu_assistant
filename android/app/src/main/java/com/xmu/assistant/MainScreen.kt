package com.xmu.assistant

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 考试安排模块组合侧持有者：rememberSaveable + Saver 恢复用户选择的学期与"是否手动选择"标记。
 * （从 XmuAssistantApp 提取，调用位置不变；summary/validTerms 等其余状态在
 *  ExamSectionState 构造时从磁盘缓存重载，与拆分前一致。）
 */
@Composable
internal fun rememberExamSection(
    activity: ComponentActivity,
    requestGate: RequestGate,
    sessionEpoch: SessionEpoch,
    sessionOwner: SessionOwner,
    scope: CoroutineScope,
    show: (String) -> Unit,
    loggedIn: () -> Boolean,
    cookieHeader: () -> String,
    username: () -> String,
    password: () -> String,
    accountTransitionInProgress: () -> Boolean,
    scoreCookieHeader: () -> String,
    setScoreCookieHeader: (String) -> Unit,
    reminderSettings: () -> ExamReminderSettings,
): ExamSectionState {
    return rememberSaveable(
        saver = Saver(
            save = { state -> state.savedState() },
            restore = { saved ->
                ExamSectionState(
                    activity = activity,
                    requestGate = requestGate,
                    sessionEpoch = sessionEpoch,
                    sessionOwner = sessionOwner,
                    scope = scope,
                    show = show,
                    loggedIn = loggedIn,
                    cookieHeader = cookieHeader,
                    username = username,
                    password = password,
                    accountTransitionInProgress = accountTransitionInProgress,
                    scoreCookieHeader = scoreCookieHeader,
                    setScoreCookieHeader = setScoreCookieHeader,
                    reminderSettings = reminderSettings,
                    selectedTermInitial = (saved as? List<*>)?.getOrNull(0) as? String ?: "",
                    manuallySelectedInitial = (saved as? List<*>)?.getOrNull(1) as? Boolean ?: false,
                )
            },
        ),
    ) {
        ExamSectionState(
            activity = activity,
            requestGate = requestGate,
            sessionEpoch = sessionEpoch,
            sessionOwner = sessionOwner,
            scope = scope,
            show = show,
            loggedIn = loggedIn,
            cookieHeader = cookieHeader,
            username = username,
            password = password,
            accountTransitionInProgress = accountTransitionInProgress,
            scoreCookieHeader = scoreCookieHeader,
            setScoreCookieHeader = setScoreCookieHeader,
            reminderSettings = reminderSettings,
        )
    }
}

/**
 * 主界面 UI 骨架（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 组合侧（XmuAssistantApp）只保留状态与编排，本文件只做页面渲染：
 * - 各模块状态由 SectionState 对象承载（exam/rollcall/scores/schedule/courseware），
 *   通过参数注入；组合侧状态用读写器（函数式注入，与 SectionState 模式一致）。
 * - 页面内联回调（登录/登出/监控开关/桌面小卡片/手动周次等）与拆分前逐字对齐，
 *   全部依赖经构造参数注入，行为零变更。
 */
@Composable
internal fun MainScreen(
    activity: ComponentActivity,
    workScope: CoroutineScope,
    page: String,
    openedEventId: String,
    loggedIn: Boolean,
    notificationSettings: NotificationSettings,
    onNotificationSettingsSaved: (NotificationSettings) -> Unit,
    downloadingCount: Int,
    busy: String,
    setBusy: (String) -> Unit,
    toast: String,
    username: String,
    password: String,
    setUsername: (String) -> Unit,
    setPassword: (String) -> Unit,
    accountTransitionInProgress: Boolean,
    setAccountTransitionInProgress: (Boolean) -> Unit,
    monitorTransitionInProgress: Boolean,
    setMonitorTransitionInProgress: (Boolean) -> Unit,
    monitorRunning: Boolean,
    setMonitorRunning: (Boolean) -> Unit,
    monitorConsecutiveFailures: Int,
    setMonitorConsecutiveFailures: (Int) -> Unit,
    monitorLastCheckMillis: Long,
    setMonitorLastCheckMillis: (Long) -> Unit,
    monitorLastError: String,
    setMonitorLastError: (String) -> Unit,
    monitorTransitionId: Long,
    setMonitorTransitionId: (Long) -> Unit,
    // 回调守卫用「当前值读取器」：组合侧状态经 setter 写入后，重组是异步的，
    // 协程回调里读参数快照拿到的是点击时的旧值（导致守卫误判提前 return）。
    // 因此暂停/登出等异步完成回调必须经读取器取最新值，与 SectionState 的函数式注入一致。
    busyNow: () -> String,
    monitorTransitionInProgressNow: () -> Boolean,
    monitorTransitionIdNow: () -> Long,
    rollcallSettings: RollcallSettings,
    setRollcallSettings: (RollcallSettings) -> Unit,
    themeMode: String,
    setThemeMode: (String) -> Unit,
    widgetGuideOpen: Boolean,
    setWidgetGuideOpen: (Boolean) -> Unit,
    widgetPermissionGuideOpen: Boolean,
    setWidgetPermissionGuideOpen: (Boolean) -> Unit,
    courses: List<CourseSummary>,
    setCourses: (List<CourseSummary>) -> Unit,
    selectedCourse: CourseSummary?,
    setSelectedCourseId: (String?) -> Unit,
    coursesLoading: Boolean,
    setCoursesLoading: (Boolean) -> Unit,
    coursesRefreshError: String,
    setCoursesRefreshError: (String) -> Unit,
    coursesUpdatedAtMillis: Long,
    coursewareUpdatedAtMillis: Long,
    academicCache: AcademicCacheSnapshot,
    setAcademicCache: (AcademicCacheSnapshot) -> Unit,
    examReminder: ExamReminderSettings,
    onExamReminderChanged: (ExamReminderSettings) -> Unit,
    onOpenExamAlarmSettings: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    rollcall: RollcallSectionState,
    scores: ScoreSectionState,
    exam: ExamSectionState,
    schedule: ScheduleSectionState,
    courseware: CoursewareSectionState,
    settings: AssistantSettings,
    sessionEpoch: SessionEpoch,
    sessionOwner: SessionOwner,
    pendingModuleRefresh: PendingModuleRefresh,
    setPendingSessionRetry: (ModuleReadRetry?) -> Unit,
    setCookieHeader: (String) -> Unit,
    setLoggedIn: (Boolean) -> Unit,
    setStartupSessionReady: (Boolean) -> Unit,
    requireLogin: () -> Boolean,
    show: (String) -> Unit,
    onModuleEntered: (String) -> Unit,
    testNotifications: (NotificationSettings) -> Unit,
    onRefreshCourses: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    clearLoggedOutUi: () -> Unit,
    refreshMonitorHealth: () -> Unit,
    requestNotificationPermissionIfNeeded: () -> Unit,
) {
    XmuMobileTheme(themeMode = themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BrandHeader(loggedIn = loggedIn)
                    TopTabs(
                        selected = page,
                        notificationSettings = notificationSettings,
                        downloadingCount = downloadingCount,
                        onSelected = onModuleEntered,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        when (page) {
                            "签到情况" -> RollcallStatusPage(
                                events = rollcall.events,
                                openedEventId = openedEventId,
                                loading = rollcall.loading,
                                refreshError = rollcall.refreshError,
                                updatedAtMillis = rollcall.updatedAtMillis,
                                onRefresh = { rollcall.refresh() },
                            )
                            "成绩" -> ScorePage(
                                records = scores.scoreRecords,
                                loading = scores.loading,
                                refreshError = scores.refreshError,
                                updatedAtMillis = scores.updatedAtMillis,
                                onRefresh = { scores.refresh() },
                                onShareScore = { scores.shareLongImage() },
                            )
                            "考试安排" -> ExamPage(
                                activity = activity,
                                summary = exam.summary,
                                validTerms = exam.validTerms,
                                selectedTerm = exam.selectedTerm,
                                loading = exam.loading,
                                refreshError = exam.refreshError,
                                autoUpdated = exam.autoUpdated,
                                onSelectTerm = exam::selectTerm,
                                onRefresh = exam::refresh,
                            )
                            "课程课件" -> CoursewarePage(
                                courses = courses,
                                selectedCourse = selectedCourse,
                                coursewareItems = courseware.coursewareItems,
                                selectedIds = courseware.selectedCoursewareIds,
                                onRefreshCourses = onRefreshCourses,
                                onCourseSelected = { courseware.load(it) },
                                onToggle = courseware::toggleSelected,
                                onSelectAll = courseware::toggleSelectAll,
                                onDownload = courseware::downloadSelected,
                                onRefreshCourseware = {
                                    selectedCourse?.let { courseware.load(it, forceRefresh = true) } ?: show("请先选择课程")
                                },
                                onOpenPlatform = courseware::openSelected,
                                downloadProgress = courseware.coursewareDownloadProgress,
                                downloadLoading = courseware.downloadLoading,
                                coursesLoading = coursesLoading,
                                coursesRefreshError = coursesRefreshError,
                                coursesUpdatedAtMillis = coursesUpdatedAtMillis,
                                coursewareLoading = selectedCourse?.id in courseware.loadingIds,
                                coursewareRefreshError = selectedCourse?.id
                                    ?.let { courseware.refreshErrors[it] }
                                    .orEmpty(),
                                coursewareUpdatedAtMillis = coursewareUpdatedAtMillis,
                            )
                            else -> key(page) {
                                // 每页独立滚动状态：共享同一 ScrollState 会让切页后
                                // 继承上一页的滚动位置（教程滚到底，首页/课表跟着从底部开始）。
                                // rememberSaveable(saver = ScrollState.Saver)：转屏后保留滚动位置。
                                val pageScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(pageScrollState),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    when (page) {
                                        "课表" -> SchedulePage(
                                            entries = schedule.entries,
                                            termCode = schedule.termCode,
                                            updatedAtMillis = schedule.updatedAtMillis,
                                            loading = schedule.loading,
                                            refreshError = schedule.refreshError,
                                            onRefresh = { schedule.refresh() },
                                            inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode],
                                            manualWeek = settings.manualAcademicWeek(schedule.termCode),
                                        )
                                        "通知" -> NotificationSettingsPage(settings, notificationSettings, testNotifications) {
                                            onNotificationSettingsSaved(it)
                                            show("通知设置已保存")
                                        }
                                        "教程" -> TutorialPage(pageScrollState, onModuleEntered)
                                        "策略" -> StrategyPage(
                                            settings,
                                            schedule.termCode,
                                            rollcallSettings,
                                            themeMode,
                                            onThemeModeChanged = { mode ->
                                                setThemeMode(mode)
                                                settings.themeMode = mode
                                                show("外观已更新")
                                            },
                                            onSaved = {
                                                setRollcallSettings(it)
                                                show("策略已保存")
                                            },
                                            onWidgetToggle = { enabled ->
                                                settings.widgetEnabled = enabled
                                                if (schedule.entries.isNotEmpty()) {
                                                    syncScheduleWidget(
                                                        activity,
                                                        schedule.entries,
                                                        schedule.termCode,
                                                    )
                                                } else {
                                                    // 课表尚未加载：只应用开/关状态并刷新 widget，
                                                    // 避免空数据覆盖既有"今日课程"摘要。
                                                    ScheduleWidgetProvider.refreshAll(activity)
                                                }
                                                show(if (enabled) "桌面小卡片已开启" else "桌面小卡片已关闭")
                                            },
                                            onAddWidget = {
                                                if (knownPinBlockedOem(activity)) {
                                                    // 一加/OPPO/realme/vivo（ColorOS 系 + vivo）系统硬拒第三方
                                                    // pin，跳过注定失败的请求，直接跳到系统「小部件」入口引导。
                                                    setWidgetGuideOpen(true)
                                                } else if (needsShortcutPermission(activity)) {
                                                    // 小米/Redmi：先发起 pin，若已授予「桌面快捷方式」权限即可
                                                    // 直接成功；否则会谎报成功、静默失败，3 秒校验不过再回退到权限引导。
                                                    if (requestScheduleWidget(activity)) {
                                                        show("已请求添加桌面小卡片，请按系统提示确认…")
                                                        val provider = ComponentName(activity, ScheduleWidgetProvider::class.java)
                                                        val before = AppWidgetManager.getInstance(activity)
                                                            .getAppWidgetIds(provider).size
                                                        Handler(Looper.getMainLooper()).postDelayed({
                                                            val after = AppWidgetManager.getInstance(activity)
                                                                .getAppWidgetIds(provider).size
                                                            if (after <= before) setWidgetPermissionGuideOpen(true)
                                                        }, 3000)
                                                    } else {
                                                        setWidgetPermissionGuideOpen(true)
                                                    }
                                                } else if (requestScheduleWidget(activity)) {
                                                    show("已请求添加桌面小卡片，请按系统提示确认…")
                                                    // 延时校验：部分桌面会谎报支持却静默失败，
                                                    // 若 3 秒后 widget 实例数未增加，说明没真正加上，回退手动指引。
                                                    val provider = ComponentName(activity, ScheduleWidgetProvider::class.java)
                                                    val before = AppWidgetManager.getInstance(activity)
                                                        .getAppWidgetIds(provider).size
                                                    Handler(Looper.getMainLooper()).postDelayed({
                                                        val after = AppWidgetManager.getInstance(activity)
                                                            .getAppWidgetIds(provider).size
                                                        if (after <= before) setWidgetGuideOpen(true)
                                                    }, 3000)
                                                } else {
                                                    setWidgetGuideOpen(true)
                                                }
                                            },
                                            onManualWeekSet = { week ->
                                                settings.setManualAcademicWeek(schedule.termCode, week)
                                                // 手动指定周次时，用「手动周次 + 当天日期」反推开学日并缓存。
                                                // 手动路径不受 60 天窗口限制（用户显式指定第几周即权威）；
                                                // 但仍校验开学日落在合理月份窗口，防暑假把日期反推到假期里。
                                                if (week > 0 && schedule.termCode.isNotBlank()) {
                                                    val inferred = xmuTryInferCalendar(
                                                        termCode = schedule.termCode,
                                                        currentWeek = week,
                                                        today = LocalDate.now(),
                                                        maxStartAgeDays = 200L,
                                                    )
                                                    if (inferred != null) {
                                                        schedule.setInferredCalendar(schedule.termCode, inferred)
                                                        show("已设为第 ${week} 周，开学日期已校准")
                                                    } else {
                                                        show(
                                                            "已设为第 ${week} 周，但日期不在合理开学窗口内，" +
                                                                "开学后会自动校准（或确认当前周次后重试）",
                                                        )
                                                    }
                                                } else if (week == 0) {
                                                    // 恢复自动判断：清除该学期的手动校准缓存，
                                                    // 让页面回到官方校历/自动反推，而不是继续用旧的手动结果。
                                                    schedule.removeInferredCalendar(schedule.termCode)
                                                    show("已恢复自动判断周次")
                                                }
                                                // 手动校准后同步桌面 Widget，保持与课表页一致
                                                if (schedule.termCode.isNotBlank()) {
                                                    syncScheduleWidget(
                                                        activity,
                                                        schedule.entries,
                                                        schedule.termCode,
                                                        inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode],
                                                        manualWeek = settings.manualAcademicWeek(schedule.termCode),
                                                    )
                                                }
                                            },
                                            examReminder = examReminder,
                                            onExamReminderChanged = onExamReminderChanged,
                                            onOpenExamAlarmSettings = onOpenExamAlarmSettings,
                                            onOpenFullScreenSettings = onOpenFullScreenSettings,
                                        )
                                        else -> HomePage(
                                            username = username,
                                            password = password,
                                            loggedIn = loggedIn,
                                            accountTransitionInProgress = accountTransitionInProgress,
                                            monitorTransitionInProgress = monitorTransitionInProgress,
                                            monitorStatus = monitorStatusText(monitorRunning, monitorConsecutiveFailures),
                                            monitorLastCheck = formatMonitorTime(monitorLastCheckMillis),
                                            monitorFailureCount = monitorConsecutiveFailures,
                                            monitorLastError = monitorLastError,
                                            autoEnabled = rollcallSettings.autoAnswerNumber || rollcallSettings.autoAnswerRadar,
                                            recentEvent = rollcall.events.firstOrNull(),
                                            onUsername = setUsername,
                                            onPassword = setPassword,
                                            onLogin = {
                                                if (accountTransitionInProgress) return@HomePage
                                                if (username.isBlank() || password.isBlank()) {
                                                    show("请输入学号和密码")
                                                    return@HomePage
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
                                                    return@HomePage
                                                }
                                                val loginAttempt = sessionEpoch.beginLoginAttempt(
                                                    sessionOwner,
                                                    accountUsername,
                                                    accountPassword,
                                                ) ?: run {
                                                    ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken)
                                                    return@HomePage
                                                }
                                                setAccountTransitionInProgress(true)
                                                setBusy("正在登录")
                                                workScope.launch(Dispatchers.IO) {
                                                    runCatching { TronclassLogin().login(accountUsername, accountPassword) }
                                                        .onSuccess { result ->
                                                            withContext(Dispatchers.Main) {
                                                                try {
                                                                    if (!sessionEpoch.completeLogin(loginAttempt, username, password)) {
                                                                        if (sessionEpoch.acceptsLoginAttempt(
                                                                                loginAttempt,
                                                                                accountUsername,
                                                                                accountPassword,
                                                                            )
                                                                        ) {
                                                                            setAccountTransitionInProgress(false)
                                                                            setBusy("")
                                                                            show("登录信息已更改，请重新登录")
                                                                        }
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
                                                                    setBusy("")
                                                                    show("登录成功")
                                                                } finally {
                                                                    ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken)
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
                                                                            setBusy("")
                                                                            show("登录信息已更改，请重新登录")
                                                                        }
                                                                        return@withContext
                                                                    }
                                                                    setAccountTransitionInProgress(false)
                                                                    setBusy("")
                                                                    show("登录失败：${friendlyMessage(it)}")
                                                                } finally {
                                                                    ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken)
                                                                    // 无条件复位：early-return 路径（epoch 不匹配）也必须恢复按钮，
                                                                    // 否则 accountTransitionInProgress 永真 → 首页登录/退出/监控全部死锁。
                                                                    setAccountTransitionInProgress(false)
                                                                    if (busyNow() == "正在登录") setBusy("")
                                                                }
                                                            }
                                                        }
                                                }
                                            },
                                            onLogout = {
                                                if (!sessionEpoch.isOwnerActive(sessionOwner) || accountTransitionInProgress) {
                                                    return@HomePage
                                                }
                                                RollcallMonitorService.requestInvalidateActiveRun()
                                                sessionEpoch.invalidate(sessionOwner)
                                                ProcessSessionRecovery.coordinator.invalidate()
                                                settings.markUserLoggedOut()
                                                settings.clearSession()
                                                clearLoggedOutUi()
                                                setAccountTransitionInProgress(true)
                                                setBusy("正在退出登录")
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
                                                        if (busyNow() == "正在退出登录") setBusy("")
                                                        show("已退出登录")
                                                    }
                                                }
                                            },
                                            onStartMonitor = {
                                                if (!monitorTransitionInProgress && !accountTransitionInProgress && requireLogin()) {
                                                    requestNotificationPermissionIfNeeded()
                                                    settings.monitorDesired = true
                                                    activity.startForegroundService(Intent(activity, RollcallMonitorService::class.java))
                                                    setMonitorRunning(true)
                                                    refreshMonitorHealth()
                                                    show("监控已启动")
                                                }
                                            },
                                            onStopMonitor = {
                                                if (monitorTransitionInProgress) return@HomePage
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
                                                setMonitorRunning(false)
                                                setMonitorTransitionInProgress(true)
                                                setBusy("正在暂停监控")
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
                                                        if (busyNow() == "正在暂停监控") setBusy("")
                                                        refreshMonitorHealth()
                                                        show("监控已暂停")
                                                    }
                                                }
                                            },
                                            onAutoChanged = { enabled ->
                                                val next = rollcallSettings.copy(
                                                    autoAnswerNumber = enabled,
                                                    autoAnswerRadar = enabled,
                                                )
                                                setRollcallSettings(next)
                                                settings.saveRollcall(next)
                                                show(if (enabled) "已开启自动签到" else "已关闭自动签到")
                                            },
                                            onOpenBackgroundSettings = onOpenBackgroundSettings,
                                        )
                                    }
                                }
                            }
                        }
                    }
                // 底部提示收敛为单条：原先三条 ToastBar（成绩刷新中 / busy / toast）会同时渲染，
                // 视觉嘈杂且互相挤占。按优先级合并为单条显示——
                // 警告类 toast > 进行中 busy > 后台成绩刷新，避免提示轰炸。
                val toastWarning = toast.contains("失败") || toast.contains("请先")
                val activeToast: String = when {
                    toast.isNotBlank() -> toast
                    busy.isNotBlank() -> busy
                    // 跨页不残留「正在刷新成绩」：仅成绩页计入其 loading（审查 MEDIUM）
                    scores.loading && page == "成绩" -> "正在刷新成绩"
                    else -> ""
                }
                val activeWarning = when {
                    toast.isNotBlank() -> toastWarning
                    scores.loading -> false
                    else -> false
                }
                if (activeToast.isNotBlank()) ToastBar(activeToast, warning = activeWarning)
            }

            if (widgetGuideOpen) {
                // 一加/OPPO/realme/vivo（ColorOS 系）没有可直达「小部件」面板的公共 Intent，
                // Settings.ACTION_HOME_SETTINGS 在这些机型上只会打开「选择默认桌面应用」选择器，
                // 反而把用户带偏。因此 ColorOS 上不跳，只给清晰的手动步骤。
                val isColorOS = knownPinBlockedOem(activity)
                AlertDialog(
                    onDismissRequest = { setWidgetGuideOpen(false) },
                    confirmButton = {
                        if (isColorOS) {
                            // ColorOS 上只有一个按钮，避免出现两个「知道了」的重复按钮。
                            TextButton(onClick = { setWidgetGuideOpen(false) }) {
                                Text("好的")
                            }
                        } else {
                            TextButton(onClick = {
                                setWidgetGuideOpen(false)
                                // 三星/Pixel/通用 AOSP 等：该 Intent 是真正的「主屏幕」设置入口。
                                runCatching {
                                    activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                                }
                            }) {
                                Text("打开桌面设置")
                            }
                        }
                    },
                    dismissButton = if (isColorOS) {
                        // 只保留一个「好的」按钮
                        null
                    } else {
                        {
                            TextButton(onClick = { setWidgetGuideOpen(false) }) {
                                Text("知道了")
                            }
                        }
                    },
                    title = { Text("添加桌面小卡片") },
                    text = {
                        Text(
                            if (isColorOS) {
                                "受系统限制，App 没法直接把小卡片放到桌面，手动添加很简单：\n\n" +
                                    "1. 回到桌面，长按空白处\n" +
                                    "2. 在弹出的菜单里选「小部件」\n" +
                                    "3. 找到「xmu助手」，长按拖到桌面\n\n" +
                                    "添加后，每次刷新课表，卡片会自动显示今天的课。"
                            } else {
                                "系统没能直接添加成功，也可以手动添加：\n\n" +
                                    "1. 点「打开桌面设置」进入桌面设置\n" +
                                    "2. 回到桌面，长按空白处 → 选「小部件」\n" +
                                    "3. 找到「xmu助手」，长按拖到桌面\n\n" +
                                    "添加后，每次刷新课表，卡片会自动显示今天的课。"
                            },
                        )
                    },
                )
            }

            if (widgetPermissionGuideOpen) {
                AlertDialog(
                    onDismissRequest = { setWidgetPermissionGuideOpen(false) },
                    confirmButton = {
                        TextButton(onClick = {
                            setWidgetPermissionGuideOpen(false)
                            // 一键跳到本应用设置页，让用户手动开启「桌面快捷方式」权限。
                            runCatching {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", activity.packageName, null),
                                    ),
                                )
                            }
                        }) {
                            Text("去开启权限")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { setWidgetPermissionGuideOpen(false) }) {
                            Text("我知道了")
                        }
                    },
                    title = { Text("需开启「桌面快捷方式」权限") },
                    text = {
                        Text(
                            "检测到是小米 / Redmi 设备：系统支持一键添加小卡片，但需要应用先拥有" +
                                "「桌面快捷方式」权限，否则会添加失败且不报错。\n\n" +
                                "点「去开启权限」会跳到应用设置页，请找到「桌面快捷方式」并允许，" +
                                "然后返回本页重新点击「添加到桌面」即可。\n\n" +
                                "（部分 MIUI / HyperOS 版本中该权限名为「创建桌面快捷方式」或" +
                                "在「权限管理 → 其他权限」里。）",
                        )
                    },
                )
            }
        }
    }
}
}
