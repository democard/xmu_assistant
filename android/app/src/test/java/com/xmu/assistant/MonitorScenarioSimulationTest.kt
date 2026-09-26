package com.xmu.assistant

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic polling timelines. All writes are captured by an in-memory transport. */
class MonitorScenarioSimulationTest {
    @Test
    fun `one rejected code must not starve a second rollcall before its deadline`() {
        val scenario = Scenario(mapOf("a" to listOf(400, 400, 200), "b" to listOf(200)))
        fun events() = listOf(scenario.number("a", deadline = 60), scenario.number("b", deadline = 10))

        assertThrows(RollcallAnswerRejectedException::class.java) { scenario.poll(events()) }
        assertEquals("the other valid event must be answered in the same poll", listOf("a", "b"), scenario.transport.ids)
        assertEquals(setOf("b"), scenario.completed)
        assertEquals(-1, scenario.attempts["a"])
        assertEquals(0, scenario.healthSuccesses)

        scenario.nowSeconds = 5
        assertThrows(RollcallAnswerRejectedException::class.java) { scenario.poll(events()) }
        scenario.nowSeconds = 10
        scenario.poll(events())

        assertEquals(listOf("a", "b", "a", "a"), scenario.transport.ids)
        assertEquals(setOf("a", "b"), scenario.completed)
        assertEquals(listOf("a", "b"), scenario.notifications)
        assertTrue(scenario.attempts.isEmpty())
        assertEquals(1, scenario.healthSuccesses)
    }

    @Test
    fun `duplicate list rows consume at most one answer attempt per event in each poll`() {
        val scenario = Scenario(emptyMap())
        val a = scenario.number("a").copy(type = "雷达签到")
        val b = scenario.number("b").copy(type = "雷达签到")
        val writes = mutableListOf<String>()
        val answer: (RollcallEvent) -> Boolean = { event -> writes += event.id; event.id == "b" }
        val settings = RollcallSettings(autoAnswerRadar = true)

        scenario.poll(listOf(a, a, a, b), settings, answer)
        assertEquals(listOf("a", "b"), writes)
        assertEquals(-1, scenario.attempts["a"])
        assertEquals(setOf("b"), scenario.completed)
        repeat(3) {
            scenario.nowSeconds += 5
            scenario.poll(listOf(a, a, a, b), settings, answer)
        }

        assertEquals(listOf("a", "b", "a", "a"), writes)
        assertEquals(-3, scenario.attempts["a"])
        assertEquals(listOf("a", "b"), scenario.notifications)
        assertEquals(0, scenario.healthSuccesses)
    }

    @Test
    fun `timeout waits for an explicit later absence while other events still finish`() {
        val scenario = Scenario(mapOf("a" to listOf(IOException("simulated timeout"), 200), "b" to listOf(200)))
        assertThrows(IOException::class.java) {
            scenario.poll(listOf(scenario.number("a"), scenario.number("b")))
        }
        assertEquals(setOf("b"), scenario.completed)
        assertEquals(1, scenario.attempts["a"])

        scenario.nowSeconds = 5
        scenario.poll(listOf(scenario.number("a").copy(ownStatus = STATUS_UNKNOWN), scenario.number("b")))
        assertEquals(listOf("a", "b"), scenario.transport.ids)
        assertEquals(0, scenario.healthSuccesses)

        scenario.nowSeconds = 10
        scenario.poll(listOf(scenario.number("a").copy(numberCode = "0043"), scenario.number("b")))
        assertEquals(listOf("a", "b", "a"), scenario.transport.ids)
        assertEquals(setOf("a", "b"), scenario.completed)
        assertTrue(scenario.attempts.isEmpty())
        assertEquals(1, scenario.healthSuccesses)
    }

    @Test
    fun `pause during a write cannot commit old state or clear a replacement monitor`() {
        val scenario = Scenario(mapOf("a" to listOf(200), "b" to listOf(200)))
        val oldToken = scenario.token
        scenario.poll(listOf(scenario.number("a"), scenario.number("b")), answer = { event ->
            scenario.engine.answer(event).also {
                scenario.coordinator.requestInvalidateCurrent()
                scenario.token = requireNotNull(scenario.coordinator.start(true))
            }
        }, token = oldToken)

        scenario.coordinator.complete(oldToken)
        assertTrue(scenario.coordinator.isCurrent(scenario.token, true))
        assertTrue(scenario.completed.isEmpty())
        assertEquals(0, scenario.healthSuccesses)
        assertEquals(listOf("a"), scenario.transport.ids)

        scenario.nowSeconds = 5
        scenario.poll(listOf(scenario.number("a").copy(ownStatus = STATUS_SIGNED), scenario.number("b")))
        assertEquals(listOf("a", "b"), scenario.transport.ids)
        assertEquals(setOf("a", "b"), scenario.completed)
        assertEquals(listOf("a", "b"), scenario.notifications)
    }

