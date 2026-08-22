package com.xmu.assistant

import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ExamSectionState（考试模块独立分块）行为锁定。
 * 只测不触发网络的分支：登录/在途守卫、gate 互斥、缓存复用、登出清理、Saver 快照；
 * 网络路径（refresh/checkChanges/silentRefresh 的请求线程）由 XmuExamClientTest 覆盖。
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

    @Test
    fun `saved state round trips selected term and manual flag`() {
        val (state, _, _) = newState(selectedTerm = "2025-2026-2", manuallySelected = true)
        assertEquals(listOf("2025-2026-2", true), state.savedState())
    }

    @Test
    fun `constructing with a selected term loads its cached summary`() {
        ExamCache.saveTerm(app, summaryFor("2025-2026-2"))
        val (state, _, _) = newState(selectedTerm = "2025-2026-2")
        assertEquals("2025-2026-2", state.summary?.termCode)
        assertEquals(1, state.summary?.exams?.size)
        assertEquals(listOf("2025-2026-2"), state.validTerms)
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
}
