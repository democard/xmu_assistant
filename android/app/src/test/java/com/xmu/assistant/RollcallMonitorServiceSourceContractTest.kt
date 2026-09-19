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

    @Test
    fun `monitor foreground type survives android 15 dataSync timeout`() {
        val source = monitorServiceSource()
        val manifest = mainManifestSource()

        // Android 15（targetSdk 35）对 dataSync 前台服务有 24h 内 6 小时强制超时，
        // 全天候签到监控必然触顶：API 35+ 必须改传 specialUse（用途声明见 manifest）。
        assertTrue(
            "manifest must declare dataSync|specialUse for the monitor service",
            "android:foregroundServiceType=\"dataSync|specialUse\"" in manifest,
        )
        assertTrue(
            "specialUse requires the FOREGROUND_SERVICE_SPECIAL_USE permission",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in manifest,
        )
        assertTrue(
            "specialUse subtype must be declared as a service property",
            "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in manifest,
        )
        assertTrue(
            "API 35+ must start the service as specialUse",
            "Build.VERSION_CODES.VANILLA_ICE_CREAM" in source &&
                "ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in source,
        )
        assertTrue(
            "API 29-34 must keep dataSync (no timeout on those OS levels)",
            "ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in source,
        )
        // 兜底：个别 ROM 若仍按 dataSync 判定超时，必须优雅自停而不是被系统杀
        val timeoutBlock = source
            .substringAfter("override fun onTimeout(", missingDelimiterValue = "")
            .substringBefore("}", missingDelimiterValue = "")
        assertTrue("onTimeout backstop was not found", timeoutBlock.isNotBlank())
        assertTrue("onTimeout must stop the foreground service", "stopForeground(" in timeoutBlock)
        assertTrue("onTimeout must stop self", "stopSelf(" in timeoutBlock)
    }

    private fun mainManifestSource(): String {
        val relativePath = "src/main/AndroidManifest.xml"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "AndroidManifest.xml was not found from ${File(".").absolutePath}" }
            .readText()
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
