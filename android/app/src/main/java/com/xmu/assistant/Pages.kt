package com.xmu.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun HomePage(
    username: String,
    password: String,
    loggedIn: Boolean,
    accountTransitionInProgress: Boolean,
    monitorTransitionInProgress: Boolean,
    monitorStatus: String,
    monitorLastCheck: String,
    monitorFailureCount: Int,
    monitorLastError: String,
    autoEnabled: Boolean,
    recentEvent: RollcallEvent?,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onStartMonitor: () -> Unit,
    onStopMonitor: () -> Unit,
    onAutoChanged: (Boolean) -> Unit,
    onOpenBackgroundSettings: () -> Unit,
) {
    SectionCard("首页") {
        Text("账号登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            label = { Text("学号") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !accountTransitionInProgress,
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !accountTransitionInProgress,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onLogin,
                enabled = !loggedIn && !accountTransitionInProgress,
                modifier = Modifier.weight(1f),
            ) { Text("登录") }
            OutlinedButton(
                onClick = onLogout,
                enabled = loggedIn && !accountTransitionInProgress,
                modifier = Modifier.weight(1f),
            ) { Text("退出登录") }
        }
    }
    StatusStack(
        account = if (loggedIn) "已登录" else "未登录",
        monitor = monitorStatus,
        monitorLastCheck = monitorLastCheck,
        monitorFailureCount = monitorFailureCount,
        monitorLastError = monitorLastError,
        auto = if (autoEnabled) "已开启" else "未开启",
        recent = recentEvent?.let { "${it.courseTitle} / ${it.type} / ${it.status}" } ?: "-",
    )
    SectionCard("快速操作") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStartMonitor,
                enabled = !monitorTransitionInProgress && !accountTransitionInProgress,
                modifier = Modifier.weight(1f),
            ) { Text("启动监控") }
            OutlinedButton(
                onClick = onStopMonitor,
                enabled = !monitorTransitionInProgress && !accountTransitionInProgress,
                modifier = Modifier.weight(1f),
            ) { Text("暂停监控") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoEnabled, onCheckedChange = onAutoChanged)
            Spacer(Modifier.width(8.dp))
            Text("开启自动签到", fontWeight = FontWeight.Bold)
        }
    }
    SectionCard("后台运行建议") {
        Text("为了让手机锁屏后仍能提醒，请允许通知，并在系统设置里把 xmu助手 的电池策略设为不限制或允许后台运行。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenBackgroundSettings, modifier = Modifier.fillMaxWidth()) {
            Text("打开系统设置")
        }
    }
}

