package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TronclassLoginTest {
    @Test fun `cookie scope respects host path secure deletion and same name variants`() {
        val jar = SimpleCookieJar()
        jar.read(mapOf("Set-Cookie" to listOf("s=ids; Path=/; Secure", "p=narrow; Path=/auth")), "https://ids.xmu.edu.cn/auth/login")
        jar.read(mapOf("Set-Cookie" to listOf("s=lnt; Path=/; Secure", "bad=foreign; Domain=evil.example")), "https://lnt.xmu.edu.cn/api/login")
        assertEquals("s=lnt", jar.header("https://lnt.xmu.edu.cn/api/profile"))
        assertEquals("p=narrow; s=ids", jar.header("https://ids.xmu.edu.cn/auth/check"))
        assertEquals("s=ids", jar.header("https://ids.xmu.edu.cn/authentic"))
        assertEquals("", jar.header("https://unrelated.example/"))
        assertEquals("", jar.header("http://lnt.xmu.edu.cn/"))
        jar.read(mapOf("Set-Cookie" to listOf("s=; Path=/; Max-Age=0")), "https://lnt.xmu.edu.cn/")
        assertEquals("", jar.header("https://lnt.xmu.edu.cn/"))
        assertEquals("s=ids", jar.header("https://ids.xmu.edu.cn/"))
    }

    @Test
    fun `login keeps the complete seven request protocol`() {
        val responses = ArrayDeque(
            listOf(
                response(location = "https://c-identity.xmu.edu.cn/identity-start", headers = cookie("initial=one")),
                response(location = "https://ids.xmu.edu.cn/authserver/login"),
                response(
                    code = 200,
                    body = """<input id="pwdEncryptSalt" value="1234567890123456"><input name="execution" value="fixture-execution">""",
                ),
                response(location = "https://ids.xmu.edu.cn/authserver/after-password"),
                response(location = "https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true&code=fixture-code"),
                response(code = 200, body = """{"access_token":"fixture-token"}"""),
                response(code = 200, headers = cookie("session=ready")),
            ),
        )
        val transport = FakeLoginHttpTransport(responses)
        val login = TronclassLogin(
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val result = login.login("student", "not-a-real-password")

        assertEquals(listOf("GET", "GET", "GET", "POST", "GET", "POST", "POST"), transport.requests.map { it.method })
        assertEquals(7, transport.requests.size)
        assertEquals("session=ready", result.cookieHeader)
        assertEquals("initial=one", transport.requests[1].cookieHeader)
        assertEquals("", transport.requests[2].cookieHeader)
        assertEquals("", transport.requests[6].cookieHeader)
        assertTrue(transport.requests[3].body.contains("username=student"))
        assertTrue(transport.requests[3].body.contains("password=fixture-encrypted-password"))
        assertFalse(transport.requests[3].body.contains("not-a-real-password"))
        assertTrue(transport.requests[5].body.contains("grant_type=authorization_code"))
        assertEquals("application/json; charset=utf-8", transport.requests[6].contentType)
    }

    private class FakeLoginHttpTransport(
        private val responses: ArrayDeque<LoginHttpResponse>,
    ) : LoginHttpTransport {
        val requests = mutableListOf<LoginHttpRequest>()

        override fun execute(request: LoginHttpRequest): LoginHttpResponse {
            requests += request
            return responses.removeFirst().copy(url = request.url)
        }
    }

    private companion object {
        fun response(
            code: Int = 302,
            location: String? = null,
            body: String = "",
            headers: Map<String, List<String>> = emptyMap(),
        ) = LoginHttpResponse(
            url = "",
            code = code,
            location = location,
            body = body,
            headers = headers,
        )

        fun cookie(value: String) = mapOf("Set-Cookie" to listOf("$value; Path=/"))
    }
}
