package com.xmu.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
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
class ThemeUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `all five destinations are visible and selectable on a narrow phone`() {
        composeRule.setContent {
            XmuMobileTheme(THEME_MODE_LIGHT) {
                var selected by remember { mutableStateOf("首页") }
                AppNavigationBar(selected, NotificationSettings(), 0) { selected = it }
            }
        }
        listOf("首页", "课表", "成绩", "签到", "更多").forEach { title ->
            composeRule.onNodeWithText(title).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithText(title).performClick().assertIsSelected()
        }
    }

    @Test fun `secondary pages highlight more in dark theme and expose hub on click`() {
        var destination = ""
        composeRule.setContent {
            XmuMobileTheme(THEME_MODE_DARK) {
                AppNavigationBar("课程课件", NotificationSettings(), 3) { destination = it }
            }
        }
        composeRule.onNodeWithText("更多").assertIsSelected().performClick()
        composeRule.runOnIdle { assertEquals("更多", destination) }
        composeRule.onNodeWithText("更多").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3 个文件下载中"),
        )
        composeRule.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test fun `all secondary destinations remain reachable including notification setup`() {
        var selected = ""
        composeRule.setContent {
            XmuMobileTheme(THEME_MODE_LIGHT) {
                MorePage(NotificationSettings(pushPlusEnabled = true), 0) { selected = it }
            }
        }
        composeRule.onNodeWithText("有通知渠道待配置").assertIsDisplayed()
        moreDestinations.forEach {
            composeRule.onNodeWithText(it.label).performClick()
            composeRule.runOnIdle { assertEquals(it.page, selected) }
        }
    }
}
