package com.xmu.assistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamCacheSessionRegressionTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun clearCache() {
        ExamCache.clear(app)
    }

    private class RefreshFixture(
        private val emptyResult: Boolean = false,
        private val beforeExamResponse: () -> Unit = {},
    ) : AutoCloseable {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val loggedIn = AtomicBoolean(true)
        private val job = SupervisorJob()
        private val gate = RequestGate()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val state = ExamSectionState(
            activity = controller.get(),
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(job + Dispatchers.Main.immediate),
            show = {},
            loggedIn = { loggedIn.get() },
            cookieHeader = { "session=account-a" },
            username = { "20260001" },
            password = { "fixture-password" },
            accountTransitionInProgress = { false },
            scoreCookieHeader = { "jw=account-a" },
            setScoreCookieHeader = {},
            reminderSettings = { ExamReminderSettings(enabled = false, advanceMinutes = 30) },
            selectedTermInitial = TERM,
            createExamClient = { username, password, cookie, mayRelogin ->
                XmuExamClient(
                    cookie,
                    username,
                    password,
                    mayRelogin = mayRelogin,
                    transport = QueryHttpTransport { request ->
                        if (request.url.endsWith("/cxxsksap.do")) beforeExamResponse()
                        val body = when {
                            request.url.contains("index.do") -> "<html>app</html>"
                            request.url.endsWith("/cxxsksap.do") && !emptyResult -> EXAMS
                            else -> EMPTY
                        }
                        QueryHttpResponse(
                            url = request.url,
                            code = 200,
                            location = null,
                            body = body,
                            headers = emptyMap(),
                        )
                    },
                )
            },
        )

        fun awaitCompletion() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var released = false
            while (!released && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                released = gate.tryStart("exam")
                if (!released) Thread.sleep(10)
            }
            assertTrue("exam request must release its gate", released)
            gate.finish("exam")
        }

        override fun close() {
            job.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `logout while the response is pending cannot resurrect the fetched exam cache`() {
        val responsePending = CountDownLatch(1)
        val resumeResponse = CountDownLatch(1)
        val fixture = RefreshFixture(beforeExamResponse = {
            responsePending.countDown()
            check(resumeResponse.await(10, TimeUnit.SECONDS))
        })
        try {
            fixture.state.refresh()
            assertTrue("fetch must reach the pending response", responsePending.await(5, TimeUnit.SECONDS))
            fixture.loggedIn.set(false)
            fixture.epoch.invalidate(fixture.owner)
            fixture.state.clearAll()
            resumeResponse.countDown()
            fixture.awaitCompletion()
            assertNull(ExamCache.loadTerm(app, TERM))
            assertTrue(ExamCache.loadTerms(app).isEmpty())
            assertTrue(ExamCache.loadAllExams(app).isEmpty())
            assertTrue(ExamCache.shouldReProbe(app))
            assertNull(fixture.state.summary)
        } finally {
            resumeResponse.countDown()
            fixture.close()
        }
    }

    @Test
    fun `a commit admitted before logout finishes before the cache is cleared`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val request = epoch.snapshot(owner, "session=account-a")
        val applyEntered = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val prefs = app.getSharedPreferences("exam_cache", Context.MODE_PRIVATE)
        val blockingPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun apply() {
                        applyEntered.countDown()
                        check(releaseApply.await(10, TimeUnit.SECONDS))
                        editor.apply()
                    }
                }
            }
        }
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "exam_cache") blockingPrefs else super.getSharedPreferences(name, mode)
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<Unit> {
                ExamCache.saveTerm(context, summary(), isCurrent = { epoch.isCurrent(request) })
            }
            assertTrue(applyEntered.await(5, TimeUnit.SECONDS))
            epoch.invalidate(owner)
            val clearStarted = CountDownLatch(1)
            val clearFinished = CountDownLatch(1)
            val clearer = executor.submit<Unit> {
                clearStarted.countDown()
                ExamCache.clear(app)
                clearFinished.countDown()
            }
            assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
            assertFalse("clear must serialize behind the admitted commit", clearFinished.await(100, TimeUnit.MILLISECONDS))
            releaseApply.countDown()
            writer.get(5, TimeUnit.SECONDS)
            clearer.get(5, TimeUnit.SECONDS)
            assertNull(ExamCache.loadTerm(app, TERM))
            assertTrue(ExamCache.loadTerms(app).isEmpty())
        } finally {
            releaseApply.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `old probe writes after clearing do not overwrite the next account cache`() {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val oldRequest = epoch.snapshot(owner, "session=account-a")
        epoch.invalidate(owner)
        ExamCache.clear(app)
        val currentRequest = epoch.snapshot(owner, "session=account-b")
        val currentSummary = summary().copy(exams = summary().exams.map { it.copy(courseName = "Account B course") })
        ExamCache.saveTerm(app, currentSummary, isCurrent = { epoch.isCurrent(currentRequest) })
        ExamCache.saveTerms(app, listOf(TERM), isCurrent = { epoch.isCurrent(currentRequest) })
        val oldSession = { epoch.isCurrent(oldRequest) }
        ExamCache.saveTerm(app, summary(), oldSession)
        ExamCache.saveTerms(app, listOf("old-term"), oldSession)
        ExamCache.markProbed(app, oldSession)
        assertEquals(currentSummary, ExamCache.loadTerm(app, TERM))
        assertEquals(listOf(TERM), ExamCache.loadTerms(app))
        assertTrue("an old probe must not throttle the new account", ExamCache.shouldReProbe(app))
    }

    @Test
    fun `current refresh still persists populated and empty term summaries`() {
        for (empty in listOf(false, true)) {
            ExamCache.clear(app)
            RefreshFixture(emptyResult = empty).use { fixture ->
                fixture.state.refresh()
                fixture.awaitCompletion()
                val cached = ExamCache.loadTerm(app, TERM)
                assertNotNull("successful refresh must persist an explicit term summary", cached)
                assertEquals(TERM, cached?.termCode)
                assertEquals(if (empty) 0 else 1, cached?.exams?.size)
                assertEquals(cached, fixture.state.summary)
            }
        }
    }

    private fun summary() = XmuTermExamSummary(
        termCode = TERM,
        termLabel = TERM,
        exams = listOf(XmuExam("exam-1", "Account A course", "2026-07-01", "08:00-10:00", "A306", "线下", "期末考试")),
        unarranged = emptyList(),
    )

    companion object {
        private const val TERM = "2025-2026-2"
        private const val EMPTY = """{"code":"0","datas":{"cxxsksap":{"totalSize":0,"rows":[]},"cxyxkwapkwdkc":{"totalSize":0,"rows":[]}}}"""
        private const val EXAMS = """{"code":"0","datas":{"cxxsksap":{"totalSize":1,"rows":[{"KSRWID":"exam-1","KCM":"Account A course","KSRQ":"2026-07-01","KSSJMS":"2026-07-01 08:00-10:00(星期三)","JASMC":"A306","KSXS_DISPLAY":"线下","KSMC":"期末考试"}]}}}"""
    }
}
