package com.xmu.assistant

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val IMAGE_WIDTH = 1080
private const val PADDING = 72f
private const val LINE_HEIGHT = 54f
// 位图高度上限（约 12000px ≈ 50MB ARGB）：极多课程（>200 门）时防 OOM 截断渲染，
// 正常历史成绩（<150 门 ≈ 9000px）不受影响；调用方另有 runCatching 兜底。
private const val MAX_BITMAP_HEIGHT = 12000

/** 把成绩汇总 + 各学期成绩渲染成一张竖版长图。 */
fun renderScoreLongImage(
    records: List<XmuScoreRecord>,
    summary: XmuScoreSummary,
): Bitmap {
    val recordsByTerm = records.groupBy { it.term.ifBlank { "未标注学期" } }
        .entries
        .sortedByDescending { entry ->
            entry.value.firstOrNull()?.termCode?.takeIf { it.isNotBlank() } ?: entry.key
        }
        .map { (term, termRecords) -> term to termRecords.sortedBy { it.courseName } }

    val fixedLines = 6
    val termLines = recordsByTerm.sumOf { (_, termRecords) -> 2 + termRecords.size }
    val height = (PADDING * 2 + (fixedLines + termLines) * LINE_HEIGHT)
        .toInt()
        .coerceIn(480, MAX_BITMAP_HEIGHT)

    val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    val titlePaint = Paint().apply {
        color = Color.parseColor("#0F766E"); textSize = 46f
        isFakeBoldText = true; isAntiAlias = true
    }
    val headerPaint = Paint().apply {
        color = Color.parseColor("#17312E"); textSize = 34f
        isFakeBoldText = true; isAntiAlias = true
    }
    val textPaint = Paint().apply {
        color = Color.parseColor("#333333"); textSize = 30f; isAntiAlias = true
    }
    val mutedPaint = Paint().apply {
        color = Color.parseColor("#8A8F8E"); textSize = 26f; isAntiAlias = true
    }

    var y = PADDING + titlePaint.textSize
    canvas.drawText("xmu助手 · 成绩", PADDING, y, titlePaint)
    y += LINE_HEIGHT
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    canvas.drawText("导出时间 $stamp", PADDING, y, mutedPaint)
    y += LINE_HEIGHT * 1.4f

    canvas.drawText("汇总", PADDING, y, headerPaint)
    y += LINE_HEIGHT
    canvas.drawText(
        "平均绩点 ${scoreMetricText(summary.averageGpa)}    加权绩点 ${scoreMetricText(summary.weightedGpa)}",
        PADDING, y, textPaint,
    )
    y += LINE_HEIGHT
    canvas.drawText(
        "平均分数 ${scoreMetricText(summary.averageScore)}    加权分数 ${scoreMetricText(summary.weightedScore)}",
        PADDING, y, textPaint,
    )
    y += LINE_HEIGHT
    canvas.drawText("已修总学分 ${scoreMetricText(summary.completedCredits)}", PADDING, y, textPaint)
    y += LINE_HEIGHT

    // 行数预算：超出位图高度（极端课程数）时截断并显式提示，禁止静默丢数据
    var lineBudget = ((height - y) / LINE_HEIGHT).toInt() - 1 // 预留提示行
    var truncated = false
    for ((term, termRecords) in recordsByTerm) {
        val needed = 2 + termRecords.size
        if (needed > lineBudget) {
            truncated = true
            break
        }
        y += LINE_HEIGHT * 0.6f
        canvas.drawText("$term（${termRecords.size} 门）", PADDING, y, headerPaint)
        y += LINE_HEIGHT
        for (record in termRecords) {
            val detail = scoreRecordDetail(record)
            val detailWidth = textPaint.measureText(detail)
            val nameMaxWidth = IMAGE_WIDTH - PADDING * 2 - detailWidth - 48f
            val name = truncate(record.courseName, textPaint, nameMaxWidth)
            canvas.drawText(name, PADDING, y, textPaint)
            canvas.drawText(detail, IMAGE_WIDTH - PADDING - detailWidth, y, textPaint)
            y += LINE_HEIGHT
        }
        lineBudget -= needed
    }
    if (truncated) {
        canvas.drawText("……课程过多，仅显示部分（共 ${records.size} 门）", PADDING, height - PADDING - 12f, mutedPaint)
    }
    return bitmap
}

/**
 * 保存成绩长图并返回可分享的 content uri。
 * - Android 10+（API>=29）：写入系统相册（MediaStore，无需权限）。
 * - Android 8-9（API 26-28）：写入应用私有目录 files/images/，经 FileProvider 分享
 *   （免 WRITE_EXTERNAL_STORAGE 运行时权限，避免旧系统上分享失败）。
 */
fun saveScoreImageToGallery(context: Context, bitmap: Bitmap): Uri? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return saveToMediaStore(context, bitmap)
    }
    return saveToPrivateFile(context, bitmap)
}

private fun saveToMediaStore(context: Context, bitmap: Bitmap): Uri? {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "xmu成绩_$stamp.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/xmu助手")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return null
    return try {
        // openOutputStream 可能返回 null、compress 可能返回 false：两种失败都不能把
        // 指向零字节图片的 uri 返回给分享方，且要删掉 MediaStore 残留的空记录
        val written = resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: false
        if (written) {
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    } catch (_: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}

private fun saveToPrivateFile(context: Context, bitmap: Bitmap): Uri? {
    val dir = File(context.filesDir, "images").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val file = File(dir, "xmu成绩_$stamp.png")
    return try {
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        // 顺带清理 30 天前的旧分享图，避免私有目录无限累积
        try {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { old ->
                if (old.name.startsWith("xmu成绩_") && old.lastModified() < cutoff) old.delete()
            }
        } catch (_: Throwable) {
            // 清理失败不影响本次分享
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (error: Throwable) {
        Log.w("ScoreShare", "pre-Q 成绩长图分享失败", error)
        null
    }
}

/** 拉起系统分享面板。 */
fun shareScoreImage(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享成绩长图"))
}

private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var result = text
    while (result.length > 1 && paint.measureText("$result…") > maxWidth) {
        result = result.dropLast(1)
    }
    return "$result…"
}
