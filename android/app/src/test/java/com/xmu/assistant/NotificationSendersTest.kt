package com.xmu.assistant

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSendersTest {
    @Test
    fun `smtp properties bound connect read and write waits`() {
        val properties = smtpProperties(port = 465)

        assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.timeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.enable"))
        assertEquals("465", properties.getProperty("mail.smtp.port"))
    }

    @Test
    fun `submission port keeps the same bounded waits with starttls`() {
        val properties = smtpProperties(port = 587)

        assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.timeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"))
        assertEquals("587", properties.getProperty("mail.smtp.port"))
    }

    // ===== 2026-08-30 补测债：发送器行为与纯函数矩阵 =====

    @Test
    fun `parse smtp ports normalizes dedupes filters and falls back to defaults`() {
        assertEquals(listOf(465, 587), parseSmtpPorts("465,587"))
        assertEquals(listOf(587, 465), parseSmtpPorts(" 587 , 465 , 587 "))
        assertEquals(listOf(465), parseSmtpPorts("465"))
        assertEquals(listOf(465, 587), parseSmtpPorts("abc,0,70000"))
        assertEquals(listOf(465, 587), parseSmtpPorts(""))
    }

    @Test
    fun `rollcall notification body renders all five lines with deadline fallback`() {
        val event = RollcallEvent(
            id = "1", courseTitle = "数据结构", teacher = "张三",
            type = "数字签到", status = "进行中", deadline = "2026-06-14 08:00", result = "待处理",
        )
        val body = rollcallNotificationBody(event, actionUrl = "xmurollcall://rollcall/1")
        assertEquals(
            listOf(
                "课程：数据结构",
                "类型：数字签到",
                "剩余：2026-06-14 08:00",
                "状态：待处理",
                "打开：xmurollcall://rollcall/1",
            ).joinToString("\n"),
            body,
        )
        val noDeadline = event.copy(deadline = "")
        assertEquals("剩余：未知", rollcallNotificationBody(noDeadline, "u").lines()[2])
    }

    @Test
    fun `escape json escapes structural characters control bytes and keeps plain cjk`() {
        assertEquals("a\\\\b", escapeJson("a\\b"))
        assertEquals("\\\"q\\\"", escapeJson("\"q\""))
        assertEquals("l\\nr", escapeJson("l\nr"))
        assertEquals("t\\tb", escapeJson("t\tb"))
        // 其余 <0x20 控制字节必须 \u00XX 转义，否则 payload 非法 JSON
        assertEquals("x\\u0001y", escapeJson("x\u0001y"))
        assertEquals("中文课程", escapeJson("中文课程"))
    }

    @Test
    fun `pushplus send posts escaped payload and accepts business success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"code\":200}"))
        server.start()
        try {
            val sender = PushPlusSender(token = "tok\"en", endpoint = server.url("/send").toString())
            sender.send("标题\"A", "第一行\n第二行")
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/send", request.path)
            assertEquals("POST", request.method)
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("tok\"en", payload.getString("token"))
            assertEquals("标题\"A", payload.getString("title"))
            assertEquals("第一行\n第二行", payload.getString("content"))
            assertEquals("txt", payload.getString("template"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus send rejects business failure code with code detail`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"code\":500,\"msg\":\"fail\"}"))
        server.start()
        try {
            val sender = PushPlusSender(token = "t", endpoint = server.url("/send").toString())
            val error = runCatching { sender.send("t", "b") }.exceptionOrNull()
            assertTrue("expected IllegalStateException", error is IllegalStateException)
            assertTrue(error!!.message!!.contains("code=500"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus send rejects http level failure`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502))
        server.start()
        try {
            val sender = PushPlusSender(token = "t", endpoint = server.url("/send").toString())
            val error = runCatching { sender.send("t", "b") }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("502"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus blank token is rejected before any request leaves`() {
        val server = MockWebServer()
        server.start()
        try {
            val sender = PushPlusSender(token = "   ", endpoint = server.url("/send").toString())
            val error = runCatching { sender.send("t", "b") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
