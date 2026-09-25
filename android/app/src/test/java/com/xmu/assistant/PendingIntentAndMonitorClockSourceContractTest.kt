package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingIntentAndMonitorClockSourceContractTest {
    private fun source(name: String): String {
        val relative = "src/main/java/com/xmu/assistant/$name"
        val file = sequenceOf(File(relative), File("app", relative), File("android/app", relative))
            .firstOrNull(File::isFile)
        return requireNotNull(file) { "$relative was not found from ${File(".").absolutePath}" }.readText()
    }

    private fun activityRequestCode(name: String, receiver: String): Int {
        val code = Regex("PendingIntent\\.getActivity\\(\\s*$receiver,\\s*(\\d+),")
            .find(source(name))?.groupValues?.get(1)
        return requireNotNull(code) { "No literal activity request code found in $name" }.toInt()
    }

    @Test fun `activity entry points own distinct PendingIntent identities`() {
        val widget = activityRequestCode("ScheduleWidgetRenderer.kt", "context")
        val tile = activityRequestCode("MonitorControlTileService.kt", "this")
        val exam = activityRequestCode("ExamReminder.kt", "context")
        // The rollcall event deep link has its own data URI; the plain MainActivity intent is later.
        val monitorSource = source("RollcallMonitorService.kt")
        val monitorMain = monitorSource.substringAfter("private fun mainPendingIntent()")
        val monitor = Regex("PendingIntent\\.getActivity\\(\\s*this,\\s*(\\d+),")
            .find(monitorMain)?.groupValues?.get(1)?.toInt()
        assertTrue("monitor main PendingIntent must have a literal request code", monitor != null)
        assertEquals("Each entry point must have its own request code", 4, setOf(widget, tile, exam, monitor).size)
        // The widget and tile otherwise share ACTION_MAIN, CATEGORY_LAUNCHER and MainActivity.
        assertTrue("action = Intent.ACTION_MAIN" in source("ScheduleWidgetRenderer.kt"))
        assertTrue("action = Intent.ACTION_MAIN" in source("MonitorControlTileService.kt"))
        assertTrue("addCategory(Intent.CATEGORY_LAUNCHER)" in source("ScheduleWidgetRenderer.kt"))
        assertTrue("addCategory(Intent.CATEGORY_LAUNCHER)" in source("MonitorControlTileService.kt"))
    }

    @Test fun `interruptible wait measures elapsed time on a monotonic clock`() {
        val body = source("RollcallMonitorService.kt")
            .substringAfter("private fun awaitInterruptible(")
            .substringBefore("private fun monitorLoop(")
        assertEquals(2, Regex("SystemClock\\.elapsedRealtime\\(\\)").findAll(body).count())
        assertFalse("wall-clock changes must not affect the wait deadline", "System.currentTimeMillis()" in body)
    }
}
