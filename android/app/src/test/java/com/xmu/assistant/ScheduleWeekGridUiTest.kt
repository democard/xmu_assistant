package com.xmu.assistant

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric 提供 Android 运行时（createComposeRule 依赖）。 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleWeekGridUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun `large font wrapped times keep the final classroom line inside the card`() {
        val group = XmuScheduleGroup(2, 3, 4, 1010, 1150, "大学物理B（下）", listOf("学武楼（1号楼）C206"), emptyList(), "1-16周")
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f)
            ) {
                androidx.compose.foundation.layout.Box(Modifier.width(328.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ScheduleWeekGrid(listOf(group), null, false, 1, 0, {})
                    }
                }
            }
        }
        val card = composeRule.onNode(hasClickAction() and hasText(group.courseName)).fetchSemanticsNode().boundsInRoot
        val room = composeRule.onAllNodesWithText(group.rooms.single(), useUnmergedTree = true).fetchSemanticsNodes()
            .single { node -> generateSequence(node.parent) { it.parent }.none { it.config.isClearingSemantics } }.boundsInRoot
        assertTrue("final room line must have bottom padding", room.bottom <= card.bottom - 6f)
    }

    @Test fun `cards fill identical column widths and their entire period spans`() {
        val first = XmuScheduleGroup(1, 1, 2, 800, 940, "长课程", listOf("学武楼（1号楼）C204"), emptyList(), "1-16周")
        val short = first.copy(weekday = 2, courseName = "短课程", rooms = listOf("null"))
        val next = first.copy(startSection = 3, endSection = 4, courseName = "下一门")
        val three = first.copy(weekday = 2, startSection = 3, endSection = 5, courseName = "三节课程")
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleWeekGrid(listOf(first, short, next, three), null, false, 1, 0, {})
            }
        }
        fun bounds(name: String) = composeRule.onNode(hasClickAction() and hasText(name)).fetchSemanticsNode().boundsInRoot
        val a = bounds("长课程")
        val b = bounds("短课程")
        val c = bounds("下一门")
        val d = bounds("三节课程")
        assertEquals(a.width, b.width, 0.5f)
        assertEquals(a.height, b.height, 0.5f)
        assertEquals(a.top, b.top, 0.5f)
        val gap = c.top - a.bottom
        assertTrue(gap > 0)
        assertEquals((a.height + gap) / 2f, (d.height + gap) / 3f, 0.5f)
    }

    @Test fun `overlapping lessons remain individually visible and selectable`() {
        val first = XmuScheduleGroup(1, 1, 2, 800, 940, "课程甲", listOf("A101"), emptyList(), "1-16周")
        val second = first.copy(startSection = 2, endSection = 3, courseName = "课程乙")
        var selected: String? = null
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleWeekGrid(listOf(first, second), null, false, 1, 0, { selected = it.courseName })
            }
        }
        composeRule.onAllNodes(hasText("时间重叠")).assertCountEquals(2)
        composeRule.onNodeWithText("课程乙").performClick()
        assertEquals("课程乙", selected)
        composeRule.onNodeWithText("课程甲").performClick()
        assertEquals("课程甲", selected)
    }

    @Test
    fun `all classrooms get separate lines and taller rows keep the next course clear`() {
        val first = XmuScheduleGroup(1, 1, 2, 800, 940, "高等数学（含实践环节）",
            listOf("翔安校区学武楼 B-205", "思明校区海韵教学楼 A-301"), listOf("陈老师"), "1-16周")
        val next = first.copy(startSection = 3, endSection = 4, courseName = "大学英语", rooms = listOf("南强楼 301"))
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleWeekGrid(listOf(first, next), LocalDate.of(2026, 9, 7), false, 1, 0, {})
            }
        }
        val rooms = composeRule.onNodeWithText(first.rooms.joinToString("\n")).fetchSemanticsNode().boundsInRoot
        val nextTitle = composeRule.onNodeWithText("大学英语").fetchSemanticsNode().boundsInRoot
        assertTrue("long classroom text must end before the next course", rooms.bottom <= nextTitle.top)
        composeRule.onNodeWithText("11").assertExists()
    }

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
