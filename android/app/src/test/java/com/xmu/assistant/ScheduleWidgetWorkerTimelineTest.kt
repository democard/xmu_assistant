package com.xmu.assistant

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.work.ListenableWorker.Result
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class ScheduleWidgetWorkerTimelineTest {
    private class Queue : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private class Harness(
        val blockFetch: Boolean = false,
        val blockWrite: Boolean = false,
        val blockCookie: Boolean = false,
        val blockWidget: Boolean = false,
        val blockSnapshotRead: Boolean = false,
    ) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val context = controller.get()
        private val prefs = context.getSharedPreferences("maintenance4-widget-worker", Context.MODE_PRIVATE)
        val settings = AssistantSettings.forTest(context, prefs)
        val epoch = ProcessSessionEpoch.instance
        val owner = epoch.attachOwner()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + Dispatchers.IO)
        private val fileQueue = Queue()
        val fetchEntered = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val cookieEntered = CountDownLatch(1)
        val releaseCookie = CountDownLatch(1)
        val widgetEntered = CountDownLatch(1)
        val releaseWidget = CountDownLatch(1)
        val snapshotReadEntered = CountDownLatch(1)
        val releaseSnapshotRead = CountDownLatch(1)
        val pauseManualSessionRead = AtomicBoolean(false)
        val manualSessionReadEntered = CountDownLatch(1)
        val releaseManualSessionRead = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>()
        val mayReloginAfterPause = AtomicBoolean()
        val fetches = AtomicInteger()
        val widgetWrites = AtomicInteger()

        private fun fetched(title: String, term: String) = ScheduleRefreshResult(
            "academic=$title", term,
            listOf(XmuScheduleEntry(LocalDate.now().dayOfWeek.value, 1, 2, 800, 950, title, "教室", "教师", "1-18", term)),
            null,
        )

        init {
            AppForegroundTracker.foreground = false
            settings.username = "synthetic-a"
            settings.password = "synthetic-password"
            settings.cookieHeader = "session=identical"
            settings.scoreCookieHeader = "academic=initial"
            settings.widgetEnabled = true
            settings.markLoginSucceeded()
            deleteScheduleSnapshotFile(context)
            ScheduleWidgetData.clear(context)
            ScheduleSectionState.updateProcessScheduleSnapshot(null)
        }

        private fun newFrontState(initial: XmuScheduleSnapshot = XmuScheduleSnapshot()) = ScheduleSectionState(
            context, RequestGate(), epoch, owner, scope, {}, requireLogin = { true }, loggedIn = { true },
            cookieHeader = {
                if (pauseManualSessionRead.get()) {
                    manualSessionReadEntered.countDown()
                    check(releaseManualSessionRead.await(5, TimeUnit.SECONDS))
                }
                settings.cookieHeader
            }, username = { settings.username }, password = { settings.password },
            accountTransitionInProgress = { false }, setAccountTransitionInProgress = {}, busy = { "" }, setBusy = {},
            scoreCookieHeader = { settings.scoreCookieHeader }, setScoreCookieHeader = { settings.scoreCookieHeader = it },
            manualAcademicWeek = { 1 }, setStartupSessionReady = {}, setPendingSessionRetry = {}, clearToast = {},
            fetchSchedule = ScheduleFetcher { _, _, _, _ -> fetched("foreground", "20312") },
            snapshotInitial = initial, cacheDispatcher = fileQueue,
        )

        val frontState = newFrontState()

        fun recreateState(): ScheduleSectionState {
            val state = newFrontState(ScheduleSectionState.currentProcessScheduleSnapshot() ?: XmuScheduleSnapshot())
            state.loadCachedSnapshotOnStartup(context, settings) { true }
            fileQueue.drain()
            shadowOf(Looper.getMainLooper()).idle()
            fileQueue.drain()
            return state
        }

        fun drainPersistence() {
            fileQueue.drain()
            shadowOf(Looper.getMainLooper()).idle()
            fileQueue.drain()
        }

        fun cancelQueuedPersistence() {
            parent.children.toList().forEach { it.cancel() }
            drainPersistence()
        }

        private val runner = ScheduleWidgetSyncRun(
            context, settingsFactory = { settings },
            fetchSchedule = ScheduleFetcher { _, _, _, mayRelogin ->
                fetches.incrementAndGet()
                fetchEntered.countDown()
                if (blockFetch) check(releaseFetch.await(5, TimeUnit.SECONDS))
                mayReloginAfterPause.set(mayRelogin())
                fetched("background", "20311")
            },
            writeSnapshot = { snapshot ->
                writerThread.set(Thread.currentThread())
                writeEntered.countDown()
                if (blockWrite) check(releaseWrite.await(5, TimeUnit.SECONDS))
                saveScheduleSnapshotToFile(context, snapshot)
            },
            writeCookie = { target, cookie ->
                writerThread.set(Thread.currentThread())
                cookieEntered.countDown()
                if (blockCookie) check(releaseCookie.await(5, TimeUnit.SECONDS))
                target.scoreCookieHeader = cookie
            },
            updateWidget = { snapshot, _ ->
                widgetEntered.countDown()
                if (blockWidget) check(releaseWidget.await(5, TimeUnit.SECONDS))
                widgetWrites.incrementAndGet()
                ScheduleWidgetData.save(context, ScheduleWidgetSnapshot(
                    LocalDate.now().dayOfWeek.value, 1, snapshot.termCode,
                    snapshot.entries.map { ScheduleWidgetCourse(it.courseName, it.startTime, it.endTime, it.startSection, it.endSection, it.room) },
                ))
            },
            readSnapshot = {
                val snapshot = loadScheduleSnapshotFromFile(context)
                snapshotReadEntered.countDown()
                if (blockSnapshotRead) check(releaseSnapshotRead.await(5, TimeUnit.SECONDS))
                snapshot
            },
        )

        fun start(): Deferred<Result> = scope.async { runner.execute() }

        fun finish(work: Deferred<Result>): Result = runBlocking { withTimeout(10_000) { work.await() } }

        fun join(work: Deferred<Result>) = runBlocking { withTimeout(10_000) { work.join() } }

        fun refreshForeground() {
            val previous = parent.children.toSet()
            assertTrue(frontState.refresh())
            val request = parent.children.single { it !in previous }
            val done = CountDownLatch(1)
            request.invokeOnCompletion { done.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (done.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                done.await(1, TimeUnit.MILLISECONDS)
            }
            assertEquals(0L, done.count)
            fileQueue.drain()
        }

        fun clearAccount(beforeScheduleClear: () -> Unit = {}) {
            epoch.invalidate(owner)
            settings.markUserLoggedOut()
            // Match the existing UI order: Cookie is cleared before the schedule barrier.
            settings.clearSession()
            beforeScheduleClear()
            frontState.clearAll()
            settings.scoreCookieHeader = ""
            deleteScheduleSnapshotFile(context)
            ScheduleWidgetData.clear(context)
        }

        fun relogin(username: String) {
            clearAccount()
            val attempt = requireNotNull(epoch.beginLoginAttempt(owner, username, "synthetic-password"))
            assertTrue(epoch.completeLogin(attempt, username, "synthetic-password"))
            settings.username = username
            settings.cookieHeader = "session=identical"
            settings.scoreCookieHeader = "academic=new-session"
            settings.markLoginSucceeded()
        }

        override fun close() {
            releaseFetch.countDown()
            releaseWrite.countDown()
            releaseCookie.countDown()
            releaseWidget.countDown()
            releaseSnapshotRead.countDown()
            releaseManualSessionRead.countDown()
            scope.cancel()
            fileQueue.drain()
            shadowOf(Looper.getMainLooper()).idle()
            epoch.detachOwner(owner)
            AppForegroundTracker.foreground = false
            deleteScheduleSnapshotFile(context)
            ScheduleWidgetData.clear(context)
            ScheduleSectionState.updateProcessScheduleSnapshot(null)
            prefs.edit().clear().commit()
            context.getSharedPreferences(AssistantSettings.WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `unchanged background session saves schedule cookie and widget without taking activity ownership`() {
        Harness().use { h ->
            assertEquals(Result.success(), h.finish(h.start()))
            assertEquals("background", loadScheduleSnapshotFromFile(h.context).entries.single().courseName)
            assertEquals("academic=background", h.settings.scoreCookieHeader)
            assertEquals("background", ScheduleWidgetData.load(h.context)?.courses?.single()?.courseName)
            assertTrue(h.epoch.isOwnerActive(h.owner))
        }
    }

    @Test
    fun `completed background synchronization is visible when an activity reconstructs from the process cache`() {
        Harness().use { h ->
            val old = XmuScheduleSnapshot(
                entries = listOf(XmuScheduleEntry(LocalDate.now().dayOfWeek.value, 1, 2, 800, 950,
                    "previous", "旧教室", "教师", "1-18", "20311")),
                termCode = "20311", updatedAtMillis = 100L,
            )
            h.frontState.applySnapshot(old)
            assertEquals(Result.success(), h.finish(h.start()))
            val latest = loadScheduleSnapshotFromFile(h.context)
            assertEquals("background", latest.entries.single().courseName)

            val recreated = h.recreateState()
            assertEquals("reconstruction must not prefer an obsolete process snapshot over the completed worker", latest, recreated.cache)
            assertEquals(latest, ScheduleSectionState.currentProcessScheduleSnapshot())
        }
    }

    @Test
    fun `startup widget synchronization after reconstruction cannot roll back a background refresh`() {
        Harness().use { h ->
            h.frontState.applySnapshot(XmuScheduleSnapshot(
                entries = listOf(XmuScheduleEntry(LocalDate.now().dayOfWeek.value, 1, 2, 800, 950,
                    "previous", "旧教室", "教师", "1-18", "20311")),
                termCode = "20311", updatedAtMillis = 100L,
            ))
            assertEquals(Result.success(), h.finish(h.start()))
            assertEquals("background", ScheduleWidgetData.load(h.context)?.courses?.single()?.courseName)

            val recreated = h.recreateState()
            syncScheduleWidget(h.context, recreated.entries, recreated.termCode,
                inferredCalendar = recreated.cache.inferredCalendars[recreated.termCode], manualWeek = 1)

            assertEquals("startup must retain the newer background projection", "background",
                ScheduleWidgetData.load(h.context)?.courses?.single()?.courseName)
        }
    }

    private fun manualCalendar(): XmuAcademicCalendar {
        val monday = LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value - 1L)
        return XmuAcademicCalendar("20311", "2031-2032", "第一学期", monday.minusWeeks(2), monday.plusWeeks(16))
    }

    private fun previousSnapshot(calendar: XmuAcademicCalendar? = null) = XmuScheduleSnapshot(
        entries = listOf(XmuScheduleEntry(LocalDate.now().dayOfWeek.value, 1, 2, 800, 950,
            "previous", "旧教室", "教师", "1-18", "20311")),
        termCode = "20311", updatedAtMillis = 100L,
        inferredCalendars = if (calendar == null) emptyMap() else mapOf("20311" to calendar),
    )

    @Test
    fun `manual calendar publish cannot be replaced while its persistence entry captures the session`() {
        listOf(false, true).forEach { restoreAutomatic ->
            Harness(blockWrite = true).use { h ->
                val calendar = manualCalendar()
                h.frontState.applySnapshot(previousSnapshot(if (restoreAutomatic) calendar else null))
                val work = h.start()
                assertTrue(h.writeEntered.await(5, TimeUnit.SECONDS))
                h.pauseManualSessionRead.set(true)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val releaser = executor.submit<Result> {
                        assertTrue("manual persistence must reach the actual session-read seam",
                            h.manualSessionReadEntered.await(5, TimeUnit.SECONDS))
                        h.releaseWrite.countDown()
                        val result = h.finish(work)
                        h.releaseManualSessionRead.countDown()
                        result
                    }
                    if (restoreAutomatic) h.frontState.removeInferredCalendar("20311")
                    else h.frontState.setInferredCalendar("20311", calendar)
                    assertEquals(Result.success(), releaser.get(5, TimeUnit.SECONDS))

                    assertEquals("worker must not replace a manually accepted calendar snapshot",
                        h.frontState.cache, ScheduleSectionState.currentProcessScheduleSnapshot())
                    assertEquals(h.frontState.cache, h.recreateState().cache)
                } finally {
                    h.releaseManualSessionRead.countDown()
                    h.releaseWrite.countDown()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `manual widget projection stays latest when an accepted worker widget callback is paused`() {
        Harness(blockWidget = true).use { h ->
            h.frontState.applySnapshot(previousSnapshot())
            val work = h.start()
            assertTrue("worker must reach its real local widget callback", h.widgetEntered.await(5, TimeUnit.SECONDS))
            val expected = AtomicReference<ScheduleWidgetSnapshot?>()
            val manualStarted = CountDownLatch(1)
            val manualFinished = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val releaser = executor.submit<Result> {
                    assertTrue(manualStarted.await(5, TimeUnit.SECONDS))
                    // The old split publication lets manual sync finish first; the
                    // atomic local publication waits until this worker is released.
                    manualFinished.await(150, TimeUnit.MILLISECONDS)
                    h.releaseWidget.countDown()
                    h.finish(work)
                }
                manualStarted.countDown()
                try {
                    h.frontState.setInferredCalendar("20311", manualCalendar())
                    syncScheduleWidget(h.context, h.frontState.entries, h.frontState.termCode,
                        inferredCalendar = h.frontState.cache.inferredCalendars["20311"], manualWeek = 1)
                    expected.set(ScheduleWidgetData.load(h.context))
                } finally { manualFinished.countDown() }
                assertEquals(Result.success(), releaser.get(5, TimeUnit.SECONDS))

                assertEquals("manual calibration must finish with its own widget projection",
                    "previous", expected.get()?.courses?.single()?.courseName)
                assertEquals("a previously accepted worker widget must not roll back a later manual sync",
                    expected.get(), ScheduleWidgetData.load(h.context))
                assertEquals(h.frontState.cache, ScheduleSectionState.currentProcessScheduleSnapshot())
            } finally {
                h.releaseWidget.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `worker starting after a manual calendar reservation cannot commit its older disk projection`() {
        Harness().use { h ->
            h.frontState.applySnapshot(previousSnapshot())
            val calendar = manualCalendar()
            AppForegroundTracker.foreground = true
            h.frontState.setInferredCalendar("20311", calendar)
            AppForegroundTracker.foreground = false

            assertEquals(calendar, ScheduleSectionState.currentProcessScheduleSnapshot()
                ?.inferredCalendars?.get("20311"))
            assertTrue("the queued manual snapshot must still be waiting to persist",
                loadScheduleSnapshotFromFile(h.context).inferredCalendars.isEmpty())

            assertEquals(Result.success(), h.finish(h.start()))
            h.drainPersistence()

            assertEquals("the newer manual snapshot must survive the later worker commit",
                calendar, ScheduleSectionState.currentProcessScheduleSnapshot()?.inferredCalendars?.get("20311"))
            assertEquals(calendar, loadScheduleSnapshotFromFile(h.context).inferredCalendars["20311"])
            assertEquals(calendar, h.recreateState().cache.inferredCalendars["20311"])
        }
    }

    @Test
    fun `worker that read before a queued manual snapshot persists cannot publish its stale calendars`() {
        Harness(blockSnapshotRead = true).use { h ->
            h.frontState.applySnapshot(previousSnapshot())
            val calendar = manualCalendar()
            AppForegroundTracker.foreground = true
            h.frontState.setInferredCalendar("20311", calendar)
            AppForegroundTracker.foreground = false

            val work = h.start()
            assertTrue(h.snapshotReadEntered.await(5, TimeUnit.SECONDS))
            h.drainPersistence()
            assertEquals(calendar, loadScheduleSnapshotFromFile(h.context).inferredCalendars["20311"])
            h.releaseSnapshotRead.countDown()

            assertEquals(Result.success(), h.finish(work))
            assertEquals(calendar, ScheduleSectionState.currentProcessScheduleSnapshot()?.inferredCalendars?.get("20311"))
            assertEquals(calendar, loadScheduleSnapshotFromFile(h.context).inferredCalendars["20311"])
            assertEquals(calendar, h.recreateState().cache.inferredCalendars["20311"])
        }
    }

    @Test
    fun `cancelled queued manual persistence still commits before later workers read the cache`() {
        Harness().use { h ->
            h.frontState.applySnapshot(previousSnapshot())
            val calendar = manualCalendar()
            h.frontState.setInferredCalendar("20311", calendar)
            h.cancelQueuedPersistence()
            assertEquals(calendar, loadScheduleSnapshotFromFile(h.context).inferredCalendars["20311"])

            assertEquals(Result.success(), h.finish(h.start()))
            assertEquals("background", loadScheduleSnapshotFromFile(h.context).entries.single().courseName)
            assertEquals("background", ScheduleSectionState.currentProcessScheduleSnapshot()?.entries?.single()?.courseName)
            assertEquals(calendar, ScheduleSectionState.currentProcessScheduleSnapshot()?.inferredCalendars?.get("20311"))
            assertEquals(calendar, loadScheduleSnapshotFromFile(h.context).inferredCalendars["20311"])
            assertEquals("background", ScheduleWidgetData.load(h.context)?.courses?.single()?.courseName)
        }
    }

    @Test
    fun `already foreground worker makes no request or cache mutation`() {
        Harness().use { h ->
            AppForegroundTracker.foreground = true
            assertEquals(Result.success(), h.finish(h.start()))
            assertEquals(0, h.fetches.get())
            assertTrue(loadScheduleSnapshotFromFile(h.context).entries.isEmpty())
            assertEquals("academic=initial", h.settings.scoreCookieHeader)
        }
    }

    @Test
    fun `foreground return stops automatic relogin but permits an otherwise current completed read`() {
        Harness(blockFetch = true).use { h ->
            val work = h.start()
            assertTrue(h.fetchEntered.await(5, TimeUnit.SECONDS))
            AppForegroundTracker.foreground = true
            h.releaseFetch.countDown()
            assertEquals(Result.success(), h.finish(work))

            assertFalse("background CAS must stop while the user is active", h.mayReloginAfterPause.get())
            assertEquals("background", loadScheduleSnapshotFromFile(h.context).entries.single().courseName)
        }
    }

    @Test
    fun `late background result cannot overwrite a newer foreground refresh after returning to background`() {
        Harness(blockFetch = true).use { h ->
            val work = h.start()
            assertTrue(h.fetchEntered.await(5, TimeUnit.SECONDS))
            AppForegroundTracker.foreground = true
            h.refreshForeground()
            val displayedWidget = ScheduleWidgetData.load(h.context)
            AppForegroundTracker.foreground = false
            h.releaseFetch.countDown()
            h.finish(work)

            assertEquals("foreground", loadScheduleSnapshotFromFile(h.context).entries.single().courseName)
            assertEquals("academic=foreground", h.settings.scoreCookieHeader)
            assertEquals(displayedWidget, ScheduleWidgetData.load(h.context))
        }
    }

    @Test
    fun `same account logout and relogin with identical main cookie rejects the previous worker`() {
        assertReloginRejectsOldWorker("synthetic-a")
    }

    @Test
    fun `different account login cannot authorize the previous workers relogin`() {
        assertReloginRejectsOldWorker("synthetic-b")
    }

    private fun assertReloginRejectsOldWorker(username: String) {
        Harness(blockFetch = true).use { h ->
            val work = h.start()
            assertTrue(h.fetchEntered.await(5, TimeUnit.SECONDS))
            h.relogin(username)
            h.releaseFetch.countDown()
            h.finish(work)

            assertFalse("new login must not authorize the old credentials", h.mayReloginAfterPause.get())
            assertTrue(loadScheduleSnapshotFromFile(h.context).entries.isEmpty())
            assertEquals("academic=new-session", h.settings.scoreCookieHeader)
            assertNull(ScheduleWidgetData.load(h.context))
        }
    }

    @Test
    fun `logout clearing waits for entered worker write and leaves all three stores empty`() {
        assertLogoutWaitsForLocalWriter(blockCookie = false)
    }

    @Test
    fun `logout clearing also waits for an entered cookie setter before its final cookie clear`() {
        assertLogoutWaitsForLocalWriter(blockCookie = true)
    }

    private fun assertLogoutWaitsForLocalWriter(blockCookie: Boolean) {
        Harness(blockWrite = !blockCookie, blockCookie = blockCookie).use { h ->
            val work = h.start()
            val enteredWriter = if (blockCookie) h.cookieEntered else h.writeEntered
            val releaseWriter = if (blockCookie) h.releaseCookie else h.releaseWrite
            assertTrue(enteredWriter.await(5, TimeUnit.SECONDS))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val clearThread = AtomicReference<Thread>()
                val entered = CountDownLatch(1)
                val clearing = executor.submit {
                    clearThread.set(Thread.currentThread())
                    h.clearAccount { entered.countDown() }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                var blocked = false
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!clearing.isDone && System.nanoTime() < deadline) {
                        val writerState = h.writerThread.get().state
                        val clearingThread = clearThread.get()
                        val waitingForScheduleGate = clearingThread.stackTrace.any {
                            it.className == LatestSnapshotWriteGate::class.java.name &&
                                it.methodName == "invalidateAndWait"
                        }
                        if (clearingThread.state == Thread.State.BLOCKED && waitingForScheduleGate &&
                            (writerState == Thread.State.WAITING || writerState == Thread.State.TIMED_WAITING)) {
                            blocked = true
                            break
                        }
                        Thread.yield()
                    }
                } finally { releaseWriter.countDown() }
                h.finish(work)
                clearing.get(5, TimeUnit.SECONDS)

                assertTrue("clear must wait for the entered local writer", blocked)
                assertTrue(loadScheduleSnapshotFromFile(h.context).entries.isEmpty())
                assertEquals("", h.settings.scoreCookieHeader)
                assertNull(ScheduleWidgetData.load(h.context))
            } finally {
                releaseWriter.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `logout and account replacement clear the shared cookie after both local writer barriers`() {
        listOf("MainActivity.kt", "HomeActions.kt").forEach { filename ->
            val source = sequenceOf("src/main", "app/src/main", "android/app/src/main")
                .map { File("$it/java/com/xmu/assistant/$filename") }.first { it.isFile }.readText()
            val cleanup = if (filename == "MainActivity.kt") {
                source.substringAfter("fun clearLoggedOutUi()").substringBefore("fun invalidateMainSessionUi()")
            } else {
                source.substringAfter("settings.cookieHeader = result.cookieHeader")
                    .substringBefore("deleteScheduleSnapshotFile(activity)")
            }
            val clear = cleanup.lastIndexOf("settings.scoreCookieHeader = \"\"")
            assertTrue("$filename must finally clear shared cookie after score writes", clear > cleanup.indexOf("scores.clearAll()"))
            assertTrue("$filename must finally clear shared cookie after schedule writes", clear > cleanup.indexOf("schedule.clearAll()"))
        }
    }

    @Test
    fun `cancelled worker whose blocking fetch returns cannot start persistence`() {
        Harness(blockFetch = true).use { h ->
            val work = h.start()
            assertTrue(h.fetchEntered.await(5, TimeUnit.SECONDS))
            work.cancel()
            h.releaseFetch.countDown()
            h.join(work)

            assertTrue(work.isCancelled)
            assertTrue("cancelled worker still wrote the schedule", loadScheduleSnapshotFromFile(h.context).entries.isEmpty())
            assertEquals("academic=initial", h.settings.scoreCookieHeader)
            assertEquals(0, h.widgetWrites.get())
        }
    }

    @Test
    fun `WorkManager entry uses the runner tested by these timelines`() {
        val source = sequenceOf("src/main", "app/src/main", "android/app/src/main")
            .map { File("$it/java/com/xmu/assistant/ScheduleWidgetSyncWorker.kt") }.first { it.isFile }.readText()
        assertTrue(source.contains("override suspend fun doWork(): Result = ScheduleWidgetSyncRun(applicationContext).execute()"))
    }
}
