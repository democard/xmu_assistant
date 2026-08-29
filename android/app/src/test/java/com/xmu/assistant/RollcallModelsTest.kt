package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RollcallModels 模型与课件错误归类行为锁定（2026-08-30 补测债）。
 * 状态归一化/剩余秒数见既有覆盖；此处补数据类默认值与
 * shortCoursewareError / coursewareFailureStatus 判定矩阵。
 */
class RollcallModelsTest {

    @Test
    fun `login result defaults display name to blank`() {
        val result = LoginResult(cookieHeader = "session=abc")
        assertEquals("session=abc", result.cookieHeader)
        assertEquals("", result.displayName)
    }

    @Test
    fun `rollcall event defaults keep result pending and seconds unknown`() {
        val event = RollcallEvent(id = "5123", courseTitle = "数据结构", teacher = "张三", type = "数字签到", status = "进行中")
        assertEquals("", event.numberCode)
        assertEquals("", event.deadline)
        assertEquals("待处理", event.result)
        assertEquals(null, event.remainingSeconds)
        val full = RollcallEvent(
            id = "5124", courseTitle = "操作系统", teacher = "李四", type = "雷达签到", status = "进行中",
            numberCode = "123", deadline = "2026-06-14 08:00", result = "已提交", remainingSeconds = 120L,
        )
        assertEquals("已提交", full.result)
        assertEquals(120L, full.remainingSeconds)
    }

    @Test
    fun `rollcall settings default to 30s poll and no auto answer`() {
        val settings = RollcallSettings()
        assertEquals(30, settings.pollIntervalSeconds)
        assertEquals(false, settings.autoAnswerNumber)
        assertEquals(false, settings.autoAnswerRadar)
    }

    @Test
    fun `short courseware error classifies platform refusal network expiry and missing url`() {
        assertEquals(COURSEWARE_STATUS_LIMITED, shortCoursewareError("下载被平台拒绝（版权保护）"))
        assertEquals(COURSEWARE_STATUS_LIMITED, shortCoursewareError("无下载权限"))
        assertEquals("网络失败", shortCoursewareError("Connection timeout"))
        assertEquals("网络失败", shortCoursewareError("network unreachable"))
        assertEquals("登录过期", shortCoursewareError("401 Unauthorized"))
        assertEquals("登录过期", shortCoursewareError("登录态失效"))
        assertEquals("平台限制", shortCoursewareError("HTTP 403 forbidden"))
        assertEquals("平台未提供地址", shortCoursewareError("404 Not Found"))
        assertEquals("平台未提供地址", shortCoursewareError("缺少 reference_id"))
        assertEquals(COURSEWARE_STATUS_FAILED, shortCoursewareError("某种未知错误"))
    }

    @Test
    fun `courseware failure status embeds short reason`() {
        assertEquals(
            "$COURSEWARE_STATUS_FAILED（网络失败）",
            coursewareFailureStatus("connection reset by peer"),
        )
        assertEquals(
            "$COURSEWARE_STATUS_FAILED（$COURSEWARE_STATUS_LIMITED）",
            coursewareFailureStatus("下载被平台拒绝"),
        )
    }
}
