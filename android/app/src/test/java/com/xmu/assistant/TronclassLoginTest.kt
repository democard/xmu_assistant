package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TronclassLoginTest {
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
        assertEquals("session=ready", result.cookieHeader.substringAfterLast("; "))
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
