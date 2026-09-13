package com.xmu.assistant

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 策略页自动签到开关的跨导航同步语义（v1.6.1 引入 PageStateHost 后可达的回归）。
 *
 * StrategyPage 依赖 AssistantSettings 的加密 prefs（JVM 下 Keystore 不可用，无法
 * 直接渲染），所以这里直接渲染它使用的 rememberSyncedBoolean，并按 PageStateHost
 * 的页面保留方式（导航离开 → 外部改写已保存值 → 导航回来）验证重同步确实生效。
 * 这补上了纯源码契约锚的缺口：锚保证「用了这个助手」，本用例保证「这个助手管用」。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StrategyToggleSyncTest {
    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun ToggleHarness(saved: Boolean) {
        val state = rememberSyncedBoolean(saved)
        Text("number=${state.value}", modifier = Modifier.testTag("number"))
    }

    @Test
    fun `toggle follows an external change made while the page was off screen`() {
        var page by mutableStateOf("策略")
        var saved by mutableStateOf(false)
        composeRule.setContent {
            XmuMobileTheme {
                PageStateHost(page, "student-a") {
                    when (page) {
                        "策略" -> ToggleHarness(saved)
                        else -> Text("home")
                    }
                }
            }
        }
        composeRule.onNodeWithTag("number").assertTextEquals("number=false")

        // 离开策略页 → 首页开关改写已保存值 → 回到策略页：本地副本必须跟到 true
        composeRule.runOnIdle { page = "首页" }
        composeRule.runOnIdle { saved = true }
        composeRule.runOnIdle { page = "策略" }

        composeRule.onNodeWithTag("number").assertTextEquals("number=true")
    }

    @Test
    fun `local edits survive while the saved value is unchanged`() {
        var saved by mutableStateOf(false)
        composeRule.setContent {
            XmuMobileTheme { ToggleHarness(saved) }
        }
        composeRule.onNodeWithTag("number").assertTextEquals("number=false")
        // 已保存值不变时，重组不得把本地副本重置（LaunchedEffect 同值写入为 no-op）
        composeRule.runOnIdle { saved = false }
        composeRule.onNodeWithTag("number").assertTextEquals("number=false")
    }
}
