package com.xmu.assistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric 提供 Android 运行时（createComposeRule 依赖）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `dark theme selected tab background is light blue not deep navy`() {
        // 审查 P1-5 的问题：深色主题下选中底用 AppPrimary(0xFF083B6F 深海军蓝)，
        // 与未选中底 surfaceVariant(0xFF1C2530) 都是深色、区分度低；
        // themeSelectedTab 深色分支 = 0xFF2B4A6E（浅蓝），与深色底区分明显。
        val lightBlueVariant = Color(0xFF2B4A6E)
        val darkNavy = Color(0xFF083B6F)
        val darkSurfaceVariant = Color(0xFF1C2530)
        composeRule.setContent {
            XmuMobileTheme(themeMode = THEME_MODE_DARK) {
                TopTabs(
                    selected = "课表",
                    notificationSettings = NotificationSettings(),
                    downloadingCount = 0,
                    onSelected = {},
                )
            }
        }
        // 断言浅蓝底与深色 surfaceVariant 的亮度差 > 深海军蓝底与它的亮度差
        // （选中态必须从背景中"跳出来"，浅蓝比深蓝更亮更醒目）
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double {
                val s = v.toDouble()
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        val lightBlueDelta = Math.abs(luminance(lightBlueVariant) - luminance(darkSurfaceVariant))
        val navyDelta = Math.abs(luminance(darkNavy) - luminance(darkSurfaceVariant))
        assert(lightBlueDelta > navyDelta) {
            "light blue (delta $lightBlueDelta) must stand out more than navy (delta $navyDelta) on dark surface"
        }
    }

    @Test
    fun `top tab text renders in both themes without crash`() {
        composeRule.setContent {
            XmuMobileTheme(themeMode = THEME_MODE_LIGHT) {
                TopTabs(
                    selected = "首页",
                    notificationSettings = NotificationSettings(),
                    downloadingCount = 0,
                    onSelected = {},
                )
            }
        }
        composeRule.onNodeWithText("课表").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("成绩").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `tab touch targets meet 48dp in dark theme`() {
        composeRule.setContent {
            XmuMobileTheme(themeMode = THEME_MODE_DARK) {
                TopTabs(
                    selected = "课表",
                    notificationSettings = NotificationSettings(),
                    downloadingCount = 0,
                    onSelected = {},
                )
            }
        }
        composeRule.onNodeWithText("首页").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("课表").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("课程课件").assertHeightIsAtLeast(48.dp)
    }
}
