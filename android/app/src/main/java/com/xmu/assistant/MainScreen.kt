package com.xmu.assistant

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 考试安排模块组合侧持有者：rememberSaveable + Saver 恢复用户选择的学期与"是否手动选择"标记。
 * （从 XmuAssistantApp 提取，调用位置不变；summary/validTerms 等其余状态由
 *  ExamSectionState 构造后的 init 协程从磁盘缓存异步回填。）
 */
@Composable
internal fun rememberExamSection(
    activity: ComponentActivity,
    requestGate: RequestGate,
    sessionEpoch: SessionEpoch,
    sessionOwner: SessionOwner,
    scope: CoroutineScope,
    show: (String) -> Unit,
    showWarning: (String) -> Unit,
    showError: (String) -> Unit,
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
                    showWarning = showWarning,
                    showError = showError,
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
            showWarning = showWarning,
            showError = showError,
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
 * 首页只读渲染态分组（2026-08-27 参数收敛第一批，批次三·宁少勿破）：
 * 仅聚合「值语义」的展示输入；凡读写器成对的 stale-guard 参数组
 * （accountTransition 設置器、monitorTransition 全家、busyNow 读取器等）
 * 一律保持独立成对参数，不收拢以免破坏守卫语义。
 * 体内经 component 解构回原名，渲染逻辑与契约锚定文本零变更。
 */
internal data class IdentityUiState(
    val username: String,
    val password: String,
    val loggedIn: Boolean,
)

/** 监控运行展示六件套（B2 方案 A 并入 transition 值字段）：值语义展示输入。
 *  写路径（setMonitorTransition*）与 *Now 读取器仍是独立成对参数——异步回调读最新值
 *  防世代误判（见形参区注释），不随 UiState 快照重组（方案 A 保守边界，方案 B 另议）。 */
internal data class MonitorUiState(
    val monitorRunning: Boolean,
    val monitorConsecutiveFailures: Int,
    val monitorLastCheckMillis: Long,
    val monitorLastError: String,
    val monitorTransitionInProgress: Boolean,
    val monitorTransitionId: Long,
)

/** 课程列表页读取态：配套 setter 仍在 MainScreen 形参（组合侧写路径）。 */
internal data class CoursesUiState(
    val courses: List<CourseSummary>,
    val selectedCourse: CourseSummary?,
    val coursesLoading: Boolean,
    val coursesRefreshError: String,
)

/**
 * 瞬时状态展示三件套（参数收敛第二批）：单条 toast 文案 / busy 文案 / 下载角标数，
 * 外加 toast 的显式级别（B4，默认 INFO）。
 * 值语义展示输入；busy 的写路径（setBusy）与 toast 的清空仍走组合侧独立参数。
 */
internal data class StatusUiState(
    val toast: String,
    val toastSeverity: ToastSeverity,
    val busy: String,
    val downloadingCount: Int,
)

/** 监控状态写路径分组（B6 第二批，沿 e04e381 归组手法）：纯写通道聚合，
 *  字段名与原独立参数逐字相同；*Now 读取器保持独立参数（世代守卫，不随组）。 */
internal class MonitorWritePath(
    val setMonitorRunning: (Boolean) -> Unit,
    val setMonitorTransitionId: (Long) -> Unit,
    val setMonitorTransitionInProgress: (Boolean) -> Unit,
    val setMonitorLastCheckMillis: (Long) -> Unit,
    val setMonitorConsecutiveFailures: (Int) -> Unit,
    val setMonitorLastError: (String) -> Unit,
)

/** 课程列表页写路径分组（同上）：纯写通道聚合，字段名与原独立参数逐字相同。 */
internal class CoursesWritePath(
    val setCourses: (List<CourseSummary>) -> Unit,
    val setSelectedCourseId: (String?) -> Unit,
    val setCoursesLoading: (Boolean) -> Unit,
    val setCoursesRefreshError: (String) -> Unit,
)

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
    homeActions: HomeActions,
    page: String,
    openedEventId: String,
    identity: IdentityUiState,
    setUsername: (String) -> Unit,
    setPassword: (String) -> Unit,
    notificationSettings: NotificationSettings,
    onNotificationSettingsSaved: (NotificationSettings) -> Unit,
    status: StatusUiState,
    accountTransitionInProgress: Boolean,
    monitor: MonitorUiState,
    monitorWrites: MonitorWritePath,
    // 回调守卫用「当前值读取器」：组合侧状态经 setter 写入后，重组是异步的，
    // 协程回调里读参数快照拿到的是点击时的旧值（导致守卫误判提前 return）。
    // 因此暂停/登出等异步完成回调必须经读取器取最新值，与 SectionState 的函数式注入一致。
    rollcallSettings: RollcallSettings,
    setRollcallSettings: (RollcallSettings) -> Unit,
    themeMode: String,
    setThemeMode: (String) -> Unit,
    widgetGuideOpen: Boolean,
    setWidgetGuideOpen: (Boolean) -> Unit,
    widgetPermissionGuideOpen: Boolean,
    setWidgetPermissionGuideOpen: (Boolean) -> Unit,
    courseState: CoursesUiState,
    courseWrites: CoursesWritePath,
    coursesUpdatedAtMillis: Long,
    coursewareUpdatedAtMillis: Long,
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
    requireLogin: () -> Boolean,
    show: (String) -> Unit,
    onModuleEntered: (String) -> Unit,
    testNotifications: (NotificationSettings) -> Unit,
    onRefreshCourses: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    refreshMonitorHealth: () -> Unit,
    requestNotificationPermissionIfNeeded: () -> Unit,
) {
    // 只读态就地解构回原参数名：本文件既有渲染代码零改动
    // （字段名与拆分前形参一一对应）。
    val (username, password, loggedIn) = identity
    val (monitorRunning, monitorConsecutiveFailures, monitorLastCheckMillis, monitorLastError) = monitor
    // 写路径组解构回原名（B6）：体内既有调用点零改动；仅取实际使用的通道。
    val setMonitorRunning = monitorWrites.setMonitorRunning
    val (courses, selectedCourse, coursesLoading, coursesRefreshError) = courseState
    val (toast, toastSeverity, busy, downloadingCount) = status

    // 桌面小卡片 pin 结果延时校验（批次四·两段重复 postDelayed 合并 + 可取消化）：
    // 3 秒后 widget 实例数未增加即判定失败并触发对应回退引导。
    // 原实现用裸 Handler.postDelayed，不随组合销毁取消——切页/退出后定时器
    // 仍会命中并弹引导；改走 workScope 协程随组合销毁自动取消。
    fun verifyPinAdded(onPinFailed: () -> Unit) {
        val provider = ComponentName(activity, ScheduleWidgetProvider::class.java)
        val before = AppWidgetManager.getInstance(activity)
            .getAppWidgetIds(provider).size
        workScope.launch {
            delay(3000)
            val after = AppWidgetManager.getInstance(activity)
                .getAppWidgetIds(provider).size
            if (after <= before) onPinFailed()
        }
    }

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
                                historyItems = rollcall.historyItems,
                                historyLoading = rollcall.historyLoading,
                                historyError = rollcall.historyError,
                                historyUpdatedAtMillis = rollcall.historyUpdatedAtMillis,
                                loggedIn = loggedIn,
                                onRefresh = {
                                    // 顶部按钮一键刷两块（独立互斥门，可并行）
                                    rollcall.refresh()
                                    rollcall.refreshHistory()
                                },
                            )
                            "成绩" -> ScorePage(
                                records = scores.scoreRecords,
                                loading = scores.loading,
                                refreshError = scores.refreshError,
                                loggedIn = loggedIn,
                                updatedAtMillis = scores.updatedAtMillis,
                                onRefresh = { scores.refresh() },
                                onShareScore = { scores.shareLongImage() },
                            )
                            "考试安排" -> ExamPage(
                                summary = exam.summary,
                                validTerms = exam.validTerms,
                                selectedTerm = exam.selectedTerm,
                                loading = exam.loading,
                                refreshError = exam.refreshError,
                                autoUpdated = exam.autoUpdated,
                                loggedIn = loggedIn,
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
                                loggedIn = loggedIn,
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
                                            loggedIn = loggedIn,
                                            onRefresh = { schedule.refresh() },
                                            inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode],
                                            // 手动周次快照化：manualAcademicWeek 读加密 prefs + 解析 JSON，组合期
                                            // 每次重组重复读会拖帧——按学期键 remember 只在切换时读一次
                                            manualWeek = remember(schedule.termCode) { settings.manualAcademicWeek(schedule.termCode) },
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
                                                        // 体检报告 P1-6：表外学期靠反推日历/手动周次校准，
                                                        // 漏传会退回官方表查询 → week=null → 写入空快照，
                                                        // 把原本正确的「今日课程」卡片清空。
                                                        // 与 MainActivity/ScheduleSectionState 的调用点保持同参。
                                                        inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode],
                                                        manualWeek = settings.manualAcademicWeek(schedule.termCode),
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
                                                        verifyPinAdded { setWidgetPermissionGuideOpen(true) }
                                                    } else {
                                                        setWidgetPermissionGuideOpen(true)
                                                    }
                                                } else if (requestScheduleWidget(activity)) {
                                                    show("已请求添加桌面小卡片，请按系统提示确认…")
                                                    // 延时校验：部分桌面会谎报支持却静默失败，
                                                    // 若 3 秒后 widget 实例数未增加，说明没真正加上，回退手动指引。
                                                    verifyPinAdded { setWidgetGuideOpen(true) }
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
                                            monitorTransitionInProgress = monitor.monitorTransitionInProgress,
                                            monitorStatus = monitorStatusText(monitorRunning, monitorConsecutiveFailures),
                                            monitorLastCheck = formatMonitorTime(monitorLastCheckMillis),
                                            monitorFailureCount = monitorConsecutiveFailures,
                                            monitorLastError = monitorLastError,
                                            autoEnabled = rollcallSettings.autoAnswerNumber || rollcallSettings.autoAnswerRadar,
                                            recentEvent = rollcall.events.firstOrNull(),
                                            onUsername = setUsername,
                                            onPassword = setPassword,
                                            onLogin = { homeActions.onLogin(username, password, accountTransitionInProgress, monitor.monitorTransitionId) },
                                            onLogout = { homeActions.onLogout(accountTransitionInProgress) },
                                            onStartMonitor = {
                                                if (!monitor.monitorTransitionInProgress && !accountTransitionInProgress && requireLogin()) {
                                                    requestNotificationPermissionIfNeeded()
                                                    settings.monitorDesired = true
                                                    activity.startForegroundService(Intent(activity, RollcallMonitorService::class.java))
                                                    MonitorControlTileService.requestResync(activity)
                                                    setMonitorRunning(true)
                                                    refreshMonitorHealth()
                                                    show("监控已启动")
                                                }
                                            },
                                            onStopMonitor = { homeActions.onStopMonitor(monitor.monitorTransitionInProgress, monitor.monitorTransitionId) },
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
                // 级别判定（B4）：显式 severity 优先；未迁移调用点回落到子串推断兜底
                //（"失败"/"请先"），busy / 成绩刷新中恒为中性 INFO。
                val toastSubstringWarning = toast.contains("失败") || toast.contains("请先")
                val activeToast: String = when {
                    toast.isNotBlank() -> toast
                    busy.isNotBlank() -> busy
                    // 跨页不残留「正在刷新成绩」：仅成绩页计入其 loading（审查 MEDIUM）
                    scores.loading && page == "成绩" -> "正在刷新成绩"
                    else -> ""
                }
                val activeSeverity = when {
                    toast.isNotBlank() && toastSeverity != ToastSeverity.INFO -> toastSeverity
                    toast.isNotBlank() && toastSubstringWarning -> ToastSeverity.WARNING
                    else -> ToastSeverity.INFO
                }
                if (activeToast.isNotBlank()) ToastBar(activeToast, severity = activeSeverity)
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
                                Text(stringResource(R.string.widget_guide_ok))
                            }
                        } else {
                            TextButton(onClick = {
                                setWidgetGuideOpen(false)
                                // 三星/Pixel/通用 AOSP 等：该 Intent 是真正的「主屏幕」设置入口。
                                runCatching {
                                    activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                                }
                            }) {
                                Text(stringResource(R.string.widget_guide_open_settings))
                            }
                        }
                    },
                    dismissButton = if (isColorOS) {
                        // 只保留一个「好的」按钮
                        null
                    } else {
                        {
                            TextButton(onClick = { setWidgetGuideOpen(false) }) {
                                Text(stringResource(R.string.widget_guide_got_it))
                            }
                        }
                    },
                    title = { Text(stringResource(R.string.widget_guide_title)) },
                    text = {
                        Text(
                            if (isColorOS) {
                                stringResource(R.string.widget_guide_coloros_body)
                            } else {
                                stringResource(R.string.widget_guide_generic_body)
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
                            Text(stringResource(R.string.widget_permission_guide_open))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { setWidgetPermissionGuideOpen(false) }) {
                            Text(stringResource(R.string.widget_permission_guide_got_it))
                        }
                    },
                    title = { Text(stringResource(R.string.widget_permission_guide_title)) },
                    text = {
                        Text(
                            stringResource(R.string.widget_permission_guide_body),
                        )
                    },
                )
            }
        }
    }
}
}
