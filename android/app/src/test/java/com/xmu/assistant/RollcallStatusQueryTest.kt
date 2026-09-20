package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RollcallStatusQueryTest {
    @Test
    fun `pollOnce uses only the read-only status endpoint`() {
        val transport = RecordingQueryTransport(
            QueryHttpResponse(
                url = "https://lnt.xmu.edu.cn/api/radar/rollcalls",
                code = 200,
                location = null,
                body = """{"rollcalls":[{"rollcall_id":"fixture-rollcall","course_title":"fixture-course","status":"unsigned"}]}""",
                headers = emptyMap(),
            ),
        )
        val engine = RollcallEngine("session=fixture", transport)

        val events = engine.pollOnce()

        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://lnt.xmu.edu.cn/api/radar/rollcalls", request.url)
        assertEquals("session=fixture", request.headers["Cookie"])
        assertEquals(NetworkOperation.ROLLCALL_STATUS, request.operation)
        assertEquals("fixture-rollcall", events.single().id)
    }

    @Test
    fun `pollOnce reports an expired login in readable Chinese`() {
        val engine = RollcallEngine("session=fixture", responseTransport(401))

        val error = assertThrows(MainSessionExpiredException::class.java) { engine.pollOnce() }

        assertEquals("登录已过期", error.message)
        // friendlyMessage 对类型化的会话过期异常直接给出可操作提示
        assertEquals("登录已过期，请重新登录", friendlyMessage(error))
    }

    @Test
    fun `pollOnce reports a network failure with its response code`() {
        val engine = RollcallEngine("session=fixture", responseTransport(500))

        val error = assertThrows(IllegalStateException::class.java) { engine.pollOnce() }

        assertEquals("网络失败：500", error.message)
        assertEquals("网络连接失败，请稍后重试", friendlyMessage(error))
    }

    @Test
    fun `details are GET only deduplicated and preserve leading zero code`() {
        val transport = RoutingTransport(
            """{"rollcalls":[
                {"rollcall_id":"n1","is_number":true,"status":"unsigned"},
                {"rollcall_id":"n1","is_number":true,"status":"unsigned"},
                {"rollcall_id":"r1","is_radar":true,"status":"unsigned"}
            ]}""",
            mapOf(
                "n1" to Spec(200, """{"number_code":"0042","student_rollcalls":[{"user_no":"me","status":"on_call"},{"status":"absent"}]}"""),
                "r1" to Spec(200, """{"number_code":"9999","student_rollcalls":[{"status":"on_call"}]}"""),
            ),
        )
        val events = RollcallEngine("session=x", transport).pollWithDetails("me")
        assertTrue(transport.requests.all { it.method == "GET" })
        assertEquals(1, transport.requests.count { "/n1/student_rollcalls" in it.url })
        assertEquals("0042", events.first().numberCode)
        assertEquals(50.0, events.first().progress?.percentage ?: -1.0, 0.0)
        assertEquals(STATUS_SIGNED, events.first().ownStatus)
        assertEquals("", events.last().numberCode)
    }

    @Test
    fun `empty and forbidden details stay local while unauthorized propagates`() {
        val status = """{"rollcalls":[{"rollcall_id":"empty"},{"rollcall_id":"blocked"}]}"""
        val local = RoutingTransport(
            status,
            mapOf("empty" to Spec(200, """{"student_rollcalls":[]}"""), "blocked" to Spec(403, "")),
        )
        val events = RollcallEngine("session=x", local).pollWithDetails()
        assertEquals(2, events.size)
        assertNull(events[0].progress)
        assertNull(events[1].progress)

        val expired = RoutingTransport(
            """{"rollcalls":[{"rollcall_id":"x"}]}""",
            mapOf("x" to Spec(401, "")),
        )
        assertThrows(MainSessionExpiredException::class.java) { RollcallEngine("session=x", expired).pollWithDetails() }
    }

    @Test
    fun `deadline fallback skips null and expired flag is retained`() {
        val future = java.time.LocalDateTime.now().plusMinutes(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .toString().replace('T', ' ')
        val transport = RoutingTransport(
            """{"rollcalls":[
                {"rollcall_id":"ended","deadline":null,"rollcall_end_time":"$future","is_expired":true},
                {"rollcall_id":"start","rollcall_time":"$future"}
            ]}""",
            emptyMap(),
        )
        val events = RollcallEngine("session=x", transport).pollOnce()
        assertEquals(future, events[0].deadline)
        assertTrue(events[0].isExpired)
        assertEquals("", events[1].deadline)
        assertNull(events[1].remainingSeconds)
    }

    private fun responseTransport(code: Int) = RecordingQueryTransport(
        QueryHttpResponse(
            url = "https://lnt.xmu.edu.cn/api/radar/rollcalls",
            code = code,
            location = null,
            body = "",
            headers = emptyMap(),
        ),
    )

    private class RecordingQueryTransport(
        private val response: QueryHttpResponse,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return response.copy(url = request.url)
        }
    }

    private data class Spec(val code: Int, val body: String)
    private class RoutingTransport(
        private val statusBody: String,
        private val details: Map<String, Spec>,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            val spec = if ("/student_rollcalls" in request.url) {
                val id = request.url.substringAfter("/api/rollcall/").substringBefore("/student_rollcalls")
                details.getValue(id)
            } else Spec(200, statusBody)
            return QueryHttpResponse(request.url, spec.code, null, spec.body, emptyMap())
        }
    }
}
