package com.xmu.assistant

import java.time.LocalDate
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 考试安排客户端纯逻辑测试：
 * - 学期推断（本地时间边界，普适不写死年份）
 * - 探测窗口（由近及远、连续空白停止）
 * - 真实接口样例解析（docs/exam_api_sample.json 的 cxxsksap.do 结构）
 * - 会话过期续登链路（与课表/成绩同构：显式 CAS 续登后重试）
 * - 变化检测（悄悄检查核心判定）
 */
class XmuExamClientTest {

    @Test
    fun `current term code follows local calendar boundaries`() {
        val client = XmuExamClient(cookieHeader = "")
        // 1-6 月：当前学年的第二学期
        assertEquals("2025-2026-2", client.currentTermCode(LocalDate.of(2026, 1, 15)))
        assertEquals("2025-2026-2", client.currentTermCode(LocalDate.of(2026, 3, 1)))
        assertEquals("2025-2026-2", client.currentTermCode(LocalDate.of(2026, 6, 30)))
        // 7-8 月：第三学期（夏季短学期）
        assertEquals("2025-2026-3", client.currentTermCode(LocalDate.of(2026, 7, 1)))
        assertEquals("2025-2026-3", client.currentTermCode(LocalDate.of(2026, 8, 31)))
        // 9-12 月：本学年的第一学期
        assertEquals("2026-2027-1", client.currentTermCode(LocalDate.of(2026, 9, 1)))
        assertEquals("2026-2027-1", client.currentTermCode(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `probe fallback stops after consecutive blanks and returns newest first`() {
        // 回退路径（无学号/学期列表不可用）：本地推断当前学期（2025-2026-3）往前，
        // 2025-2026-3 空白、2025-2026-2 有数据、2025-2026-1 有数据、
        // 2024-2025-3 空白、2024-2025-2 空白 → 连续 2 个空白提前停止
        val transport = FakeExamTransport(
            termData = mapOf(
                "2025-2026-2" to examBody("微积分I-2"),
                "2025-2026-1" to examBody("C语言程序设计"),
                "2024-2025-2" to emptyTermBody(),
                "2024-2025-1" to emptyTermBody(),
                "2023-2024-2" to examBody("不应被探测的历史考试"),
            ),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", transport = transport)

        val result = client.probeValidTerms(maxProbe = 6, blankLimit = 2, today = LocalDate.of(2026, 8, 15))

        assertEquals(listOf("2025-2026-2", "2025-2026-1"), result.terms) // 新→旧
        assertEquals("2025-2026-2", result.latest?.termCode)
        assertFalse(transport.requests.any { it.body.contains("2023-2024-2") })
    }

    @Test
    fun `probe uses enrollment window and shows unarranged-only terms`() {
        // 假学号 12320011234567 → 2001 年入学：窗口 = [2001-2002-1, 上界]，
        // 入学前的学期（2000-2001-1）绝不探测；第三学期/新学期只有"已选课未安排"也显示（底线）。
        // 列表预置未来学期（2003-2004-*）与异常学期号（2002-2003-8）被上界/过滤剔除，不浪费探测。
        val transport = FakeExamTransport(
            termCodes = listOf(
                "2001-2002-1", "2001-2002-2", "2001-2002-3",
                "2002-2003-1", "2002-2003-2", "2002-2003-8",
                "2003-2004-1", "2003-2004-2",
            ),
            termData = mapOf(
                "2001-2002-1" to examBody("C语言程序设计"),
                "2001-2002-2" to examBody("微积分I-2"),
                "2001-2002-3-unarranged" to unarrangedOnlyBody("短学期实践课"),
                "2002-2003-1-unarranged" to unarrangedOnlyBody("新学期课程A", "新学期课程B"),
            ),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", username = "12320011234567", transport = transport)

        val result = client.probeValidTerms(today = LocalDate.of(2002, 8, 15))

        assertEquals(listOf("2002-2003-1", "2001-2002-3", "2001-2002-2", "2001-2002-1"), result.terms) // 新→旧
        assertEquals("2002-2003-1", result.latest?.termCode)
        assertEquals(2, result.latest?.unarranged?.size)
        assertTrue(result.latest?.exams.isNullOrEmpty()) // 新学期一场考试都没排
        // 未入学学期不探测（省请求）
        assertFalse(transport.requests.any { it.body.contains("2000-2001") })
        // 预置过远的未来学期（2003-2004）与异常学期号（2002-2003-8）不探测
        assertFalse(transport.requests.any { it.body.contains("2003-2004") })
        assertFalse(transport.requests.any { it.body.contains("2002-2003-8") })
        // 教务列表已取（xnxqcx.do）
        assertTrue(transport.requests.any { it.url.endsWith("/xnxqcx.do") })
        // 窗口内全部有效学期数据返回（供上层整体缓存/提醒聚合）
        assertEquals(4, result.summaries.size)
    }

    @Test
    fun `server 5xx is response error and never triggers relogin`() {
        // 风控红线：5xx/服务端错误绝不触发 CAS 续登（只有 401/403/登录页才算会话过期）
        val transport = FakeExamTransport(
            termData = mapOf("2025-2026-2" to examBody("微积分I-2")),
            serverErrorUrls = setOf("https://jw.xmu.edu.cn/jwapp/sys/studentWdksapApp/modules/wdksap/cxxsksap.do"),
        )
        val client = XmuExamClient(
            cookieHeader = "saved=session",
            username = "12320011234567",
            password = "fixture-password",
            mayRelogin = { true },
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val error = runCatching { client.fetchTermExams("2025-2026-2") }.exceptionOrNull()

        assertTrue(error is ExamResponseException)
        // 未发起任何 CAS 登录请求
        assertFalse(transport.requests.any { it.url.contains("authserver/login") })
    }

    @Test
    fun `term list network failure falls back to local window without relogin`() {
        // 学期列表网络失败（非会话过期）：回退本地推断窗口，且绝不触发 CAS 登录（风控红线）
        val transport = FakeExamTransport(
            termCodes = listOf("2025-2026-1", "2025-2026-2", "2025-2026-3"),
            termData = mapOf("2025-2026-3" to examBody("短学期考试")),
            networkFailUrls = setOf("https://jw.xmu.edu.cn/jwapp/sys/studentWdksapApp/modules/wdksap/xnxqcx.do"),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", username = "12320011234567", transport = transport)

        val result = client.probeValidTerms(today = LocalDate.of(2026, 8, 15))

        assertEquals(listOf("2025-2026-3"), result.terms)
        assertFalse(transport.requests.any { it.url.contains("authserver/login") })
    }

    @Test
    fun `relogin refused while gate busy maps to busy message not session expired`() {
        // 单飞门被其他模块占用时：不引导用户重登（避免重复登录触发风控），提示稍候重试
        val transport = SessionExpiryExamTransport(
            termData = mapOf("2025-2026-2" to examBody("微积分I-2")),
        )
        val client = XmuExamClient(
            cookieHeader = "stale=session",
            username = "12320011234567",
            password = "fixture-password",
            mayRelogin = { true },
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )
        val acquired = ProcessSessionRecovery.coordinator.tryStartAcademicCasLogin() // 占用进程级单飞门
        try {
            val error = runCatching { client.fetchTermExams("2025-2026-2") }.exceptionOrNull()
            assertTrue(error is ExamResponseException)
            assertEquals(0, transport.identityLoginCount.get())
        } finally {
            // 仅在实际取得门时才释放，避免释放他人持有的门（跨测试污染）
            if (acquired) ProcessSessionRecovery.coordinator.finishAcademicCasLogin()
        }
    }

    @Test
    fun `window probe unifies relogin across parallel terms exactly once`() {
        // 列表成功但窗口学期首轮 401：并行学期同时判过期 → 窗口层必须统一续登恰好一次，
        // 且重试后全部学期成功（不能各自抢单飞门导致部分学期丢失）。
        val transport = SessionExpiryExamTransport(
            termCodes = listOf("2001-2002-1", "2001-2002-2", "2001-2002-3", "2002-2003-1"),
            termData = mapOf(
                "2001-2002-1" to examBody("C语言程序设计"),
                "2001-2002-2" to examBody("微积分I-2"),
                "2001-2002-3-unarranged" to unarrangedOnlyBody("短学期实践课"),
                "2002-2003-1-unarranged" to unarrangedOnlyBody("新学期课程A"),
            ),
            termListAlwaysOk = true,
        )
        val client = XmuExamClient(
            cookieHeader = "stale=session",
            username = "12320011234567",
            password = "fixture-password",
            mayRelogin = { true },
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val result = client.probeValidTerms(today = LocalDate.of(2002, 8, 15))

        assertEquals(listOf("2002-2003-1", "2001-2002-3", "2001-2002-2", "2001-2002-1"), result.terms)
        // 统一续登恰好一次（窗口层单飞；若是每个学期各自续登会 >1）
        assertEquals(1, transport.identityLoginCount.get())
        // 重试请求带上了续登后的新 cookie
        assertTrue(transport.retryRequests.any { it.headers["Cookie"].orEmpty().contains("jw=ready") })
    }

    @Test
    fun `probe skips known cached terms and narrows window on re-probe`() {
        // 第二次探测（knownTerms 已缓存）：跳过已缓存学期，且窗口下界收窄到最近有效-2，
        // 只探测新增/边界学期（省请求）
        val transport = FakeExamTransport(
            termCodes = listOf(
                "2001-2002-1", "2001-2002-2", "2001-2002-3",
                "2002-2003-1", "2002-2003-2",
            ),
            termData = mapOf(
                "2001-2002-1" to examBody("C语言程序设计"),
                "2001-2002-2" to examBody("微积分I-2"),
                "2001-2002-3-unarranged" to unarrangedOnlyBody("短学期实践课"),
                "2002-2003-1-unarranged" to unarrangedOnlyBody("新学期课程A"),
            ),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", username = "12320011234567", transport = transport)
        val known = listOf("2002-2003-1", "2001-2002-3", "2001-2002-2", "2001-2002-1") // 上次探测结果（新→旧）

        val result = client.probeValidTerms(today = LocalDate.of(2002, 8, 15), knownTerms = known)

        // 已知学期全部保留
        assertEquals(known, result.terms)
        // 已缓存学期不重复请求（只探测 2002-2003-2 这个新增/边界学期）
        assertFalse(transport.requests.any { it.body.contains("2001-2002-1") })
        assertFalse(transport.requests.any { it.body.contains("2001-2002-2") })
        assertFalse(transport.requests.any { it.body.contains("2001-2002-3") })
        assertFalse(transport.requests.any { it.body.contains("2002-2003-1") })
    }

    @Test
    fun `probe retries term list after session expiry`() {
        // 列表接口第一次 401（旧 cookie）→ 续登 → 重试成功 → 窗口含未来学期
        val transport = SessionExpiryExamTransport(
            termCodes = listOf("2025-2026-1", "2025-2026-2", "2025-2026-3", "2026-2027-1"),
            termData = mapOf(
                "2025-2026-1" to examBody("C语言程序设计"),
                "2025-2026-2" to examBody("微积分I-2"),
                "2025-2026-3-unarranged" to unarrangedOnlyBody("短学期实践课"),
                "2026-2027-1-unarranged" to unarrangedOnlyBody("新学期课程A"),
            ),
        )
        val client = XmuExamClient(
            cookieHeader = "stale=session",
            username = "12320011234567",
            password = "fixture-password",
            mayRelogin = { true },
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val result = client.probeValidTerms()

        assertEquals(listOf("2026-2027-1", "2025-2026-3", "2025-2026-2", "2025-2026-1"), result.terms)
        assertTrue(transport.identityLoginCount.get() > 0)
    }

    @Test
    fun `probe falls back to local inference when student id unparsable`() {
        // 学号格式异常（非厦大 3 位学院码 + 4 位年份）：回退本地推断 + 空白停止
        val transport = FakeExamTransport(
            termCodes = listOf("2025-2026-1", "2025-2026-2", "2025-2026-3"),
            termData = mapOf("2025-2026-3" to examBody("短学期考试")),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", username = "abc2025xyz", transport = transport)

        val result = client.probeValidTerms()

        assertEquals(listOf("2025-2026-3"), result.terms)
    }

    @Test
    fun `fetch parses exam sample structure`() {
        // 接口结构样例（字段齐全的 mock）：KSRWID/KCM/KSRQ/KSSJMS/JASMC/KSXS_DISPLAY
        val realRows = """[{"KSRWID":"fixture-exam-1","KCM":"课程甲","KSRQ":"2026-01-02","KSSJMS":"2026-01-02 08:00-10:00(星期日)","JASMC":"教学楼A-306","KSXS_DISPLAY":"线下","KSMC":"2025-2026学年 第二学期 期末考试"},{"KSRWID":"fixture-exam-2","KCM":"课程乙","KSRQ":"2026-01-03","KSSJMS":"2026-01-03 10:30-12:30(星期五)","JASMC":"教学楼C-406","KSXS_DISPLAY":"线下","KSMC":"2025-2026学年 第二学期 期末考试"}]"""
        val transport = FakeExamTransport(
            termData = mapOf(
                "2025-2026-2" to """{"code":"0","datas":{"cxxsksap":{"totalSize":2,"rows":$realRows}}}""",
                "2025-2026-2-unarranged" to """{"code":"0","datas":{"cxyxkwapkwdkc":{"totalSize":1,"rows":[{"KCM":"课程丙"}]}}}""",
            ),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", transport = transport)

        val summary = client.fetchTermExams("2025-2026-2")

        assertNotNull(summary)
        assertEquals(2, summary!!.exams.size)
        assertEquals("课程甲", summary.exams[0].courseName)
        assertEquals("2026-01-02", summary.exams[0].date)
        assertEquals("08:00-10:00", summary.exams[0].timeRange) // 从 KSSJMS 提取，不含星期
        assertEquals("教学楼A-306", summary.exams[0].room)
        assertEquals("线下", summary.exams[0].mode)
        assertEquals(1, summary.unarranged.size)
        assertEquals("课程丙", summary.unarranged[0].courseName)
        // 学期标签普适：从代码生成，不写死年份
        assertEquals("2025-2026学年 第二学期", summary.termLabel)
    }

    @Test
    fun `blank term returns null summary`() {
        val transport = FakeExamTransport(termData = mapOf("2025-2026-1" to emptyTermBody()))
        val client = XmuExamClient(cookieHeader = "saved=session", transport = transport)

        assertNull(client.fetchTermExams("2025-2026-1"))
    }

    @Test
    fun `fetch issues both endpoints in parallel`() {
        val transport = FakeExamTransport(
            termData = mapOf(
                "2025-2026-2" to examBody("微积分I-2"),
                "2025-2026-2-unarranged" to """{"code":"0","datas":{"cxyxkwapkwdkc":{"totalSize":1,"rows":[{"KCM":"形势与政策（2）"}]}}}""",
            ),
        )
        val client = XmuExamClient(cookieHeader = "saved=session", transport = transport)

        val summary = client.fetchTermExams("2025-2026-2")

        assertNotNull(summary)
        // 两个接口都已请求（并行拉取：单学期从 2 个串行 RTT 减到 1 个）；
        // 另有 1 个应用入口请求（index.do 建立应用级会话，与成绩/课表同构）。
        val urls = transport.requests.map { it.url }
        assertTrue(urls.any { it.endsWith("/cxxsksap.do") })
        assertTrue(urls.any { it.endsWith("/cxyxkwapkwdkc.do") })
        assertTrue(urls.any { it.contains("index.do") })
        assertEquals(1, summary!!.exams.size)
        assertEquals(1, summary.unarranged.size)
    }

    @Test
    fun `relogin refused maps to session expired`() {
        // 并行场景下若两个接口同时判定会话过期，进程级单飞门只放行一个续登；
        // 被拒绝的一方必须明确抛 ExamSessionExpiredException（不静默吞掉）。
        val transport = SessionExpiryExamTransport()
        val client = XmuExamClient(
            cookieHeader = "stale=session",
            username = "fixture-student",
            password = "fixture-password",
            mayRelogin = { false },
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val error = runCatching { client.fetchTermExams("2025-2026-2") }.exceptionOrNull()

        assertTrue(error is ExamSessionExpiredException)
        assertEquals(0, transport.identityLoginCount.get())
    }

    @Test
    fun `session expiry triggers explicit academic relogin then retries`() {
        // 第一次 cxxsksap 401（会话过期）→ forceAcademicLogin（CAS 登录链）→ 重试成功
        val transport = SessionExpiryExamTransport(
            termData = mapOf("2025-2026-2" to examBody("微积分I-2")),
        )
        val client = XmuExamClient(
            cookieHeader = "stale=session",
            username = "fixture-student",
            password = "fixture-password",
            mayRelogin = { true },
            transport = transport,
            base64Encoder = { "fixture-encrypted-password" },
        )

        val summary = client.fetchTermExams("2025-2026-2")

        assertNotNull(summary)
        assertEquals(1, summary!!.exams.size)
        assertTrue(transport.identityLoginCount.get() > 0)
        // 续登后新 cookie 已持久化到 jar，供上层写回 settings
        assertTrue(client.currentCookie().contains("jw=ready"))
        // 重试请求带上了续登后的 cookie
        assertTrue(transport.retryRequests.any { it.headers["Cookie"].orEmpty().contains("jw=ready") })
    }

    @Test
    fun `enrollment year extracted only from xmu undergraduate id format`() {
        assertEquals(2001, enrollmentYearFromStudentId("12320011234567"))
        assertEquals(2002, enrollmentYearFromStudentId("12320021234567"))
        assertNull(enrollmentYearFromStudentId("1232001"))          // 太短
        assertNull(enrollmentYearFromStudentId("abc200112345"))     // 非数字前缀
        assertNull(enrollmentYearFromStudentId("12319991234567"))   // 年份不是 20xx
    }

    @Test
    fun `term label short is generic and semester aware`() {
        assertEquals("25-26第二学期", termLabelShort("2025-2026-2"))
        assertEquals("25-26第一学期", termLabelShort("2025-2026-1"))
        assertEquals("26-27第三学期", termLabelShort("2026-2027-3"))
        assertEquals("异常代码", termLabelShort("异常代码"))
    }

    @Test
    fun `exams changed detects add remove and status moves`() {
        val old = summaryOf(
            exams = listOf(
                exam("1", "课程甲", "2026-01-02", "08:00-10:00", "A306"),
                exam("2", "课程乙", "2026-01-03", "10:30-12:30", "C406"),
            ),
            unarranged = listOf(XmuExamUnarranged("课程丙")),
        )
        // 完全相同 → 无变化
        assertFalse(examsChanged(old, summaryOf(exams = old.exams, unarranged = old.unarranged)))
        // 新增一场考试 → 变化
        assertTrue(
            examsChanged(
                old,
                summaryOf(exams = old.exams + exam("3", "课程丁", "2026-01-04", "10:30-12:30", "C401"), unarranged = old.unarranged),
            ),
        )
        // 未安排课程被安排上（从未安排移除）→ 变化
        assertTrue(examsChanged(old, summaryOf(exams = old.exams, unarranged = emptyList())))
        // 时间变动 → 变化
        assertTrue(
            examsChanged(
                old,
                summaryOf(exams = listOf(exam("1", "课程甲", "2026-01-05", "08:00-10:00", "A306"), old.exams[1]), unarranged = old.unarranged),
            ),
        )
    }

    private fun exam(id: String, name: String, date: String, time: String, room: String) =
        XmuExam(id = id, courseName = name, date = date, timeRange = time, room = room, mode = "线下", examName = "期末考试")

    private fun summaryOf(exams: List<XmuExam>, unarranged: List<XmuExamUnarranged> = emptyList()) =
        XmuTermExamSummary(
            termCode = "2025-2026-2",
            termLabel = "2025-2026学年 第二学期",
            exams = exams,
            unarranged = unarranged,
        )

    /** 按学期返回响应；未安排接口默认空；支持教务学期列表（xnxqcx.do）与失败注入。 */
    private class FakeExamTransport(
        private val termData: Map<String, String>,
        private val termCodes: List<String> = emptyList(),
        private val networkFailUrls: Set<String> = emptySet(),
        private val serverErrorUrls: Set<String> = emptySet(),
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            requests += request
            if (request.url in networkFailUrls) throw java.io.IOException("fixture network failure")
            if (request.url in serverErrorUrls) {
                return response(code = 500, body = "fixture server error").copy(url = request.url)
            }
            // 应用入口（index.do）：真实建模为 200（此前靠 else->error 被吞错入列，
            // 断言依赖副作用；显式建模让"应用会话已打开"成为真实成功路径）
            if (request.url.contains("index.do")) {
                return response(code = 200, body = "<html>app</html>").copy(url = request.url)
            }
            if (request.url.endsWith("/xnxqcx.do")) {
                val rows = termCodes.joinToString(",") { """{"DM":"$it"}""" }
                return response(
                    body = """{"code":"0","datas":{"xnxqcx":{"totalSize":${termCodes.size},"rows":[$rows]}}}""",
                ).copy(url = request.url)
            }
            val body = request.body
            val term = Regex("""XNXQDM=([^&]+)""").find(body)?.groupValues?.get(1)?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""
            val responseBody = when {
                request.url.endsWith("/cxxsksap.do") -> termData[term] ?: emptyTermBody()
                request.url.endsWith("/cxyxkwapkwdkc.do") -> termData["$term-unarranged"] ?: emptyTermBody()
                else -> error("Unexpected fixture URL: ${request.url}")
            }
            return response(code = 200, body = responseBody).copy(url = request.url)
        }
    }

    /** 第一次业务/列表请求 401 → CAS 续登 → 重试 200（认证前 401、认证后按 termData 返回）。
     *  termListAlwaysOk：学期列表始终 200（用于构造"列表成功但窗口学期 401"的窗口层续登场景）。 */
    private class SessionExpiryExamTransport(
        private val termCodes: List<String> = emptyList(),
        private val termData: Map<String, String> = emptyMap(),
        private val termListAlwaysOk: Boolean = false,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        val identityLoginCount = java.util.concurrent.atomic.AtomicInteger(0)
        val retryRequests = mutableListOf<QueryHttpRequest>()
        private var authenticated = false

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
                request.url.contains("/login?ticket=") -> {
                    authenticated = true
                    response(headers = cookie("jw=ready")).copy(url = request.url)
                }
                request.url.contains("/appShow") -> response().copy(url = request.url)
                request.url.contains("index.do") ->
                    response(code = 200, body = "<html>app</html>").copy(url = request.url)
                request.url.endsWith("/xnxqcx.do") -> {
                    if (!authenticated && !termListAlwaysOk) {
                        response(code = 401).copy(url = request.url)
                    } else {
                        retryRequests += request
                        val rows = termCodes.joinToString(",") { """{"DM":"$it"}""" }
                        response(
                            body = """{"code":"0","datas":{"xnxqcx":{"totalSize":${termCodes.size},"rows":[$rows]}}}""",
                        ).copy(url = request.url)
                    }
                }
                request.url.endsWith("/cxxsksap.do") -> {
                    if (!authenticated) {
                        response(code = 401).copy(url = request.url)
                    } else {
                        retryRequests += request
                        response(body = termData[decodeTerm(request.body)] ?: emptyTermBody()).copy(url = request.url)
                    }
                }
                request.url.endsWith("/cxyxkwapkwdkc.do") ->
                    response(body = termData["${decodeTerm(request.body)}-unarranged"] ?: emptyTermBody()).copy(url = request.url)
                else -> error("Unexpected fixture URL: ${request.url}")
            }
        }

        private fun decodeTerm(body: String): String =
            Regex("""XNXQDM=([^&]+)""").find(body)?.groupValues?.get(1)?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""
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

        fun examBody(vararg names: String): String {
            val rows = names.mapIndexed { index, name ->
                """{"KSRWID":"id-$index","KCM":"$name","KSRQ":"2026-06-14","KSSJMS":"2026-06-14 08:00-10:00(星期日)","JASMC":"A306","KSXS_DISPLAY":"线下","KSMC":"期末考试"}"""
            }.joinToString(",")
            return """{"code":"0","datas":{"cxxsksap":{"totalSize":${names.size},"rows":[$rows]}}}"""
        }

        fun emptyTermBody(): String =
            """{"code":"0","datas":{"cxxsksap":{"totalSize":0,"rows":[]},"cxyxkwapkwdkc":{"totalSize":0,"rows":[]}}}"""

        /** 只有已选课未安排、没有已排考试的学期（底线场景：新学期/第三学期）。 */
        fun unarrangedOnlyBody(vararg names: String): String {
            val rows = names.map { """{"KCM":"$it"}""" }.joinToString(",")
            return """{"code":"0","datas":{"cxxsksap":{"totalSize":0,"rows":[]},"cxyxkwapkwdkc":{"totalSize":${names.size},"rows":[$rows]}}}"""
        }
    }
}
