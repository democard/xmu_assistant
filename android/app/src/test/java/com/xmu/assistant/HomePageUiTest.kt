package com.xmu.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                            navigate: (String) -> Unit = {}) {
        XmuMobileTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HomePage(
                    username = "123456789", password = "", loggedIn = loggedIn, monitorRunning = running,
                    accountTransitionInProgress = false, monitorTransitionInProgress = busy,
                    monitorStatus = if (running) "运行中" else "未启动", monitorLastCheck = "10:00:00",
                    monitorFailureCount = 0, monitorLastError = "", rollcallSettings = settings, recentEvent = null,
                    onUsername = {}, onPassword = {}, onLogin = {}, onLogout = logout,
                    onStartMonitor = start, onStopMonitor = stop, onAutoChanged = autoChanged,
                    onOpenBackgroundSettings = {}, onNavigate = navigate,
                )
            }
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
        composeRule.onNodeWithText("自动签到快捷开关").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(false, enabled) }
    }
}
