package com.xmu.assistant

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Production discovery, parsing, code lookup, policy and answer; no real network. */
class DigitalSignInPipelineTest {
    @Test fun `complete automatic pipeline preserves leading zero and deduplicates accepted writes`() {
        for (mode in listOf(WAIT_BEFORE_ANSWER_NONE, WAIT_BEFORE_ANSWER_COUNT, WAIT_BEFORE_ANSWER_PERCENT)) {
            for (titleOnly in listOf(false, true)) {
                val scenario = Scenario()
                if (titleOnly) scenario.platform.activity.remove("course_id")
                scenario.platform.duplicateDiscovery = true
                val settings = settings(mode)
                scenario.poll(settings)
                scenario.poll(settings)
                assertEquals(1, scenario.platform.writes.size)
                val request = scenario.platform.writes.single()
                val body = JSONObject(request.body)
                assertEquals("0042", body.getString("numberCode"))
                assertEquals(setOf("deviceId", "numberCode"), body.keys().asSequence().toSet())
                UUID.fromString(body.getString("deviceId"))
                assertTrue(request.url.endsWith("/api/rollcall/fixture-number/answer_number_rollcall"))
                assertTrue(request.oneShot)
                assertEquals("application/json; charset=utf-8", request.contentType)
                assertEquals(setOf("fixture-number"), scenario.completed)
                assertEquals(listOf("fixture-number"), scenario.notifications)
                assertTrue(scenario.platform.requests.any { "rollcall_date=2026-06-03" in it.url })
            }
        }
    }

    @Test fun `signed leave ambiguous and expired events never write through the full pipeline`() {
        for (ownStatus in listOf("on_call", "on_personal_leave", "unrecognized_status")) {
            val scenario = Scenario()
            scenario.platform.ownStatus(ownStatus)
            scenario.poll()
            assertTrue(scenario.platform.writes.isEmpty())
        }
        val expired = Scenario()
        expired.platform.activity.put("is_expired", true)
        expired.poll()
        assertTrue(expired.platform.writes.isEmpty())
    }

    @Test fun `missing target code never borrows a code from the other activity`() {
        val scenario = Scenario()
        scenario.platform.code(null)
        scenario.poll()
        assertTrue(scenario.platform.writes.isEmpty())
        assertTrue(scenario.completed.isEmpty())
    }

    @Test fun `later missing code is not replaced by an earlier cached code and can recover`() {
        val scenario = Scenario()
        scenario.platform.secondStatus("absent")
        scenario.poll(settings(WAIT_BEFORE_ANSWER_COUNT))
        assertTrue(scenario.platform.writes.isEmpty())
        scenario.platform.secondStatus("on_call")
        scenario.platform.code(null)
        scenario.poll(settings(WAIT_BEFORE_ANSWER_COUNT))
        assertTrue(scenario.platform.writes.isEmpty())
        assertTrue(scenario.completed.isEmpty())
        scenario.platform.code("0048")
        scenario.poll(settings(WAIT_BEFORE_ANSWER_COUNT))
        assertEquals("0048", JSONObject(scenario.platform.writes.single().body).getString("numberCode"))
    }

    @Test fun `paused monitor changed settings and disabled automatic policy block writes`() {
        val paused = Scenario()
        paused.beforeDispatch = { paused.coordinator.requestInvalidateCurrent() }
        paused.poll()
        assertTrue(paused.platform.writes.isEmpty())
        val changed = Scenario()
        changed.settingsCurrent = false
        changed.poll()
        assertTrue(changed.platform.writes.isEmpty())
        val disabled = Scenario()
        disabled.poll(RollcallSettings(autoAnswerNumber = false))
        assertTrue(disabled.platform.writes.isEmpty())
    }

    @Test fun `empty discovery still has no web timetable discovery fallback`() {
        val scenario = Scenario()
        scenario.platform.discoverable = false
        scenario.poll()
        assertTrue(scenario.platform.writes.isEmpty())
        assertEquals(1, scenario.platform.requests.size)
    }

