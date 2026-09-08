package com.xmu.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleWidgetProjectionTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
    private val base = XmuScheduleEntry(1, 1, 2, 800, 940, "课程甲", "A101", "老师", "1周", "20261")

    @Test fun `offline summaries advance date and week without leaking courses from the wrong week`() {
        val days = projectScheduleWidgetDays(listOf(base, base.copy(room = "B202"), base.copy(courseName = "课程乙", weeks = "2周")), "20261", calendar, today = monday)
        assertEquals(14, days.size)
        assertEquals("A101\nB202", days[0].courses.single().location)
        assertEquals("课程乙", days[7].courses.single().courseName)
        assertEquals(2, days[7].week)
        assertTrue(days[1].courses.isEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        ScheduleWidgetData.saveUpcoming(context, days)
        assertEquals(days[7], ScheduleWidgetData.load(context, monday.plusWeeks(1)))
        ScheduleWidgetData.clear(context)
        assertNull(ScheduleWidgetData.load(context, monday.plusWeeks(1)))
    }

    @Test fun `manual week calibration advances on monday instead of freezing the cached week`() {
        val sunday = monday.plusDays(6)
        val days = projectScheduleWidgetDays(listOf(base.copy(weeks = "3周")), "20261", null, manualWeek = 2, today = sunday)
        assertEquals(2, days[0].week)
        assertEquals(3, days[1].week)
        assertEquals("课程甲", days[1].courses.single().courseName)
    }
}
