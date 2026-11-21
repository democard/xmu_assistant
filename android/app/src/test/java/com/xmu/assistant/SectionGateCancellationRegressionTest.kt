package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SectionGateCancellationRegressionTest {
    private fun cancelledScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).apply { cancel() }

    private fun assertReleased(gate: RequestGate, key: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (gate.tryStart(key)) {
                gate.finish(key)
                return
            }
            Thread.sleep(5)
        }
        assertTrue("cancelled request must release $key even when its body never starts", gate.tryStart(key))
        gate.finish(key)
    }

    @Test
    fun `cancelled download does not retain the shared download admission`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val gate = RequestGate()
            val epoch = SessionEpoch()
            val course = CourseSummary("course", "Test course")
            val item = CoursewareUiItem("file", course.id, "activity", "Slides", "slides.pdf", "file")
            var selected: String? = null
            var busy = BusyStates.IDLE
            val cache = AcademicCacheSnapshot(
                coursewareByCourse = mapOf(course.id to listOf(item)),
                coursewareUpdatedAtMillis = mapOf(course.id to System.currentTimeMillis()),
            )
            val state = CoursewareSectionState(
                activity = controller.get(), requestGate = gate, sessionEpoch = epoch,
                sessionOwner = epoch.attachOwner(), scope = cancelledScope(), show = {},
                requireLogin = { true }, loggedIn = { true }, cookieHeader = { "session=fixture" },
                busy = { busy }, setBusy = { busy = it },
                selectedCourseId = { selected }, setSelectedCourseId = { selected = it },
                academicCache = { cache }, setAcademicCache = { 0L }, setAcademicCacheJson = {},
                isSelectedCourse = { it == selected }, setPendingSessionRetry = {},
                createCoursewareClient = { error("cancelled download must never construct a client") },
            )
            assertFalse(state.load(course))
            state.toggleSelectAll()
            assertEquals(setOf(item.id), state.selectedCoursewareIds)
            state.downloadSelected()
            assertReleased(gate, "downloads")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `cancelled history load does not retain the shared history admission`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val gate = RequestGate()
            val epoch = SessionEpoch()
            val state = RollcallSectionState(
                activity = controller.get(), requestGate = gate, sessionEpoch = epoch,
                sessionOwner = epoch.attachOwner(), scope = cancelledScope(), show = {},
                requireLogin = { true }, loggedIn = { true }, cookieHeader = { "session=fixture" },
                username = { "fixture" }, coursesProvider = { emptyList() }, setPendingSessionRetry = {},
                createHistoryClient = { error("cancelled history must never construct a client") },
            )
            assertTrue(state.refreshHistory())
            assertReleased(gate, "rollcall_history")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun withExam(cached: Boolean = false, action: (ExamSectionState, RequestGate) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        try {
            ExamCache.clear(activity)
            val gate = RequestGate()
            val epoch = SessionEpoch()
            var transition = true
            val term = "2025-2026-2"
            if (cached) ExamCache.saveTerm(activity, XmuTermExamSummary(term, term, emptyList(), emptyList()))
            val state = ExamSectionState(
                activity = activity, requestGate = gate, sessionEpoch = epoch,
                sessionOwner = epoch.attachOwner(), scope = cancelledScope(), show = {},
                loggedIn = { true }, cookieHeader = { "session=fixture" },
                username = { "fixture" }, password = { "synthetic" },
                accountTransitionInProgress = { transition }, scoreCookieHeader = { "JWSESSION=fixture" },
                setScoreCookieHeader = {}, reminderSettings = { ExamReminderSettings(false, 30) },
                selectedTermInitial = "",
                createExamClient = { _, _, _, _ -> error("cancelled exam must never construct a client") },
            )
            if (cached) {
                state.selectTerm(term)
                assertEquals(term, state.summary?.termCode)
            }
            transition = false
            action(state, gate)
        } finally {
            ExamCache.clear(activity)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `cancelled initial exam probe releases admission before handoff starts`() = withExam { state, gate ->
        state.refresh()
        assertReleased(gate, "exam")
    }

    @Test
    fun `cancelled cached exam check releases admission`() = withExam(cached = true) { state, gate ->
        state.checkChanges(state.selectedTerm)
        assertReleased(gate, "exam")
    }

    @Test
    fun `cancelled silent exam refresh releases admission`() = withExam { state, gate ->
        state.silentRefresh()
        assertReleased(gate, "exam")
    }
}
