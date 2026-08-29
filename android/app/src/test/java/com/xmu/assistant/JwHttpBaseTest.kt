package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务 HTTP 公共基座（JwHttpBase）防漂移守护（批次三）。
 *
 * 用假 transport 驱动公共 follower 的重定向链 / 终止标记命中 / 耗尽路径，
 * 并断言三类客户端共享的判定结果一致——判定条件本体只有 JwSessionMarkers
 * 这一份，测试锁死清单与分类行为，任何一侧单方面改动都会在这里红。
 */
class JwHttpBaseTest {

    private class FakeTransport : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        private val script = ArrayDeque<QueryHttpResponse>()

        fun enqueue(response: QueryHttpResponse) {
            script.addLast(response)
        }

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return script.removeFirstOrNull()
                ?: throw IllegalStateException("no scripted response for ${request.url}")
        }
    }

    private fun response(
        code: Int = 200,
        location: String? = null,
        body: String = "",
        headers: Map<String, List<String>> = emptyMap(),
    ) = QueryHttpResponse(url = "https://jw.xmu.edu.cn/", code = code, location = location, body = body, headers = headers)


    // ---- follower：重定向链 -------------------------------------------------

    @Test
    fun `follow follows absolute and relative redirects and decorates the final landing url`() {
        val transport = FakeTransport()
        transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/a/b"))
        transport.enqueue(response(code = 301, location = "/c/d?x=1"))
        transport.enqueue(response(code = 200, body = "ok"))
        val seen = mutableListOf<String>()
        val final = JwHttpBase(transport).followDecorated(
            "https://jw.xmu.edu.cn/start",
            operation = NetworkOperation.EXAM,
            referer = "https://jw.xmu.edu.cn/referer",
            maxRedirects = 8,
            loginTerminators = JwSessionMarkers.EXAM_FOLLOW_TERMINATORS,
            onLoginTerminus = { ExamSessionExpiredException() },
            onExhaustedNonLogin = { ExamResponseException("教务跳转次数过多") },
            responseDecorator = { current: String, resp: QueryHttpResponse -> seen += current; resp },
        )
        assertEquals(200, final.code)
        assertEquals("https://jw.xmu.edu.cn/c/d?x=1", seen.single())
        // 重定向之后的续跳必须降级为 GET 空体
        assertEquals("GET", transport.requests[1].method)
        assertEquals("", transport.requests[1].body)
    }

    @Test
    fun `redirect budget is honored exactly`() {
        val transport = FakeTransport()
        repeat(7) { transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/next${it}")) }
        transport.enqueue(response(code = 200, body = "final"))
        val final = JwHttpBase(transport).follow(
            "https://jw.xmu.edu.cn/start",
            operation = NetworkOperation.EXAM,
            referer = "https://jw.xmu.edu.cn/r",
            maxRedirects = 8,
            loginTerminators = JwSessionMarkers.EXAM_FOLLOW_TERMINATORS,
            onLoginTerminus = { ExamSessionExpiredException() },
            onExhaustedNonLogin = { ExamResponseException("教务跳转次数过多") },
        )
        assertEquals("final", final.body)
        assertEquals(8, transport.requests.size)
    }

    @Test
    fun `exhaustion on non login domain is a data problem`() {
        val transport = FakeTransport()
        repeat(8) { transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/loop")) }
        assertThrows(ExamResponseException::class.java) {
            JwHttpBase(transport).follow(
                "https://jw.xmu.edu.cn/start",
                operation = NetworkOperation.EXAM,
                referer = "https://jw.xmu.edu.cn/r",
                maxRedirects = 8,
                loginTerminators = JwSessionMarkers.EXAM_FOLLOW_TERMINATORS,
                onLoginTerminus = { ExamSessionExpiredException() },
                onExhaustedNonLogin = { ExamResponseException("教务跳转次数过多") },
            )
        }
    }

    @Test
    fun `exam terminators cover the explicit keycloak drift surface`() {
        // exam 终止域全集逐个命中都要判会话过期（d518e20 对齐清单 + Keycloak 显式化）
        val examDomains = listOf(
            "https://ids.xmu.edu.cn/authserver/login",
            "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/auth",
            "https://jw.xmu.edu.cn/cas/login?service=authserver/login",
            "https://jw.xmu.edu.cn/redirect?to=/auth/realms/xmu/protocol/openid-connect/auth",
        )
        examDomains.forEach { landing ->
            val transport = FakeTransport()
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/x"))
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/y"))
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/z"))
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/w"))
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/v"))
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/u"))
            transport.enqueue(response(code = 302, location = "https://jw.xmu.edu.cn/t"))
            transport.enqueue(response(code = 302, location = landing))
            assertThrows(ExamSessionExpiredException::class.java) {
                JwHttpBase(transport).follow(
                    "https://jw.xmu.edu.cn/start",
                    operation = NetworkOperation.EXAM,
                    referer = "https://jw.xmu.edu.cn/r",
                    maxRedirects = 8,
                    loginTerminators = JwSessionMarkers.EXAM_FOLLOW_TERMINATORS,
                    onLoginTerminus = { ExamSessionExpiredException() },
                    onExhaustedNonLogin = { ExamResponseException("教务跳转次数过多") },
                )
            }
        }
    }

    // ---- 判定一致性：三类客户端共享的 JwSessionMarkers -----------------------

    private fun classifierMatrix(): List<Pair<QueryHttpResponse, Boolean>> = listOf(
        response(code = 401) to false, // 状态码不在本分类器内（由各客户端薄封装补上）
        response(code = 302, location = "https://ids.xmu.edu.cn/authserver/login") to true,
        response(code = 302, location = "https://jw.xmu.edu.cn/jwapp/x") to false,
        response(body = "<!doctype html><html>pwdencryptsalt") to true,
        response(body = "<html>请先登录") to true,
        response(body = "<html>维护中 WAF") to false,
        response(body = "{\"rows\":[]}") to false,
        response(body = "  <html>未登录") to true,
    )

    @Test
    fun `exam and schedule thin wrappers judge identically on the whole matrix`() {
        // 出层前两处实现各自手写（已出现漂移史）；出层后必须同源同判。
        classifierMatrix().forEach { (resp, expected) ->
            assertEquals("matrix mismatch for ${resp.code}/${resp.body.take(24)}", expected, JwSessionMarkers.isAcademicLoginResponse(resp))
        }
    }

    @Test
    fun `isAuthFailure equals the pre-merge exam and schedule compound judgment`() {
        // 合并前 exam.isSessionExpired ≡ schedule post 判定：code==401 || code==403 || 登录页。
        // 401/403 无论响应体如何都判失效；其余状态码沿用登录页分类器结果。
        classifierMatrix().forEach { (resp, classifierExpected) ->
            val expected = resp.code == 401 || resp.code == 403 || classifierExpected
            assertEquals("auth matrix mismatch for ${resp.code}/${resp.body.take(24)}", expected, JwSessionMarkers.isAuthFailure(resp.code, resp))
        }
        // 状态码分支本体：401/403 叠加"非登录页"响应体也必须判失效（合并前行为）。
        assertTrue(JwSessionMarkers.isAuthFailure(401, response(code = 401, body = "{\"rows\":[]}")))
        assertTrue(JwSessionMarkers.isAuthFailure(403, response(code = 403, body = "{\"rows\":[]}")))
        assertFalse(JwSessionMarkers.isAuthFailure(500, response(code = 500, body = "boom")))
        assertFalse(JwSessionMarkers.isAuthFailure(200, response(code = 200, body = "{\"rows\":[]}")))
    }

    @Test
    fun `login page marker list is exactly the d518e20 alignment list`() {
        // 锁死清单本体：新增/删减特征词必须显式改这里（防悄悄漂移）
        val viaReflection = listOf("pwdencryptsalt", "authserver/login", "ids.xmu.edu.cn", "请先登录", "未登录")
        viaReflection.forEach { marker ->
            assertTrue("marker must be honored: $marker", JwSessionMarkers.isAcademicLoginResponse(response(body = "<html>$marker")))
        }
        assertEquals(
            setOf("ids.xmu.edu.cn", "authserver/login", "c-identity.xmu.edu.cn", "/auth/realms/xmu/"),
            JwSessionMarkers.EXAM_FOLLOW_TERMINATORS,
        )
        // score 现状清单（无 Keycloak）必须保持为 exam 集合的真子集
        assertTrue(JwSessionMarkers.SCORE_FOLLOW_TERMINATORS.all { it in JwSessionMarkers.EXAM_FOLLOW_TERMINATORS })
        assertTrue("/auth/realms/xmu/" in JwSessionMarkers.EXAM_FOLLOW_TERMINATORS)
        assertTrue("/auth/realms/xmu/" !in JwSessionMarkers.SCORE_FOLLOW_TERMINATORS)
    }

    // ---- request 模板 -------------------------------------------------------

    @Test
    fun `get template carries the shared headers and the client referer`() {
        val transport = FakeTransport()
        transport.enqueue(response(code = 200))
        JwHttpBase(transport).request(
            "https://jw.xmu.edu.cn/x",
            referer = "https://jw.xmu.edu.cn/referer",
            operation = NetworkOperation.EXAM,
        )
        val sent = transport.requests.single()
        assertEquals(
            linkedMapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
                "Connection" to "keep-alive",
                "Referer" to "https://jw.xmu.edu.cn/referer",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "User-Agent" to JwSessionMarkers.USER_AGENT,
            ),
            sent.headers,
        )
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", sent.contentType)
    }

    @Test
    fun `exam post variant adds xhr headers while score style stays on the document template`() {
        val examTransport = FakeTransport()
        examTransport.enqueue(response(code = 200))
        JwHttpBase(examTransport).request(
            "https://jw.xmu.edu.cn/x",
            method = "POST",
            body = "a=1",
            referer = "https://jw.xmu.edu.cn/referer",
            operation = NetworkOperation.EXAM,
            applyPostHeaders = true,
        )
        val examPost = examTransport.requests.single().headers
        assertEquals("application/json, text/plain, */*", examPost["Accept"])
        assertEquals("XMLHttpRequest", examPost["X-Requested-With"])
        assertEquals("empty", examPost["Sec-Fetch-Dest"])

        val scoreTransport = FakeTransport()
        scoreTransport.enqueue(response(code = 200))
        JwHttpBase(scoreTransport).request(
            "https://jw.xmu.edu.cn/x",
            method = "POST",
            body = "a=1",
            referer = "https://jw.xmu.edu.cn/referer",
            operation = NetworkOperation.SCORES,
        )
        val scorePost = scoreTransport.requests.single().headers
        // score 现状：POST 仍是 document 模板，无 XHR 变体（逐字保持）
        assertEquals("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", scorePost["Accept"])
        assertEquals(null, scorePost["X-Requested-With"])
    }

    @Test
    fun `jar cookies flow both ways through the shared base`() {
        val transport = FakeTransport()
        val jar = XmuScoreCookieJar().apply { seed("SESSION=x; PATH_TOKEN=y") }
        transport.enqueue(
            response(
                code = 200,
                headers = mapOf("Set-Cookie" to listOf("NEW=z; Path=/; HttpOnly")),
            ),
        )
        JwHttpBase(transport, jar).request("https://jw.xmu.edu.cn/x", referer = "r", operation = NetworkOperation.EXAM)
        assertEquals("SESSION=x; PATH_TOKEN=y", transport.requests.single().headers["Cookie"])
        assertTrue(jar.header().contains("NEW=z"))
    }

    // ---- form 编码 ----------------------------------------------------------

    @Test
    fun `form encode matches the exam and schedule wire format`() {
        val encoded = JwHttpBase(FakeTransport()).formEncode(
            linkedMapOf(
                "XNXQDM" to "2025-2026-1",
                "XM&=" to "中文 空格",
            ),
        )
        assertEquals("XNXQDM=2025-2026-1&XM%26%3D=%E4%B8%AD%E6%96%87+%E7%A9%BA%E6%A0%BC", encoded)
        // score 专有的 %2A 星号转义语义仍保留在其客户端内，不并入学基座
        assertEquals("*", JwHttpBase(FakeTransport()).encode("*"))
        assertEquals("%2A", "*".replace("*", "%2A"))
    }
}
