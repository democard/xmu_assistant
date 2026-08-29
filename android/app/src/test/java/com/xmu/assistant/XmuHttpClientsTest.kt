package com.xmu.assistant

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class XmuHttpClientsTest {
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
    fun `default login and query transports reuse one real connection`() {
        server.enqueue(MockResponse().setBody("login-response"))
        server.enqueue(MockResponse().setBody("query-response"))

        OkHttpLoginTransport().execute(
            LoginHttpRequest(
                url = server.url("/login").toString(),
                method = "GET",
                contentType = "text/plain; charset=utf-8",
                body = "",
                cookieHeader = "",
            ),
        )
        OkHttpQueryTransport().execute(
            QueryHttpRequest(url = server.url("/query").toString()),
        )

        val first = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val second = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(0, first.sequenceNumber)
        assertEquals(1, second.sequenceNumber)
    }
}
