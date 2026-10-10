package com.xmu.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Executors

class RollcallNumberCodeLookupTest {
    private val timetable = """{"rollcalls":[
        {"id":"other","course":{"id":"c1"},"number_code":"9999"},
        {"id":"r1","course":{"id":"c1"},"is_number":true,"number_code":"0048",
         "student_rollcalls":[{"student_id":"fixture","status":"present"}]}
    ]}"""

    @Test fun `UTC date agrees for aware and school display times`() {
        for (time in listOf("2026-06-03T23:48:00Z", "2026-06-04T07:48:00+08:00", "2026-06-04 07:48:00")) {
            assertEquals(listOf("2026-06-03"), rollcallCodeDates(time))
        }
        assertEquals(listOf("2026-06-04", "2026-06-03"), rollcallCodeDates(now = Instant.parse("2026-06-04T00:05:00Z")))
        assertEquals(listOf("2026-06-04", "2026-06-03"), rollcallCodeDates(endTime = "2026-06-04T00:10:00Z"))
    }

    @Test fun `matches exact activity and course without recursively borrowing a code`() {
        assertEquals("0048", matchingTimetableCode(JSONObject(timetable), "r1", "c1"))
        assertEquals("", matchingTimetableCode(JSONObject(timetable), "missing", "c1"))
        assertEquals("", matchingTimetableCode(JSONObject(timetable), "r1", "c2"))
        assertEquals("", matchingTimetableCode(JSONObject("""{"rollcalls":[{"id":"r1","number_code":null,"student_rollcalls":[{"number_code":"9999"}]}]}"""), "r1"))
    }

    @Test fun `old valid code avoids fallback reads and missing context avoids probing`() {
        val transport = Transport { error("unexpected request") }
        val lookup = RollcallNumberCodeLookup("fixture", transport)
        assertEquals("0048", lookup.complete("r1", JSONObject("""{"number_code":"0048"}"""), "c1"))
        assertEquals("", lookup.complete("r1", null))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `poll fallback preserves legacy own status and feeds a single unchanged PUT`() {
        val transport = Transport { request ->
            when {
                request.url.endsWith("/api/radar/rollcalls") -> response(body = """{"rollcalls":[
                    {"rollcall_id":"r1","course_id":"c1","course_title":"Fixture","is_number":true,"status":"ongoing","rollcall_time":"2026-06-04T07:48:00+08:00"},
                    {"rollcall_id":"r1","course_id":"c1","course_title":"Fixture","is_number":true,"status":"ongoing","rollcall_time":"2026-06-04T07:48:00+08:00"}
                ]}""")
                "/student_rollcalls" in request.url -> response(body = """{"number_code":null,"student_rollcalls":[{"user_no":"fixture","status":"absent"}]}""")
                "/api/timetable_rollcalls?" in request.url -> response(body = timetable)
                request.method == "PUT" -> response()
                else -> error("unexpected request ${request.url}")
            }
        }
        val engine = RollcallEngine("session=fixture", transport, transport)
        val events = engine.pollWithDetails("fixture")
        assertEquals(listOf("0048", "0048"), events.map { it.numberCode })
        assertTrue(events.all { it.ownStatus != STATUS_SIGNED })
        assertEquals(1, transport.requests.count { "/student_rollcalls" in it.url })
        assertEquals(1, transport.requests.count { "/timetable_rollcalls" in it.url })
        assertTrue(transport.requests.last().url.endsWith("course_ids=c1&rollcall_date=2026-06-03"))
        assertTrue(transport.requests.all { it.method == "GET" })
        assertTrue(engine.answer(events.first()))
        val put = transport.requests.single { it.method == "PUT" }
        assertTrue(put.url.endsWith("/api/rollcall/r1/answer_number_rollcall"))
        // QueryHttpRequest carries a String JSON body, retaining the leading zero.
        assertTrue(put.body.orEmpty().contains("0048"))
    }

    @Test fun `history null code fallback does not create an own attendance verdict`() {
        val transport = Transport { request ->
            when {
                "/student_rollcalls" in request.url -> response(body = """{"number_code":null}""")
                "/timetable_rollcalls" in request.url -> response(body = timetable)
                else -> error("unexpected request")
            }
        }
        val client = RollcallHistoryClient("fixture", transport, { size -> Executors.newFixedThreadPool(size) })
        val row = RollcallHistoryItem("r1", "c1", "Fixture", "数字签到", "06-04 07:48",
            Instant.parse("2026-06-03T23:48:00Z").toEpochMilli(), "")
        val result = client.resolveOwnStatuses(listOf(row), "fixture").single()
        assertEquals("0048", result.numberCode)
        assertEquals(STATUS_UNKNOWN, result.ownStatus)
        assertNull(result.progress)
        assertEquals(2, transport.requests.size)
    }

    @Test fun `resource denial is empty but expiry propagates without a write`() {
        val forbidden = Transport { response(code = 403) }
        assertEquals("", RollcallNumberCodeLookup("fixture", forbidden).complete("r1", null, "c1", "2026-06-03T12:00:00Z"))
        for (spec in listOf(response(code = 401), response(code = 302, location = "https://c-identity.xmu.edu.cn/login"))) {
            val expired = Transport { spec }
            assertThrows(MainSessionExpiredException::class.java) {
                RollcallNumberCodeLookup("fixture", expired).complete("r1", null, "c1", "2026-06-03T12:00:00Z")
            }
            assertEquals(1, expired.requests.size)
            assertEquals("GET", expired.requests.single().method)
        }
    }

    @Test fun `title resolution requires a unique enrolled course`() {
        val transport = Transport { request ->
            if ("/my-courses" in request.url) response(body = """{"courses":[{"id":"c1","name":"Fixture"}]}""")
            else response(body = timetable)
        }
        assertEquals("0048", RollcallNumberCodeLookup("fixture", transport).complete("r1", null,
            rollcallTime = "2026-06-03T12:00:00Z", courseTitle = "Fixture"))
        val ambiguous = Transport { response(body = """{"courses":[{"id":"c1","name":"Fixture"},{"id":"c2","name":"Fixture"}]}""") }
        assertEquals("", RollcallNumberCodeLookup("fixture", ambiguous).complete("r1", null, courseTitle = "Fixture"))
        assertEquals(1, ambiguous.requests.size)
    }

    private fun response(code: Int = 200, body: String = "{}", location: String? = null) =
        QueryHttpResponse("https://lnt.xmu.edu.cn/api/fixture", code, location, body, emptyMap())

    private class Transport(val route: (QueryHttpRequest) -> QueryHttpResponse) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            return route(request).copy(url = request.url)
        }
    }
}
