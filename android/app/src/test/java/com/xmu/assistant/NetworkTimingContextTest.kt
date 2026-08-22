package com.xmu.assistant

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTimingContextTest {
    @Test
    fun `scoped context is operation specific inherited by new workers and cleared after failure`() {
        val context = NetworkTimingContext("context-fixture", NetworkOperation.SCORES)
        val inherited = ConcurrentLinkedQueue<NetworkTimingContext?>()

        runCatching {
            NetworkTimingContextScope.withContext(context) {
                assertEquals(context, NetworkTimingContextScope.currentFor(NetworkOperation.SCORES))
                assertNull(NetworkTimingContextScope.currentFor(NetworkOperation.COURSES))
                val worker = Thread { inherited += NetworkTimingContextScope.currentFor(NetworkOperation.SCORES) }
                worker.start()
                worker.join()
                error("fixture failure")
            }
        }

        assertEquals(listOf(context), inherited.toList())
        assertNull(NetworkTimingContextScope.currentFor(NetworkOperation.SCORES))
    }

    @Test
    fun `collectors isolate concurrent contexts and reject foreign samples`() {
        val scores = NetworkTimingContext("scores-fixture", NetworkOperation.SCORES)
        val courses = NetworkTimingContext("courses-fixture", NetworkOperation.COURSES)
        val scoreCollector = NetworkTimingSamples.register(scores)
        val courseCollector = NetworkTimingSamples.register(courses)
        try {
            val gate = CountDownLatch(1)
            val first = Thread {
                gate.await(2, TimeUnit.SECONDS)
                NetworkTimingSamples.record(sample(scores.token, NetworkOperation.SCORES))
            }
            val second = Thread {
                gate.await(2, TimeUnit.SECONDS)
                NetworkTimingSamples.record(sample(courses.token, NetworkOperation.COURSES))
            }
            first.start(); second.start(); gate.countDown(); first.join(); second.join()

            NetworkTimingSamples.record(sample(null, NetworkOperation.SCORES))
            NetworkTimingSamples.record(sample("foreign", NetworkOperation.SCORES))
            NetworkTimingSamples.record(sample(scores.token, NetworkOperation.COURSES))

            assertEquals(listOf(NetworkOperation.SCORES), scoreCollector.samples().map { it.operation })
            assertEquals(listOf(NetworkOperation.COURSES), courseCollector.samples().map { it.operation })
        } finally {
            scoreCollector.close()
            courseCollector.close()
        }
    }

    private fun sample(token: String?, operation: NetworkOperation) = NetworkTimingSample(
        operation = operation,
        dnsMillis = 0,
        connectMillis = 0,
        tlsMillis = 0,
        timeToFirstByteMillis = 0,
        bodyMillis = 0,
        totalMillis = 0,
        reusedConnection = true,
        outcome = "success",
        contextToken = token,
    )
}
