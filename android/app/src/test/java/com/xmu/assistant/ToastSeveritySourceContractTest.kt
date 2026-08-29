package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * toast 级别通道守护（B4 severity 显式化）。
 *
 * 约定：show() 默认 INFO；告警/失败必须走 showWarning/showError 显式级别。
 * 子串推断（"失败"/"请先"）只是未迁移调用点的兜底，禁止删除；
 * ToastBar 渲染签名必须吃 ToastSeverity（禁止退回 Boolean 二态）。
 */
class ToastSeveritySourceContractTest {
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

    @Test
    fun `toast bar must consume explicit severity`() {
        val ui = source("UiComponents.kt")
        assertTrue("ToastBar 必须以 ToastSeverity 为参数", "fun ToastBar(message: String, severity: ToastSeverity)" in ui)
        assertTrue("severity 必须定义三态", "enum class ToastSeverity { INFO, WARNING, ERROR }" in ui)
    }

    @Test
    fun `show helpers must set explicit severity and default resets to info`() {
        val main = source("MainActivity.kt")
        val showFun = main.substringAfter("fun show(message: String)", missingDelimiterValue = "")
            .substringBefore("fun showWarning")
        assertTrue("show() 必须复位 INFO（防止上一条告警级别串染中性提示）",
            "toastSeverity = ToastSeverity.INFO" in showFun)
        assertTrue("showWarning 必须存在且设 WARNING",
            "toastSeverity = ToastSeverity.WARNING" in main)
        assertTrue("showError 必须存在且设 ERROR",
            "toastSeverity = ToastSeverity.ERROR" in main)
    }

    @Test
    fun `substring fallback must be kept as safety net`() {
        val mainScreen = source("MainScreen.kt")
        assertTrue("子串兜底（失败）不许删", "toast.contains(\"失败\")" in mainScreen)
        assertTrue("子串兜底（请先）不许删", "toast.contains(\"请先\")" in mainScreen)
        assertTrue("显式级别必须优先于子串兜底",
            "toastSeverity != ToastSeverity.INFO -> toastSeverity" in mainScreen)
    }

    @Test
    fun `migrated high frequency sites use explicit severity`() {
        val homeActions = source("HomeActions.kt")
        val main = source("MainActivity.kt")
        assertTrue("登录失败必须走 showError",
            "showError(\"登录失败：" in homeActions)
        assertTrue("登录信息已更改必须走 showWarning",
            "showWarning(\"登录信息已更改，请重新登录\")" in homeActions)
        assertTrue("课程读取失败必须走 showError",
            "showError(\"课程读取失败：" in main)
        assertTrue("会话过期提醒必须走 showWarning",
            "showWarning(\"登录已过期，请手动登录\")" in main)
        assertTrue("requireLogin 必须走 showWarning",
            "showWarning(\"请先登录\")" in main)
    }

    @Test
    fun `home actions must be wired with severity channels`() {
        val main = source("MainActivity.kt")
        assertTrue("HomeActions 构造必须注入 showWarning", "showWarning = ::showWarning" in main)
        assertTrue("HomeActions 构造必须注入 showError", "showError = ::showError" in main)
    }
}
