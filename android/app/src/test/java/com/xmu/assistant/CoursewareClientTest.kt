package com.xmu.assistant

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoursewareClientTest {
    @Test fun `different resource with same filename never claims another resources partial`() {
        val server = MockWebServer()
        val directory = temporaryFolder.newFolder("resource-identity")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("ETag", "\"new-resource\"")
                .setBody("resource-b-complete"),
        )
        server.start()
        try {
            val oldPartial = File(directory, "lecture.pdf.part").apply { writeText("resource-a-prefix") }
            DownloadResumeMetadata.write(oldPartial, "resource-a", DownloadEntityValidator("etag", "\"a-v1\""))
            val item = CoursewareUiItem(
                id = "upload-b", courseId = "course-1", activityId = "activity-b", title = "Lecture",
                filename = "lecture.pdf", type = "文件", sourceUrl = server.url("/lecture.pdf").toString(),
            )
            val client = CoursewareClient(
                cookieHeader = "",
                queryTransport = QueryHttpTransport { error("query transport unused") },
                executorFactory = { size -> Executors.newFixedThreadPool(size) },
                fileDownloadTransport = OkHttpFileDownloadTransport(OkHttpClient()),
                downloadDirectory = directory,
            )

            val result = client.download(item)

            assertEquals(COURSEWARE_STATUS_SUCCESS, result)
            assertEquals("resource-a-prefix", oldPartial.readText())
            assertEquals("resource-b-complete", File(directory, "lecture (2).pdf").readText())
            assertEquals(null, server.takeRequest().getHeader("Range"))
            assertTrue(DownloadResumeMetadata.sidecar(oldPartial).exists())
        } finally {
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test fun `signed URL resource forbidden does not expire account`() {
        val directory = java.nio.file.Files.createTempDirectory("forbidden-download").toFile()
        val client = CoursewareClient("session=fixture", RecordingTransport { request ->
            QueryHttpResponse(request.url, 403, null, "forbidden", emptyMap())
        }, { size -> Executors.newFixedThreadPool(size) }, downloadDirectory = directory)
        try {
            val item = CoursewareUiItem(id = "1", courseId = "c", activityId = "a", title = "file", filename = "file.pdf", type = "文件", referenceId = "r")
            try { client.download(item); org.junit.Assert.fail("expected failure") }
            catch (error: IllegalStateException) { assertFalse(error is MainSessionExpiredException) }
        } finally { directory.deleteRecursively() }
    }

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
    fun `course query treats known 200 login form as expired session`() {
        val html = """<html><form action="https://c-identity.xmu.edu.cn/auth/realms/xmu/login-actions/authenticate"><input id="pwdEncryptSalt"></form></html>"""
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                QueryHttpResponse(request.url, 200, null, html, emptyMap())
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        assertThrows(MainSessionExpiredException::class.java) { client.fetchCourses() }
    }

    @Test
    fun `course query keeps unrelated 200 HTML as network failure`() {
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = RecordingTransport { request ->
                QueryHttpResponse(request.url, 200, null, "<html><h1>Gateway error</h1></html>", emptyMap())
            },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val error = assertThrows(IllegalStateException::class.java) { client.fetchCourses() }
        assertFalse(error is MainSessionExpiredException)
        assertTrue(error.message.orEmpty().contains("网络失败"))
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
    fun `JSON and XHTML challenge responses do not promote partial`() {
        val directory = temporaryFolder.newFolder("json-challenge")
        listOf("application/json; charset=utf-8", "application/xhtml+xml").forEachIndexed { index, contentType ->
            val client = downloadClient(directory) { target ->
                target.writeText(if (index == 0) "existing-partial" else "")
                FileDownloadResult(200, contentType)
            }
            val error = assertThrows(IllegalStateException::class.java) {
                client.download(directItem(filename = "challenge-$index.pdf"))
            }
            assertTrue(error.message.orEmpty().contains("网络失败"))
            assertFalse(File(directory, "challenge-$index.pdf").exists())
            assertTrue(File(directory, "challenge-$index.pdf.part").exists())
            assertEquals(if (index == 0) "existing-partial" else "", File(directory, "challenge-$index.pdf.part").readText())
        }
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
        // The real transport writes this sidecar from the response validator; this fake only
        // covers client-side reservation and therefore seeds the equivalent resume ownership.
        DownloadResumeMetadata.write(
            File(directory, "resume.pdf.part"),
            coursewareResourceIdentity(item),
            DownloadEntityValidator("etag", "\"fixture-version\""),
        )

        client.download(item)

        assertEquals("final", File(directory, "resume.pdf").readText())
        assertFalse(File(directory, "resume.pdf.part").exists())
        assertEquals(2, attempt)
    }

    @Test
    fun `retry finds matching numbered partial after another resource owns primary`() {
        val directory = temporaryFolder.newFolder("numbered-resume")
        var attempts = 0
        val client = downloadClient(directory) { target ->
            attempts++
            if (attempts == 1) {
                target.writeText("partial-b")
                FileDownloadResult(500, "application/pdf")
            } else {
                target.writeText("complete-b")
                FileDownloadResult(200, "application/pdf")
            }
        }
        val itemA = directItem("same.pdf", "upload-a")
        val itemB = directItem("same.pdf", "upload-b")
        val primary = File(directory, "same.pdf.part").apply { writeText("partial-a") }
        DownloadResumeMetadata.write(primary, coursewareResourceIdentity(itemA), DownloadEntityValidator("etag", "\"a\""))

        assertThrows(IllegalStateException::class.java) { client.download(itemB) }
        val numberedPart = File(directory, "same (2).pdf.part")
        assertTrue(numberedPart.exists())
        DownloadResumeMetadata.write(numberedPart, coursewareResourceIdentity(itemB), DownloadEntityValidator("etag", "\"b\""))

        client.download(itemB)

        assertEquals("partial-a", primary.readText())
        assertEquals("complete-b", File(directory, "same (2).pdf").readText())
        assertFalse(File(directory, "same (3).pdf").exists())
        assertEquals(2, attempts)
    }

    @Test
    fun `active sidecar reservation prevents another legal filename from replacing it`() {
        val directory = temporaryFolder.newFolder("sidecar-collision")
        val reserved = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val client = CoursewareClient(
            cookieHeader = "",
            queryTransport = RecordingTransport { error("query transport unused") },
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
            fileDownloadTransport = FileDownloadTransport { request, target ->
                if (request.url.endsWith("/first.pdf")) {
                    reserved.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    target.writeText("first")
                } else {
                    target.writeText("second")
                }
                FileDownloadResult(200, "application/pdf")
            },
            downloadDirectory = directory,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<String> {
                client.download(directItem("same.pdf", "first").copy(sourceUrl = "https://fixture.invalid/first.pdf"))
            }
            assertTrue(reserved.await(5, TimeUnit.SECONDS))
            val second = directItem("same.pdf.part.meta", "second").copy(sourceUrl = "https://fixture.invalid/valid.pdf")
            assertEquals(COURSEWARE_STATUS_SUCCESS, client.download(second))
            val secondFile = File(directory, "same.pdf.part (2).meta")
            assertTrue(secondFile.exists())
            releaseFirst.countDown()
            assertEquals(COURSEWARE_STATUS_SUCCESS, first.get(5, TimeUnit.SECONDS))
            assertEquals("first", File(directory, "same.pdf").readText())
            assertEquals("second", secondFile.readText())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
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
    fun `download attaches cookie only to same-origin urls`() {
        // 安全回归：Cookie 附带按 URL host 精确比对，不用字符串前缀——
        // lnt.xmu.edu.cn.evil.com 这类前缀伪装域不得携带会话 Cookie。
        val captured = mutableMapOf<String, Map<String, String>>()
        val directory = temporaryFolder.newFolder("cookie-host")
        val transport = FileDownloadTransport { request, target ->
            synchronized(captured) { captured[request.url] = request.headers }
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
                id = "http-same-host", courseId = "course-1", activityId = "activity-http-same-host",
                title = "t", filename = "http.pdf", type = "file",
                sourceUrl = "http://lnt.xmu.edu.cn/f/http.pdf",
            ),
        )
        client.download(
            CoursewareUiItem(
                id = "port-same-host", courseId = "course-1", activityId = "activity-port-same-host",
                title = "t", filename = "port.pdf", type = "file",
                sourceUrl = "https://lnt.xmu.edu.cn:8443/f/port.pdf",
            ),
        )
        client.download(
            CoursewareUiItem(
                id = "explicit-default-port", courseId = "course-1", activityId = "activity-default-port",
                title = "t", filename = "default.pdf", type = "file",
                sourceUrl = "https://lnt.xmu.edu.cn:443/f/default.pdf",
            ),
        )
        client.download(
            CoursewareUiItem(
                id = "prefix-spoof", courseId = "course-1", activityId = "activity-prefix-spoof",
                title = "t", filename = "spoof.pdf", type = "file",
                sourceUrl = "https://lnt.xmu.edu.cn.evil.com/f/spoof.pdf",
            ),
        )

        assertTrue(captured.getValue("https://lnt.xmu.edu.cn/f/same.pdf").containsKey("Cookie"))
        assertFalse(captured.getValue("http://lnt.xmu.edu.cn/f/http.pdf").containsKey("Cookie"))
        assertFalse(captured.getValue("https://lnt.xmu.edu.cn:8443/f/port.pdf").containsKey("Cookie"))
        assertTrue(captured.getValue("https://lnt.xmu.edu.cn:443/f/default.pdf").containsKey("Cookie"))
        assertFalse(captured.getValue("https://lnt.xmu.edu.cn.evil.com/f/spoof.pdf").containsKey("Cookie"))
    }

    @Test
    fun `explicit json null fields fall back to secondary keys in courses and activities`() {
        val transport = RecordingTransport { request ->
            val body = when {
                request.url.contains("/api/my-courses") ->
                    """{"courses":[{"id":null,"course_id":"course-1","name":null,"title":"数学分析","academic_year":null,"term_name":"2025-2026-1","semester":{"code":null,"name":"20251"}}]}"""
                request.url.endsWith("/courseware-activities") ->
                    """{"activities":[{"id":"act-1","type":"video","title":null,"name":"第一章课件","module_name":null,"syllabus_name":"第一章"}]}"""
                request.url.endsWith("/api/activities/act-1") ->
                    """{"title":null,"name":"第一章课件","type":null,"uploads":[{"id":null,"reference_id":"ref-1","name":null,"filename":"ch1.mp4","type":null}]}"""
                else -> error("Unexpected URL: ${request.url}")
            }
            QueryHttpResponse(request.url, 200, null, body, emptyMap())
        }
        val client = CoursewareClient(
            cookieHeader = "session=fixture",
            queryTransport = transport,
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val course = client.fetchCourses().single()
        assertEquals("course-1", course.id)
        assertEquals("数学分析", course.title)
        assertEquals("2025-2026-1", course.term)
        assertEquals("20251", course.semesterCode)

        val item = client.fetchCourseware("course-1").single()
        assertEquals("act-1", item.activityId)
        assertEquals("第一章课件", item.title)
        assertEquals("ch1.mp4", item.filename)
        assertEquals("ref-1", item.referenceId)
        assertEquals("ref-1", item.resourceId)
        assertEquals(
            coursewareResourceIdentity(item),
            coursewareResourceIdentity(item.copy(id = "act-1-99-ref-1")),
        )
        val direct = item.copy(referenceId = "", resourceId = "", sourceUrl = "https://cdn.example/file.pdf?sig=one#page=1")
        val renewedDirect = direct.copy(sourceUrl = "https://cdn.example/file.pdf?sig=two#page=2")
        assertNotEquals(coursewareResourceIdentity(direct), coursewareResourceIdentity(renewedDirect))
        assertEquals(
            coursewareResourceIdentity(direct),
            coursewareResourceIdentity(direct.copy(sourceUrl = direct.sourceUrl.substringBefore('#') + "#other")),
        )
        assertEquals("视频", item.type)
        assertEquals("第一章", item.moduleName)
    }

    @Test
    fun `direct download URL parsing uses path with query fragment and spaces`() {
        assertTrue(isDirectCoursewareUrl("https://storage.example/course notes/file.pdf#page=1"))
        assertTrue(isDirectCoursewareUrl("https://storage.example/course notes/file.pdf?token=1#page=1"))
        assertFalse(isDirectCoursewareUrl("https://storage.example/view?name=file.pdf"))
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
