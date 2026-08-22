package com.xmu.assistant

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDownloadTransportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
    fun `download transport streams bytes and preserves explicit headers`() {
        val expected = byteArrayOf(0, 1, 2, 3, 127, -1)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .setBody(Buffer().write(expected)),
        )
        val target = File(temporaryFolder.root, "fixture.pdf")
        var operation: NetworkOperation? = null
        val transport = OkHttpFileDownloadTransport(
            OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    operation = chain.request().tag(NetworkOperation::class.java)
                    chain.proceed(chain.request())
                })
                .build(),
        )

        val result = transport.download(
            FileDownloadRequest(
                url = server.url("/fixture.pdf").toString(),
                headers = mapOf(
                    "Cookie" to "fixture=session",
                    "User-Agent" to "fixture-agent",
                ),
            ),
            target,
        )

        val recorded = server.takeRequest()
        assertEquals("fixture=session", recorded.getHeader("Cookie"))
        assertEquals("fixture-agent", recorded.getHeader("User-Agent"))
        assertEquals(NetworkOperation.DOWNLOAD, operation)
        assertEquals(200, result.code)
        assertEquals("application/pdf", result.contentType)
        assertArrayEquals(expected, target.readBytes())
    }

    @Test
    fun `non-success download response does not create target file`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("fixture-error"))
        val target = File(temporaryFolder.root, "must-not-exist.bin")
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        val result = transport.download(
            FileDownloadRequest(url = server.url("/failure").toString()),
            target,
        )

        assertEquals(500, result.code)
        assertFalse(target.exists())
    }

    @Test
    fun `resume appends partial bytes with 206 and sends range header`() {
        // 断点续传：.part 已有 "part-one"，206 返回剩余 "+resumed"，追加成完整内容
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 8-15/16")
                .setBody(Buffer().write("+resumed".toByteArray())),
        )
        val target = File(temporaryFolder.root, "resume.pdf")
        target.writeBytes("part-one".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        val result = transport.download(
            FileDownloadRequest(url = server.url("/file").toString()),
            target,
        )

        assertEquals(206, result.code)
        assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
        assertArrayEquals("part-one+resumed".toByteArray(), target.readBytes())
    }

    @Test
    fun `server ignoring range returns 200 and overwrites instead of appending`() {
        // 服务端忽略 Range 返回 200 全量：必须覆盖重写，不得把旧 .part 字节重复拼接
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .setBody(Buffer().write("full-content".toByteArray())),
        )
        val target = File(temporaryFolder.root, "overwrite.pdf")
        target.writeBytes("stale-bytes".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        val result = transport.download(
            FileDownloadRequest(url = server.url("/file").toString()),
            target,
        )

        assertEquals(200, result.code)
        assertEquals("bytes=11-", server.takeRequest().getHeader("Range"))
        assertArrayEquals("full-content".toByteArray(), target.readBytes())
    }

    @Test
    fun `cross-host redirect strips cookie header`() {
        // 签名地址 302 → 第三方 CDN：跨主机跳转必须剥离 Cookie（会话凭据不外泄）
        val cdn = MockWebServer()
        cdn.start()
        try {
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("cdn-data"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/file").toString()))
            val target = File(temporaryFolder.root, "redirect.pdf")
            // 与生产 XmuHttpClients.download 一致：关闭自动重定向（手动逐跳跟随）
            val transport = OkHttpFileDownloadTransport(
                OkHttpClient.Builder().followRedirects(false).build(),
            )

            val result = transport.download(
                FileDownloadRequest(
                    url = server.url("/signed").toString(),
                    headers = mapOf("Cookie" to "jw=secret"),
                ),
                target,
            )

            assertEquals(200, result.code)
            // 原站请求带 Cookie
            assertEquals("jw=secret", server.takeRequest().getHeader("Cookie"))
            // 跨主机跳转后 Cookie 被剥离
            assertEquals(null, cdn.takeRequest().getHeader("Cookie"))
            assertEquals("cdn-data", target.readText())
        } finally {
            cdn.shutdown()
        }
    }

    @Test
    fun `same-host redirect keeps cookie header`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val target = File(temporaryFolder.root, "samehost.bin")
        val transport = OkHttpFileDownloadTransport(
            OkHttpClient.Builder().followRedirects(false).build(),
        )

        val result = transport.download(
            FileDownloadRequest(
                url = server.url("/start").toString(),
                headers = mapOf("Cookie" to "jw=secret"),
            ),
            target,
        )

        assertEquals(200, result.code)
        assertEquals("jw=secret", server.takeRequest().getHeader("Cookie"))
        assertEquals("jw=secret", server.takeRequest().getHeader("Cookie"))
        assertEquals("ok", target.readText())
    }
}
