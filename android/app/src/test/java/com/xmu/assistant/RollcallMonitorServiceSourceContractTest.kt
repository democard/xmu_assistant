package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RollcallMonitorServiceSourceContractTest {
    @Test
    fun `monitor invalidates stopped runs before processing a poll result`() {
        val source = monitorServiceSource()

        assertTrue("monitor must expose immediate invalidation", "fun requestInvalidateActiveRun()" in source)
        assertTrue("monitor must expose an off-main quiescence barrier", "fun awaitActiveRunQuiescence()" in source)
        assertTrue("destroy must conditionally invalidate its active worker", "monitorWorkerCoordinator::requestInvalidate" in source)
        assertTrue("monitor poll result must flow through the active-work guard", "runIfCurrent(" in source)
        assertTrue("monitor failures must also use the linearized guard", "runIfCurrent(token, settings.monitorDesired) {" in source)
    }

    @Test
    fun `monitor restarts an invalidated worker without allowing a stale completion to clear it`() {
        val source = monitorServiceSource()

        assertTrue("monitor must use token-aware worker coordination", "MonitorWorkerCoordinator" in source)
        assertTrue("monitor must not gate starts on a stale running boolean", "if (!running)" !in source)
        assertTrue("start must request a token-aware worker decision", "monitorWorkerCoordinator.start(settings.monitorDesired)" in source)
        assertTrue("each worker must conditionally complete in finally", "finally" in source)
        assertTrue("worker completion must be token-specific", "monitorWorkerCoordinator.complete(token)" in source)
    }

    private fun monitorServiceSource(): String {
        val relativePath = "src/main/java/com/xmu/assistant/RollcallMonitorService.kt"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "RollcallMonitorService.kt was not found from ${File(".").absolutePath}" }
            .readText()
    }
}
