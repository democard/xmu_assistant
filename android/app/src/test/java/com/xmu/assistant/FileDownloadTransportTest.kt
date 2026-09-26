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
    fun `206 from zero works with a long target name`() {
        val expected = "complete".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 0-7/8")
                .setBody(Buffer().write(expected)),
        )
        val target = File(temporaryFolder.root, "a".repeat(235) + ".pdf")
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        transport.download(FileDownloadRequest(server.url("/file").toString()), target)

        assertArrayEquals(expected, target.readBytes())
        assertEquals(0, temporaryFolder.root.listFiles().orEmpty().count { it.name.endsWith(".download-chunk") })
    }

    @Test
    fun `206 with content encoding keeps the partial untouched`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Encoding", "br")
                .addHeader("Content-Range", "bytes 8-15/16")
                .setBody("compressed"),
        )
        val target = File(temporaryFolder.root, "encoded.pdf")
        target.writeBytes("part-one".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            transport.download(FileDownloadRequest(server.url("/file").toString()), target)
        }
        assertArrayEquals("part-one".toByteArray(), target.readBytes())
        assertEquals(0, temporaryFolder.root.listFiles().orEmpty().count { it.name.endsWith(".download-chunk") })
    }

    @Test
    fun `206 missing or malformed range leaves the partial untouched`() {
        listOf(null, "bytes 9-15/16", "bytes 8-15/*").forEachIndexed { index, range ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Type", "application/pdf")
                    .apply { if (range != null) addHeader("Content-Range", range) }
                    .setBody("eightbyt"),
            )
            val target = File(temporaryFolder.root, "bad-range-$index.pdf")
            target.writeBytes("part-one".toByteArray())
            val transport = OkHttpFileDownloadTransport(OkHttpClient())
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                transport.download(FileDownloadRequest(server.url("/file").toString()), target)
            }
            assertArrayEquals("part-one".toByteArray(), target.readBytes())
            assertEquals(0, temporaryFolder.root.listFiles().orEmpty().count { it.name.endsWith(".download-chunk") })
        }
    }

    @Test
    fun `206 with a range starting before the partial leaves it untouched`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 0-7/16")
                .setBody("wrong-chunk"),
        )
        val target = File(temporaryFolder.root, "range-start.pdf")
        target.writeBytes("part-one".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            transport.download(FileDownloadRequest(server.url("/file").toString()), target)
        }
        assertArrayEquals("part-one".toByteArray(), target.readBytes())
    }

    @Test
    fun `206 with a short final chunk leaves the partial untouched`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 8-15/16")
                .addHeader("Content-Length", "8")
                .setBody("short"),
        )
        val target = File(temporaryFolder.root, "range-short.pdf")
        target.writeBytes("part-one".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            transport.download(FileDownloadRequest(server.url("/file").toString()), target)
        }
        assertArrayEquals("part-one".toByteArray(), target.readBytes())
    }

    @Test
    fun `206 with a complete but non-final range leaves the partial untouched`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 8-11/16")
                .addHeader("Content-Length", "4")
                .setBody("tail"),
        )
        val target = File(temporaryFolder.root, "range-non-final.pdf")
        target.writeBytes("part-one".toByteArray())
        val transport = OkHttpFileDownloadTransport(OkHttpClient())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            transport.download(FileDownloadRequest(server.url("/file").toString()), target)
        }
        assertArrayEquals("part-one".toByteArray(), target.readBytes())
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
    fun `416 retries once from zero and replaces stale partial with validated full response`() {
        server.enqueue(MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */3"))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/pdf").setBody("new"))
        val target = File(temporaryFolder.root, "stale.pdf.part").apply { writeText("stale-prefix") }

        val result = OkHttpFileDownloadTransport(OkHttpClient()).download(
            FileDownloadRequest(server.url("/file").toString(), mapOf("Cookie" to "fixture=session")), target,
        )

        assertEquals(200, result.code)
        assertEquals("new", target.readText())
        assertEquals(2, server.requestCount)
        assertEquals("bytes=12-", server.takeRequest().getHeader("Range"))
        val restarted = server.takeRequest()
        assertEquals(null, restarted.getHeader("Range"))
        assertEquals("fixture=session", restarted.getHeader("Cookie"))
        assertEquals(listOf(target.name), temporaryFolder.root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `repeated 416 is bounded and retains the original partial`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(416)) }
        val target = File(temporaryFolder.root, "repeated.pdf.part").apply { writeText("old-prefix") }

        val result = OkHttpFileDownloadTransport(OkHttpClient()).download(
            FileDownloadRequest(server.url("/file").toString()), target,
        )

        assertEquals(416, result.code)
        assertEquals(2, server.requestCount)
        assertEquals("old-prefix", target.readText())
    }

    @Test
    fun `416 without an existing range never retries`() {
        server.enqueue(MockResponse().setResponseCode(416))
        val target = File(temporaryFolder.root, "absent.pdf.part")

        val result = OkHttpFileDownloadTransport(OkHttpClient()).download(
            FileDownloadRequest(server.url("/file").toString()), target,
        )

        assertEquals(416, result.code)
        assertEquals(1, server.requestCount)
        assertFalse(target.exists())
    }

    @Test
    fun `416 recovery error and challenge responses retain the original partial`() {
        listOf(401 to "application/json", 500 to "text/plain", 200 to "text/html").forEachIndexed { index, (code, contentType) ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Content-Type", contentType).setBody("error"))
            val target = File(temporaryFolder.root, "recovery-error-$index.pdf.part").apply { writeText("old-prefix") }

            val result = OkHttpFileDownloadTransport(OkHttpClient()).download(
                FileDownloadRequest(server.url("/file").toString()), target,
            )

            assertEquals(code, result.code)
            assertEquals("old-prefix", target.readText())
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `416 recovery validates full response length before replacing the original partial`() {
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/pdf").setBody("short"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 200) response.newBuilder().header("Content-Length", "20").build() else response
        }.build()
        val target = File(temporaryFolder.root, "recovery-short.pdf.part").apply { writeText("old-prefix") }

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            OkHttpFileDownloadTransport(client).download(FileDownloadRequest(server.url("/file").toString()), target)
        }

        assertEquals(2, server.requestCount)
        assertEquals("old-prefix", target.readText())
        assertEquals(listOf(target.name), temporaryFolder.root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `416 recovery accepts a complete 206 starting at zero`() {
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(
            MockResponse().setResponseCode(206).addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 0-2/3").setBody("new"),
        )
        val target = File(temporaryFolder.root, "recovery-206.pdf.part").apply { writeText("old-prefix") }

        val result = OkHttpFileDownloadTransport(OkHttpClient()).download(
            FileDownloadRequest(server.url("/file").toString()), target,
        )

        assertEquals(206, result.code)
        assertEquals("new", target.readText())
        assertEquals(2, server.requestCount)
        assertEquals(listOf(target.name), temporaryFolder.root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `416 recovery rejects a mismatched range without replacing the original partial`() {
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(
            MockResponse().setResponseCode(206).addHeader("Content-Type", "application/pdf")
                .addHeader("Content-Range", "bytes 1-3/4").setBody("new"),
        )
        val target = File(temporaryFolder.root, "recovery-bad-range.pdf.part").apply { writeText("old-prefix") }

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            OkHttpFileDownloadTransport(OkHttpClient()).download(
                FileDownloadRequest(server.url("/file").toString()), target,
            )
        }

        assertEquals(2, server.requestCount)
        assertEquals("old-prefix", target.readText())
        assertEquals(listOf(target.name), temporaryFolder.root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `416 recovery has one retry after the final allowed redirect`() {
        repeat(7) { index ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/redirect-$index"))
        }
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/pdf").setBody("new"))
        val target = File(temporaryFolder.root, "redirect-recovery.pdf.part").apply { writeText("old-prefix") }
        val client = OkHttpClient.Builder().followRedirects(false).build()

        val result = OkHttpFileDownloadTransport(client).download(FileDownloadRequest(server.url("/file").toString()), target)

        assertEquals(200, result.code)
        assertEquals("new", target.readText())
        assertEquals(9, server.requestCount)
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
