package com.xmu.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun RollcallRecordCard(
    courseTitle: String,
    typeAndTime: String,
    supportingLines: List<String>,
    progress: StudentRollcallProgress?,
    modifier: Modifier = Modifier,
    numberCode: String? = null,
    numberCodeInFacts: Boolean = false,
) {
    InfoCard(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth >= 260.dp && LocalDensity.current.fontScale <= 1.2f
            if (side) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RollcallDetails(courseTitle, typeAndTime, supportingLines, Modifier.weight(1f))
                    RollcallFacts(
                        progress,
                        numberCode.takeIf { numberCodeInFacts },
                        Modifier.width(120.dp),
                        Alignment.End,
                        TextAlign.End,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RollcallDetails(courseTitle, typeAndTime, supportingLines)
                    RollcallFacts(
                        progress,
                        numberCode.takeIf { numberCodeInFacts },
                        Modifier.fillMaxWidth(),
                        Alignment.Start,
                        TextAlign.Start,
                    )
                }
            }
        }
        if (!numberCodeInFacts && numberCode != null) {
            RollcallCode(numberCode, Modifier.fillMaxWidth(), TextAlign.Start, showCopy = true)
        }
    }
}

@Composable
private fun RollcallDetails(
    courseTitle: String,
    typeAndTime: String,
    supportingLines: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(courseTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(typeAndTime)
        supportingLines.forEach { Text(it) }
    }
}

@Composable
private fun RollcallFacts(
    progress: StudentRollcallProgress?,
    numberCode: String?,
    modifier: Modifier,
    alignment: Alignment.Horizontal,
    textAlign: TextAlign,
) {
    val presentation = progressPresentation(progress)
    Column(modifier = modifier, horizontalAlignment = alignment) {
        Text(
            presentation.first,
            color = if (presentation.reliable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign,
        )
        Text(presentation.second, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign)
        if (numberCode != null) RollcallCode(numberCode, Modifier, textAlign, showCopy = false)
    }
}

@Composable
private fun RollcallCode(
    numberCode: String,
    modifier: Modifier,
    textAlign: TextAlign,
    showCopy: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val label: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            if (numberCode.isBlank()) "签到码 暂未获取" else "签到码 $numberCode",
            modifier = textModifier,
            fontWeight = if (numberCode.isBlank()) FontWeight.Normal else FontWeight.Bold,
            color = if (numberCode.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = textAlign,
        )
    }
    if (showCopy) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            label(Modifier.weight(1f))
            if (numberCode.isNotBlank()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(numberCode)) }) { Text("复制") }
            }
        }
    } else {
        label(modifier)
    }
}

private data class ProgressPresentation(val first: String, val second: String, val reliable: Boolean)

private fun progressPresentation(progress: StudentRollcallProgress?): ProgressPresentation {
    if (progress == null || progress.total <= 0 || !progress.reliablePercentage || progress.percentage == null) {
        return ProgressPresentation("签到进度", "暂未获取", false)
    }
    val percent = progress.percentage.takeIf(Double::isFinite)
        ?: return ProgressPresentation("签到进度", "暂未获取", false)
    return ProgressPresentation(
        "${progress.present} / ${progress.total} 人",
        formatPercentage(percent),
        true,
    )
}

private fun formatPercentage(value: Double): String {
    val tenths = (value.coerceIn(0.0, 100.0) * 10.0).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
}
