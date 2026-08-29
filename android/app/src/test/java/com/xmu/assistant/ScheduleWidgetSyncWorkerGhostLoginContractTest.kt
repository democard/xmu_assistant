package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登出后幽灵登录守卫：凭据（学号/密码）按设计登出残留，周期 Widget worker
 * 若不复核登录态，会在登出后以残留凭据发起教务 CAS 登录（登录请求已打出 =
 * 风控暴露；同款缺陷 ScoreSectionState.refresh 已先行修复）。纯源码契约
 * （worker 行为 JVM 测试覆盖不到，参照项目契约测试哲学）。
 */
class ScheduleWidgetSyncWorkerGhostLoginContractTest {
    @Test
    fun `worker relogin is gated on logged-in mirror`() {
        val worker = sourceOf("ScheduleWidgetSyncWorker.kt")
        val settings = sourceOf("AssistantSettings.kt")
        val main = sourceOf("MainActivity.kt")

        assertTrue(
            "worker must consult the logged-in mirror before refreshing",
            "readWidgetLoggedInMirror(context)" in worker,
        )
        assertTrue(
            "an explicitly logged-out worker must not refresh at all",
            "if (loggedInMirror == false) return Result.success()" in worker,
        )
        assertTrue(
            "mayRelogin must not be unconditional",
            "mayRelogin = { true }" !in worker,
        )
        assertTrue(
            "mayRelogin must re-read the mirror in-flight (mid-refresh logout is also blocked)",
            "mayRelogin = { AssistantSettings.readWidgetLoggedInMirror(context) != false }" in worker,
        )
        assertTrue(
            "markLoginSucceeded must raise the mirror",
            "widgetWorkerLoggedIn = true" in settings,
        )
        assertTrue(
            "clearSession must drop the mirror",
            "widgetWorkerLoggedIn = false" in settings,
        )
        assertTrue(
            "clearLoggedOutUi must drop the mirror (path does not call clearSession)",
            "settings.widgetWorkerLoggedIn = false" in main,
        )
    }

    @Test
    fun `worker must recheck login state before persisting fetched data`() {
        val worker = sourceOf("ScheduleWidgetSyncWorker.kt")

        // 成功取数后的持久化段（快照文件/教务 cookie/widget 摘要）同样受守卫：
        // 登出/换号清理链已删快照清数据，此处照常落盘会让旧账号数据复活串号
        assertTrue(
            "persisted artifacts must be dropped when logged out mid-refresh",
            "if (AssistantSettings.readWidgetLoggedInMirror(context) == false) return Result.success()" in worker,
        )
        assertTrue(
            "persisted artifacts must be dropped when the account switched mid-refresh",
            "if (settings.username != fetchedUsername) return Result.success()" in worker,
        )
    }

    private fun sourceOf(name: String): String {
        val relativePath = "src/main/java/com/xmu/assistant/$name"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "$name was not found from ${File(".").absolutePath}" }
            .readText()
    }
}
