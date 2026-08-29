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

    @Test
    fun `short body against content length keeps part file and fails`() {
        // 服务端提前断流但连接干净收尾：Content-Length 不符不得让截断 .part 被扶正
        val short = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .setHeader("Content-Length", "20")
                .setBody(Buffer().write(short)),
        )
        // 传输层直接写调用方传入的 .part 文件（rename 扶正由调用方负责）。
        // MockWebServer 不允许头与 body 不一致，用响应拦截器改写 Content-Length
        // 模拟「声明 20 实收 8」的服务端短流；传输层第二道 Content-Length 校验
        // 兜「OkHttp 干净收尾但字节不符」的残余窗口（本用例锁定端到端失败语义）。
        val partial = File(temporaryFolder.root, "short.pdf.part")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder()
                    .header("Content-Length", "20")
                    .build()
            }
            .build()
        val transport = OkHttpFileDownloadTransport(client)
        val error = runCatching {
            transport.download(
                FileDownloadRequest(url = server.url("/file.pdf").toString(), headers = emptyMap(), operation = NetworkOperation.DOWNLOAD),
                partial,
            )
        }.exceptionOrNull()
        org.junit.Assert.assertNotNull("短流必须以失败收场（不允许截断文件被当成功）", error)
        org.junit.Assert.assertFalse("失败路径不得把截断 .part 标为成品", File(temporaryFolder.root, "short.pdf").exists())
        if (partial.exists()) {
            org.junit.Assert.assertArrayEquals(short, partial.readBytes())
        }
    }

    @Test
    fun `matching content length promotes normally`() {
        val full = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .setBody(Buffer().write(full)),
        )
        val partial = File(temporaryFolder.root, "full.pdf.part")
        val transport = OkHttpFileDownloadTransport(OkHttpClient())
        val result = transport.download(
            FileDownloadRequest(url = server.url("/full.pdf").toString(), headers = emptyMap(), operation = NetworkOperation.DOWNLOAD),
            partial,
        )
        org.junit.Assert.assertEquals(200, result.code)
        org.junit.Assert.assertArrayEquals(full, partial.readBytes())
    }

    @Test
    fun `html challenge page with 200 is never written to part file`() {
        // 体检报告 P0-2：网关/WAF 以 200 + text/html 返回挑战页时，
        // HTML 绝不能落入 .part（否则续传会拼出损坏文件）
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body>gateway challenge</body></html>"),
        )
        val target = File(temporaryFolder.root, "must-not-exist.pdf")
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        val result = transport.download(
            FileDownloadRequest(url = server.url("/signed").toString()),
            target,
        )

        assertEquals(200, result.code)
        assertEquals("text/html; charset=utf-8", result.contentType)
        assertFalse(target.exists())
    }

    @Test
    fun `json error payload with 206 leaves existing partial bytes untouched`() {
        // 断点场景下收到非文件载荷（206 + json）：不得追加、不得污染已有断点字节
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error":"challenge"}"""),
        )
        val target = File(temporaryFolder.root, "resume-guard.pdf")
        target.writeBytes("good-bytes".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        val result = transport.download(
            FileDownloadRequest(url = server.url("/file").toString()),
            target,
        )

        assertEquals(206, result.code)
        assertArrayEquals("good-bytes".toByteArray(), target.readBytes())
    }
}
