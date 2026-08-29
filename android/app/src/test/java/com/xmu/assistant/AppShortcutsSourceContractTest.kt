package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态应用快捷方式（App Shortcuts）源码契约：
 * - manifest launcher activity 内必须挂 android.app.shortcuts meta-data（否则系统不读取）；
 * - shortcuts.xml 必须有签到情况/课表/成绩三条，open_page 取值必须与
 *   MainScreen 页面路由的页面名逐字一致（错一字深链静默失焦）；
 * - intent 必须显式指向 com.xmu.assistant.MainActivity（res/xml 不做 manifest 占位符替换，
 *   targetPackage 写死 applicationId）；
 * - extra 名必须是 "open_page"，与 MainActivity 深链消费 getStringExtra("open_page") 一致。
 */
class AppShortcutsSourceContractTest {

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
    fun `manifest references the shortcuts xml inside the launcher activity`() {
        val manifest = source("src/main/AndroidManifest.xml")
        val activityBlock = manifest
            .substringAfter("<activity", "")
            .substringBefore("</activity>")
        assertTrue(
            "android.app.shortcuts meta-data must be declared inside the launcher activity",
            "android:name=\"android.app.shortcuts\"" in activityBlock &&
                "@xml/shortcuts" in activityBlock,
        )
    }

    @Test
    fun `shortcuts xml declares three routes with page names matching the router`() {
        val shortcuts = source("src/main/res/xml/shortcuts.xml")
        listOf(
            "rollcall_status" to "签到情况",
            "schedule" to "课表",
            "scores" to "成绩",
        ).forEach { (id, page) ->
            assertTrue("shortcut $id must exist", "android:shortcutId=\"$id\"" in shortcuts)
        }
        assertEquals(3, Regex("android:shortcutId=").findAll(shortcuts).count())
        // 三条 intent 都显式指向主界面并复用 open_page 深链 extra
        assertTrue(
            "intents must target MainActivity explicitly",
            Regex("<intent[\\s\\S]*?android:targetClass=\"com\\.xmu\\.assistant\\.MainActivity\"")
                .findAll(shortcuts)
                .count() == 3,
        )
        val pages = Regex("android:name=\"open_page\"\\s+android:value=\"([^\"]+)\"")
            .findAll(shortcuts)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("签到情况", "课表", "成绩"), pages)
    }

    @Test
    fun `extra name stays aligned with the deep link consumer`() {
        val mainActivity = source("src/main/java/com/xmu/assistant/MainActivity.kt")
        assertTrue(
            "deep link consumer must read the same extra name used by shortcuts",
            "getStringExtra(\"open_page\")" in mainActivity,
        )
    }
}
