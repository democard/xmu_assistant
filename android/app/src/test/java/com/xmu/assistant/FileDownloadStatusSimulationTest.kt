package com.xmu.assistant

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDownloadStatusSimulationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val nonFileCodes = listOf(201, 202, 203, 204, 205, 207, 226, 304)
    private val client = OkHttpClient.Builder().followRedirects(false)
        .callTimeout(3, TimeUnit.SECONDS).build()

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun response(code: Int) = MockResponse().setResponseCode(code)
        .addHeader("Content-Type", "application/octet-stream")

    @Test fun `non file status never creates a new partial`() {
        for (code in nonFileCodes) {
            server.enqueue(response(code))
            val partial = File(temporary.root, "fresh-$code.pdf.part")
            val result = OkHttpFileDownloadTransport(client).download(
                FileDownloadRequest(server.url("/fresh.pdf").toString()), partial,
            )
            assertEquals(code, result.code)
            assertFalse("HTTP $code must not create a file", partial.exists())
        }
        assertEquals(nonFileCodes.size, server.requestCount)
    }

    @Test fun `non file status preserves existing resume bytes`() {
        for (code in nonFileCodes) {
            server.enqueue(response(code))
            val partial = File(temporary.root, "resume-$code.pdf.part").apply { writeText("valid-prefix") }
            val result = OkHttpFileDownloadTransport(client).download(
                FileDownloadRequest(server.url("/resume.pdf").toString()), partial,
            )
            assertEquals(code, result.code)
            assertEquals("HTTP $code must retain existing bytes", "valid-prefix", partial.readText())
            assertEquals("bytes=12-", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun `416 fallback non file status preserves original without further retry`() {
        for (code in nonFileCodes) {
            server.enqueue(response(416))
            server.enqueue(response(code))
            val partial = File(temporary.root, "fallback-$code.pdf.part").apply { writeText("valid-prefix") }
            val result = OkHttpFileDownloadTransport(client).download(
                FileDownloadRequest(server.url("/fallback.pdf").toString()), partial,
            )
            assertEquals(code, result.code)
            assertEquals("HTTP $code must retain invalid-range prefix for recovery", "valid-prefix", partial.readText())
            assertEquals("bytes=12-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
        assertEquals(nonFileCodes.size * 2, server.requestCount)
        assertFalse(temporary.root.listFiles().orEmpty().any { it.name.endsWith(".download-chunk") })
    }

    @Test fun `courseware caller rejects non file status without promoting reserved partial`() {
        for (code in nonFileCodes) {
            val directory = temporary.newFolder("caller-$code")
            val partial = File(directory, "fixture.pdf.part").apply { writeText("valid-prefix") }
            val courseware = CoursewareClient(
                cookieHeader = "fixture=session", downloadDirectory = directory,
                queryTransport = QueryHttpTransport { error("unexpected query") },
                executorFactory = { error("unexpected executor") },
                fileDownloadTransport = FileDownloadTransport { _, _ -> FileDownloadResult(code, "application/octet-stream") },
            )
            val item = CoursewareUiItem(id = "fixture", courseId = "course", activityId = "activity",
                title = "fixture", filename = "fixture.pdf", type = "文件", sourceUrl = "https://cdn.example.test/fixture.pdf")
            assertThrows("HTTP $code must report failure", IllegalStateException::class.java) { courseware.download(item) }
            assertFalse(File(directory, "fixture.pdf").exists())
            assertEquals("valid-prefix", partial.readText())
        }
    }

    @Test fun `valid zero byte 200 remains a successful empty download`() {
        server.enqueue(response(200).setBody(""))
        val directory = temporary.newFolder("empty-valid")
        val courseware = CoursewareClient(cookieHeader = "", downloadDirectory = directory,
            queryTransport = QueryHttpTransport { error("unexpected query") },
            executorFactory = { error("unexpected executor") },
            fileDownloadTransport = OkHttpFileDownloadTransport(client))
        val item = CoursewareUiItem(id = "empty", courseId = "course", activityId = "activity",
            title = "empty", filename = "empty.pdf", type = "文件", sourceUrl = server.url("/empty.pdf").toString())
        assertEquals(COURSEWARE_STATUS_SUCCESS, courseware.download(item))
        val file = File(directory, "empty.pdf")
        assertTrue(file.isFile)
        assertEquals(0L, file.length())
    }
}
