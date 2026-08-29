package com.xmu.assistant

import androidx.activity.ComponentActivity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ScoreShare 可测面行为测试（片C 补测：此前全文件零测试）。
 * 覆盖：长图渲染的尺寸公式/下限钳位/极端输入上限钳位（NATIVE 图形）、
 * 超长课程名截断不崩、系统分享 Intent 的 action/type/授权 flag。
 * 存储层（MediaStore 私有目录回退）环境依赖重，不在 JVM 面锁定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreShareTest {

    private fun record(term: String, name: String = "高等数学") = XmuScoreRecord(
        courseCode = "c", courseName = name, term = term, termCode = term,
        credit = 3.0, score = 90.0, gradePoint = 4.0,
        countsForStatistics = true,
    )

    private fun summary() = XmuScoreSummary(
        averageGpa = 4.0, weightedGpa = 4.0, averageScore = 90.0,
        weightedScore = 90.0, completedCredits = 3.0,
    )

    @Test
    fun `render produces 1080-wide bitmap with formula height for small input`() {
        // 1 学期 1 门：termLines = 2+1，height = 72*2 + (6+3)*54 = 630
        val bitmap = renderScoreLongImage(listOf(record("2025-2026-1")), summary())
        assertEquals(1080, bitmap.width)
        assertEquals(630, bitmap.height)
    }

    @Test
    fun `render with no records floors the bitmap height`() {
        // 0 门：height = 144 + 6*54 = 468 → 钳到下限 480
        val bitmap = renderScoreLongImage(emptyList(), summary())
        assertEquals(1080, bitmap.width)
        assertEquals(480, bitmap.height)
    }

    @Test
    fun `render caps bitmap height for extreme record count`() {
        // 1 学期 300 门：termLines=302，height=16776 → 钳到上限 12000（防 OOM 截断）
        val records = (1..300).map { record("2025-2026-1", "课程$it") }
        val bitmap = renderScoreLongImage(records, summary())
        assertEquals(1080, bitmap.width)
        assertEquals(12000, bitmap.height)
    }

    @Test
    fun `render tolerates very long course names and unlabelled terms`() {
        val longName = "超".repeat(500)
        val records = listOf(record("", longName), record("", longName))
        val bitmap = renderScoreLongImage(records, summary())
        assertNotNull(bitmap)
        assertTrue(bitmap.height >= 480)
    }

    @Test
    fun `share starts a chooser granting read access to the image`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val uri = Uri.parse("content://com.xmu.assistant.fileprovider/images/xmu成绩.png")

        shareScoreImage(activity, uri)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertEquals("分享成绩长图", started.getStringExtra(Intent.EXTRA_TITLE))
        val send = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send!!.action)
        assertEquals("image/png", send.type)
        assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
}
