package com.xmu.assistant

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleWidgetRendererTest {
    private val today = LocalDate.of(2026, 9, 7)
    private val courses = listOf(
        ScheduleWidgetCourse("课程一", 800, 940, 1, 2, "翔安校区 学武楼 B-205"),
        ScheduleWidgetCourse("课程二", 1010, 1150, 3, 4, "思明校区 海韵教学楼 A-301"),
        ScheduleWidgetCourse("课程三", 1430, 1610, 5, 6, "教学楼 C-206"),
        ScheduleWidgetCourse("课程四", 1910, 2050, 9, 10, "教学楼 B-201"),
    )

    @Test fun `small widget shows upcoming course and makes overflow explicit`() {
        val plan = widgetCoursePlan(courses, 1000, 70) { 60 }
        assertEquals(listOf("课程二"), plan.courses.map { it.courseName })
        assertEquals(2, plan.hiddenCount)
        assertEquals(3, widgetCoursePlan(courses, 1000, 180) { 60 }.courses.size)
        assertEquals(1, widgetCoursePlan(courses, 1000, 180) { 100 }.courses.size)
        assertTrue(widgetCoursePlan(courses, 2100, 180) { 60 }.courses.isEmpty())
    }

    private fun render(snapshot: ScheduleWidgetSnapshot?, height: Int = 160, enabled: Boolean = true, width: Int = 250): View {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val remote = buildScheduleWidgetViews(context, snapshot, enabled, height, today, LocalTime.of(10, 0), widthDp = width)
        return remote.apply(context, FrameLayout(context)).apply {
            val density = context.resources.displayMetrics.density
            measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    @Test fun `old empty snapshot never claims that today has no classes`() {
        val old = ScheduleWidgetSnapshot(1, 2, "20261", emptyList(), today.minusDays(1).toEpochDay())
        assertTrue(render(old).findViewById<TextView>(R.id.widget_empty).text.contains("待更新"))
        assertTrue(render(old.copy(savedEpochDay = today.toEpochDay())).findViewById<TextView>(R.id.widget_empty).text.contains("今天没有课"))
        assertTrue(render(old, enabled = false).findViewById<TextView>(R.id.widget_empty).text.contains("已关闭"))
    }

    @Test fun `long classroom has its own line without squeezing course title`() {
        val snapshot = ScheduleWidgetSnapshot(1, 2, "20261", courses, today.toEpochDay())
        val view = render(snapshot, 180)
        val rows = view.findViewById<LinearLayout>(R.id.widget_courses)
        assertEquals(2, rows.childCount)
        val first = rows.getChildAt(0)
        val title = first.findViewById<TextView>(R.id.row_course)
        val location = first.findViewById<TextView>(R.id.row_location)
        assertEquals("课程二", title.text.toString())
        assertEquals(courses[1].location, location.text.toString())
        assertTrue(location.top >= title.bottom)
        assertTrue(title.width > first.findViewById<TextView>(R.id.row_time).width)
        assertTrue(view.findViewById<TextView>(R.id.widget_footer).visibility == View.GONE || rows.bottom <= view.findViewById<TextView>(R.id.widget_footer).top)
        assertTrue(view.findViewById<TextView>(R.id.widget_footer).text.contains("另有 1 门课"))
    }

    @Test fun `minimum height widget keeps every visible row above the footer`() {
        val snapshot = ScheduleWidgetSnapshot(1, 2, "20261", listOf(courses[1].copy(location = "翔安校区 学武楼 B-205\n思明校区 海韵教学楼 A-301")), today.toEpochDay())
        val view = render(snapshot, 128)
        val rows = view.findViewById<LinearLayout>(R.id.widget_courses)
        assertEquals(1, rows.childCount)
        assertTrue("course row cannot extend past its container", rows.getChildAt(0).bottom <= rows.height)
        assertTrue(view.findViewById<TextView>(R.id.widget_footer).visibility == View.GONE || rows.bottom <= view.findViewById<TextView>(R.id.widget_footer).top)
    }
    @Test fun `wide desktop widget keeps old three column alignment with bounded classroom width`() {
        val snapshot = ScheduleWidgetSnapshot(1, 2, "20261", courses, today.toEpochDay())
        val view = render(snapshot, 150, width = 350)
        val rows = view.findViewById<LinearLayout>(R.id.widget_courses)
        assertEquals(3, rows.childCount)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_footer).visibility)
        for (index in 0 until rows.childCount) {
            val row = rows.getChildAt(index)
            val time = row.findViewById<TextView>(R.id.row_time)
            val title = row.findViewById<TextView>(R.id.row_course)
            val room = row.findViewById<TextView>(R.id.row_location)
            assertTrue(time.text.contains("–"))
            assertTrue(time.right <= title.left)
            assertTrue(title.right <= room.left)
            assertTrue(title.width >= room.width)
            assertTrue(room.right <= row.width)
            assertTrue(row.bottom <= rows.height)
        }
    }

    @Test fun `short widget uses available space without permanent footer`() {
        val snapshot = ScheduleWidgetSnapshot(1, 2, "20261", listOf(courses[1]), today.toEpochDay())
        val view = render(snapshot, 96)
        val rows = view.findViewById<LinearLayout>(R.id.widget_courses)
        assertEquals(1, rows.childCount)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_footer).visibility)
        assertTrue(rows.getChildAt(0).bottom <= rows.height)
    }

    @Test fun `one row launcher size retains a course and explicit overflow`() {
        val snapshot = ScheduleWidgetSnapshot(1, 2, "20261", courses, today.toEpochDay())
        val view = render(snapshot, 56, width = 350)
        val rows = view.findViewById<LinearLayout>(R.id.widget_courses)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_header).visibility)
        assertEquals(1, rows.childCount)
        assertTrue(rows.getChildAt(0).bottom <= rows.height)
        assertTrue(view.findViewById<TextView>(R.id.widget_footer).text.contains("另有 2 门课"))
    }

    @Test fun `launcher reapply restores header and padding after growing compact widget`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = ScheduleWidgetSnapshot(1, 2, "20261", courses, today.toEpochDay())
        val small = buildScheduleWidgetViews(context, snapshot, true, 56, today, LocalTime.of(10, 0), 350)
        val view = small.apply(context, FrameLayout(context))
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_header).visibility)
        val large = buildScheduleWidgetViews(context, snapshot, true, 183, today, LocalTime.of(10, 0), 196)
        large.reapply(context, view)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_header).visibility)
        assertEquals((12 * context.resources.displayMetrics.density).toInt(), view.paddingTop)
    }

}
