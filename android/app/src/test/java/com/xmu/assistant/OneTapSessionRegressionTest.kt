package com.xmu.assistant

import android.os.Looper
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneTapSessionRegressionTest {
    private class Fixture(
        private val ids: List<String> = listOf("101"),
        private val afterFirstPoll: () -> Unit = {},
        private val afterFirstAnswer: () -> Unit = {},
        private val emptyAfterAnswer: Boolean = false,
    ) {
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val job = SupervisorJob()
        val gate = RequestGate()
        val messages = CopyOnWriteArrayList<String>()
        val submitted = CopyOnWriteArrayList<String>()
        private val cookie = "session=${java.util.UUID.randomUUID()}"
        private var polls = 0
        val state = RollcallSectionState(
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(job + Dispatchers.Main.immediate),
            show = { messages += it },
            requireLogin = { true },
            loggedIn = { true },
            cookieHeader = { cookie },
            username = { "20260001" },
            coursesProvider = { emptyList() },
            setPendingSessionRetry = {},
            createEngine = { cookie ->
                RollcallEngine(
                    cookie,
                    statusTransport = QueryHttpTransport { request ->
                        val body = if (request.url.endsWith("/api/radar/rollcalls")) {
                            polls++
                            if (polls == 1) afterFirstPoll()
                            if (emptyAfterAnswer && submitted.isNotEmpty()) {
                                """{"rollcalls":[]}"""
                            } else {
                                val rows = ids.joinToString(",") { id ->
                                    """{"rollcall_id":"$id","course_id":"c1","is_number":true,"status":"on_call"}"""
                                }
                                """{"rollcalls":[$rows]}"""
                            }
                        } else {
                            """{"number_code":"3819","student_rollcalls":[{"user_no":"20260001","status":"absent"}]}"""
                        }
                        QueryHttpResponse(url = request.url, code = 200, location = "", body = body, headers = emptyMap())
                    },
                    answerTransport = QueryHttpTransport { request ->
                        submitted += request.url
                        if (submitted.size == 1) afterFirstAnswer()
                        QueryHttpResponse(url = request.url, code = 200, location = "", body = """{"status":"ok"}""", headers = emptyMap())
                    },
                )
            },
        )

        fun awaitCompletion() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var released = false
            while (!released && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                released = gate.tryStart("rollcall_one_tap")
                if (!released) Thread.sleep(10)
            }
            assertTrue("one-tap request must release its gate", released)
            gate.finish("rollcall_one_tap")
        }
    }

    @Test
    fun `session invalidated during poll sends no answer`() {
        lateinit var fixture: Fixture
        fixture = Fixture(afterFirstPoll = { fixture.epoch.invalidate(fixture.owner) })
        try {
            assertTrue(fixture.state.oneTapSignIn())
            fixture.awaitCompletion()
            assertTrue("old session must not submit after poll", fixture.submitted.isEmpty())
            assertTrue("old session must not update UI", fixture.messages.isEmpty())
        } finally {
            fixture.job.cancel()
        }
    }

    @Test
    fun `session invalidated after first answer stops the rest of the batch`() {
        lateinit var fixture: Fixture
        fixture = Fixture(ids = listOf("101", "102"), afterFirstAnswer = { fixture.epoch.invalidate(fixture.owner) })
        try {
            assertTrue(fixture.state.oneTapSignIn())
            fixture.awaitCompletion()
            assertEquals(1, fixture.submitted.size)
            assertTrue(fixture.messages.isEmpty())
        } finally {
            fixture.job.cancel()
        }
    }

    @Test
    fun `scope cancelled while poll blocks sends no answer after response`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture(afterFirstPoll = {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        try {
            assertTrue(fixture.state.oneTapSignIn())
            assertTrue(started.await(10, TimeUnit.SECONDS))
            fixture.job.cancel()
            release.countDown()
            fixture.awaitCompletion()
            assertTrue("cancelled coroutine must not submit", fixture.submitted.isEmpty())
        } finally {
            release.countDown()
            fixture.job.cancel()
        }
    }

    @Test
    fun `duplicate poll entries submit each rollcall once`() {
        val fixture = Fixture(ids = listOf("101", "101"))
        try {
            assertTrue(fixture.state.oneTapSignIn())
            fixture.awaitCompletion()
            assertEquals(1, fixture.submitted.size)
        } finally {
            fixture.job.cancel()
        }
    }

    @Test
    fun `completed rollcall removed from active list still reports successful submission`() {
        val fixture = Fixture(emptyAfterAnswer = true)
        try {
            assertTrue(fixture.state.oneTapSignIn())
            fixture.awaitCompletion()
            assertEquals(1, fixture.submitted.size)
            assertTrue(fixture.messages.toString(), fixture.messages.any { "已提交 1 项" in it })
            assertTrue(fixture.state.events.isEmpty())
        } finally {
            fixture.job.cancel()
        }
    }
}
