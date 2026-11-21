package com.xmu.assistant

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollcallAnswerGateTest {
    @Test
    fun `concurrent foreground and monitor answers share one accepted request`() {
        val account = UUID.randomUUID().toString()
        val rollcallId = UUID.randomUUID().toString()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val foreground = executor.submit<Boolean> {
                RollcallAnswerGate.answerOnce(account, "cookie-a", rollcallId) {
                    requests.incrementAndGet()
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val monitor = executor.submit<Boolean> {
                RollcallAnswerGate.answerOnce(account, "cookie-a", rollcallId) {
                    requests.incrementAndGet()
                    true
                }
            }
            release.countDown()
            assertTrue(foreground.get(5, TimeUnit.SECONDS))
            assertTrue(monitor.get(5, TimeUnit.SECONDS))
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `rejected answer remains retryable`() {
        val account = UUID.randomUUID().toString()
        val rollcallId = UUID.randomUUID().toString()
        var requests = 0

        assertFalse(RollcallAnswerGate.answerOnce(account, "cookie-b", rollcallId) { requests++; false })
        assertTrue(RollcallAnswerGate.answerOnce(account, "cookie-b", rollcallId) { requests++; true })
        assertEquals(2, requests)
    }

    @Test
    fun `session guard is checked inside the submission lock`() {
        val account = UUID.randomUUID().toString()
        val rollcallId = UUID.randomUUID().toString()
        var requests = 0

        org.junit.Assert.assertThrows(RollcallAnswerNotSubmittedException::class.java) {
            RollcallAnswerGate.answerOnce(
                accountId = account,
                cookieHeader = "cookie-c",
                rollcallId = rollcallId,
                canAnswer = { false },
                answer = { requests++; true },
            )
        }

        assertEquals(0, requests)
    }

    @Test
    fun `a waiter invalidated before acquiring the gate never submits`() {
        val account = UUID.randomUUID().toString()
        val holderStarted = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val waiterStarted = CountDownLatch(1)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val requests = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit<Boolean> {
                RollcallAnswerGate.answerOnce(account, "wait-cookie", "holder") {
                    holderStarted.countDown()
                    check(releaseHolder.await(5, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(holderStarted.await(5, TimeUnit.SECONDS))
            val waiter = executor.submit<Result<Boolean>> {
                waiterStarted.countDown()
                runCatching {
                    RollcallAnswerGate.answerOnce(
                        account, "wait-cookie", "waiter",
                        canAnswer = { current.get() },
                        answer = { requests.incrementAndGet(); true },
                    )
                }
            }
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))
            current.set(false)
            releaseHolder.countDown()

            assertTrue(holder.get(5, TimeUnit.SECONDS))
            assertTrue(waiter.get(5, TimeUnit.SECONDS).exceptionOrNull() is RollcallAnswerNotSubmittedException)
            assertEquals(0, requests.get())
        } finally {
            releaseHolder.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `accepted answers stay isolated across accounts and renewed cookies`() {
        val account = UUID.randomUUID().toString()
        val event = UUID.randomUUID().toString()
        var requests = 0
        fun answer(owner: String, cookie: String) = RollcallAnswerGate.answerOnce(owner, cookie, event) {
            requests++
            true
        }

        assertTrue(answer(account, "original-cookie"))
        assertTrue(answer(account, "original-cookie"))
        assertEquals(1, requests)
        assertTrue(answer(account, "renewed-cookie"))
        assertTrue(answer("other-$account", "original-cookie"))
        assertEquals(3, requests)
    }

    @Test
    fun `local session skips never consume monitor answer attempts`() {
        val account = UUID.randomUUID().toString()
        val event = RollcallEvent(UUID.randomUUID().toString(), "测试课程", "教师", "雷达签到", "未签", ownStatus = "未签")
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var current = false
        var requests = 0
        fun poll() = processRollcallMonitorPoll(
            events = listOf(event),
            settings = RollcallSettings(autoAnswerRadar = true),
            notifiedIds = notified,
            completedIds = completed,
            answerAttempts = attempts,
            runIfActive = { action -> action(); true },
            onNotify = { true },
            onAnswer = {
                RollcallAnswerGate.answerOnce(
                    account, "monitor-test", event.id,
                    canAnswer = { current },
                    answer = { requests++; true },
                )
            },
            onSuccess = {},
        )
        repeat(5) {
            org.junit.Assert.assertThrows(RollcallAnswerNotSubmittedException::class.java) { poll() }
        }
        assertTrue(attempts.isEmpty())
        assertTrue(completed.isEmpty())
        assertEquals(0, requests)
        current = true
        poll()
        assertEquals(1, requests)
        assertEquals(setOf(event.id), completed)
    }
}
