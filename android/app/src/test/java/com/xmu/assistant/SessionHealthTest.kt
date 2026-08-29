package com.xmu.assistant

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHealthTest {
    @Test
    fun `valid JSON object is valid and uses the status request contract`() {
        val transport = RecordingTransport { request -> response(request, 200, body = """{"rollcalls":[]}""") }

        val health = SessionHealthProbe(transport).check("session=fixture")

        assertEquals(SessionHealth.VALID, health)
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://lnt.xmu.edu.cn/api/radar/rollcalls", request.url)
        assertEquals("session=fixture", request.headers["Cookie"])
        assertEquals("Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36", request.headers["User-Agent"])
        assertEquals("zh-CN,zh;q=0.9", request.headers["Accept-Language"])
        assertEquals(NetworkOperation.ROLLCALL_STATUS, request.operation)
    }

    @Test
    fun `401 is expired`() {
        assertEquals(SessionHealth.EXPIRED, SessionHealthProbe(responseTransport(401)).check("session=fixture"))
    }

    @Test
    fun `403 is expired`() {
        assertEquals(SessionHealth.EXPIRED, SessionHealthProbe(responseTransport(403)).check("session=fixture"))
    }

    @Test
    fun `known XMU authentication redirect is expired`() {
        val health = SessionHealthProbe(responseTransport(302, location = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/auth"))
            .check("session=fixture")

        assertEquals(SessionHealth.EXPIRED, health)
    }

    @Test
    fun `non authentication redirects are unknown`() {
        val health = SessionHealthProbe(responseTransport(302, location = "https://www.xmu.edu.cn/news"))
            .check("session=fixture")

        assertEquals(SessionHealth.UNKNOWN, health)
    }

    @Test
    fun `known XMU login HTML is expired`() {
        val html = """<html><form action="https://c-identity.xmu.edu.cn/auth/realms/xmu/login-actions/authenticate"><input id="pwdEncryptSalt"></form></html>"""

        assertEquals(SessionHealth.EXPIRED, SessionHealthProbe(responseTransport(200, body = html)).check("session=fixture"))
    }

    @Test
    fun `server errors are unknown`() {
        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(responseTransport(500)).check("session=fixture"))
    }

    @Test
    fun `IO exceptions are unknown`() {
        val health = SessionHealthProbe(RecordingTransport { throw IOException("fixture timeout") }).check("session=fixture")

        assertEquals(SessionHealth.UNKNOWN, health)
    }

    @Test
    fun `unrelated HTML is unknown`() {
        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(responseTransport(200, body = "<html><form action=\"https://www.xmu.edu.cn/login\"></form></html>")).check("session=fixture"))
    }

    @Test
    fun `malformed bodies are unknown`() {
        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(responseTransport(200, body = "not-json")).check("session=fixture"))
    }

    @Test
    fun `arbitrary JSON without rollcalls is unknown not valid`() {
        // 服务端/网关错误也可能是 JSON（如 {"code":500}），不能误判为有效会话
        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(responseTransport(200, body = """{"code":500,"msg":"服务器错误"}""")).check("session=fixture"))
        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(responseTransport(200, body = "{}")).check("session=fixture"))
        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(responseTransport(200, body = """{"data":[]}""")).check("session=fixture"))
    }

    @Test
    fun `blank cookie is unknown without a transport call`() {
        val transport = responseTransport(200, body = "{}")

        assertEquals(SessionHealth.UNKNOWN, SessionHealthProbe(transport).check(""))
        assertTrue(transport.requests.isEmpty())
    }

    private fun responseTransport(
        code: Int,
        location: String? = null,
        body: String = "",
    ) = RecordingTransport { request -> response(request, code, location, body) }

    private fun response(
        request: QueryHttpRequest,
        code: Int,
        location: String? = null,
        body: String = "",
    ) = QueryHttpResponse(
        url = request.url,
        code = code,
        location = location,
        body = body,
        headers = emptyMap(),
    )

    private class RecordingTransport(
        private val handler: (QueryHttpRequest) -> QueryHttpResponse,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return handler(request)
        }
    }
}
