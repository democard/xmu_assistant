package com.xmu.assistant

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun TutorialPage(scrollState: androidx.compose.foundation.ScrollState, navigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewCard(soft = true) {
            PreviewListRow(R.drawable.ic_nav_help, "你想完成什么？", "按场景直达对应页面，快速上手全部功能", trailing = {})
        }
        val tasks = listOf(
            Triple("01", "登录与签到监控", "校园账号本机加密保存；支持下拉通知栏「签到监控」磁贴与新签到提醒") to "首页",
            Triple("02", "查看签到与打开畅课", "实时查看签到进度与出勤记录，并可直接跳转畅课页面完成签到") to "签到情况",
            Triple("03", "微信与 QQ 邮箱提醒", "开启本机通知、微信 PushPlus 或免费的 QQ 邮箱 SMTP 授权码提醒") to "通知",
            Triple("04", "看课表、改安排与导出日历", "支持周课表/日程切换、本地微调上课教室与节次、导出 .ics 系统日历") to "课表",
            Triple("05", "监控间隔、小卡片与外观", "在策略页设置轮询间隔、校准当前周次、添加今日课程桌面小卡片并切换深色模式") to "策略",
            Triple("06", "查看成绩、排名与模拟", "固定成绩概览、长图分享、专业排名证明以及实时加权成绩模拟") to "成绩",
            Triple("07", "课程课件与考试安排", "按学年/学期筛选课程批量下载课件，查看考试倒计时并开启考前强提醒") to "课程课件",
        )
        tasks.forEach { (task, target) ->
            PreviewCard(onClick = { navigate(target) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                        Text(task.first, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(task.second, fontWeight = FontWeight.Bold)
                        Text(task.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("前往设置 ›", style = MaterialTheme.typography.labelMedium, color = themePrimary(), modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
        PreviewCard {
            PreviewSectionHeader("后台保活与常见问题", "4 项")
            PreviewListRow(R.drawable.ic_nav_bell, "后台保活建议", "请在系统电池设置中允许「无限制 / 允许后台运行」，并保持通知权限开启", trailing = {})
            PreviewListRow(R.drawable.ic_nav_check, "下拉磁贴快捷开关", "下拉系统快捷设置面板编辑添加「签到监控」磁贴，无需打开 App 即可启停", trailing = {})
            PreviewListRow(R.drawable.ic_nav_help, "课程或成绩刷不出来", "回到首页重新登录后再手动刷新对应页面", trailing = {})
            PreviewListRow(R.drawable.ic_nav_folder, "课件显示已保存入口", "部分视频/H5 资源受平台限制无法直链下载，助手已保存访问入口", trailing = {})
        }
    }
}

@Composable
internal fun NotificationSettingsPreviewPage(
    settings: AssistantSettings,
    current: NotificationSettings,
    onTest: (NotificationSettings) -> Unit,
    onSaved: (NotificationSettings) -> Unit,
) {
    var systemEnabled by rememberSaveable { mutableStateOf(current.systemEnabled) }
    var pushEnabled by rememberSaveable { mutableStateOf(current.pushPlusEnabled) }
    var pushToken by rememberSaveable { mutableStateOf(current.pushPlusToken) }
    var qqEnabled by rememberSaveable { mutableStateOf(current.qqMailEnabled) }
    var qqSender by rememberSaveable { mutableStateOf(current.qqMailSender) }
    var qqPassword by rememberSaveable { mutableStateOf(current.qqMailPassword) }
    var qqRecipient by rememberSaveable { mutableStateOf(current.qqMailRecipient) }
    var qqPorts by rememberSaveable { mutableStateOf(current.qqMailPorts.ifBlank { "465,587" }) }
    val invalidPorts = !smtpPortsValid(qqPorts)
    fun snapshot() = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts.ifBlank { "465,587" })

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_bell, "本机通知", "新签到、监控异常与考试提醒", systemEnabled) { systemEnabled = it }
            PreviewBadge(if (systemEnabled) "已开启本机推送" else "已关闭", if (systemEnabled) PreviewBadgeTone.GREEN else PreviewBadgeTone.AMBER)
        }
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_check, "微信 PushPlus", "把新签到提醒推送到微信", pushEnabled) { pushEnabled = it }
            PreviewBadge("服务需约 3.5 元实名费用", PreviewBadgeTone.AMBER)
            if (pushEnabled) {
                OutlinedTextField(pushToken, { pushToken = it }, label = { Text("PushPlus Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_folder, "QQ 邮箱", "免费 · 使用 SMTP 授权码", qqEnabled) { qqEnabled = it }
            if (qqEnabled) {
                OutlinedTextField(qqSender, { qqSender = it }, label = { Text("发件 QQ 邮箱") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(qqPassword, { qqPassword = it }, label = { Text("SMTP 授权码") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(qqRecipient, { qqRecipient = it }, label = { Text("收件邮箱") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    qqPorts,
                    { qqPorts = it.filter { ch -> ch.isDigit() || ch == ',' } },
                    label = { Text("SMTP 端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = invalidPorts,
                    supportingText = { if (invalidPorts) Text("端口需为逗号分隔的 1-65535 数字，例如 465,587") },
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (!invalidPorts) onTest(snapshot()) },
                enabled = !invalidPorts,
                modifier = Modifier.weight(1f).height(46.dp),
            ) { Text("发送测试通知") }
            Button(
                onClick = {
                    if (invalidPorts) return@Button
                    val saved = snapshot()
                    settings.saveNotifications(saved)
                    onSaved(saved)
                },
                enabled = !invalidPorts,
                modifier = Modifier.weight(1f).height(46.dp),
            ) { Text("保存通知设置") }
        }
    }
}

@Composable
private fun ChannelHeader(icon: Int, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PreviewIconBox(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

@Composable
internal fun StrategyPreviewPage(
    settings: AssistantSettings,
    termCode: String,
    current: RollcallSettings,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onSaved: (RollcallSettings) -> Unit,
    onWidgetToggle: (Boolean) -> Unit,
    onAddWidget: () -> Unit,
    onManualWeekSet: (Int) -> Unit,
    examReminder: ExamReminderSettings,
    onExamReminderChanged: (ExamReminderSettings) -> Unit,
    onOpenExamAlarmSettings: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
) {
    var interval by rememberSyncedText(current.pollIntervalSeconds.toString())

    val intervalInvalid = !pollIntervalSecondsValid(interval)
    val saveEnabled = !intervalInvalid
    val hasUnsavedRollcallChanges = interval != current.pollIntervalSeconds.toString()

    var reminderEnabled by rememberSyncedBoolean(examReminder.enabled)
    var reminderMinutes by rememberSyncedText(examReminder.advanceMinutes.toString())
    var fullScreen by rememberSyncedBoolean(examReminder.fullScreenEnabled)
    val reminderMinutesInvalid = reminderMinutes.isNotBlank() && reminderMinutes.toIntOrNull()?.let { it in 0..60 } != true
    fun emitReminder(
        enabled: Boolean = reminderEnabled,
        minutesText: String = reminderMinutes,
        fullScreenFlag: Boolean = fullScreen,
    ) {
        onExamReminderChanged(
            ExamReminderSettings(
                enabled = enabled,
                advanceMinutes = minutesText.toIntOrNull()?.coerceIn(0, 60) ?: 30,
                fullScreenEnabled = fullScreenFlag,
            ),
        )
    }
    LaunchedEffect(reminderMinutes) {
        if (reminderMinutes != examReminder.advanceMinutes.toString()) {
            kotlinx.coroutines.delay(500)
            if (reminderMinutesInvalid || reminderMinutes.isBlank()) return@LaunchedEffect
            emitReminder()
        }
    }

    var widgetEnabled by rememberSaveable { mutableStateOf(settings.widgetEnabled) }
    var week by rememberSaveable(termCode) { mutableStateOf(settings.manualAcademicWeek(termCode)) }
    var choosingWeek by rememberSaveable { mutableStateOf(false) }
    val calibrationMaxWeek = xmuAcademicCalendarForTerm(termCode)?.totalWeeks ?: 19

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewSectionHeader("签到监控", if (hasUnsavedRollcallChanges) "有未保存修改" else "已保存")
        PreviewCard {
            Text(
                "监控仅负责轮询签到状态并发送提醒；检测到新签到后请前往畅课完成签到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter(Char::isDigit) },
                label = { Text("默认轮询间隔（1-300 秒）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = intervalInvalid,
                supportingText = { if (intervalInvalid) Text("请输入 1-300 的整数；留空默认 30 秒") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(
                onClick = {
                    if (!saveEnabled) return@Button
                    val next = current.copy(
                        pollIntervalSeconds = (interval.toIntOrNull() ?: 30).coerceIn(1, 300),
                        autoAnswerNumber = false,
                        autoAnswerRadar = false,
                    )
                    settings.saveRollcall(next)
                    onSaved(next)
                },
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存监控间隔") }
        }
        PreviewSectionHeader("考试提醒")
        PreviewCard {
            SettingSwitch("考试提醒", "开考前提前提醒（响铃 + 震动）", reminderEnabled) {
                reminderEnabled = it
                emitReminder(enabled = it)
            }
            OutlinedTextField(
                value = reminderMinutes,
                onValueChange = { reminderMinutes = it.filter(Char::isDigit) },
                label = { Text("提前提醒时间（0-60 分钟）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = reminderMinutesInvalid,
                supportingText = { if (reminderMinutesInvalid) Text("请输入 0-60 的整数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SettingSwitch("全屏提醒", "锁屏时显示横幅", fullScreen) {
                fullScreen = it
                emitReminder(fullScreenFlag = it)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenExamAlarmSettings, modifier = Modifier.weight(1f)) { Text("闹钟权限") }
                OutlinedButton(onClick = onOpenFullScreenSettings, modifier = Modifier.weight(1f)) { Text("全屏权限") }
            }
        }
        PreviewSectionHeader("课表与桌面")
        PreviewCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("当前周次", fontWeight = FontWeight.Bold)
                    Text(
                        if (week > 0) "第 $week 周 · 手动校准基准" else "自动判断",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { choosingWeek = !choosingWeek }) { Text(if (choosingWeek) "收起" else "校准周次") }
            }
            if (choosingWeek) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        onClick = { week = 0; choosingWeek = false; onManualWeekSet(0) },
                        color = if (week == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "自动",
                            color = if (week == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                    (1..calibrationMaxWeek).forEach { candidate ->
                        Surface(
                            onClick = { week = candidate; choosingWeek = false; onManualWeekSet(candidate) },
                            color = if (week == candidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                "第${candidate}周",
                                color = if (week == candidate) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
            SettingSwitch("今日课程小卡片", "桌面显示当天课程", widgetEnabled) {
                widgetEnabled = it
                onWidgetToggle(it)
            }
            OutlinedButton(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) { Text("添加到桌面") }
        }
        PreviewSectionHeader("外观")
        PreviewCard {
            SegmentedOptions(
                options = listOf(THEME_MODE_SYSTEM to "跟随系统", THEME_MODE_LIGHT to "浅色", THEME_MODE_DARK to "深色"),
                selected = themeMode,
                onSelected = onThemeModeChanged,
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

@Composable
private fun SegmentedOptions(options: List<Pair<String, String>>, selected: String, onSelected: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            Surface(
                onClick = { onSelected(value) },
                color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) themePrimary() else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp)) {
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        color = if (isSelected) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoursewarePreviewPage(
    courses: List<CourseSummary>,
    selectedCourse: CourseSummary?,
    coursewareItems: List<CoursewareUiItem>,
    selectedIds: Set<String>,
    onRefreshCourses: () -> Unit,
    onCourseSelected: (CourseSummary) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onRefreshCourseware: () -> Unit,
    onOpenPlatform: () -> Unit,
    downloadProgress: String,
    downloadLoading: Boolean,
    coursesLoading: Boolean,
    coursesRefreshError: String,
    coursewareLoading: Boolean,
    coursewareRefreshError: String,
    loggedIn: Boolean,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedYear by rememberSaveable { mutableStateOf("全部") }
    var selectedSemester by rememberSaveable { mutableStateOf("全部") }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    val years = remember(courses) {
        listOf("全部") + courses.mapNotNull { courseYear(it.term) }.distinct().sortedDescending()
    }
    val semesters = listOf("全部", "第一学期", "第二学期", "第三学期")
    val filtered = remember(courses, query, selectedYear, selectedSemester) {
        courses.filter { course ->
            val matchQuery = query.isBlank() || course.displayName.contains(query, ignoreCase = true)
            val matchYear = selectedYear == "全部" || courseYear(course.term) == selectedYear
            val matchSem = selectedSemester == "全部" || courseSemesterLabel(course.semesterCode, course.term) == selectedSemester
            matchQuery && matchYear && matchSem
        }
    }
    val counts = remember(coursewareItems) { coursewareCounts(coursewareItems) }
    val allSelected = coursewareItems.isNotEmpty() && coursewareItems.all { it.id in selectedIds }

    if (pickerOpen && courses.isNotEmpty()) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { pickerOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("选择课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "显示 ${filtered.size} / 共 ${courses.size} 门 · 点击切换课件列表",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { pickerOpen = false }) { Text("关闭") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索课程") },
                    placeholder = { Text("课程名称或学期") },
                    trailingIcon = if (query.isNotBlank()) {
                        { TextButton(onClick = { query = "" }) { Text("清除") } }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                )
                if (years.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        years.forEach { yr ->
                            Surface(
                                onClick = { selectedYear = yr },
                                color = if (yr == selectedYear) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(9.dp),
                            ) {
                                Text(
                                    yr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (yr == selectedYear) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    semesters.forEach { sem ->
                        Surface(
                            onClick = { selectedSemester = sem },
                            color = if (sem == selectedSemester) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(9.dp),
                        ) {
                            Text(
                                sem,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sem == selectedSemester) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        "没有匹配课程",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.id.ifBlank { it.displayName } }) { course ->
                            val isSelected = course.id == selectedCourse?.id
                            val accent = scheduleCourseColor(course.title)
                            Surface(
                                onClick = {
                                    onCourseSelected(course)
                                    pickerOpen = false
                                },
                                color = if (isSelected) themePrimarySoft() else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) themePrimary() else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(38.dp)
                                            .background(accent, RoundedCornerShape(999.dp)),
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            course.title,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) themePrimary() else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (course.term.isNotBlank()) {
                                            Text(
                                                course.term,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        PreviewBadge("当前")
                                    } else {
                                        Text(
                                            "查看 ›",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = themePrimary(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // 顶部课程选择卡固定：长课件列表下随时一键切换课程
        PreviewCard(
            onClick = if (courses.isNotEmpty()) {
                { pickerOpen = true }
            } else null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PreviewIconBox(R.drawable.ic_nav_folder, Modifier.size(36.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        selectedCourse?.title ?: "选择课程",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            selectedCourse != null -> selectedCourse.term.ifBlank { "点击切换其他课程" }
                            !loggedIn -> "登录后可读取课程列表"
                            courses.isNotEmpty() -> "共 ${courses.size} 门课程 · 点击挑选"
                            else -> "点击右侧刷新读取课程"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { pickerOpen = true },
                    enabled = courses.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (selectedCourse != null) "换课 ▾" else "选课 ▾", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onRefreshCourses,
                    enabled = !coursesLoading,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (coursesLoading) "刷新中" else "刷新", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (coursesLoading || coursesRefreshError.isNotBlank()) {
                RefreshStateBanner(coursesLoading, coursesRefreshError, courses.isNotEmpty())
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (selectedCourse == null) {
                item {
                    if (!loggedIn) {
                        EmptyState("请先登录", "登录后即可读取课程并下载课件。")
                    } else if (courses.isEmpty()) {
                        EmptyState("暂无课程", "点击上方刷新即可获取本学期课程列表。")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EmptyState("尚未选择课程", "共读取到 ${courses.size} 门课程，点击下方按钮选择一门即可浏览课件。")
                            Button(
                                onClick = { pickerOpen = true },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            ) { Text("点击选择课程") }
                        }
                    }
                }
            } else {
                item { PreviewSectionHeader("${selectedCourse.title} · 课件", "${coursewareItems.size} 条") }
                item {
                    PreviewCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PreviewBadge("已选 ${selectedIds.size} 项")
                            Text(
                                "可直接下载 ${counts.direct} · 入口 ${counts.entry} · 受限 ${counts.limited}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (downloadLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (downloadProgress.isNotBlank()) {
                            Text(downloadProgress, color = themePrimary(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                        RefreshStateBanner(coursewareLoading, coursewareRefreshError, coursewareItems.isNotEmpty())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onDownload,
                                enabled = selectedIds.isNotEmpty() && !downloadLoading,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (downloadLoading) "下载中" else "下载（${selectedIds.size}）") }
                            OutlinedButton(onClick = onSelectAll, enabled = coursewareItems.isNotEmpty()) {
                                Text(if (allSelected) "取消全选" else "全选")
                            }
                            OutlinedButton(onClick = onRefreshCourseware, enabled = !coursewareLoading) { Text("刷新") }
                        }
                    }
                }
                if (coursewareItems.isEmpty() && !coursewareLoading) {
                    item { EmptyState("暂无课件", "刷新后显示平台提供的课程资料。") }
                } else {
                    items(coursewareItems, key = { it.id }) { item ->
                        CoursewareItemCard(item, checked = item.id in selectedIds, onToggle = { onToggle(item.id) })
                    }
                }
                item { OutlinedButton(onClick = onOpenPlatform, modifier = Modifier.fillMaxWidth()) { Text("打开课程平台") } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExamPreviewPage(
    summary: XmuTermExamSummary?,
    validTerms: List<String>,
    selectedTerm: String,
    loading: Boolean,
    refreshError: String,
    loggedIn: Boolean,
    onSelectTerm: (String) -> Unit,
    onRefresh: () -> Unit,
    autoUpdated: Boolean = false,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var showFinished by rememberSaveable(selectedTerm) { mutableStateOf(false) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000 - now % 60_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    val termSummary = remember(summary, selectedTerm) { summary?.takeIf { it.termCode == selectedTerm } }
    val upcoming = remember(termSummary, nowMillis) {
        termSummary?.exams?.filter { exam -> examEndEpochMillis(exam) > nowMillis }
            ?.sortedBy { it.date + it.timeRange } ?: emptyList()
    }
    val finished = remember(termSummary, nowMillis) {
        termSummary?.exams?.filter { exam -> examEndEpochMillis(exam) <= nowMillis }
            ?.sortedByDescending { it.date + it.timeRange } ?: emptyList()
    }
    val unarranged = remember(termSummary) { termSummary?.unarranged.orEmpty() }
    val today = LocalDate.now()

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                PreviewCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(selectedTerm.ifBlank { "考试安排" }, fontWeight = FontWeight.Bold)
                            Text(
                                "即将 ${upcoming.size} 场 · 已结束 ${finished.size} 场",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "刷新中" else "刷新") }
                    }
                    if (validTerms.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            validTerms.forEach { term ->
                                val active = term == selectedTerm
                                Surface(
                                    onClick = { onSelectTerm(term) },
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        termLabelShort(term),
                                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                    RefreshStateBanner(loading, refreshError, summary != null)
                    if (autoUpdated) {
                        Text("检测到考试安排变化，已自动更新", style = MaterialTheme.typography.bodySmall, color = themePrimary(), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (summary == null && loading) {
                item { PreviewExamSkeleton() }
            } else if (summary == null && !loading) {
                item {
                    EmptyState(
                        if (!loggedIn) "请先登录" else "暂无考试安排",
                        if (!loggedIn) "登录后刷新即可读取考试。" else "当前学期没有读取到考试。",
                    )
                }
            } else {
                if (upcoming.isNotEmpty()) {
                    item { PreviewSectionHeader("即将考试", "${upcoming.size} 场") }
                    items(upcoming, key = { "up:${it.id}:${it.courseName}" }) { exam ->
                        PreviewExamCard(exam = exam, today = today, finished = false)
                    }
                }
                if (finished.isNotEmpty()) {
                    item {
                        Surface(
                            onClick = { showFinished = !showFinished },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (showFinished) "已结束（点击收起）" else "已结束（点击展开）",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${finished.size} 场 ${if (showFinished) "▴" else "▾"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (showFinished) {
                        items(finished, key = { "done:${it.id}:${it.courseName}" }) { exam ->
                            PreviewExamCard(exam = exam, today = today, finished = true)
                        }
                    }
                }
                if (unarranged.isNotEmpty()) {
                    item { PreviewSectionHeader("暂无安排", "${unarranged.size} 门") }
                    items(unarranged, key = { "un:${it.courseName}" }) { item ->
                        PreviewCard(soft = true) { Text(item.courseName, fontWeight = FontWeight.Bold) }
                    }
                }
                if (upcoming.isEmpty() && finished.isEmpty() && unarranged.isEmpty()) {
                    item { EmptyState("本学期暂无考试安排", "刷新后如教务公布考试时间会自动更新。") }
                }
            }
        }
    }
}

@Composable
private fun PreviewExamSkeleton(rows: Int = 3) {
    val transition = rememberInfiniteTransition(label = "preview-exam-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "preview-exam-pulse",
    )
    val blockColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        repeat(rows) {
            PreviewCard {
                Box(Modifier.fillMaxWidth(0.7f).height(16.dp).alpha(pulse).background(blockColor, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.45f).height(12.dp).alpha(pulse).background(blockColor, RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun PreviewExamCard(exam: XmuExam, today: LocalDate, finished: Boolean) {
    val parts = exam.date.split('-')
    val day = parts.lastOrNull().orEmpty().trimStart('0').ifBlank { "—" }
    val month = parts.getOrNull(parts.lastIndex - 1)?.trimStart('0').orEmpty()
    val daysUntil = remember(exam.date, today) {
        runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(exam.date)) }.getOrNull()
    }
    val countdownLabel = when {
        finished -> "已结束"
        daysUntil == null -> ""
        daysUntil <= 0L -> "今天"
        daysUntil == 1L -> "明天"
        else -> "${daysUntil}天后"
    }
    val countdownTone = when {
        finished -> PreviewBadgeTone.BLUE
        daysUntil != null && daysUntil <= 3L -> PreviewBadgeTone.RED
        else -> PreviewBadgeTone.AMBER
    }
    PreviewCard(modifier = Modifier.alpha(if (finished) 0.65f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(color = themePrimarySoft(), shape = RoundedCornerShape(11.dp)) {
                Column(Modifier.size(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(day, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = themePrimary())
                    Text("${month}月", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exam.courseName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (countdownLabel.isNotBlank()) {
                        PreviewBadge(countdownLabel, countdownTone)
                    }
                }
                Text(exam.timeRange, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val locationLine = listOfNotNull(
                    exam.room.takeIf { it.isNotBlank() },
                    exam.mode.takeIf { it.isNotBlank() && it !in exam.room },
                ).joinToString(" · ")
                if (locationLine.isNotBlank()) {
                    Surface(color = themePrimarySoft(), shape = RoundedCornerShape(7.dp)) {
                        Text(
                            locationLine,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = themePrimary(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
