package com.xmu.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class HomePageUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun HomeFixture(loggedIn: Boolean = true, running: Boolean = false, busy: Boolean = false,
                            settings: RollcallSettings = RollcallSettings(), start: () -> Unit = {},
                            stop: () -> Unit = {}, logout: () -> Unit = {},
                            navigate: (String) -> Unit = {}, events: List<RollcallEvent> = emptyList(),
                            rollcallLoading: Boolean = false, rollcallError: String = "",
                            updatedAt: Long = 0L,
                            scheduleEntries: List<XmuScheduleEntry> = emptyList(),
                            scheduleTermCode: String = "",
                            inferredCalendar: XmuAcademicCalendar? = null,
                            upcomingExams: List<XmuExam> = emptyList()) {
        XmuMobileTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HomePage(
                    username = "123456789", password = "", loggedIn = loggedIn, monitorRunning = running,
                    accountTransitionInProgress = false, monitorTransitionInProgress = busy,
                    monitorStatus = if (running) "运行中" else "未启动", monitorLastCheck = "10:00:00",
                    monitorFailureCount = 0, monitorLastError = "", rollcallSettings = settings,
                    onUsername = {}, onPassword = {}, onLogin = {}, onLogout = logout,
                    onStartMonitor = start, onStopMonitor = stop,
                    onOpenBackgroundSettings = {}, onNavigate = navigate,
                    currentRollcalls = events, rollcallLoading = rollcallLoading,
                    rollcallError = rollcallError, rollcallUpdatedAtMillis = updatedAt,
                    scheduleEntries = scheduleEntries, scheduleTermCode = scheduleTermCode,
                    inferredCalendar = inferredCalendar, upcomingExams = upcomingExams,
                )
            }
        }
    }

    @Test fun `home shows compact rollcall banner inside hero and clears ended events`() {
        val events = mutableStateOf(listOf(
            RollcallEvent("r1", "高等数学", "陈老师", "数字签到", "未知", numberCode = "001204"),
            RollcallEvent("r2", "数学建模", "李老师", "雷达签到", "未知", courseId = "c2"),
        ))
        composeRule.setContent { HomeFixture(events = events.value, updatedAt = 1L) }
        composeRule.onNodeWithText("进行中：高等数学 / 数字签到 等 2 项").assertExists()
        composeRule.onNodeWithText("签到码 001204").assertDoesNotExist()
        composeRule.onNodeWithText("当前签到").assertDoesNotExist()
        composeRule.onNodeWithText("未配置课程坐标").assertDoesNotExist()
        composeRule.runOnIdle { events.value = emptyList() }
        composeRule.onNodeWithText("进行中：高等数学 / 数字签到 等 2 项").assertDoesNotExist()
        composeRule.onNodeWithText("高等数学").assertDoesNotExist()
        composeRule.onNodeWithText("数学建模").assertDoesNotExist()
    }

    @Test fun `rollcall detail and monitor settings buttons retain navigation`() {
        var destination = ""
        composeRule.setContent {
            HomeFixture(
                events = listOf(RollcallEvent("r2", "数学建模", "李老师", "雷达签到", "未知", courseId = "c2")),
                navigate = { destination = it },
            )
        }
        composeRule.onNodeWithText("进行中：数学建模 / 雷达签到").assertExists().performClick()
        composeRule.runOnIdle { assertEquals("畅课签到", destination) }
        composeRule.onNodeWithText("监控设置").performClick()
        composeRule.runOnIdle {
            assertEquals("策略", destination)
        }
    }

    @Test fun `home keeps current rollcalls visible when refresh fails`() {
        composeRule.setContent {
            HomeFixture(
                events = listOf(RollcallEvent("r1", "高等数学", "陈老师", "数字签到", "未知")),
                rollcallError = "网络连接失败",
            )
        }
        composeRule.onNodeWithText("进行中：高等数学 / 数字签到").assertExists()
        composeRule.onNodeWithText("网络连接失败", substring = true).assertExists()
    }

    @Test fun `home hides private rollcalls when logged out and keeps summary while loading`() {
        val loggedIn = mutableStateOf(true)
        composeRule.setContent {
            HomeFixture(loggedIn = loggedIn.value, rollcallLoading = true,
                events = listOf(RollcallEvent("r1", "高等数学", "陈老师", "数字签到", "未知")))
        }
        composeRule.onNodeWithText("进行中：高等数学 / 数字签到").assertExists()
        composeRule.runOnIdle { loggedIn.value = false }
        composeRule.onNodeWithText("进行中：高等数学 / 数字签到").assertDoesNotExist()
        composeRule.onNodeWithText("高等数学").assertDoesNotExist()
    }

    private fun saveCurrentRender(name: String) {
        composeRule.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/rollcall-ui").apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `running monitor exposes only pause and requires confirmation before logout`() {
        var starts = 0
        var stops = 0
        var logouts = 0
        composeRule.setContent { HomeFixture(running = true, start = { starts++ }, stop = { stops++ }, logout = { logouts++ }) }
        composeRule.onNodeWithText("学号").assertDoesNotExist()
        composeRule.onNodeWithText("启动监控").assertDoesNotExist()
        composeRule.onNodeWithText("暂停监控").performClick()
        composeRule.runOnIdle { assertEquals(0, starts); assertEquals(1, stops) }
        composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(0, logouts) }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, logouts) }
        composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
        composeRule.onNodeWithText("确认退出").performClick()
        composeRule.runOnIdle { assertEquals(1, logouts) }
    }

    @Test fun `monitor transition cannot be clicked again`() {
        composeRule.setContent { HomeFixture(busy = true) }
        composeRule.onNodeWithText("正在切换…").assertIsNotEnabled()
    }

    @Test fun `missing password disables login and monitor is hidden until authenticated`() {
        composeRule.setContent { HomeFixture(loggedIn = false) }
        composeRule.onNodeWithText("登录").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("启动监控").assertDoesNotExist()
        composeRule.onNodeWithText("暂停监控").assertDoesNotExist()
    }

    @Test fun `logout confirmation survives state restoration`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { HomeFixture() }
        composeRule.onNodeWithText("退出登录").performScrollTo().performClick()
        composeRule.onNodeWithText("确认退出").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("确认退出").assertExists()
    }

    @Test fun `password visibility survives state restoration`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { HomeFixture(loggedIn = false) }
        composeRule.onNodeWithText("显示").performScrollTo().performClick()
        composeRule.onNodeWithText("隐藏").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("隐藏").assertExists()
    }

    @Test fun `home renders today schedule upcoming exam and shortcuts without auto signin`() {
        val today = java.time.LocalDate.now()
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
        val entry = XmuScheduleEntry(today.dayOfWeek.value, 1, 2, 2230, 2350, "高等数学", "学武楼 A305", "陈老师", "1-16周", "20261")
        val exam = XmuExam("e1", "概率论", today.plusDays(2).toString(), "08:30-10:30", "坤銮楼 B102", "线下", "期末考试")
        var destination = ""
        composeRule.setContent {
            HomeFixture(
                scheduleEntries = listOf(entry),
                scheduleTermCode = "20261",
                inferredCalendar = calendar,
                upcomingExams = listOf(exam),
                navigate = { destination = it },
            )
        }
        composeRule.onNodeWithText("今日课程").assertExists()
        composeRule.onNodeWithText("高等数学").assertExists()
        composeRule.onNodeWithText("近期考试").assertExists()
        composeRule.onNodeWithText("概率论").assertExists()
        composeRule.onNodeWithText("自动签到快捷开关").assertDoesNotExist()
        composeRule.onNodeWithText("一键签到").assertDoesNotExist()
        composeRule.onNodeWithText("雷达签到坐标管理").assertDoesNotExist()
        composeRule.onNodeWithText("课件").performClick()
        composeRule.runOnIdle { assertEquals("课程课件", destination) }
        composeRule.onNodeWithText("通知").performClick()
        composeRule.runOnIdle { assertEquals("通知", destination) }
    }
}
