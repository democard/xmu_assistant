package com.xmu.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun RollcallStatusPage(
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
    monitorRunning: Boolean = false,
    onRefresh: () -> Unit,
    onOpenTronclass: (RollcallEvent?) -> Unit = {},
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LaunchedEffect(openedEventId, events) {
        if (openedEventId.isNotBlank()) {
            val activeIndex = events.indexOfFirst { it.id == openedEventId }
            if (activeIndex >= 0) {
                // index 0 = top sync card, index 1 = section header, index 2 + activeIndex = card
                listState.animateScrollToItem(2 + activeIndex)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            PreviewCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val lastSyncMillis = maxOf(updatedAtMillis, historyUpdatedAtMillis)
                            Text(
                                if (loading || historyLoading) {
                                    "正在同步…"
                                } else {
                                    "上次同步 ${if (lastSyncMillis > 0L) formatMonitorTime(lastSyncMillis) else "刚刚"}"
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            PreviewBadge(
                                if (monitorRunning) "监控中" else "未监控",
                                if (monitorRunning) PreviewBadgeTone.GREEN else PreviewBadgeTone.AMBER,
                            )
                        }
                        Text(
                            "进行中 ${events.size} 项 · 最近记录 ${historyItems.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !loading && !historyLoading,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(if (loading || historyLoading) "刷新中" else "刷新", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "仅展示进行中的签到类型、进度与近期出勤状态，请前往畅课完成签到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (events.isNotEmpty()) {
                    Button(
                        onClick = { onOpenTronclass(events.firstOrNull()) },
                        enabled = loggedIn,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "打开畅课完成签到 ›",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { onOpenTronclass(null) },
                        enabled = loggedIn,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "打开畅课 ›",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (loading || historyLoading || refreshError.isNotBlank()) {
                    RefreshStateBanner(
                        loading = loading || historyLoading,
                        errorMessage = refreshError,
                        hasData = events.isNotEmpty() || historyItems.isNotEmpty(),
                    )
                }
                notificationEventLabel(openedEventId, events, historyItems)?.let { label ->
                    Text(label, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (events.isNotEmpty()) {
            item { PreviewSectionHeader("正在签到", "${events.size} 项") }
            // 同一签到也会出现在最近记录中，LazyColumn 的 key 必须跨区块唯一。
            items(events, key = { "active:${it.id}" }) { event ->
                ActiveRollcallPreviewCard(
                    event = event,
                    highlighted = openedEventId.isNotBlank() && event.id == openedEventId,
                    onOpenTronclass = { onOpenTronclass(event) },
                )
            }
        }
        item {
            PreviewSectionHeader(
                "最近签到记录",
                if (historyItems.isNotEmpty()) "最近 ${historyItems.size} 次" else "",
            )
        }
        if (historyLoading) {
            item { Text("正在读取最近签到…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
        } else if (historyError.isNotBlank()) {
            item { RefreshStateBanner(loading = false, errorMessage = historyError, hasData = historyItems.isNotEmpty()) }
        }
        if (historyItems.isEmpty()) {
            item {
                if (!loggedIn) EmptyState("请先登录", "登录后拉取最近签到记录。")
                else EmptyState("暂无签到记录", "点击顶部刷新即可读取最近记录。")
            }
        } else {
            items(historyItems, key = { "history:${it.rollcallId}" }) { item ->
                CompactRollcallHistoryCard(item = item)
            }
        }
    }
}

@Composable
private fun ActiveRollcallPreviewCard(
    event: RollcallEvent,
    highlighted: Boolean = false,
    onOpenTronclass: () -> Unit = {},
) {
    val resolvedStatus = event.ownStatus ?: event.status
    val isSigned = isTerminalRollcallStatus(resolvedStatus)
    val statusBadgeText = if (isSigned) "本人已签" else "待签到"
    val statusBadgeTone = if (isSigned) PreviewBadgeTone.GREEN else PreviewBadgeTone.AMBER

    PreviewCard(
        modifier = Modifier,
        soft = highlighted,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(event.courseTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("发起人：${event.teacher}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PreviewBadge(event.type)
            PreviewBadge(statusBadgeText, statusBadgeTone)
        }
        val progress = event.progress
        if (progress != null && progress.total > 0) {
            Text(
                "${progress.present} / ${progress.total} 人已签到",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (progress.present.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (isSigned) "可点击右侧核实或打开畅课" else "请前往畅课完成签到",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onOpenTronclass,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("打开畅课签到 ›", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompactRollcallHistoryCard(item: RollcallHistoryItem) {
    val displayStatus = historyRollcallDisplayStatus(item.ownStatus)
    val isAbsent = displayStatus == "缺勤" || displayStatus == "未签"
    val progressText = item.progress?.takeIf { it.total > 0 }?.let { "${it.present} / ${it.total} 人" }
    PreviewCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = if (isAbsent) themeSoftRed() else themeSoftGreen(),
                shape = RoundedCornerShape(11.dp),
            ) {
                Text(
                    if (isAbsent) "!" else "✓",
                    color = if (isAbsent) themeWarning() else themeSuccess(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.courseTitle, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.timeDisplay} · ${item.type}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewBadge(displayStatus, if (isAbsent) PreviewBadgeTone.RED else PreviewBadgeTone.GREEN)
                if (progressText != null) {
                    Text(
                        progressText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
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
    rankLabel: String = "尚未获取",
    onOpenRank: (() -> Unit)? = null,
) {
    var simulationRows by rememberSaveable(stateSaver = ScoreSimulationRowsSaver) {
        mutableStateOf<List<Pair<String, String>>>(emptyList())
    }
    val validSimulationCount = remember(simulationRows) {
        simulationRows.count { (score, credit) -> xmuSimulatedScoreRecord(score, credit) != null }
    }
    // The overview and the simulation panel must show the same live result. Keeping
    // simulation state inside the bottom section left the overview frozen at real grades.
    val currentSummary = remember(records) { xmuScoreSummary(records) }
    val summary = remember(records, simulationRows) { xmuSimulatedSummary(records, simulationRows) }
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
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶部成绩概览卡片固定，下方课程与模拟成绩列表独立滚动
        PreviewCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("成绩概览", fontWeight = FontWeight.Bold)
                    Text(
                        if (updatedAtMillis > 0L) "上次更新 ${formatMonitorTime(updatedAtMillis)}" else "尚未更新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewBadge(
                    if (validSimulationCount > 0) "${records.size} 已出 · $validSimulationCount 模拟"
                    else "${records.size} 门",
                )
            }
            if (records.isNotEmpty() || validSimulationCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactScoreMetric("加权平均分", scoreMetricText(summary.weightedScore), Modifier.weight(1f), prominent = true)
                    CompactScoreMetric("加权 GPA", scoreMetricText(summary.weightedGpa), Modifier.weight(1f), prominent = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactScoreMetric("算术平均分", scoreMetricText(summary.averageScore), Modifier.weight(1f), prominent = false)
                    CompactScoreMetric("已修总学分", scoreMetricText(summary.completedCredits), Modifier.weight(1f), prominent = false)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(if (loading) "刷新中" else "刷新成绩", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onShareScore,
                    enabled = records.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("分享长图", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("模拟 ↓", maxLines = 1)
                }
            }
            if (loading || refreshError.isNotBlank()) {
                RefreshStateBanner(loading = loading, errorMessage = refreshError, hasData = records.isNotEmpty())
            }
            if (onOpenRank != null) {
                Surface(
                    onClick = onOpenRank,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("专业排名与绩点证明", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("$rankLabel ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = themePrimary())
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                recordsByTerm.forEach { (term, termRecords) ->
                    val termCredits = termRecords.sumOf { it.credit }
                    item(key = "term:$term") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(term, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(
                                "${termRecords.size} 门 · ${String.format(Locale.US, "%.1f", termCredits)} 学分",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
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
                ScoreSimulationSection(
                    currentSummary = currentSummary,
                    simulatedSummary = summary,
                    simulationRows = simulationRows,
                    onRowsChanged = { simulationRows = it },
                )
            }
        }
    }
}

@Composable
private fun CompactScoreMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (prominent) 0.72f else 0.42f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        if (prominent) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
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
 * 实时显示「当前 → 计入后」的平均成绩、平均绩点、加权成绩、加权绩点。
 * 纯本地换算（xmuSimulatedSummary），零网络请求；无效行不参与计算、不打断报错。
 */
@Composable
private fun ScoreSimulationSection(
    currentSummary: XmuScoreSummary,
    simulatedSummary: XmuScoreSummary,
    simulationRows: List<Pair<String, String>>,
    onRowsChanged: (List<Pair<String, String>>) -> Unit,
) {
    val validRowCount = simulationRows.count { (score, credit) -> xmuSimulatedScoreRecord(score, credit) != null }
    SectionCard("模拟成绩") {
        Text(
            "填入预计成绩与学分，平均和加权四项都会实时更新；模拟课不计入已修总学分。",
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
                    onValueChange = { onRowsChanged(simulationRows.withRowReplaced(index, it to creditText)) },
                    label = { Text("预计成绩") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = creditText,
                    onValueChange = { onRowsChanged(simulationRows.withRowReplaced(index, scoreText to it)) },
                    label = { Text("学分") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedButton(
                    onClick = { onRowsChanged(simulationRows.filterIndexed { i, _ -> i != index }) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text("删除") }
            }
        }
        OutlinedButton(onClick = { onRowsChanged(simulationRows + ("" to "")) }) {
            Text("+ 添加一门")
        }
        if (simulationRows.isNotEmpty() && validRowCount == 0) {
            Text(
                "当前行尚未计入：成绩需在 0–100、学分需大于 0",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ScoreSimulationSummaryPanel(currentSummary, simulatedSummary)
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

/**
 * 本地可编辑的布尔副本，并在每次进入组合时以「已保存值」为准重同步。
 *
 * 为什么不能用 `rememberSaveable(saved) { ... }` 的 inputs 同步：inputs 只在**组合内**
 * 做失效检测，**状态恢复路径不校验**——SaveableStateHolder 恢复出的旧值优先于 init，
 * 因此跨页面保留的本地副本会一直沿用旧值。策略页正是这种情况：首页改写了同一份
 * RollcallSettings 的自动签到开关，回到策略页仍是旧值，按「确认更改」就把刚开启的
 * 自动签到静默写回（v1.6.1 引入 PageStateHost 后可达）。LaunchedEffect 在每次进入
 * 组合时必跑，故用它重同步；值未变时写入同值不触发重组。
 */
@Composable
internal fun rememberSyncedBoolean(saved: Boolean): MutableState<Boolean> {
    val state = rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { state.value = saved }
    return state
}

internal fun notificationEventLabel(
    openedEventId: String,
    events: List<RollcallEvent>,
    historyItems: List<RollcallHistoryItem>,
): String? {
    if (openedEventId.isBlank()) return null
    val known = events.any { it.id == openedEventId } || historyItems.any { it.rollcallId == openedEventId }
    if (!known) return "从通知打开的签到未在当前记录中"
    val safeId = openedEventId.filterNot { it == '\r' || it == '\n' || it.isISOControl() }.take(64)
    return "从通知打开：$safeId"
}

@Composable
internal fun rememberSyncedText(saved: String): MutableState<String> {
    val state = rememberSaveable { mutableStateOf(saved) }
    LaunchedEffect(saved) { state.value = saved }
    return state
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
    var manualWeek by rememberSaveable(termCode) { mutableStateOf(settings.manualAcademicWeek(termCode)) }
    val intervalInvalid = !pollIntervalSecondsValid(interval)
    val hasUnsavedRollcallChanges = interval != current.pollIntervalSeconds.toString()
    val calibrationMaxWeek = xmuAcademicCalendarForTerm(termCode)?.totalWeeks ?: 19
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
    SectionCard("签到监控") {
        Text(
            "仅用于后台监测新签到并发送提醒；已下线自动签到，检测到新签到请前往畅课完成。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Button(
            onClick = {
                if (intervalInvalid) return@Button
                val saved = current.copy(
                    pollIntervalSeconds = (interval.toIntOrNull() ?: 30).coerceIn(1, 300),
                    autoAnswerNumber = false,
                    autoAnswerRadar = false,
                )
                settings.saveRollcall(saved)
                onSaved(saved)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !intervalInvalid,
        ) { Text("保存监控间隔") }
        Text(
            if (hasUnsavedRollcallChanges) {
                "当前修改尚未保存；点击“保存监控间隔”后生效。"
            } else {
                "当前显示已保存设置。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