    @Test
    fun `expired session stops later writes and invalidated monitor cannot deliver late results`() {
        val scenario = Scenario(mapOf("a" to listOf(LOGIN_FORM), "b" to listOf(200)))
        assertThrows(MainSessionExpiredException::class.java) {
            scenario.poll(listOf(scenario.number("a"), scenario.number("b")))
        }
        scenario.coordinator.requestInvalidateCurrent()
        scenario.nowSeconds = 5
        scenario.poll(listOf(scenario.number("b"), scenario.number("late")))

        assertEquals(listOf("a"), scenario.transport.ids)
        assertEquals(listOf("a", "b"), scenario.notifications)
        assertTrue(scenario.completed.isEmpty())
        assertTrue(scenario.attempts.isEmpty())
        assertEquals(0, scenario.healthSuccesses)
        assertFalse(scenario.coordinator.hasCurrent())
    }

    @Test
    fun `mixed event failures still process independent events and report the first error`() {
        val scenario = Scenario(mapOf("a" to listOf(400), "b" to listOf(IOException("simulated timeout")), "c" to listOf(200)))
        val error = assertThrows(RollcallAnswerRejectedException::class.java) {
            scenario.poll(listOf(scenario.number("a"), scenario.number("b"), scenario.number("c")))
        }

        assertEquals(400, error.responseCode)
        assertEquals(listOf("a", "b", "c"), scenario.transport.ids)
        assertEquals(setOf("c"), scenario.completed)
        assertEquals(mapOf("a" to -1, "b" to 1), scenario.attempts)
        assertEquals(0, scenario.healthSuccesses)
    }

    @Test
    fun `cancellation interruption and fatal errors stop later writes immediately`() {
        for (failure in listOf(CancellationException("cancelled"), InterruptedException("interrupted"), AssertionError("fatal"))) {
            val scenario = Scenario(mapOf("a" to listOf(failure), "b" to listOf(200)))
            val actual = runCatching {
                scenario.poll(listOf(scenario.number("a"), scenario.number("b")))
            }.exceptionOrNull()

            assertSame(failure, actual)
            assertEquals(listOf("a"), scenario.transport.ids)
            assertTrue(scenario.completed.isEmpty())
            assertEquals(0, scenario.healthSuccesses)
        }
    }

    @Test
    fun `notification rejection then recovery delivers a pending QR event only once`() {
        val scenario = Scenario(emptyMap())
        val event = scenario.number("qr").copy(type = "二维码签到")
        scenario.notificationAvailable = false
        scenario.poll(listOf(event))
        assertTrue(scenario.notified.isEmpty())
        assertTrue(scenario.completed.isEmpty())

        scenario.nowSeconds = 5
        scenario.notificationAvailable = true
        scenario.poll(listOf(event))
        scenario.nowSeconds = 10
        scenario.poll(listOf(event))

        assertEquals(listOf("qr"), scenario.notifications)
        assertEquals(setOf("qr"), scenario.completed)
        assertTrue(scenario.transport.ids.isEmpty())
    }

    private class Scenario(outcomes: Map<String, List<Any>>) {
        val coordinator = MonitorWorkerCoordinator()
        var token = requireNotNull(coordinator.start(true))
        var nowSeconds = 0L
        var notificationAvailable = true
        var healthSuccesses = 0
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        val notifications = mutableListOf<String>()
        val transport = ScriptedTransport(outcomes)
        val engine = RollcallEngine("offline-session", transport, transport)

        fun number(id: String, deadline: Long = 60) = RollcallEvent(
            id, "课程 $id", "模拟教师", "数字签到", "未签", numberCode = "0042",
            ownStatus = "未签", remainingSeconds = maxOf(0, deadline - nowSeconds),
        )

        fun poll(
            events: List<RollcallEvent>,
            settings: RollcallSettings = RollcallSettings(autoAnswerNumber = true),
            answer: (RollcallEvent) -> Boolean = engine::answer,
            token: Long = this.token,
        ) = processRollcallMonitorPoll(
            events, settings, notified, completed, attempts,
            runIfActive = { action -> coordinator.runIfCurrent(token, true, action) },
            onNotify = { event ->
                if (notificationAvailable) notifications += event.id
                notificationAvailable
            },
            onAnswer = answer,
            onSuccess = { healthSuccesses++ },
        )
    }

    private class ScriptedTransport(outcomes: Map<String, List<Any>>) : QueryHttpTransport {
        private val pending = outcomes.mapValues { ArrayDeque(it.value) }
        val ids = mutableListOf<String>()

        override fun execute(request: QueryHttpRequest): QueryHttpResponse {
            check(request.method == "PUT") { "simulation permits captured PUTs only" }
            val id = request.url.substringAfter("/api/rollcall/").substringBefore('/')
            ids += id
            return when (val outcome = requireNotNull(pending[id]).removeFirst()) {
                is Throwable -> throw outcome
                is Int -> QueryHttpResponse(request.url, outcome, null, "", emptyMap())
                is String -> QueryHttpResponse(request.url, 200, null, outcome, emptyMap())
                else -> error("unsupported simulation outcome")
            }
        }
    }

    companion object {
        private const val LOGIN_FORM =
            "<form action='https://c-identity.xmu.edu.cn/auth/realms/xmu/login-actions/authenticate'>login</form>"
    }
}
