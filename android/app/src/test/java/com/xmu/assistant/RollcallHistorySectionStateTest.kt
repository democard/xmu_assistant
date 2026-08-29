package com.xmu.assistant

import android.app.Activity
import android.os.Looper
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 最近十次签到（历史区块）全流程模拟（Robolectric，真机前的本地验证）：
 * SWR 缓存先行渲染 → 网络原位覆盖；失败保留缓存只报错（断网不清缓存模拟）；
 * 会话过期挂起 ROLLCALL_HISTORY 一次性续登；独立互斥门与登出清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollcallHistorySectionStateTest {

    private val app = RuntimeEnvironment.getApplication()

    /** 轮询等待异步协程收敛，同时泵主线程 Looper 让 withContext(Main) 段落地。 */
    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private class StubTransport(
        private val profileCode: Int = 200,
        private val coursesPayload: String? = null,
        private val rollcallsPayload: String? = null,
        private val detailPayload: String? = null,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            val url = request.url
            return when {
                "/api/profile" in url -> respond(profileCode, """{"id":"s1"}""")
                "/api/my-courses" in url ->
                    if (coursesPayload == null) respond(500, "") else respond(200, coursesPayload)
                "/student_rollcalls" in url ->
                    if (detailPayload == null) respond(500, "") else respond(200, detailPayload)
                else ->
                    if (rollcallsPayload == null) respond(500, "") else respond(200, rollcallsPayload)
            }
        }

        private fun respond(code: Int, body: String) = QueryHttpResponse(
            url = "https://lnt.xmu.edu.cn/api/fake",
            code = code,
            location = null,
            body = body,
            headers = emptyMap(),
        )
    }

    private data class Harness(
        val state: RollcallSectionState,
        val gate: RequestGate,
        val transport: StubTransport,
        val pendingRetries: MutableList<ModuleReadRetry>,
        val activity: Activity,
    )

    private fun newHarness(
        transport: StubTransport,
        loggedIn: Boolean = true,
    ): Harness {
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val pendingRetries = mutableListOf<ModuleReadRetry>()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RollcallSectionState(
            activity = activity,
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = {},
            requireLogin = { loggedIn },
            loggedIn = { loggedIn },
            cookieHeader = { "session=fixture" },
            username = { "u1" },
            coursesProvider = { emptyList() },
            setPendingSessionRetry = { pendingRetries += it },
            createHistoryClient = { cookieHeader ->
                assertEquals("session=fixture", cookieHeader)
                RollcallHistoryClient(cookieHeader, transport, { size -> java.util.concurrent.Executors.newFixedThreadPool(size) })
            },
        )
        return Harness(state, gate, transport, pendingRetries, activity)
    }

    private fun seedCache(accountId: String = "u1") {
        saveRollcallHistoryCache(
            rollcallHistoryCacheFile(app),
            RollcallHistorySnapshot(
                accountId = accountId,
                fetchedAtMillis = 1_700_000_000_000L,
                items = listOf(
                    RollcallHistoryItem("cached-1", "c1", "缓存课程", "雷达签到", "01-01 08:00", 1L, STATUS_SIGNED),
                ),
            ),
        )
    }

    @Test
    fun `renders cache first then overwrites with fresh verified data`() {
        seedCache()
        // 网络返回一条更新的记录（本人明细时间戳→已签）
        val transport = StubTransport(
            coursesPayload = """{"courses":[{"id":"c1","name":"课程一","semester":{"code":"2026-1"}}]}""",
            rollcallsPayload = """{"rollcalls":[{"id":"fresh-1","rollcall_time":"2026-07-01T08:00:00","is_radar":true}]}""",
            detailPayload = """{"student_rollcalls":[{"user_no":"u1","updated_at":"2026-07-01T08:05:00"}]}""",
        )
        val (state, _, _, _, _) = newHarness(transport)
        state.refreshHistory()
        assertTrue(await { !state.historyLoading })
        // SWR：缓存先上屏过（无法在单测里观察中间帧），最终以网络结果原位覆盖
        await { state.historyItems.isNotEmpty() }
        assertEquals(listOf("fresh-1"), state.historyItems.map { it.rollcallId })
        assertTrue(state.historyLoaded)
        assertEquals("", state.historyError)
        assertTrue(state.historyUpdatedAtMillis > 0L)
        assertEquals(listOf(STATUS_SIGNED), state.historyItems.map { it.ownStatus })
        // 刷新成功后新数据落盘缓存（下次进页秒开）
        val persisted = loadRollcallHistoryCache(rollcallHistoryCacheFile(app), "u1")
        assertEquals(listOf("fresh-1"), persisted?.items?.map { it.rollcallId })
    }

    @Test
    fun `network failure keeps cached items and only reports the error`() {
        seedCache()
        // 全链路 500：模拟断网/平台故障
        val transport = StubTransport(profileCode = 500)
        val (state, _, _, _, _) = newHarness(transport)
        state.refreshHistory()
        assertTrue(await { !state.historyLoading })
        // 断网不清缓存：旧数据仍在屏上，只报错误
        assertEquals(listOf("cached-1"), state.historyItems.map { it.rollcallId })
        assertTrue(state.historyError.isNotBlank())
        assertFalse(state.historyLoaded)
        // 磁盘缓存原样保留
        assertEquals("cached-1", loadRollcallHistoryCache(rollcallHistoryCacheFile(app), "u1")?.items?.single()?.rollcallId)
    }

    @Test
    fun `session expiry arms a one-shot history relogin request`() {
        val transport = StubTransport(profileCode = 401)
        val (state, _, _, pendingRetries, _) = newHarness(transport)
        state.refreshHistory()
        assertTrue(await { !state.historyLoading })
        assertEquals(listOf(ModuleReadRetry(ModuleReadRequest.ROLLCALL_HISTORY)), pendingRetries)
        assertTrue(state.historyError.contains("登录已过期"))
        assertTrue(state.historyItems.isEmpty())
    }

    @Test
    fun `held history gate blocks a second refresh`() {
        val transport = StubTransport(profileCode = 500)
        val (state, gate, _, _, _) = newHarness(transport)
        assertTrue(gate.tryStart("rollcall_history"))
        // 门被占：直接 false，不产生任何请求副作用
        assertFalse(state.refreshHistory())
        gate.finish("rollcall_history")
    }

    @Test
    fun `clearAll resets both blocks without touching other sections`() {
        seedCache()
        val transport = StubTransport(profileCode = 500)
        val (state, _, _, _, _) = newHarness(transport)
        // 先把历史区块填充为缓存内容（直接读盘语义等价于一次成功刷新后的残留）
        state.refreshHistory()
        assertTrue(await { !state.historyLoading })
        state.clearAll()
        assertTrue(state.historyItems.isEmpty() && !state.historyLoading && !state.historyLoaded)
        assertEquals("", state.historyError)
        assertEquals(0L, state.historyUpdatedAtMillis)
        assertTrue(state.events.isEmpty() && !state.loading)
    }

    @Test
    fun `logged out refresh is rejected before any request`() {
        val transport = StubTransport()
        val harness = newHarness(transport, loggedIn = false)
        assertFalse(harness.state.refreshHistory())
        assertTrue(harness.transport.requests.isEmpty())
    }
}
