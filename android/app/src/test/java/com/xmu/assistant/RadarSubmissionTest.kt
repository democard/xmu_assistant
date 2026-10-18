package com.xmu.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.math.*

class RadarSubmissionTest {
    private val radarEvent = RollcallEvent("fixture", "Fixture", "Teacher", "雷达签到", "ongoing")
    private class Transport(val respond: (QueryHttpRequest) -> QueryHttpResponse) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return respond(request)
        }
    }
    private fun response(request: QueryHttpRequest, code: Int, body: String) =
        QueryHttpResponse(request.url, code, null, body, emptyMap())
    private fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val p = Math.toRadians(a.first)
        val q = Math.toRadians(b.first)
        val h = sin((q - p) / 2).pow(2) + cos(p) * cos(q) * sin(Math.toRadians(b.second - a.second) / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h).coerceAtMost(1.0))
    }

    @Test fun `complete flow succeeds for both former planar failures`() {
        for (target in listOf(24.4371 to 118.0975, 24.45 to 118.1)) {
            val transport = Transport { request ->
                val body = JSONObject(request.body)
                val distance = meters(body.getDouble("latitude") to body.getDouble("longitude"), target)
                response(request, if (distance < 3) 200 else 422, """{"distance":$distance}""")
            }
            val engine = RollcallEngine("session=fixture", transport, transport)
            assertTrue(engine.answerRadar("fixture"))
            assertTrue(transport.requests.size <= 4)
            val bodies = transport.requests.map { JSONObject(it.body) }
            assertEquals(1, bodies.map { it.getString("deviceId") }.toSet().size)
            val last = bodies.last()
            assertTrue(meters(last.getDouble("latitude") to last.getDouble("longitude"), target) < 0.001)
            assertTrue(transport.requests.all { it.method == "PUT" && it.oneShot })
        }
    }

    @Test fun `201 distance response continues solving instead of reporting success`() {
        var call = 0
        val transport = Transport { request ->
            call += 1
            response(request, if (call < 3) 201 else 200, if (call < 3) """{"distance":50000}""" else "{}")
        }
        assertTrue(RollcallEngine("fixture", transport, transport).answerRadar("fixture"))
        assertEquals(3, call)
    }

    @Test fun `HTTP rejection exposes stage and reason without a second write`() {
        val transport = Transport { request -> response(request, 403, """{"message":"location denied token=secret"}""") }
        val engine = RollcallEngine("fixture", transport, transport)
        assertFalse(engine.answerRadar("fixture"))
        assertEquals(1, transport.requests.size)
        assertTrue(engine.lastRadarFailure!!.contains("HTTP 403"))
        assertTrue(engine.lastRadarFailure!!.contains("location denied"))
        assertFalse(engine.lastRadarFailure!!.contains("secret"))
    }

    @Test fun `invalid distance is rejected before another write`() {
        for (value in listOf("null", "true", "-1", "\"42\"")) {
            val transport = Transport { request -> response(request, 422, """{"distance":$value}""") }
            val engine = RollcallEngine("fixture", transport, transport)
            assertFalse(engine.answerRadar("fixture"))
            assertEquals(1, transport.requests.size)
            assertTrue(engine.lastRadarFailure!!.contains("distance"))
        }
    }

    @Test fun `timeout is one shot and retains uncertain outcome`() {
        val transport = Transport { throw IOException("receipt lost") }
        val engine = RollcallEngine("fixture", transport, transport)
        assertFalse(engine.answerRadar("fixture"))
        assertEquals(1, transport.requests.size)
        assertTrue(engine.lastRadarFailure!!.contains("结果未知"))
    }

    @Test fun `known refusal keeps rejected outcome and radar message for monitor`() {
        val transport = Transport { request -> response(request, 403, """{"message":"location denied"}""") }
        val engine = RollcallEngine("fixture", transport, transport)
        try {
            engine.answer(radarEvent)
            fail("expected typed refusal")
        } catch (error: RollcallAnswerRejectedException) {
            assertEquals(403, error.responseCode)
            assertTrue(error.message!!.contains("雷达签到"))
            assertTrue(error.message!!.contains("location denied"))
        }
    }

    @Test fun `lost receipt is uncertain instead of a definite refusal`() {
        val transport = Transport { throw IOException("receipt lost") }
        val engine = RollcallEngine("fixture", transport, transport)
        try {
            engine.answer(radarEvent)
            fail("expected uncertain outcome")
        } catch (error: IllegalStateException) {
            assertFalse(error is RollcallAnswerRejectedException)
            assertTrue(error.message!!.contains("结果未知"))
        }
    }

    @Test fun `HTML gateway response is not attendance success`() {
        for (text in listOf("<html><body>Bad gateway</body></html>", "<body>Bad gateway</body>", "<h1>Bad gateway</h1>", "Bad gateway")) {
            val transport = Transport { request ->
                response(request, 200, text).copy(headers = mapOf("Content-Type" to listOf("text/html")))
            }
            assertFalse(RollcallEngine("fixture", transport, transport).answerRadar("fixture"))
            assertEquals(1, transport.requests.size)
        }
    }

    @Test(expected = MainSessionExpiredException::class)
    fun `200 login form is an expired session instead of successful attendance`() {
        val html = """<html><form action="https://c-identity.xmu.edu.cn/auth/realms/xmu/login-actions/authenticate"><input id="pwdEncryptSalt"></form></html>"""
        val transport = Transport { request -> response(request, 200, html) }
        RollcallEngine("fixture", transport, transport).answerRadar("fixture")
    }
}
