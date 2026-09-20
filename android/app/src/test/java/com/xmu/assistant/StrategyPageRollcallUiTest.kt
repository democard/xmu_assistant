package com.xmu.assistant

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ApplicationProvider
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
class StrategyPageRollcallUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `invalid active count disables save and does not invoke callback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("strategy_page_rollcall_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = AssistantSettings.forTest(context, prefs)
        var saves = 0
        composeRule.setContent {
            XmuMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StrategyPage(
                        settings = settings,
                        termCode = "2026-1",
                        current = RollcallSettings(),
                        themeMode = THEME_MODE_SYSTEM,
                        onThemeModeChanged = {},
                        onSaved = { saves++ },
                        onWidgetToggle = {},
                        onAddWidget = {},
                        onManualWeekSet = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("达到人数").performScrollTo().performClick()
        composeRule.onNodeWithText("当前修改尚未保存；点击“保存策略更改”后生效。").assertExists()
        composeRule.onNodeWithText("达到人数后自动处理（至少 1 人）").performTextClearance()
        composeRule.onNodeWithText("请输入至少 1 的整数").assertExists()
        composeRule.onNodeWithText("保存策略更改").performScrollTo().assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(0, saves) }
    }
}
