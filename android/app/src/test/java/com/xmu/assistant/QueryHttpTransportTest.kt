package com.xmu.assistant

import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class QueryHttpTransportTest {
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
    fun `transport preserves explicit request and redirect response`() {
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
        val transport = OkHttpQueryTransport(client)

        val context = NetworkTimingContext("query-fixture", NetworkOperation.SCORES)
        val response = NetworkTimingContextScope.withContext(context) {
            transport.execute(
                QueryHttpRequest(
                    url = server.url("/query").toString(),
                    method = "POST",
                    headers = mapOf(
                        "Cookie" to "fixture=cookie",
                        "Accept" to "application/json",
                    ),
                    contentType = "application/x-www-form-urlencoded; charset=utf-8",
                    body = "fixture=body",
                    operation = NetworkOperation.SCORES,
                ),
            )
        }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("fixture=body", recorded.body.readUtf8())
        assertEquals("fixture=cookie", recorded.getHeader("Cookie"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertEquals(NetworkOperation.SCORES, operation)
        assertEquals(context, timingContext)
        assertEquals(302, response.code)
        assertEquals("/next", response.location)
        assertEquals("fixture-response", response.body)
        assertEquals(listOf("new=cookie; Path=/"), response.headers["Set-Cookie"])
    }

    @Test
    fun `transport sends a zero length body for an empty post`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val transport = OkHttpQueryTransport(
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

        transport.execute(
            QueryHttpRequest(
                url = server.url("/empty-post").toString(),
                method = "POST",
                contentType = "application/x-www-form-urlencoded; charset=utf-8",
                body = "",
                operation = NetworkOperation.SCHEDULE,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(0L, recorded.bodySize)
    }
}
