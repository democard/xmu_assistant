package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
class ScorePersistenceOrderTest {
    private fun homeActionsSource(): String = sequenceOf(
        File("src/main/java/com/xmu/assistant/HomeActions.kt"),
        File("app/src/main/java/com/xmu/assistant/HomeActions.kt"),
        File("android/app/src/main/java/com/xmu/assistant/HomeActions.kt"),
    ).first { it.isFile }.readText()

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        val size: Int get() = pending.size
        fun drainNewestFirst() { while (pending.isNotEmpty()) pending.removeLast().run() }
    }

    private data class PersistedSnapshot(val cookie: String, val json: String, val updatedAt: Long)

    /** Real score parsing runs entirely against this fixture; unexpected URLs must fail. */
    private class ScoreTransport(private val courseCode: String, private val failRows: Boolean) : QueryHttpTransport {
        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            val body = when {
                "cxycjdxnxq.do" in request.url ->
                    """{"datas":{"cxycjdxnxq":{"rows":[{"XNXQDM":"20251","XNXQDM_DISPLAY":"2025 第一学期"}]}}}"""
                "xscjcx.do" in request.url ->
                    """{"datas":{"xscjcx":{"rows":[{"KCH":"$courseCode","KCM":"测试课程","KXH":"01","XF":"4","ZCJ":"92","XFJD":"3.7","DJCJLXDM_DISPLAY":"百分制"}]}}}"""
                else -> error("Unexpected fixture request: ${request.url}")
            }
            return QueryHttpResponse(
                url = request.url, code = if (failRows && "xscjcx.do" in request.url) 500 else 200,
                location = null, body = body,
                headers = mapOf("Set-Cookie" to listOf("academic=$courseCode; Path=/")),
            )
        }
    }

    private class Harness(private val beforeJsonWrite: () -> Unit = {}) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val parentJob = SupervisorJob()
        private val scope = CoroutineScope(parentJob + Dispatchers.IO)
        val persistence = QueuedDispatcher()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val snapshots = mutableListOf<PersistedSnapshot>()
        val clientCookies = mutableListOf<String>()
        private var nextCourseCode = ""
        private var nextFails = false
        @Volatile private var persistedCookie = "academic=initial"
        @Volatile private var persistedJson = ""
        @Volatile private var persistedAt = 0L
        val state = ScoreSectionState(
            activity = controller.get(), requestGate = RequestGate(), sessionEpoch = epoch,
            sessionOwner = owner, scope = scope, show = {},
            loggedIn = { true }, cookieHeader = { "session=fixture" },
            username = { "fixture-user" }, password = { "fixture-password" },
            accountTransitionInProgress = { false }, scoreCookieHeader = { persistedCookie },
            setScoreCookieHeader = { persistedCookie = it },
            setScoreRecordsJson = { beforeJsonWrite(); persistedJson = it },
            setScoreUpdatedAtMillisPref = {
                persistedAt = it
                snapshots += PersistedSnapshot(persistedCookie, persistedJson, it)
            },
            createScoreClient = { username, password, cookie, mayRelogin ->
                clientCookies += cookie
                XmuScoreAutoQueryClient(username, password, cookie, ScoreTransport(nextCourseCode, nextFails), mayRelogin = mayRelogin)
            },
            persistenceDispatcher = persistence,
        )

        fun refresh(courseCode: String, failRows: Boolean = false) {
            nextCourseCode = courseCode
            nextFails = failRows
            val oldJobs = parentJob.children.toSet()
            assertTrue(state.refresh())
            // The paused main looper keeps this request alive until we register its completion.
            val request = parentJob.children.single { it !in oldJobs }
            val completed = CountDownLatch(1)
            request.invokeOnCompletion { completed.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (completed.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                // Wait for actual completion, pumping Main between waits; no settling sleep.
                completed.await(1, TimeUnit.MILLISECONDS)
            }
            assertEquals("score request did not finish", 0L, completed.count)
            assertFalse(state.loading)
            if (failRows) assertTrue(state.refreshError.isNotBlank())
            else assertEquals(courseCode, state.scoreRecords.single().courseCode)
        }

        fun storedSnapshot() = PersistedSnapshot(persistedCookie, persistedJson, persistedAt)

        /** Mirrors the caller clearing prefs after the state's clearAll barrier. */
        fun clearPreferences() {
            persistedCookie = ""
            persistedJson = ""
            persistedAt = 0L
        }

        override fun close() {
            scope.cancel()
            persistence.drainNewestFirst()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `older queued refresh cannot overwrite the newer accepted score snapshot`() {
        Harness().use { harness ->
            harness.refresh("OLD101")
            harness.refresh("NEW202")
            assertEquals(2, harness.persistence.size)
            assertTrue(harness.snapshots.isEmpty())

            // Deliberately run the newer disk task before the older one.
            harness.persistence.drainNewestFirst()

            val latest = harness.snapshots.last()
            assertTrue("old JSON replaced the newer result", latest.json.contains("NEW202"))
            assertFalse(latest.json.contains("OLD101"))
            assertEquals("academic=NEW202", latest.cookie)
            assertEquals(harness.state.updatedAtMillis, latest.updatedAt)
            assertEquals("superseded snapshot should be skipped", 1, harness.snapshots.size)
        }
    }

    @Test
    fun `account invalidation discards a queued score snapshot`() {
        Harness().use { harness ->
            harness.refresh("OLD101")
            harness.epoch.invalidate(harness.owner)
            harness.state.clearAll()
            harness.persistence.drainNewestFirst()

            assertTrue(harness.snapshots.isEmpty())
            assertTrue(harness.state.scoreRecords.isEmpty())
            assertEquals(0L, harness.state.updatedAtMillis)
        }
    }

    @Test
    fun `clearing scores invalidates a queued snapshot even before the session changes`() {
        Harness().use { harness ->
            harness.refresh("OLD101")
            harness.state.clearAll()
            harness.persistence.drainNewestFirst()

            assertTrue(harness.snapshots.isEmpty())
            assertTrue(harness.state.scoreRecords.isEmpty())
            assertEquals(0L, harness.state.updatedAtMillis)
        }
    }

    @Test
    fun `new activity owner prevents old state from persisting its queued snapshot`() {
        Harness().use { harness ->
            harness.refresh("OLD101")
            harness.epoch.attachOwner()
            harness.persistence.drainNewestFirst()

            assertTrue(harness.snapshots.isEmpty())
        }
    }

    @Test
    fun `single accepted score refresh persists a coherent snapshot`() {
        Harness().use { harness ->
            harness.refresh("ONLY101")
            harness.persistence.drainNewestFirst()

            val snapshot = harness.snapshots.single()
            assertTrue(snapshot.json.contains("ONLY101"))
            assertEquals("academic=ONLY101", snapshot.cookie)
            assertEquals(harness.state.updatedAtMillis, snapshot.updatedAt)
            assertTrue(snapshot.updatedAt > 0)
        }
    }

    @Test
    fun `failed refresh keeps last valid scores and its newer cookie when prior save is queued`() {
        Harness().use { harness ->
            harness.refresh("VALID101")
            val validUpdatedAt = harness.state.updatedAtMillis
            harness.refresh("FAILED202", failRows = true)
            assertEquals("VALID101", harness.state.scoreRecords.single().courseCode)
            assertEquals(validUpdatedAt, harness.state.updatedAtMillis)

            harness.persistence.drainNewestFirst()

            val saved = harness.storedSnapshot()
            assertEquals("academic=FAILED202", saved.cookie)
            assertTrue("last valid result must still be saved", saved.json.contains("VALID101"))
            assertFalse(saved.json.contains("FAILED202"))
            assertEquals(validUpdatedAt, saved.updatedAt)
        }
    }

    @Test
    fun `next refresh uses the latest failure cookie before queued snapshots are saved`() {
        Harness().use { harness ->
            harness.refresh("VALID101")
            harness.refresh("FAILED202", failRows = true)
            assertTrue("disk snapshots deliberately remain queued", harness.snapshots.isEmpty())

            harness.refresh("NEXT303")

            assertEquals("academic=initial", harness.clientCookies.first())
            assertEquals("next request must use the just-renewed cookie", "academic=FAILED202", harness.clientCookies[2])
            assertTrue(harness.snapshots.isEmpty())
        }
    }

    @Test
    fun `clearing waits for an entered writer before the caller erases preferences`() {
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        Harness(beforeJsonWrite = {
            writerEntered.countDown()
            check(releaseWriter.await(5, TimeUnit.SECONDS)) { "writer was not released" }
        }).use { harness ->
            harness.refresh("OLD101")
            val workers = Executors.newFixedThreadPool(2)
            try {
                val writerThread = AtomicReference<Thread>()
                val clearingThread = AtomicReference<Thread>()
                val writing = workers.submit {
                    writerThread.set(Thread.currentThread())
                    harness.persistence.drainNewestFirst()
                }
                assertTrue(writerEntered.await(5, TimeUnit.SECONDS))
                val clearStarted = CountDownLatch(1)
                val clearing = workers.submit {
                    clearingThread.set(Thread.currentThread())
                    clearStarted.countDown()
                    harness.state.clearAll()
                    harness.clearPreferences()
                }
                assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
                var blockedOnWriter = false
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!clearing.isDone && System.nanoTime() < deadline) {
                        // The writer waits inside its setter hook while holding only the gate's
                        // monitor. clearAll has no preceding monitor acquisition in this fixture.
                        val writerState = writerThread.get().state
                        val writerWaiting = writerState == Thread.State.WAITING || writerState == Thread.State.TIMED_WAITING
                        if (clearingThread.get().state == Thread.State.BLOCKED && writerWaiting) {
                            blockedOnWriter = true
                            break
                        }
                        Thread.yield()
                    }
                } finally {
                    // An independent thread performs clearAll, so the test can release its writer.
                    releaseWriter.countDown()
                }
                writing.get(5, TimeUnit.SECONDS)
                clearing.get(5, TimeUnit.SECONDS)

                assertTrue("clearAll must block on the writer's lock before returning", blockedOnWriter)
                assertEquals(PersistedSnapshot("", "", 0L), harness.storedSnapshot())
                assertTrue(harness.state.scoreRecords.isEmpty())
            } finally {
                releaseWriter.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `account replacement clears score state before clearing its persisted fields`() {
        val success = homeActionsSource().substringAfter("settings.cookieHeader = result.cookieHeader")
            .substringBefore("schedule.clearAll()")
        val clearIndex = success.indexOf("scores.clearAll()")
        assertTrue("successful replacement must clear score state", clearIndex >= 0)
        listOf("settings.scoreCookieHeader =", "settings.scoreRecordsJson =", "settings.scoreUpdatedAtMillis =")
            .forEach { field ->
                assertTrue("$field must be cleared after the in-flight writer barrier", success.indexOf(field) > clearIndex)
            }
    }

    @Test
    fun `manual login uses lifecycle completion cleanup for its admission token`() {
        val login = homeActionsSource().substringAfter("fun onLogin(").substringBefore("fun onLogout(")
        assertTrue(login.contains("workScope.launchStartupSessionWork("))
        assertTrue(login.contains("onFinished = { ProcessSessionRecovery.coordinator.finishAutoLogin(loginToken) }"))
    }
}
