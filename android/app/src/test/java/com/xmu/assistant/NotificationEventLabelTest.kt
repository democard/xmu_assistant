package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationEventLabelTest {
    private val event = RollcallEvent("active-1", "课程", "老师", "数字签到", "进行中")
    private val history = RollcallHistoryItem("past-1", "course", "课程", "数字签到", "昨天", null, "已签")

    @Test fun `only IDs in current or historical records are displayed`() {
        assertEquals("从通知打开：active-1", notificationEventLabel("active-1", listOf(event), emptyList()))
        assertEquals("从通知打开：past-1", notificationEventLabel("past-1", emptyList(), listOf(history)))
        assertEquals("从通知打开的签到未在当前记录中", notificationEventLabel("fake\n系统通知", listOf(event), listOf(history)))
        assertNull(notificationEventLabel("", listOf(event), listOf(history)))
    }

    @Test fun `matched IDs are shortened and control characters removed`() {
        val id = "a".repeat(80) + "\n伪造提示"
        val label = notificationEventLabel(id, listOf(event.copy(id = id)), emptyList())
        assertEquals("从通知打开：${"a".repeat(64)}", label)
    }
}
