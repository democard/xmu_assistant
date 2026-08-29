package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * busy 通道魔法字符串守护：写入/比较必须走 [BusyStates] 常量。
 *
 * busy 的写入侧（setBusy/busy =）与复位比较侧（busy ==/busyNow() ==）分散在
 * 四个文件，任何一侧新写文案字面量都可能造成两侧不一致 → busy 永不复位
 * （底部 ToastBar 常驻"正在xx"）。本测试禁止消费文件出现 busy 字符串字面量，
 * 新增忙碌态必须先在 BusyStates 登记。
 */
class BusyStatesSourceContractTest {
    private val consumerFiles = listOf(
        "MainActivity.kt",
        "MainScreen.kt",
        "CoursewareSectionState.kt",
        "ScheduleSectionState.kt",
    )

    /** busy 通道全部已登记文案（与 BusyStates.kt 的非空常量一一对应）。 */
    private val registeredLiterals = listOf(
        "正在登录",
        "正在退出登录",
        "正在暂停监控",
        "正在下载课件",
        "正在发送测试通知",
        "正在检查登录状态",
        "会话已过期，正在安全重登",
    )

    private fun source(name: String): String {
        val relativePath = "src/main/java/com/xmu/assistant/$name"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)
        return requireNotNull(sourceFile) { "$name was not found from ${File(".").absolutePath}" }
            .readText()
    }

    @Test
    fun `busy writes and comparisons must reference BusyStates constants`() {
        val forbiddenPatterns = listOf(
            "setBusy(\"" to "setBusy 写入",
            "busy = \"" to "busy 赋值",
            "busy() == \"" to "busy 比较",
            "busyNow() == \"" to "busyNow 比较",
            "busy == \"" to "busy 相等比较",
        )
        consumerFiles.forEach { name ->
            val text = source(name)
            forbiddenPatterns.forEach { (pattern, what) ->
                assertTrue(
                    "$name 不允许 $what 魔法字符串（命中 \"$pattern\"），须走 BusyStates 常量",
                    !text.contains(pattern),
                )
            }
        }
    }

    @Test
    fun `busy literals are registered in BusyStates and absent from consumers`() {
        val registry = source("BusyStates.kt")
        registeredLiterals.forEach { literal ->
            assertTrue("BusyStates 必须登记忙碌态常量：$literal", literal in registry)
        }
        consumerFiles.forEach { name ->
            val text = source(name)
            registeredLiterals.forEach { literal ->
                assertTrue("$name 不应包含 busy 文案字面量：$literal", literal !in text)
            }
        }
    }
}
