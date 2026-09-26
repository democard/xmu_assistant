package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `pollOnce accepts a normal empty rollcalls array without interpreting business codes`() {
        val transport = responseTransport(200, """{"rollcalls":[],"code":401}""")

        assertTrue(RollcallEngine("session=fixture", transport).pollOnce().isEmpty())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `pollOnce rejects missing null and wrong typed rollcalls instead of reporting empty success`() {
        val bodies = listOf(
            "{}",
            """{"code":500,"message":"temporary upstream failure"}""",
            """{"code":401,"message":"unknown business protocol"}""",
            """{"rollcalls":null}""",
            """{"rollcalls":{}}""",
            """{"rollcalls":"[]"}""",
        )
        for (body in bodies) {
            val transport = responseTransport(200, body)
            val failure = assertThrows(IllegalStateException::class.java) {
                RollcallEngine("session=fixture", transport).pollOnce()
            }

            assertFalse("unknown business codes must not imply session expiry", failure is MainSessionExpiredException)
            assertTrue("schema failure should explain the invalid list", failure.message.orEmpty().contains("签到列表格式异常"))
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `pollOnce classifies a known login form returned with HTTP 200 as session expiry`() {
        val transport = responseTransport(200, knownLoginPage)

        assertThrows(MainSessionExpiredException::class.java) {
            RollcallEngine("session=fixture", transport).pollOnce()
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `unknown HTML is a read failure rather than proof of session expiry`() {
        val transport = responseTransport(200, "<html><h1>Upstream maintenance</h1></html>")
        val failure = assertThrows(Exception::class.java) {
            RollcallEngine("session=fixture", transport).pollOnce()
        }

        assertFalse(failure is MainSessionExpiredException)
    }

    @Test
    fun `known login form in student detail propagates instead of falling back to summary`() {
        val transport = RoutingTransport(
            """{"rollcalls":[{"rollcall_id":"n1","is_number":true,"status":"unsigned"}]}""",
            mapOf("n1" to Spec(200, knownLoginPage)),
        )

        assertThrows(MainSessionExpiredException::class.java) {
            RollcallEngine("session=fixture", transport).pollWithDetails("me")
        }
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.method == "GET" })
    }

    @Test
    fun `unknown HTML detail remains unavailable without falsely expiring the whole session`() {
        val transport = RoutingTransport(
            """{"rollcalls":[{"rollcall_id":"n1","is_number":true,"status":"unsigned"}]}""",
            mapOf("n1" to Spec(200, "<html><h1>Upstream maintenance</h1></html>")),
        )

        val event = RollcallEngine("session=fixture", transport).pollWithDetails("me").single()

        assertEquals("n1", event.id)
        assertNull(event.ownStatus)
        assertEquals("", event.numberCode)
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
    fun `number answer login form is session expiry and never completes the rollcall`() {
        val transport = ScriptedAnswerTransport(mutableListOf(answerResponse(200).copy(body = knownLoginPage)))
        val engine = RollcallEngine("session=fixture", answerTransport = transport)
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var healthSuccesses = 0

        assertThrows(MainSessionExpiredException::class.java) {
            processRollcallMonitorPoll(
                events = listOf(numberEvent("0042")),
                settings = RollcallSettings(autoAnswerNumber = true),
                notifiedIds = mutableSetOf(),
                completedIds = completed,
                answerAttempts = attempts,
                runIfActive = { action -> action(); true },
                onNotify = { true },
                onAnswer = engine::answer,
                onSuccess = { healthSuccesses++ },
            )
        }

        assertTrue(completed.isEmpty())
        assertTrue(attempts.isEmpty())
        assertEquals(0, healthSuccesses)
        assertEquals(1, transport.requests.size)
        assertEquals("PUT", transport.requests.single().method)
    }

    @Test
    fun `forbidden response semantics take precedence over login looking bodies`() {
        val details = RoutingTransport(
            """{"rollcalls":[{"rollcall_id":"n1","is_number":true}]}""",
            mapOf("n1" to Spec(403, knownLoginPage)),
        )
        assertNull(RollcallEngine("session=fixture", details).pollWithDetails().single().ownStatus)

        val answers = ScriptedAnswerTransport(mutableListOf(answerResponse(403).copy(body = knownLoginPage)))
        val rejection = assertThrows(RollcallAnswerRejectedException::class.java) {
            RollcallEngine("session=fixture", answerTransport = answers).answer(numberEvent("0042"))
        }
        assertEquals(403, rejection.responseCode)
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

    private val knownLoginPage = """
        <html><form action="https://c-identity.xmu.edu.cn/auth/realms/xmu/login-actions/authenticate">
          <input name="username"><input name="password" type="password">
        </form></html>
    """.trimIndent()

    private fun responseTransport(code: Int, body: String = "") = RecordingQueryTransport(
        QueryHttpResponse(
            url = "https://lnt.xmu.edu.cn/api/radar/rollcalls",
            code = code,
            location = null,
            body = body,
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
