package com.xmu.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var openedEventId by mutableStateOf("")
    private var openedPage by mutableStateOf("")
    // 深链递增序号：内容相等的重复深链（如连续点同一桌面小卡片）也必须重新消费。
    // 原「内容相等」一次性消费（consumedPage != openedPage）会让第二次点击失效——
    // LaunchedEffect key 未变连 effect 都不重启，页面停留在首页（进程存活时必现）。
    private var deepLinkSeq by mutableStateOf(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedEventId = intent.data?.lastPathSegment.orEmpty()
        openedPage = intent.getStringExtra("open_page").orEmpty()
        deepLinkSeq = 1
        // 注册「每日自动同步课表 + 桌面小卡片」后台任务（KEEP 策略幂等：同 key 只保留一个周期任务）。
        // 挪到 IO 线程：enqueue 内部是同步 Room 写入，主线程执行会拖慢冷启动首帧。
        // 同协程内顺带：
        // 1) 预热加密设置（MasterKey+EncryptedSharedPreferences 构建较慢），与组合期首次访问竞速；
        // 2) 教务会话保温——单请求探测既有教务 Cookie（滑动过期机制下保持热态），
        //    减少用户打开成绩/课表页时的完整 CAS 认证链。绝不自动续登（mayRelogin 恒 false）。
        lifecycleScope.launch(Dispatchers.IO) {
            val warmSettings = runCatching { AssistantSettings(this@MainActivity) }.getOrNull()
            ScheduleWidgetSyncWorker.scheduleDaily(applicationContext)
            if (warmSettings != null && warmSettings.scoreCookieHeader.isNotBlank()) {
                runCatching {
                    XmuScoreAutoQueryClient(
                        username = "",
                        password = "",
                        cookieHeader = warmSettings.scoreCookieHeader,
                        mayRelogin = { false },
                    ).probeAcademicSession()
                }
            }
        }
        setContent { XmuAssistantApp(this, openedEventId, openedPage, deepLinkSeq) }
    }

    override fun onStart() {
        super.onStart()
        AppForegroundTracker.foreground = true
    }

    override fun onStop() {
        super.onStop()
        AppForegroundTracker.foreground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedEventId = intent.data?.lastPathSegment.orEmpty()
        openedPage = intent.getStringExtra("open_page").orEmpty()
        deepLinkSeq += 1
    }
}

