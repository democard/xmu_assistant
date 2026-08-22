package com.xmu.assistant

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 课表页选中课程详情（XmuScheduleGroup?）的 rememberSaveable Saver：
 * 转屏恢复用，字段扁平化为可保存列表（含 variants 平行教学班，避免恢复后详情回退）。 */
private val scheduleGroupListSaver = listSaver<XmuScheduleGroup?, Any>(
    save = { group ->
        if (group == null) {
            listOf(false)
        } else {
            listOf(
                true,
                group.weekday,
                group.startSection,
                group.endSection,
                group.startTime,
                group.endTime,
                group.courseName,
                group.rooms,
                group.teachers,
                group.weeks,
                group.variants.map { variant ->
                    listOf(variant.room, variant.teacher, variant.weeks)
                },
            )
        }
    },
    restore = { saved ->
        if (saved.firstOrNull() != true) {
            null
        } else {
            val rawVariants: List<*> = (saved.getOrNull(10) as? List<*>) ?: emptyList<Any>()
            XmuScheduleGroup(
                weekday = saved[1] as Int,
                startSection = saved[2] as Int,
                endSection = saved[3] as Int,
                startTime = saved[4] as Int,
                endTime = saved[5] as Int,
                courseName = saved[6] as String,
                rooms = saved[7] as List<String>,
                teachers = saved[8] as List<String>,
                weeks = saved[9] as String,
                variants = rawVariants.mapNotNull { raw ->
                    val triple = raw as? List<*> ?: return@mapNotNull null
                    XmuScheduleVariant(
                        room = triple.getOrNull(0) as? String ?: "",
                        teacher = triple.getOrNull(1) as? String ?: "",
                        weeks = triple.getOrNull(2) as? String ?: "",
                    )
                },
            )
        }
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SchedulePage(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    updatedAtMillis: Long,
    loading: Boolean,
    refreshError: String,
    onRefresh: () -> Unit,
    inferredCalendar: XmuAcademicCalendar? = null,
    manualWeek: Int = 0,
) {
    val today = LocalDate.now()
    // 每分钟刷新当前时刻：跨过下课时刻后「正在上课」高亮/下一节课判断/置灰
    // 才能随时间自动更新（此前只在重组时取一次，页面停留期间状态陈旧）。
    var nowValue by remember {
        mutableIntStateOf(LocalTime.now().let { it.hour * 100 + it.minute })
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            nowValue = LocalTime.now().let { it.hour * 100 + it.minute }
        }
    }
    // 反推/手动校准结果优先（校准过即权威），官方校历表兜底：
    // 校准写入缓存后必须优先采用，否则页面仍显示旧官方表，校准失效。
    val calendar = remember(termCode, inferredCalendar) {
        inferredCalendar ?: xmuAcademicCalendarForTerm(termCode)
    }
    // 总周数兜底 19：与反推默认一致，避免 19 周长学期第 19 周课程被钳制丢失。
    val totalWeeks = calendar?.totalWeeks ?: 19
    // 手动周次只是「校准基准」：手动指定后系统已反推开学日（inferredCalendar 非空），
    // 此后按日期自动推进（明天同周、下周一自动 +1），无需每次手动更新；
    // 仅当手动校准反推失败（无校准日历）时才以手动周次兜底显示。
    val academicWeek = remember(calendar, today, manualWeek, totalWeeks) {
        if (manualWeek in 1..totalWeeks && inferredCalendar == null) {
            XmuAcademicWeek(
                phase = XmuTermPhase.DURING,
                week = manualWeek,
                totalWeeks = totalWeeks,
                date = today,
            )
        } else {
            xmuAcademicWeekFor(calendar, today)
        }
    }
    val defaultWeek = academicWeek.week ?: 1
    // rememberSaveable：转屏（Activity 重建）后保留用户选择的周/视图/日/课程详情，
    // 而不是重置回默认值。viewMode 枚举与 selectedCourse 用 name/自定义 Saver 保存。
    // 注意 key 只含 termCode：若把 defaultWeek 放进 key，跨午夜/磁盘缓存异步加载导致
    // defaultWeek 变化时，用户正浏览的周/日会被静默重置回默认周。
    var selectedWeek by rememberSaveable(termCode) { mutableIntStateOf(defaultWeek) }
    var viewModeName by rememberSaveable { mutableStateOf(ScheduleViewMode.WEEK.name) }
    // 从 viewModeName 派生：切换视图必须写 viewModeName（写入 State 触发重组），
    // 直接写 viewMode 只改局部变量，UI 不会更新（日程点不开的根因）。
    // valueOf 对非法持久化值（旧版本枚举/状态损坏）会抛异常，安全回退 WEEK。
    val viewMode: ScheduleViewMode = ScheduleViewMode.entries.firstOrNull { it.name == viewModeName }
        ?: ScheduleViewMode.WEEK
    var selectedDay by rememberSaveable(termCode) {
        mutableIntStateOf(if (academicWeek.week != null) xmuWeekdayFrom(today) else 1)
    }
    // defaultWeek 变化时的跟随策略：仅当用户仍停留在「旧的默认周」（未手动切周）才跟随
    // 新默认周（含启动时缓存异步就位 1→真实周次 的校正）；手动切到第 N 周则不抢。
    var lastDefaultWeek by remember(termCode) { mutableIntStateOf(defaultWeek) }
    LaunchedEffect(defaultWeek) {
        if (selectedWeek == lastDefaultWeek && selectedWeek != defaultWeek) {
            selectedWeek = defaultWeek
            selectedDay = if (academicWeek.week != null) xmuWeekdayFrom(today) else 1
        }
        lastDefaultWeek = defaultWeek
    }
    // 绑定 termCode：切换学期时清掉上一学期点开的课程详情，避免残留旧数据
    var selectedCourse by rememberSaveable(termCode, stateSaver = scheduleGroupListSaver) {
        mutableStateOf<XmuScheduleGroup?>(null)
    }
    // 点击课程后自动滚动到上方详情卡片（与课件页 reveal 同思路）。
    // 课表页外层是普通 verticalScroll Column（非 LazyColumn），所有内容一次组合，
    // BringIntoViewRequester 在这里有效（不会像 LazyColumn 那样对未组合节点静默失效）。
    val courseDetailsBringIntoViewRequester = remember { BringIntoViewRequester() }
    var courseRevealRequestId by remember { mutableIntStateOf(0) }

    fun selectCourseAndReveal(group: XmuScheduleGroup) {
        selectedCourse = group
        courseRevealRequestId += 1
    }

    LaunchedEffect(courseRevealRequestId) {
        if (courseRevealRequestId > 0 && selectedCourse != null) {
            // 等详情卡片完成组合与布局后滚动到它；快速连点多个课程时旧协程被取消，
            // 新协程以最新 requestId 重启，不会堆积。
            kotlinx.coroutines.yield()
            courseDetailsBringIntoViewRequester.bringIntoView()
        }
    }

    val clampedWeek = selectedWeek.coerceIn(1, totalWeeks)
    val weekIndex = remember(entries) { indexXmuScheduleByWeek(entries) }
    val weekGroups = remember(entries, weekIndex, clampedWeek) {
        (weekIndex[clampedWeek] ?: emptyList()).groupForDisplay()
    }
    val selectedWeekStart = remember(calendar, clampedWeek) { xmuWeekStart(calendar, clampedWeek) }
    val selectedIsCurrent = academicWeek.week == clampedWeek
    val todayWeekday = xmuWeekdayFrom(today)

    SectionCard("课表") {
        ScheduleAcademicHeader(
            calendar = calendar,
            academicWeek = academicWeek,
            selectedWeek = selectedWeek,
            today = today,
            loading = loading,
            onRefresh = onRefresh,
        )

        ScheduleWeekNavigator(
            selectedWeek = selectedWeek,
            totalWeeks = totalWeeks,
            currentWeek = academicWeek.week,
            onWeekSelected = {
                selectedWeek = it
                selectedCourse = null
                if (academicWeek.week == it) selectedDay = todayWeekday
            },
        )

        ScheduleModeToggle(viewMode = viewMode, onModeChanged = { viewModeName = it.name })

        RefreshStateBanner(
            loading = loading,
            errorMessage = refreshError,
            hasData = entries.isNotEmpty(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (updatedAtMillis > 0L) {
                Text(
                    formatMonitorTime(updatedAtMillis),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (entries.isEmpty()) {
            EmptyState("暂无课表", "点击右上角刷新，从教务系统读取原始排课数据。")
            return@SectionCard
        }

        ScheduleNextCourseCard(
            groups = weekGroups,
            selectedIsCurrent = selectedIsCurrent,
            todayWeekday = todayWeekday,
            nowValue = nowValue,
            phase = academicWeek.phase,
            onCourseSelected = ::selectCourseAndReveal,
        )

        selectedCourse?.let { group ->
            ScheduleCourseDetails(
                group = group,
                onClose = { selectedCourse = null },
                modifier = Modifier.bringIntoViewRequester(courseDetailsBringIntoViewRequester),
            )
        }

        if (weekGroups.isEmpty()) {
            EmptyState(
                "第${clampedWeek}周没有课程",
                if (selectedIsCurrent) "本周可以安心安排其他事项。" else "切换其他周查看课程安排。",
            )
            return@SectionCard
        }

        when (viewMode) {
            ScheduleViewMode.WEEK -> ScheduleWeekGrid(
                groups = weekGroups,
                weekStart = selectedWeekStart,
                selectedIsCurrent = selectedIsCurrent,
                todayWeekday = todayWeekday,
                nowValue = nowValue,
                onCourseSelected = ::selectCourseAndReveal,
            )
            ScheduleViewMode.AGENDA -> ScheduleAgendaView(
                groups = weekGroups,
                selectedDay = selectedDay,
                onDaySelected = { selectedDay = it },
                selectedIsCurrent = selectedIsCurrent,
                todayWeekday = todayWeekday,
                nowValue = nowValue,
                onCourseSelected = ::selectCourseAndReveal,
            )
        }
    }
}

private enum class ScheduleViewMode { WEEK, AGENDA }

/**
 * 开学前（BEFORE）阶段的标题文案：秋季学期（...1）前是暑假，
 * 春季学期（...2）前是寒假；夏季短学期（...3）开学前是学期间隙（晚春/初夏），
 * 不能标"寒假"；未知学期显示通用「假期中」。
 */
private fun beforeTermLabel(calendar: XmuAcademicCalendar?): String = when (calendar?.termCode?.lastOrNull()) {
    '1' -> "暑假中"
    '2' -> "寒假中"
    '3' -> "短学期尚未开始"
    else -> "假期中"
}

@Composable
private fun ScheduleAcademicHeader(
    calendar: XmuAcademicCalendar?,
    academicWeek: XmuAcademicWeek,
    selectedWeek: Int,
    today: LocalDate,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val headline = when (academicWeek.phase) {
        XmuTermPhase.BEFORE -> beforeTermLabel(calendar)
        XmuTermPhase.DURING -> if (academicWeek.week == selectedWeek) "第${selectedWeek}周 · 本周" else "第${selectedWeek}周"
        XmuTermPhase.AFTER -> "学期已结束"
        XmuTermPhase.UNKNOWN -> "第${selectedWeek}周"
    }
    val supporting = when (academicWeek.phase) {
        XmuTermPhase.BEFORE -> {
            val days = calendar?.let { ChronoUnit.DAYS.between(today, it.startDate) }
            if (days != null && days >= 0) "距离开学${days}天 · 正在预览第${selectedWeek}周" else "正在预览第${selectedWeek}周"
        }
        XmuTermPhase.AFTER -> "可切换周次回顾本学期课程"
        else -> xmuWeekDateRange(calendar, selectedWeek)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = themePrimary())
            Text(supporting, style = MaterialTheme.typography.bodyMedium)
            Text(
                calendar?.displayLabel ?: "学期代码 ${calendar?.termCode.orEmpty().ifBlank { "待识别" }}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(if (loading) "…" else "刷新")
        }
    }
}

@Composable
private fun ScheduleWeekNavigator(
    selectedWeek: Int,
    totalWeeks: Int,
    currentWeek: Int?,
    onWeekSelected: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { onWeekSelected((selectedWeek - 1).coerceAtLeast(1)) },
                enabled = selectedWeek > 1,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val first = (selectedWeek - 2).coerceAtLeast(1)
                val last = (selectedWeek + 2).coerceAtMost(totalWeeks)
                (first..last).forEach { week ->
                    val active = week == selectedWeek
                    Surface(
                        color = if (active) themeSelectedChip() else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (active) Color.White else themePrimary(),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, if (active) themeSelectedChip() else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { onWeekSelected(week) },
                    ) {
                        // Box 垂直居中：defaultMinSize(48dp) 撑高后文字必须居中而非顶部对齐
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(
                                if (week == currentWeek) "第${week}周·本周" else "第${week}周",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { onWeekSelected((selectedWeek + 1).coerceAtMost(totalWeeks)) },
                enabled = selectedWeek < totalWeeks,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (currentWeek != null && selectedWeek != currentWeek) {
            Text(
                "回到第${currentWeek}周（本周）",
                color = themePrimary(),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable { onWeekSelected(currentWeek) }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ScheduleModeToggle(
    viewMode: ScheduleViewMode,
    onModeChanged: (ScheduleViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            ScheduleViewMode.WEEK to "周视图",
            ScheduleViewMode.AGENDA to "日程",
        ).forEach { (mode, label) ->
            val selected = viewMode == mode
            Surface(
                color = if (selected) themeSurface() else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable { onModeChanged(mode) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (selected) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleNextCourseCard(
    groups: List<XmuScheduleGroup>,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    phase: XmuTermPhase,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    if (!selectedIsCurrent || phase != XmuTermPhase.DURING) return
    val todayGroups = groups.filter { it.weekday == todayWeekday }.sortedBy { it.startTime }
    val currentOrNext = todayGroups.firstOrNull { it.endTime >= nowValue }
    val title = when {
        todayGroups.isEmpty() -> "今天没有课"
        currentOrNext == null -> "今天的课程已结束"
        currentOrNext.startTime <= nowValue -> "正在上课"
        else -> "下一节课"
    }
    Surface(
        color = if (currentOrNext?.startTime?.let { it <= nowValue } == true) themeSoftGreen() else themePrimarySoft(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (currentOrNext != null) base.clickable { onCourseSelected(currentOrNext) } else base },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = themePrimary(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            currentOrNext?.let { group ->
                Text(group.courseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${formatXmuTime(group.startTime)}—${formatXmuTime(group.endTime)} · " +
                        scheduleLocationSummary(group),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ScheduleWeekGrid(
    groups: List<XmuScheduleGroup>,
    weekStart: LocalDate?,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    // 一天最多 11 节课，网格固定渲染到第 11 节
    val maxSection = maxOf(11, groups.maxOfOrNull { it.endSection } ?: 11)
    val scrollState = rememberScrollState()
    val sectionHeight = 76.dp
    val dayWidth = 100.dp
    val sectionWidth = 56.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
    ) {
        Row {
            ScheduleGridHeaderCell("节次", sectionWidth, highlighted = false)
            (1..7).forEach { weekday ->
                val dateLabel = weekStart?.plusDays((weekday - 1).toLong())?.let { "${it.monthValue}/${it.dayOfMonth}" }.orEmpty()
                ScheduleGridHeaderCell(
                    text = "周${xmuWeekdayShort(weekday)}\n$dateLabel",
                    width = dayWidth,
                    highlighted = selectedIsCurrent && weekday == todayWeekday,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(sectionWidth + dayWidth * 7)
                .height(sectionHeight * maxSection),
        ) {
            Column {
                (1..maxSection).forEach { section ->
                    Row {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .width(sectionWidth)
                                .height(sectionHeight),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$section", fontWeight = FontWeight.Bold, color = themePrimary())
                                Text("节", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        (1..7).forEach { weekday ->
                            Surface(
                                color = if (selectedIsCurrent && weekday == todayWeekday) themeTodayColumn() else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .width(dayWidth)
                                    .height(sectionHeight),
                            ) {}
                        }
                    }
                }
            }
            groups.forEach { group ->
                val safeStart = group.startSection.coerceIn(1, maxSection)
                val safeEnd = group.endSection.coerceIn(safeStart, maxSection)
                val isPast = selectedIsCurrent && group.weekday == todayWeekday && group.endTime < nowValue
                val span = safeEnd - safeStart + 1
                ScheduleGridCourseBlock(
                    group = group,
                    dimmed = isPast,
                    inProgress = selectedIsCurrent &&
                        group.weekday == todayWeekday &&
                        group.startTime <= nowValue &&
                        group.endTime >= nowValue,
                    onClick = { onCourseSelected(group) },
                    modifier = Modifier
                        .offset(
                            x = sectionWidth + dayWidth * (group.weekday.coerceIn(1, 7) - 1),
                            y = sectionHeight * (safeStart - 1),
                        )
                        .width(dayWidth)
                        // 高度自适应内容：格子不够高时课程块自动变高，保证课程名/地点完整显示（无省略号）。
                        // 极端超长课程名可能盖住下方格子：zIndex 按开始节次分层，后开始的课程在上层，
                        // 保证点击命中与文字优先可见（完整显示优先于紧凑，属用户要求的取舍）。
                        .zIndex(safeStart.toFloat())
                        .heightIn(min = sectionHeight * span)
                        .padding(3.dp),
                )
            }
        }
    }
    Text(
        "左右滑动查看一周 · 点击课程查看完整信息",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ScheduleGridHeaderCell(text: String, width: androidx.compose.ui.unit.Dp, highlighted: Boolean) {
    Surface(
        color = if (highlighted) themeSelectedChip() else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) Color.White else themePrimary(),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(width)
            .height(54.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
        }
    }
}

@Composable
internal fun ScheduleGridCourseBlock(
    group: XmuScheduleGroup,
    dimmed: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = scheduleCourseColor(group.courseName)
    val dark = LocalXmuDarkTheme.current
    // 深色模式：课程色底加深、文字改浅，避免半透明色叠深底后深字不可读
    val blockColor = if (dark) color.copy(alpha = 0.28f) else color.copy(alpha = 0.18f)
    // inProgress 在深色下也换深底浅字：浅绿底 + 近白字对比不足（审查 M2）
    val inProgressBlock = if (dark) Color(0xFF1E3A32) else Color(0xFFD9F5E7)
    val textColor = if (dark) Color(0xFFE3EBF3) else Color(0xFF16283A)
    val detailColor = if (dark) Color(0xFF9FB0C0) else Color(0xFF425B72)
    Surface(
        color = if (inProgress) inProgressBlock else blockColor,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(if (inProgress) 2.dp else 1.dp, if (inProgress) AppSuccess else color.copy(alpha = 0.55f)),
        modifier = modifier
            .alpha(if (dimmed) 0.48f else 1f)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // 课程名完整显示，不省略：内容多时块随 heightIn(min) 自适应变高
            Text(
                group.courseName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            Text(
                "${group.startSection}-${group.endSection}节",
                style = MaterialTheme.typography.labelSmall,
                color = detailColor,
            )
            Text(
                scheduleLocationSummary(group),
                style = MaterialTheme.typography.labelSmall,
                color = detailColor,
            )
        }
    }
}

@Composable
private fun ScheduleAgendaView(
    groups: List<XmuScheduleGroup>,
    selectedDay: Int,
    onDaySelected: (Int) -> Unit,
    selectedIsCurrent: Boolean,
    todayWeekday: Int,
    nowValue: Int,
    onCourseSelected: (XmuScheduleGroup) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (1..7).forEach { weekday ->
            val count = groups.count { it.weekday == weekday }
            val active = selectedDay == weekday
            Surface(
                color = if (active) themeSelectedChip() else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (active) Color.White else themePrimary(),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable { onDaySelected(weekday) },
            ) {
                Text(
                    "周${xmuWeekdayShort(weekday)} $count",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
    val dayGroups = groups.filter { it.weekday == selectedDay }.sortedBy { it.startTime }
    if (dayGroups.isEmpty()) {
        EmptyState("周${xmuWeekdayShort(selectedDay)}没有课", "选择其他日期查看本周课程。")
        return
    }
    dayGroups.forEach { group ->
        val isPast = selectedIsCurrent && selectedDay == todayWeekday && group.endTime < nowValue
        val inProgress = selectedIsCurrent &&
            selectedDay == todayWeekday &&
            group.startTime <= nowValue &&
            group.endTime >= nowValue
        Surface(
            color = if (inProgress) themeSoftGreen() else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(if (inProgress) 2.dp else 1.dp, if (inProgress) AppSuccess else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isPast) 0.5f else 1f)
                .clickable { onCourseSelected(group) },
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.width(58.dp)) {
                    Text(formatXmuTime(group.startTime), color = themePrimary(), fontWeight = FontWeight.Bold)
                    Text(formatXmuTime(group.endTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(64.dp)
                        .background(scheduleCourseColor(group.courseName), RoundedCornerShape(999.dp)),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.courseName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${group.startSection}-${group.endSection}节",
                            style = MaterialTheme.typography.labelMedium,
                            color = themePrimary(),
                        )
                    }
                    Text(
                        scheduleLocationSummary(group),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        group.weeks.ifBlank { "周次未标注" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleCourseDetails(
    group: XmuScheduleGroup,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(container = themeInfoCard(), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.courseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "收起",
                color = themePrimary(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(10.dp),
            )
        }
        Text("${weekdayName(group.weekday)} · ${formatXmuTime(group.startTime)}—${formatXmuTime(group.endTime)}")
        Text("节次：${group.startSection}—${group.endSection}节")
        Text("周次：${group.weeks.ifBlank { "未标注" }}")
        if (group.variants.size > 1) {
            Text("教学班安排：${group.variants.size} 个")
            group.variants.forEach { variant ->
                Text(
                    "· ${variant.weeks.ifBlank { "周次未标注" }} · " +
                        "${variant.room.ifBlank { "教室未标注" }} · " +
                        "${variant.teacher.ifBlank { "教师未标注" }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text("教室：${group.rooms.joinToString("、").ifBlank { "未标注" }}")
            Text("教师：${group.teachers.joinToString("、").ifBlank { "未标注" }}")
        }
    }
}

private val SCHEDULE_COLOR_PALETTE = listOf(
    Color(0xFF2F6FA3),
    Color(0xFF16866A),
    Color(0xFF9B5C22),
    Color(0xFF7654A8),
    Color(0xFFB34F64),
    Color(0xFF397C8C),
)

internal fun scheduleCourseColor(courseName: String): Color {
    val index = (courseName.hashCode().toLong() and 0x7FFFFFFF).toInt() % SCHEDULE_COLOR_PALETTE.size
    return SCHEDULE_COLOR_PALETTE[index]
}

internal fun scheduleLocationSummary(group: XmuScheduleGroup): String = when {
    group.rooms.isEmpty() -> "教室未标注"
    group.rooms.size == 1 -> group.rooms.first()
    else -> "${group.rooms.first()} 等${group.rooms.size}个教室"
}

internal fun weekdayName(weekday: Int): String = when (weekday) {
    1 -> "星期一"
    2 -> "星期二"
    3 -> "星期三"
    4 -> "星期四"
    5 -> "星期五"
    6 -> "星期六"
    7 -> "星期日"
    else -> "星期$weekday"
}
