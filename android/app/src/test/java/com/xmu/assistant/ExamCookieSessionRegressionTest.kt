package com.xmu.assistant

import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ExamCookieSessionRegressionTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun clearCache() {
        ExamCache.clear(app)
    }

    private enum class RequestPath { REFRESH, REFRESH_PROBE, CHECK_CHANGES, SILENT_PROBE }

    private class Fixture(private val path: RequestPath, blockResponse: Boolean) : AutoCloseable {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val loggedIn = AtomicBoolean(true)
        val mainCookie = AtomicReference("session=account-a")
        val academicCookie = AtomicReference("jw=account-a")
        val cookieWrites = ConcurrentLinkedQueue<String>()
        val wroteOffMain = AtomicBoolean(false)
        val responseEntered = CountDownLatch(1)
        private val releaseResponse = CountDownLatch(if (blockResponse) 1 else 0)
        private val blockedOnce = AtomicBoolean(false)
        private val job = SupervisorJob()
        private val gate = RequestGate()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val state: ExamSectionState

        init {
            ExamCache.clear(controller.get())
            if (path == RequestPath.CHECK_CHANGES) ExamCache.saveTerm(controller.get(), cachedSummary())
            state = ExamSectionState(
                activity = controller.get(),
                requestGate = gate,
                sessionEpoch = epoch,
                sessionOwner = owner,
                scope = CoroutineScope(job + Dispatchers.Main.immediate),
                show = {},
                loggedIn = { loggedIn.get() },
                cookieHeader = { mainCookie.get() },
                username = { "20260001" },
                password = { "fixture-password" },
                accountTransitionInProgress = { false },
                scoreCookieHeader = { academicCookie.get() },
                setScoreCookieHeader = { updated ->
                    if (Looper.myLooper() != Looper.getMainLooper()) wroteOffMain.set(true)
                    cookieWrites += updated
                    academicCookie.set(updated)
                },
                reminderSettings = { ExamReminderSettings(enabled = false, advanceMinutes = 30) },
                selectedTermInitial = if (path == RequestPath.REFRESH_PROBE || path == RequestPath.SILENT_PROBE) "" else TERM,
                cacheDispatcher = Dispatchers.Main.immediate,
                createExamClient = { username, password, cookie, mayRelogin ->
                    XmuExamClient(
                        cookie,
                        username,
                        password,
                        mayRelogin = mayRelogin,
                        transport = QueryHttpTransport { request ->
                            if (blockResponse && blockedOnce.compareAndSet(false, true)) {
                                responseEntered.countDown()
                                check(releaseResponse.await(10, TimeUnit.SECONDS))
                            }
                            val body = when {
                                request.url.contains("index.do") -> "<html>app</html>"
                                request.url.endsWith("/xnxqcx.do") -> TERMS
                                request.url.endsWith("/cxxsksap.do") -> EXAMS
                                else -> EMPTY
                            }
                            QueryHttpResponse(
                                url = request.url,
                                code = 200,
                                location = null,
                                body = body,
                                headers = mapOf("Set-Cookie" to listOf("JWSESSION=renewed-a; Path=/")),
                            )
                        },
                    )
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
            if (path == RequestPath.CHECK_CHANGES) assertNotNull(state.summary)
        }

        fun start() {
            when (path) {
                RequestPath.REFRESH, RequestPath.REFRESH_PROBE -> state.refresh()
                RequestPath.CHECK_CHANGES -> state.checkChanges(TERM)
                RequestPath.SILENT_PROBE -> state.silentRefresh()
            }
        }

        fun logout() {
            epoch.invalidate(owner)
            loggedIn.set(false)
            mainCookie.set("")
            academicCookie.set("")
            state.clearAll()
        }

        fun switchAccount() {
            logout()
            val attempt = epoch.beginLoginAttempt(owner, "20260002", "account-b-password")
            assertNotNull(attempt)
            assertTrue(epoch.completeLogin(requireNotNull(attempt), "20260002", "account-b-password"))
            mainCookie.set("session=account-b")
            academicCookie.set("jw=account-b")
            loggedIn.set(true)
        }

        fun unblock() = releaseResponse.countDown()

        fun awaitCompletion() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var released = false
            while (!released && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                released = gate.tryStart("exam")
                if (!released) Thread.sleep(10)
            }
            assertTrue("$path must release its request gate", released)
            gate.finish("exam")
        }

        override fun close() {
            unblock()
            job.cancel()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `logout discards delayed cookies from every exam request path`() {
        for (path in RequestPath.values()) {
            Fixture(path, blockResponse = true).use { fixture ->
                fixture.start()
                assertTrue("$path must reach the blocked response", fixture.responseEntered.await(5, TimeUnit.SECONDS))
                fixture.logout()
                fixture.unblock()
                fixture.awaitCompletion()
                assertEquals("$path must keep the logout cookie cleared", "", fixture.academicCookie.get())
                assertTrue("$path must discard the old cookie callback", fixture.cookieWrites.isEmpty())
            }
        }
    }

    @Test
    fun `switching accounts keeps the new academic cookie on every exam request path`() {
        for (path in RequestPath.values()) {
            Fixture(path, blockResponse = true).use { fixture ->
                fixture.start()
                assertTrue("$path must reach the blocked response", fixture.responseEntered.await(5, TimeUnit.SECONDS))
                fixture.switchAccount()
                fixture.unblock()
                fixture.awaitCompletion()
                assertEquals("$path must preserve account B", "jw=account-b", fixture.academicCookie.get())
                assertTrue("$path must discard account A's cookie callback", fixture.cookieWrites.isEmpty())
            }
        }
    }

    @Test
    fun `current sessions commit renewed exam cookies on the main thread for every path`() {
        for (path in RequestPath.values()) {
            Fixture(path, blockResponse = false).use { fixture ->
                fixture.start()
                fixture.awaitCompletion()
                assertFalse("$path must commit the successful cookie", fixture.cookieWrites.isEmpty())
                assertTrue("$path must retain the renewed server cookie", fixture.academicCookie.get().contains("JWSESSION=renewed-a"))
                assertFalse("$path must commit only after main-thread result acceptance", fixture.wroteOffMain.get())
            }
        }
    }

    companion object {
        private const val TERM = "2025-2026-2"
        private const val TERMS = """{"code":"0","datas":{"xnxqcx":{"totalSize":1,"rows":[{"DM":"2025-2026-2"}]}}}"""
        private const val EMPTY = """{"code":"0","datas":{"cxxsksap":{"totalSize":0,"rows":[]},"cxyxkwapkwdkc":{"totalSize":0,"rows":[]}}}"""
        private const val EXAMS = """{"code":"0","datas":{"cxxsksap":{"totalSize":1,"rows":[{"KSRWID":"exam-1","KCM":"Account A course","KSRQ":"2026-07-01","KSSJMS":"2026-07-01 08:00-10:00(星期三)","JASMC":"A306","KSXS_DISPLAY":"线下","KSMC":"期末考试"}]}}}"""

        private fun cachedSummary() = XmuTermExamSummary(
            termCode = TERM,
            termLabel = TERM,
            exams = listOf(XmuExam("exam-1", "Account A course", "2026-07-01", "08:00-10:00", "A306", "线下", "期末考试")),
            unarranged = emptyList(),
        )
    }
}
