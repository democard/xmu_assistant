package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * CoursewareSectionState（课件模块独立分块）行为测试（E2，createCoursewareClient 工厂注入）：
 * - 成功读取 + 缓存落盘（内存态 + academicCache JSON 持久化通道）；
 * - 会话过期（401）挂起一次性安全续登（ModuleReadRetry(COURSEWARE, course)，不弹失败）；
 * - 课程级互斥门（"courseware:{id}" 被占时直接拒绝，零请求）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoursewareSectionStateTest {

    private val course = CourseSummary(id = "cs-101", title = "数据结构")

    /** 课件列表/明细 fake transport：listCode 注入 401 构造会话过期。 */
    private class StubCoursewareTransport(
        private val listCode: Int = 200,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            return when {
                "/courseware-activities" in request.url -> respond(
                    listCode,
                    """{"activities":[{"id":"act-1","type":"document","title":"第一章 课件"}]}""",
                    request.url,
                )
                "/api/activities/" in request.url -> respond(
                    200,
                    """{"title":"第一章 课件","type":"file"}""",
                    request.url,
                )
                else -> respond(200, "{}", request.url)
            }
        }

        private fun respond(code: Int, body: String, url: String) = QueryHttpResponse(
            url = url,
            code = code,
            location = null,
            body = body,
            headers = emptyMap(),
        )
    }

    private class Harness(
        val state: CoursewareSectionState,
        val gate: RequestGate,
        val transport: StubCoursewareTransport,
        val toasts: MutableList<String>,
        val pendingRetries: MutableList<ModuleReadRetry>,
        val cacheJsonWrites: MutableList<String>,
    )

    private fun newHarness(transport: StubCoursewareTransport): Harness {
        val toasts = mutableListOf<String>()
        val pendingRetries = mutableListOf<ModuleReadRetry>()
        val cacheJsonWrites = mutableListOf<String>()
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        var cache = AcademicCacheSnapshot()
        var selectedCourseId: String? = null
        val state = CoursewareSectionState(
            activity = Robolectric.buildActivity(Activity::class.java).setup().get(),
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = { toasts += it },
            showWarning = {},
            showError = { toasts += "ERROR:$it" },
            requireLogin = { true },
            loggedIn = { true },
            cookieHeader = { "session=fixture" },
            busy = { "" },
            setBusy = {},
            selectedCourseId = { selectedCourseId },
            setSelectedCourseId = { selectedCourseId = it },
            academicCache = { cache },
            setAcademicCache = { cache = it },
            setAcademicCacheJson = { cacheJsonWrites += it },
            isSelectedCourse = { it == selectedCourseId },
            setPendingSessionRetry = { pendingRetries += it },
            createCoursewareClient = { cookie -> CoursewareClient(cookie, transport, { size -> Executors.newFixedThreadPool(size) }) },
        )
        return Harness(state, gate, transport, toasts, pendingRetries, cacheJsonWrites)
    }

    /** 轮询驱动 Robolectric 主 looper（withContext(Main) 段落地）直至条件成立或超时。 */
    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    @Test
    fun `load fills items persists cache and releases loading`() {
        val harness = newHarness(StubCoursewareTransport())
        assertTrue(harness.state.load(course))
        assertTrue("课件列表未落地或 loading 未释放", await { harness.state.coursewareItems.isNotEmpty() && harness.state.loadingIds.isEmpty() })
        val item = harness.state.coursewareItems.single()
        assertEquals("act-1", item.id)
        assertEquals("cs-101", item.courseId)
        assertEquals("第一章 课件", item.title)
        assertTrue(harness.toasts.contains("已读取 1 条课件"))
        // 缓存落盘通道：academicCache JSON 写入且包含课件条目
        assertTrue("缓存 JSON 未写入", await { harness.cacheJsonWrites.isNotEmpty() })
        assertTrue(harness.cacheJsonWrites.single().contains("act-1"))
    }

    @Test
    fun `session expiry defers one-shot recovery with course bound retry`() {
        val harness = newHarness(StubCoursewareTransport(listCode = 401))
        assertTrue(harness.state.load(course))
        assertTrue("续登挂起未落地", await { harness.pendingRetries.isNotEmpty() })
        assertEquals(listOf(ModuleReadRetry(ModuleReadRequest.COURSEWARE, course)), harness.pendingRetries)
        assertEquals("登录已过期，正在安全续登", harness.state.refreshErrors[course.id])
        // 会话过期路径不弹失败 toast（由续登接管，避免误导）
        assertFalse(harness.toasts.any { it.startsWith("ERROR:") })
        assertTrue("loadingIds 必须释放（守卫通过）", await { harness.state.loadingIds.isEmpty() })
    }

    @Test
    fun `held course gate rejects load without any request`() {
        val harness = newHarness(StubCoursewareTransport())
        assertTrue(harness.gate.tryStart("courseware:${course.id}"))
        assertFalse(harness.state.load(course))
        assertTrue(harness.transport.requests.isEmpty())
        harness.gate.finish("courseware:${course.id}")
    }
}