@Composable
fun RollcallStatusPage(
    events: List<RollcallEvent>,
    openedEventId: String,
    loading: Boolean,
    refreshError: String,
    updatedAtMillis: Long,
    historyItems: List<RollcallHistoryItem>,
    historyLoading: Boolean,
    historyError: String,
    historyUpdatedAtMillis: Long,
    loggedIn: Boolean = false,
    onRefresh: () -> Unit,
) {
    // rememberSaveable：转屏（Activity 重建）后保留滚动位置，而不是回到顶部。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard("签到情况") {
                // 顶部一键刷两块：进行中签到与最近十次各走独立互斥门，可并行
                Button(onClick = onRefresh, enabled = !loading && !historyLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading || historyLoading) "刷新中" else "刷新签到情况")
                }
                // 刷新横幅跟随整页节奏：两块任一在途都保持进度条，直到刷新全部完成
                // （真机反馈：只跟进行中块会让进度条提前消失，历史块只剩静态文字）
                RefreshStateBanner(loading = loading || historyLoading, errorMessage = refreshError, hasData = events.isNotEmpty())
                if (updatedAtMillis > 0L) {
                    Text("上次更新 ${formatMonitorTime(updatedAtMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (openedEventId.isNotBlank()) Text("从通知打开：$openedEventId", color = MaterialTheme.colorScheme.primary)
            }
        }
        // 卡片二：正在进行（检测到签到时的展示与原来完全一致）
        item {
            SectionCard("正在进行") {
                if (events.isEmpty()) {
                    EmptyState("暂无进行中签到", "有新的课堂签到时会在这里出现，也可从通知直接打开。")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        events.forEach { event ->
                            InfoCard {
                                Text(event.courseTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${event.type} · ${event.status}")
                                Text("发起人：${event.teacher}")
                                Text("截止时间：${event.deadline.ifBlank { "未知" }}")
                            }
                        }
                    }
                }
            }
        }
        // 卡片三：历史签到（最近十次，准确本人状态）
        item {
            SectionCard("历史签到") {
                // 历史区块状态词表：加载中「正在读取最近签到…」；失败走横幅错误态；
                // 成功后由「上次更新」接管（不堆成功提示）
                if (historyLoading) {
                    Text("正在读取最近签到…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (historyError.isNotBlank()) {
                    RefreshStateBanner(loading = false, errorMessage = historyError, hasData = historyItems.isNotEmpty())
                }
                if (historyUpdatedAtMillis > 0L && !historyLoading) {
                    Text("上次更新 ${formatMonitorTime(historyUpdatedAtMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (historyItems.isEmpty()) {
                    // 空态三档之「未登录」：登出后历史缓存清空时给出登录引导
                    if (!loggedIn) {
                        EmptyState("请先登录", "登录后拉取最近签到记录。")
                    } else {
                        EmptyState("暂无签到历史", "点击顶部刷新拉取最近十次签到记录")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        historyItems.forEach { item ->
                            InfoCard {
                                Text(item.courseTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${item.type} · ${item.timeDisplay}")
                                // 单条状态位：明细未回显示「核实中…」，回包后为准确本人状态
                                Text("本人状态：${item.ownStatus.ifBlank { "核实中…" }}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScorePage(
    records: List<XmuScoreRecord>,
    loading: Boolean,
    refreshError: String,
    loggedIn: Boolean = false,
    updatedAtMillis: Long,
    onRefresh: () -> Unit,
    onShareScore: () -> Unit,
) {
    val summary = remember(records) { xmuScoreSummary(records) }
    val recordsByTerm = remember(records) {
        records.groupBy { it.term.ifBlank { "未标注学期" } }
            .entries
            .sortedByDescending { entry ->
                entry.value.firstOrNull()?.termCode?.takeIf { it.isNotBlank() } ?: entry.key
            }
            .map { (term, termRecords) -> term to termRecords.sortedBy { it.courseName } }
    }
    // rememberSaveable：转屏后保留滚动位置。
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // 「模拟成绩」入口滚动跳转用：模拟区固定是 LazyColumn 最后一个 item
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard("成绩") {
                // FlowRow：宽屏下四件单行（「刷新成绩」weight 撑满余宽，观感同原 Row）；
                // 窄屏放不下时自动换行，避免「刷新成绩」被挤压到文字裁切。
                // weight 在 FlowRow 里只按行内余宽分配（320dp 下仅剩约 44dp，主按钮
                // 文字被迫折行），故加最小宽度兜底保证「刷新成绩」始终单行可读。
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.weight(1f).widthIn(min = 112.dp),
                    ) {
                        Text(if (loading) "刷新中" else "刷新成绩")
                    }
                    // 两个次级按钮收紧内边距，配合 FlowRow 换行兜底窄屏排版
                    OutlinedButton(
                        onClick = onShareScore,
                        enabled = records.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("分享长图")
                    }
                    OutlinedButton(
                        onClick = {
                            val simulationIndex = listState.layoutInfo.totalItemsCount - 1
                            if (simulationIndex >= 0) {
                                scope.launch { listState.animateScrollToItem(simulationIndex) }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("模拟成绩")
                    }
                    Surface(
                        color = themePrimarySoft(),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Text(
                            "${records.size} 门",
                            color = themePrimary(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                RefreshStateBanner(loading = loading, errorMessage = refreshError, hasData = records.isNotEmpty())
                if (updatedAtMillis > 0L) {
                    Text("上次更新 ${formatMonitorTime(updatedAtMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (records.isEmpty()) {
            item {
                // 空态三档之「未登录」：登出后成绩清空时给出登录引导而非"暂无成绩"
                if (!loggedIn) {
                    EmptyState("请先登录", "登录后点击刷新即可读取成绩。")
                } else {
                    EmptyState("暂无成绩", "刷新后显示百分制成绩和及格制等非百分制结果。")
                }
            }
        } else {
            item {
                ScoreSummaryPanel(summary)
            }
            recordsByTerm.forEach { (term, termRecords) ->
                item(key = "term:$term") {
                    TermHeader(term, termRecords.size)
                }
                // 注意：不使用显式 key。同一学期可能存在 courseCode+courseName 相同的
                // 重修/补考记录，若用内容 key 会重复导致 LazyColumn 崩溃；默认 index key 绝对安全。
                items(count = termRecords.size) { index ->
                    ScoreRecordCard(termRecords[index])
                }
            }
        }
        // 模拟区固定在成绩列表下方（页面最底部，不碍眼），入口按钮滚动跳转至此
        item(key = "score-simulation") {
            ScoreSimulationSection(records)
        }
    }
}

/** 模拟行输入的持久化：List<Pair<String, String>> 展平为字符串列表（旋转重建不丢）。 */
private val ScoreSimulationRowsSaver = listSaver<List<Pair<String, String>>, String>(
    save = { rows -> rows.flatMap { listOf(it.first, it.second) } },
    restore = { flat -> List(flat.size / 2) { index -> flat[2 * index] to flat[2 * index + 1] } },
)

private fun List<Pair<String, String>>.withRowReplaced(
    index: Int,
    row: Pair<String, String>,
): List<Pair<String, String>> = mapIndexed { i, current -> if (i == index) row else current }

/**
 * 成绩页底部的「模拟成绩」区：追加若干门预计出分课程（百分制成绩 + 学分），
 * 实时显示并入后的整体四项指标（与顶部统计面板同款布局，不显示已修总学分）。
 * 纯本地换算（xmuSimulatedSummary），零网络请求；无效行不参与计算、不打断报错。
 */
@Composable
private fun ScoreSimulationSection(records: List<XmuScoreRecord>) {
    var simulationRows by rememberSaveable(stateSaver = ScoreSimulationRowsSaver) {
        mutableStateOf<List<Pair<String, String>>>(emptyList())
    }
    val summary = remember(records, simulationRows) { xmuSimulatedSummary(records, simulationRows) }
    SectionCard("模拟成绩") {
        Text(
            "填入预计出分课程的成绩与学分，实时查看计入后的整体成绩；空、非法或超 0-100 的行不参与计算。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        simulationRows.forEachIndexed { index, (scoreText, creditText) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { simulationRows = simulationRows.withRowReplaced(index, it to creditText) },
                    label = { Text("预计成绩") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = creditText,
                    onValueChange = { simulationRows = simulationRows.withRowReplaced(index, scoreText to it) },
                    label = { Text("学分") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedButton(
                    onClick = { simulationRows = simulationRows.filterIndexed { i, _ -> i != index } },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text("删除") }
            }
        }
        OutlinedButton(onClick = { simulationRows = simulationRows + ("" to "") }) {
            Text("+ 添加一门")
        }
        ScoreSimulationSummaryPanel(summary)
    }
}


@Composable
fun NotificationSettingsPage(
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
    // 端口只允许数字和逗号；输入过程中即时校验，非法时保存前给出提示而不是静默钳制
    val portsInvalid = !smtpPortsValid(qqPorts)
    val preview = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts)
    SectionCard("通知") {
        val status = when {
            notificationSettingsMissing(preview) -> "当前状态：缺少配置"
            systemEnabled || pushEnabled || qqEnabled -> "当前状态：已配置"
            else -> "当前状态：未开启"
        }
        Text(status, fontWeight = FontWeight.Bold)
        StatusRow("本机通知", notificationChannelStatus(systemEnabled, false))
        StatusRow("微信 PushPlus", notificationChannelStatus(pushEnabled, pushToken.isBlank()))
        StatusRow("QQ 邮箱", notificationChannelStatus(qqEnabled, qqSender.isBlank() || qqPassword.isBlank() || qqRecipient.isBlank()))
        Text("温馨提示：PushPlus 会收取约 3.5 元实名费用；QQ 邮箱免费使用。", color = themeWarning())
        ToggleRow("开启系统通知", systemEnabled) { systemEnabled = it }
        ToggleRow("开启微信通知", pushEnabled) { pushEnabled = it }
        OutlinedTextField(value = pushToken, onValueChange = { pushToken = it }, label = { Text("PushPlus Token") }, placeholder = { Text("从 PushPlus 个人中心复制 token") }, modifier = Modifier.fillMaxWidth())
        ToggleRow("开启 QQ 邮箱提醒", qqEnabled) { qqEnabled = it }
        OutlinedTextField(value = qqSender, onValueChange = { qqSender = it }, label = { Text("发件 QQ 邮箱") }, placeholder = { Text("例如 student@example.invalid") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = qqPassword, onValueChange = { qqPassword = it }, label = { Text("SMTP 授权码") }, placeholder = { Text("不是 QQ 密码，是邮箱生成的授权码") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(value = qqRecipient, onValueChange = { qqRecipient = it }, label = { Text("收件邮箱") }, placeholder = { Text("接收提醒的邮箱") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = qqPorts,
            onValueChange = { qqPorts = it.filter { ch -> ch.isDigit() || ch == ',' } },
            label = { Text("SMTP 端口") },
            placeholder = { Text("465,587") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = portsInvalid,
            supportingText = {
                if (portsInvalid) Text("端口需为逗号分隔的 1-65535 数字，例如 465,587")
            },
        )
        Text("可填写多个端口，例如 465,587；465 使用 SSL，587 使用 STARTTLS。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (portsInvalid) return@Button
                    val saved = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts.ifBlank { "465,587" })
                    settings.saveNotifications(saved)
                    onSaved(saved)
                },
                modifier = Modifier.weight(1f),
            ) { Text("保存通知设置") }
            OutlinedButton(
                onClick = {
                    // 与保存按钮同一校验：onTest 内部会持久化设置，
                    // 非法端口（如 99999）一旦写入，监控链路每次发邮件都拿非法端口连接
                    if (portsInvalid) return@OutlinedButton
                    val saved = NotificationSettings(systemEnabled, pushEnabled, pushToken, qqEnabled, qqSender, qqPassword, qqRecipient, qqPorts.ifBlank { "465,587" })
                    onTest(saved)
                },
                modifier = Modifier.weight(1f),
            ) { Text("发送测试通知") }
        }
    }
}

@Composable
fun TutorialPage(scrollState: ScrollState, navigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // 锚点滚动像素为手写近似值（跳转允许略偏）：新增小节时按前后节高
    // （短节约 130dp、多行/带按钮节约 260dp）估算并顺延后续 offset。
    val anchors = listOf(
        "签到启用教程" to 0,
        "开启监控" to 260,
        "磁贴快捷开关" to 520,
        "开启自动签到" to 780,
        "查看课表" to 1040,
        "导出日历" to 1300,
        "考试安排与提醒" to 1430,
        "成绩与长图分享" to 1560,
        "模拟成绩" to 1690,
        "桌面小卡片" to 1820,
        "设置微信通知" to 2080,
        "设置 QQ 邮箱通知" to 2360,
        "下载课件" to 2620,
        "外观主题" to 2880,
        "常见问题" to 3010,
    )
    SectionCard("教程") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            anchors.forEach { (title, offset) ->
                OutlinedButton(
                    onClick = { scope.launch { scrollState.animateScrollTo(offset) } },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(title, fontWeight = FontWeight.Bold)
                }
            }
        }
        TutorialSection("签到启用教程") {
            Text("1. 在首页输入学号和密码，点击登录。")
            Text("2. 登录成功后点击启动监控。")
            Text("3. 需要自动处理数字签到和雷达签到时，再打开开启自动签到。")
            Text("账号、Cookie、通知配置和下载目录只保存在本机。")
            OutlinedButton(onClick = { navigate("首页") }) { Text("前往首页") }
        }
        TutorialSection("开启监控") {
            Text("1. 监控会按策略页的默认轮询间隔检查签到。")
            Text("2. 检测到签到后会在签到情况页展示课程、类型、剩余时间和状态。")
            Text("3. 手机端需要允许通知和后台运行，否则系统可能会限制常驻。")
            OutlinedButton(onClick = { navigate("签到情况") }) { Text("查看签到情况") }
        }
        TutorialSection("磁贴快捷开关") {
            Text("1. 下拉通知栏展开快捷设置面板，长按编辑，找到「签到监控」磁贴。")
            Text("2. 把磁贴拖到常用位置，之后不打开 App 点一下就能启动或暂停监控。")
            Text("3. 磁贴点亮表示监控运行中，灰色表示已暂停；与 App 内开关状态同步。")
            Text("4. 未登录时磁贴不可用，点击会跳到首页提示先登录。")
            Text("5. 长按桌面 App 图标也能看到「签到情况 / 课表 / 成绩」快捷方式直达对应页面。")
        }
        TutorialSection("开启自动签到") {
            Text("1. 自动签到只处理数字签到和雷达签到。")
            Text("2. 二维码签到只提醒，不会自动处理。")
            Text("3. 如果担心误操作，可以先关闭自动签到，只保留监控提醒。")
            OutlinedButton(onClick = { navigate("策略") }) { Text("前往策略") }
        }
        TutorialSection("查看课表") {
            Text("1. 进入课表页后自动读取本学期排课，按周显示课程格子。")
            Text("2. 左右滑动切换星期，顶部可以切换第几周。")
            Text("3. 格子显示课程名、节次和当周教室，不显示老师。")
            Text("4. 点击某个课程格子，可以看到老师、完整周次和平行教学班详情。")
            Text("5. 平行教学班（同一时间多个教室）会在格子显示第一个教室，点开看全部。")
            OutlinedButton(onClick = { navigate("课表") }) { Text("前往课表") }
        }
        TutorialSection("导出日历") {
            Text("1. 课表页右上角点击「导出日历」，选择系统日历或邮件应用打开即可导入。")
            Text("2. 单双周课程会按隔周重复写入，断档的周次不会多排。")
            Text("3. 上课日期以课表页校准后的开学日为准，校准过再导出更准确。")
            Text("4. 节假日调休由学校另行安排，导入的课程不会自动跳过。")
            OutlinedButton(onClick = { navigate("课表") }) { Text("前往课表") }
        }
        TutorialSection("考试安排与提醒") {
            Text("1. 考试安排页查看各学期考试的时间、地点和座位，顶栏可切换学期，下拉即刷新。")
            Text("2. 首次进入显示骨架占位，读取完成后展示列表。")
            Text("3. 在策略页开启「考试提醒」并设置提前分钟数，到点会发通知提醒。")
            Text("4. Android 12 及以上需要在系统设置允许「闹钟和提醒」权限，点击策略页的设置入口可直接跳转。")
            Text("5. 可选「全屏提醒」：锁屏时也能弹出横幅（需要系统允许全屏通知）。")
            OutlinedButton(onClick = { navigate("考试安排") }) { Text("前往考试安排") }
        }
        TutorialSection("成绩与长图分享") {
            Text("1. 成绩页点击刷新按钮拉取各学期成绩，卡片显示课程、成绩、学分与绩点。")
            Text("2. 页面顶部汇总面板展示总学分、平均绩点等整体情况。")
            Text("3. 点击「分享长图」可把成绩单生成为一张长图，保存或分享给他人。")
            Text("4. 成绩数据按账号加密保存在本机，换账号登录会自动清空。")
            OutlinedButton(onClick = { navigate("成绩") }) { Text("前往成绩") }
        }
        TutorialSection("模拟成绩") {
            Text("1. 成绩页顶部点击「模拟成绩」，页面跳到底部的模拟区。")
            Text("2. 填入预计出分课程的成绩（0-100）和学分，可添加多行，每行可单独删除。")
            Text("3. 面板实时显示计入模拟行后的整体平均绩点、加权绩点、平均分数、加权分数；模拟课不计入已修总学分。")
            Text("4. 空、非法或超范围的行自动跳过，不影响其余计算。")
            Text("5. 换算采用厦大官方 4.0 分制，纯本地计算，不上传任何数据。")
            OutlinedButton(onClick = { navigate("成绩") }) { Text("前往成绩") }
        }
        TutorialSection("桌面小卡片") {
            Text("1. 在策略页开启「桌面小卡片」，然后点击「添加到桌面」。")
            Text("2. 系统会弹出固定小部件窗口，选一个位置放置即可。")
            Text("3. 小卡片显示今天的课程、时间和地点，点击可打开 App 课表页。")
            Text("4. 课表刷新后小卡片会自动更新；在策略页关闭开关可隐藏内容。")
            Text("5. 如果没弹出添加窗口，可以长按手机桌面空白处 -> 小部件 -> 找到 xmu助手。")
            OutlinedButton(onClick = { navigate("策略") }) { Text("前往策略") }
        }
        TutorialSection("设置微信通知") {
            Text("1. 打开通知页，开启微信通知。")
            Text("2. 填入 PushPlus Token，保存后点击发送测试通知。")
            Text("3. 收到测试消息后，说明微信通知可用。")
            Text("PushPlus 会收取约 3.5 元实名费用。", color = themeWarning(), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { navigate("通知") }) { Text("前往通知") }
        }
        TutorialSection("设置 QQ 邮箱通知") {
            Text("1. 在 QQ 邮箱网页版开启 SMTP 服务，并生成授权码。")
            Text("网页版", color = themeWarning(), fontWeight = FontWeight.Bold)
            Text("打开网页 -> 点击设置 -> 点击账号与安全 -> 安全设置 -> 下滑找到生成入口。")
            Text("2. 回到通知页，填写发件 QQ 邮箱、SMTP 授权码和收件邮箱。")
            Text("3. QQ 邮箱免费使用；授权码不是 QQ 密码。")
            Text("4. 保存后点击发送测试通知，确认邮箱能收到提醒。")
            OutlinedButton(onClick = { navigate("通知") }) { Text("前往通知") }
        }
        TutorialSection("下载课件") {
            Text("1. 进入课程课件页，先点击刷新课程。")
            Text("2. 选择学年、学期和课程，再点击刷新课件。")
            Text("3. 勾选需要的课件，点击下载；也可以点击全选后统一下载。")
            Text("4. 文件会尽量直接下载；视频、网页、H5 或 SCORM 无法直接下载时，会保存平台入口。")
            Text("5. 下载失败的课件会在卡片里显示原因，例如平台未提供地址、登录过期、网络失败。")
            OutlinedButton(onClick = { navigate("课程课件") }) { Text("前往课程课件") }
        }
        TutorialSection("外观主题") {
            Text("1. 策略页底部的「外观」可选浅色、深色或跟随系统。")
            Text("2. 切换即时生效并自动记忆，下次启动保持上次选择。")
            Text("3. 桌面小卡片会同步使用深色配色，夜间不刺眼。")
            OutlinedButton(onClick = { navigate("策略") }) { Text("前往策略") }
        }
        TutorialSection("常见问题") {
            Text(
                "重要：系统后台限制无法绕过。手机锁屏后，Android 系统（尤其国产系统）可能延迟或停止后台服务，自动签到和提醒最多可能延迟约 6 小时（系统数据同步周期）。",
                color = themeWarning(),
                fontWeight = FontWeight.Bold,
            )
            Text(
                "建议：把 xmu助手 加入电池不限制 / 允许后台运行白名单，保持通知开启；重要签到请同时手动确认，不要只依赖自动签到。",
                color = themeWarning(),
                fontWeight = FontWeight.Bold,
            )
            Text("关闭 App 后，监控服务会尽量在后台运行；彻底退出需要在系统后台或通知里停止。")
            Text("如果课程或课件刷不出来，先重新登录，再刷新课程。")
            Text("如果通知测试失败，优先检查 Token、SMTP 授权码、收件邮箱和网络。")
            Text("如果平台没有提供资源地址，软件不会绕过平台限制，只会保存入口并显示原因。")
        }
    }
}

@Composable
fun StrategyPage(
    settings: AssistantSettings,
    termCode: String,
    current: RollcallSettings,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    onSaved: (RollcallSettings) -> Unit,
    onWidgetToggle: (Boolean) -> Unit,
    onAddWidget: () -> Unit,
    onManualWeekSet: (Int) -> Unit,
    examReminder: ExamReminderSettings = ExamReminderSettings(),
    onExamReminderChanged: (ExamReminderSettings) -> Unit = {},
    onOpenExamAlarmSettings: () -> Unit = {},
    onOpenFullScreenSettings: () -> Unit = {},
) {
    var interval by rememberSaveable { mutableStateOf(current.pollIntervalSeconds.toString()) }
    var number by rememberSaveable { mutableStateOf(current.autoAnswerNumber) }
    var radar by rememberSaveable { mutableStateOf(current.autoAnswerRadar) }
    // 手动周次用本地 State 持有，确保切换开关/选周时 UI 立即重组（settings 非 State）。
    // 以 termCode 为键：学期变化（新学期/缓存重载）后重读该学期的校准值，
    // 否则本地副本停留在旧学期，与 SchedulePage 实际生效值不一致。
    var manualWeek by rememberSaveable(termCode) { mutableStateOf(settings.manualAcademicWeek(termCode)) }
    // 轮询间隔即时校验：只允许数字，非空时必须为 1-300；空串保存时回退默认 30
    val intervalInvalid = !pollIntervalSecondsValid(interval)
    // 校准按钮范围：官方校历表内学期用真实周数（18/19），表外学期按 19 兜底，
    // 与反推默认总周数一致，保证 19 周长学期也能手动校准到第 19 周。
    val calibrationMaxWeek = xmuAcademicCalendarForTerm(termCode)?.totalWeeks ?: 19
    // 考试提醒：本地 State + 变更即回调（由 MainActivity 持久化并重排闹钟）
    var reminderEnabled by rememberSaveable { mutableStateOf(examReminder.enabled) }
    var reminderMinutes by rememberSaveable { mutableStateOf(examReminder.advanceMinutes.toString()) }
    var reminderFullScreen by rememberSaveable { mutableStateOf(examReminder.fullScreenEnabled) }
    val reminderMinutesInvalid = reminderMinutes.toIntOrNull()?.let { it in 0..60 } != true && reminderMinutes.isNotBlank()
    fun emitReminder() {
        onExamReminderChanged(
            ExamReminderSettings(
                enabled = reminderEnabled,
                advanceMinutes = reminderMinutes.toIntOrNull()?.coerceIn(0, 60) ?: 30,
                fullScreenEnabled = reminderFullScreen,
            ),
        )
    }
    SectionCard("策略") {
        OutlinedTextField(
            value = interval,
            onValueChange = { interval = it.filter(Char::isDigit) },
            label = { Text("默认轮询间隔（1-300 秒）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = intervalInvalid,
            supportingText = {
                if (intervalInvalid) Text("请输入 1-300 的整数；留空将使用默认 30 秒")
            },
        )
        Text("二维码签到只提醒；数字签到和雷达签到可按开关自动处理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("数字签到自动处理", number) { number = it }
        ToggleRow("雷达签到自动处理", radar) { radar = it }
        Button(
            onClick = {
                if (intervalInvalid) return@Button
                val saved = RollcallSettings((interval.toIntOrNull() ?: 30).coerceIn(1, 300), number, radar)
                settings.saveRollcall(saved)
                onSaved(saved)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("确认更改") }
    }
    SectionCard("考试提醒") {
        Text(
            "每场未完成考试在开考前提醒一次（强提醒：响铃 + 震动）。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow("开启考试提醒", reminderEnabled) {
            reminderEnabled = it
            emitReminder()
        }
        OutlinedTextField(
            value = reminderMinutes,
            // 防抖提交：仅更新本地文本，停止输入 500ms 后由 LaunchedEffect 提交一次，
            // 避免逐字符触发全量闹钟重排（100 次 cancelAll + 全量 setExact）与 gate 占用丢值。
            onValueChange = { reminderMinutes = it.filter(Char::isDigit) },
            label = { Text("提前提醒时间（0-60 分钟）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = reminderMinutesInvalid,
            supportingText = {
                if (reminderMinutesInvalid) Text("请输入 0-60 的整数")
            },
        )
        LaunchedEffect(reminderMinutes) {
            if (reminderMinutes != examReminder.advanceMinutes.toString()) {
                kotlinx.coroutines.delay(500)
                // 非法/空白输入不提交：否则会被静默钳制（999→60、空→30）并持久化 +
                // 触发全量闹钟重排，与端口校验「非法时提示而不是静默钳制」的约定矛盾
                if (reminderMinutesInvalid || reminderMinutes.isBlank()) return@LaunchedEffect
                emitReminder()
            }
        }
        ToggleRow("锁屏全屏提醒（闹钟式）", reminderFullScreen) {
            reminderFullScreen = it
            emitReminder()
        }
        Text(
            "全屏提醒需要系统授权「闹钟与提醒」和「全屏通知」；未授权时仍会以高优先级通知提醒。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenExamAlarmSettings,
                modifier = Modifier.weight(1f),
            ) { Text("授权精确闹钟") }
            OutlinedButton(
                onClick = onOpenFullScreenSettings,
                modifier = Modifier.weight(1f),
            ) { Text("授权全屏通知") }
        }
    }
    SectionCard("课表周次") {
        Text(
            "课表默认按教务系统自动判断当前周次；如果判断不准（如假期或新学期），" +
                "校准一次当前周次即可，之后仍会按日期自动推进（明天同周、下周自动 +1）。" +
                "校准不是关闭自动判断，两者同时生效。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 状态行：自动判断始终开启；手动校准只是额外基准，不与之对立
        Text(
            if (manualWeek == 0) {
                "当前：自动判断周次"
            } else {
                "当前：已校准为第 ${manualWeek} 周为基准，仍在自动推进"
            },
            fontWeight = FontWeight.Bold,
            color = if (manualWeek == 0) themePrimary() else themeWarning(),
        )
        var calibrating by remember { mutableStateOf(false) }
        if (calibrating) {
            Text("点击指定当前是第几周：", fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                (1..calibrationMaxWeek).forEach { week ->
                    OutlinedButton(
                        onClick = {
                            calibrating = false
                            manualWeek = week
                            onManualWeekSet(week)
                        },
                        shape = RoundedCornerShape(12.dp),
                        // 触控目标 ≥48dp（无障碍基线，与其余交互组件一致）；行本身可横向滚动，加大不挤布局
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text("$week", fontWeight = if (manualWeek == week) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            OutlinedButton(
                onClick = { calibrating = false },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("取消") }
        } else {
            Button(
                onClick = { calibrating = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (manualWeek == 0) "校准当前周次" else "重新校准当前周次") }
        }
        if (manualWeek != 0) {
            OutlinedButton(
                onClick = {
                    manualWeek = 0
                    onManualWeekSet(0)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清除校准，恢复纯自动判断") }
        }
    }
    SectionCard("桌面小卡片") {
        Text("在手机桌面添加「今日课程」小卡片，不用打开 App 也能看到当天课程、时间和地点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
// 本地乐观状态：settings.widgetEnabled 非组合状态，拨动后若不本地回显，
        // 开关要等下一次无关重组（如 toast）才更新，看起来像没拨动
        var widgetEnabled by remember { mutableStateOf(settings.widgetEnabled) }
        ToggleRow("显示桌面小卡片", widgetEnabled) { enabled ->
            widgetEnabled = enabled
            onWidgetToggle(enabled)
        }
        Button(
            onClick = onAddWidget,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("添加到桌面") }
    }
    SectionCard("外观") {
        Text("选择主题模式：", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OptionRow(
            label = "主题",
            options = listOf("跟随系统", "浅色", "深色"),
            selected = when (themeMode) {
                THEME_MODE_LIGHT -> "浅色"
                THEME_MODE_DARK -> "深色"
                else -> "跟随系统"
            },
        ) { selectedLabel ->
            onThemeModeChanged(
                when (selectedLabel) {
                    "浅色" -> THEME_MODE_LIGHT
                    "深色" -> THEME_MODE_DARK
                    else -> THEME_MODE_SYSTEM
                },
            )
        }
    }
}
