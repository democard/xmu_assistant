package com.xmu.assistant

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePage(
    entries: List<XmuScheduleEntry>,
    termCode: String,
    updatedAtMillis: Long,
    loading: Boolean,
    refreshError: String,
    loggedIn: Boolean = false,
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
    fun selectCourseAndReveal(group: XmuScheduleGroup) {
        selectedCourse = group
    }

    val clampedWeek = selectedWeek.coerceIn(1, totalWeeks)
    val weekIndex = remember(entries) { indexXmuScheduleByWeek(entries) }
    val weekGroups = remember(entries, weekIndex, clampedWeek) {
        (weekIndex[clampedWeek] ?: emptyList()).groupForDisplay()
    }
    val selectedWeekStart = remember(calendar, clampedWeek) { xmuWeekStart(calendar, clampedWeek) }
    val selectedIsCurrent = academicWeek.week == clampedWeek
    val todayWeekday = xmuWeekdayFrom(today)

    // ICS 导出走 cacheDir → FileProvider → 系统分享（日历/邮件应用均可导入）；
    // 文件很小（<200KB），生成与落盘放 IO 协程只为规避主线程磁盘 StrictMode 告警。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    fun exportScheduleIcs() {
        val activeCalendar = calendar ?: run {
            Toast.makeText(context, "开学日待校准，先在课表页校准周次再导出", Toast.LENGTH_LONG).show()
            return
        }
        exporting = true
        scope.launch(Dispatchers.IO) {
            try {
                val text = buildScheduleIcs(entries, activeCalendar)
                    ?: error("开学日待校准，暂无法换算上课日期")
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "xmu课表_${activeCalendar.termCode}.ics")
                file.writeText(text)
                withContext(Dispatchers.Main) {
                    exporting = false
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/calendar"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "分享课表日历"))
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    exporting = false
                    Toast.makeText(context, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    selectedCourse?.let { group ->
        ModalBottomSheet(onDismissRequest = { selectedCourse = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SelectionContainer { ScheduleCourseDetails(group, onClose = { selectedCourse = null }) }
            }
        }
    }
    SectionCard("课表", showTitle = false) {
        ScheduleAcademicHeader(
            calendar = calendar,
            academicWeek = academicWeek,
            selectedWeek = selectedWeek,
            today = today,
            loading = loading,
            onRefresh = onRefresh,
            onExport = ::exportScheduleIcs,
            canExport = !exporting && entries.isNotEmpty(),
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

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ScheduleModeToggle(viewMode = viewMode, onModeChanged = { viewModeName = it.name })
            }
            TextButton(onClick = {
                selectedWeek = defaultWeek
                selectedDay = todayWeekday
                viewModeName = ScheduleViewMode.AGENDA.name
                selectedCourse = null
            }, enabled = academicWeek.week != null) {
                Text("今天")
            }
        }

        RefreshStateBanner(
            loading = loading,
            errorMessage = refreshError,
            hasData = entries.isNotEmpty(),
        )

        if (entries.isEmpty()) {
            // 空态三档之「未登录」：登出后清空缓存时给出登录引导而非"暂无数据"
            if (!loggedIn) {
                EmptyState("请先登录", "登录后点击刷新即可获取课表。")
            } else {
                EmptyState("暂无课表", "点击右上角刷新，从教务系统读取原始排课数据。")
            }
            return@SectionCard
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
        if (viewMode == ScheduleViewMode.WEEK) ScheduleNextCourseCard(
            groups = weekGroups,
            selectedIsCurrent = selectedIsCurrent,
            todayWeekday = todayWeekday,
            nowValue = nowValue,
            phase = academicWeek.phase,
            onCourseSelected = ::selectCourseAndReveal,
        )
        Text(
            listOfNotNull(calendar?.displayLabel, updatedAtMillis.takeIf { it > 0 }?.let(::formatMonitorTime)).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
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
    onExport: () -> Unit,
    canExport: Boolean,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val headline = when (academicWeek.phase) {
        XmuTermPhase.BEFORE -> beforeTermLabel(calendar)
        XmuTermPhase.DURING -> if (academicWeek.week == selectedWeek) "第${selectedWeek}周 · 本周" else "第${selectedWeek}周 · 非本周"
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
            Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = themePrimary())
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Box {
            TextButton(onClick = { actionsExpanded = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("更多") }
            DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                DropdownMenuItem(text = { Text("导出日历") }, enabled = canExport, onClick = { actionsExpanded = false; onExport() })
            }
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
    var choosingWeek by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onWeekSelected(selectedWeek - 1) }, enabled = selectedWeek > 1,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { choosingWeek = true }, modifier = Modifier.fillMaxWidth()) {
                Text("第${selectedWeek}周 ▾", fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = choosingWeek, onDismissRequest = { choosingWeek = false }) {
                (1..totalWeeks).forEach { week ->
                    DropdownMenuItem(
                        text = { Text(if (week == currentWeek) "第${week}周 · 本周" else "第${week}周") },
                        onClick = { choosingWeek = false; onWeekSelected(week) },
                    )
                }
            }
        }
        if (currentWeek != null && selectedWeek != currentWeek) {
            TextButton(onClick = { onWeekSelected(currentWeek) }) { Text("本周") }
        }
        OutlinedButton(
            onClick = { onWeekSelected(selectedWeek + 1) }, enabled = selectedWeek < totalWeeks,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("›", style = MaterialTheme.typography.titleLarge) }
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
        listOf(ScheduleViewMode.WEEK to "周课表", ScheduleViewMode.AGENDA to "日程").forEach { (mode, label) ->
            val selected = viewMode == mode
            Surface(
                color = if (selected) themeSurface() else Color.Transparent,
                // 与全应用使用一致的选中态配色。
                shape = RoundedCornerShape(999.dp),
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
internal fun ScheduleGridHeaderCell(text: String, width: androidx.compose.ui.unit.Dp, highlighted: Boolean) {
    Surface(
        color = if (highlighted) themeSelectedChip() else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) Color.White else themePrimary(),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .width(width)
            .heightIn(min = 44.dp),
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
@OptIn(ExperimentalLayoutApi::class)
internal fun ScheduleGridCourseBlock(
    group: XmuScheduleGroup,
    dimmed: Boolean,
    inProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlapping: Boolean = false,
) {
    val color = scheduleCourseColor(group.courseName)
    val dark = LocalXmuDarkTheme.current
    // 深色模式：课程色底加深、文字改浅，避免半透明色叠深底后深字不可读
    val blockColor = color.copy(alpha = if (dark) 0.28f else 0.18f).compositeOver(MaterialTheme.colorScheme.surface)
    // inProgress 在深色下也换深底浅字：浅绿底 + 近白字对比不足（审查 M2）
    val inProgressBlock = if (dark) Color(0xFF1E3A32) else Color(0xFFD9F5E7)
    val textColor = if (dark) Color(0xFFE3EBF3) else Color(0xFF16283A)
    val detailColor = if (dark) Color(0xFF9FB0C0) else Color(0xFF425B72)
    Surface(
        color = if (inProgress) inProgressBlock else blockColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (inProgress) 2.dp else 1.dp, if (inProgress) AppSuccess else color.copy(alpha = 0.55f)),
        modifier = modifier
            .alpha(if (dimmed) 0.48f else 1f)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (overlapping) Text("时间重叠", style = MaterialTheme.typography.labelSmall, color = themeWarning())
            // 完整显示文字，网格按实际换行测量后，统一全部节次的行高。
            Text(
                group.courseName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            Text(
                "${group.startSection}-${group.endSection}节",
                style = MaterialTheme.typography.labelSmall,
                color = detailColor,
            )
            FlowRow {
                Text(formatXmuTime(group.startTime), style = MaterialTheme.typography.labelSmall, color = detailColor)
                Text("–${formatXmuTime(group.endTime)}", style = MaterialTheme.typography.labelSmall, color = detailColor)
            }
            Text(
                scheduleLocationSummary(group),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
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
            Text("教室：\n${scheduleLocationSummary(group)}")
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

internal fun scheduleLocationSummary(group: XmuScheduleGroup): String =
    group.rooms.map { it.trim() }.filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }.distinct().joinToString("\n").ifBlank { "教室未标注" }

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
