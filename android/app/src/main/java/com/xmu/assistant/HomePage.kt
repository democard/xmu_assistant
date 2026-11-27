package com.xmu.assistant

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

@Suppress("UNUSED_PARAMETER")
@Composable
fun HomePage(
    username: String,
    password: String,
    loggedIn: Boolean,
    monitorRunning: Boolean,
    accountTransitionInProgress: Boolean,
    monitorTransitionInProgress: Boolean,
    monitorStatus: String,
    monitorLastCheck: String,
    monitorFailureCount: Int,
    monitorLastError: String,
    rollcallSettings: RollcallSettings,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    onNavigate: (String) -> Unit,
    currentRollcalls: List<RollcallEvent> = emptyList(),
    rollcallLoading: Boolean = false,
    rollcallError: String = "",
    rollcallUpdatedAtMillis: Long = 0L,
    scheduleEntries: List<XmuScheduleEntry> = emptyList(),
    scheduleTermCode: String = "",
    inferredCalendar: XmuAcademicCalendar? = null,
    manualAcademicWeek: Int = 0,
    upcomingExams: List<XmuExam> = emptyList(),
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val canLogin = username.isNotBlank() && password.isNotBlank() && !accountTransitionInProgress

    var nowValue by remember {
        mutableIntStateOf(LocalTime.now().let { it.hour * 100 + it.minute })
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            nowValue = LocalTime.now().let { it.hour * 100 + it.minute }
        }
    }

    val today = LocalDate.now()
    val todayWeekday = xmuWeekdayFrom(today)
    val calendar = remember(scheduleTermCode, inferredCalendar) {
        inferredCalendar ?: xmuAcademicCalendarForTerm(scheduleTermCode)
    }
    val totalWeeks = calendar?.totalWeeks ?: 19
    val academicWeek = remember(calendar, today, manualAcademicWeek, totalWeeks) {
        if (manualAcademicWeek in 1..totalWeeks && inferredCalendar == null) {
            XmuAcademicWeek(
                phase = XmuTermPhase.DURING,
                week = manualAcademicWeek,
                totalWeeks = totalWeeks,
                date = today,
            )
        } else {
            xmuAcademicWeekFor(calendar, today)
        }
    }
    val overrideStore = remember(context) { ScheduleOverrideStore(context) }
    val todayGroups = remember(scheduleEntries, scheduleTermCode, academicWeek.week, todayWeekday) {
        val currentWeek = academicWeek.week
        if (scheduleEntries.isEmpty() || currentWeek == null) {
            emptyList()
        } else {
            val effectiveEntries = overrideStore.applyToEntries(scheduleTermCode, scheduleEntries)
            val weekIndex = indexXmuScheduleByWeek(effectiveEntries)
            val visibleEntries = (weekIndex[currentWeek] ?: emptyList()).toSet()
            scheduleEntries.zip(effectiveEntries)
                .filter { (_, effective) -> effective in visibleEntries }
                .map { (original, _) -> original }
                .groupForDisplay()
                .map { base -> overrideStore.apply(scheduleTermCode, base) }
                .filter { it.weekday == todayWeekday }
                .sortedWith(compareBy<XmuScheduleGroup> { it.startTime }.thenBy { it.startSection }.thenBy { it.courseName })
        }
    }
    val upcomingToday = remember(todayGroups, nowValue) {
        todayGroups.filter { it.endTime >= nowValue }
    }
    val nearestExam = upcomingExams.firstOrNull()

    fun submitLogin() {
        if (canLogin) {
            focus.clearFocus()
            onLogin()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 1. 顶部监控状态卡（精简紧凑两段式：顶部周次状态 + 设置，下方核心状态 + 主开关）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppHeaderBrush, RoundedCornerShape(16.dp))
                .padding(horizontal = 15.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!loggedIn) {
                Text(
                    "XMU / 校园日常",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFCDE5F2),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "校园日常，从这里开始",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "登录后查看课表、成绩、考试、课件与签到提醒。",
                    color = Color(0xFFD7E8F2),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    color = if (monitorRunning) Color(0xFF8CD8B0) else Color(0xFFC5D5E2),
                                    shape = RoundedCornerShape(999.dp),
                                ),
                        )
                        val contextPrefix = academicWeek.week?.let { "第${it}周 周${xmuWeekdayShort(todayWeekday)}" } ?: "XMU"
                        Text(
                            "$contextPrefix · ${if (monitorRunning) "监控运行中" else "监控未启动"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFE3F0F7),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        onClick = { onNavigate("策略") },
                        color = Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    ) {
                        Text(
                            "监控设置",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (monitorRunning) "课堂签到监听中" else "开启签到提醒",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (monitorRunning) {
                                "同步于 $monitorLastCheck · 间隔 ${rollcallSettings.pollIntervalSeconds} 秒"
                            } else {
                                "检测到新签到时第一时间通知"
                            },
                            color = Color(0xFFD7E8F2),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = if (monitorRunning) onStopMonitor else onStartMonitor,
                        enabled = !monitorTransitionInProgress && !accountTransitionInProgress,
                        modifier = Modifier.heightIn(min = 36.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = AppPrimary,
                        ),
                    ) {
                        Text(
                            if (monitorTransitionInProgress) "正在切换…" else if (monitorRunning) "暂停监控" else "启动监控",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (!loggedIn) {
            PreviewCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("登录校园账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PreviewBadge("仅保存在本机")
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsername,
                    label = { Text("学号") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !accountTransitionInProgress,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !accountTransitionInProgress,
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "隐藏" else "显示")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                )
                Button(
                    onClick = { submitLogin() },
                    enabled = canLogin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                ) {
                    Text(if (accountTransitionInProgress) "正在登录…" else "登录")
                }
                Text(
                    "用于读取课表、成绩、考试、课件与签到提醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PreviewCard(soft = true) {
                PreviewListRow(
                    R.drawable.ic_nav_check,
                    "账号数据留在手机",
                    "密码、Cookie 与通知配置不会上传到其他服务器。",
                    trailing = {},
                )
            }
        } else {
            if (monitorFailureCount > 0) {
                RefreshStateBanner(
                    loading = false,
                    errorMessage = monitorLastError.ifBlank { "最近检查失败，请检查网络与后台设置。" },
                    hasData = false,
                )
            }
            if (rollcallError.isNotBlank()) {
                RefreshStateBanner(false, rollcallError, currentRollcalls.isNotEmpty())
            }

            // 2. 当前签到提醒（独立于 Hero 之外，仅在有进行中签到时显示）
            val current = currentRollcalls.firstOrNull()
            if (current != null) {
                val extra = if (currentRollcalls.size > 1) " 等 ${currentRollcalls.size} 项" else ""
                Surface(
                    onClick = { onNavigate("畅课签到") },
                    color = themeSoftAmber(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, themeOnAmber().copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "进行中：${current.courseTitle} / ${current.type}$extra",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${current.teacher.takeIf { it.isNotBlank() }?.let { "发起人：$it · " }.orEmpty()}请前往畅课完成签到",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PreviewBadge("去查看 ›", PreviewBadgeTone.AMBER)
                    }
                }
            }

            // 3. 今日课程（最多展示 2 节当前/待上课程，结构饱满）
            PreviewCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("今日课程", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    when {
                        scheduleEntries.isEmpty() -> PreviewBadge("未同步", PreviewBadgeTone.AMBER)
                        todayGroups.isEmpty() -> PreviewBadge("今日无课", PreviewBadgeTone.BLUE)
                        else -> PreviewBadge("共 ${todayGroups.size} 节", PreviewBadgeTone.BLUE)
                    }
                    Text(
                        "完整课表 ›",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = themePrimary(),
                        modifier = Modifier.clickable { onNavigate("课表") },
                    )
                }
                when {
                    scheduleEntries.isEmpty() -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "尚未同步课表数据",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { onNavigate("课表") },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) { Text("去同步 ›") }
                        }
                    }
                    todayGroups.isEmpty() -> {
                        Text(
                            "今天没有排课，可自习或提前下载课程资料。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    upcomingToday.isEmpty() -> {
                        Text(
                            "今日 ${todayGroups.size} 节课程已全部结束。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        upcomingToday.take(2).forEach { group ->
                            HomeTodayCourseRow(
                                group = group,
                                nowValue = nowValue,
                                onClick = { onNavigate("课表") },
                            )
                        }
                        if (upcomingToday.size > 2) {
                            Text(
                                "还有 ${upcomingToday.size - 2} 节待上 · 点击完整课表查看",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { onNavigate("课表") },
                            )
                        }
                    }
                }
            }

            // 4. 近期考试（有层次的提醒卡）
            if (nearestExam != null) {
                HomeExamCard(
                    exam = nearestExam,
                    today = today,
                    onClick = { onNavigate("考试安排") },
                )
            }

            // 5. 常用功能 2×2 卡片网格（图标 + 标题 + 副标题）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HomeShortcutTile("课件", "课程资料下载", R.drawable.ic_nav_folder, Modifier.weight(1f)) { onNavigate("课程课件") }
                    HomeShortcutTile("考试", "考场与倒计时", R.drawable.ic_nav_calendar, Modifier.weight(1f)) { onNavigate("考试安排") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    HomeShortcutTile("通知", "校内公告通知", R.drawable.ic_nav_bell, Modifier.weight(1f)) { onNavigate("通知") }
                    HomeShortcutTile("教程", "监控与保活说明", R.drawable.ic_nav_help, Modifier.weight(1f)) { onNavigate("教程") }
                }
            }

            // 6. 账号与后台状态
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "${maskHomeUsername(username)} · 已登录",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onOpenBackgroundSettings,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("后台权限", style = MaterialTheme.typography.labelMedium) }
                    TextButton(
                        onClick = { confirmLogout = true },
                        enabled = !accountTransitionInProgress,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("退出登录", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }

    if (confirmLogout && loggedIn) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出当前账号？") },
            text = { Text("退出后将暂停监控。再次登录即可继续使用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        onLogout()
                    },
                    enabled = !accountTransitionInProgress,
                ) { Text("确认退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun HomeTodayCourseRow(
    group: XmuScheduleGroup,
    nowValue: Int,
    onClick: () -> Unit,
) {
    val inProgress = group.startTime <= nowValue && group.endTime >= nowValue
    val room = scheduleLocationSummary(group).replace("\n", " / ")
    val accent = scheduleCourseColor(group.courseName)
    Surface(
        onClick = onClick,
        color = if (inProgress) themeSoftGreen() else themeInfoCard(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (inProgress) 1.5.dp else 1.dp,
            color = if (inProgress) AppSuccess else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .background(accent, RoundedCornerShape(999.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    group.courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatXmuTime(group.startTime)}–${formatXmuTime(group.endTime)} · $room",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PreviewBadge(
                text = if (inProgress) "上课中" else "${group.startSection}-${group.endSection}节",
                tone = if (inProgress) PreviewBadgeTone.GREEN else PreviewBadgeTone.BLUE,
            )
        }
    }
}

@Composable
private fun HomeExamCard(
    exam: XmuExam,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val daysUntil = remember(exam.date, today) {
        runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(exam.date)) }.getOrNull()
    }
    val countdownLabel = when {
        daysUntil == null -> ""
        daysUntil <= 0L -> "今天开考"
        daysUntil == 1L -> "明天"
        else -> "${daysUntil}天后"
    }
    val countdownTone = if (daysUntil != null && daysUntil <= 3L) PreviewBadgeTone.RED else PreviewBadgeTone.AMBER
    val shortDate = exam.date.substringAfter('-')
    val startTime = exam.timeRange.substringBefore('-')
    val location = exam.room.ifBlank { exam.mode }

    PreviewCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("近期考试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (countdownLabel.isNotBlank()) PreviewBadge(countdownLabel, countdownTone)
            Text(
                "全部 ›",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = themePrimary(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                exam.courseName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                listOfNotNull("$shortDate $startTime".trim().takeIf { it.isNotBlank() }, location.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeShortcutTile(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PreviewIconBox(icon, Modifier.size(34.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun maskHomeUsername(username: String): String {
    val trimmed = username.trim()
    return when {
        trimmed.length >= 8 -> trimmed.take(3) + "****" + trimmed.takeLast(4)
        trimmed.length >= 4 -> trimmed.take(2) + "**" + trimmed.takeLast(1)
        else -> trimmed
    }
}
