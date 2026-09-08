package com.xmu.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulePageUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `today action returns from another week and exposes todays agenda`() {
        val today = LocalDate.now()
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
        val course = XmuScheduleEntry(today.dayOfWeek.value, 1, 2, 800, 940, "今天的课程", "A101", "老师", "1-16周", "20261")
        composeRule.setContent {
            XmuMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SchedulePage(listOf(course), "20261", 0, false, "", true, {}, inferredCalendar = calendar)
                }
            }
        }
        composeRule.onNodeWithText("第1周 ▾").performClick()
        composeRule.onNodeWithText("第2周").performClick()
        composeRule.onNodeWithText("第2周 · 非本周").assertIsDisplayed()
        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNodeWithText("第1周 · 本周").assertIsDisplayed()
        composeRule.onNodeWithText("今天的课程").assertIsDisplayed()
        composeRule.onNodeWithText("固定 11 节课", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("周课表").performClick()
        composeRule.onNodeWithText("固定 11 节课", substring = true).assertExists()
    }
}
