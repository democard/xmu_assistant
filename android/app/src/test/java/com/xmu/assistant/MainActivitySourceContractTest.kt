package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySourceContractTest {
    @Test
    fun `startup contains no academic business prefetch path`() {
        val source = mainActivitySource()

        listOf(
            "launchAcademicPrefetch",
            "plannedAcademicPrefetches",
            "unacceptedAcademicPrefetches",
            "AcademicPrefetchOperation",
            "AcademicFreshnessSnapshot",
            "autoRefreshedPages",
            "LaunchedEffect(loggedIn, cookieHeader)",
        ).forEach { removed -> assertTrue("startup prefetch symbol must be removed: $removed", removed !in source) }
    }

    @Test
    fun `all navigation uses one cache first module entry handler`() {
        val source = mainActivitySource()
        val handler = source
            .substringAfter("fun dispatchModuleRefresh(module: RefreshModule)", missingDelimiterValue = "")
            .substringBefore("val downloadingCount")

        assertTrue("module entry handler was not found", handler.isNotBlank())
        assertTrue("handler must show the selected page first", "page = targetPage" in handler)
        assertTrue("handler must clear the prior toast", "toast = \"\"" in handler)
        assertTrue("handler must map the selected page", "refreshModuleForPage(targetPage)" in handler)
        assertTrue("rollcall entry must refresh only rollcall", "RefreshModule.ROLLCALL -> rollcall.refresh()" in handler)
        assertTrue("score entry must use the user-entered score flow", "RefreshModule.SCORES -> scores.refresh()" in handler)
        assertTrue("course entry must refresh only courses", "RefreshModule.COURSES -> refreshCourses()" in handler)
        assertTrue("schedule must never auto-refresh on tab entry", "RefreshModule.SCHEDULE" !in handler)
        assertTrue("non-business entry must make no request", "RefreshModule.NONE -> Unit" in handler)
        assertTrue("top tabs must use the unified handler", "onSelected = onModuleEntered" in mainScreenSource())
        assertTrue("tutorial navigation must use the unified handler", "TutorialPage(pageScrollState, onModuleEntered)" in mainScreenSource())
    }

    @Test
    fun `schedule follows cache session and recovery contracts`() {
        val source = mainActivitySource()
        val schedule = scheduleSectionSource()
        val refreshBlock = schedule
            .substringAfter("fun refresh(", missingDelimiterValue = "")
            .substringBefore("fun clearAll()")
        val logoutBlock = source
            .substringAfter("fun clearLoggedOutUi()", missingDelimiterValue = "")
            .substringBefore("fun invalidateMainSessionUi()")
        val recoveryBlock = source
            .substringAfter("fun recoverExpiredModule(", missingDelimiterValue = "")
            .substringBefore("fun dispatchModuleRefresh(")

        assertTrue("schedule holder must exist", "internal class ScheduleSectionState(" in schedule)
        assertTrue("schedule refresh must snapshot the active session", "sessionEpoch.snapshot(sessionOwner, cookieHeader())" in refreshBlock)
        assertTrue("schedule refresh must reject stale completions", "sessionEpoch.accepts(session, cookieHeader(), loggedIn())" in refreshBlock)
        assertTrue("schedule refresh must persist its cache", "saveScheduleSnapshotToFile(activity, persistSnapshot)" in refreshBlock)
        assertTrue("logout must clear the schedule section", "schedule.clearAll()" in logoutBlock)
        val scheduleClear = schedule.substringAfter("fun clearAll()", missingDelimiterValue = "")
        assertTrue("schedule clear must reset rows", "entries = emptyList()" in scheduleClear)
        assertTrue("schedule clear must reset the timestamp", "updatedAtMillis = 0L" in scheduleClear)
        assertTrue("schedule must participate in one-shot recovery", "schedule.refresh(alreadyRetriedAfterRecovery = true)" in recoveryBlock)
        // SCHEDULE 恢复走 CAS 身份域：必须保留内存 cookieHeader（invalidateMainSessionUi 会清空它，
        // 导致 schedule.refresh 的 requireLogin 拦截，恢复失效）。断言 SCHEDULE 分支内无 invalidate 调用。
        val scheduleRecoveryBranch = recoveryBlock
            .substringAfter("if (retry.request == ModuleReadRequest.SCHEDULE) {", missingDelimiterValue = "")
            .substringBefore("schedule.refresh(alreadyRetriedAfterRecovery = true)", missingDelimiterValue = "")
        assertTrue(
            "schedule recovery must keep the in-memory cookie",
            scheduleRecoveryBranch.isNotBlank() && "invalidateMainSessionUi()" !in scheduleRecoveryBranch,
        )
    }

    @Test
    fun `module entry tracking follows foreground lifecycle and startup readiness`() {
        val source = mainActivitySource()

        assertTrue("ON_START must begin a foreground refresh session", "moduleRefreshTracker.beginForeground()" in source)
        assertTrue("ON_STOP must end the foreground refresh session", "moduleRefreshTracker.endForeground()" in source)
        assertTrue("startup must defer only the latest selected module", "pendingModuleRefresh.enter(module, startupSessionReady)" in source)
        assertTrue("accepted startup must release pending module work", "pendingModuleRefresh.release()" in source)
        assertTrue("logout must clear pending module work", "pendingModuleRefresh.clear()" in source)
    }

    @Test
    fun `automatic recovery preserves cached module data`() {
        val source = mainActivitySource()
        val startupBlock = source.substringAfter("fun startStartupSessionRecovery()").substringBefore("fun recoverExpiredModule(")
        val expiredAction = startupBlock.substringAfter("StartupSessionAction.AUTO_LOGIN -> {").substringBefore("StartupSessionAction.STAY_LOGGED_OUT")
        val moduleRecovery = source.substringAfter("fun recoverExpiredModule(").substringBefore("fun dispatchModuleRefresh(")

        assertTrue("startup recovery must keep cached page data", "clearLoggedOutUi()" !in expiredAction)
        assertTrue("startup recovery must invalidate only the main session UI", "invalidateMainSessionUi()" in expiredAction)
        assertTrue(
            "module recovery failure must preserve already visible cached data",
            "preserveCachedDataOnFailure = true" in moduleRecovery,
        )
    }

    @Test
    fun `automatic login never starts a second authentication attempt`() {
        val source = mainActivitySource()
        val startupBlock = source.substringAfter("fun startStartupSessionRecovery()").substringBefore("fun recoverExpiredModule(")
        val autoLoginBlock = source.substringAfter("fun startStartupAutoLogin(").substringBefore("fun startStartupSessionRecovery()")

        assertTrue("an active account transition must suppress another startup flow", "if (accountTransitionInProgress) return" in startupBlock)
        assertTrue("auto-login admission failure must be observable", "): Boolean {" in autoLoginBlock)
        assertTrue("a denied single-flight token must not invoke login", "recovery.tryStartAutoLogin() ?: return false" in autoLoginBlock)
    }

    @Test
    fun `course selection remains cache first and manual refresh remains forced`() {
        val source = mainActivitySource()
        val courseware = coursewareSectionSource()
        val loadBlock = courseware
            .substringAfter("fun load(", missingDelimiterValue = "")
            .substringBefore("fun downloadSelected")

        assertTrue("courseware holder must exist", "internal class CoursewareSectionState(" in courseware)
        assertTrue(
            "cached courseware must be assigned before freshness and request checks",
            loadBlock.indexOf("coursewareItems = academicCache().coursewareByCourse[course.id].orEmpty()") <
                loadBlock.indexOf("if (!forceRefresh"),
        )
        assertTrue("missing cache keys must refresh", "academicCache().coursewareByCourse.containsKey(course.id)" in loadBlock)
        assertTrue("manual courseware refresh must force", "courseware.load(it, forceRefresh = true)" in mainScreenSource())
    }

    @Test
    fun `course selection reveals its courseware section without another refresh`() {
        val source = pagesSource()
        val page = source
            .substringAfter("fun CoursewarePage(", missingDelimiterValue = "")
            .substringBefore("fun NotificationSettingsPage(")
        val selection = page
            .substringAfter("fun selectCourseAndReveal(course: CourseSummary)", missingDelimiterValue = "")
            // 切片下界跟随源码字面量：课件页文案资源化后 SectionCard 的首参改为
            // stringResource(...)，此处同步更新为新的源码文本。切片语义（selectCourse
            // AndReveal 起、LazyColumn 正文前）与原先逐字一致，防护面不变。
            .substringBefore("SectionCard(stringResource(R.string.courseware_page_title))")

        assertTrue("courseware page was not found", page.isNotBlank())
        assertTrue("selection must keep the existing cache-first loader", "onCourseSelected(course)" in selection)
        assertTrue("every click must create a new reveal request", "coursewareRevealRequestId += 1" in selection)
        // LazyColumn 懒加载下 BringIntoViewRequester 对未组合 item 静默失效，必须用 LazyListState 按 index 滚动。
        assertTrue("the courseware list must own a lazy list state", "rememberSaveable(saver = LazyListState.Saver)" in page)
        assertTrue("the reveal effect must scroll by header item index", "animateScrollToItem(coursewareHeaderItemIndex)" in page)
        assertTrue("the reveal must wait for the header item to be accepted", "snapshotFlow { coursewareListState.layoutInfo.totalItemsCount }" in page)
        assertTrue("revealing must not force a second courseware refresh", "onRefreshCourseware" !in selection)
        assertTrue("selected course buttons must use the shared reveal handler", page.split("selectCourseAndReveal(course)").size - 1 >= 2)
        // 课程列表必须是独立 LazyColumn item（真懒加载），不能退化成整块 forEach 一次性组合
        assertTrue("course list must be lazy items", "items(count = filteredCourses.size)" in page)
        assertTrue("course list must not be a single forEach block", "filteredCourses.forEach" !in page)
    }

    @Test
    fun `list pages retain scroll position across rotation`() {
        val source = pagesSource()
        // 三处 LazyColumn 页（签到/成绩/课件）都必须用 saveable 的 LazyListState，转屏不丢滚动位置
        val scrollStateCount = Regex("rememberSaveable\\(saver = LazyListState\\.Saver\\)").findAll(source).count()
        assertTrue("rollcall, score and courseware pages must use saveable list state", scrollStateCount >= 3)
        // SchedulePage 的周/日/视图/详情选择必须转屏可恢复（rememberSaveable）
        val scheduleBlock = source
            .substringAfter("fun SchedulePage(", missingDelimiterValue = "")
            .substringBefore("private fun ScheduleWeekNavigator(")
        assertTrue("schedule page must save selected week", "rememberSaveable(termCode) { mutableIntStateOf(defaultWeek) }" in scheduleBlock)
        // 周/日选择的 key 只含 termCode：defaultWeek 变化（跨午夜/缓存异步就位）不得抢走
        // 用户手动选择的周次；跟随默认周由 LaunchedEffect(defaultWeek) 显式处理
        assertTrue("default week follow must be an explicit effect", "LaunchedEffect(defaultWeek)" in scheduleBlock)
        assertTrue("schedule page must save view mode", "viewModeName" in scheduleBlock)
        // 视图切换必须写 State（viewModeName）而不是局部变量：否则点"日程"不触发重组（回归 7b1997b 修复的 bug）
        assertTrue("view mode must be derived from saved state", "val viewMode: ScheduleViewMode" in scheduleBlock && "firstOrNull { it.name == viewModeName }" in scheduleBlock)
        assertTrue("mode toggle must write the saved state", "onModeChanged = { viewModeName = it.name }" in scheduleBlock)
        assertTrue("schedule page must save selected course", "scheduleGroupListSaver" in scheduleBlock)
    }

    @Test
    fun `top tabs and option rows are theme aware with accessible touch targets`() {
        val ui = uiComponentsSource()
        assertTrue("active tab must use the theme selected color", "themeSelectedTab()" in ui)
        assertTrue("tab border must not be a hardcoded light blue", "Color(0xFFD6E4F2)" !in ui)
        assertTrue("tabs must meet the 48dp touch target", "defaultMinSize(minHeight = 48.dp)" in ui)
        assertTrue("course cards must be toggleable as a whole row", ".toggleable(" in ui)
    }

    @Test
    fun `startup recovery is a throttled daemon probe with no business refreshes`() {
        val source = mainActivitySource()
        val startupBlock = source
            .substringAfter("fun startStartupSessionRecovery()", missingDelimiterValue = "")
            .substringBefore("fun recoverExpiredModule(")

        assertTrue("startup recovery block was not found", startupBlock.isNotBlank())
        assertTrue("startup must use the process-wide recovery coordinator", "ProcessSessionRecovery.coordinator" in startupBlock)
        assertTrue("startup must use elapsed realtime", "tryStartProbe(SystemClock.elapsedRealtime())" in startupBlock)
        assertTrue("startup must be triggered from ON_START only", "LaunchedEffect(startupStartGeneration)" in source)
        assertTrue("startup trigger must ignore its initial composition", "if (startupStartGeneration == 0) return@LaunchedEffect" in source)
        assertTrue("probe must run on the composition work scope", "workScope.launch(Dispatchers.IO)" in startupBlock)
        assertTrue("blank cookies must bypass transport", "if (capturedCookie.isBlank())" in startupBlock)
        assertTrue("unknown result must not login", "StartupSessionAction.SHOW_UNKNOWN -> {" in startupBlock)
        listOf("refreshRollcalls(", "refreshScores(", "refreshCourses(", "loadCourseware(").forEach { forbidden ->
            assertTrue("startup must not call $forbidden", forbidden !in startupBlock)
        }
        assertTrue("expired no-relogin path must clear persisted stale cookies", "if (capturedCookie.isNotBlank()) settings.clearSession()" in startupBlock)
        assertTrue("auto login must invalidate epoch but preserve settings before login", "sessionEpoch.invalidate(sessionOwner)" in startupBlock)
    }

    @Test
    fun `startup owns monitor launch only after a valid or recovered session`() {
        val source = mainScreenPlusActionsSource()
        val createBlock = mainActivitySource().substringAfter("override fun onCreate").substringBefore("override fun onNewIntent")
        val startupBlock = mainActivitySource()
            .substringAfter("fun startStartupSessionRecovery()").substringBefore("fun recoverExpiredModule(")

        assertTrue("onCreate must not start monitor before probing", "startForegroundService" !in createBlock)
        assertTrue("valid startup may start desired monitor", "StartupSessionAction.KEEP_SESSION -> {" in startupBlock)
        assertTrue("auto-login failure must require all stale guards", "sessionEpoch.acceptsLoginAttempt(loginAttempt, username, password)" in source)
    }

    @Test
    fun `manual transitions invalidate recovery and set explicit policy`() {
        val loginBlock = homeActionBlock(
            "onLogin = {" to "onLogout = {",
            "fun onLogin(" to "fun onLogout(",
        )
        val logoutBlock = homeActionBlock(
            "onLogout = {" to "onStartMonitor = {",
            "fun onLogout(" to "fun onStopMonitor(",
        )

        assertTrue("login block was not found", loginBlock.isNotBlank())
        assertTrue("logout block was not found", logoutBlock.isNotBlank())
        assertTrue("manual login must invalidate stale recovery", "ProcessSessionRecovery.coordinator.invalidate()" in loginBlock)
        assertTrue("manual success enables auto login", "settings.markLoginSucceeded()" in loginBlock)
        assertTrue("logout invalidates stale recovery", "ProcessSessionRecovery.coordinator.invalidate()" in logoutBlock)
        assertTrue("logout stores the explicit logged-out policy", "settings.markUserLoggedOut()" in logoutBlock)
        assertTrue(
            "logout policy precedes session clearing",
            logoutBlock.indexOf("settings.markUserLoggedOut()") < logoutBlock.indexOf("settings.clearSession()"),
        )
    }

    @Test
    fun `logout clears every page refresh error`() {
        val source = mainActivitySource()
        val logoutBlock = homeActionBlock(
            "onLogout = {" to "onStartMonitor = {",
            "fun onLogout(" to "fun onStopMonitor(",
        )
        val clearUiBlock = source
            .substringAfter("fun clearLoggedOutUi()", missingDelimiterValue = "")
            .substringBefore("fun refreshMonitorHealth()")

        assertTrue("onLogout block was not found", logoutBlock.isNotBlank())
        assertTrue("onLogout must clear its shared UI state", "clearLoggedOutUi()" in logoutBlock)
        // 分块后的登出清理：各模块状态由各自的 SectionState.clearAll() 复位（与拆分前逐字对齐），
        // 组合侧只保留课程（refreshCourses 未拆分）与持久化清理。
        listOf(
            "rollcall.clearAll()",
            "scores.clearAll()",
            "schedule.clearAll()",
            "courseware.clearAll()",
            "coursesRefreshError = \"\"",
        ).forEach { reset ->
            assertTrue("logout UI state must contain `$reset`", reset in clearUiBlock)
        }
        // 各分块的 clearAll 必须真的清掉错误状态（防回退成空壳委托）
        val rollcallClear = rollcallSectionSource().substringAfter("fun clearAll()", missingDelimiterValue = "")
        assertTrue("rollcall clear must reset its refresh error", "refreshError = \"\"" in rollcallClear)
        val scoresClear = scoresSectionSource().substringAfter("fun clearAll()", missingDelimiterValue = "")
        assertTrue("score clear must reset its refresh error", "refreshError = \"\"" in scoresClear)
        val coursewareClear = coursewareSectionSource().substringAfter("fun clearAll()", missingDelimiterValue = "")
        assertTrue("courseware clear must reset per-course errors", "refreshErrors = emptyMap()" in coursewareClear)
    }

    @Test
    fun `logout preserves the populated credentials while ending its session`() {
        val source = mainActivitySource()
        val logoutBlock = homeActionBlock(
            "onLogout = {" to "onStartMonitor = {",
            "fun onLogout(" to "fun onStopMonitor(",
        )

        assertTrue("onLogout block was not found", logoutBlock.isNotBlank())
        assertTrue("logout must immediately invalidate active monitor work", "RollcallMonitorService.requestInvalidateActiveRun()" in logoutBlock)
        assertTrue(
            "logout must invalidate monitor work before clearing session state",
            logoutBlock.indexOf("RollcallMonitorService.requestInvalidateActiveRun()") < logoutBlock.indexOf("settings.clearSession()"),
        )
        assertTrue("logout must wait for old side effects off the UI thread", "RollcallMonitorService.awaitActiveRunQuiescence()" in logoutBlock)
        assertTrue(
            "logout must repeat session cleanup after the barrier so admitted health writes cannot survive",
            "settings.clearSession()" in logoutBlock.substringAfter("RollcallMonitorService.awaitActiveRunQuiescence()"),
        )
        assertTrue("normal logout must clear session-only state", "settings.clearSession()" in logoutBlock)
        assertTrue("normal logout must keep the username field populated", "username = \"\"" !in logoutBlock)
        assertTrue("normal logout must keep the password field populated", "password = \"\"" !in logoutBlock)
        assertTrue("normal logout must clear its in-memory session state", "clearLoggedOutUi()" in logoutBlock)
        assertTrue(
            "logout completion must not erase another operation's busy state",
            "if (busyNow() == BusyStates.LOGGING_OUT) setBusy(BusyStates.IDLE)" in logoutBlock,
        )

        val clearUiBlock = source
            .substringAfter("fun clearLoggedOutUi()", missingDelimiterValue = "")
            .substringBefore("fun refreshMonitorHealth()")
        assertTrue("logout UI state helper was not found", clearUiBlock.isNotBlank())
        assertTrue("logout must mark the app as logged out", "loggedIn = false" in clearUiBlock)
        assertTrue("logout must stop the monitor service", "activity.stopService" in clearUiBlock)
        assertTrue("logout helper must preserve username", "username = \"\"" !in clearUiBlock)
        assertTrue("logout helper must preserve password", "password = \"\"" !in clearUiBlock)
    }

    @Test
    fun `monitor stop invalidates immediately and waits away from the UI callback`() {
        val source = mainActivitySource()
        val stopBlock = homeActionBlock(
            "onStopMonitor = {" to "onAutoChanged = {",
            "fun onStopMonitor(" to "\n}",
        )

        assertTrue("monitor stop block was not found", stopBlock.isNotBlank())
        assertTrue("monitor stop must request invalidation", "RollcallMonitorService.requestInvalidateActiveRun()" in stopBlock)
        assertTrue("monitor stop must wait for quiescence", "RollcallMonitorService.awaitActiveRunQuiescence()" in stopBlock)
        assertTrue(
            "monitor stop must enter a background worker before waiting",
            stopBlock.indexOf("workScope.launch(Dispatchers.IO)") < stopBlock.indexOf("RollcallMonitorService.awaitActiveRunQuiescence()"),
        )
        assertTrue(
            "monitor stop completion must work while logged out",
            "sessionEpoch.isOwnerActive(sessionOwner)" in stopBlock && "monitorTransitionInProgress" in stopBlock,
        )
        assertTrue(
            "monitor stop completion must not require a cookie-backed login",
            "sessionEpoch.accepts(transitionSession, cookieHeader, loggedIn)" !in stopBlock,
        )
        assertTrue("each stop must capture a monotonic transition identity", "val stopTransitionId = nextTransitionId" in stopBlock)
        assertTrue(
            "an old stop completion must not clear a newer stop",
            "monitorTransitionIdNow() != stopTransitionId" in stopBlock,
        )
        assertTrue(
            "stop completion must not erase login or other busy state",
            "if (busyNow() == BusyStates.PAUSING_MONITOR) setBusy(BusyStates.IDLE)" in stopBlock,
        )

        val clearUiBlock = source
            .substringAfter("fun clearLoggedOutUi()", missingDelimiterValue = "")
            .substringBefore("fun refreshMonitorHealth()")
        assertTrue("logout must invalidate an in-flight stop identity", "monitorTransitionId += 1" in clearUiBlock)
    }

    @Test
    fun `home page does not offer automatic saved credential deletion`() {
        // 出层后协调器也在 HomePage 编排面内：合并扫描防「搬走即绕过」。
        val source = mainScreenPlusActionsSource()
        // 仅检查凭证删除相关符号（HomePage 编排区不得提供删除保存账号的入口）。
        // 注意：不得用 "AlertDialog(" 做全局断言——widget 引导/权限引导弹窗合法使用 AlertDialog。
        assertTrue("HomePage must not add a credential delete callback", "onDeleteSavedAccount" !in source)
        assertTrue("HomePage must not add a delete-account action", "删除保存的账号" !in source)
    }

    @Test
    fun `score refresh cannot restore state after logout`() {
        val source = scoresSectionSource()
        assertTrue(
            "score refresh must require the current epoch-backed login before writing a result",
            "sessionEpoch.accepts(session, cookieHeader(), loggedIn())" in source,
        )
    }

    @Test
    fun `all read only refreshes use the session epoch`() {
        val sources = listOf(
            mainActivitySource(),
            homeActionsSource(),
            rollcallSectionSource(),
            scoresSectionSource(),
            scheduleSectionSource(),
            coursewareSectionSource(),
        ).joinToString("\n")
        assertTrue("the app must use the process session epoch", "ProcessSessionEpoch.instance" in sources)
        assertTrue("the activity composition must attach a distinct owner", "sessionEpoch.attachOwner()" in sources)
        assertTrue("disposing an old activity must conditionally detach only its owner", "sessionEpoch.detachOwner(sessionOwner)" in sources)
        assertTrue("a started or resumed owner must reactivate", "sessionEpoch.activateOwner(sessionOwner)" in sources)
        assertTrue("logout must invalidate prior work for the current owner", "sessionEpoch.invalidate(sessionOwner)" in sources)
        assertTrue(
            "rollcall, scores, courses, and courseware must each guard completion by epoch",
            Regex("sessionEpoch\\.accepts\\(session, cookieHeader, loggedIn\\)").findAll(sources).count() +
                Regex("sessionEpoch\\.accepts\\(session, cookieHeader\\(\\), loggedIn\\(\\)\\)").findAll(sources).count() >= 4,
        )
        assertTrue(
            "auto re-login must be gated on the current epoch generation",
            "mayRelogin = { sessionEpoch.isCurrent(session) }" in sources,
        )
    }

    @Test
    fun `main activity uses a single task and receives new deep link intents`() {
        val manifest = mainManifestSource()
        val source = mainActivitySource()

        assertTrue("MainActivity must use singleTask to prevent stale session UI stacks", "android:launchMode=\"singleTask\"" in manifest)
        assertTrue("singleTask activity must receive replacement intents", "override fun onNewIntent(intent: Intent)" in source)
        assertTrue("new intents must replace Activity intent state", "setIntent(intent)" in source)
        assertTrue("new deep links must update Compose-observed event state", "openedEventId = intent.data?.lastPathSegment.orEmpty()" in source)
    }

    @Test
    fun `deep link counters persist in pairs across activity recreation`() {
        val source = mainActivitySource()

        // seq 与 consumed 双计数器必须配对持久化：只持久化 consumed
        // （rememberSaveable）而让 seq 重建重置为 1，重建后守卫
        // 「seq <= consumed」会把其后 N-1 次新深链误判为回放吞掉。
        assertTrue(
            "deepLinkSeq must be restored from saved instance state",
            "deepLinkSeq = savedInstanceState?.getInt(STATE_DEEP_LINK_SEQ, 1) ?: 1" in source,
        )
        assertTrue(
            "deepLinkSeq must be saved alongside the consumed counter",
            "outState.putInt(STATE_DEEP_LINK_SEQ, deepLinkSeq)" in source,
        )
        assertTrue(
            "consumed counter must persist across recreation",
            "var consumedDeepLinkSeq by rememberSaveable { mutableIntStateOf(0) }" in source,
        )
    }

    @Test
    fun `login completion requires the captured credentials to still match`() {
        val loginBlock = homeActionBlock(
            "onLogin = {" to "onLogout = {",
            "fun onLogin(" to "fun onLogout(",
        )

        assertTrue("login block was not found", loginBlock.isNotBlank())
        assertTrue(
            "login result must atomically validate owner generation and captured credentials",
            "sessionEpoch.completeLogin(loginAttempt, username, password)" in loginBlock,
        )
        assertTrue(
            "credential validation must happen before settings persistence",
            loginBlock.indexOf("sessionEpoch.completeLogin(loginAttempt, username, password)") <
                loginBlock.indexOf("settings.username = accountUsername"),
        )
    }

    @Test
    fun `exam section lives in its own state holder`() {
        val main = mainActivitySource()
        val exam = examSectionSource()

        // 考试状态与动作必须已拆出 MainActivity（防回退成巨型组合函数）
        listOf(
            "fun silentRefresh(",
            "fun fetchExamData(",
            "fun checkExamChanges(",
            "fun refreshExam(",
            "fun rescheduleExamReminders(",
            "fun ensureExamAcademicSession(",
            "examValidTerms",
            "examSelectedTerm",
            "examManuallySelected",
            "examLoading",
            "examRefreshError",
            "examAutoUpdated",
            "examSummary",
        ).forEach { removed -> assertTrue("exam symbol must not stay in MainActivity: $removed", removed !in main) }

        // 独立分块必须承接全部考试动作与状态
        assertTrue("exam holder must exist", "internal class ExamSectionState(" in exam)
        listOf(
            "fun selectTerm(term: String)",
            "fun ensureAcademicSession(",
            "fun rescheduleReminders()",
            "fun fetchData(",
            "fun refresh()",
            "fun checkChanges(term: String)",
            "fun silentRefresh()",
            "fun clearAll()",
            "fun savedState()",
        ).forEach { action -> assertTrue("exam holder must keep `$action`", action in exam) }
        assertTrue("exam writes must be epoch-guarded", "sessionEpoch.accepts(session, cookieHeader(), loggedIn())" in exam)
        assertTrue("exam re-login must be gated on the epoch", ") { sessionEpoch.isCurrent(session) }" in exam)
        assertTrue("exam client factory must propagate the re-login gate", "mayRelogin = mayRelogin" in exam)
        assertTrue("exam refresh must share the module gate", "requestGate.tryStart(gateKey)" in exam)

        // 组合侧只保留编排点
        assertTrue("composition must build the holder through a Saver", "saver = Saver(" in mainScreenSource())
        assertTrue("composition must build the holder via rememberExamSection", "val exam = rememberExamSection(" in main)
        assertTrue("exam page entry must run the silent check", "exam.silentRefresh()" in main)
        assertTrue("logout must clear the exam section", "exam.clearAll()" in main)
        assertTrue("term switch must delegate to the holder", "onSelectTerm = exam::selectTerm" in mainScreenSource())
        assertTrue("manual refresh must delegate to the holder", "onRefresh = exam::refresh" in mainScreenSource())
        assertTrue("reminder changes must delegate to the holder", "exam.rescheduleReminders()" in main)
    }

    private fun mainActivitySource(): String {
        val relativePath = "src/main/java/com/xmu/assistant/MainActivity.kt"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "MainActivity.kt was not found from ${File(".").absolutePath}" }
            .readText()
    }

    /**
     * 登录/登出/停监控协调器（HomeActions.kt）源码，文件尚未创建时返回空串。
     * 2026-08-27 起该三个动作块出层为普通协调类；本测试的块提取改为双源扫描
     * （MainScreen.kt 的 onClick lambda 与 HomeActions.kt 的 fun onXxx 方法），
     * 出层前后同等守护（sourcesUnderTest 拼接手法）。
     */
    private fun homeActionsSource(): String =
        sectionSourceOrNull("HomeActions.kt") ?: ""

    private fun sectionSourceOrNull(fileName: String): String? {
        val relativePath = "src/main/java/com/xmu/assistant/$fileName"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)
        return sourceFile?.readText()
    }

    /** 主界面 + 协调器合并源：出层后仍需全量扫描的动作断言改用此源。 */
    private fun mainScreenPlusActionsSource(): String =
        mainScreenSource() + "\n" + homeActionsSource()

    /**
     * HomePage 动作块提取：候选标记对在两个候选源里分别切片，取最长非空结果。
     * 出层前 MainScreen.kt 的 lambda 块体远长于其它偶然匹配；出层后
     * HomeActions.kt 的方法体取代之。两个阶段都能拿到完整块。
     */
    private fun homeActionBlock(vararg markerPairs: Pair<String, String>): String {
        val sources = listOf(mainScreenSource(), homeActionsSource())
        return markerPairs.asSequence()
            .flatMap { (start, end) ->
                sources.map { source ->
                    source.substringAfter(start, missingDelimiterValue = "")
                        .substringBefore(end, missingDelimiterValue = "")
                }
            }
            .filter { it.isNotBlank() }
            .maxByOrNull { it.length }
            .orEmpty()
    }

    private fun sectionSource(fileName: String): String {
        val relativePath = "src/main/java/com/xmu/assistant/$fileName"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "$fileName was not found from ${File(".").absolutePath}" }
            .readText()
    }

    private fun rollcallSectionSource(): String = sectionSource("RollcallSectionState.kt")

    private fun scoresSectionSource(): String = sectionSource("ScoreSectionState.kt")

    private fun scheduleSectionSource(): String = sectionSource("ScheduleSectionState.kt")

    private fun coursewareSectionSource(): String = sectionSource("CoursewareSectionState.kt")

    private fun mainScreenSource(): String = sectionSource("MainScreen.kt")

    private fun pagesSource(): String {
        // 页面代码已按职责拆分到多个文件（Pages/SchedulePage/CoursewarePage）。
        // 按文件名字母序拼接，保证 CoursewarePage 在 NotificationSettingsPage 之前
        // （契约断言依赖该相对顺序）。
        val relativePaths = listOf(
            "src/main/java/com/xmu/assistant/CoursewarePage.kt",
            "src/main/java/com/xmu/assistant/Pages.kt",
            "src/main/java/com/xmu/assistant/SchedulePage.kt",
        )
        val root = File(".").absolutePath
        return relativePaths.map { path ->
            val sourceFile = sequenceOf(
                File(path),
                File("app", path),
                File("android/app", path),
            ).firstOrNull(File::isFile)
            requireNotNull(sourceFile) { "$path was not found from $root" }.readText()
        }.joinToString("\n")
    }

    private fun uiComponentsSource(): String {
        val relativePath = "src/main/java/com/xmu/assistant/UiComponents.kt"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "UiComponents.kt was not found from ${File(".").absolutePath}" }
            .readText()
    }

    private fun examSectionSource(): String {
        val relativePath = "src/main/java/com/xmu/assistant/ExamSectionState.kt"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "ExamSectionState.kt was not found from ${File(".").absolutePath}" }
            .readText()
    }

    private fun mainManifestSource(): String {
        val relativePath = "src/main/AndroidManifest.xml"
        val manifestFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(manifestFile) { "AndroidManifest.xml was not found from ${File(".").absolutePath}" }
            .readText()
    }
}
