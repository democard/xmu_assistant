package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrefStores 登记表契约（B4）：登记清单必须与源码里实际使用的
 * SharedPreferences 文件名逐一一致——新增存储文件必须同步登记，
 * 改名必须同步改表（防四处散置存储再漂移）。只读源码，不改语义。
 */
class PrefStoresContractTest {
    private fun source(name: String): String {
        val relativePath = "src/main/java/com/xmu/assistant/$name"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)
        checkNotNull(sourceFile) { "source file not found: $name" }
        return sourceFile.readText()
    }

    /** 源码中出现的 SharedPreferences 文件名（字面量 + 常量定义双重提取）。 */
    private fun actualFileNames(): Set<String> {
        val files = listOf(
            "AssistantSettings.kt", "ExamCache.kt", "ScheduleWidgetData.kt",
            "RollcallMonitorService.kt", "ScheduleCache.kt", "MainActivity.kt",
        )
        val literal = Regex("""getSharedPreferences\("([^"]+)""")
        val encryptedLiteral = Regex("""EncryptedSharedPreferences\.create\(\s*\w+,\s*"([^"]+)""")
        val constantDefs = Regex("""(?:private )?const val \w+ = "([a-z_]+)"""")
        val names = mutableSetOf<String>()
        files.forEach { name ->
            val text = source(name)
            literal.findAll(text).forEach { names += it.groupValues[1] }
            encryptedLiteral.findAll(text).forEach { names += it.groupValues[1] }
            // 常量定义（如 SEEN_PREFS = "rollcall_seen"）按"被 getSharedPreferences 引用"收录
            constantDefs.findAll(text).forEach { match ->
                val constantName = Regex("""const val (\w+) =""").find(match.value)?.groupValues?.get(1)
                if (constantName != null && "getSharedPreferences($constantName" in text) {
                    names += match.groupValues[1]
                }
            }
        }
        return names
    }

    @Test
    fun `registry matches every getSharedPreferences file name in sources`() {
        val actual = actualFileNames()
        assertEquals("登记表与源码存储文件名必须一致（新增/改名需同步 PrefStores）", PrefStores.fileNames, actual)
    }

    @Test
    fun `encrypted primary store and logout-critical files are registered`() {
        // 关键面点名：加密主存储与登出清理链依赖的文件必须在表
        assertTrue("xmu_assistant" in PrefStores.fileNames)
        assertTrue("rollcall_seen" in PrefStores.fileNames)
    }
}
