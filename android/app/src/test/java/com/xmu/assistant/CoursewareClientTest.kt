package com.xmu.assistant

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoursewareClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `course query uses existing endpoint cookie and parsing`() {
        val transport = RecordingTransport { request ->
            QueryHttpResponse(
                url = request.url,
                code = 200,
                location = null,
                body = """{"courses":[{"id":"course-1","name":"fixture-course","term_name":"fixture-term"}]}""",
                headers = emptyMap(),
            )
        }
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = transport,
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val courses = client.fetchCourses()

        assertEquals("https://lnt.xmu.edu.cn/api/my-courses?per_page=1000", transport.requests.single().url)
        assertEquals("session=fixture", transport.requests.single().headers["Cookie"])
        assertEquals(NetworkOperation.COURSES, transport.requests.single().operation)
        assertEquals("course-1", courses.single().id)
        assertEquals("fixture-course", courses.single().title)
        assertEquals("fixture-term", courses.single().term)
    }

    @Test
    fun `course query reports a typed main session expiration`() {
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                QueryHttpResponse(request.url, 401, null, "", emptyMap())
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        assertThrows(MainSessionExpiredException::class.java) { client.fetchCourses() }
    }

    @Test
    fun `courseware details keep order use four workers and preserve failed entry`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstWave = CountDownLatch(4)
        val activityIds = (1..8).map { "activity-$it" }
        val activitiesJson = activityIds.joinToString(",") { id ->
            """{"id":"$id","type":"file","title":"title-$id"}"""
        }
        val transport = RecordingTransport { request ->
            if (request.url.endsWith("/courseware-activities")) {
                QueryHttpResponse(
                    url = request.url,
                    code = 200,
                    location = null,
                    body = """{"activities":[$activitiesJson]}""",
                    headers = emptyMap(),
                )
            } else {
                val id = request.url.substringAfterLast("/")
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                firstWave.countDown()
                check(firstWave.await(2, TimeUnit.SECONDS)) { "four detail requests did not overlap" }
                try {
                    if (id == "activity-3") throw IOException("fixture detail failure")
                    QueryHttpResponse(
                        url = request.url,
                        code = 200,
                        location = null,
                        body = """{"title":"title-$id","type":"file","uploads":[]}""",
                        headers = emptyMap(),
                    )
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = transport,
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val items = client.fetchCourseware("course-1")

        assertEquals(8, items.size)
        assertEquals(activityIds, items.map { it.activityId })
        assertEquals(4, maximumActive.get())
        assertTrue(transport.requests.all { it.operation == NetworkOperation.COURSEWARE })
        assertTrue(items[2].failureReason.isNotBlank())
    }

    @Test
    fun `courseware detail query propagates a typed main session expiration`() {
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                if (request.url.endsWith("/courseware-activities")) {
                    QueryHttpResponse(request.url, 200, null, """{"activities":[{"id":"activity-1","type":"file"}]}""", emptyMap())
                } else {
                    QueryHttpResponse(request.url, 401, null, "", emptyMap())
                }
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        assertThrows(MainSessionExpiredException::class.java) { client.fetchCourseware("course-1") }
    }

    @Test
    fun `detail failure shuts down executor to cancel queued tasks`() {
        // 体检报告 §6-OPT2：首个详情失败（此处 401 → 会话过期）后，
        // 已入队的其余任务必须被取消，而不是继续对失效会话逐个发请求
        val recordingExecutor = RecordingExecutor(Executors.newFixedThreadPool(4))
        val activities = (1..6).joinToString(",") { index -> """{"id":"activity-$index","type":"file"}""" }
        try {
            val client = CoursewareClient(
                cookieHeader = "session=fixture",
                queryTransport = RecordingTransport { request ->
                    if (request.url.endsWith("/courseware-activities")) {
                        QueryHttpResponse(request.url, 200, null, """{"activities":[$activities]}""", emptyMap())
                    } else {
                        QueryHttpResponse(request.url, 401, null, "", emptyMap())
                    }
                },
                executorFactory = { _ -> recordingExecutor },
            )

            assertThrows(MainSessionExpiredException::class.java) { client.fetchCourseware("course-1") }
            assertTrue(recordingExecutor.shutdownNowCalled)
        } finally {
            recordingExecutor.shutdownNow()
        }
    }

    @Test
    fun `forbidden courseware detail remains a restricted entry without expiring session`() {
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                if (request.url.endsWith("/courseware-activities")) {
                    QueryHttpResponse(request.url, 200, null, """{"activities":[{"id":"activity-1","type":"file","title":"restricted"}]}""", emptyMap())
                } else {
                    QueryHttpResponse(request.url, 403, null, "", emptyMap())
                }
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val items = client.fetchCourseware("course-1")

        assertEquals(1, items.size)
        assertEquals("activity-1", items.single().activityId)
        assertTrue(items.single().failureReason.isNotBlank())
    }

    @Test
    fun `successful direct download promotes partial file and keeps existing status`() {
        val directory = temporaryFolder.newFolder("success")
        val client = downloadClient(directory) { target ->
            target.writeBytes(byteArrayOf(1, 2, 3))
            FileDownloadResult(200, "application/pdf")
        }

        val status = client.download(directItem(filename = "fixture.pdf"))

        assertEquals(COURSEWARE_STATUS_SUCCESS, status)
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(directory, "fixture.pdf").readBytes().toList())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `HTML and IO download failures keep partial bytes for resume`() {
        // 断点续传语义（原契约"失败即删"已废弃）：失败后 .part 保留供续传，
        // 但不得产生正式文件或旁路 "(2)" 文件
        val htmlDirectory = temporaryFolder.newFolder("html")
        val htmlClient = downloadClient(htmlDirectory) { target ->
            target.writeText("fixture-login-page")
            FileDownloadResult(200, "text/html; charset=utf-8")
        }
        val htmlFailure = runCatching { htmlClient.download(directItem(filename = "html.pdf")) }.exceptionOrNull()

        assertTrue(htmlFailure is IllegalStateException)
        assertTrue(htmlFailure!!.message!!.contains("网络失败"))
        assertEquals(listOf("html.pdf.part"), htmlDirectory.listFiles().orEmpty().map { it.name })

        val ioDirectory = temporaryFolder.newFolder("io")
        val ioClient = downloadClient(ioDirectory) { target ->
            target.writeText("partial")
            throw IOException("fixture write failure")
        }
        val ioFailure = runCatching { ioClient.download(directItem(filename = "io.pdf")) }.exceptionOrNull()

        assertTrue(ioFailure is IOException)
        assertEquals(listOf("io.pdf.part"), ioDirectory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `download reports a typed main session expiration on 401`() {
        val directory = temporaryFolder.newFolder("expired")
        val client = downloadClient(directory) { FileDownloadResult(401, "application/pdf") }

        assertThrows(MainSessionExpiredException::class.java) { client.download(directItem(filename = "expired.pdf")) }
        // 401 传输层不写 body：只留空 .part 占位（0 字节，下次重试等价于从头下载）
        assertEquals(
            listOf("expired.pdf.part"),
            directory.listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `download 403 is a permission failure not a session expiration`() {
        // 双端一致性 A11：CDN 防盗链/版权保护 403 不应触发 CAS 续登风暴
        // （对齐桌面端 courseware._download_url 的 PermissionError 语义）
        val directory = temporaryFolder.newFolder("forbidden")
        val client = downloadClient(directory) { FileDownloadResult(403, "application/pdf") }

        val error = assertThrows(IllegalStateException::class.java) {
            client.download(directItem(filename = "denied.pdf"))
        }
        assertTrue(error.message.orEmpty().contains("平台拒绝"))
        assertEquals(
            listOf("denied.pdf.part"),
            directory.listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `failed download keeps part and retry resumes same name`() {
        // 断点续传端到端（客户层级）：首次失败保留 .part 且不换名，
        // 重试在原名上完成下载——不得出现 "resume (2).pdf" 旁路文件
        val directory = temporaryFolder.newFolder("resume")
        var attempt = 0
        val client = downloadClient(directory) { target ->
            attempt += 1
            if (attempt == 1) {
                target.writeText("half")
                FileDownloadResult(500, "application/pdf")
            } else {
                target.writeText("final")
                FileDownloadResult(200, "application/pdf")
            }
        }
        val item = directItem(filename = "resume.pdf")

        assertThrows(IllegalStateException::class.java) { client.download(item) }
        assertTrue(File(directory, "resume.pdf.part").exists())
        assertFalse(File(directory, "resume.pdf").exists())
        assertFalse(File(directory, "resume (2).pdf").exists())

        client.download(item)

        assertEquals("final", File(directory, "resume.pdf").readText())
        assertFalse(File(directory, "resume.pdf.part").exists())
        assertEquals(2, attempt)
    }

    @Test
    fun `parallel same-name downloads reserve distinct final files`() {
        val directory = temporaryFolder.newFolder("same-name")
        val client = downloadClient(directory) { target ->
            target.writeText(Thread.currentThread().name)
            FileDownloadResult(200, "application/pdf")
        }

        boundedParallelMap(
            items = listOf(directItem("same.pdf", "one"), directItem("same.pdf", "two")),
            maxParallel = 2,
        ) { item -> client.download(item) }

        val files = directory.listFiles().orEmpty().filterNot { it.name.endsWith(".part") }
        assertEquals(2, files.size)
        assertEquals(setOf("same.pdf", "same (2).pdf"), files.map { it.name }.toSet())
        assertFalse(files.any { it.length() == 0L })
    }

    @Test
    fun `courseware activities endpoint 403 does not expire session`() {
        // 与 fetchCourses 同语义（2026-08 审查）：端点级 403 是资源/权限问题，
        // 不应带动整模块 CAS 续登；只有 401/登录页重定向才上抛触发续登。
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                QueryHttpResponse(request.url, 403, null, "", emptyMap())
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val error = assertThrows(IllegalStateException::class.java) { client.fetchCourseware("course-1") }
        assertFalse(error is MainSessionExpiredException)
    }

    @Test
    fun `download attaches cookie only to same-host urls`() {
        // 安全回归：Cookie 附带按 URL host 精确比对，不用字符串前缀——
        // lnt.xmu.edu.cn.evil.com 这类前缀伪装域不得携带会话 Cookie。
        val captured = mutableListOf<Map<String, String>>()
        val directory = temporaryFolder.newFolder("cookie-host")
        val transport = FileDownloadTransport { request, target ->
            synchronized(captured) { captured += request.headers }
            target.writeText("ok")
            FileDownloadResult(200, "application/pdf")
        }
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                error("Unexpected query request: ${request.url}")
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
            fileDownloadTransport = transport,
            downloadDirectory = directory,
        )

        client.download(
            CoursewareUiItem(
                id = "same-host", courseId = "course-1", activityId = "activity-same-host",
                title = "t", filename = "same.pdf", type = "file",
                sourceUrl = "https://lnt.xmu.edu.cn/f/same.pdf",
            ),
        )
        client.download(
            CoursewareUiItem(
                id = "prefix-spoof", courseId = "course-1", activityId = "activity-prefix-spoof",
                title = "t", filename = "spoof.pdf", type = "file",
                sourceUrl = "https://lnt.xmu.edu.cn.evil.com/f/spoof.pdf",
            ),
        )

        assertTrue(captured[0].containsKey("Cookie"))
        assertFalse(captured[1].containsKey("Cookie"))
    }

    private fun downloadClient(
        directory: File,
        handler: (File) -> FileDownloadResult,
    ) = CoursewareClient(
        cookieHeader = "session=fixture",
        queryTransport = RecordingTransport { request ->
            error("Unexpected query request: ${request.url}")
        },
        executorFactory = { size -> Executors.newFixedThreadPool(size) },
        fileDownloadTransport = FileDownloadTransport { _, target -> handler(target) },
        downloadDirectory = directory,
    )

    private fun directItem(filename: String, id: String = "fixture-item") = CoursewareUiItem(
        id = id,
        courseId = "course-1",
        activityId = "activity-$id",
        title = "fixture-title",
        filename = filename,
        type = "file",
        sourceUrl = "https://fixture.invalid/$filename",
    )

    private class RecordingTransport(
        private val handler: (QueryHttpRequest) -> QueryHttpResponse,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            return handler(request)
        }
    }

    /** 包装真实线程池，记录 shutdownNow 是否被调用（OPT-2 契约验证用）。 */
    private class RecordingExecutor(
        private val delegate: java.util.concurrent.ExecutorService,
    ) : java.util.concurrent.ExecutorService by delegate {
        var shutdownNowCalled = false

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> {
            shutdownNowCalled = true
            return delegate.shutdownNow()
        }
    }
}
