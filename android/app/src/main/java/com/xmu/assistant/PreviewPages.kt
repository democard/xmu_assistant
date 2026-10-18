package com.xmu.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun TutorialPage(scrollState: androidx.compose.foundation.ScrollState, navigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewCard(soft = true) {
            PreviewListRow(R.drawable.ic_nav_help, "你想完成什么？", "按任务查看说明，不必从头读完整页", trailing = {})
        }
        val tasks = listOf(
            Triple("01", "登录并启动监控", "校园账号、本机保存与后台运行") to "首页",
            Triple("02", "自动处理签到", "数字签到、雷达签到与二维码提醒") to "策略",
            Triple("03", "看课表与导出日历", "周课表、课程详情和系统日历") to "课表",
            Triple("04", "查看成绩与专业排名", "成绩长图、模拟成绩和绩点证明") to "成绩",
            Triple("05", "下载课程课件", "筛选课程、批量下载和平台入口") to "课程课件",
        )
        tasks.forEach { (task, target) ->
            PreviewCard(onClick = { navigate(target) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                        Text(task.first, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(task.second, fontWeight = FontWeight.Bold)
                        Text(task.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("查看步骤 ›", style = MaterialTheme.typography.labelMedium, color = themePrimary(), modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        PreviewCard {
            PreviewSectionHeader("常见问题", "3 项")
            PreviewListRow(R.drawable.ic_nav_help, "课程刷不出来", "先重新登录，再刷新课程")
            PreviewListRow(R.drawable.ic_nav_bell, "收不到通知", "检查通知权限与后台运行")
            PreviewListRow(R.drawable.ic_nav_folder, "课件不能下载", "平台未提供地址时会保存入口")
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
            ChannelHeader(R.drawable.ic_nav_bell, "本机通知", "签到、监控异常与考试提醒", systemEnabled) { systemEnabled = it }
            PreviewBadge("系统权限正常", PreviewBadgeTone.GREEN)
        }
        PreviewCard {
            ChannelHeader(R.drawable.ic_nav_check, "微信 PushPlus", "把签到状态推送到微信", pushEnabled) { pushEnabled = it }
            PreviewBadge("服务需约 3.5 元实名费用", PreviewBadgeTone.AMBER)
            if (pushEnabled) {
                OutlinedTextField(pushToken, { pushToken = it }, label = { Text("PushPlus Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onTest(snapshot()) }, modifier = Modifier.fillMaxWidth()) { Text("发送测试通知") }
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
                )
                OutlinedButton(onClick = { if (!invalidPorts) onTest(snapshot()) }, enabled = !invalidPorts, modifier = Modifier.fillMaxWidth()) { Text("发送测试通知") }
            }
        }
        Button(
            onClick = {
                if (invalidPorts) return@Button
                val saved = snapshot()
                settings.saveNotifications(saved)
                onSaved(saved)
            },
            enabled = !invalidPorts,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) { Text("保存通知设置") }
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
    var interval by rememberSaveable { mutableStateOf(current.pollIntervalSeconds.toString()) }
    var number by rememberSaveable { mutableStateOf(current.autoAnswerNumber) }
    var radar by rememberSaveable { mutableStateOf(current.autoAnswerRadar) }
    var waitMode by rememberSaveable { mutableStateOf(current.waitBeforeAnswerMode) }
    // 人数/比例必须可编辑：此前本页只保存模式，阈值一直被 current 覆盖，改了也不生效。
    var waitCount by rememberSaveable { mutableStateOf(current.waitBeforeAnswerCount.toString()) }
    var waitPercent by rememberSaveable { mutableStateOf(current.waitBeforeAnswerPercent.toString()) }
    val countValue = waitCount.toIntOrNull()
    val percentValue = waitPercent.toIntOrNull()
    val countInvalid = waitMode == WAIT_BEFORE_ANSWER_COUNT && (countValue == null || countValue < 1)
    val percentInvalid = waitMode == WAIT_BEFORE_ANSWER_PERCENT && (percentValue == null || percentValue !in 1..100)
    var reminderEnabled by rememberSaveable { mutableStateOf(examReminder.enabled) }
    var fullScreen by rememberSaveable { mutableStateOf(examReminder.fullScreenEnabled) }
    var widgetEnabled by rememberSaveable { mutableStateOf(settings.widgetEnabled) }
    var week by rememberSaveable(termCode) { mutableStateOf(settings.manualAcademicWeek(termCode)) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        PreviewSectionHeader("自动签到策略", "已保存")
        PreviewCard {
            OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("轮询间隔（秒）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            SettingSwitch("数字签到", "自动处理", number) { number = it }
            SettingSwitch("雷达签到", "使用课程坐标自动处理", radar) { radar = it }
        }
        PreviewCard {
            Text("自动处理时机", fontWeight = FontWeight.Bold)
            SegmentedOptions(
                options = listOf(WAIT_BEFORE_ANSWER_NONE to "立即", WAIT_BEFORE_ANSWER_COUNT to "达到人数", WAIT_BEFORE_ANSWER_PERCENT to "达到比例"),
                selected = waitMode,
                onSelected = { waitMode = it },
            )
            if (waitMode == WAIT_BEFORE_ANSWER_COUNT) {
                OutlinedTextField(
                    waitCount,
                    { waitCount = it.filter(Char::isDigit) },
                    label = { Text("达到人数（至少 1 人）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = countInvalid,
                    supportingText = { if (countInvalid) Text("请输入至少 1 的整数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (waitMode == WAIT_BEFORE_ANSWER_PERCENT) {
                OutlinedTextField(
                    waitPercent,
                    { waitPercent = it.filter(Char::isDigit) },
                    label = { Text("达到比例（1-100%）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = percentInvalid,
                    supportingText = { if (percentInvalid) Text("请输入 1-100 的整数比例") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Button(
                onClick = {
                    if (countInvalid || percentInvalid) return@Button
                    val next = current.copy(
                        pollIntervalSeconds = (interval.toIntOrNull() ?: 30).coerceIn(1, 300),
                        autoAnswerNumber = number,
                        autoAnswerRadar = radar,
                        waitBeforeAnswerMode = waitMode,
                        waitBeforeAnswerCount = (countValue ?: current.waitBeforeAnswerCount).coerceAtLeast(1),
                        waitBeforeAnswerPercent = (percentValue ?: current.waitBeforeAnswerPercent).coerceIn(1, 100),
                    )
                    settings.saveRollcall(next)
                    onSaved(next)
                },
                enabled = !countInvalid && !percentInvalid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存策略更改") }
        }
        PreviewSectionHeader("考试提醒")
        PreviewCard {
            SettingSwitch("考试提醒", "提前 ${examReminder.advanceMinutes} 分钟", reminderEnabled) {
                reminderEnabled = it
                onExamReminderChanged(examReminder.copy(enabled = it))
            }
            SettingSwitch("全屏提醒", "锁屏时显示横幅", fullScreen) {
                fullScreen = it
                onExamReminderChanged(examReminder.copy(enabled = reminderEnabled, fullScreenEnabled = it))
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
                    Text(if (week > 0) "第 $week 周 · 手动校准" else "自动判断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { week = if (week >= 19) 0 else week + 1; onManualWeekSet(week) }) { Text("修改") }
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            Surface(
                onClick = { onSelected(value) },
                color = if (selected == value) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f),
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp), color = if (selected == value) themePrimary() else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}

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
    val filtered = courses.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
    val counts = coursewareCounts(coursewareItems)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            PreviewCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择课程", fontWeight = FontWeight.Bold)
                        Text("先选课程，再批量选择课件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onRefreshCourses, enabled = !coursesLoading) { Text(if (coursesLoading) "刷新中" else "刷新") }
                }
                OutlinedTextField(query, { query = it }, label = { Text("搜索课程") }, placeholder = { Text("课程名称或学期") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                RefreshStateBanner(coursesLoading, coursesRefreshError, courses.isNotEmpty())
                if (filtered.isEmpty()) {
                    Text(if (!loggedIn) "登录后可读取课程" else "没有匹配课程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        filtered.forEach { course ->
                            Surface(
                                onClick = { onCourseSelected(course) },
                                color = if (course.id == selectedCourse?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(course.title, color = if (course.id == selectedCourse?.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
                            }
                        }
                    }
                    selectedCourse?.let {
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.title, fontWeight = FontWeight.Bold)
                                Text(it.term, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            PreviewBadge("已选择")
                        }
                    }
                }
            }
        }
        if (selectedCourse != null) {
            item { PreviewSectionHeader("${selectedCourse.title} · 课件", "${coursewareItems.size} 条") }
            item {
                PreviewCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PreviewBadge("已选 ${selectedIds.size} 项")
                        Text("可直接下载 ${counts.direct} · 入口 ${counts.entry} · 受限 ${counts.limited}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (downloadProgress.isNotBlank()) Text(downloadProgress, color = themePrimary(), fontWeight = FontWeight.Bold)
                    RefreshStateBanner(coursewareLoading, coursewareRefreshError, coursewareItems.isNotEmpty())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload, enabled = selectedIds.isNotEmpty() && !downloadLoading, modifier = Modifier.weight(1f)) { Text(if (downloadLoading) "下载中" else "下载（${selectedIds.size}）") }
                        OutlinedButton(onClick = onSelectAll) { Text("全选") }
                        OutlinedButton(onClick = onRefreshCourseware, enabled = !coursewareLoading) { Text("刷新") }
                    }
                }
            }
            if (coursewareItems.isEmpty() && !coursewareLoading) {
                item { EmptyState("暂无课件", "刷新后显示平台提供的课程资料。") }
            } else {
                items(coursewareItems) { item ->
                    CoursewareItemCard(item, checked = item.id in selectedIds, onToggle = { onToggle(item.id) })
                }
            }
            item { OutlinedButton(onClick = onOpenPlatform, modifier = Modifier.fillMaxWidth()) { Text("打开课程平台") } }
        }
    }
}

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
) {
    val exams = summary?.takeIf { it.termCode == selectedTerm }?.exams.orEmpty()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            PreviewCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedTerm.ifBlank { "考试安排" }, fontWeight = FontWeight.Bold)
                        Text("${exams.size} 场考试 · 提醒可在策略页设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !loading) { Text(if (loading) "刷新中" else "刷新") }
                }
                if (validTerms.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        validTerms.forEach { term ->
                            Surface(onClick = { onSelectTerm(term) }, color = if (term == selectedTerm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                                Text(termLabelShort(term), color = if (term == selectedTerm) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                RefreshStateBanner(loading, refreshError, summary != null)
            }
        }
        if (summary == null && !loading) {
            item { EmptyState(if (!loggedIn) "请先登录" else "暂无考试安排", if (!loggedIn) "登录后刷新即可读取考试。" else "当前学期没有读取到考试。") }
        } else if (exams.isNotEmpty()) {
            item { PreviewSectionHeader("考试安排", "${exams.size} 场") }
            items(exams) { exam -> PreviewExamCard(exam) }
        }
        val unarranged = summary?.unarranged.orEmpty()
        if (unarranged.isNotEmpty()) {
            item { PreviewSectionHeader("未安排", "${unarranged.size} 门") }
            items(unarranged) { item -> PreviewCard(soft = true) { Text(item.courseName, fontWeight = FontWeight.Bold) } }
        }
    }
}

@Composable
private fun PreviewExamCard(exam: XmuExam) {
    val parts = exam.date.split('-')
    val day = parts.lastOrNull().orEmpty().trimStart('0').ifBlank { "—" }
    val month = parts.getOrNull(parts.lastIndex - 1)?.trimStart('0').orEmpty()
    PreviewCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(color = themePrimarySoft(), shape = RoundedCornerShape(11.dp)) {
                Column(Modifier.size(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(day, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = themePrimary())
                    Text("${month}月", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(exam.courseName, fontWeight = FontWeight.Bold)
                Text("${exam.timeRange} · ${exam.room}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
