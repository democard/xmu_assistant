package com.xmu.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorRunGateTest {
    @Test
    fun `invalidated token cannot start an action`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        gate.requestInvalidate()
        var actions = 0

        assertFalse(gate.runIfActive(token, monitorDesired = true) { actions += 1 })
        assertEquals(0, actions)
    }

    @Test
    fun `invalidation returns immediately while background barrier waits for admitted action`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val invalidateReturned = CountDownLatch(1)
        val staleCallReturned = CountDownLatch(1)
        val staleActionEntered = CountDownLatch(1)
        val barrierStarted = CountDownLatch(1)
        val barrierReturned = CountDownLatch(1)

        val actionThread = Thread {
            assertTrue(
                gate.runIfActive(token, monitorDesired = true) {
                    actionStarted.countDown()
                    assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
                },
            )
        }
        actionThread.start()
        assertTrue(actionStarted.await(5, TimeUnit.SECONDS))

        val invalidateThread = Thread {
            gate.requestInvalidate()
            invalidateReturned.countDown()
        }
        invalidateThread.start()
        val invalidatedWithoutWaiting = invalidateReturned.await(1, TimeUnit.SECONDS)
        if (!invalidatedWithoutWaiting) releaseAction.countDown()
        assertTrue("requestInvalidate must not wait for network side effects", invalidatedWithoutWaiting)
        val staleThread = Thread {
            assertFalse(gate.runIfActive(token, monitorDesired = true) { staleActionEntered.countDown() })
            staleCallReturned.countDown()
        }
        staleThread.start()
        assertFalse(staleActionEntered.await(150, TimeUnit.MILLISECONDS))

        val barrierThread = Thread {
            barrierStarted.countDown()
            gate.awaitQuiescence()
            barrierReturned.countDown()
        }
        barrierThread.start()
        assertTrue(barrierStarted.await(5, TimeUnit.SECONDS))
        assertFalse(barrierReturned.await(150, TimeUnit.MILLISECONDS))

        releaseAction.countDown()
        assertTrue(staleCallReturned.await(5, TimeUnit.SECONDS))
        assertFalse(staleActionEntered.await(150, TimeUnit.MILLISECONDS))
        assertTrue(barrierReturned.await(5, TimeUnit.SECONDS))
        actionThread.join(5_000)
        invalidateThread.join(5_000)
        staleThread.join(5_000)
        barrierThread.join(5_000)
    }

    @Test
    fun `active token runs its action exactly once`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        var actions = 0

        assertTrue(gate.runIfActive(token, monitorDesired = true) { actions += 1 })
        assertEquals(1, actions)
    }

    @Test
    fun `replacement side effect cannot overlap an admitted old side effect`() {
        val gate = MonitorRunGate()
        val oldToken = gate.begin()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldBodyFinished = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)

        val oldThread = Thread {
            gate.runIfActive(oldToken, monitorDesired = true) {
                oldStarted.countDown()
                assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                oldBodyFinished.countDown()
            }
        }
        oldThread.start()
        assertTrue(oldStarted.await(5, TimeUnit.SECONDS))

        gate.requestInvalidate()
        val replacementToken = gate.begin()
        val replacementThread = Thread {
            gate.runIfActive(replacementToken, monitorDesired = true) {
                replacementEntered.countDown()
            }
        }
        replacementThread.start()

        assertFalse("replacement must wait for the old side effect mutex", replacementEntered.await(150, TimeUnit.MILLISECONDS))
        releaseOld.countDown()
        assertTrue(oldBodyFinished.await(5, TimeUnit.SECONDS))
        assertTrue(replacementEntered.await(5, TimeUnit.SECONDS))
        oldThread.join(5_000)
        replacementThread.join(5_000)
    }

    @Test
    fun `poll completion after stop performs no notify answer or health write`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        gate.requestInvalidate()
        var notifications = 0
        var answers = 0
        var healthWrites = 0

        processActiveMonitorPoll(
            events = listOf("event"),
            runIfActive = { action -> gate.runIfActive(token, monitorDesired = false, action = action) },
            onNotify = { notifications += 1 },
            shouldAnswer = { true },
            onAnswer = { answers += 1 },
            onSuccess = { healthWrites += 1 },
        )

        assertEquals(0, notifications)
        assertEquals(0, answers)
        assertEquals(0, healthWrites)
    }

    @Test
    fun `active poll notifies answers and records health`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        var notifications = 0
        var answers = 0
        var healthWrites = 0

        processActiveMonitorPoll(
            events = listOf("event"),
            runIfActive = { action -> gate.runIfActive(token, monitorDesired = true, action = action) },
            onNotify = { notifications += 1 },
            shouldAnswer = { true },
            onAnswer = { answers += 1 },
            onSuccess = { healthWrites += 1 },
        )

        assertEquals(1, notifications)
        assertEquals(1, answers)
        assertEquals(1, healthWrites)
    }

    @Test
    fun `stop during notification prevents later answer and health write`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        var running = true
        var answers = 0
        var healthWrites = 0

        processActiveMonitorPoll(
            events = listOf("event"),
            runIfActive = { action -> gate.runIfActive(token, monitorDesired = running, action = action) },
            onNotify = {
                running = false
                gate.requestInvalidate()
            },
            shouldAnswer = { true },
            onAnswer = { answers += 1 },
            onSuccess = { healthWrites += 1 },
        )

        assertEquals(0, answers)
        assertEquals(0, healthWrites)
        assertFalse(gate.isActive(token, running = running, monitorDesired = true))
        assertTrue(gate.isActive(gate.begin(), running = true, monitorDesired = true))
    }

    @Test
    fun `session expired answer exception still marks event processed so it is not re-notified`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        var processed = 0

        val thrown = assertThrows(MainSessionExpiredException::class.java) {
            processActiveMonitorPoll(
                events = listOf("event"),
                runIfActive = { action -> gate.runIfActive(token, monitorDesired = true, action = action) },
                onNotify = { },
                shouldAnswer = { true },
                onAnswer = { throw MainSessionExpiredException() },
                onSuccess = { },
                onProcessed = { processed += 1 },
            )
        }

        // P3：会话过期（永久态）必须标记已处理，否则外层 while 下一轮会重复通知同一签到/重复应答
        assertEquals("登录已过期", thrown.message)
        assertEquals(1, processed)
    }

    @Test
    fun `transient answer exception does not mark processed so it is retried next poll`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        var processed = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            processActiveMonitorPoll(
                events = listOf("event"),
                runIfActive = { action -> gate.runIfActive(token, monitorDesired = true, action = action) },
                onNotify = { },
                shouldAnswer = { true },
                onAnswer = { throw IllegalStateException("网络失败：500") },
                onSuccess = { },
                onProcessed = { processed += 1 },
            )
        }

        // H1 修订：瞬时网络/5xx 失败不能标已处理——否则该签到永久不再重试（自动签到可靠性）
        assertEquals("网络失败：500", thrown.message)
        assertEquals(0, processed)
    }

    @Test
    fun `non-answer event is still marked processed`() {
        val gate = MonitorRunGate()
        val token = gate.begin()
        var processed = 0

        processActiveMonitorPoll(
            events = listOf("event"),
            runIfActive = { action -> gate.runIfActive(token, monitorDesired = true, action = action) },
            onNotify = { },
            shouldAnswer = { false },
            onAnswer = { },
            onSuccess = { },
            onProcessed = { processed += 1 },
        )

        assertEquals(1, processed)
    }
}
