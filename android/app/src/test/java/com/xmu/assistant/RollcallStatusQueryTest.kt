package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.IOException

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

    @Test
    fun `number answer sends one PUT with the exact fresh code and cookie`() {
        val transport = ScriptedAnswerTransport(mutableListOf(answerResponse(200)))
        val engine = RollcallEngine("session=fixture", answerTransport = transport)

        assertTrue(engine.answer(numberEvent("0042")))

        val request = transport.requests.single()
        assertEquals("PUT", request.method)
        assertEquals("https://lnt.xmu.edu.cn/api/rollcall/n1/answer_number_rollcall", request.url)
        assertEquals("session=fixture", request.headers["Cookie"])
        assertEquals("application/json; charset=utf-8", request.contentType)
        assertTrue(request.oneShot)
        val payload = JSONObject(request.body)
        assertEquals("0042", payload.getString("numberCode"))
        assertTrue(payload.getString("deviceId").isNotBlank())
    }

    @Test
    fun `number answer keeps resource forbidden separate from expired session`() {
        val forbidden = RollcallEngine(
            "session=x",
            answerTransport = ScriptedAnswerTransport(mutableListOf(answerResponse(403))),
        )
        val rejected = assertThrows(RollcallAnswerRejectedException::class.java) {
            forbidden.answer(numberEvent("1234"))
        }
        assertEquals(403, rejected.responseCode)

        val unauthorized = RollcallEngine(
            "session=x",
            answerTransport = ScriptedAnswerTransport(mutableListOf(answerResponse(401))),
        )
        assertThrows(MainSessionExpiredException::class.java) { unauthorized.answer(numberEvent("1234")) }
    }

    @Test
    fun `number answer treats server failure as an uncertain write result`() {
        val engine = RollcallEngine(
            "session=x",
            answerTransport = ScriptedAnswerTransport(mutableListOf(answerResponse(503))),
        )

        val uncertain = assertThrows(RollcallAnswerUncertainException::class.java) {
            engine.answer(numberEvent("1234"))
        }

        assertEquals(503, uncertain.responseCode)
    }

    @Test
    fun `number answer timeout is not converted into an accepted or rejected response`() {
        val transport = ScriptedAnswerTransport(mutableListOf(IOException("timeout")))
        val engine = RollcallEngine("session=x", answerTransport = transport)

        assertThrows(IOException::class.java) { engine.answer(numberEvent("1234")) }
        assertEquals(1, transport.requests.size)
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

    private fun numberEvent(code: String) = RollcallEvent(
        id = "n1",
        courseTitle = "课程",
        teacher = "老师",
        type = "数字签到",
        status = "未签",
        numberCode = code,
    )

    private fun answerResponse(code: Int) = QueryHttpResponse(
        url = "https://lnt.xmu.edu.cn/api/rollcall/n1/answer_number_rollcall",
        code = code,
        location = null,
        body = "",
        headers = emptyMap(),
    )

    private class ScriptedAnswerTransport(
        private val outcomes: MutableList<Any>,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return when (val outcome = outcomes.removeAt(0)) {
                is QueryHttpResponse -> outcome.copy(url = request.url)
                is Throwable -> throw outcome
                else -> error("unsupported scripted outcome")
            }
        }
    }

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
