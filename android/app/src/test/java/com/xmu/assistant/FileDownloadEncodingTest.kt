package com.xmu.assistant

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDownloadEncodingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun encoded(bytes: ByteArray, encoding: String = "gzip") = MockResponse()
        .addHeader("Content-Type", "application/pdf")
        .addHeader("Content-Encoding", encoding)
        .setBody(Buffer().write(bytes))

    @Test fun `gzip full response after ignored range restarts without range and saves decoded file`() {
        val expected = "%PDF synthetic complete contents".toByteArray()
        repeat(2) { server.enqueue(encoded(gzip(expected))) }
        val target = File(temporaryFolder.root, "file.pdf.part").apply { writeText("old-prefix") }

        val result = OkHttpFileDownloadTransport(OkHttpClient()).download(
            FileDownloadRequest(server.url("/file").toString()), target,
        )

        assertEquals(200, result.code)
        assertArrayEquals(expected, target.readBytes())
        assertEquals(2, server.requestCount)
        assertEquals("bytes=10-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun `failed compressed restart keeps original partial and removes temporary files`() {
        server.enqueue(encoded(gzip("new file".toByteArray())))
        server.enqueue(encoded("not gzip".toByteArray()))
        val target = File(temporaryFolder.root, "file.pdf.part").apply { writeText("old-prefix") }

        assertThrows(Exception::class.java) {
            OkHttpFileDownloadTransport(OkHttpClient()).download(
                FileDownloadRequest(server.url("/file").toString()), target,
            )
        }

        assertEquals(2, server.requestCount)
        assertEquals("old-prefix", target.readText())
        assertEquals(listOf(target.name), temporaryFolder.root.listFiles()!!.map { it.name })
    }

    @Test fun `unsupported content encoding is never saved as a successful file`() {
        server.enqueue(encoded("encoded bytes".toByteArray(), "br"))
        val target = File(temporaryFolder.root, "file.pdf.part")

        assertThrows(IllegalStateException::class.java) {
            OkHttpFileDownloadTransport(OkHttpClient()).download(
                FileDownloadRequest(server.url("/file").toString()), target,
            )
        }

        assertEquals(1, server.requestCount)
        assertFalse(target.exists())
    }

    @Test fun `fresh gzip download preserves automatic decoding`() {
        val expected = "fresh synthetic document".toByteArray()
        server.enqueue(encoded(gzip(expected)))
        val target = File(temporaryFolder.root, "fresh.pdf.part")

        OkHttpFileDownloadTransport(OkHttpClient()).download(
            FileDownloadRequest(server.url("/file").toString()), target,
        )

        assertArrayEquals(expected, target.readBytes())
        assertEquals(1, server.requestCount)
    }
}
