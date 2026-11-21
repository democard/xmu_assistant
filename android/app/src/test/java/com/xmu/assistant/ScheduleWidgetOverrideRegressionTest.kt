package com.xmu.assistant

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1800dp")
class ScheduleWidgetOverrideRegressionTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val today = LocalDate.now()
    private val monday = today.minusDays(today.dayOfWeek.value - 1L)
    private val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
    private val original = XmuScheduleEntry(
        today.dayOfWeek.value, 1, 2, 800, 940, "小组件修正测试课", "官方教室", "教师", "1-3周", "20261",
    )
    private val store get() = ScheduleOverrideStore(context)
    private val base get() = listOf(original).groupForDisplay().single()

    @Before
    fun clearLocalData() {
        store.clear()
        ScheduleWidgetData.clear(context)
    }

    @Test
    fun `sync projects corrected weekday sections location and weeks`() {
        val tomorrow = today.plusDays(1)
        store.save(
            "20261", base,
            ScheduleCourseOverride(tomorrow.dayOfWeek.value, 3, 4, "修正教室", "", "1-3周"),
        )

        syncScheduleWidget(context, listOf(original), "20261", inferredCalendar = calendar)

        assertTrue(requireNotNull(ScheduleWidgetData.load(context, today)).courses.isEmpty())
        val moved = requireNotNull(ScheduleWidgetData.load(context, tomorrow)).courses.single()
        assertEquals(original.courseName, moved.courseName)
        assertEquals("修正教室", moved.location)
        assertEquals(3, moved.startSection)
        assertEquals(4, moved.endSection)
    }

    @Test
    fun `saving a local classroom change refreshes widget immediately`() {
        syncScheduleWidget(context, listOf(original), "20261", inferredCalendar = calendar)
        showSchedule()

        openDetails()
        composeRule.onNodeWithText("地点").performTextReplacement("修改后的教室")
        composeRule.onNodeWithText("保存修改").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals("修改后的教室", requireNotNull(ScheduleWidgetData.load(context, today)).courses.single().location)
        }
    }

    @Test
    fun `restoring official arrangement refreshes corrected widget immediately`() {
        store.save("20261", base, ScheduleCourseOverride(original.weekday, 1, 2, "修改后的教室", "教师", "1-3周"))
        ScheduleWidgetData.saveUpcoming(
            context,
            projectScheduleWidgetDays(store.applyToEntries("20261", listOf(original)), "20261", calendar, today = today),
        )
        showSchedule()

        openDetails()
        composeRule.onNodeWithText("恢复官方").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(original.room, requireNotNull(ScheduleWidgetData.load(context, today)).courses.single().location)
        }
    }

    private fun openDetails() {
        composeRule.onNodeWithText("修改安排").performClick()
        composeRule.onNodeWithText("编辑 ›").performClick()
    }

    private fun showSchedule() {
        composeRule.setContent {
            XmuMobileTheme {
                SchedulePage(
                    listOf(original), "20261", 0, false, "", true, {},
                    inferredCalendar = calendar, pinnedHeader = true,
                )
            }
        }
    }
}
