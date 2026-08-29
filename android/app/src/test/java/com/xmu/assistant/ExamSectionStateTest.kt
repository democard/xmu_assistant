package com.xmu.assistant

import androidx.activity.ComponentActivity
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExamSectionState（考试模块独立分块）行为锁定。
 * 不触网络的分支：登录/在途守卫、gate 互斥、缓存复用、登出清理、Saver 快照；
 * 网络请求细节由 XmuExamClientTest 覆盖；silentRefresh 全链路（fake transport）
 * 见文件尾 A2 两用例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamSectionStateTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun summaryFor(term: String) = XmuTermExamSummary(
        termCode = term,
        termLabel = term,
        exams = listOf(
            XmuExam(
                id = "1",
                courseName = "高等数学A-2",
                date = "2026-07-01",
                timeRange = "08:00-10:00",
                room = "A306",
                mode = "线下",
                examName = "期末考试",
            ),
        ),
        unarranged = listOf(XmuExamUnarranged(courseName = "大学英语(四)")),
    )

    private fun newState(
        loggedIn: Boolean = false,
        cookieHeader: String = "",
        username: String = "",
        password: String = "",
        transitionInProgress: Boolean = false,
        selectedTerm: String = "",
        manuallySelected: Boolean = false,
    ): Triple<ExamSectionState, RequestGate, MutableList<String>> {
        val toasts = mutableListOf<String>()
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val state = ExamSectionState(
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = { toasts += it },
            loggedIn = { loggedIn },
            cookieHeader = { cookieHeader },
            username = { username },
            password = { password },
            accountTransitionInProgress = { transitionInProgress },
            // 存储依赖用函数式注入（真实 AssistantSettings 走 EncryptedSharedPreferences，
            // Robolectric 无 AndroidKeyStore；此处仅需 cookie 读写与提醒设置的读写语义）
            scoreCookieHeader = { "stored-cookie" },
            setScoreCookieHeader = {},
            reminderSettings = { ExamReminderSettings(enabled = true, advanceMinutes = 30) },
            selectedTermInitial = selectedTerm,
            manuallySelectedInitial = manuallySelected,
        )
        return Triple(state, gate, toasts)
    }

    /** 构造期缓存回填已改异步（IO 线程读盘 → 主线程写入）：
     *  轮询驱动 Robolectric 主 looper 直到回填落地（或超时失败）。 */
    private fun awaitInitialCacheLoad(state: ExamSectionState, timeoutMillis: Long = 5000) {
        val looper = Looper.getMainLooper()
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(looper).idle()
            if (state.summary != null && state.validTerms.isNotEmpty()) return
            Thread.sleep(20)
        }
        shadowOf(looper).idle()
    }

    @Test
    fun `saved state round trips selected term and manual flag`() {
        val (state, _, _) = newState(selectedTerm = "2025-2026-2", manuallySelected = true)
        assertEquals(listOf("2025-2026-2", true), state.savedState())
    }

    @Test
    fun `constructing with a selected term loads its cached summary`() {
        ExamCache.saveTerm(app, summaryFor("2025-2026-2"))
        val (state, _, _) = newState(selectedTerm = "2025-2026-2")
        awaitInitialCacheLoad(state)
        assertEquals("2025-2026-2", state.summary?.termCode)
        assertEquals(1, state.summary?.exams?.size)
        assertEquals(listOf("2025-2026-2"), state.validTerms)
    }

    @Test
    fun `late cache backfill is dropped after clear all`() {
        ExamCache.saveTerm(app, summaryFor("2025-2026-2"))
        val (state, _, _) = newState(selectedTerm = "2025-2026-2")
        // 登出清理先于异步回填落地：世代守卫必须丢弃晚到的回填，防旧缓存回灌
        state.clearAll()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("backfill must not resurrect cleared state", state.summary)
        assertTrue(state.validTerms.isEmpty())
        assertEquals("", state.selectedTerm)
    }

    @Test
    fun `select term with cache reuses cached summary and stays quiet while logged out`() {
        val cached = summaryFor("2025-2026-2")
        ExamCache.saveTerm(app, cached)
        val (state, _, toasts) = newState()
        state.selectTerm("2025-2026-2")
        assertEquals("2025-2026-2", state.selectedTerm)
        assertTrue(state.manuallySelected)
        assertEquals(cached, state.summary)
        assertTrue("logged-out select must not toast or refresh", toasts.isEmpty())
        assertEquals("", state.refreshError)
    }

    @Test
    fun `select term without cache defers while transition is in progress`() {
        val (state, _, toasts) = newState(transitionInProgress = true)
        state.selectTerm("2025-2026-2")
        assertTrue(toasts.contains("登录处理中，请稍候"))
        assertNull("transition in progress must not clear the shown data", state.summary)
    }

    @Test
    fun `refresh requires an active login`() {
        val (state, _, toasts) = newState()
        state.refresh()
        assertTrue(toasts.contains("请先登录"))
    }

    @Test
    fun `refresh shows busy when the exam gate is held`() {
        val (state, gate, toasts) = newState(
            loggedIn = true,
            cookieHeader = "session=abc",
            username = "12320011234567",
            password = "secret",
        )
        assertTrue(gate.tryStart("exam"))
        state.refresh()
        assertTrue(toasts.contains("正在刷新，请稍候"))
        gate.finish("exam")
    }

    @Test
    fun `clear all resets state cache and reminder plans`() {
        ExamCache.saveTerm(app, summaryFor("2025-2026-2"))
        val (state, _, _) = newState(selectedTerm = "2025-2026-2")
        state.summary?.let { assertEquals("2025-2026-2", it.termCode) }
        state.clearAll()
        assertNull(state.summary)
        assertEquals("", state.selectedTerm)
        assertTrue(state.validTerms.isEmpty())
        assertTrue(!state.loading && state.refreshError.isEmpty() && !state.autoUpdated)
        assertNull("clearAll must drop the on-disk exam cache", ExamCache.loadTerm(app, "2025-2026-2"))
    }

    @Test
    fun `reschedule reminders defers while the reminder gate is held`() {
        val (state, gate, _) = newState()
        assertTrue(gate.tryStart("exam-reminder"))
        // gate 被占：直接返回，不启动重排线程（无副作用、无异常）
        state.rescheduleReminders()
        gate.finish("exam-reminder")
    }

    @Test
    fun `ensure academic session guards login transition and returns stored cookie`() {
        val (state, _, _) = newState()
        // 正常路径：直接返回存储的教务 cookie（不再打开课表应用 appShow——见坑 27）
        assertEquals("stored-cookie", state.ensureAcademicSession())
        // 登录/恢复在途：拒绝（风控红线：不并发打两个身份域）
        val inTransition = newState(transitionInProgress = true).first
        assertTrue(runCatching { inTransition.ensureAcademicSession() }.exceptionOrNull() is ExamLoginInProgressException)
    }

    @Test
    fun `check changes stays quiet while a login transition is in progress`() {
        val cached = summaryFor("2025-2026-2")
        ExamCache.saveTerm(app, cached)
        val (state, _, toasts) = newState(loggedIn = true, cookieHeader = "session=abc", transitionInProgress = true)
        state.selectTerm("2025-2026-2")
        // 有缓存分支会进入 checkChanges，但 transition 在途时静默跳过（风控红线：不并发打两个身份域）
        assertEquals(cached, state.summary)
        assertTrue(toasts.isEmpty())
    }

    // ===== A2：silentRefresh 行为测试（fake transport 全链路，范式照 ScoreSectionStateTest） =====

    /** 静默刷新 fake transport：学期列表/考试/未安排载荷；blockFirstRequest 时首请求阻塞，
     *  供登出竞态用例在请求在途期间翻转登录态。 */
    private class StubSilentRefreshTransport(
        private val termCodes: List<String>,
        private val termData: Map<String, String>,
    ) : QueryHttpTransport {
        val requests = mutableListOf<QueryHttpRequest>()
        val firstRequestArrived = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val blockedOnce = AtomicBoolean(false)
        var blockFirstRequest = false

        /** 考试/未安排两端点的状态码注入：500 模拟「考试安排接口暂时不可用」失败路径。 */
        var termDataStatusCode = 200

        fun unblock() = release.countDown()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            synchronized(requests) { requests += request }
            if (blockFirstRequest && blockedOnce.compareAndSet(false, true)) {
                firstRequestArrived.countDown()
                release.await()
            }
            return when {
                // 应用入口（index.do）真实建模为 200（应用会话已打开）
                request.url.contains("index.do") -> respond(200, "<html>app</html>", request.url)
                request.url.endsWith("/xnxqcx.do") -> {
                    val rows = termCodes.joinToString(",") { """{"DM":"$it"}""" }
                    respond(200, """{"code":"0","datas":{"xnxqcx":{"totalSize":${termCodes.size},"rows":[$rows]}}}""", request.url)
                }
                request.url.endsWith("/cxxsksap.do") -> respond(termDataStatusCode, termData[decodeTerm(request.body)] ?: emptyBothBody(), request.url)
                request.url.endsWith("/cxyxkwapkwdkc.do") ->
                    respond(termDataStatusCode, termData["${decodeTerm(request.body)}-unarranged"] ?: emptyBothBody(), request.url)
                else -> respond(200, "", request.url)
            }
        }

        private fun decodeTerm(body: String): String =
            Regex("""XNXQDM=([^&]+)""").find(body)?.groupValues?.get(1)?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: ""

        private fun respond(code: Int, body: String, url: String) = QueryHttpResponse(
            url = url,
            code = code,
            location = null,
            body = body,
            headers = emptyMap(),
        )

        companion object {
            fun emptyBothBody(): String =
                """{"code":"0","datas":{"cxxsksap":{"totalSize":0,"rows":[]},"cxyxkwapkwdkc":{"totalSize":0,"rows":[]}}}"""
        }
    }

    private fun examRowsBody(vararg names: String): String {
        val rows = names.mapIndexed { index, name ->
            """{"KSRWID":"id-$index","KCM":"$name","KSRQ":"2026-06-14","KSSJMS":"2026-06-14 08:00-10:00(星期日)","JASMC":"A306","KSXS_DISPLAY":"线下","KSMC":"期末考试"}"""
        }.joinToString(",")
        return """{"code":"0","datas":{"cxxsksap":{"totalSize":${names.size},"rows":[$rows]}}}"""
    }

    private fun unarrangedRowsBody(vararg names: String): String {
        val rows = names.map { """{"KCM":"$it"}""" }.joinToString(",")
        return """{"code":"0","datas":{"cxxsksap":{"totalSize":0,"rows":[]},"cxyxkwapkwdkc":{"totalSize":${names.size},"rows":[$rows]}}}"""
    }

    /** 登出竞态用例需要 epoch/owner 复现真实登出链路（invalidate + clearAll）。 */
    private data class SilentRefreshHarness(
        val state: ExamSectionState,
        val gate: RequestGate,
        val epoch: SessionEpoch,
        val owner: SessionOwner,
    )

    private fun newSilentRefreshHarness(
        transport: StubSilentRefreshTransport,
        loggedInNow: AtomicBoolean,
        selectedTerm: String = "",
    ): SilentRefreshHarness {
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val state = ExamSectionState(
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = {},
            showWarning = {},
            showError = {},
            loggedIn = { loggedInNow.get() },
            cookieHeader = { "session=fixture" },
            username = { "12320011234567" },
            password = { "fixture-password" },
            accountTransitionInProgress = { false },
            scoreCookieHeader = { "stored-cookie" },
            setScoreCookieHeader = {},
            reminderSettings = { ExamReminderSettings(enabled = true, advanceMinutes = 30) },
            selectedTermInitial = selectedTerm,
            createExamClient = { accountUsername, accountPassword, cookie, mayRelogin ->
                XmuExamClient(cookie, accountUsername, accountPassword, mayRelogin = mayRelogin, transport = transport)
            },
        )
        return SilentRefreshHarness(state, gate, epoch, owner)
    }

    /** 轮询驱动 Robolectric 主 looper（withContext(Main) 段落地）直至条件成立或超时。 */
    private fun awaitCondition(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
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
    fun `silent refresh backfills empty state and releases loading`() {
        val transport = StubSilentRefreshTransport(
            termCodes = listOf("2025-2026-1", "2025-2026-2", "2025-2026-3", "2026-2027-1"),
            termData = mapOf(
                "2025-2026-2" to examRowsBody("数据结构"),
                "2026-2027-1-unarranged" to unarrangedRowsBody("新学期课程A"),
            ),
        )
        val (state, gate) = newSilentRefreshHarness(transport, AtomicBoolean(true))
        state.silentRefresh()
        assertTrue("无缓存进入考试页应显示加载", state.loading)
        assertTrue("静默回填未落地或 loading 未释放", awaitCondition { state.summary != null && !state.loading })
        assertTrue(state.validTerms.isNotEmpty())
        // 未手动选择时默认校正到最近有效学期（列表第一个）；学期窗随当天日期变化，只锁相对关系
        assertEquals(state.validTerms.first(), state.summary?.termCode)
        assertEquals("", state.refreshError)
        assertTrue("exam 门未释放", awaitCondition { gate.tryStart("exam").also { ok -> if (ok) gate.finish("exam") } })
    }

    @Test
    fun `logout race does not write exam cache from in-flight probe`() {
        // 登出竞态的缓存面（2026-08-30 体检 P2）：clearAll 清 ExamCache 后，
        // 在途 silentRefresh 的 IO 侧晚到写（markProbed + 逐学期 saveTerm）
        // 必须被世代守卫拦截，不得把旧账号考试数据回填进明文缓存
        val transport = StubSilentRefreshTransport(
            termCodes = listOf("2025-2026-1", "2025-2026-2"),
            termData = mapOf("2025-2026-2" to examRowsBody("数据结构")),
        ).apply { blockFirstRequest = true }
        val harness = newSilentRefreshHarness(transport, AtomicBoolean(true))
        harness.state.silentRefresh()
        assertTrue("首请求未发出", transport.firstRequestArrived.await(5, TimeUnit.SECONDS))
        // 复现真实登出链路：epoch 失效（onLogout: sessionEpoch.invalidate）+ 清理面
        harness.epoch.invalidate(harness.owner)
        harness.state.clearAll()
        transport.unblock()
        assertTrue(
            "silentRefresh 协程未收尾",
            awaitCondition { harness.gate.tryStart("exam").also { ok -> if (ok) harness.gate.finish("exam") } },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("登出后晚到写不得回填考试缓存", ExamCache.loadTerm(app, "2025-2026-2"))
        assertTrue("重探时间戳同样不得残留", ExamCache.shouldReProbe(app))
    }

    @Test
    fun `logout race drops backfill and recovers loading via clearLoadingState`() {
        val transport = StubSilentRefreshTransport(
            termCodes = listOf("2025-2026-1", "2025-2026-2", "2025-2026-3", "2026-2027-1"),
            termData = mapOf("2025-2026-2" to examRowsBody("数据结构")),
        ).apply { blockFirstRequest = true }
        val loggedInNow = AtomicBoolean(true)
        val (state, gate) = newSilentRefreshHarness(transport, loggedInNow)
        state.silentRefresh()
        assertTrue(state.loading)
        // 首个请求在途时登出（翻转登录态）：世代守卫必须丢弃晚到的回填（防旧账号数据回灌）
        assertTrue("首请求未发出", transport.firstRequestArrived.await(5, TimeUnit.SECONDS))
        loggedInNow.set(false)
        transport.unblock()
        // 门重新可用 = 协程完整收尾（Main 回调段与外层 NonCancellable finish 均已执行）
        assertTrue("silentRefresh 协程未收尾", awaitCondition { gate.tryStart("exam").also { ok -> if (ok) gate.finish("exam") } })
        assertNull("登出后晚到回填必须被丢弃", state.summary)
        // A2 统一后：守卫拒绝路径不再无条件复位 loading，直至兜底通道执行（唯一释放通道）
        assertTrue("登出竞态下 loading 应保持（由 clearLoadingState 兜底释放）", state.loading)
        state.clearLoadingState()
        assertFalse(state.loading)
    }

    @Test
    fun `check changes keeps cached data when the fetch fails`() {
        // 回归：checkChanges 曾把请求失败折叠出的 null 当「教务清空该学期」处理，
        // 弱网/5xx 下会清空显示与缓存、撤掉真实考试提醒。失败必须静默保留数据。
        ExamCache.saveTerm(app, summaryFor("2025-2026-2"))
        val transport = StubSilentRefreshTransport(
            termCodes = listOf("2025-2026-2"),
            termData = emptyMap(),
        ).apply { termDataStatusCode = 500 }
        val (state, gate) = newSilentRefreshHarness(transport, AtomicBoolean(true), selectedTerm = "2025-2026-2")
        awaitInitialCacheLoad(state)
        state.checkChanges("2025-2026-2")
        // 门重新可用 = checkChanges 协程完整收尾（Main 回调段已执行）
        assertTrue("checkChanges 协程未收尾", awaitCondition { gate.tryStart("exam").also { ok -> if (ok) gate.finish("exam") } })
        assertEquals("失败不得清空显示", 1, state.summary?.exams?.size)
        assertEquals("失败不得清空缓存", 1, ExamCache.loadTerm(app, "2025-2026-2")?.exams?.size)
        assertFalse("失败不得触发「检测到变化」横幅", state.autoUpdated)
    }

    @Test
    fun `check changes clears cached data only on confirmed empty term`() {
        // 与失败用例形成判别对照：成功返回真空（两端点 rows 空）才允许走清空分支
        ExamCache.saveTerm(app, summaryFor("2025-2026-2"))
        val transport = StubSilentRefreshTransport(
            termCodes = listOf("2025-2026-2"),
            termData = emptyMap(),
        )
        val (state, _) = newSilentRefreshHarness(transport, AtomicBoolean(true), selectedTerm = "2025-2026-2")
        awaitInitialCacheLoad(state)
        state.checkChanges("2025-2026-2")
        assertTrue("确认真空未走清空分支", awaitCondition { state.summary?.exams?.isEmpty() == true && state.autoUpdated })
        assertEquals(0, ExamCache.loadTerm(app, "2025-2026-2")?.exams?.size)
    }
}
