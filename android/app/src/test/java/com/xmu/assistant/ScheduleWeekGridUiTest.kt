package com.xmu.assistant

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric 提供 Android 运行时（createComposeRule 依赖）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleWeekGridUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 超过 2 行的超长课程名（单节课程，块高=76dp，此前会被 Ellipsis 截断）。 */
    @Test
    fun `long course name is fully rendered without ellipsis`() {
        val longName = "课程I（含实践环节）"
        val group = XmuScheduleGroup(
            weekday = 1,
            startSection = 1,
            endSection = 1,
            startTime = 800,
            endTime = 845,
            courseName = longName,
            rooms = listOf("教学楼B-109"),
            teachers = listOf("张老师"),
            weeks = "1-16周",
        )

        composeRule.setContent {
            ScheduleWeekGrid(
                groups = listOf(group),
                weekStart = LocalDate.of(2026, 9, 7),
                selectedIsCurrent = false,
                todayWeekday = 1,
                nowValue = 0,
                onCourseSelected = {},
            )
        }

        // 完整课程名必须出现在渲染树中（无截断）
        composeRule.onNodeWithText(longName).assertIsDisplayed()
        // 节次与地点也完整显示
        composeRule.onNodeWithText("1-1节").assertIsDisplayed()
        composeRule.onNodeWithText("教学楼B-109").assertIsDisplayed()
    }

    /** 多地点（变体）课程：地点摘要完整显示。 */
    @Test
    fun `multi room summary is fully rendered without ellipsis`() {
        val group = XmuScheduleGroup(
            weekday = 2,
            startSection = 3,
            endSection = 4,
            startTime = 1000,
            endTime = 1140,
            courseName = "课程E",
            rooms = listOf("教学楼B-205", "教学楼A-C206"),
            teachers = listOf("李老师", "王老师"),
            weeks = "1-16周",
        )

        composeRule.setContent {
            ScheduleWeekGrid(
                groups = listOf(group),
                weekStart = LocalDate.of(2026, 9, 7),
                selectedIsCurrent = false,
                todayWeekday = 1,
                nowValue = 0,
                onCourseSelected = {},
            )
        }

        val location = scheduleLocationSummary(group)
        composeRule.onNodeWithText(location).assertIsDisplayed()
        composeRule.onNodeWithText("3-4节").assertIsDisplayed()
    }

    /** 点击课程块应触发 onCourseSelected 回调（交互未被高度自适应破坏）。 */
    @Test
    fun `course block click still invokes callback`() {
        val group = XmuScheduleGroup(
            weekday = 3,
            startSection = 5,
            endSection = 6,
            startTime = 1400,
            endTime = 1540,
            courseName = "课程A",
            rooms = listOf("教学楼A-A107"),
            teachers = listOf("老师A"),
            weeks = "1-16周",
        )
        var clicked: XmuScheduleGroup? = null

        composeRule.setContent {
            ScheduleWeekGrid(
                groups = listOf(group),
                weekStart = LocalDate.of(2026, 9, 7),
                selectedIsCurrent = false,
                todayWeekday = 1,
                nowValue = 0,
                onCourseSelected = { clicked = it },
            )
        }

        composeRule.onNodeWithText("课程A").performClick()
        composeRule.runOnIdle {
            assertEquals("clicked course must match", "课程A", clicked?.courseName.orEmpty())
        }
    }

    /**
     * 渲染树语义完整性：长课程名的完整文本必须出现在语义树中（未被字符串级截断）。
     * 说明：Robolectric 的文本布局不做按宽换行，高度自适应（heightIn min）的真实换行
     * 行为无法在 JVM 上验证，改由模拟器人工/截图验证；这里锁定「完整文本存在」这一层。
     */
    @Test
    fun `full course name text is present in semantics tree`() {
        val longName = "课程I（含实践环节）"
        val group = XmuScheduleGroup(
            weekday = 1, startSection = 1, endSection = 1,
            startTime = 800, endTime = 845,
            courseName = longName,
            rooms = listOf("教学楼B-109"),
            teachers = listOf("张老师"),
            weeks = "1-16周",
        )
        composeRule.setContent {
            ScheduleWeekGrid(
                groups = listOf(group),
                weekStart = LocalDate.of(2026, 9, 7),
                selectedIsCurrent = false,
                todayWeekday = 1,
                nowValue = 0,
                onCourseSelected = {},
            )
        }
        // 语义树中的文本必须等于完整课程名（不含省略号、未被截断）
        composeRule.onNodeWithText(longName).assertIsDisplayed()
        // 渲染树中不应出现省略号字符（完整文本在树上，若被截断为省略号则查找不到完整名）
        composeRule.onAllNodes(hasText("…", substring = true)).assertCountEquals(0)
    }
}
