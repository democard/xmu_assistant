package com.xmu.assistant

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleWeekGridUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `full grid keeps eleven sections and the complete week`() {
        composeRule.setContent { XmuMobileTheme { ScheduleWeekGrid(emptyList(), LocalDate.of(2026, 9, 7), false, 1, 0, {}) } }
        (1..11).forEach {
            composeRule.onNodeWithText(it.toString()).assertExists()
        }
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEach {
            assertTrue(composeRule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty())
        }
        composeRule.onNodeWithText("固定 11 节课", substring = true).assertExists()
    }

    @Test fun `course card keeps course sections and all room text`() {
        val group = XmuScheduleGroup(2, 3, 4, 1010, 1150, "大学物理B（下）", listOf("学武楼C206", "实验楼A301"), emptyList(), "1-16周")
        composeRule.setContent { XmuMobileTheme { ScheduleWeekGrid(listOf(group), null, false, 1, 0, {}) } }
        composeRule.onNodeWithText(group.courseName).assertIsDisplayed()
        composeRule.onNodeWithText("3-4节").assertIsDisplayed()
        composeRule.onNodeWithText(scheduleLocationSummary(group)).assertIsDisplayed()
    }

    @Test fun `overlapping lessons remain individually selectable`() {
        val first = XmuScheduleGroup(1, 1, 2, 800, 940, "课程甲", listOf("A101"), emptyList(), "1-16周")
        val second = first.copy(startSection = 2, endSection = 3, courseName = "课程乙")
        var selected = ""
        composeRule.setContent {
            XmuMobileTheme { ScheduleWeekGrid(listOf(first, second), null, false, 1, 0, { selected = it.courseName }) }
        }
        composeRule.onAllNodes(hasText("时间重叠")).assertCountEquals(2)
        composeRule.onNode(hasClickAction() and hasText("课程乙")).performClick()
        assertEquals("课程乙", selected)
        composeRule.onNode(hasClickAction() and hasText("课程甲")).performClick()
        assertEquals("课程甲", selected)
    }

    @Test fun `course columns keep equal widths in full grid`() {
        val first = XmuScheduleGroup(1, 1, 2, 800, 940, "课程甲", listOf("A101"), emptyList(), "1-16周")
        val second = first.copy(weekday = 2, courseName = "课程乙")
        composeRule.setContent { XmuMobileTheme { ScheduleWeekGrid(listOf(first, second), null, false, 1, 0, {}) } }
        val a = composeRule.onNode(hasClickAction() and hasText("课程甲")).fetchSemanticsNode().boundsInRoot
        val b = composeRule.onNode(hasClickAction() and hasText("课程乙")).fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(a.width - b.width) < 1f)
        assertTrue(kotlin.math.abs(a.top - b.top) < 1f)
    }
}
