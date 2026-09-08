package com.xmu.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun rankDate(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))

@Composable
internal fun RankEntry(label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("专业排名", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelMedium, color = themePrimary())
            Spacer(Modifier.width(8.dp)); Text("›", color = themePrimary())
        }
    }
}

@Composable
internal fun RankPage(state: RankSectionState, onBack: () -> Unit) {
    var exportRecord by rememberSaveable { mutableStateOf("") }
    var clearDialog by remember { mutableStateOf(false) }
    var abandonDialog by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) state.exportPdf(uri, exportRecord)
    }
    val result = state.cache.result
    val pending = state.cache.pending
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ 返回成绩") }
        if (state.stale) Text("成绩已变化，以下为上次排名，待重新获取。", color = themeWarning(), style = MaterialTheme.typography.bodySmall)
        Surface(color = themePrimarySoft(), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GPA 专业排名", color = themePrimary())
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result?.position?.toString() ?: "—", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = themePrimary())
                    Text(result?.participants?.let { "/ $it 人" } ?: "尚无排名结果", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (result != null) {
                    Text(if (result.calculatedAt.isNotBlank()) "教务记录时间：${result.calculatedAt}" else "申请于 ${rankDate(result.requestedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text("点击获取后，提交申请并读取本次计算结果。", style = MaterialTheme.typography.bodySmall)
                if (state.ranges.size > 1 && pending == null) {
                    Text("请选择教务提供的计算范围", fontWeight = FontWeight.Medium)
                    state.ranges.forEach { range ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.loading) { state.selectedRange = range.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = state.selectedRange == range.id, onClick = { state.selectedRange = range.id }, enabled = !state.loading)
                            Text(range.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text("请勿频繁申请。获取或重新获取会提交新的绩点计算申请，请按需操作；提交后请耐心等待。",
                    color = themeWarning(), style = MaterialTheme.typography.bodySmall)
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Button(onClick = state::fetch, enabled = !state.loading && (pending != null || state.ranges.size <= 1 || state.selectedRange.isNotBlank()),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when {
                        state.loading -> state.stage
                        pending != null -> "继续查询结果"
                        result != null -> "重新获取专业排名"
                        else -> "获取专业排名"
                    })
                }
            }
        }
        if (state.error.isNotBlank()) Text(state.error, color = themeWarning(), style = MaterialTheme.typography.bodyMedium)
        if (pending != null && !state.loading) {
            Text("本次申请：${rankDate(pending.requestedAt)}\n继续查询只读取结果，不会新增申请。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { abandonDialog = true }) { Text("结束本次等待") }
        }
        if (result != null) {
            Text("计算范围：${result.rangeName}", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text("绩点证明", fontWeight = FontWeight.Bold)
            if (result.pdfBase64.isBlank()) Text("本地 PDF 已清理。排名与日期仍保留，已导出的副本不受影响。", style = MaterialTheme.typography.bodySmall)
            else {
                Text("与上方排名对应的原始 PDF", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        exportRecord = result.recordId
                        export.launch("绩点证明-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date(result.obtainedAt))}.pdf")
                    }, modifier = Modifier.weight(1f), enabled = !state.loading) { Text("导出绩点证明") }
                    TextButton(onClick = { clearDialog = true }, enabled = !state.loading) { Text("清理 PDF") }
                }
            }
        }
        Text("排名以本次计算范围和时间为准；成绩变化只会标记待更新，不会自动申请。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清理本地 PDF？") },
        text = { Text("仅删除应用内保存的证明，保留排名和日期。已导出的文件不会删除；清理后无法再次导出这份本地证明。") },
        confirmButton = { TextButton(onClick = { clearDialog = false; state.clearPdf() }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } })
    if (abandonDialog) AlertDialog(onDismissRequest = { abandonDialog = false }, title = { Text("结束等待？") },
        text = { Text("这不会撤销教务端的申请。若申请已受理，再次获取会重复申请；建议先在教务系统核对。") },
        confirmButton = { TextButton(onClick = { abandonDialog = false; state.abandonPending() }) { Text("结束等待") } },
        dismissButton = { TextButton(onClick = { abandonDialog = false }) { Text("继续等待") } })
}
