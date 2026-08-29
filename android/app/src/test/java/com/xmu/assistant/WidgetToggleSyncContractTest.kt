package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体检报告 P1-6 守卫：策略页开关 Widget 的 syncScheduleWidget 调用必须携带
 * 反推日历与手动周次，否则表外学期下切换开关会清空正确的「今日课程」卡片。
 * 纯源码契约（布局类改动 JVM 测试覆盖不到渲染行为，参照项目契约测试哲学）。
 */
class WidgetToggleSyncContractTest {
    @Test
    fun `widget toggle passes inferred calendar and manual week`() {
        val source = mainScreenSource()

        assertTrue(
            "onWidgetToggle must forward inferredCalendar from schedule cache",
            "inferredCalendar = schedule.cache.inferredCalendars[schedule.termCode]" in source,
        )
        assertTrue(
            "onWidgetToggle must forward manual academic week",
            "manualWeek = settings.manualAcademicWeek(schedule.termCode)" in source,
        )
    }

    private fun mainScreenSource(): String {
        val relativePath = "src/main/java/com/xmu/assistant/MainScreen.kt"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "MainScreen.kt was not found from ${File(".").absolutePath}" }
            .readText()
    }
}
