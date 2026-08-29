package com.xmu.assistant

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 考试安排页。
 *
 * 交互契约：
 * - 进入页面不自动拉数据：先读缓存 + 悄悄检查变化（列表增删 / 未完成→已完成）
 * - 有变化才自动刷新，并显示底部横幅「检测到变化，自动更新」；无变化静默
 * - 手动刷新按钮始终可用；支持下拉手势触发同一刷新链路（PullToRefreshBox，
 *   material3 1.3.0 为 Experimental API，页面为本页顶层 LazyColumn 的非嵌套
 *   标准用法，规避 issuetracker 356039090 的嵌套布局指示器残留问题）
 * - 无缓存首次加载显示骨架占位（简单灰块 + 呼吸透明度，不引 shimmer 依赖）
 * - 学期切换器只列「有效学期」（探测到有数据），默认最近有效学期
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamPage(
    summary: XmuTermExamSummary?,
    validTerms: List<String>,
    selectedTerm: String,
    loading: Boolean,
    refreshError: String,
    autoUpdated: Boolean,
    loggedIn: Boolean,
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

    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        item {
            SectionCard(stringResource(R.string.exam_page_title)) {
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
                    ) { Text(if (loading) stringResource(R.string.exam_page_refreshing) else stringResource(R.string.exam_page_refresh)) }
                }
                RefreshStateBanner(loading = loading, errorMessage = refreshError, hasData = summary != null)
                if (autoUpdated) {
                    Text(
                        stringResource(R.string.exam_page_auto_updated),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        // 首次加载（无缓存且拉取中）：骨架占位代替空白列表
        if (summary == null && loading) {
            item { ExamSkeleton() }
            return@LazyColumn
        }

        if (summary == null && !loading) {
            item {
                // 空态三档之「未登录」：给出登录引导而非"暂无数据"（用户此时刷新只会得到
                // 弹出的请先登录提示，页面自身无解释）
                if (!loggedIn) {
                    EmptyState(stringResource(R.string.exam_page_empty_not_logged_in_title), stringResource(R.string.exam_page_empty_not_logged_in_body))
                } else {
                    // 错误已由 RefreshStateBanner 展示，这里不重复塞入（避免双份文案）
                    EmptyState(stringResource(R.string.exam_page_empty_title), stringResource(R.string.exam_page_empty_body))
                }
            }
            return@LazyColumn
        }

        // 未完成（未到考试时间）
        if (upcoming.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.exam_page_upcoming, upcoming.size),
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
                    stringResource(R.string.exam_page_finished, finished.size),
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
                    stringResource(R.string.exam_page_unarranged, unarranged.size),
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
                EmptyState(stringResource(R.string.exam_page_term_empty_title), stringResource(R.string.exam_page_term_empty_body))
            }
        }
        }
    }
}

/** 首次加载骨架占位：简单灰块 + 呼吸透明度（纯 Compose 动画，不引 shimmer 依赖）。 */
@Composable
private fun ExamSkeleton(rows: Int = 3) {
    val transition = rememberInfiniteTransition(label = "exam-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "exam-skeleton-pulse",
    )
    val blockColor = MaterialTheme.colorScheme.onSurfaceVariant
    repeat(rows) {
        InfoCard {
            SkeletonLine(modifier = Modifier.fillMaxWidth(), height = 18.dp, alpha = pulse, color = blockColor)
            Spacer(Modifier.height(8.dp))
            SkeletonLine(modifier = Modifier.fillMaxWidth(0.62f), height = 13.dp, alpha = pulse, color = blockColor)
            Spacer(Modifier.height(4.dp))
            SkeletonLine(modifier = Modifier.fillMaxWidth(0.4f), height = 13.dp, alpha = pulse, color = blockColor)
        }
    }
}

@Composable
private fun SkeletonLine(modifier: Modifier, height: androidx.compose.ui.unit.Dp, alpha: Float, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = modifier
            .height(height)
            .alpha(alpha)
            .background(color.copy(alpha = 0.35f), RoundedCornerShape(4.dp)),
    )
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
