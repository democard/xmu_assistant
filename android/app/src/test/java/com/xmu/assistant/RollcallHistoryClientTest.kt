package com.xmu.assistant

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 最近十次签到客户端回归（任务书 §5.5）：解析排序截十、学期分批早停、null id 跳过、
 * 本人明细判定三态、401 类型化上抛、缓存往返与账号绑定、课程复用零重复请求。
 */
class RollcallHistoryClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun resetEndpointMemory() {
        // 端点记忆是进程级单例：每个用例重置，保证探测顺序断言确定性
        EndpointMemory.reset()
    }

    private fun client(transport: QueryHttpTransport) = RollcallHistoryClient(
        cookieHeader = "session=fixture",
        queryTransport = transport,
        executorFactory = { size -> Executors.newFixedThreadPool(size) },
    )

    @Test
    fun `parses times sorts descending and keeps only newest ten`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to (
                    (1..12).map { index -> rollcallJson("r%02d".format(index), "2026-07-0${(index % 9) + 1}T08:00:00") } +
                        listOf(rollcallJson("r91", "不是时间"), rollcallJson("r92", ""))
                    ),
            ),
            detailsByRollcallId = emptyMap(),
        )
        val items = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        assertEquals(ROLLCALL_HISTORY_LIMIT, items.size)
        // 解析失败时间的两条被可排序记录挤掉；结果按时间严格降序
        val expectedFirst = items.maxByOrNull { it.sortKeyMillis ?: Long.MIN_VALUE }
        assertEquals(expectedFirst?.rollcallId, items.first().rollcallId)
        assertTrue(items.zipWithNext().all { (a, b) -> (a.sortKeyMillis ?: 0) >= (b.sortKeyMillis ?: 0) })
        assertTrue(items.none { it.rollcallId == "r91" || it.rollcallId == "r92" })
        // 展示格式 MM-dd HH:mm（本地时区往返后与输入挂钟一致）
        assertEquals("雷达签到", items.first().type)
        assertTrue(
            "timeDisplay 应为 MM-dd HH:mm，实际 ${items.first().timeDisplay}",
            items.first().timeDisplay.matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}""")),
        )
    }

    @Test
    fun `stops after newer semester batch once enough sortable records collected`() {
        val courseList = listOf(
            course("newer", "新学期课", "2026-2"),
            course("older", "旧学期课", "2025-1"),
        )
        val transport = FakeHistoryTransport(
            courses = courseList,
            rollcallsByCourse = mapOf(
                "newer" to (1..12).map { index -> rollcallJson("n%02d".format(index), "2026-06-01T08:%02d:00".format(index)) },
                "older" to (1..12).map { index -> rollcallJson("o%02d".format(index), "2025-06-01T08:%02d:00".format(index)) },
            ),
            detailsByRollcallId = emptyMap(),
        )
        val items = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = courseList)
        assertEquals(ROLLCALL_HISTORY_LIMIT, items.size)
        // 新学期批已凑够 10 条：旧学期课程一个请求都不该发（早停）
        assertTrue(transport.requests.none { "/api/course/older/" in it.url })
        assertTrue(items.all { it.courseId == "newer" })
    }

    @Test
    fun `skips records whose id is missing or literal null`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to listOf(
                    rollcallJson(id = "", time = "2026-07-01T08:00:00"),
                    rollcallJson(id = "null", time = "2026-07-02T08:00:00"),
                    rollcallJson("ok", "2026-07-03T08:00:00"),
                ),
            ),
            detailsByRollcallId = mapOf("ok" to """{"student_rollcalls":[]}"""),
        )
        val items = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        assertEquals(listOf("ok"), items.map { it.rollcallId })
        // 无有效 id 的记录不产生任何明细请求
        assertEquals(1, transport.requests.count { "/student_rollcalls" in it.url })
    }

    @Test
    fun `own detail resolves signed unsigned and unknown`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to listOf(
                    rollcallJson("signed", "2026-07-03T08:00:00"),
                    rollcallJson("unsigned", "2026-07-02T08:00:00"),
                    rollcallJson("mystery", "2026-07-01T08:00:00"),
                ),
            ),
            detailsByRollcallId = mapOf(
                // 时间戳 → 已签
                "signed" to """{"student_rollcalls":[{"user_no":"u1","updated_at":"2026-07-03T08:01:00"}]}""",
                // 本人明确未签 → 未签
                "unsigned" to """{"student_rollcalls":[{"user_no":"u1","status":"not_signed"}]}""",
                // 没有本人记录 → 未知（绝不冒充聚合状态）
                "mystery" to """{"student_rollcalls":[{"user_no":"someone_else","status":"signed"}]}""",
            ),
        )
        val items = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        assertEquals(
            listOf(
                "signed" to STATUS_SIGNED,
                "unsigned" to "未签",
                "mystery" to STATUS_UNKNOWN,
            ),
            items.map { it.rollcallId to it.ownStatus },
        )
    }

    @Test
    fun `detail query propagates a typed main session expiration`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to listOf(rollcallJson("r1", "2026-07-01T08:00:00")),
            ),
            detailsByRollcallId = emptyMap(),
            detailCodeOverride = 401,
        )
        assertThrows(MainSessionExpiredException::class.java) {
            client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        }
    }

    @Test
    fun `cache roundtrip preserves items and rejects foreign accounts`() {
        val file = temporaryFolder.newFile("rollcall_history_cache.json")
        val snapshot = RollcallHistorySnapshot(
            accountId = "u1",
            fetchedAtMillis = 1_720_000_000_000L,
            items = listOf(
                RollcallHistoryItem("r1", "c1", "课程一", "雷达签到", "07-01 08:00", 1_720_000_000L * 1000, STATUS_SIGNED),
                RollcallHistoryItem("r2", "c1", "课程一", "数字签到", "-", null, STATUS_UNKNOWN),
            ),
        )
        saveRollcallHistoryCache(file, snapshot)

        val loaded = loadRollcallHistoryCache(file, accountId = "u1")
        assertEquals(snapshot.accountId, loaded?.accountId)
        assertEquals(snapshot.fetchedAtMillis, loaded?.fetchedAtMillis)
        assertEquals(snapshot.items, loaded?.items)

        // 换账号读同一份缓存：一律判空（防串号）
        assertNull(loadRollcallHistoryCache(file, accountId = "u2"))
    }

    @Test
    fun `cache rejects wrong version and corrupted files`() {
        val file = temporaryFolder.newFile("rollcall_history_cache.json")
        file.writeText("""{"version":999,"account_id":"u1","fetched_at":1,"items":[]}""")
        assertNull(loadRollcallHistoryCache(file, accountId = "u1"))
        file.writeText("{broken json")
        assertNull(loadRollcallHistoryCache(file, accountId = "u1"))
    }

    @Test
    fun `reuses preloaded courses without re-fetching the course list`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to listOf(rollcallJson("r1", "2026-07-01T08:00:00")),
            ),
            detailsByRollcallId = mapOf("r1" to """{"student_rollcalls":[{"is_current_user":true,"status":"present"}]}"""),
        )
        val items = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        assertEquals(listOf(STATUS_SIGNED), items.map { it.ownStatus })
        // 课程复用：除 /api/profile 外不得出现课程列表端点请求
        assertTrue(transport.requests.none { "/api/my-courses" in it.url || "/api/courses" in it.url })
        // is_current_user 命中本人记录（不依赖 username 匹配分支）
        assertTrue(transport.requests.any { "/student_rollcalls" in it.url })
    }

    @Test
    fun `falls back to probing the course list when nothing preloaded`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to listOf(rollcallJson("r1", "2026-07-01T08:00:00")),
            ),
            detailsByRollcallId = mapOf("r1" to """{"student_rollcalls":[{"user_no":"u1"}]}"""),
        )
        val items = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = emptyList())
        // 兜底路径：先打课程端点再走主流程；本人记录无时间戳且无状态词 → 未知
        assertTrue(transport.requests.any { "/api/my-courses" in it.url })
        assertEquals(listOf(STATUS_UNKNOWN), items.map { it.ownStatus })
    }

    @Test
    fun `two phase split previews blank statuses then resolves identically to one shot`() {
        val transport = FakeHistoryTransport(
            courses = listOf(course("c1", "课程一", "2026-1")),
            rollcallsByCourse = mapOf(
                "c1" to listOf(
                    rollcallJson("r1", "2026-07-02T08:00:00"),
                    rollcallJson("r2", "2026-07-01T08:00:00"),
                ),
            ),
            detailsByRollcallId = mapOf(
                "r1" to """{"student_rollcalls":[{"user_no":"u1","updated_at":"2026-07-02T08:05:00"}]}""",
                "r2" to """{"student_rollcalls":[{"user_no":"u1","status":"not_signed"}]}""",
            ),
        )
        val twoPhase = client(transport)
        // 阶段一即出列表：状态位空白（UI 显示「核实中…」），绝不预填聚合值
        val preview = twoPhase.selectRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        assertEquals(listOf("r1", "r2"), preview.map { it.rollcallId })
        assertTrue(preview.all { it.ownStatus.isEmpty() })
        // 阶段二原位回填准确结论
        val resolved = twoPhase.resolveOwnStatuses(preview, "u1")
        assertEquals(listOf(STATUS_SIGNED, "未签"), resolved.map { it.ownStatus })
        // 与一站式入口逐字段一致
        val oneshot = client(transport).fetchRecentRollcalls(username = "u1", preloadedCourses = fakeCourses())
        assertEquals(resolved, oneshot)
    }

    // ---- 夹具 ----

    private fun fakeCourses() = listOf(course("c1", "课程一", "2026-1"))

    private fun course(id: String, title: String, semester: String) =
        CourseSummary(id = id, title = title, term = "$semester 学期", semesterCode = semester)

    private fun rollcallJson(id: String, time: String): String =
        """{"id":"$id","rollcall_time":"$time","type":"radar"}"""

    /**
     * 按 URL 子串路由的假传输；detailCodeOverride 用于注入 401 会话过期。
     */
    private class FakeHistoryTransport(
        private val courses: List<CourseSummary>,
        private val rollcallsByCourse: Map<String, List<String>>,
        private val detailsByRollcallId: Map<String, String>,
        private val detailCodeOverride: Int = 200,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            val url = request.url
            return when {
                "/api/profile" in url -> json(200, """{"id":"s1"}""")
                "/api/my-courses" in url -> json(
                    200,
                    courses.joinToString(",", prefix = "{\"courses\":[", postfix = "]}") { c ->
                        """{"id":"${c.id}","name":"${c.title}","semester":{"code":"${c.semesterCode}"}}"""
                    },
                )
                "/student_rollcalls" in url -> {
                    val rid = url.substringAfter("/api/rollcall/").substringBefore("/student_rollcalls")
                    if (rid in detailsByRollcallId) json(detailCodeOverride, detailsByRollcallId[rid].orEmpty())
                    else json(detailCodeOverride, "{}")
                }
                else -> {
                    val cid = url.substringAfter("/api/course/").substringBefore("/student/")
                    json(200, rollcallsByCourse[cid]?.joinToString(",", prefix = "{\"rollcalls\":[", postfix = "]}") ?: "{\"rollcalls\":[]}")
                }
            }
        }

        private fun json(code: Int, body: String) = QueryHttpResponse(
            url = "https://lnt.xmu.edu.cn/api/fake",
            code = code,
            location = null,
            body = body,
            headers = emptyMap(),
        )
    }
}
