package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷设置磁贴（MonitorControlTileService）源码契约：
 * - manifest 必须以 BIND_QUICK_SETTINGS_TILE 权限 + QS_TILE action 声明（否则系统不绑定）；
 * - 启停必须与 App 内开关同链路（monitorDesired + RollcallMonitorService 启停），
 *   状态判定必须复用 shouldStartMonitorWorker 防口径分裂；
 * - App 内启动/暂停必须 requestResync 磁贴。
 */
class MonitorControlTileServiceSourceContractTest {

    private fun source(relativePath: String): String {
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)
        return requireNotNull(sourceFile) { "$relativePath was not found from ${File(".").absolutePath}" }
            .readText()
    }

    @Test
    fun `manifest declares the tile service with binding permission and action`() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue("tile service must be declared", "android:name=\".MonitorControlTileService\"" in manifest)
        assertTrue(
            "tile must require BIND_QUICK_SETTINGS_TILE",
            "android.permission.BIND_QUICK_SETTINGS_TILE" in manifest,
        )
        assertTrue("tile must filter QS_TILE action", "android.service.quicksettings.action.QS_TILE" in manifest)
    }

    @Test
    fun `tile toggles monitor through the same chain as the in-app switch`() {
        val tile = source("src/main/java/com/xmu/assistant/MonitorControlTileService.kt")
        // 状态判定复用启动门契约
        assertTrue("tile state must reuse shouldStartMonitorWorker", "shouldStartMonitorWorker(" in tile)
        // 开链路
        assertTrue("tile start must set monitorDesired", "settings.monitorDesired = true" in tile)
        assertTrue("tile start must launch the monitor service", "startForegroundService(Intent(this, RollcallMonitorService::class.java))" in tile)
        // 关链路
        assertTrue("tile stop must invalidate the active run", "RollcallMonitorService.requestInvalidateActiveRun()" in tile)
        assertTrue("tile stop must clear monitorDesired", "settings.monitorDesired = false" in tile)
        assertTrue("tile stop must stop the monitor service", "stopService(Intent(this, RollcallMonitorService::class.java))" in tile)
        // 未登录守卫：置灰 + 带去首页
        assertTrue("logged-out click must route to the login page", "putExtra(\"open_page\", \"首页\")" in tile)
        assertTrue("logged-out tile must render unavailable", "Tile.STATE_UNAVAILABLE" in tile)
    }

    @Test
    fun `in-app monitor toggles resync the tile`() {
        // 启动路径留在 MainScreen，暂停路径 2026-08-27 出层至 HomeActions：
        // 两文件合并扫描保持「启停都必须 requestResync」的防护等价。
        val mainScreen = sourceOrNull("src/main/java/com/xmu/assistant/MainScreen.kt").orEmpty()
        val homeActions = sourceOrNull("src/main/java/com/xmu/assistant/HomeActions.kt").orEmpty()
        val combined = mainScreen + "\n" + homeActions
        val startCount = Regex("MonitorControlTileService\\.requestResync\\(activity\\)").findAll(combined).count()
        assertTrue(
            "start and stop paths must both resync the tile (found $startCount)",
            startCount >= 2,
        )
    }

    private fun sourceOrNull(relativePath: String): String? {
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)
        return sourceFile?.readText()
    }

    @Test
    fun `tutorial mentions the quick settings tile`() {
        val pages = source("src/main/java/com/xmu/assistant/Pages.kt")
        assertTrue("tutorial must contain the tile section", "磁贴快捷开关" in pages)
    }
}
