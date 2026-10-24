package com.xmu.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RadarConfiguredSubmissionTest {
    @Test fun `invalid supplied coordinate sends no write`() {
        val transport = RecordingTransport()
        val coordinate = RadarCoordinate("bad", "c", "数学", "invalid", Double.NaN, 118.3, "custom", true)
        val engine = RollcallEngine("fixture", transport, transport) { coordinate }
        org.junit.Assert.assertThrows(RollcallAnswerNotSubmittedException::class.java) {
            engine.answer(RollcallEvent("r", "数学", "老师", "雷达签到", "未签"))
        }
        assertTrue(transport.requests.isEmpty())
    }

    private class RecordingTransport : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return QueryHttpResponse(request.url, 200, null, "{}", emptyMap())
        }
    }

    @Test fun `configured course submits one exact saved coordinate`() {
        val transport = RecordingTransport()
        val coordinate = RadarCoordinate(
            id = "place-1",
            courseId = "course-1",
            courseTitle = "数学建模",
            name = "坤銮楼 B207",
            latitude = 24.605607,
            longitude = 118.313093,
            source = "preset",
            preferred = true,
        )
        val engine = RollcallEngine("session=test", transport, transport) { coordinate }
        assertTrue(engine.answer(RollcallEvent("rollcall-1", "数学建模", "老师", "雷达签到", "进行中", courseId = "course-1")))
        assertEquals(1, transport.requests.size)
        val body = JSONObject(transport.requests.single().body)
        assertEquals(24.605607, body.getDouble("latitude"), 0.0)
        assertEquals(118.313093, body.getDouble("longitude"), 0.0)
    }

    @Test fun `missing course coordinate sends no write`() {
        val transport = RecordingTransport()
        val engine = RollcallEngine("session=test", transport, transport) { null }
        try {
            engine.answer(RollcallEvent("rollcall-1", "数学建模", "老师", "雷达签到", "进行中", courseId = "course-1"))
            fail("expected configuration error")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("尚未配置课程坐标"))
        }
        assertTrue(transport.requests.isEmpty())
    }
}
