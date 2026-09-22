package com.xmu.assistant

import androidx.activity.ComponentActivity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyBitmap
import org.robolectric.shadows.ShadowLog

/**
 * ScoreShare 可测面行为测试（片C 补测：此前全文件零测试）。
 * 覆盖：长图渲染的尺寸公式/下限钳位/极端输入上限钳位（NATIVE 图形）、
 * 超长课程名截断不崩、系统分享 Intent 的 action/type/授权 flag，
 * 以及 Android 8-9 私有目录回退的防覆盖与压缩失败清理。
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

    @Test
    @Config(sdk = [28], shadows = [WindowsFileProviderShadow::class])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `pre Q saves use distinct files within one second`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val firstBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffff0000.toInt()) }
        val secondBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff0000ff.toInt()) }
        val imageDir = File(activity.filesDir, "images")
        val before = imageDir.listFiles()?.toSet().orEmpty()
        while (System.currentTimeMillis() % 1000L > 50L) Thread.sleep(10L)
        val startedAt = System.currentTimeMillis()
        val first = saveScoreImageToGallery(activity, firstBitmap)
        assertNotNull(scoreShareErrors(), first)
        val firstFile = (imageDir.listFiles()?.toSet().orEmpty() - before).single()
        assertEquals("content", first!!.scheme)
        val firstBytes = firstFile.readBytes()
        val second = saveScoreImageToGallery(activity, secondBitmap)
        val finishedAt = System.currentTimeMillis()
        assertEquals("saves must exercise the same-second collision case", startedAt / 1000L, finishedAt / 1000L)
        assertNotNull(scoreShareErrors(), second)
        val files = imageDir.listFiles()?.toSet().orEmpty() - before
        val secondFile = (files - firstFile).single()
        assertFalse(first == second)
        val secondBytes = secondFile.readBytes()
        val firstBytesAfter = firstFile.readBytes()
        assertFalse(firstBytes.contentEquals(secondBytes))
        assertTrue(firstBytes.contentEquals(firstBytesAfter))
        assertTrue(secondFile.length() > 0L)
    }

    @Test
    @Config(sdk = [28], shadows = [CompressFalseBitmapShadow::class])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `pre Q does not return a uri when compression fails`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val imageDir = File(activity.filesDir, "images")
        val before = imageDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        CompressFalseBitmapShadow.compressCalled = false
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertEquals(null, saveScoreImageToGallery(activity, bitmap))
        assertTrue("the false result must come from Bitmap.compress", CompressFalseBitmapShadow.compressCalled)
        val after = imageDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals(before, after)
    }

    @Implements(Bitmap::class)
    class CompressFalseBitmapShadow : ShadowLegacyBitmap() {
        companion object {
            var compressCalled: Boolean = false
        }

        @Implementation
        override fun compress(format: Bitmap.CompressFormat, quality: Int, stream: OutputStream): Boolean {
            compressCalled = true
            return false
        }
    }

    /** AndroidX FileProvider's root containment check is not Windows-path aware in Robolectric. */
    @Implements(value = FileProvider::class, isInAndroidSdk = false)
    class WindowsFileProviderShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun getUriForFile(context: Context, authority: String, file: File): Uri {
                check(authority == "${context.packageName}.fileprovider")
                return Uri.Builder()
                    .scheme("content")
                    .authority(authority)
                    .appendPath("shared_images")
                    .appendPath(file.name)
                    .build()
            }
        }
    }

    private fun scoreShareErrors(): String = ShadowLog.getLogsForTag("ScoreShare")
        .joinToString("\n") { log -> "${log.msg}: ${log.throwable?.stackTraceToString().orEmpty()}" }

}
