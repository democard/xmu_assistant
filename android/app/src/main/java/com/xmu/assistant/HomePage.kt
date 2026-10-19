package com.xmu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

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
    onAutoChanged: (Boolean) -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    onNavigate: (String) -> Unit,
    currentRollcalls: List<RollcallEvent> = emptyList(),
    rollcallLoading: Boolean = false,
    rollcallError: String = "",
    rollcallUpdatedAtMillis: Long = 0L,
) {
    val focus = LocalFocusManager.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val canLogin = username.isNotBlank() && password.isNotBlank() && !accountTransitionInProgress
    val autoEnabled = rollcallSettings.autoAnswerNumber || rollcallSettings.autoAnswerRadar
    fun submitLogin() {
        if (canLogin) {
            focus.clearFocus()
            onLogin()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(AppHeaderBrush, RoundedCornerShape(17.dp)).heightIn(min = 182.dp).padding(20.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text("XMU / 校园日常", style = MaterialTheme.typography.labelLarge, color = Color(0xFFCDE5F2))
            Text(
                if (!loggedIn) "校园日常，从这里开始" else if (monitorRunning) "签到监控已开启" else "准备好，开始新的一天",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
            )
            Text(
                when {
                    !loggedIn -> "登录后查看课表、成绩和课程资料。"
                    monitorRunning -> "最近检查 $monitorLastCheck · $monitorStatus"
                    else -> "开启监控，及时接收课堂签到提醒。"
                },
                color = Color(0xFFD7E8F2), style = MaterialTheme.typography.bodyMedium,
            )
            if (loggedIn) {
                Button(
                    onClick = if (monitorRunning) onStopMonitor else onStartMonitor,
                    enabled = !monitorTransitionInProgress && !accountTransitionInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color.White, contentColor = AppPrimary,
                    ),
                ) {
                    Text(if (monitorTransitionInProgress) "正在切换…" else if (monitorRunning) "暂停监控" else "启动监控")
                }
            }
        }

        if (!loggedIn) {
            PreviewCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("登录校园账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PreviewBadge("仅保存在本机")
                }
                OutlinedTextField(
                    value = username, onValueChange = onUsername, label = { Text("学号") },
                    modifier = Modifier.fillMaxWidth(), enabled = !accountTransitionInProgress, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = password, onValueChange = onPassword, label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(), enabled = !accountTransitionInProgress, singleLine = true,
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
                Button(onClick = { submitLogin() }, enabled = canLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (accountTransitionInProgress) "正在登录…" else "登录")
                }
                Text("用于读取课表、成绩、考试与畅课签到", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PreviewCard(soft = true) {
                PreviewListRow(R.drawable.ic_nav_check, "账号数据留在手机", "密码、Cookie 与通知配置不会上传到其他服务器。", trailing = {})
            }
        } else {
            PreviewCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreviewIconBox(R.drawable.ic_nav_check)
                    Column(Modifier.weight(1f)) {
                        Text("校园账号已连接", fontWeight = FontWeight.Bold)
                        Text(username.take(3) + "****" + username.takeLast(4) + " · 登录状态正常", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { confirmLogout = true }, enabled = !accountTransitionInProgress) { Text("退出登录") }
                }
            }
        }

        if (loggedIn) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeShortcut("课程课件", "查找与下载资料", Modifier.weight(1f)) { onNavigate("课程课件") }
                HomeShortcut("考试安排", "查看时间与考场", Modifier.weight(1f)) { onNavigate("考试安排") }
            }
            PreviewCard {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = autoEnabled, role = Role.Switch,
                        enabled = !accountTransitionInProgress, onValueChange = onAutoChanged,
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动签到快捷开关", fontWeight = FontWeight.Bold)
                        Text(
                            homeAutoAnswerSummary(rollcallSettings),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoEnabled, onCheckedChange = null, enabled = !accountTransitionInProgress)
                }
                Text(
                    "与策略页同步；此开关同时开启或关闭数字、雷达签到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (autoEnabled && !monitorRunning) {
                    Text(
                        "尚未生效，请先启动监控",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (monitorFailureCount > 0) {
                    RefreshStateBanner(false, monitorLastError.ifBlank { "最近检查失败，请检查网络与后台设置。" }, false)
                }
                // 沿用美化前“监控与提醒”内的摘要行，不另建签到模块或重复详情卡片。
                val current = currentRollcalls.firstOrNull()
                if (current != null) {
                    val count = if (currentRollcalls.size > 1) " · 共 ${currentRollcalls.size} 项" else ""
                    StatusRow("当前签到", "${current.courseTitle} / ${current.type}$count")
                    TextButton(onClick = { onNavigate("签到情况") }) { Text("查看签到详情") }
                } else if (rollcallLoading) {
                    StatusRow("当前签到", "正在读取…")
                } else if (rollcallUpdatedAtMillis > 0L && rollcallError.isBlank()) {
                    StatusRow("当前签到", "暂无进行中的签到")
                }
                if (rollcallError.isNotBlank()) {
                    RefreshStateBanner(false, rollcallError, currentRollcalls.isNotEmpty())
                }
                OutlinedButton(onClick = { onNavigate("策略") }, modifier = Modifier.fillMaxWidth()) { Text("调整类型与人数条件") }
                OutlinedButton(onClick = { onNavigate("课程坐标") }, modifier = Modifier.fillMaxWidth()) { Text("雷达签到坐标管理") }
            }
            PreviewCard(soft = true, onClick = onOpenBackgroundSettings) {
                PreviewListRow(
                    icon = R.drawable.ic_nav_check,
                    title = "后台提醒正常",
                    subtitle = "已允许通知与后台运行",
                    trailing = { PreviewBadge("已检查", PreviewBadgeTone.GREEN) },
                )
            }
        }
    }
    if (confirmLogout && loggedIn) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出当前账号？") },
            text = { Text("退出后将暂停监控。再次登录即可继续使用。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }, enabled = !accountTransitionInProgress) { Text("确认退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

internal fun autoAnswerTypeSummary(settings: RollcallSettings): String = when {
    settings.autoAnswerNumber && settings.autoAnswerRadar -> "数字与雷达"
    settings.autoAnswerNumber -> "仅数字"
    settings.autoAnswerRadar -> "仅雷达"
    else -> "仅提醒"
}

internal fun waitBeforeAnswerSummary(settings: RollcallSettings): String = when (settings.waitBeforeAnswerMode) {
    WAIT_BEFORE_ANSWER_COUNT -> "达到 ${settings.waitBeforeAnswerCount} 人"
    WAIT_BEFORE_ANSWER_PERCENT -> "达到 ${settings.waitBeforeAnswerPercent}%"
    else -> "不等待人数条件"
}

internal fun homeAutoAnswerSummary(settings: RollcallSettings): String =
    "${autoAnswerTypeSummary(settings)} · ${waitBeforeAnswerSummary(settings)}"

internal fun toggleAllAutoAnswer(settings: RollcallSettings, enabled: Boolean): RollcallSettings =
    settings.copy(autoAnswerNumber = enabled, autoAnswerRadar = enabled)

@Composable
private fun HomeShortcut(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PreviewIconBox(if (title == "课程课件") R.drawable.ic_nav_folder else R.drawable.ic_nav_calendar, Modifier.size(34.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
