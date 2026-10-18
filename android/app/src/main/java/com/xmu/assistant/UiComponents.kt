package com.xmu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale

/** 通知配置是否缺少必填项（用于顶部标签角标提示）。 */
fun notificationSettingsMissing(settings: NotificationSettings): Boolean {
    val pushMissing = settings.pushPlusEnabled && settings.pushPlusToken.isBlank()
    val qqMissing = settings.qqMailEnabled &&
        (settings.qqMailSender.isBlank() || settings.qqMailPassword.isBlank() || settings.qqMailRecipient.isBlank())
    return pushMissing || qqMissing
}

/** 通知通道状态文案。 */
fun notificationChannelStatus(enabled: Boolean, missing: Boolean): String = when {
    !enabled -> "未开启"
    missing -> "缺少配置"
    else -> "可用"
}

/** 监控状态文案（首页状态区）。 */
fun monitorStatusText(running: Boolean, failures: Int): String = when {
    !running -> "未启动"
    failures >= 3 -> "系统可能限制后台"
    failures > 0 -> "最近失败"
    else -> "运行中"
}

/** 时间戳格式化（HH:mm:ss，首页/各页"上次更新"用）。
 *  DateTimeFormatter 线程安全可做单例（SimpleDateFormat 每次新建有分配开销且非线程安全）。 */
private val monitorTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

fun formatMonitorTime(millis: Long): String {
    if (millis <= 0L) return "-"
    return monitorTimeFormatter.format(
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()),
    )
}

/** 发送本机测试通知。 */
fun sendLocalTestNotification(activity: ComponentActivity) {
    val manager = activity.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("xmu_assistant_test", "虾大助手通知测试", NotificationManager.IMPORTANCE_DEFAULT))
    val pendingIntent = PendingIntent.getActivity(
        activity,
        2001,
        Intent(activity, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    manager.notify(
        2001,
        Notification.Builder(activity, "xmu_assistant_test")
            .setContentTitle("虾大助手 测试通知")
            .setContentText("如果你收到这条消息，说明本机通知可用。")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build(),
    )
}

/**
 * 当前是否深色（由 XmuMobileTheme 按 themeMode 决定：跟随系统 / 强制浅色 / 强制深色）。
 * 所有 theme* 变体函数读取它而不是直接读 isSystemInDarkTheme()，
 * 这样用户手动选择主题后，课表格子/功能底色/警示色等全部跟随。
 */
internal val LocalXmuDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun XmuMobileTheme(themeMode: String = THEME_MODE_SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        THEME_MODE_LIGHT -> false
        THEME_MODE_DARK -> true
        else -> systemDark
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalXmuDarkTheme provides dark) {
        // 体检报告 P1-7：色板必须 remember(dark)。M3 Colors 不覆写 equals，
        // 内联构造会让每次重组都产生新实例 → CompositionLocal 判定变化 →
        // 所有读取 colorScheme 的组件全量重组（登录页每敲一个字符即重跑整棵树）。
        val colorScheme = remember(dark) {
            if (dark) {
                darkColorScheme(
                    primary = Color(0xFF8FAEC8),
                    secondary = Color(0xFFA8C1D4),
                    surface = Color(0xFF17232D),
                    surfaceVariant = Color(0xFF223542),
                    onPrimary = Color(0xFF14212A),
                    onSurface = Color(0xFFEBF2F6),
                    onSurfaceVariant = Color(0xFF9BAAB5),
                    background = Color(0xFF111A22),
                    onBackground = Color(0xFFEBF2F6),
                    outline = Color(0xFF8FA1AE),
                    outlineVariant = Color(0xFF2C3C49),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6683A0),
                    secondary = Color(0xFF7D95AD),
                    surface = Color(0xFFDEE4E7),
                    surfaceVariant = Color(0xFFC4D2DB),
                    onPrimary = Color.White,
                    onSurface = Color(0xFF293C4C),
                    onSurfaceVariant = Color(0xFF637583),
                    background = Color(0xFFCAD5DC),
                    onBackground = Color(0xFF293C4C),
                    outline = Color(0xFF607583),
                    outlineVariant = Color(0xFFB0BEC7),
                )
            }
        }
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}

