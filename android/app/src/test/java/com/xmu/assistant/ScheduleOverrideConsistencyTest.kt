package com.xmu.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleOverrideConsistencyTest {
    @get:Rule val composeRule = createComposeRule()

    private val today = LocalDate.now()
    private val monday = today.minusDays(today.dayOfWeek.value - 1L)
    private val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
    private val original = XmuScheduleEntry(
        today.dayOfWeek.value, 1, 2, 800, 940, "修正周次测试课", "原教室", "原教师", "1-2周", "20261",
    )

    @Test
    fun `corrected weeks control both removed and newly added UI weeks`() {
        val store = ScheduleOverrideStore(RuntimeEnvironment.getApplication())
        store.clear()
        store.save(
            "20261",
            listOf(original).groupForDisplay().single(),
            ScheduleCourseOverride(original.weekday, 1, 2, "新教室", "新教师", "2-3周"),
        )
        composeRule.setContent {
            XmuMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SchedulePage(listOf(original), "20261", 0, false, "", true, {}, inferredCalendar = calendar)
                }
            }
        }
        composeRule.onNodeWithText(original.courseName).assertDoesNotExist()
        composeRule.onNodeWithText("第1周 ▾").performClick()
        composeRule.onNodeWithText("第3周").performClick()
        composeRule.onNodeWithText(original.courseName).assertExists()
        composeRule.onNodeWithText("第3周 ▾").performClick()
        composeRule.onNodeWithText("第1周").performClick()
        composeRule.onNodeWithText(original.courseName).assertDoesNotExist()
    }

    @Test
    fun `export retains explicitly cleared location teacher and weeks`() {
        val store = ScheduleOverrideStore(RuntimeEnvironment.getApplication())
        store.clear()
        val base = listOf(original).groupForDisplay().single()
        store.save("20261", base, ScheduleCourseOverride(original.weekday, 1, 2, "", "", ""))

        val visible = store.apply("20261", base)
        val exported = store.applyToEntries("20261", listOf(original)).single()

        assertTrue(visible.rooms.isEmpty())
        assertTrue(visible.teachers.isEmpty())
        assertEquals("", visible.weeks)
        assertEquals("", exported.room)
        assertEquals("", exported.teacher)
        assertEquals("", exported.weeks)
    }

    @Test
    fun `corrected ICS contains new dates without resurrecting old location`() {
        val store = ScheduleOverrideStore(RuntimeEnvironment.getApplication())
        store.clear()
        val base = listOf(original).groupForDisplay().single()
        store.save("20261", base, ScheduleCourseOverride(original.weekday, 1, 2, "", "", "2-3周"))

        val text = requireNotNull(buildScheduleIcs(store.applyToEntries("20261", listOf(original)), calendar))

        assertFalse(text.contains(original.room))
        assertFalse(text.contains(original.teacher))
        val firstDate = monday.plusWeeks(1).plusDays(original.weekday - 1L)
        val lastDate = firstDate.plusWeeks(1)
        val dateFormat = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        assertTrue(text.contains("DTSTART:${firstDate.format(dateFormat)}T080000"))
        assertTrue(text.contains(";UNTIL=${lastDate.format(dateFormat)}T235959"))
        assertTrue(store.has("20261", base))
        assertTrue(store.remove("20261", base))
        assertEquals(listOf(original), store.applyToEntries("20261", listOf(original)))
    }
}