@Composable
fun XmuAssistantApp(activity: ComponentActivity, openedEventId: String, openedPage: String = "", deepLinkSeq: Int = 1) {
    val settings = remember { AssistantSettings(activity) }
    val sessionEpoch = ProcessSessionEpoch.instance
    val sessionOwner = remember { sessionEpoch.attachOwner() }
    // 组合作用域：随 Activity 销毁取消，替代裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
    val workScope = rememberCoroutineScope()
    val moduleRefreshTracker = remember { ForegroundModuleRefreshTracker() }
    val pendingModuleRefresh = remember { PendingModuleRefresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var startupStartGeneration by remember { mutableStateOf(0) }
    var startupSessionReady by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, sessionOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                sessionEpoch.activateOwner(sessionOwner)
            }
            if (event == Lifecycle.Event.ON_START) {
                moduleRefreshTracker.beginForeground()
                startupSessionReady = false
                startupStartGeneration += 1
            }
            if (event == Lifecycle.Event.ON_STOP) {
                moduleRefreshTracker.endForeground()
                pendingModuleRefresh.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sessionEpoch.detachOwner(sessionOwner)
        }
    }
    // 支持从桌面小卡片点击直接进入指定页（如「课表」）；无指定则回首页。
    // rememberSaveable：旋转屏幕（Activity 重建）后停留在原页，而不是跳回首页。
    val knownPages = listOf("首页", "签到情况", "成绩", "课表", "考试安排", "课程课件", "通知", "教程", "策略")
    var page by rememberSaveable {
        mutableStateOf(openedPage.takeIf { it in knownPages } ?: "首页")
    }
    // 桌面小卡片等外部跳转消费：按 Activity 递增序号（deepLinkSeq）一次性处理，
    // 修复「内容相等」判断导致同一 Widget/通知第二次点按不切页的必现 bug。
    // consumedDeepLinkSeq 用 rememberSaveable：旋转重建后 Activity 的 seq 重置为 1，
    // 已保存的消费序号 ≥ 当前 seq 时跳过，rememberSaveable 恢复的 page 不被旧深链覆盖。
    var consumedDeepLinkSeq by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(deepLinkSeq) {
        if (deepLinkSeq <= consumedDeepLinkSeq) return@LaunchedEffect
        if (openedPage in knownPages && openedPage != page) {
            page = openedPage
        }
        // 签到通知深链（xmurollcall://rollcall/<id>）不带 open_page 但带事件 id：
        // 用户不在签到页时切到签到页，保证通知点按能看到对应事件。
        if (openedEventId.isNotBlank() && page != "签到情况") {
            page = "签到情况"
        }
        consumedDeepLinkSeq = deepLinkSeq
    }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf(settings.password) }
    var cookieHeader by remember { mutableStateOf(settings.cookieHeader) }
    var loggedIn by remember { mutableStateOf(cookieHeader.isNotBlank()) }
    var monitorRunning by remember { mutableStateOf(false) }
    var monitorLastCheckMillis by remember { mutableStateOf(settings.monitorLastCheckMillis) }
    var monitorConsecutiveFailures by remember { mutableStateOf(settings.monitorConsecutiveFailures) }
    var monitorLastError by remember { mutableStateOf(settings.monitorLastError) }
    var notificationSettings by remember { mutableStateOf(settings.notifications()) }
    var rollcallSettings by remember { mutableStateOf(settings.rollcall()) }
    var academicCache by remember { mutableStateOf(academicCacheFromJson(settings.academicCacheJson)) }
    var courses by remember { mutableStateOf(academicCache.courses) }
    // 记住选中的课程 id（rememberSaveable：转屏/进程重建后恢复），课程对象从 courses 派生。
    // 不直接保存 CourseSummary，避免自定义 Saver，也保证课程列表刷新后仍能对应。
    var selectedCourseId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCourse = remember(courses, selectedCourseId) {
        courses.firstOrNull { it.id == selectedCourseId }
    }
    // 异步加载课表缓存（文件 + 旧版加密 prefs 迁移）：避免启动首帧主线程磁盘 IO。
    // 守卫：仅当当前仍是空快照（启动期间未被网络刷新填充）才应用缓存，防止旧缓存覆盖新数据。
    // 该 LaunchedEffect 需要在 schedule 分块创建后执行（见下方 schedule 实例化之后）。
    var coursesLoading by remember { mutableStateOf(false) }
    var coursesRefreshError by remember { mutableStateOf("") }
    var pendingSessionRetry by remember { mutableStateOf<ModuleReadRetry?>(null) }
    var busy by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    var accountTransitionInProgress by remember { mutableStateOf(false) }
    var monitorTransitionInProgress by remember { mutableStateOf(false) }
    var monitorTransitionId by remember { mutableStateOf(0L) }
    val requestGate = remember { RequestGate() }
    // 外观主题模式（浅色/深色/跟随系统），用户可在策略页底部切换
    var themeMode by remember { mutableStateOf(settings.themeMode) }

    // toastSeq：相同文案连续 show() 时值不变、旧计时器不重启会把「新」toast 提前清掉，
    // 用递增序号作 effect key 保证每次 show 都重新计时
    var toastSeq by remember { mutableStateOf(0L) }

    fun show(message: String) {
        toast = message
        toastSeq += 1
    }

    // 桌面小卡片手动添加指引弹窗：一加/ColorOS/vivo 等桌面硬拒系统一键 pin 时弹出，
    // 引导用户走系统「小部件」入口（按钮跳桌面设置页）。
    var widgetGuideOpen by remember { mutableStateOf(false) }

    // 桌面小卡片「需权限」引导弹窗：小米/Redmi 的 MIUI/HyperOS 在缺少「桌面快捷方式」
    // 权限时会谎报 pin 成功、实际静默失败，校验失败后引导用户去应用设置开启权限。
    var widgetPermissionGuideOpen by remember { mutableStateOf(false) }

    // 瞬时提示自动消退：toast 是页面底部内嵌横幅，此前只有切页/下一次 show() 才清除，
    // "正在刷新，请稍候""已读取 N 门"这类提示会一直挂着（用户误以为刷新卡死）。
    // 3 秒后自动清空；以 toastSeq 为 key：新一轮 show()（含同文案）取消旧计时并重新计时。
    LaunchedEffect(toastSeq) {
        if (toast.isNotBlank()) {
            kotlinx.coroutines.delay(3000)
            toast = ""
        }
    }

    fun requireLogin(): Boolean {
        if (cookieHeader.isBlank()) {
            show("请先登录")
            return false
        }
        return true
    }

    // 考试安排模块（独立分块）：状态与动作都在 ExamSectionState 中，这里只保留编排。
    // rememberSaveable + Saver：转屏/进程重建后恢复用户选择的学期与"是否手动选择"标记；
    // summary/validTerms 等其余状态在 ExamSectionState 构造时从磁盘缓存重载（与拆分前一致）。
    val exam = rememberExamSection(
        activity = activity,
        requestGate = requestGate,
        sessionEpoch = sessionEpoch,
        sessionOwner = sessionOwner,
        scope = workScope,
        show = ::show,
        loggedIn = { loggedIn },
        cookieHeader = { cookieHeader },
        username = { username },
        password = { password },
        accountTransitionInProgress = { accountTransitionInProgress },
        scoreCookieHeader = { settings.scoreCookieHeader },
        setScoreCookieHeader = { settings.scoreCookieHeader = it },
        reminderSettings = {
            ExamReminderSettings(
                enabled = settings.examReminderEnabled,
                advanceMinutes = settings.examReminderAdvanceMinutes,
                fullScreenEnabled = settings.examReminderFullScreen,
            )
        },
    )

    // 进入考试安排页：立即显示缓存（复用上一次结果），后台悄悄检查变化；
    // 有缓存时不显示"正在刷新"表达，检测到变化才更新（悄悄检查模式）。
    LaunchedEffect(page) {
        if (page != "考试安排") return@LaunchedEffect
        if (!loggedIn || cookieHeader.isBlank()) return@LaunchedEffect
        // 登录/恢复在途时跳过本次进入检查（风控红线：不并发打两个身份域），
        // 登录完成后再次进入页面或手动刷新即可加载。
        if (accountTransitionInProgress) return@LaunchedEffect
        exam.silentRefresh()
    }

    // —— 分块状态持有者（状态 + 动作独立成类，模式同 ExamSectionState）——
    // 签到：状态与动作在 RollcallSectionState，组合侧只保留编排。
    val rollcall = remember {
        RollcallSectionState(
            activity = activity,
            requestGate = requestGate,
            sessionEpoch = sessionEpoch,
            sessionOwner = sessionOwner,
            scope = workScope,
            show = ::show,
            requireLogin = ::requireLogin,
            loggedIn = { loggedIn },
            cookieHeader = { cookieHeader },
            setPendingSessionRetry = { req -> pendingSessionRetry = ModuleReadRetry(req) },
        )
    }
    // 成绩：状态与动作在 ScoreSectionState；持久化（scoreRecordsJson 等）仍留在组合侧 settings。
    val scores = remember {
        ScoreSectionState(
            activity = activity,
            requestGate = requestGate,
            sessionEpoch = sessionEpoch,
            sessionOwner = sessionOwner,
            scope = workScope,
            show = ::show,
            loggedIn = { loggedIn },
            cookieHeader = { cookieHeader },
            username = { username },
            password = { password },
            accountTransitionInProgress = { accountTransitionInProgress },
            scoreCookieHeader = { settings.scoreCookieHeader },
            setScoreCookieHeader = { settings.scoreCookieHeader = it },
            setScoreRecordsJson = { settings.scoreRecordsJson = it },
            setScoreUpdatedAtMillisPref = { settings.scoreUpdatedAtMillis = it },
            scoreRecordsInitial = xmuScoreRecordsFromJson(settings.scoreRecordsJson),
            scoreUpdatedAtMillisInitial = settings.scoreUpdatedAtMillis,
        )
    }
    // 课表：状态与动作在 ScheduleSectionState；启动/转屏恢复用进程级快照（与拆分前一致）。
    val schedule = remember {
        ScheduleSectionState(
            activity = activity,
            requestGate = requestGate,
            sessionEpoch = sessionEpoch,
            sessionOwner = sessionOwner,
            scope = workScope,
            show = ::show,
            requireLogin = ::requireLogin,
            loggedIn = { loggedIn },
            cookieHeader = { cookieHeader },
            username = { username },
            password = { password },
            accountTransitionInProgress = { accountTransitionInProgress },
            setAccountTransitionInProgress = { accountTransitionInProgress = it },
            busy = { busy },
            setBusy = { busy = it },
            scoreCookieHeader = { settings.scoreCookieHeader },
            setScoreCookieHeader = { settings.scoreCookieHeader = it },
            manualAcademicWeek = { settings.manualAcademicWeek(it) },
            setStartupSessionReady = { startupSessionReady = it },
            setPendingSessionRetry = { req -> pendingSessionRetry = ModuleReadRetry(req) },
            clearToast = { toast = "" },
            snapshotInitial = processScheduleSnapshot ?: XmuScheduleSnapshot(),
        )
    }
    // 课件：状态与动作在 CoursewareSectionState；课程列表/缓存（academicCache）留在组合侧。
    val courseware = remember {
        CoursewareSectionState(
            activity = activity,
            requestGate = requestGate,
            sessionEpoch = sessionEpoch,
            sessionOwner = sessionOwner,
            scope = workScope,
            show = ::show,
            requireLogin = ::requireLogin,
            loggedIn = { loggedIn },
            cookieHeader = { cookieHeader },
            busy = { busy },
            setBusy = { busy = it },
            selectedCourseId = { selectedCourseId },
            setSelectedCourseId = { selectedCourseId = it },
            academicCache = { academicCache },
            setAcademicCache = { academicCache = it },
            setAcademicCacheJson = { settings.academicCacheJson = it },
            isSelectedCourse = { selectedCourse?.id == it },
            setPendingSessionRetry = { req, course -> pendingSessionRetry = ModuleReadRetry(req, course) },
        )
    }
    // 异步加载课表缓存（文件 + 旧版加密 prefs 迁移）：避免启动首帧主线程磁盘 IO。
    // 守卫：仅当当前仍是空快照（启动期间未被网络刷新填充）才应用缓存，防止旧缓存覆盖新数据。
    // 该 LaunchedEffect 需要在 schedule 分块创建后执行（见下方 schedule 实例化之后）。
    LaunchedEffect(Unit) {
        schedule.loadCachedSnapshotOnStartup(
            activity,
            settings,
            isEligible = { loggedIn && cookieHeader.isNotBlank() },
        )
    }

    fun clearLoggedOutUi() {
        activity.stopService(Intent(activity, RollcallMonitorService::class.java))
        cookieHeader = ""
        loggedIn = false
        rollcall.clearAll()
        scores.clearAll()
        // 清成绩持久化与教务 cookie：避免换账号/重启后旧账号成绩与教务会话残留（串号/复活）
        settings.scoreRecordsJson = ""
        settings.scoreUpdatedAtMillis = 0L
        settings.scoreCookieHeader = ""
        schedule.clearAll()
        // 退出登录：清理明文课缓存、桌面小卡片摘要和手动周次，避免排课信息残留
        deleteScheduleSnapshotFile(activity)
        ScheduleWidgetData.clear(activity)
        settings.clearManualAcademicWeeks()
        academicCache = AcademicCacheSnapshot()
        courses = emptyList()
        coursesLoading = false
        coursesRefreshError = ""
        selectedCourseId = null
        courseware.clearAll()
        // 课程/课件缓存持久化同样清掉（与成绩清理对齐）：登出后重启不残留旧账号课程
        settings.academicCacheJson = ""
        // 退出登录：清考试数据缓存与提醒（考试数据不含个人信息，但跟随账号会话）
        exam.clearAll()
        // 登出后立即刷新桌面小卡片落空态：仅清数据不刷新会让 widget 残留旧课表画面
        ScheduleWidgetProvider.refreshAll(activity)
        pendingModuleRefresh.clear()
        pendingSessionRetry = null
        startupSessionReady = false
        monitorRunning = false
        monitorTransitionId += 1
        monitorTransitionInProgress = false
        monitorLastCheckMillis = 0L
        monitorConsecutiveFailures = 0
        monitorLastError = ""
        busy = ""
    }

    fun invalidateMainSessionUi() {
        activity.stopService(Intent(activity, RollcallMonitorService::class.java))
        settings.cookieHeader = ""
        // scoreCookieHeader 是教务(ids/jw)身份域 cookie：不清会让自动续登失败路径
        // 残留旧 scoreCookie，下次 ensureScheduleSession 拿它打教务（会话"复活"）。
        settings.scoreCookieHeader = ""
        cookieHeader = ""
        loggedIn = false
        monitorRunning = false
        rollcall.clearLoadingState()
        coursesLoading = false
        courseware.clearLoadingState()
        scores.clearLoadingState()
        schedule.clearLoadingState()
        exam.clearLoadingState()
    }

    fun refreshMonitorHealth() {
        monitorLastCheckMillis = settings.monitorLastCheckMillis
        monitorConsecutiveFailures = settings.monitorConsecutiveFailures
        monitorLastError = settings.monitorLastError
    }

    fun openBackgroundSettings() {
        activity.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}"),
            )
        )
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1301)
        }
    }

    fun refreshCourses(alreadyRetriedAfterRecovery: Boolean = false): Boolean {
        if (!requireLogin()) return false
        if (!requestGate.tryStart("courses")) return false
        coursesRefreshError = ""
        coursesLoading = true
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader)
        workScope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { CoursewareClient(activity, session.cookieHeader).fetchCourses() }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader, loggedIn)) {
                            result.onSuccess { list ->
                                coursesRefreshError = ""
                                val updatedAt = System.currentTimeMillis()
                                academicCache = academicCache.withCourses(list, updatedAt)
                                settings.academicCacheJson = academicCacheToJson(academicCache)
                                courses = list
                                if (selectedCourseId != null && selectedCourseId !in list.map { it.id }) {
                                    selectedCourseId = null
                                    courseware.clearAll()
                                }
                                show("已读取 ${list.size} 门课程")
                            }
                            result.onFailure { error ->
                                if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                                    coursesRefreshError = "登录已过期，正在安全续登"
                                    pendingSessionRetry = ModuleReadRetry(ModuleReadRequest.COURSES)
                                } else {
                                    coursesRefreshError = refreshFailureMessage(error)
                                    show("课程读取失败：$coursesRefreshError")
                                }
                            }
                        }
                    } finally {
                        if (sessionEpoch.accepts(session, cookieHeader, loggedIn)) {
                            coursesLoading = false
                        }
                    }
                }
            } finally {
                // 门释放提到协程最外层（NonCancellable）：协程取消时内层 withContext 整段
                // 跳过，finish 放内层会导致 "courses" 门永久占用
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish("courses")
                }
            }
        }
        return true
    }

    fun testNotifications(next: NotificationSettings) {
        settings.saveNotifications(next)
        notificationSettings = next
        busy = "正在发送测试通知"
        workScope.launch(Dispatchers.IO) {
            runCatching {
                if (next.systemEnabled) sendLocalTestNotification(activity)
                if (next.pushPlusEnabled) PushPlusSender(next.pushPlusToken).send("xmu助手 测试通知", "如果你收到这条消息，说明微信通知已配置成功。")
                if (next.qqMailEnabled) QQMailSender(next.qqMailSender, next.qqMailPassword, next.qqMailRecipient, next.qqMailPorts).send(
                    "xmu助手 测试通知",
                    "如果你收到这封邮件，说明 QQ 邮箱提醒已配置成功。",
                )
                if (!next.pushPlusEnabled && !next.qqMailEnabled && !next.systemEnabled) error("请先开启一种通知方式")
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    busy = ""
                    show("测试通知已发送")
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    busy = ""
                    show(friendlyNotificationMessage(it, next))
                }
            }
        }
    }

    fun startStartupAutoLogin(
        onAcceptedSuccess: (() -> Unit)? = null,
        preserveCachedDataOnFailure: Boolean = false,
    ): Boolean {
        val recovery = ProcessSessionRecovery.coordinator
        val accountUsername = username
        val accountPassword = password
        if (settings.autoLoginPolicy != AutoLoginPolicy.ENABLED || accountUsername.isBlank() ||
            accountPassword.isBlank() || !sessionEpoch.isOwnerActive(sessionOwner)
        ) return false
        val recoveryToken = recovery.tryStartAutoLogin() ?: return false
        val loginAttempt = sessionEpoch.beginLoginAttempt(sessionOwner, accountUsername, accountPassword)
        if (loginAttempt == null) {
            recovery.finishAutoLogin(recoveryToken)
            return false
        }
        accountTransitionInProgress = true
        workScope.launch(Dispatchers.IO) {
            runCatching { TronclassLogin().login(accountUsername, accountPassword) }
                .onSuccess { result -> withContext(Dispatchers.Main) {
                    try {
                        if (recovery.accepts(recoveryToken) && sessionEpoch.isOwnerActive(sessionOwner) &&
                            username == accountUsername && password == accountPassword &&
                            sessionEpoch.completeLogin(loginAttempt, username, password)
                        ) {
                            settings.cookieHeader = result.cookieHeader
                            settings.markLoginSucceeded()
                            cookieHeader = result.cookieHeader
                            loggedIn = true
                            startupSessionReady = true
                            if (settings.monitorDesired) {
                                activity.startForegroundService(Intent(activity, RollcallMonitorService::class.java))
                                monitorRunning = true
                            }
                            if (busy == "会话已过期，正在安全重登" || busy == "正在检查登录状态") busy = ""
                            onAcceptedSuccess?.invoke()
                        }
                    } finally {
                        recovery.finishAutoLogin(recoveryToken)
                        accountTransitionInProgress = false
                    }
                } }
                .onFailure { error -> 
                    Log.e(
                        "XmuLogin",
                        "login flow failed: ${error.rootCause().javaClass.simpleName}: " +
                            error.rootCause().message,
                    )
                    withContext(Dispatchers.Main) {
                    try {
                        if (recovery.accepts(recoveryToken) && sessionEpoch.isOwnerActive(sessionOwner) &&
                            username == accountUsername && password == accountPassword &&
                            sessionEpoch.acceptsLoginAttempt(loginAttempt, username, password)
                        ) {
                            settings.markAutoLoginFailed()
                            if (preserveCachedDataOnFailure) {
                                invalidateMainSessionUi()
                                startupSessionReady = false
                            } else {
                                settings.clearSession()
                                clearLoggedOutUi()
                            }
                            pendingModuleRefresh.clear()
                            show("登录已过期，请手动登录")
                            if (busy == "会话已过期，正在安全重登") busy = ""
                        }
                    } finally {
                        recovery.finishAutoLogin(recoveryToken)
                        // 与成功分支对齐：无条件复位。此前依赖 busy.isBlank() 才复位，
                        // 而调用方（recoverExpiredModule/startStartupSessionRecovery）进入前
                        // 已把 busy 置为非空，accept 校验不通过时 flag 永不复位 → 首页按钮死锁。
                        accountTransitionInProgress = false
                    }
                } }
        }
        return true
    }

    fun startStartupSessionRecovery() {
        if (accountTransitionInProgress) return
        val recovery = ProcessSessionRecovery.coordinator
        val probeToken = recovery.tryStartProbe(SystemClock.elapsedRealtime())
        if (probeToken == null) {
            startupSessionReady = true
            if (busy == "正在检查登录状态") busy = ""
            return
        }
        val capturedCookie = cookieHeader
        val capturedPolicy = settings.autoLoginPolicy
        val capturedUsername = username
        val capturedPassword = password
        val credentialsPresent = capturedUsername.isNotBlank() && capturedPassword.isNotBlank()
        if (busy.isBlank()) busy = "正在检查登录状态"
        fun complete(action: StartupSessionAction): Boolean {
            if (!recovery.accepts(probeToken) || !sessionEpoch.isOwnerActive(sessionOwner) ||
                cookieHeader != capturedCookie || settings.autoLoginPolicy != capturedPolicy ||
                username != capturedUsername || password != capturedPassword
            ) return false
            when (action) {
                StartupSessionAction.KEEP_SESSION -> {
                    startupSessionReady = true
                    if (settings.monitorDesired) {
                        activity.startForegroundService(Intent(activity, RollcallMonitorService::class.java))
                        monitorRunning = true
                    }
                    if (busy == "正在检查登录状态") busy = ""
                }
                StartupSessionAction.SHOW_UNKNOWN -> {
                    pendingModuleRefresh.clear()
                    startupSessionReady = true
                    if (busy == "正在检查登录状态") busy = ""
                    show("暂时无法验证登录状态，已保留缓存")
                }
                StartupSessionAction.AUTO_LOGIN -> {
                    startupSessionReady = false
                    sessionEpoch.invalidate(sessionOwner)
                    invalidateMainSessionUi()
                    if (busy.isBlank() || busy == "正在检查登录状态") busy = "会话已过期，正在安全重登"
                    if (!startStartupAutoLogin()) {
                        settings.markAutoLoginFailed()
                        if (busy == "会话已过期，正在安全重登") busy = ""
                        show("自动登录未重复执行，请手动登录")
                    }
                }
                StartupSessionAction.STAY_LOGGED_OUT -> {
                    startupSessionReady = false
                    pendingModuleRefresh.clear()
                    if (capturedCookie.isNotBlank()) settings.clearSession()
                    clearLoggedOutUi()
                }
            }
            return true
        }
        if (capturedCookie.isBlank()) {
            if (complete(decideBlankCookieStartupAction(capturedPolicy, credentialsPresent))) {
                recovery.finishProbe(probeToken, SystemClock.elapsedRealtime())
            } else recovery.cancelProbe(probeToken)
            return
        }
        workScope.launch(Dispatchers.IO) {
            val health = SessionHealthProbe().check(capturedCookie)
            withContext(Dispatchers.Main) {
                if (complete(decideStartupSessionAction(health, capturedPolicy, credentialsPresent))) {
                    recovery.finishProbe(probeToken, SystemClock.elapsedRealtime())
                } else recovery.cancelProbe(probeToken)
            }
        }
    }

    fun recoverExpiredModule(retry: ModuleReadRetry) {
        // 用户正在首页手动登录时绝不叠加自动续登：两条路径各自有单飞去重，
        // 但互不知晓对方，同时发起会重复打身份域登录接口（CAS 风控红线）。
        if (accountTransitionInProgress) return
        if (settings.autoLoginPolicy != AutoLoginPolicy.ENABLED || username.isBlank() || password.isBlank()) {
            show("登录已过期，请手动登录")
            return
        }
        // 课表走教务 CAS 身份域，而统一自动恢复走 TronClass（c-identity）域——
        // 对课表做 TronClass 登录无效，只会白白多打一次登录（风控隐患）。
        // 课表恢复直接刷新即可：fetchScheduleWithNetworkRetry 内部会经
        // ensureScheduleSession（用 settings.scoreCookieHeader）自动补 CAS 会话。
        // 注意：此处不能调用 invalidateMainSessionUi()——它会清空内存 cookieHeader，
        // 导致 refreshSchedule 的 requireLogin() 直接拦截，恢复流程根本跑不到。
        if (retry.request == ModuleReadRequest.SCHEDULE) {
            startupSessionReady = false
            sessionEpoch.invalidate(sessionOwner)
            // 恢复课表会经 ensureScheduleSession 走 CAS(ids/jw 域)登录：
            // 置位 transition 禁止首页手动登录/退出与之并发（双身份域同时打登录 = 风控红线）。
            // refreshSchedule 完成/失败回调里统一复位。
            accountTransitionInProgress = true
            if (!schedule.refresh(alreadyRetriedAfterRecovery = true)) {
                accountTransitionInProgress = false
            }
            return
        }
        startupSessionReady = false
        sessionEpoch.invalidate(sessionOwner)
        invalidateMainSessionUi()
        if (busy.isBlank()) busy = "会话已过期，正在安全重登"
        val started = startStartupAutoLogin(
            onAcceptedSuccess = {
                when (retry.request) {
                    ModuleReadRequest.ROLLCALL -> rollcall.refresh(alreadyRetriedAfterRecovery = true)
                    ModuleReadRequest.COURSES -> refreshCourses(alreadyRetriedAfterRecovery = true)
                    ModuleReadRequest.COURSEWARE -> retry.course?.let { course ->
                        courseware.load(
                            course = course,
                            forceRefresh = true,
                            alreadyRetriedAfterRecovery = true,
                        )
                    }
                    ModuleReadRequest.SCHEDULE -> schedule.refresh(alreadyRetriedAfterRecovery = true)
                }
            },
            preserveCachedDataOnFailure = true,
        )
        if (!started) {
            settings.markAutoLoginFailed()
            if (busy == "会话已过期，正在安全重登") busy = ""
            show("自动登录未重复执行，请手动登录")
        }
    }

    fun dispatchModuleRefresh(module: RefreshModule) {
        val now = System.currentTimeMillis()
        val updatedAt = when (module) {
            RefreshModule.ROLLCALL -> rollcall.updatedAtMillis
            RefreshModule.SCORES -> scores.updatedAtMillis
            RefreshModule.COURSES -> academicCache.coursesUpdatedAtMillis
            RefreshModule.NONE -> return
        }
        if (!moduleRefreshTracker.shouldRefresh(module, updatedAt, now)) return
        when (module) {
            RefreshModule.ROLLCALL -> rollcall.refresh()
            RefreshModule.SCORES -> scores.refresh()
            RefreshModule.COURSES -> refreshCourses()
            RefreshModule.NONE -> Unit
        }
    }

    fun onModuleEntered(targetPage: String) {
        page = targetPage
        toast = ""
        val module = refreshModuleForPage(targetPage)
        pendingModuleRefresh.enter(module, startupSessionReady)?.let(::dispatchModuleRefresh)
    }

    LaunchedEffect(startupStartGeneration) {
        if (startupStartGeneration == 0) return@LaunchedEffect
        startStartupSessionRecovery()
    }

    LaunchedEffect(startupSessionReady) {
        if (startupSessionReady) pendingModuleRefresh.release()?.let(::dispatchModuleRefresh)
        // 启动时若有缓存课表，同步一次桌面 Widget（不依赖网络刷新）
        val cached = schedule.cache
        if (startupSessionReady && cached.entries.isNotEmpty()) {
            syncScheduleWidget(
                activity,
                cached.entries,
                cached.termCode,
                inferredCalendar = cached.inferredCalendars[cached.termCode],
                manualWeek = settings.manualAcademicWeek(cached.termCode),
            )
        }
    }

    LaunchedEffect(pendingSessionRetry) {
        val retry = pendingSessionRetry ?: return@LaunchedEffect
        pendingSessionRetry = null
        recoverExpiredModule(retry)
    }

    val downloadingCount = remember(courseware.coursewareItems) {
        courseware.coursewareItems.count { it.downloadStatus == COURSEWARE_STATUS_DOWNLOADING }
    }

    MainScreen(
        activity = activity,
        workScope = workScope,
        page = page,
        openedEventId = openedEventId,
        loggedIn = loggedIn,
        notificationSettings = notificationSettings,
        onNotificationSettingsSaved = { notificationSettings = it },
        downloadingCount = downloadingCount,
        busy = busy,
        setBusy = { busy = it },
        toast = toast,
        username = username,
        password = password,
        setUsername = { username = it },
        setPassword = { password = it },
        accountTransitionInProgress = accountTransitionInProgress,
        setAccountTransitionInProgress = { accountTransitionInProgress = it },
        monitorTransitionInProgress = monitorTransitionInProgress,
        setMonitorTransitionInProgress = { monitorTransitionInProgress = it },
        monitorRunning = monitorRunning,
        setMonitorRunning = { monitorRunning = it },
        monitorConsecutiveFailures = monitorConsecutiveFailures,
        setMonitorConsecutiveFailures = { monitorConsecutiveFailures = it },
        monitorLastCheckMillis = monitorLastCheckMillis,
        setMonitorLastCheckMillis = { monitorLastCheckMillis = it },
        monitorLastError = monitorLastError,
        setMonitorLastError = { monitorLastError = it },
        monitorTransitionId = monitorTransitionId,
        setMonitorTransitionId = { monitorTransitionId = it },
        busyNow = { busy },
        monitorTransitionInProgressNow = { monitorTransitionInProgress },
        monitorTransitionIdNow = { monitorTransitionId },
        rollcallSettings = rollcallSettings,
        setRollcallSettings = { rollcallSettings = it },
        themeMode = themeMode,
        setThemeMode = { themeMode = it },
        widgetGuideOpen = widgetGuideOpen,
        setWidgetGuideOpen = { widgetGuideOpen = it },
        widgetPermissionGuideOpen = widgetPermissionGuideOpen,
        setWidgetPermissionGuideOpen = { widgetPermissionGuideOpen = it },
        courses = courses,
        setCourses = { courses = it },
        selectedCourse = selectedCourse,
        setSelectedCourseId = { selectedCourseId = it },
        coursesLoading = coursesLoading,
        setCoursesLoading = { coursesLoading = it },
        coursesRefreshError = coursesRefreshError,
        setCoursesRefreshError = { coursesRefreshError = it },
        coursesUpdatedAtMillis = academicCache.coursesUpdatedAtMillis,
        coursewareUpdatedAtMillis = selectedCourse?.id
            ?.let { academicCache.coursewareUpdatedAtMillis[it] }
            ?: 0L,
        academicCache = academicCache,
        setAcademicCache = { academicCache = it },
        examReminder = ExamReminderSettings(
            enabled = settings.examReminderEnabled,
            advanceMinutes = settings.examReminderAdvanceMinutes,
            fullScreenEnabled = settings.examReminderFullScreen,
        ),
        onExamReminderChanged = { reminder ->
            settings.examReminderEnabled = reminder.enabled
            settings.examReminderAdvanceMinutes = reminder.advanceMinutes
            settings.examReminderFullScreen = reminder.fullScreenEnabled
            exam.rescheduleReminders()
            show("考试提醒设置已保存")
            // Android 13+ 通知默认关闭：开启提醒时主动请求通知权限，
            // 否则闹钟触发但通知不显示，用户会以为提醒失效。
            if (reminder.enabled) requestNotificationPermissionIfNeeded()
        },
        onOpenExamAlarmSettings = {
            activity.startActivity(ExamReminder.exactAlarmSettingsIntent(activity))
        },
        onOpenFullScreenSettings = {
            activity.startActivity(ExamReminder.fullScreenSettingsIntent(activity))
        },
        rollcall = rollcall,
        scores = scores,
        exam = exam,
        schedule = schedule,
        courseware = courseware,
        settings = settings,
        sessionEpoch = sessionEpoch,
        sessionOwner = sessionOwner,
        pendingModuleRefresh = pendingModuleRefresh,
        setPendingSessionRetry = { pendingSessionRetry = it },
        setCookieHeader = { cookieHeader = it },
        setLoggedIn = { loggedIn = it },
        setStartupSessionReady = { startupSessionReady = it },
        requireLogin = ::requireLogin,
        show = ::show,
        onModuleEntered = ::onModuleEntered,
        testNotifications = ::testNotifications,
        onRefreshCourses = { refreshCourses() },
        onOpenBackgroundSettings = ::openBackgroundSettings,
        clearLoggedOutUi = ::clearLoggedOutUi,
        refreshMonitorHealth = ::refreshMonitorHealth,
        requestNotificationPermissionIfNeeded = ::requestNotificationPermissionIfNeeded,
    )
}
