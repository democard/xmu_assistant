package com.xmu.assistant

import android.os.Looper
import androidx.activity.ComponentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
class ExamGateHandoffRegressionTest {
    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun `completed probe keeps admission owned by the blocked follow-up fetch`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.IO)
        val fetchEntered = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val factories = AtomicInteger()
        val term = "2025-2026-2"
        val transport = QueryHttpTransport { request ->
            val body = when {
                request.url.endsWith("xnxqcx.do") ->
                    """{"code":"0","datas":{"xnxqcx":{"totalSize":1,"rows":[{"DM":"$term"}]}}}"""
                request.url.endsWith("cxxsksap.do") ->
                    """{"code":"0","datas":{"cxxsksap":{"totalSize":1,"rows":[{"KSRWID":"exam","KCM":"Test course","KSRQ":"2026-06-14","KSSJMS":"08:00-10:00"}]}}}"""
                request.url.endsWith("cxyxkwapkwdkc.do") ->
                    """{"code":"0","datas":{"cxyxkwapkwdkc":{"totalSize":0,"rows":[]}}}"""
                else -> "<html>app</html>"
            }
            QueryHttpResponse(request.url, 200, null, body, emptyMap())
        }
        try {
            ExamCache.clear(activity)
            val gate = RequestGate()
            val epoch = SessionEpoch()
            val state = ExamSectionState(
                activity = activity, requestGate = gate, sessionEpoch = epoch,
                sessionOwner = epoch.attachOwner(), scope = scope, show = {},
                loggedIn = { true }, cookieHeader = { "session=fixture" },
                username = { "12320011234567" }, password = { "synthetic" },
                accountTransitionInProgress = { false }, scoreCookieHeader = { "JWSESSION=fixture" },
                setScoreCookieHeader = {}, reminderSettings = { ExamReminderSettings(false, 30) },
                selectedTermInitial = "", cacheDispatcher = Dispatchers.Main,
                createExamClient = { account, password, cookie, current ->
                    if (factories.incrementAndGet() == 2) {
                        fetchEntered.countDown()
                        check(releaseFetch.await(10, TimeUnit.SECONDS))
                    }
                    XmuExamClient(cookie, account, password, mayRelogin = current, transport = transport)
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
            state.refresh()
            val probe = parent.children.single()
            assertTrue("probe must hand off and finish while fetch remains blocked", await {
                fetchEntered.count == 0L && probe.isCompleted
            })
            assertEquals(2, factories.get())
            assertFalse("probe completion must not release the follow-up request's admission", gate.tryStart("exam"))
            assertTrue(state.loading)
            releaseFetch.countDown()
            assertTrue("follow-up fetch must finish and clear loading", await {
                !state.loading && parent.children.none()
            })
            assertEquals(term, state.summary?.termCode)
            assertTrue(gate.tryStart("exam"))
            gate.finish("exam")
        } finally {
            releaseFetch.countDown()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            ExamCache.clear(activity)
            controller.pause().stop().destroy()
        }
    }
}
