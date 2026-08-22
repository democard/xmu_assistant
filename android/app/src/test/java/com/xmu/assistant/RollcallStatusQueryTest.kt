package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
