package com.xmu.assistant

import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LoginHttpTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `transport preserves request and redirect response semantics`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/next")
                .addHeader("Set-Cookie", "new=cookie; Path=/")
                .setBody("fixture-response"),
        )
        var operation: NetworkOperation? = null
        var timingContext: NetworkTimingContext? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                operation = chain.request().tag(NetworkOperation::class.java)
                timingContext = chain.request().tag(NetworkTimingContext::class.java)
                chain.proceed(chain.request())
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val transport = OkHttpLoginTransport(client)

        val context = NetworkTimingContext("login-fixture", NetworkOperation.LOGIN)
        val response = NetworkTimingContextScope.withContext(context) {
            transport.execute(
                LoginHttpRequest(
                    url = server.url("/login").toString(),
                    method = "POST",
                    contentType = "text/plain; charset=utf-8",
                    body = "fixture-body",
                    cookieHeader = "existing=cookie",
                    operation = NetworkOperation.LOGIN,
                ),
            )
        }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("fixture-body", recorded.body.readUtf8())
        assertEquals("existing=cookie", recorded.getHeader("Cookie"))
        assertEquals(NetworkOperation.LOGIN, operation)
        assertEquals(context, timingContext)
        assertEquals(302, response.code)
        assertEquals("/next", response.location)
        assertEquals("fixture-response", response.body)
        assertEquals(listOf("new=cookie; Path=/"), response.headers["Set-Cookie"])
    }
}