    @Test fun `explicit rejection retries with a newly queried code on the next poll only`() {
        val scenario = Scenario()
        scenario.platform.outcome = 400
        assertThrows(RollcallAnswerRejectedException::class.java) { scenario.poll() }
        assertEquals(1, scenario.platform.writes.size)
        assertTrue(scenario.completed.isEmpty())
        scenario.platform.outcome = 200
        scenario.platform.code("0048")
        scenario.poll()
        scenario.poll()
        assertEquals(listOf("0042", "0048"), scenario.platform.writes.map { JSONObject(it.body).getString("numberCode") })
        assertEquals(setOf("fixture-number"), scenario.completed)
    }

    @Test fun `uncertain acknowledgement waits for explicit own absence before a bounded retry`() {
        val scenario = Scenario()
        scenario.platform.outcome = IOException("offline lost acknowledgement")
        assertThrows(IOException::class.java) { scenario.poll() }
        scenario.platform.ownStatus("unrecognized_status")
        scenario.poll()
        assertEquals(1, scenario.platform.writes.size)
        scenario.platform.ownStatus("absent")
        scenario.platform.outcome = 200
        scenario.platform.code("0048")
        scenario.poll()
        assertEquals(2, scenario.platform.writes.size)
        assertEquals(setOf("fixture-number"), scenario.completed)
    }

    @Test fun `expired lookup session propagates without a write`() {
        val scenario = Scenario()
        scenario.platform.lookupStatus = 401
        assertThrows(MainSessionExpiredException::class.java) { scenario.poll() }
        assertTrue(scenario.platform.writes.isEmpty())
    }

