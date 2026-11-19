package com.xmu.assistant

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1800dp")
class AndroidUiOptimizationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `maskHomeUsername handles short and standard student ids safely`() {
        assertEquals("123", maskHomeUsername("123"))
        assertEquals("12**6", maskHomeUsername("123456"))
        assertEquals("202****5678", maskHomeUsername("202612345678"))
    }

    @Test
    fun `schedule override store applies edits to entries for ICS export`() {
        val app = RuntimeEnvironment.getApplication()
        val store = ScheduleOverrideStore(app)
        store.clear()
        val base = XmuScheduleGroup(1, 1, 2, 800, 935, "数据结构", listOf("A101"), listOf("王老师"), "1-16周")
        store.save("20261", base, ScheduleCourseOverride(3, 5, 6, "坤銮楼 B207", "王老师", "2-15周"))
        val updated = store.applyToEntries(
            "20261",
            listOf(XmuScheduleEntry(1, 1, 2, 800, 935, "数据结构", "A101", "王老师", "1-16周", "20261")),
        ).single()
        assertEquals(3, updated.weekday)
        assertEquals(5, updated.startSection)
        assertEquals(6, updated.endSection)
        assertEquals("坤銮楼 B207", updated.room)
        assertEquals("2-15周", updated.weeks)
        store.clear()
    }

    @Test
    fun `home page exposes one tap sign in button and dispatches click`() {
        var clicked = 0
        composeRule.setContent {
            XmuMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HomePage(
                        username = "202612345678", password = "", loggedIn = true, monitorRunning = false,
                        accountTransitionInProgress = false, monitorTransitionInProgress = false,
                        monitorStatus = "未启动", monitorLastCheck = "10:00:00",
                        monitorFailureCount = 0, monitorLastError = "", rollcallSettings = RollcallSettings(),
                        onUsername = {}, onPassword = {}, onLogin = {}, onLogout = {},
                        onStartMonitor = {}, onStopMonitor = {}, onAutoChanged = {},
                        onOpenBackgroundSettings = {}, onNavigate = {},
                        onOneTapSignIn = { clicked++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("一键签到").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, clicked) }
    }

    @Test
    fun `rollcall status page renders 3 distinct branches and absent history badge`() {
        val app = RuntimeEnvironment.getApplication()
        val store = RadarCoordinateStore(app)
        store.placesFor("c-radar", "高等数学").forEach { store.delete(it.id) }
        val p1 = store.add("c-radar", "高等数学", "坤銮楼 B207", 24.6, 118.3, "custom", makePreferred = true)
        val p2 = store.add("c-radar", "高等数学", "学武楼 A305", 24.61, 118.31, "custom", makePreferred = false)

        val events = listOf(
            RollcallEvent("d1", "软件工程", "李老师", "数字签到", "进行中", numberCode = "3819", ownStatus = "已签"),
            RollcallEvent("r1", "高等数学", "陈老师", "雷达签到", "进行中", courseId = "c-radar"),
            RollcallEvent("q1", "大学英语", "张老师", "二维码签到", "进行中"),
        )
        val history = listOf(
            RollcallHistoryItem(
                "h1", "c1", "数据结构", "雷达签到", "11-18 10:00",
                progress = StudentRollcallProgress(64, 58, 6, 0, 5800.0 / 64.0, true),
                sortKeyMillis = null, ownStatus = STATUS_SIGNED,
            ),
            RollcallHistoryItem("h2", "c2", "操作系统", "数字签到", "11-16 08:00", null, "缺勤", numberCode = "4521"),
        )

        composeRule.setContent {
            XmuMobileTheme {
                Box(Modifier.width(390.dp).height(1800.dp)) {
                    RollcallStatusPage(
                        events = events,
                        openedEventId = "",
                        loading = false,
                        refreshError = "",
                        updatedAtMillis = 1L,
                        historyItems = history,
                        historyLoading = false,
                        historyError = "",
                        historyUpdatedAtMillis = 1L,
                        loggedIn = true,
                        monitorRunning = true,
                        coordinateStore = store,
                        onRefresh = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("监控中").assertExists()
        composeRule.onNodeWithText("签到码 3819").assertExists()
        composeRule.onNodeWithText("复制").assertExists()
        composeRule.onNodeWithText("坤銮楼 B207").assertExists()
        composeRule.onNodeWithText("学武楼 A305").assertExists()
        composeRule.onNodeWithText("（首选）", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("需扫码").assertExists()
        composeRule.onNodeWithText("最近 2 次").assertExists()
        composeRule.onNodeWithText("缺勤").assertExists()
        composeRule.onNodeWithText("!").assertExists()
        composeRule.onNodeWithText("已签").assertExists()
        composeRule.onNodeWithText("58 / 64 人").assertExists()
        store.delete(p1.id)
        store.delete(p2.id)
    }

    @Test
    fun `oneTapSignIn submits pending digital rollcall and skips QR`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val messages = mutableListOf<String>()
        var submittedPutUrls = mutableListOf<String>()

        val statusTransport = QueryHttpTransport { request ->
            when {
                request.url.endsWith("/api/radar/rollcalls") -> QueryHttpResponse(
                    code = 200,
                    url = request.url,
                    location = "",
                    body = """
                        {"rollcalls":[
                            {"rollcall_id":"101","course_id":"c1","course_title":"软件工程","created_by_name":"李老师","is_number":true,"status":"on_call"},
                            {"rollcall_id":"102","course_id":"c2","course_title":"大学英语","created_by_name":"张老师","is_number":false,"is_radar":false,"status":"on_call"}
                        ]}
                    """.trimIndent(),
                    headers = emptyMap(),
                )
                request.url.endsWith("/api/rollcall/101/student_rollcalls") -> QueryHttpResponse(
                    code = 200,
                    url = request.url,
                    location = "",
                    body = """{"number_code":"3819","student_rollcalls":[{"user_no":"20260001","status":"absent"}]}""",
                    headers = emptyMap(),
                )
                request.url.endsWith("/api/rollcall/102/student_rollcalls") -> QueryHttpResponse(
                    code = 200,
                    url = request.url,
                    location = "",
                    body = """{"student_rollcalls":[{"user_no":"20260001","status":"absent"}]}""",
                    headers = emptyMap(),
                )
                else -> QueryHttpResponse(code = 404, url = request.url, location = "", body = "{}", headers = emptyMap())
            }
        }
        val answerTransport = QueryHttpTransport { request ->
            submittedPutUrls += request.url
            QueryHttpResponse(code = 200, url = request.url, location = "", body = """{"status":"ok"}""", headers = emptyMap())
        }

        val state = RollcallSectionState(
            activity = activity,
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            show = { messages += it },
            requireLogin = { true },
            loggedIn = { true },
            cookieHeader = { "session=ok" },
            username = { "20260001" },
            coursesProvider = { emptyList() },
            setPendingSessionRetry = {},
            createEngine = { cookie ->
                RollcallEngine(cookie, statusTransport = statusTransport, answerTransport = answerTransport)
            },
        )

        assertTrue(state.oneTapSignIn())
        val deadline = System.currentTimeMillis() + 5000
        while (state.oneTapBusy && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, submittedPutUrls.size)
        assertTrue(submittedPutUrls.single().endsWith("/api/rollcall/101/answer_number_rollcall"))
        assertTrue(messages.any { "已提交 1 项" in it && "跳过 1 项二维码签到" in it })
    }

    @Test
    fun `course coordinate page opens bottom sheet picker and shows configured status`() {
        val app = RuntimeEnvironment.getApplication()
        val store = RadarCoordinateStore(app)
        store.placesFor("c-1", "高等数学").forEach { store.delete(it.id) }
        store.placesFor("c-2", "操作系统").forEach { store.delete(it.id) }
        val p1 = store.add("c-1", "高等数学", "坤銮楼 B207", 24.6, 118.3, "custom", makePreferred = true)

        val courses = listOf(
            CourseSummary("c-1", "高等数学", "2026-2027 第一学期", "2026-1"),
            CourseSummary("c-2", "操作系统", "2026-2027 第一学期", "2026-1"),
        )
        var picked = ""
        composeRule.setContent {
            XmuMobileTheme {
                CourseCoordinatePage(
                    courses = courses,
                    initialCourse = courses.first(),
                    store = store,
                    show = {},
                    onCourseSelected = { picked = it.title },
                )
            }
        }
        composeRule.onNodeWithText("换课 ▾").performClick()
        composeRule.onNodeWithText("选择课程").assertExists()
        composeRule.onNodeWithText("已设 1 个").assertExists()
        composeRule.onNodeWithText("未配置").assertExists()
        composeRule.onNodeWithText("操作系统").performClick()
        composeRule.runOnIdle { assertEquals("操作系统", picked) }
        composeRule.onNodeWithText("选择课程").assertDoesNotExist()
        store.delete(p1.id)
    }

    @Test
    fun `schedule page opens course picker modal sheet and enters edit sheet`() {
        val today = java.time.LocalDate.now()
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
        val course = XmuScheduleEntry(today.dayOfWeek.value, 1, 2, 800, 935, "数据结构与算法", "学武楼 A305", "王老师", "1-16周", "20261")
        composeRule.setContent {
            XmuMobileTheme {
                SchedulePage(
                    entries = listOf(course),
                    termCode = "20261",
                    updatedAtMillis = 0L,
                    loading = false,
                    refreshError = "",
                    loggedIn = true,
                    onRefresh = {},
                    inferredCalendar = calendar,
                    pinnedHeader = true,
                )
            }
        }
        composeRule.onNodeWithText("修改安排").performClick()
        composeRule.onNodeWithText("修改课程安排").assertExists()
        composeRule.onNodeWithText("编辑 ›").performClick()
        composeRule.onNodeWithText("保存修改").assertExists()
    }

    @Test
    fun `courseware page opens modal course sheet and selects course`() {
        val courses = listOf(
            CourseSummary("c-1", "高等数学", "2026-2027 第一学期", "2026-1"),
            CourseSummary("c-2", "线性代数", "2026-2027 第一学期", "2026-1"),
        )
        var selectedTitle = ""
        composeRule.setContent {
            XmuMobileTheme {
                CoursewarePreviewPage(
                    courses = courses,
                    selectedCourse = courses.first(),
                    coursewareItems = emptyList(),
                    selectedIds = emptySet(),
                    onRefreshCourses = {},
                    onCourseSelected = { selectedTitle = it.title },
                    onToggle = {},
                    onSelectAll = {},
                    onDownload = {},
                    onRefreshCourseware = {},
                    onOpenPlatform = {},
                    downloadProgress = "",
                    downloadLoading = false,
                    coursesLoading = false,
                    coursesRefreshError = "",
                    coursewareLoading = false,
                    coursewareRefreshError = "",
                    loggedIn = true,
                )
            }
        }
        composeRule.onNodeWithText("换课 ▾").performClick()
        composeRule.onNodeWithText("选择课程").assertExists()
        composeRule.onNodeWithText("当前").assertExists()
        composeRule.onNodeWithText("线性代数").performClick()
        composeRule.runOnIdle { assertEquals("线性代数", selectedTitle) }
        composeRule.onNodeWithText("选择课程").assertDoesNotExist()
    }
}
