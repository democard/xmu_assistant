package com.xmu.assistant

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XmuScoreAutoQueryClientTest {
    @Before
    fun resetLoginCooldown() {
        // 登录冷却是进程级状态（companion），JVM 单测用例间共享，必须重置防污染
        XmuScoreAutoQueryClient.clearLoginBlockedForTests()
    }

    @Test
    fun `warmup probe validates session with single request and never logs in`() {
        val transport = FakeQueryTransport(
            ArrayDeque(
                listOf(
                    response(body = """{"datas":{"cxycjdxnxq":{"rows":[{"XNXQDM":"20261","XNXQDM_DISPLAY":"26-27 1"}]}}}"""),
                ),
            ),
        )
        val client = scoreClient(transport, cookieHeader = "jw=fixture", mayRelogin = { false })

        assertTrue(client.probeAcademicSession())

        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.all { it.method == "POST" })
    }

    @Test
    fun `warmup probe on expired session reports invalid without cas login`() {
        // 会话过期 + mayRelogin=false：探测返回无效，绝不触发 CAS 认证链（风控红线）
        val transport = FakeQueryTransport(
            ArrayDeque(listOf(response(code = 401, body = "expired"))),
        )
        val client = scoreClient(transport, cookieHeader = "jw=fixture", mayRelogin = { false })

        assertFalse(client.probeAcademicSession())

        assertTrue(transport.requests.all { !it.url.contains("ids.xmu.edu.cn") && !it.url.contains("authserver/login") })
    }

    @Test
    fun `login page without salt maps to blocked and never submits`() {
        // 冻结/限流形态：登录页无 pwdEncryptSalt（提示页）→ 类型化 blocked，绝不发登录提交
        val transport = FakeQueryTransport(
            ArrayDeque(
                listOf(
                    response(body = "<html><body>登录请求过于频繁</body></html>"),
                ),
            ),
        )
        val client = scoreClient(transport, cookieHeader = "")

        val error = runCatching { client.fetchScores() }.exceptionOrNull()

        assertTrue(error is AcademicLoginBlockedException)
        assertTrue(transport.requests.all { it.method == "GET" })
    }

    @Test
    fun `login landing after submit sets cooldown and blocks`() {
        // 登录页有表单但提交后仍回统一登录页（认证未通过/风控拦截）→ blocked + 冷却
        val transport = FakeQueryTransport(
            ArrayDeque(
                listOf(
                    response(
                        body = """
                            <input id="pwdEncryptSalt" value="1234567890123456">
                            <input name="lt" value="fixture-lt">
                            <input name="execution" value="fixture-execution">
                        """.trimIndent(),
                        headers = cookie("ids=one"),
                    ),
                    response(
                        code = 302,
                        location = "https://ids.xmu.edu.cn/authserver/login?service=x",
                        headers = cookie("auth=try"),
                    ),
                    response(
                        body = "<html><form action='/authserver/login'><input id='pwdEncryptSalt' value='x'></form></html>",
                    ),
                ),
            ),
        )
        val client = scoreClient(transport, cookieHeader = "")

        val error = runCatching { client.fetchScores() }.exceptionOrNull()

        assertTrue(error is AcademicLoginBlockedException)
        assertEquals(3, transport.requests.size) // GET 登录页 + POST + GET 回登录页，不继续
    }

    @Test
    fun `blocked cooldown prevents further login attempts without network`() {
        // 冷却窗口内：任何登录尝试直接 blocked，零网络请求（不加重冻结）
        val blockedTransport = FakeQueryTransport(
            ArrayDeque(listOf(response(body = "<html>blocked</html>"))),
        )
        runCatching { scoreClient(blockedTransport, cookieHeader = "").fetchScores() }

        val quietTransport = FakeQueryTransport(ArrayDeque())
        val error = runCatching { scoreClient(quietTransport, cookieHeader = "").fetchScores() }.exceptionOrNull()

        assertTrue(error is AcademicLoginBlockedException)
        assertTrue("cooldown must not touch the network", quietTransport.requests.isEmpty())
    }
    @Test
    fun `grade query preserves login redirects forms cookies and score parsing`() {
        val transport = FakeQueryTransport(
            ArrayDeque(
                listOf(
                    response(
                        body = """
                            <input id="pwdEncryptSalt" value="1234567890123456">
                            <input name="lt" value="fixture-lt">
                            <input name="dllt" value="fixture-dllt">
                            <input name="execution" value="fixture-execution">
                        """.trimIndent(),
                        headers = cookie("ids=one"),
                    ),
                    response(
                        code = 302,
                        location = "https://jw.xmu.edu.cn/login?ticket=fixture-ticket",
                        headers = cookie("auth=ready"),
                    ),
                    response(headers = cookie("jw=ready")),
                    response(),
                    response(
                        body = """{"datas":{"cxycjdxnxq":{"rows":[{"XNXQDM":"2025-1","XNXQDM_DISPLAY":"fixture-term"}]}}}""",
                    ),
                    response(
                        body = """{"datas":{"xscjcx":{"rows":[{"KCH":"FIX101","KCM":"fixture-course","XF":"2.0","ZCJ":"88","XFJD":"3.8","DJCJLXDM_DISPLAY":"百分制"}]}}}""",
                    ),
                ),
            ),
        )
        val client = XmuScoreAutoQueryClient(
            username = "fixture-student",
            password = "not-a-real-password",
            cookieHeader = "",
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val records = client.fetchScores().records

        assertEquals(listOf("GET", "POST", "GET", "GET", "POST", "POST"), transport.requests.map { it.method })
        assertTrue(transport.requests.all { it.operation == NetworkOperation.SCORES })
        assertTrue(transport.requests[0].url.startsWith("https://ids.xmu.edu.cn/authserver/login"))
        assertTrue(transport.requests[1].body.contains("username=fixture-student"))
        assertTrue(transport.requests[1].body.contains("password=fixture-encrypted-password"))
        assertFalse(transport.requests[1].body.contains("not-a-real-password"))
        assertEquals("ids=one", transport.requests[1].headers["Cookie"])
        assertTrue(transport.requests[4].body.contains("XH=fixture-student"))
        assertTrue(transport.requests[5].body.contains("querySetting="))
        assertEquals(1, records.size)
        assertEquals("FIX101", records.single().courseCode)
        assertEquals("fixture-course", records.single().courseName)
        assertEquals("fixture-term", records.single().term)
        assertEquals(88.0, records.single().score)
        assertEquals(3.8, records.single().gradePoint)
    }

    @Test
    fun `valid saved grade session skips identity login`() {
        val transport = SessionScenarioTransport(
            terms = listOf("T1"),
            savedSessionValid = true,
        )
        val client = scoreClient(transport, cookieHeader = "saved=session")

        val records = client.fetchScores().records

        assertEquals(listOf("T1"), records.map { it.courseCode })
        assertTrue(transport.requests.none { "ids.xmu.edu.cn" in it.url })
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `schedule session opens the dedicated timetable application`() {
        val transport = SessionScenarioTransport(
            terms = listOf("T1"),
            savedSessionValid = true,
        )
        val client = scoreClient(transport, cookieHeader = "saved=session")

        client.ensureScheduleSession()

        val appRequest = transport.requests.single { "/appShow?appId=" in it.url }
        assertEquals("https://jw.xmu.edu.cn/appShow?appId=4979568947762216", appRequest.url)
        assertEquals(NetworkOperation.SCHEDULE, appRequest.operation)
        // 提速：会话有效时不再请求成绩学期列表（与课表无关的浪费请求）
        assertTrue(
            "课表会话建立不应请求成绩学期列表: ${transport.requests.map { it.url }}",
            transport.requests.none { it.url.contains("cxycjdxnxq.do") },
        )
        assertEquals(0, transport.identityLoginCount.get()) // 有效会话不触发身份登录
    }

    @Test
    fun `expired schedule session falls back to one login and opens the app`() {
        val transport = SessionScenarioTransport(
            terms = listOf("T1"),
            savedSessionValid = false,
        )
        val client = scoreClient(transport, cookieHeader = "expired=session")

        // 会话已过期：appShow 会先落到登录页，触发一次续登后再打开课表应用
        client.ensureScheduleSession()

        assertEquals(1, transport.identityLoginCount.get())
        val appRequests = transport.requests.filter { "/appShow?appId=" in it.url }
        assertTrue("过期会话应重试打开课表应用: ${appRequests.size}", appRequests.size >= 1)
    }

    @Test
    fun `expired saved grade session falls back to one existing login flow`() {
        val transport = SessionScenarioTransport(
            terms = listOf("T1"),
            savedSessionValid = false,
        )
        val client = scoreClient(transport, cookieHeader = "expired=session")

        val records = client.fetchScores().records

        assertEquals("T1", records.single().courseCode)
        assertEquals(1, transport.identityLoginCount.get())
    }

    @Test
    fun `stale epoch never triggers a login after the session moved on`() {
        // 用户登出后，陈旧请求到达时不允许自动续登（mayRelogin=false），
        // 即使会话已过期也不应发起任何 CAS 身份登录。
        val transport = SessionScenarioTransport(
            terms = listOf("T1"),
            savedSessionValid = false,
        )
        val client = scoreClient(
            transport,
            cookieHeader = "expired=session",
            mayRelogin = { false },
        )

        val failure = runCatching { client.fetchScores() }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(0, transport.identityLoginCount.get())
        assertTrue(transport.requests.none { "ids.xmu.edu.cn" in it.url })
    }

    @Test
    fun `stale epoch keeps failed term rows as failures without relogin`() {
        // 部分成绩项失败时，若会话世代已变化（mayRelogin=false），
        // 不再重登重试：失败项保持失败、不发起任何身份登录，成功项照常返回。
        val transport = SessionScenarioTransport(
            terms = listOf("T1", "T2", "T3"),
            savedSessionValid = true,
            firstWaveFailures = setOf("T1", "T2"),
        )
        val client = scoreClient(
            transport,
            cookieHeader = "saved=session",
            mayRelogin = { false },
        )

        val records = client.fetchScores().records

        assertEquals(listOf("T3"), records.map { it.courseCode })
        assertEquals(0, transport.identityLoginCount.get())
        // 失败项没有被重试：三个学期各只请求了一次
        assertEquals(1, transport.rowAttempts.getValue("T1").get())
        assertEquals(1, transport.rowAttempts.getValue("T2").get())
        assertEquals(1, transport.rowAttempts.getValue("T3").get())
    }

    @Test
    fun `server 5xx is format failure and never triggers relogin`() {
        // 风控红线：5xx/服务端坏页绝不能判会话过期触发 CAS 续登（只有 401/403/登录页才算）
        var authRequests = 0
        val transport = QueryHttpTransport { request ->
            if (request.url.contains("authserver")) authRequests++
            when {
                request.url.contains("authserver/login") ->
                    QueryHttpResponse(request.url, 200, null, """<input id="pwdEncryptSalt" value="x">""", emptyMap())
                request.url.endsWith("/cxycjdxnxq.do") ->
                    QueryHttpResponse(
                        request.url, 200, null,
                        """{"datas":{"cxycjdxnxq":{"rows":[{"XNXQDM":"T1","XNXQDM_DISPLAY":"T1"}]}}}""",
                        emptyMap(),
                    )
                else ->
                    QueryHttpResponse(request.url, 500, null, "server busy", emptyMap())
            }
        }
        val client = XmuScoreAutoQueryClient(
            username = "fixture-student",
            password = "not-a-real-password",
            cookieHeader = "saved=session",
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
            mayRelogin = { true },
        )

        val failure = runCatching { client.fetchScores() }.exceptionOrNull()

        assertTrue(failure is ScoreJsonFormatException)
        // 未发起任何身份域登录请求
        assertEquals(0, authRequests)
    }

    @Test
    fun `existing session only score query never reauthenticates blank or expired sessions`() {
        val validTransport = SessionScenarioTransport(terms = listOf("T1"), savedSessionValid = true)
        val validRecords = XmuScoreAutoQueryClient
            .existingSessionOnly("saved=session", validTransport)
            .fetchScoresFromExistingSession()
        assertEquals(listOf("T1"), validRecords.map { it.courseCode })
        assertEquals(0, validTransport.identityLoginCount.get())

        val blankTransport = SessionScenarioTransport(terms = listOf("T1"), savedSessionValid = false)
        val blankFailure = runCatching {
            XmuScoreAutoQueryClient.existingSessionOnly("", blankTransport).fetchScoresFromExistingSession()
        }.exceptionOrNull()
        assertTrue(blankFailure is ExistingScoreSessionUnavailable)
        assertTrue(blankTransport.requests.isEmpty())
        assertEquals(0, blankTransport.identityLoginCount.get())

        val expiredTransport = SessionScenarioTransport(terms = listOf("T1"), savedSessionValid = false)
        val expiredFailure = runCatching {
            XmuScoreAutoQueryClient.existingSessionOnly("expired=session", expiredTransport).fetchScoresFromExistingSession()
        }.exceptionOrNull()
        assertTrue(expiredFailure is ExistingScoreSessionUnavailable)
        assertTrue(expiredTransport.requests.none { "ids.xmu.edu.cn" in it.url && it.method == "POST" })
        assertEquals(0, expiredTransport.identityLoginCount.get())
    }

    @Test
    fun `grade terms use at most five workers and keep term order`() {
        val transport = SessionScenarioTransport(
            terms = (1..6).map { "T$it" },
            savedSessionValid = true,
            rowBarrierSize = 5,
        )
        val client = scoreClient(
            transport = transport,
            cookieHeader = "saved=session",
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val records = client.fetchScores().records

        assertEquals(5, transport.maximumActiveRows.get())
        assertEquals((1..6).map { "T$it" }, records.map { it.courseCode })
    }

    @Test
    fun `failed grade term wave performs one login and retries every failed term`() {
        val transport = SessionScenarioTransport(
            terms = listOf("T1", "T2", "T3"),
            savedSessionValid = true,
            firstWaveFailures = setOf("T1", "T2"),
        )
        val client = scoreClient(
            transport = transport,
            cookieHeader = "saved=session",
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val records = client.fetchScores().records

        assertEquals(listOf("T1", "T2", "T3"), records.map { it.courseCode })
        assertEquals(1, transport.identityLoginCount.get())
        assertEquals(2, transport.rowAttempts.getValue("T1").get())
        assertEquals(2, transport.rowAttempts.getValue("T2").get())
        assertEquals(1, transport.rowAttempts.getValue("T3").get())
    }

    @Test
    fun `network jitter on some terms never triggers a login`() {
        // 弱网抖动：某学期成绩行请求抛 IOException（非会话过期），
        // 不得因此触发 CAS 身份登录（风控红线）；失败项保持失败，成功项照常返回。
        val transport = SessionScenarioTransport(
            terms = listOf("T1", "T2", "T3"),
            savedSessionValid = true,
            firstWaveNetworkFailures = setOf("T2"),
        )
        val client = scoreClient(
            transport = transport,
            cookieHeader = "saved=session",
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val records = client.fetchScores().records

        // T2 网络失败不重试不登录；T1/T3 正常返回
        assertEquals(listOf("T1", "T3"), records.map { it.courseCode })
        assertEquals(0, transport.identityLoginCount.get())
        assertEquals(1, transport.rowAttempts.getValue("T1").get())
        assertEquals(1, transport.rowAttempts.getValue("T2").get())
        assertEquals(1, transport.rowAttempts.getValue("T3").get())
    }

    @Test
    fun `format errors on some terms never trigger a login`() {
        // 服务端数据问题：某学期返回无法解析的 HTML 错误页（ScoreJsonFormatException，非会话失效），
        // 不得因此触发 CAS 身份登录（风控红线）；重登也修不好数据问题，失败项保持失败。
        val transport = SessionScenarioTransport(
            terms = listOf("T1", "T2", "T3"),
            savedSessionValid = true,
            firstWaveFormatFailures = setOf("T2"),
        )
        val client = scoreClient(
            transport = transport,
            cookieHeader = "saved=session",
            executorFactory = { size -> Executors.newFixedThreadPool(size) },
        )

        val records = client.fetchScores().records

        // T2 格式异常不重试不登录；T1/T3 正常返回
        assertEquals(listOf("T1", "T3"), records.map { it.courseCode })
        assertEquals(0, transport.identityLoginCount.get())
        assertEquals(1, transport.rowAttempts.getValue("T1").get())
        assertEquals(1, transport.rowAttempts.getValue("T2").get())
        assertEquals(1, transport.rowAttempts.getValue("T3").get())
    }

    private fun scoreClient(
        transport: QueryHttpTransport,
        cookieHeader: String,
        executorFactory: (Int) -> java.util.concurrent.ExecutorService = { size -> Executors.newFixedThreadPool(size) },
        mayRelogin: () -> Boolean = { true },
    ) = XmuScoreAutoQueryClient(
        username = "fixture-student",
        password = "not-a-real-password",
        cookieHeader = cookieHeader,
        transport = transport,
        base64Encoder = { "fixture-encrypted-password" },
        executorFactory = executorFactory,
        mayRelogin = mayRelogin,
    )

    private class FakeQueryTransport(
        private val responses: ArrayDeque<QueryHttpResponse>,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return responses.removeFirst().copy(url = request.url)
        }
    }

    private class SessionScenarioTransport(
        private val terms: List<String>,
        savedSessionValid: Boolean,
        private val firstWaveFailures: Set<String> = emptySet(),
        private val firstWaveNetworkFailures: Set<String> = emptySet(),
        private val firstWaveFormatFailures: Set<String> = emptySet(),
        rowBarrierSize: Int = 0,
    ) : QueryHttpTransport {
        val requests = java.util.Collections.synchronizedList(mutableListOf<QueryHttpRequest>())
        val identityLoginCount = AtomicInteger(0)
        val maximumActiveRows = AtomicInteger(0)
        val rowAttempts = ConcurrentHashMap<String, AtomicInteger>()
        private val activeRows = AtomicInteger(0)
        private val firstRowWave = rowBarrierSize.takeIf { it > 0 }?.let(::CountDownLatch)
        @Volatile private var academicAuthenticated = savedSessionValid

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            return when {
                request.url.startsWith("https://ids.xmu.edu.cn/authserver/login") && request.method == "GET" -> {
                    identityLoginCount.incrementAndGet()
                    response(
                        body = """
                            <input id="pwdEncryptSalt" value="1234567890123456">
                            <input name="lt" value="fixture-lt">
                            <input name="dllt" value="fixture-dllt">
                            <input name="execution" value="fixture-execution">
                        """.trimIndent(),
                        headers = cookie("ids=one"),
                    ).copy(url = request.url)
                }
                request.url.startsWith("https://ids.xmu.edu.cn/authserver/login") && request.method == "POST" ->
                    response(
                        code = 302,
                        location = "https://jw.xmu.edu.cn/login?ticket=fixture-ticket",
                        headers = cookie("auth=ready"),
                    ).copy(url = request.url)
                request.url.startsWith("https://jw.xmu.edu.cn/login?ticket=") -> {
                    academicAuthenticated = true
                    response(headers = cookie("jw=ready")).copy(url = request.url)
                }
                request.url.startsWith("https://jw.xmu.edu.cn/appShow") -> {
                    if (!academicAuthenticated) {
                        // 会话过期时 appShow 返回登录页（真实行为：重定向到统一登录）
                        response(
                            body = """<html><form action="/authserver/login"><input id="pwdEncryptSalt" value="1234567890123456"></form></html>""",
                        ).copy(url = request.url)
                    } else {
                        academicAuthenticated = true
                        response().copy(url = request.url)
                    }
                }
                request.url.contains("cxycjdxnxq.do") -> {
                    if (!academicAuthenticated) {
                        response(code = 401).copy(url = request.url)
                    } else {
                        val rows = terms.joinToString(",") { term ->
                            """{"XNXQDM":"$term","XNXQDM_DISPLAY":"$term"}"""
                        }
                        response(body = """{"datas":{"cxycjdxnxq":{"rows":[$rows]}}}""").copy(url = request.url)
                    }
                }
                request.url.contains("xscjcx.do") -> rowResponse(request)
                else -> error("Unexpected fixture request: ${request.method} ${request.url}")
            }
        }

        private fun rowResponse(request: QueryHttpRequest): QueryHttpResponse {
            val term = terms.firstOrNull { it in request.body }
                ?: error("Missing fixture term in row request")
            val attempt = rowAttempts.computeIfAbsent(term) { AtomicInteger(0) }.incrementAndGet()
            val current = activeRows.incrementAndGet()
            maximumActiveRows.updateAndGet { previous -> maxOf(previous, current) }
            return try {
                firstRowWave?.let { barrier ->
                    barrier.countDown()
                    check(barrier.await(2, TimeUnit.SECONDS)) { "grade row requests did not overlap" }
                }
                if (attempt == 1 && identityLoginCount.get() == 0 && term in firstWaveFailures) {
                    response(code = 401).copy(url = request.url)
                } else if (term in firstWaveNetworkFailures) {
                    throw java.io.IOException("fixture network jitter for $term")
                } else if (term in firstWaveFormatFailures) {
                    // 模拟服务端数据问题：返回无法解析的 HTML 错误页（非网络、非会话失效）
                    response(body = "<html><body>Gateway Timeout</body></html>").copy(url = request.url)
                } else {
                    response(
                        body = """{"datas":{"xscjcx":{"rows":[{"KCH":"$term","KCM":"course-$term","XF":"1.0","ZCJ":"88","XFJD":"3.8","DJCJLXDM_DISPLAY":"百分制"}]}}}""",
                    ).copy(url = request.url)
                }
            } finally {
                activeRows.decrementAndGet()
            }
        }
    }

    private companion object {
        fun response(
            code: Int = 200,
            location: String? = null,
            body: String = "",
            headers: Map<String, List<String>> = emptyMap(),
        ) = QueryHttpResponse(
            url = "",
            code = code,
            location = location,
            body = body,
            headers = headers,
        )

        fun cookie(value: String) = mapOf("Set-Cookie" to listOf("$value; Path=/"))
    }
}