    @Test fun `real HTTP transport completes the automatic chain on a loopback mock server`() {
        val fixture = Platform()
        val server = MockWebServer()
        val client = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build()
        fun enqueue(body: String) = server.enqueue(MockResponse().setResponseCode(200)
            .addHeader("Content-Type", "application/json").setBody(body))
        enqueue(JSONObject().put("rollcalls", JSONArray().put(fixture.activity)).toString())
        enqueue(fixture.detail.toString())
        enqueue(fixture.timetable.toString())
        enqueue("{}")
        server.start()
        try {
            val http = OkHttpQueryTransport(client)
            val localOnly = QueryHttpTransport { request ->
                check(request.url.startsWith("https://lnt.xmu.edu.cn/api/"))
                http.execute(request.copy(url = server.url(request.url.removePrefix("https://lnt.xmu.edu.cn")).toString()))
            }
            val engine = RollcallEngine("session=offline-fixture", localOnly, localOnly)
            val gate = MonitorRunGate()
            val token = gate.begin()
            val completed = mutableSetOf<String>()
            processRollcallMonitorPoll(
                engine.pollWithDetails("fixture-user"), RollcallSettings(autoAnswerNumber = true),
                mutableSetOf(), completed, mutableMapOf(),
                runIfActive = { action -> gate.runIfActive(token, true, action) },
                onNotify = { true }, onAnswer = engine::answer, onSuccess = {},
            )
            assertEquals(setOf("fixture-number"), completed)
            val requests = (1..4).map { requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            assertEquals(listOf("GET", "GET", "GET", "PUT"), requests.map { it.method })
            assertEquals("/api/timetable_rollcalls?course_ids=fixture-course&rollcall_date=2026-06-03", requests[2].path)
            assertEquals("/api/rollcall/fixture-number/answer_number_rollcall", requests[3].path)
            assertEquals("application/json; charset=utf-8", requests[3].getHeader("Content-Type"))
            assertEquals("session=offline-fixture", requests[3].getHeader("Cookie"))
            val body = JSONObject(requests[3].body.readUtf8())
            assertTrue(body.get("numberCode") is String)
            assertEquals("0042", body.getString("numberCode"))
            UUID.fromString(body.getString("deviceId"))
            assertEquals(4, server.requestCount)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private fun settings(mode: String) = RollcallSettings(
        autoAnswerNumber = true, waitBeforeAnswerMode = mode,
        waitBeforeAnswerCount = 2, waitBeforeAnswerPercent = 50,
    )

    private class Scenario {
        val platform = Platform()
        val coordinator = MonitorWorkerCoordinator()
        val token = requireNotNull(coordinator.start(true))
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val notifications = mutableListOf<String>()
        var settingsCurrent = true
        var beforeDispatch: () -> Unit = {}

        fun poll(settings: RollcallSettings = RollcallSettings(autoAnswerNumber = true)) {
            // The actual Service creates an engine each poll, rather than keeping old codes.
            val engine = RollcallEngine("session=offline-fixture", platform, platform)
            val events = engine.pollWithDetails("fixture-user")
            beforeDispatch()
            processRollcallMonitorPoll(
                events, settings, notified, completed, attempts,
                runIfActive = { action -> coordinator.runIfCurrent(token, true, action) },
                onNotify = { notifications += it.id; true },
                onAnswer = engine::answer,
                onSuccess = {},
                settingsStillCurrent = { settingsCurrent },
            )
        }
    }

    private class Platform : QueryHttpTransport {
        private val fixture = DigitalSignInPipelineTest::class.java
            .getResourceAsStream("/digital_protocol_fixture.json")!!
            .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val activity = fixture.getJSONObject("activity")
        val detail = fixture.getJSONObject("detail")
        val timetable = fixture.getJSONObject("timetable")
        val requests = mutableListOf<QueryHttpRequest>()
        val writes = mutableListOf<QueryHttpRequest>()
        var discoverable = true
        var duplicateDiscovery = false
        var outcome: Any = 200
        var lookupStatus = 200

        fun ownStatus(status: String) {
            detail.getJSONArray("student_rollcalls").getJSONObject(0)
                .put("status", status).put("rollcall_status", status)
        }

        fun secondStatus(status: String) {
            detail.getJSONArray("student_rollcalls").getJSONObject(2)
                .put("status", status).put("rollcall_status", status)
        }

        fun code(code: String?) {
            timetable.getJSONArray("rollcalls").getJSONObject(1).put("number_code", code ?: JSONObject.NULL)
        }

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            if (request.method == "PUT") {
                check(request.url.endsWith("/api/rollcall/fixture-number/answer_number_rollcall"))
                writes += request
                val spec = outcome
                if (spec is IOException) throw spec
                val current = timetable.getJSONArray("rollcalls").getJSONObject(1).optRealString("number_code")
                val code = if (JSONObject(request.body).getString("numberCode") == current) spec as Int else 400
                if (code == 200) ownStatus("on_call")
                return response(request, code, "{}")
            }
            check(request.method == "GET")
            val body = when {
                request.url.endsWith("/api/radar/rollcalls") -> {
                    val rows = JSONArray()
                    if (discoverable) {
                        rows.put(activity)
                        if (duplicateDiscovery) rows.put(activity)
                    }
                    JSONObject().put("rollcalls", rows)
                }
                request.url.endsWith("/api/rollcall/fixture-number/student_rollcalls") -> detail
                "/api/timetable_rollcalls?" in request.url -> {
                    check(request.url.endsWith("course_ids=fixture-course&rollcall_date=2026-06-03"))
                    return response(request, lookupStatus, timetable.toString())
                }
                request.url.endsWith("/api/my-courses?per_page=1000") ->
                    JSONObject().put("courses", JSONArray().put(JSONObject().put("id", "fixture-course").put("name", "Fixture course")))
                else -> error("Unscripted read: ${request.url}")
            }
            return response(request, 200, body.toString())
        }

        private fun response(request: QueryHttpRequest, code: Int, body: String) =
            QueryHttpResponse(request.url, code, null, body, emptyMap())
    }
}
