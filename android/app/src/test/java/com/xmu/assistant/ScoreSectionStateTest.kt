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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 成绩模块行为测试（E2，六 SectionState 中原唯一零行为测试者）：
 * fake transport 全链路——成功读取+落持久化快照；服务端 5xx 只报错不触发续登
 * （风控红线：非鉴权失败绝不 CAS 重登）；互斥门；账号切换/登出前置拒绝。
 * 范式照 RollcallHistorySectionStateTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScoreSectionStateTest {

    private class StubScoreTransport(
        private val termsCode: Int = 200,
        private val rowsCode: Int = 200,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            val url = request.url
            return when {
                "cxycjdxnxq.do" in url -> respond(termsCode, TERMS_PAYLOAD)
                "xscjcx.do" in url -> respond(rowsCode, ROWS_PAYLOAD)
                else -> respond(200, "{}")
            }
        }

        private fun respond(code: Int, body: String) = QueryHttpResponse(
            url = "https://jw.xmu.edu.cn/jwapp/sys/cjcx/modules/cjcx/fake",
            code = code,
            location = null,
            body = body,
            headers = emptyMap(),
        )

        companion object {
            val TERMS_PAYLOAD =
                """{"datas":{"cxycjdxnxq":{"rows":[{"XNXQDM":"20251","XNXQDM_DISPLAY":"2025-2026 第一学期"}]}}}"""
            val ROWS_PAYLOAD =
                """{"datas":{"xscjcx":{"rows":[{"KCH":"CS101","KCM":"数据结构","KXH":"01","XF":"4","ZCJ":"92","XFJD":"3.7","DJCJLXDM_DISPLAY":"百分制"}]}}}"""
        }
    }

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

    private class Harness(
        val state: ScoreSectionState,
        val gate: RequestGate,
        val transport: StubScoreTransport,
        val persistedJson: MutableList<String>,
        val persistedAt: MutableList<Long>,
        val writtenCookies: MutableList<String>,
    )

    private fun newHarness(
        transport: StubScoreTransport,
        loggedIn: Boolean = true,
        transitionInProgress: Boolean = false,
    ): Harness {
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val persistedJson = mutableListOf<String>()
        val persistedAt = mutableListOf<Long>()
        val writtenCookies = mutableListOf<String>()
        val state = ScoreSectionState(
            activity = activity,
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = {},
            loggedIn = { loggedIn },
            cookieHeader = { "session=fixture" },
            username = { "u1" },
            password = { "p1" },
            accountTransitionInProgress = { transitionInProgress },
            scoreCookieHeader = { "session=fixture" },
            setScoreCookieHeader = { writtenCookies += it },
            setScoreRecordsJson = { persistedJson += it },
            setScoreUpdatedAtMillisPref = { persistedAt += it },
            createScoreClient = { accountUsername, accountPassword, cookie, mayRelogin ->
                assertEquals("u1", accountUsername)
                XmuScoreAutoQueryClient(accountUsername, accountPassword, cookie, transport, mayRelogin = mayRelogin)
            },
        )
        return Harness(state, gate, transport, persistedJson, persistedAt, writtenCookies)
    }

    @Test
    fun `fetch fills records and persists snapshot under live epoch`() {
        val harness = newHarness(StubScoreTransport())
        assertTrue(harness.state.refresh())
        assertTrue("加载态未复位", await { !harness.state.loading })
        assertEquals(1, harness.state.scoreRecords.size)
        val record = harness.state.scoreRecords.single()
        assertEquals("CS101", record.courseCode)
        assertEquals("数据结构", record.courseName)
        assertEquals("2025-2026 第一学期", record.term)
        assertEquals(92.0, record.score!!, 0.001)
        assertEquals("", harness.state.refreshError)
        assertTrue(harness.state.updatedAtMillis > 0L)
        // 持久化快照（后台协程 + 世代校验后写入）：JSON 含解析出的课程码，时间戳同步落 prefs
        assertTrue("持久化 JSON 未写入", await { harness.persistedJson.isNotEmpty() })
        assertTrue(harness.persistedJson.single().contains("CS101"))
        assertTrue(harness.persistedAt.single() > 0L)
    }

    @Test
    fun `server 5xx surfaces failure without relogin or records`() {
        // 风控红线：非鉴权失败（5xx=ScoreJsonFormatException）绝不触发 CAS 续登
        val harness = newHarness(StubScoreTransport(termsCode = 500))
        assertTrue(harness.state.refresh())
        assertTrue(await { !harness.state.loading })
        assertTrue(harness.state.refreshError.isNotBlank())
        assertTrue(harness.state.scoreRecords.isEmpty())
        // 未落任何持久化（失败路径不写成绩 JSON）
        Thread.sleep(150)
        assertTrue(harness.persistedJson.isEmpty())
    }

    @Test
    fun `held scores gate blocks a second refresh`() {
        val harness = newHarness(StubScoreTransport())
        assertTrue(harness.gate.tryStart("scores"))
        assertFalse(harness.state.refresh())
        harness.gate.finish("scores")
    }

    @Test
    fun `account transition rejects refresh before any request`() {
        val harness = newHarness(StubScoreTransport(), transitionInProgress = true)
        assertFalse(harness.state.refresh())
        assertTrue(harness.transport.requests.isEmpty())
    }

    @Test
    fun `logged out refresh is rejected before any request`() {
        val harness = newHarness(StubScoreTransport(), loggedIn = false)
        assertFalse(harness.state.refresh())
        assertTrue(harness.transport.requests.isEmpty())
    }
}
