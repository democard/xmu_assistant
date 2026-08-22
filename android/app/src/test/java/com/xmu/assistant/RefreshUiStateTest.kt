package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshUiStateTest {
    @Test
    fun `refresh state keeps cached data message actionable`() {
        assertEquals(
            "刷新失败，正在显示上次数据：网络连接失败，请稍后重试",
            refreshStateText("网络连接失败，请稍后重试", hasData = true),
        )
    }

    @Test
    fun `refresh state without data asks the user to retry`() {
        assertEquals(
            "刷新失败：网络连接失败，请稍后重试。请重试。",
            refreshStateText("网络连接失败，请稍后重试", hasData = false),
        )
    }

    @Test
    fun `refresh formatter gives expired sessions a login action`() {
        assertEquals(
            "登录已过期，请重新登录",
            refreshFailureMessage(IllegalStateException("登录过期")),
        )
    }

    @Test
    fun `schedule refresh formatter exposes safe actionable categories`() {
        assertEquals(
            "教务登录已过期，请重新登录",
            refreshFailureMessage(ScheduleSessionExpiredException()),
        )
        assertEquals(
            "无法连接厦大教务系统，请检查网络后重试",
            refreshFailureMessage(ScheduleNetworkException(IllegalStateException("secret"))),
        )
        assertEquals(
            "教务课表接口返回异常，请稍后重试",
            refreshFailureMessage(ScheduleResponseException("server detail")),
        )
        assertEquals(
            "没有找到可用学期，请稍后重试",
            refreshFailureMessage(ScheduleTermUnavailableException()),
        )
    }

    @Test
    fun `refresh formatter hides mojibake details behind a retry action`() {
        assertEquals(
            "数据刷新失败，请稍后重试",
            refreshFailureMessage(IllegalStateException("æ•°æ®èŽ·å–å¤±è´¥")),
        )
    }

    @Test
    fun `login formatter preserves actionable credential errors`() {
        assertEquals(
            "账号或密码可能不正确",
            friendlyMessage(IllegalStateException("账号或密码可能不正确")),
        )
    }

    @Test
    fun `blocked academic login maps to rate limit message not generic format error`() {
        // 登录页异常（被服务端拒绝/限流）必须显示明确文案，绝不能落进"Empty key"兜底误导文案
        assertEquals(
            "教务登录请求过于频繁，请稍后再试（网络环境可能被临时限制）",
            refreshFailureMessage(AcademicLoginBlockedException()),
        )
        // 空盐加密的原始崩溃（IllegalArgumentException: Empty key）不再可能出现：
        // loginAndGetToken 在盐为空时直接抛 AcademicLoginBlockedException
        assertEquals(
            "数据刷新失败，请稍后重试",
            refreshFailureMessage(IllegalArgumentException("Empty key")),
        )
    }
}
