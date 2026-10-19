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
                            stop: () -> Unit = {}, logout: () -> Unit = {}, autoChanged: (Boolean) -> Unit = {},
                            navigate: (String) -> Unit = {}, events: List<RollcallEvent> = emptyList(),
                            rollcallLoading: Boolean = false, rollcallError: String = "",
                            updatedAt: Long = 0L) {
        XmuMobileTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HomePage(
                    username = "123456789", password = "", loggedIn = loggedIn, monitorRunning = running,
                    accountTransitionInProgress = false, monitorTransitionInProgress = busy,
                    monitorStatus = if (running) "运行中" else "未启动", monitorLastCheck = "10:00:00",
                    monitorFailureCount = 0, monitorLastError = "", rollcallSettings = settings,
                    onUsername = {}, onPassword = {}, onLogin = {}, onLogout = logout,
                    onStartMonitor = start, onStopMonitor = stop, onAutoChanged = autoChanged,
                    onOpenBackgroundSettings = {}, onNavigate = navigate,
                    currentRollcalls = events, rollcallLoading = rollcallLoading,
                    rollcallError = rollcallError, rollcallUpdatedAtMillis = updatedAt,
                )
            }
        }
    }

    @Test fun `home uses the legacy inline summary and clears ended events`() {
        val events = mutableStateOf(listOf(
            RollcallEvent("r1", "高等数学", "陈老师", "数字签到", "未知", numberCode = "001204"),
            RollcallEvent("r2", "数学建模", "李老师", "雷达签到", "未知", courseId = "c2"),
        ))
        composeRule.setContent { HomeFixture(events = events.value, updatedAt = 1L) }
        composeRule.onNodeWithText("当前签到").performScrollTo().assertExists()
        composeRule.onNodeWithText("高等数学 / 数字签到 · 共 2 项").assertExists()
        composeRule.onNodeWithText("签到码 001204").assertDoesNotExist()
        composeRule.onNodeWithText("未配置课程坐标").assertDoesNotExist()
        composeRule.onNodeWithText("刷新签到").assertDoesNotExist()
        composeRule.onNodeWithText("详情 ›").assertDoesNotExist()
        composeRule.onNodeWithText("当前签到").performScrollTo()
        saveCurrentRender("home-current-rollcalls.png")
        composeRule.runOnIdle { events.value = emptyList() }
        composeRule.onNodeWithText("暂无进行中的签到").performScrollTo().assertExists()
        composeRule.onNodeWithText("高等数学").assertDoesNotExist()
        composeRule.onNodeWithText("数学建模").assertDoesNotExist()
    }

    @Test fun `legacy summary detail and existing coordinate button retain navigation`() {
        var destination = ""
        composeRule.setContent {
            HomeFixture(
                events = listOf(RollcallEvent("r2", "数学建模", "李老师", "雷达签到", "未知", courseId = "c2")),
                navigate = { destination = it },
            )
        }
        composeRule.onNodeWithText("数学建模 / 雷达签到").performScrollTo().assertExists()
        composeRule.onNodeWithText("查看签到详情").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("签到情况", destination) }
        composeRule.onNodeWithText("雷达签到坐标管理").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("课程坐标", destination)
        }
    }

    @Test fun `home keeps current rollcalls visible when refresh fails`() {
        composeRule.setContent {
            HomeFixture(
                events = listOf(RollcallEvent("r1", "高等数学", "陈老师", "数字签到", "未知", numberCode = "0099")),
                rollcallError = "网络连接失败",
            )
        }
        composeRule.onNodeWithText("高等数学 / 数字签到").performScrollTo().assertExists()
        composeRule.onNodeWithText("暂无进行中的签到").assertDoesNotExist()
        composeRule.onNodeWithText("网络连接失败", substring = true).performScrollTo().assertExists()
    }

    @Test fun `home hides private rollcalls when logged out and keeps summary while loading`() {
        val loggedIn = mutableStateOf(true)
        composeRule.setContent {
            HomeFixture(loggedIn = loggedIn.value, rollcallLoading = true,
                events = listOf(RollcallEvent("r1", "高等数学", "陈老师", "数字签到", "未知")))
        }
        composeRule.onNodeWithText("高等数学 / 数字签到").performScrollTo().assertExists()
        composeRule.runOnIdle { loggedIn.value = false }
        composeRule.onNodeWithText("当前签到").assertDoesNotExist()
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
        composeRule.onNodeWithText("退出登录").performClick()
        composeRule.runOnIdle { assertEquals(0, logouts) }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, logouts) }
        composeRule.onNodeWithText("退出登录").performClick()
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
        composeRule.onNodeWithText("退出登录").performClick()
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

    @Test fun `auto shortcut shows saved types threshold and inactive warning`() {
        composeRule.setContent {
            HomeFixture(
                settings = RollcallSettings(
                    autoAnswerNumber = true,
                    autoAnswerRadar = false,
                    waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_COUNT,
                    waitBeforeAnswerCount = 8,
                ),
            )
        }
        composeRule.onNodeWithText("自动签到快捷开关").performScrollTo().assertExists()
        composeRule.onNodeWithText("仅数字 · 达到 8 人").assertExists()
        composeRule.onNodeWithText("尚未生效，请先启动监控").assertExists()
        composeRule.onNodeWithText("与策略页同步；此开关同时开启或关闭数字、雷达签到。").assertExists()
    }

    @Test fun `strategy link and shortcut toggle dispatch expected values`() {
        var destination = ""
        var enabled: Boolean? = null
        composeRule.setContent {
            HomeFixture(
                settings = RollcallSettings(autoAnswerNumber = true),
                autoChanged = { enabled = it },
                navigate = { destination = it },
            )
        }
        composeRule.onNodeWithText("调整类型与人数条件").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("策略", destination) }
        composeRule.onNodeWithText("雷达签到坐标管理").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("课程坐标", destination) }
        composeRule.onNodeWithText("自动签到快捷开关").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(false, enabled) }
    }
}