internal val AppPrimary = Color(0xFF4B6884)
internal val AppHeaderBrush = Brush.linearGradient(
    listOf(Color(0xFF657F9A), Color(0xFF7D95AD)),
)
internal val AppSuccess = Color(0xFF6D8C80)

/** 深色模式下把浅色功能底换成深色变体，避免深色界面出现刺眼亮块。 */
@Composable
internal fun themeSoftGreen(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF1D332B) else Color(0xFFD2DED8)

/** 未登录/警示底色：深色模式换深红变体，避免深色界面出现刺眼亮块。 */
@Composable
internal fun themeSoftRed(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF3A2020) else Color(0xFFFFEEEE)

/** 品牌角标（XMU）底色：深色模式换深琥珀变体，避免刺眼亮块。 */
@Composable
internal fun themeSoftAmber(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF362B22) else Color(0xFFE3D8CE)

/** 品牌角标（XMU）文字色：深色下用更亮的琥珀保证深底可读。 */
@Composable
internal fun themeOnAmber(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFDDB68E) else Color(0xFF7F5F48)

@Composable
internal fun themeSurface(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF17232D) else Color(0xFFDEE4E7)

@Composable
internal fun themePrimarySoft(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF223542) else Color(0xFFC4D2DB)

/** 警示文字色：深色模式下用更亮的红，保证深底上的可读性。 */
@Composable
internal fun themeWarning(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFC62828)

/** 成功文字色：深色模式下用更亮的绿，保证深底上的可读性。 */
@Composable
internal fun themeSuccess(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF9FC8B9) else Color(0xFF58776C)

/** 顶部标签选中底色：深色模式下用浅蓝（AppPrimary 深海军蓝在深底上区分度不足）。 */
@Composable
internal fun themeSelectedTab(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF456B86) else AppPrimary

/** 课表/日程选中的 chip 底色：与 themeSelectedTab 同源，深色下用浅蓝区分深底。 */
@Composable
internal fun themeSelectedChip(): Color = themeSelectedTab()

/** 主色文字变体：深色模式下用浅蓝，避免深海军蓝 AppPrimary 在深底上不可读。 */
@Composable
internal fun themePrimary(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF8FAEC8) else Color(0xFF4B6884)

@Composable
internal fun themeTodayColumn(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF223441) else Color(0xFFE3EDF2)

@Composable
internal fun themeInfoCard(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF1B2B36) else Color(0xFFF2F6F8)

@Composable
internal fun themeFailedCard(): Color =
    if (LocalXmuDarkTheme.current) Color(0xFF3A2323) else Color(0xFFFFEDEA)

