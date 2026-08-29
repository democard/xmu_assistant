package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkScenarioTest {
    @Test
    fun `plan contains only safe read-only operations at conservative cadence`() {
        val plan = readOnlyBenchmarkPlan()

        assertEquals(
            listOf(
                NetworkOperation.ROLLCALL_STATUS,
                NetworkOperation.SCORES,
                NetworkOperation.COURSES,
                NetworkOperation.COURSEWARE,
            ),
            plan.map { it.operation },
        )
        assertFalse(plan.any { it.operation == NetworkOperation.LOGIN })
        assertTrue(plan.all { it.repetitions >= 7 })
        assertFalse(plan.any { it.operation == NetworkOperation.DOWNLOAD })
        assertFalse(plan.any { it.operation.name.contains("ANSWER") })
    }

    @Test
    fun `aggregate separates request failures and courseware partial outcome without details`() {
        val aggregate = aggregateBenchmarkTiming(
            listOf(
                sample(NetworkOperation.COURSEWARE, "success", totalMillis = 20),
                sample(NetworkOperation.COURSEWARE, "IOException", totalMillis = 30),
            ),
        )

        assertEquals(2, aggregate.requestCount)
        assertEquals(1, aggregate.requestSuccessCount)
        assertEquals(1, aggregate.requestFailureCount)
        assertEquals(setOf("IOException"), aggregate.requestFailureClasses)
        assertEquals("PartialCoursewareFailure", benchmarkOutcome(NetworkOperation.COURSEWARE, null, hasPartialCoursewareFailure = true))
    }

    private fun sample(operation: NetworkOperation, outcome: String, totalMillis: Long) = NetworkTimingSample(
        operation = operation,
        dnsMillis = 0,
        connectMillis = 0,
        tlsMillis = 0,
        timeToFirstByteMillis = totalMillis,
        bodyMillis = 0,
        totalMillis = totalMillis,
        reusedConnection = true,
        outcome = outcome,
    )
}
