package com.xmu.assistant

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class StrategyPageRollcallUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `invalid poll interval disables save and valid interval saves monitor only settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("strategy_page_rollcall_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = AssistantSettings.forTest(context, prefs)
        val savedList = mutableListOf<RollcallSettings>()
        composeRule.setContent {
            XmuMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StrategyPage(
                        settings = settings,
                        termCode = "2026-1",
                        current = RollcallSettings(autoAnswerNumber = true, autoAnswerRadar = true),
                        themeMode = THEME_MODE_SYSTEM,
                        onThemeModeChanged = {},
                        onSaved = { savedList += it },
                        onWidgetToggle = {},
                        onAddWidget = {},
                        onManualWeekSet = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("数字签到自动处理").assertDoesNotExist()
        composeRule.onNodeWithText("雷达签到自动处理").assertDoesNotExist()
        composeRule.onNodeWithText("默认轮询间隔（1-300 秒）").performScrollTo().performTextReplacement("500")
        composeRule.onNodeWithText("请输入 1-300 的整数；留空将使用默认 30 秒").assertExists()
        composeRule.onNodeWithText("保存监控间隔").performScrollTo().assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(0, savedList.size) }

        composeRule.onNodeWithText("默认轮询间隔（1-300 秒）").performScrollTo().performTextReplacement("45")
        composeRule.onNodeWithText("保存监控间隔").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, savedList.size)
            assertEquals(45, savedList.single().pollIntervalSeconds)
            assertFalse(savedList.single().autoAnswerNumber)
            assertFalse(savedList.single().autoAnswerRadar)
        }
    }
}
