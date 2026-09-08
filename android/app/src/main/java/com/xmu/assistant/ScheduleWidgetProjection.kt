package com.xmu.assistant

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Precompute two weeks of public summaries; the widget can roll over without credentials or network. */
internal fun projectScheduleWidgetDays(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    calendar: XmuAcademicCalendar?,
    manualWeek: Int = 0,
    today: LocalDate = LocalDate.now(),
): List<ScheduleWidgetSnapshot> {
    val totalWeeks = calendar?.totalWeeks ?: 19
    val index = indexXmuScheduleByWeek(entries)
    val currentMonday = today.minusDays(today.dayOfWeek.value - 1L)
    return (0L until 14L).map { offset ->
        val date = today.plusDays(offset)
        val week = if (manualWeek in 1..totalWeeks) {
            val monday = date.minusDays(date.dayOfWeek.value - 1L)
            (manualWeek + ChronoUnit.WEEKS.between(currentMonday, monday).toInt()).takeIf { it in 1..totalWeeks }
        } else xmuAcademicWeekFor(calendar, date).week
        val weekday = xmuWeekdayFrom(date)
        val courses = (index[week] ?: emptyList()).filter { it.weekday == weekday }.groupForDisplay().map { group ->
            ScheduleWidgetCourse(group.courseName, group.startTime, group.endTime,
                group.startSection, group.endSection, scheduleLocationSummary(group))
        }
        ScheduleWidgetSnapshot(weekday, week ?: 0, termCode, courses, date.toEpochDay())
    }
}
