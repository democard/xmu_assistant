package com.xmu.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
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
    onNavigate: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val canLogin = username.isNotBlank() && password.isNotBlank() && !accountTransitionInProgress
    fun submitLogin() {
        if (canLogin) {
            focus.clearFocus()
            onLogin()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(AppHeaderBrush, RoundedCornerShape(22.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            SectionCard("登录校园账号") {
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
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("校园账号已连接", fontWeight = FontWeight.Bold)
                        Text(username.take(3) + "••••" + username.takeLast(2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { confirmLogout = true }, enabled = !accountTransitionInProgress) { Text("退出登录") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeShortcut("课程课件", "查找与下载资料", Modifier.weight(1f)) { onNavigate("课程课件") }
            HomeShortcut("考试安排", "查看时间与考场", Modifier.weight(1f)) { onNavigate("考试安排") }
        }

        if (loggedIn) {
            SectionCard("监控与提醒") {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = autoEnabled, role = Role.Switch,
                        enabled = !accountTransitionInProgress, onValueChange = onAutoChanged,
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动签到", fontWeight = FontWeight.Bold)
                        Text("数字与雷达签到", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoEnabled, onCheckedChange = null, enabled = !accountTransitionInProgress)
                }
                if (monitorFailureCount > 0) {
                    RefreshStateBanner(false, monitorLastError.ifBlank { "最近检查失败，请检查网络与后台设置。" }, false)
                }
                if (recentEvent != null) {
                    StatusRow("最近签到", "${recentEvent.courseTitle} / ${recentEvent.status}")
                    TextButton(onClick = { onNavigate("签到情况") }) { Text("查看签到详情") }
                }
            }
            SectionCard("保持后台提醒") {
                Text("允许通知，并将电池策略设为不限制，让锁屏后的提醒更可靠。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpenBackgroundSettings, modifier = Modifier.fillMaxWidth()) { Text("检查后台设置") }
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

@Composable
private fun HomeShortcut(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp), color = themePrimarySoft()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = themePrimary())
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
