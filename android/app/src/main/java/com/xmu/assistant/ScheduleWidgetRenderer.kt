package com.xmu.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.FrameLayout
import java.time.LocalDate
import java.time.LocalTime

internal data class WidgetCoursePlan(val courses: List<ScheduleWidgetCourse>, val hiddenCount: Int)

internal fun widgetCoursePlan(courses: List<ScheduleWidgetCourse>, nowTime: Int, availableHeight: Int, rowHeight: (ScheduleWidgetCourse) -> Int): WidgetCoursePlan {
    val upcoming = courses.filter { it.endTime > nowTime }.sortedWith(compareBy({ it.startTime }, { it.startSection }))
    var usedHeight = 0
    val visible = upcoming.takeWhile { course ->
        usedHeight += rowHeight(course)
        usedHeight <= availableHeight
    }
    return WidgetCoursePlan(visible, upcoming.size - visible.size)
}

/** Pure rendering inputs make size, stale-day and long-location behavior testable without account data. */
internal fun buildScheduleWidgetViews(
    context: Context,
    snapshot: ScheduleWidgetSnapshot?,
    enabled: Boolean,
    heightDp: Int,
    today: LocalDate = LocalDate.now(),
    now: LocalTime = LocalTime.now(),
    widthDp: Int = 250,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.schedule_widget)
    val compact = heightDp < 96
    val displayDensity = context.resources.displayMetrics.density
    views.setViewVisibility(R.id.widget_header, if (compact) View.GONE else View.VISIBLE)
    views.setViewPadding(R.id.widget_root, (12 * displayDensity).toInt(), ((if (compact) 4 else 12) * displayDensity).toInt(),
        (12 * displayDensity).toInt(), ((if (compact) 4 else 12) * displayDensity).toInt())
    val openIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        putExtra("open_page", "课表")
    }
    val pending = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    views.setOnClickPendingIntent(R.id.widget_root, pending)
    views.setTextViewText(R.id.widget_title, "今日课程")
    views.setTextViewText(R.id.widget_week, "${today.monthValue}/${today.dayOfMonth} 周${xmuWeekdayShort(today.dayOfWeek.value)}")
    views.setViewVisibility(R.id.widget_footer, View.GONE)
    views.removeAllViews(R.id.widget_courses)

    fun empty(text: String): RemoteViews {
        views.setViewVisibility(R.id.widget_courses, View.GONE)
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        views.setTextViewText(R.id.widget_empty, text)
        return views
    }
    if (!enabled) return empty("小卡片已关闭\n在「更多 → 策略与外观」中开启")
    if (snapshot == null) return empty("课表还未同步\n打开课表，刷新后即可查看")
    // Validate the date before the empty-course branch: yesterday's free day is not today's free day.
    if (snapshot.savedEpochDay != today.toEpochDay()) return empty("今日课表待更新\n点击打开课表刷新")
    if (snapshot.week in 1..30) views.setTextViewText(R.id.widget_week, "周${xmuWeekdayShort(today.dayOfWeek.value)} · 第${snapshot.week}周")
    if (snapshot.courses.isEmpty()) return empty("今天没有课\n留些时间，安排自己的计划")
    val nowTime = now.hour * 100 + now.minute
    if (snapshot.courses.none { it.endTime > nowTime }) return empty("今天的课程已结束\n点击查看本周安排")

    views.setViewVisibility(R.id.widget_courses, View.VISIBLE)
    views.setViewVisibility(R.id.widget_empty, View.GONE)
    val density = context.resources.displayMetrics.density
    val parent = FrameLayout(context)
    val wide = widthDp >= 340 && context.resources.configuration.fontScale <= 1.15f
    val template = views.apply(context, parent)
    template.measure(
        View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY),
    )
    val container = template.findViewById<View>(R.id.widget_courses)
    val rows = mutableMapOf<ScheduleWidgetCourse, RemoteViews>()
    val heights = mutableMapOf<ScheduleWidgetCourse, Int>()
    val upcoming = snapshot.courses.filter { it.endTime > nowTime }
    upcoming.forEach { course ->
        val row = RemoteViews(context.packageName, if (wide) R.layout.schedule_widget_row_wide else R.layout.schedule_widget_row)
        if (wide && heightDp < 96) {
            row.setViewPadding(R.id.widget_row, 0, (2 * density).toInt(), 0, (2 * density).toInt())
            row.setInt(R.id.widget_row, "setMinimumHeight", (26 * density).toInt())
        }
        row.setTextViewText(R.id.row_time, if (wide) "${formatXmuTime(course.startTime)}–${formatXmuTime(course.endTime)}" else formatXmuTime(course.startTime))
        if (!wide) row.setTextViewText(R.id.row_end_time, formatXmuTime(course.endTime))
        row.setTextViewText(R.id.row_course, course.courseName)
        val location = course.location.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }.distinct().joinToString(" / ")
            .ifBlank { "教室待确认" }
        row.setTextViewText(R.id.row_location, location)
        row.setContentDescription(R.id.row_course, "${course.courseName}，${formatXmuTime(course.startTime)}至${formatXmuTime(course.endTime)}，$location")
        val view = row.apply(context, parent)
        view.measure(View.MeasureSpec.makeMeasureSpec(container.measuredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        rows[course] = row
        heights[course] = view.measuredHeight
    }
    var plan = widgetCoursePlan(snapshot.courses, nowTime, container.measuredHeight) { heights.getValue(it) }
    // Only reserve footer space when needed; a permanent footer took a course row from small widgets.
    if (plan.hiddenCount > 0) {
        val footer = template.findViewById<android.widget.TextView>(R.id.widget_footer)
        footer.visibility = View.VISIBLE
        footer.text = context.getString(R.string.schedule_widget_overflow, plan.hiddenCount)
        template.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        plan = widgetCoursePlan(snapshot.courses, nowTime, container.measuredHeight) { heights.getValue(it) }
        views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        views.setTextViewText(R.id.widget_footer, context.getString(R.string.schedule_widget_overflow, plan.hiddenCount))
    }
    if (plan.courses.isEmpty()) {
        views.setViewVisibility(R.id.widget_footer, View.GONE)
        return empty("空间不足，拉高查看课程")
    }
    plan.courses.forEach { views.addView(R.id.widget_courses, rows.getValue(it)) }
    return views
}
