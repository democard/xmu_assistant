package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务重定向链的凭据守卫：落点离开厦大域（或降级明文）后，从该跳起不再携带
 * 会话 Cookie、也不再读回第三方种下的 Cookie；CAS 链的 ids.xmu.edu.cn 属校内
 * 域，照常携带。纯 JVM 测试（JwHttpBase 不依赖 Android 框架）。
 */
class JwHttpBaseRedirectTest {

    private class StubTransport(
        private val responses: Map<String, QueryHttpResponse>,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return responses[request.url] ?: error("no fixture for ${request.url}")
        }
    }

    private fun response(code: Int, location: String? = null, body: String = "", cookie: String? = null) =
        QueryHttpResponse(
            url = "",
            code = code,
            location = location,
            body = body,
            headers = if (cookie == null) emptyMap() else mapOf("Set-Cookie" to listOf(cookie)),
        )

    private fun follow(base: JwHttpBase, start: String): QueryHttpResponse =
        base.follow(
            url = start,
            operation = NetworkOperation.EXAM,
            referer = "https://jw.xmu.edu.cn/",
            maxRedirects = 5,
            loginTerminators = emptySet(),
            onLoginTerminus = { error("unexpected login terminus: $it") },
            onExhaustedNonLogin = { error("unexpected redirect exhaustion") },
        )

    @Test
    fun `redirect leaving xmu domains stops carrying and learning cookies`() {
        val jar = XmuScoreCookieJar().apply { seed("JWSESSION=secret") }
        val transport = StubTransport(
            mapOf(
                "https://jw.xmu.edu.cn/start" to response(302, location = "https://attacker.example/steal"),
                "https://attacker.example/steal" to response(200, body = "pwned", cookie = "EVIL=1"),
            ),
        )

        val result = follow(JwHttpBase(transport = transport, jar = jar), "https://jw.xmu.edu.cn/start")

        assertEquals("pwned", result.body)
        assertEquals("首跳（本校域）必须携带会话 Cookie", "JWSESSION=secret", transport.requests[0].headers["Cookie"])
        assertFalse(
            "跨主机跳转必须剥离会话 Cookie",
            transport.requests[1].headers.containsKey("Cookie"),
        )
        assertTrue("本校会话 Cookie 不受影响", "JWSESSION=secret" in jar.header())
        assertFalse(
            "第三方种下的 Cookie 不得进入 jar（jar 是平铺串，读回后会再发往校内）",
            "EVIL=1" in jar.header(),
        )
    }

    @Test
    fun `cas hop to ids xmu keeps carrying and learning cookies`() {
        val jar = XmuScoreCookieJar().apply { seed("JWSESSION=secret") }
        val transport = StubTransport(
            mapOf(
                "https://jw.xmu.edu.cn/login" to
                    response(302, location = "https://ids.xmu.edu.cn/authserver/login"),
                "https://ids.xmu.edu.cn/authserver/login" to response(200, cookie = "IDS=abc"),
            ),
        )

        follow(JwHttpBase(transport = transport, jar = jar), "https://jw.xmu.edu.cn/login")

        assertEquals(
            "CAS 跳转仍在厦大域，必须继续携带会话 Cookie（否则登录链断裂）",
            "JWSESSION=secret",
            transport.requests[1].headers["Cookie"],
        )
        assertTrue(
            "ids 下发的 Cookie 必须读回（后续 CAS 表单提交需要）",
            "IDS=abc" in jar.header(),
        )
    }

    @Test
    fun `https to http downgrade is treated as untrusted too`() {
        val jar = XmuScoreCookieJar().apply { seed("JWSESSION=secret") }
        val transport = StubTransport(
            mapOf(
                "https://jw.xmu.edu.cn/start" to response(302, location = "http://jw.xmu.edu.cn/fall"),
                "http://jw.xmu.edu.cn/fall" to response(200, body = "ok"),
            ),
        )

        follow(JwHttpBase(transport = transport, jar = jar), "https://jw.xmu.edu.cn/start")

        assertFalse(
            "降级明文的跳转不得携带会话 Cookie",
            transport.requests[1].headers.containsKey("Cookie"),
        )
    }
}
