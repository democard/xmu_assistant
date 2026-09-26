package com.xmu.assistant

import android.app.Activity
import android.content.Context
import android.os.Looper
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
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
class ScheduleCacheTimelineTest {
    private class Queue : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        fun drainNewestFirst() { while (tasks.isNotEmpty()) tasks.removeLast().run() }
    }

    private fun snapshot(term: String = "20311", title: String = "课程 A") = XmuScheduleSnapshot(
        termCode = term, updatedAtMillis = 100L,
        entries = listOf(XmuScheduleEntry(1, 1, 2, 800, 950, title, "教室", "教师", "1-18", term)),
    )

    private fun calendar() = XmuAcademicCalendar(
        "20311", "2031-2032", "第一学期", LocalDate.of(2031, 9, 1), LocalDate.of(2032, 1, 30), 20,
    )

    private class Harness(private val beforeWrite: () -> Unit = {}, private val writeSucceeds: Boolean = true) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val queue = Queue()
        private val scopes = mutableListOf<CoroutineScope>()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        var account = "account-a"
        private val prefs = activity.getSharedPreferences("maintenance3-schedule", Context.MODE_PRIVATE)
        val settings = AssistantSettings.forTest(activity, prefs)
        val state = newState(owner)

        fun newState(stateOwner: SessionOwner): ScheduleSectionState = ScheduleSectionState(
            activity, RequestGate(), epoch, stateOwner,
            CoroutineScope(SupervisorJob() + queue).also { scopes += it }, {}, requireLogin = { true }, loggedIn = { true },
            cookieHeader = { "session=$account" }, username = { account }, password = { "synthetic" },
            accountTransitionInProgress = { false }, setAccountTransitionInProgress = {}, busy = { "" }, setBusy = {},
            scoreCookieHeader = { "academic=synthetic" }, setScoreCookieHeader = {}, manualAcademicWeek = { 0 },
            setStartupSessionReady = {}, setPendingSessionRetry = {}, clearToast = {},
            fetchSchedule = ScheduleFetcher { _, _, _, _ -> error("network must not run") }, cacheDispatcher = queue,
            writeCacheSnapshot = { context, snapshot ->
                beforeWrite()
                if (writeSucceeds) saveScheduleSnapshotToFile(context, snapshot)
            },
        )

        fun cancelOldScope() { scopes.first().cancel() }

        fun readFileButHoldMain() {
            state.loadCachedSnapshotOnStartup(activity, settings) { true }
            queue.drain()
        }

        fun finishMain() {
            shadowOf(Looper.getMainLooper()).idle()
            queue.drain()
        }

        override fun close() {
            scopes.forEach { it.cancel() }
            queue.drain()
            shadowOf(Looper.getMainLooper()).idle()
            deleteScheduleSnapshotFile(activity)
            prefs.edit().clear().commit()
            ScheduleSectionState.updateProcessScheduleSnapshot(null)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `account switch discards the former account startup snapshot already waiting on Main`() {
        Harness().use { h ->
            saveScheduleSnapshotToFile(h.activity, snapshot())
            h.readFileButHoldMain()
            h.epoch.invalidate(h.owner)
            h.account = "account-b"
            h.state.clearAll()
            deleteScheduleSnapshotFile(h.activity)
            h.finishMain()

            assertTrue("account A entries returned after B became eligible", h.state.entries.isEmpty())
            assertEquals("", h.state.termCode)
            assertNull(ScheduleSectionState.currentProcessScheduleSnapshot())
        }
    }

    @Test
    fun `newer term applied while startup cache is pending stays selected`() {
        Harness().use { h ->
            saveScheduleSnapshotToFile(h.activity, snapshot())
            h.readFileButHoldMain()
            val newer = snapshot("20312", "课程 B")
            h.state.applySnapshot(newer)
            h.finishMain()

            assertEquals(newer, h.state.cache)
            assertEquals(newer, ScheduleSectionState.currentProcessScheduleSnapshot())
        }
    }

    @Test
    fun `failed legacy migration preserves its only durable snapshot`() {
        Harness(writeSucceeds = false).use { h ->
            val legacy = xmuScheduleSnapshotToJson(snapshot())
            h.settings.scheduleCacheJson = legacy
            h.state.loadCachedSnapshotOnStartup(h.activity, h.settings) { true }
            h.queue.drain()
            h.finishMain()

            assertEquals(snapshot(), h.state.cache)
            assertEquals(XmuScheduleSnapshot(), loadScheduleSnapshotFromFile(h.activity))
            assertEquals("failed file write must not discard the only cached copy", legacy, h.settings.scheduleCacheJson)
        }
    }

    @Test
    fun `manual week calibration is available to activity reconstruction before disk write`() {
        Harness().use { h ->
            h.state.applySnapshot(snapshot())
            h.state.setInferredCalendar("20311", calendar())

            assertEquals(calendar(), h.state.cache.inferredCalendars["20311"])
            assertEquals("recreated activity must receive the current calibration", h.state.cache,
                ScheduleSectionState.currentProcessScheduleSnapshot())
        }
    }

    @Test
    fun `returning to automatic weeks removes calibration from the reconstruction snapshot`() {
        Harness().use { h ->
            h.state.applySnapshot(snapshot().copy(inferredCalendars = mapOf("20311" to calendar())))
            h.state.removeInferredCalendar("20311")

            assertTrue(h.state.cache.inferredCalendars.isEmpty())
            assertEquals("recreated activity must not restore removed calibration", h.state.cache,
                ScheduleSectionState.currentProcessScheduleSnapshot())
        }
    }

    @Test
    fun `late manual calibration save cannot undo a newer automatic week selection on disk`() {
        Harness().use { h ->
            h.state.applySnapshot(snapshot())
            h.state.setInferredCalendar("20311", calendar())
            h.state.removeInferredCalendar("20311")
            h.queue.drainNewestFirst()

            val restored = loadScheduleSnapshotFromFile(h.activity)
            assertEquals("20311", restored.termCode)
            assertTrue("cold startup must keep the newer removal", restored.inferredCalendars.isEmpty())
        }
    }

    @Test
    fun `replacement activity clear waits for an old writer even after its scope was cancelled`() {
        assertReplacementClearWaits(migrateLegacy = false)
    }

    @Test
    fun `replacement waits for cancelled legacy migration without clearing new account legacy preferences`() {
        assertReplacementClearWaits(migrateLegacy = true)
    }

    private fun assertReplacementClearWaits(migrateLegacy: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }).use { h ->
            if (migrateLegacy) {
                h.settings.scheduleCacheJson = xmuScheduleSnapshotToJson(snapshot())
                h.state.loadCachedSnapshotOnStartup(h.activity, h.settings) { true }
            } else {
                h.state.applySnapshot(snapshot())
                h.state.setInferredCalendar("20311", calendar())
            }
            val workers = Executors.newFixedThreadPool(2)
            try {
                val writerThread = AtomicReference<Thread>()
                val clearingThread = AtomicReference<Thread>()
                val writing = workers.submit { writerThread.set(Thread.currentThread()); h.queue.drain() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.cancelOldScope()
                val replacement = h.newState(h.epoch.attachOwner())
                val newAccountLegacy = xmuScheduleSnapshotToJson(snapshot("20312", "新账号课程"))
                if (migrateLegacy) h.settings.scheduleCacheJson = newAccountLegacy
                val clearStarted = CountDownLatch(1)
                val clearing = workers.submit {
                    clearingThread.set(Thread.currentThread())
                    clearStarted.countDown()
                    replacement.clearAll()
                    deleteScheduleSnapshotFile(h.activity)
                }
                assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
                var blockedOnWriter = false
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!clearing.isDone && System.nanoTime() < deadline) {
                        val writerState = writerThread.get().state
                        val writerWaiting = writerState == Thread.State.WAITING || writerState == Thread.State.TIMED_WAITING
                        if (clearingThread.get().state == Thread.State.BLOCKED && writerWaiting) {
                            blockedOnWriter = true
                            break
                        }
                        Thread.yield()
                    }
                } finally { release.countDown() }
                writing.get(5, TimeUnit.SECONDS)
                clearing.get(5, TimeUnit.SECONDS)

                assertTrue("new state must wait for the old state writer's lock", blockedOnWriter)
                assertEquals(XmuScheduleSnapshot(), loadScheduleSnapshotFromFile(h.activity))
                assertNull(ScheduleSectionState.currentProcessScheduleSnapshot())
                if (migrateLegacy) assertEquals("old migration must not clear the new account preference",
                    newAccountLegacy, h.settings.scheduleCacheJson)
            } finally {
                release.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