@Composable
fun BrandHeader(
    loggedIn: Boolean,
    page: String = "首页",
    showBack: Boolean = false,
    onBack: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            Surface(onClick = onBack, color = themePrimarySoft(), shape = RoundedCornerShape(10.dp)) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            AppLogo(Modifier.size(34.dp), null)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (page == "首页") "虾大助手" else page,
            modifier = Modifier.weight(1f),
            color = themePrimary(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            color = if (loggedIn) themeSoftGreen() else themeSoftRed(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                if (loggedIn) "已登录" else "未登录",
                color = if (loggedIn) themeSuccess() else themeWarning(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun LargeLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.xmu_assistant_logo_large),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AppLogo(modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.xmu_assistant_mark),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun StatusStack(
    account: String,
    monitor: String,
    monitorLastCheck: String,
    monitorFailureCount: Int,
    monitorLastError: String,
    auto: String,
    recent: String,
) {
    SectionCard("守护状态") {
        StatusRow("账号", account)
        StatusRow("监控", monitor)
        StatusRow("最近检查", monitorLastCheck)
        if (monitorFailureCount > 0) {
            StatusRow("连续失败", "${monitorFailureCount} 次")
        }
        if (monitorLastError.isNotBlank()) {
            StatusRow("失败原因", monitorLastError)
        }
        StatusRow("自动签到", auto)
        StatusRow("最近签到", recent)
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.weight(0.65f), fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RefreshStateBanner(loading: Boolean, errorMessage: String, hasData: Boolean) {
    when {
        loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在刷新…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        errorMessage.isNotBlank() -> Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                refreshStateText(errorMessage, hasData),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onChanged)
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(title: String, showTitle: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outlineVariant
    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showTitle) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            content()
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(themeInfoCard(), RoundedCornerShape(12.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLogo(Modifier.size(64.dp), null)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 瞬时提示级别（B4 显式化）：show() 默认 INFO；showWarning/showError 显式标注，
 * 文案子串匹配（"失败"/"请先"）仅作未迁移调用点的兜底，保留不删。
 * WARNING/ERROR 共用 errorContainer 视觉（历史 warning 二态样式不变）。
 */
enum class ToastSeverity { INFO, WARNING, ERROR }

@Composable
fun ToastBar(message: String, severity: ToastSeverity) {
    val warning = severity != ToastSeverity.INFO
    Surface(
        color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            // 非警告的中性提示用中灰（勿用绿色成功色误导：'正在刷新成绩'等不是成功）
            color = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ScoreSummaryPanel(summary: XmuScoreSummary) {
    ScoreSummaryPanelSurface {
        ScoreSummaryMetricRows(summary)
        ScoreMetric("已修总学分", scoreMetricText(summary.completedCredits), Modifier.fillMaxWidth())
    }
}

/** 四项统计的公共两行布局：平均与加权各自成行，顶部概览与模拟面板共用。 */
@Composable
private fun ScoreSummaryMetricRows(summary: XmuScoreSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ScoreMetric("平均绩点", scoreMetricText(summary.averageGpa), Modifier.weight(1f))
        ScoreMetric("加权绩点", scoreMetricText(summary.weightedGpa), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ScoreMetric("平均分数", scoreMetricText(summary.averageScore), Modifier.weight(1f))
        ScoreMetric("加权分数", scoreMetricText(summary.weightedScore), Modifier.weight(1f))
    }
}

/**
 * 模拟成绩面板：每项并排显示「当前 → 计入模拟课程后」，四项都会随输入变化。
 * 平均按课程等权、加权按学分；模拟课不计入已修学分。
 */
@Composable
fun ScoreSimulationSummaryPanel(current: XmuScoreSummary, simulated: XmuScoreSummary) {
    ScoreSummaryPanelSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SimulationDeltaMetric("平均绩点", current.averageGpa, simulated.averageGpa, "sim-avg-gpa", Modifier.weight(1f))
            SimulationDeltaMetric("加权绩点", current.weightedGpa, simulated.weightedGpa, "sim-weighted-gpa", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SimulationDeltaMetric("平均分数", current.averageScore, simulated.averageScore, "sim-avg-score", Modifier.weight(1f))
            SimulationDeltaMetric("加权分数", current.weightedScore, simulated.weightedScore, "sim-weighted-score", Modifier.weight(1f))
        }
        Text(
            "箭头左侧为当前成绩，右侧为计入模拟课程后的结果；模拟课不计入已修总学分。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单项：左值当前、右值计入模拟后；两值并排显示，较小变化也不会被误读成未生效。 */
@Composable
private fun SimulationDeltaMetric(
    label: String,
    before: Double?,
    after: Double?,
    afterTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(68.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    scoreMetricText(before),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text("→", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    scoreMetricText(after),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.testTag(afterTag),
                )
            }
        }
    }
}

@Composable
private fun ScoreSummaryPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun ScoreMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.height(68.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun TermHeader(term: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(term, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("${count} 门", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ScoreRecordCard(record: XmuScoreRecord) {
    PreviewCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(color = if ((record.score ?: 0.0) >= 90.0) themeSoftGreen() else themePrimarySoft(), shape = RoundedCornerShape(11.dp)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text(record.score?.let(::scoreMetricText) ?: scoreResultText(record), fontWeight = FontWeight.Bold, color = if ((record.score ?: 0.0) >= 90.0) themeSuccess() else themePrimary())
                }
            }
            Column(Modifier.weight(1f)) {
                Text(record.courseName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${scoreMetricText(record.credit)} 学分 · 绩点 ${scoreMetricText(record.gradePoint)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun scoreMetricText(value: Double?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "-"

fun scoreRecordDetail(record: XmuScoreRecord): String =
    if (record.countsForStatistics) {
        "成绩 ${scoreMetricText(record.score)} · 绩点 ${scoreMetricText(record.gradePoint)} · 学分 ${scoreMetricText(record.credit)}"
    } else {
        val result = scoreResultText(record)
        if (isTwoLevelScore(record)) {
            "成绩 $result · 学分 ${scoreMetricText(record.credit)}"
        } else {
            "成绩 $result · ${scoreGradeModeText(record)} · 学分 ${scoreMetricText(record.credit)}"
        }
    }

fun scoreResultText(record: XmuScoreRecord): String {
    val rawResult = record.resultText.ifBlank { record.gradeMode.ifBlank { "已记录" } }
    return if (isTwoLevelScore(record)) {
        if (rawResult.equals("NP", ignoreCase = true) || !record.countsForCompletedCredit) "NP" else "P"
    } else {
        rawResult
    }
}

fun scoreGradeModeText(record: XmuScoreRecord): String =
    if (isTwoLevelScore(record)) "P/NP" else record.gradeMode.ifBlank { "非百分制" }

fun isTwoLevelScore(record: XmuScoreRecord): Boolean =
    record.gradeMode == "两级制" ||
        record.gradeMode.equals("P/NP", ignoreCase = true) ||
        record.resultText == "两级制"

@Composable
fun OptionRow(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            if (selected == option) {
                Button(
                    onClick = { onSelected(option) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(option) }
            } else {
                OutlinedButton(
                    onClick = { onSelected(option) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) { Text(option) }
            }
        }
    }
}

@Composable
fun CoursewareItemCard(item: CoursewareUiItem, checked: Boolean, onToggle: () -> Unit) {
    val failed = item.downloadStatus.startsWith("下载失败")
    InfoCard(container = if (failed) themeFailedCard() else MaterialTheme.colorScheme.surface) {
        // 整行可点（toggleable）：只有 Checkbox 可点命中率太低，触控/无障碍都不达标。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .defaultMinSize(minHeight = 48.dp),
        ) {
            // 整行已是 toggleable(role=Checkbox)：内层 Checkbox 清除自身语义，
            // 避免读屏对同一勾选状态重复播报两次
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (item.moduleName.isNotBlank()) Text("章节：${item.moduleName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("文件名：${item.filename.ifBlank { "入口文件" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("类型：${item.type}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("下载状态：${item.downloadStatus}", color = coursewareStatusColor(item.downloadStatus), fontWeight = FontWeight.Bold)
                val reason = item.failureReason.ifBlank {
                    item.downloadStatus.substringAfter("下载失败（", "").substringBefore("）")
                }
                if (reason.isNotBlank() && failed) {
                    Text("失败原因：$reason", color = coursewareStatusColor(item.downloadStatus))
                }
            }
        }
    }
}

@Composable
fun TutorialSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    InfoCard(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

fun friendlyMessage(error: Throwable): String {
    // 类型优先：会话过期已在源头类型化（MainSessionExpiredException），
    // 消息匹配仅作兼容兜底
    if (error is MainSessionExpiredException) return "登录已过期，请重新登录"
    val message = error.message.orEmpty()
    return when (shortCoursewareError(message)) {
        "登录过期" -> "登录已过期，请重新登录"
        "网络失败" -> "网络连接失败，请稍后重试"
        "平台未提供地址" -> "平台没有提供可用地址"
        else -> message.ifBlank { "操作失败" }
    }
}

fun friendlyNotificationMessage(error: Throwable, settings: NotificationSettings): String {
    val message = error.message.orEmpty()
    val lowered = message.lowercase()
    return when {
        message.contains("请先开启") -> message
        settings.pushPlusEnabled && (settings.pushPlusToken.isBlank() || "token" in lowered || "pushplus" in lowered) -> "Token 可能不正确"
        settings.qqMailEnabled && (
            settings.qqMailSender.isBlank() ||
                settings.qqMailPassword.isBlank() ||
                settings.qqMailRecipient.isBlank() ||
                listOf("smtp", "auth", "mail", "login").any { it in lowered }
            ) -> "QQ 邮箱授权码或 SMTP 配置可能有误"
        listOf("timeout", "connect", "network", "dns", "unknownhost").any { it in lowered } -> "网络连接失败"
        else -> "测试通知发送失败"
    }
}

private val courseYearPattern = Regex("""20\d{2}\s*[-/]\s*20\d{2}""")

fun courseYear(term: String): String? =
    courseYearPattern.find(term)?.value?.replace(" ", "")

fun courseSemesterLabel(semesterCode: String, term: String): String {
    val code = semesterCode.trim()
    if (code.endsWith("-3")) return "第三学期"
    if (code.endsWith("-2")) return "第二学期"
    if (code.endsWith("-1")) return "第一学期"
    // 兜底：老缓存没有 semesterCode 时，从学期名里猜
    val lowered = term.lowercase()
    return when {
        "第三" in term || "third" in lowered || "summer" in lowered -> "第三学期"
        "第二" in term || "second" in lowered || "spring" in lowered -> "第二学期"
        "第一" in term || "first" in lowered || "fall" in lowered || "autumn" in lowered -> "第一学期"
        else -> "全部"
    }
}

/** 课件下载状态色：深色模式下用更亮的变体，保证深底上的可读性。 */
@Composable
fun coursewareStatusColor(status: String): Color = when {
    status == COURSEWARE_STATUS_SUCCESS || status == COURSEWARE_STATUS_ENTRY_SAVED ->
        if (LocalXmuDarkTheme.current) Color(0xFF6FCF97) else Color(0xFF067647)
    status == COURSEWARE_STATUS_DOWNLOADING ->
        if (LocalXmuDarkTheme.current) Color(0xFF7FB5E8) else Color(0xFF0B4F8A)
    status.startsWith(COURSEWARE_STATUS_FAILED) || status == COURSEWARE_STATUS_LIMITED ->
        if (LocalXmuDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFB42318)
    else -> if (LocalXmuDarkTheme.current) Color(0xFF9FB0C0) else Color(0xFF55708A)
}

/**
 * 尝试通过系统「固定 Widget」流程把「今日课程」小卡片 pin 到桌面。
 *
 * 返回 true 表示已成功发起（系统确认框会/已弹出），false 表示当前环境根本不支持
 * [AppWidgetManager.requestPinAppWidget]（如 Android < P，或桌面明确不支持）。
 *
 * 不同厂商对第三方 pin 的态度差异很大，这里按「硬封禁 / 需权限 / 标准」三层处理：
 *
 * 1. 硬封禁（ColorOS 系 + vivo）：一加 / ColorOS 实测机型上，桌面
 *    [com.android.launcher3.dragndrop.AddItemActivity.isAllowedAddWidget] 直接在
 *    onCreate 里返回 false，[AppWidgetManager.requestPinAppWidget] 调用后 58ms 内
 *    finish，既不弹窗也无回调，纯靠 API 永成功不了。这类直接引导用户走系统
 *    「小部件」入口，不做注定失败的 pin 请求。
 *
 * 2. 需权限（小米 / Redmi 的 MIUI / HyperOS）：系统声称支持，但若应用没拿到
 *    「桌面快捷方式」权限，[AppWidgetManager.requestPinAppWidget] 会「谎报成功、实际
 *    静默失败」——AddItemActivity 立即 finish，既无回调也无法靠返回值判断。所以仍要
 *    发起 pin，但由调用方延时校验成功与否；失败再回退到「去设置开权限」引导。
 *
 * 3. 标准（三星 / Pixel / 通用 AOSP / 华为 / 荣耀 / 其他）：直接发起 pin + 延时校验兜底。
 *
 * 不论哪一类，调用方都应做「延时校验 getAppWidgetIds 数量是否真的增加」来兜底，
 * 失败再回退手动指引或对应引导。
 */
private val PIN_BLOCKED_BRANDS = setOf(
    "oneplus", "oppo", "realme", "vivo",
)

/**
 * 这些厂商（小米 / Redmi 的 MIUI / HyperOS）支持第三方 pin，但前提是应用已获得
 * 「桌面快捷方式」权限；否则 [AppWidgetManager.requestPinAppWidget] 会「谎报成功、
 * 实际静默失败」，无法靠返回值判断。即便返回 true 仍要发起 pin + 延时校验；
 * 校验失败时引导用户去应用设置页开启「桌面快捷方式」权限。
 */
private val SHORTCUT_PERMISSION_BRANDS = setOf(
    "xiaomi", "redmi",
)

/**
 * 这些厂商的默认桌面会硬拒第三方 [AppWidgetManager.requestPinAppWidget]
 * （[com.android.launcher3.dragndrop.AddItemActivity.isAllowedAddWidget] 直接返回 false，
 * 实测一加 / ColorOS 机型即如此），标准 API 无法一键加卡片。对它们直接引导用户走系统
 * 「小部件」入口，而不是做注定失败的 pin 请求。
 */
fun knownPinBlockedOem(context: Context): Boolean {
    val brand = Build.BRAND?.lowercase() ?: ""
    return brand in PIN_BLOCKED_BRANDS
}

/**
 * 该设备是否走「需桌面快捷方式权限」的引导分支（小米 / Redmi）。
 * 注意：即便返回 true，仍应发起 pin 尝试并延时校验；仅当校验失败（缺权限）时，
 * 才回退到「去应用设置开权限」引导，省得用户白点一次。
 */
fun needsShortcutPermission(context: Context): Boolean {
    val brand = Build.BRAND?.lowercase() ?: ""
    return brand in SHORTCUT_PERMISSION_BRANDS
}

fun requestScheduleWidget(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val manager = AppWidgetManager.getInstance(context)
    val component = ComponentName(context, ScheduleWidgetProvider::class.java)
    if (!manager.isRequestPinAppWidgetSupported) return false
    return try {
        manager.requestPinAppWidget(component, null, null)
    } catch (_: Throwable) {
        false
    }
}

/**
 * 课表刷新成功后同步桌面 Widget 的「今日课程」摘要，并通知所有 Widget 更新。
 * 与课表页一致：反推/校准结果优先，官方表兜底；手动周次只是校准基准，
 * 校准成功后按日期自动推进，仅当校准反推失败时才以手动周次兜底。
 */
fun syncScheduleWidget(
    context: Context,
    entries: List<XmuScheduleEntry>,
    termCode: String,
    inferredCalendar: XmuAcademicCalendar? = null,
    manualWeek: Int = 0,
) {
    val days = projectScheduleWidgetDays(
        entries, termCode, inferredCalendar ?: xmuAcademicCalendarForTerm(termCode),
        manualWeek = if (inferredCalendar == null) manualWeek else 0,
    )
    ScheduleWidgetData.saveUpcoming(context, days)
    ScheduleWidgetProvider.refreshAll(context)
}
