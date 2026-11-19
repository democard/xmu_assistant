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
        val hScroll = rememberScrollState()
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val dayPx = dayWidth.roundToPx()
            val sectionPx = sectionWidth.roundToPx()
            val totalDayWidth = dayPx * laneCounts.values.sum()
            val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else sectionPx + totalDayWidth

            // Measure real wrapped text, including FlowRow, before fixing every card to its span.
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
            val measuredHeader = subcompose("measureHeader") {
                Box(Modifier.clearAndSetSemantics {}) {
                    ScheduleGridHeaderCell("周一\n10/10", dayWidth, false)
                }
            }.single().measure(Constraints.fixedWidth(dayPx))
            val headerHeight = maxOf(44.dp.roundToPx(), measuredHeader.height)
            val totalHeight = headerHeight + periodHeight * axes.sections.size
            val headerHeightDp = headerHeight.toDp()
            val periodHeightDp = periodHeight.toDp()

            // Fixed left 1..11 section column (does not scroll horizontally)
            val leftColumn = subcompose("leftColumn") {
                Column(Modifier.width(sectionWidth)) {
                    ScheduleGridHeaderCell("节次", sectionWidth, false, Modifier.height(headerHeightDp))
                    axes.sections.forEach { section ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.width(sectionWidth).height(periodHeightDp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("$section", style = MaterialTheme.typography.labelMedium, color = themePrimary())
                            }
                        }
                    }
                }
            }.single().measure(Constraints.fixed(sectionPx, totalHeight))

            val rightViewportWidth = (layoutWidth - sectionPx).coerceAtLeast(dayPx)
            val rightScrollable = subcompose("rightGrid") {
                Box(Modifier.horizontalScroll(hScroll)) {
                    SubcomposeLayout { _ ->
                        val measurables = subcompose("daysGrid") {
                            Row {
                                axes.days.forEach { day ->
                                    val date = weekStart?.plusDays(day - 1L)?.let { "${it.monthValue}/${it.dayOfMonth}" }.orEmpty()
                                    ScheduleGridHeaderCell(
                                        "周${xmuWeekdayShort(day)}\n$date",
                                        dayWidth * laneCounts.getValue(day),
                                        selectedIsCurrent && day == todayWeekday,
                                        Modifier.height(headerHeightDp),
                                    )
                                }
                            }
                            axes.sections.forEach { _ ->
                                Row {
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
                        val headerPlaceable = measurables[0].measure(Constraints.fixed(totalDayWidth, headerHeight))
                        val stripPlaceables = axes.sections.indices.map { index ->
                            measurables[1 + index].measure(Constraints.fixed(totalDayWidth, periodHeight))
                        }
                        val cardPlaceables = placements.indices.map { index ->
                            measurables[1 + axes.sections.size + index].measure(
                                Constraints.fixed(dayPx, periodHeight * spans[index]),
                            )
                        }
                        layout(totalDayWidth, totalHeight) {
                            headerPlaceable.placeRelative(0, 0)
                            stripPlaceables.forEachIndexed { index, strip ->
                                strip.placeRelative(0, headerHeight + index * periodHeight)
                            }
                            cardPlaceables.forEachIndexed { index, card ->
                                val placement = placements[index]
                                val group = placement.course
                                val precedingColumns = axes.days.takeWhile { it != group.weekday }.sumOf { laneCounts.getValue(it) }
                                val sectionIdx = axes.sections.indexOf(group.startSection)
                                card.placeRelative((precedingColumns + placement.lane) * dayPx, headerHeight + sectionIdx * periodHeight)
                            }
                        }
                    }
                }
            }.single().measure(Constraints.fixed(rightViewportWidth, totalHeight))

            layout(constraints.constrainWidth(layoutWidth), constraints.constrainHeight(totalHeight)) {
                leftColumn.placeRelative(0, 0)
                rightScrollable.placeRelative(sectionPx, 0)
            }
        }
    }
    Text(
        "固定 11 节课 · 左右滑动查看一周 · 点击课程可修改安排",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
