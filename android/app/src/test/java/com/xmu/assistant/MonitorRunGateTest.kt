package com.xmu.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun `threshold wait notifies once then submits once after progress reaches target`() {
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var notifications = 0
        var answers = 0
        val settings = RollcallSettings(
            autoAnswerRadar = true,
            waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_COUNT,
            waitBeforeAnswerCount = 5,
        )
        fun event(present: Int) = RollcallEvent(
            "r1", "课程", "老师", "雷达签到", "未签",
            progress = progress(present, 10),
        )
        repeat(2) {
            processRollcallMonitorPoll(
                listOf(event(4)), settings, notified, completed, attempts, { action -> action(); true },
                { notifications++; true }, { answers++; true }, {},
            )
        }
        processRollcallMonitorPoll(
            listOf(event(5)), settings, notified, completed, attempts, { action -> action(); true },
            { notifications++; true }, { answers++; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(event(8)), settings, notified, completed, attempts, { action -> action(); true },
            { notifications++; true }, { answers++; true }, {},
        )
        assertEquals(1, notifications)
        assertEquals(1, answers)
        assertEquals(setOf("r1"), completed)
    }

    @Test
    fun `second notification is delivered while first answer is blocked`() {
        val events = listOf(
            RollcallEvent("a", "课程 A", "老师", "雷达签到", "未签"),
            RollcallEvent("b", "课程 B", "老师", "雷达签到", "未签"),
        )
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val notifications = mutableListOf<String>()
        val answers = mutableListOf<String>()
        val firstAnswerStarted = CountDownLatch(1)
        val releaseFirstAnswer = CountDownLatch(1)
        val workerError = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                processRollcallMonitorPoll(
                    events, RollcallSettings(autoAnswerRadar = true), notified, completed, attempts,
                    { action -> action(); true },
                    { notifications += it.id; true },
                    {
                        answers += it.id
                        if (it.id == "a") {
                            firstAnswerStarted.countDown()
                            check(releaseFirstAnswer.await(5, TimeUnit.SECONDS))
                        }
                        true
                    },
                    {},
                )
            } catch (error: Throwable) {
                workerError.set(error)
            }
        }

        worker.start()
        try {
            assertTrue("first answer did not start", firstAnswerStarted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("a", "b"), notifications)
            assertEquals(listOf("a"), answers)
        } finally {
            releaseFirstAnswer.countDown()
            worker.join(5_000)
        }
        assertFalse("poll worker did not finish", worker.isAlive)
        workerError.get()?.let { throw AssertionError("poll worker failed", it) }
        assertEquals(listOf("a", "b"), answers)
        assertEquals(setOf("a", "b"), completed)

        processRollcallMonitorPoll(
            events, RollcallSettings(autoAnswerRadar = true), notified, completed, attempts,
            { action -> action(); true }, { notifications += it.id; true }, { answers += it.id; true }, {},
        )
        assertEquals(listOf("a", "b"), notifications)
        assertEquals(listOf("a", "b"), answers)
    }

    @Test
    fun `number event waits for code then preserves leading zero and submits once`() {
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val submittedCodes = mutableListOf<String>()
        val settings = RollcallSettings(
            autoAnswerNumber = true,
            waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_PERCENT,
            waitBeforeAnswerPercent = 50,
        )
        val base = RollcallEvent("n1", "课程", "老师", "数字签到", "未签", progress = progress(5, 10))
        processRollcallMonitorPoll(
            listOf(base), settings, notified, completed, attempts, { action -> action(); true }, { true },
            { submittedCodes += it.numberCode; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(base.copy(numberCode = "0042")), settings, notified, completed, attempts,
            { action -> action(); true }, { true }, { submittedCodes += it.numberCode; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(base.copy(numberCode = "0042")), settings, notified, completed, attempts,
            { action -> action(); true }, { true }, { submittedCodes += it.numberCode; true }, {},
        )
        assertEquals(listOf("0042"), submittedCodes)
    }

    @Test
    fun `number rejection retries with a refreshed code then completes only on success`() {
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val submittedCodes = mutableListOf<String>()
        val settings = RollcallSettings(autoAnswerNumber = true)

        runCatching {
            processRollcallMonitorPoll(
                listOf(numberEvent("1111")), settings, notified, completed, attempts,
                { action -> action(); true }, { true },
                {
                    submittedCodes += it.numberCode
                    throw RollcallAnswerRejectedException(400)
                }, {},
            )
        }
        assertTrue("an explicit rejection must remain pending", completed.isEmpty())

        processRollcallMonitorPoll(
            listOf(numberEvent("2222")), settings, notified, completed, attempts,
            { action -> action(); true }, { true },
            { submittedCodes += it.numberCode; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(numberEvent("2222")), settings, notified, completed, attempts,
            { action -> action(); true }, { true },
            { submittedCodes += it.numberCode; true }, {},
        )

        assertEquals(listOf("1111", "2222"), submittedCodes)
        assertEquals(setOf("n1"), completed)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `number rejection is bounded when the platform keeps refusing it`() {
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var writes = 0
        repeat(4) {
            processRollcallMonitorPoll(
                listOf(numberEvent("1111")), RollcallSettings(autoAnswerNumber = true),
                mutableSetOf(), completed, attempts, { action -> action(); true }, { true },
                { writes++; false }, {}, maxAnswerAttempts = 3,
            )
        }
        assertEquals(3, writes)
        assertTrue("exhausted rejection must stay a visible failure, not completed", completed.isEmpty())
        assertEquals(-3, attempts["n1"])
    }

    @Test
    fun `unknown number result waits for detail verification before retrying`() {
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var writes = 0
        val settings = RollcallSettings(autoAnswerNumber = true)

        runCatching {
            processRollcallMonitorPoll(
                listOf(numberEvent("1111")), settings, mutableSetOf(), completed, attempts,
                { action -> action(); true }, { true }, { writes++; error("timeout") }, {},
            )
        }
        processRollcallMonitorPoll(
            listOf(numberEvent("1111", ownStatus = null)), settings, mutableSetOf(), completed, attempts,
            { action -> action(); true }, { true }, { writes++; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(numberEvent("1111", ownStatus = STATUS_SIGNED)), settings,
            mutableSetOf(), completed, attempts, { action -> action(); true }, { true },
            { writes++; true }, {},
        )

        assertEquals("an unverified timeout must never be replayed", 1, writes)
        assertEquals(setOf("n1"), completed)
    }

    @Test
    fun `unknown number result retries once after detail explicitly remains unsigned`() {
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var writes = 0
        val settings = RollcallSettings(autoAnswerNumber = true)

        runCatching {
            processRollcallMonitorPoll(
                listOf(numberEvent("1111")), settings, mutableSetOf(), completed, attempts,
                { action -> action(); true }, { true }, { writes++; error("timeout") }, {},
            )
        }
        processRollcallMonitorPoll(
            listOf(numberEvent("1111", ownStatus = null)), settings, mutableSetOf(), completed, attempts,
            { action -> action(); true }, { true }, { writes++; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(numberEvent("2222", ownStatus = "未签")), settings,
            mutableSetOf(), completed, attempts, { action -> action(); true }, { true },
            { writes++; true }, {},
        )

        assertEquals(2, writes)
        assertEquals(setOf("n1"), completed)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `leave is terminal before first submission and clears an uncertain retry`() {
        val attempts = mutableMapOf("n1" to 1)
        val completed = mutableSetOf<String>()
        var writes = 0

        processRollcallMonitorPoll(
            listOf(numberEvent("2222", ownStatus = STATUS_LEAVE)),
            RollcallSettings(autoAnswerNumber = true),
            mutableSetOf(), completed, attempts,
            { action -> action(); true }, { true }, { writes++; true }, {},
        )

        assertEquals(0, writes)
        assertEquals(setOf("n1"), completed)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `pending failed number answer does not clear health while detail or threshold is unavailable`() {
        val attempts = mutableMapOf("n1" to 1) // positive = prior write result unknown
        var healthSuccesses = 0

        processRollcallMonitorPoll(
            listOf(numberEvent("", ownStatus = null)), RollcallSettings(autoAnswerNumber = true),
            mutableSetOf(), mutableSetOf(), attempts, { action -> action(); true }, { true },
            { true }, { healthSuccesses++ },
        )
        processRollcallMonitorPoll(
            listOf(numberEvent("2222").copy(progress = progress(1, 10))),
            RollcallSettings(
                autoAnswerNumber = true,
                waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_COUNT,
                waitBeforeAnswerCount = 5,
            ),
            mutableSetOf(), mutableSetOf(), attempts, { action -> action(); true }, { true },
            { true }, { healthSuccesses++ },
        )

        assertEquals(0, healthSuccesses)
        assertEquals(1, attempts["n1"])
    }

    @Test
    fun `expired signed stopped and account invalidated events never submit`() {
        val settings = RollcallSettings(autoAnswerRadar = true)
        val compatibleLateStatus = parsedOwnRollcallStatus(
            org.json.JSONObject("""{"status":"on_call","rollcall_status":"on_call_arrive_late"}"""),
        )
        assertEquals(STATUS_LATE, compatibleLateStatus)
        val candidates = listOf(
            RollcallEvent("expired", "课", "师", "雷达签到", "未签", isExpired = true),
            RollcallEvent("signed", "课", "师", "雷达签到", "未签", ownStatus = STATUS_SIGNED),
            RollcallEvent("late", "课", "师", "雷达签到", "未签", ownStatus = compatibleLateStatus),
            RollcallEvent("leave", "课", "师", "雷达签到", "未签", ownStatus = STATUS_LEAVE),
            RollcallEvent("summary-late", "课", "师", "雷达签到", STATUS_LATE),
            RollcallEvent("summary-leave", "课", "师", "雷达签到", STATUS_LEAVE),
        )
        var answers = 0
        processRollcallMonitorPoll(
            candidates, settings, mutableSetOf(), mutableSetOf(), mutableMapOf(),
            { action -> action(); true }, { true }, { answers++; true }, {},
        )
        processRollcallMonitorPoll(
            listOf(RollcallEvent("stopped", "课", "师", "雷达签到", "未签")),
            settings, mutableSetOf(), mutableSetOf(), mutableMapOf(),
            { false }, { true }, { answers++; true }, {},
        )
        assertEquals(0, answers)
    }

    @Test
    fun `conflicting own detail overrides signed summary until a later explicit absence`() {
        val settings = RollcallSettings(autoAnswerRadar = true)
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf("conflict" to 1)
        var writes = 0
        var healthSuccesses = 0

        processRollcallMonitorPoll(
            listOf(
                RollcallEvent(
                    "conflict", "课", "师", "雷达签到", STATUS_SIGNED,
                    ownStatus = STATUS_UNKNOWN,
                ),
            ),
            settings, notified, completed, attempts,
            { action -> action(); true }, { true }, { writes++; true }, { healthSuccesses++ },
        )

        assertEquals(0, writes)
        assertTrue(completed.isEmpty())
        assertEquals(0, healthSuccesses)
        assertEquals(1, attempts["conflict"])

        processRollcallMonitorPoll(
            listOf(
                RollcallEvent(
                    "conflict", "课", "师", "雷达签到", STATUS_SIGNED,
                    ownStatus = "未签",
                ),
            ),
            settings, notified, completed, attempts,
            { action -> action(); true }, { true }, { writes++; true }, { healthSuccesses++ },
        )

        assertEquals(1, writes)
        assertEquals(setOf("conflict"), completed)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `signed summary remains terminal when own detail is unavailable`() {
        val completed = mutableSetOf<String>()
        var writes = 0

        processRollcallMonitorPoll(
            listOf(RollcallEvent("summary", "课", "师", "雷达签到", STATUS_SIGNED, ownStatus = null)),
            RollcallSettings(autoAnswerRadar = true), mutableSetOf(), completed, mutableMapOf(),
            { action -> action(); true }, { true }, { writes++; true }, {},
        )

        assertEquals(0, writes)
        assertEquals(setOf("summary"), completed)
    }

    @Test
    fun `radar rejection retries at most three times and never reports healthy completion`() {
        val settings = RollcallSettings(autoAnswerRadar = true)
        val event = RollcallEvent("radar", "课", "师", "雷达签到", "未签", ownStatus = "未签")
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var writes = 0
        var healthSuccesses = 0

        repeat(4) {
            processRollcallMonitorPoll(
                listOf(event), settings, notified, completed, attempts,
                { action -> action(); true }, { true }, { writes++; false }, { healthSuccesses++ },
                maxAnswerAttempts = 3,
            )
        }

        assertEquals(3, writes)
        assertEquals(-3, attempts["radar"])
        assertTrue(completed.isEmpty())
        assertEquals(0, healthSuccesses)
    }

    @Test
    fun `network write failures stop retrying after bounded attempts`() {
        val completed = mutableSetOf<String>()
        val event = RollcallEvent("r1", "课", "师", "雷达签到", "未签")
        var writes = 0
        val attempts = mutableMapOf<String, Int>()
        repeat(4) { index ->
            runCatching {
                processRollcallMonitorPoll(
                    listOf(event.copy(ownStatus = if (index == 0) null else "未签")),
                    RollcallSettings(autoAnswerRadar = true), mutableSetOf(), completed,
                    attempts, { action -> action(); true }, { true }, { writes++; error("timeout") }, {}, maxAnswerAttempts = 3,
                )
            }
        }
        assertEquals(3, writes)
        assertTrue("unknown writes must not be presented as completed", completed.isEmpty())
        assertEquals(3, attempts["r1"])
    }

    @Test
    fun `changed settings after read prevent submission with stale threshold`() {
        var writes = 0
        processRollcallMonitorPoll(
            listOf(RollcallEvent("r", "课", "师", "雷达签到", "未签")),
            RollcallSettings(autoAnswerRadar = true),
            mutableSetOf(), mutableSetOf(), mutableMapOf(),
            { action -> action(); true }, { true }, { writes++; true }, {},
            settingsStillCurrent = { false },
        )
        assertEquals(0, writes)
    }

    @Test
    fun `unavailable notification does not prevent automatic answering or mark notified`() {
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        var answers = 0
        processRollcallMonitorPoll(
            listOf(numberEvent("1234")), RollcallSettings(autoAnswerNumber = true),
            notified, completed, mutableMapOf(), { action -> action(); true },
            { false }, { answers++; true }, {},
        )

        assertEquals(1, answers)
        assertTrue(notified.isEmpty())
        assertEquals(setOf("n1"), completed)
    }

    @Test
    fun `notification only rollcall remains pending until a channel accepts it`() {
        val event = RollcallEvent("qr", "课程", "老师", "二维码签到", "未签")
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        var accepted = false
        var notificationAttempts = 0
        fun poll() = processRollcallMonitorPoll(
            listOf(event), RollcallSettings(), notified, completed, mutableMapOf(),
            { action -> action(); true }, { notificationAttempts++; accepted },
            { error("QR rollcall cannot be answered automatically") }, {},
        )

        poll()
        assertTrue(notified.isEmpty())
        assertTrue(completed.isEmpty())
        accepted = true
        poll()
        poll()

        assertEquals(2, notificationAttempts)
        assertEquals(setOf("qr"), notified)
        assertEquals(setOf("qr"), completed)
    }

    private fun progress(present: Int, total: Int) = StudentRollcallProgress(
        total, present, total - present, 0, present * 100.0 / total, true,
    )

    private fun numberEvent(code: String, ownStatus: String? = "未签") = RollcallEvent(
        "n1", "课程", "老师", "数字签到", "未签", numberCode = code, ownStatus = ownStatus,
    )
}
