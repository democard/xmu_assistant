package com.xmu.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 考试安排页。
 *
 * 交互契约：
 * - 进入页面不自动拉数据：先读缓存 + 悄悄检查变化（列表增删 / 未完成→已完成）
 * - 有变化才自动刷新，并显示底部横幅「检测到变化，自动更新」；无变化静默
 * - 手动刷新按钮始终可用
 * - 学期切换器只列「有效学期」（探测到有数据），默认最近有效学期
 */
@Composable
fun ExamPage(
    activity: androidx.activity.ComponentActivity,
    summary: XmuTermExamSummary?,
    validTerms: List<String>,
    selectedTerm: String,
    loading: Boolean,
    refreshError: String,
    autoUpdated: Boolean,
    onSelectTerm: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    // 转屏保活滚动位置
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // 用「考试结束时刻」划分未完成/已完成：当天已结束的考试归入已完成，更准确。
    // 每分钟刷新一次：页面停留时考试跨过结束时刻也会从未完成滚到已完成（不与实际状态漂移）。
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // 对齐下一分钟边界再更新（避免固定 60s 累积漂移，跨结束时刻的分档更准时）
            val now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000 - now % 60_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // 归属守卫：只展示与当前所选学期匹配的摘要，防止切学期/续登在途时残留的
    // 旧学期 summary 串显到新标签下（与 selectTerm 的清空双保险）。
    val termSummary = remember(summary, selectedTerm) {
        summary?.takeIf { it.termCode == selectedTerm }
    }
    val upcoming = remember(termSummary, nowMillis) {
        termSummary?.exams?.filter { exam -> examEndEpochMillis(exam) > nowMillis }
            ?.sortedBy { it.date + it.timeRange } ?: emptyList()
    }
    val finished = remember(termSummary, nowMillis) {
        termSummary?.exams?.filter { exam -> examEndEpochMillis(exam) <= nowMillis }
            ?.sortedByDescending { it.date + it.timeRange } ?: emptyList()
    }
    val unarranged = remember(termSummary) { termSummary?.unarranged ?: emptyList() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard("考试安排") {
                // 学期切换器：列出全部有效学期（横向滚动），默认最近有效学期
                if (validTerms.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        validTerms.forEach { term ->
                            val active = term == selectedTerm
                            if (active) {
                                Button(onClick = { onSelectTerm(term) }) { Text(termLabelShort(term)) }
                            } else {
                                OutlinedButton(onClick = { onSelectTerm(term) }) { Text(termLabelShort(term)) }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onRefresh,
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (loading) "刷新中" else "刷新考试安排") }
                }
                RefreshStateBanner(loading = loading, errorMessage = refreshError, hasData = summary != null)
                if (autoUpdated) {
                    Text(
                        "检测到变化，自动更新",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        if (summary == null && !loading) {
            item {
                // 错误已由 RefreshStateBanner 展示，这里不重复塞入（避免双份文案）
                EmptyState("暂无考试数据", "点击上方刷新按钮获取考试安排。")
            }
            return@LazyColumn
        }

        // 未完成（未到考试时间）
        if (upcoming.isNotEmpty()) {
            item {
                Text(
                    "未完成（${upcoming.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(upcoming.size) { index ->
                ExamCard(upcoming[index])
            }
        }

        // 已完成（考试时间已过）
        if (finished.isNotEmpty()) {
            item {
                Text(
                    "已完成（${finished.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(finished.size) { index ->
                ExamCard(finished[index])
            }
        }

        // 未安排（底部）
        if (unarranged.isNotEmpty()) {
            item {
                Text(
                    "未安排（${unarranged.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(unarranged.size) { index ->
                InfoCard {
                    Text(unarranged[index].courseName, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (summary != null && upcoming.isEmpty() && finished.isEmpty() && unarranged.isEmpty()) {
            item {
                EmptyState("该学期暂无安排", "本学期没有已排考试或未安排课程。")
            }
        }
    }
}

@Composable
private fun ExamCard(exam: XmuExam) {
    InfoCard {
        Text(exam.courseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "${exam.date}  ${exam.timeRange}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            exam.room,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** 考试结束时刻（epoch 毫秒）：日期 + 时间段结束时间；跨午夜（结束早于开始）日期 +1。
 *  解析失败返回 Long.MAX_VALUE（视为未完成，避免误归已完成）。 */
private fun examEndEpochMillis(exam: XmuExam): Long {
    val date = runCatching { java.time.LocalDate.parse(exam.date) }.getOrNull() ?: return Long.MAX_VALUE
    val parts = exam.timeRange.split("-").map { it.trim() }
    if (parts.size != 2) return Long.MAX_VALUE
    val start = runCatching { java.time.LocalTime.parse(parts[0]) }.getOrNull() ?: return Long.MAX_VALUE
    val end = runCatching { java.time.LocalTime.parse(parts[1]) }.getOrNull() ?: return Long.MAX_VALUE
    val effectiveDate = if (end < start) date.plusDays(1) else date
    return java.time.LocalDateTime.of(effectiveDate, end)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/** 学期短标签：2025-2026-2 → 25-26第二。普适，不写死任何年份。 */
internal fun termLabelShort(termCode: String): String {
    val match = Regex("""^(\d{4})-(\d{4})-(\d)$""").find(termCode) ?: return termCode
    val shortYear = match.groupValues[1].takeLast(2)
    val semester = when (match.groupValues[3]) {
        "1" -> "一"
        "2" -> "二"
        else -> "三"
    }
    return "${shortYear}-${match.groupValues[2].takeLast(2)}第${semester}学期"
}
