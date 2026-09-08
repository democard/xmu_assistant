package com.xmu.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

internal data class ScheduleGridAxes(val days: List<Int>, val sections: List<Int>)

internal fun scheduleGridAxes(): ScheduleGridAxes = ScheduleGridAxes((1..7).toList(), (1..11).toList())

internal data class ScheduleGridCluster(val courses: List<XmuScheduleGroup>) {
    val weekday get() = courses.first().weekday
    val startSection get() = courses.minOf { it.startSection }
    val endSection get() = courses.maxOf { it.endSection }
}

internal data class ScheduleGridPlacement(val course: XmuScheduleGroup, val lane: Int, val overlapping: Boolean)

internal fun scheduleGridPlacements(groups: List<XmuScheduleGroup>): List<ScheduleGridPlacement> =
    scheduleGridClusters(groups).flatMap { cluster ->
        val laneEnds = mutableListOf<Int>()
        cluster.courses.map { course ->
            val free = laneEnds.indexOfFirst { it < course.startSection }
            val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = course.endSection
            ScheduleGridPlacement(course, lane, cluster.courses.size > 1)
        }
    }

/** Keep overlapping courses reachable, including partial and chained overlaps. */
internal fun scheduleGridClusters(groups: List<XmuScheduleGroup>): List<ScheduleGridCluster> = buildList {
    groups.groupBy { it.weekday }.toSortedMap().forEach { (_, dayCourses) ->
        var current = mutableListOf<XmuScheduleGroup>()
        dayCourses.sortedWith(compareBy({ it.startSection }, { it.endSection }, { it.courseName })).forEach { course ->
            if (current.isNotEmpty() && course.startSection > current.maxOf { it.endSection }) {
                add(ScheduleGridCluster(current.toList()))
                current = mutableListOf()
            }
            current += course
        }
        if (current.isNotEmpty()) add(ScheduleGridCluster(current.toList()))
    }
}

/** Use uniform period rows and fill each course's exact span after measuring its text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleWeekGrid(
    groups: List<XmuScheduleGroup>,
    weekStart: LocalDate?,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    val axes = remember { scheduleGridAxes() }
    val validGroups = groups.filter { it.weekday in axes.days && it.startSection in axes.sections }
    val placements = remember(validGroups) { scheduleGridPlacements(validGroups) }
    val laneCounts = axes.days.associateWith { day -> (placements.filter { it.course.weekday == day }.maxOfOrNull { it.lane } ?: 0) + 1 }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sectionWidth = 36.dp
        val dayWidth = maxOf(104.dp, (maxWidth - sectionWidth) / axes.days.size.coerceIn(1, 3))
        @Composable
        fun CourseCard(placement: ScheduleGridPlacement) {
            val group = placement.course
            ScheduleGridCourseBlock(
                group = group,
                dimmed = selectedIsCurrent && group.weekday == todayWeekday && group.endTime < nowValue,
                inProgress = selectedIsCurrent && group.weekday == todayWeekday && nowValue in group.startTime..group.endTime,
                onClick = { onCourseSelected(group) },
                modifier = Modifier.padding(3.dp),
                overlapping = placement.overlapping,
            )
        }
        SubcomposeLayout(Modifier.horizontalScroll(rememberScrollState())) { constraints ->
            val measurables = subcompose("grid") {
                Row {
                    ScheduleGridHeaderCell("节次", sectionWidth, false)
                    axes.days.forEach { day ->
                        val date = weekStart?.plusDays(day - 1L)?.let { "${it.monthValue}/${it.dayOfMonth}" }.orEmpty()
                        ScheduleGridHeaderCell("周${xmuWeekdayShort(day)}\n$date", dayWidth * laneCounts.getValue(day), selectedIsCurrent && day == todayWeekday)
                    }
                }
                axes.sections.forEach { section ->
                    Row(Modifier.fillMaxWidth()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.width(sectionWidth).fillMaxHeight(),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("$section", style = MaterialTheme.typography.labelMedium, color = themePrimary())
                            }
                        }
                        axes.days.forEach { day ->
                            Surface(
                                color = if (selectedIsCurrent && day == todayWeekday) themeTodayColumn() else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.width(dayWidth * laneCounts.getValue(day)).fillMaxHeight(),
                            ) { }
                        }
                    }
                }
                placements.forEach { CourseCard(it) }
            }
            val dayPx = dayWidth.roundToPx()
            val sectionPx = sectionWidth.roundToPx()
            val width = sectionPx + dayPx * laneCounts.values.sum()
            val header = measurables[0].measure(Constraints.fixedWidth(width))
            // Measure real wrapped text, including FlowRow, before fixing every card to its span.
            // Intrinsic estimates can undercount wrapped time lines at larger font scales.
            val spans = placements.map { (it.course.endSection.coerceAtMost(11) - it.course.startSection + 1).coerceAtLeast(1) }
            val measuredCards = subcompose("measure") {
                placements.forEach { placement ->
                    Box(Modifier.clearAndSetSemantics {}) { CourseCard(placement) }
                }
            }.map { it.measure(Constraints.fixedWidth(dayPx)) }
            val periodHeight = placements.indices.fold(44.dp.roundToPx()) { height, index ->
                val required = measuredCards[index].height
                maxOf(height, (required + spans[index] - 1) / spans[index])
            }
            val heights = IntArray(axes.sections.size) { periodHeight }
            val cards = placements.indices.map { index ->
                measurables[1 + axes.sections.size + index].measure(Constraints.fixed(dayPx, periodHeight * spans[index]))
            }
            val tops = IntArray(heights.size)
            var totalHeight = header.height
            heights.forEachIndexed { index, height -> tops[index] = totalHeight; totalHeight += height }
            val strips = axes.sections.indices.map { index ->
                measurables[1 + index].measure(Constraints.fixed(width, heights[index]))
            }
            layout(constraints.constrainWidth(width), constraints.constrainHeight(totalHeight)) {
                header.placeRelative(0, 0)
                strips.forEachIndexed { index, strip -> strip.placeRelative(0, tops[index]) }
                cards.forEachIndexed { index, card ->
                    val placement = placements[index]
                    val group = placement.course
                    val precedingColumns = axes.days.takeWhile { it != group.weekday }.sumOf { laneCounts.getValue(it) }
                    card.placeRelative(sectionPx + (precedingColumns + placement.lane) * dayPx, tops[axes.sections.indexOf(group.startSection)])
                }
            }
        }
    }
    Text(
        "固定 11 节课 · 左右滑动查看一周 · 点击课程查看详情",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
