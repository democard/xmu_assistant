package com.xmu.assistant

import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamCacheInitializationLifecycleTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private fun cachedSummary(term: String) = XmuTermExamSummary(
        termCode = term, termLabel = term,
        exams = listOf(XmuExam("fixture-exam", "旧学期考试", "2026-01-01", "08:00-10:00", "教室", "线下", "期末")),
        unarranged = emptyList(),
    )

    private fun simulate(action: (ExamSectionState) -> Unit, verify: (ExamSectionState) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val io = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + io)
        val epoch = SessionEpoch()
        val networkCalls = AtomicInteger()
        ExamCache.saveTerm(activity, cachedSummary("2025-2026-1"))
        val state = ExamSectionState(
            activity = activity,
            requestGate = RequestGate(), sessionEpoch = epoch, sessionOwner = epoch.attachOwner(), scope = scope,
            show = {}, loggedIn = { false }, cookieHeader = { "" }, username = { "" }, password = { "" },
            accountTransitionInProgress = { false }, scoreCookieHeader = { "" }, setScoreCookieHeader = {},
            reminderSettings = { ExamReminderSettings(enabled = false) },
            createExamClient = { _, _, _, _ -> networkCalls.incrementAndGet(); error("network must not run") },
            selectedTermInitial = "2025-2026-1", cacheDispatcher = io,
        )
        try {
            // Let IO capture A, but deliberately hold its main-thread completion until after action.
            io.drain()
            action(state)
            shadowOf(Looper.getMainLooper()).idle()
            io.drain()
            verify(state)
            assertEquals(0, networkCalls.get())
        } finally {
            scope.cancel()
            io.drain()
            ExamCache.clear(activity)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `late initial cache from term A cannot appear under newly selected uncached term B`() {
        simulate(action = { it.selectTerm("2025-2026-2") }) { state ->
            assertEquals("2025-2026-2", state.selectedTerm)
            assertTrue(state.manuallySelected)
            assertNull("A must not populate B's empty summary", state.summary)
        }
    }

    @Test
    fun `late initial cache cannot resurrect a cleared account`() {
        simulate(action = { it.clearAll() }) { state ->
            assertEquals("", state.selectedTerm)
            assertTrue(state.validTerms.isEmpty())
            assertNull(state.summary)
        }
    }

    @Test
    fun `unchanged selected term accepts its initial cache`() {
        simulate(action = {}) { state ->
            assertEquals("2025-2026-1", state.selectedTerm)
            assertEquals(cachedSummary("2025-2026-1"), state.summary)
        }
    }
}
