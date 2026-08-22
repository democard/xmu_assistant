package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkBenchmarkModeTest {
    @Test
    fun `unknown or missing mode performs no requests`() {
        assertNull(networkBenchmarkPlanForMode(null))
        assertNull(networkBenchmarkPlanForMode("typo"))
    }

    @Test
    fun `bounded smoke performs exactly three read only status requests`() {
        val plan = networkBenchmarkPlanForMode(BOUNDED_ROLLCALL_SMOKE_MODE)

        assertEquals(
            listOf(BenchmarkStep(NetworkOperation.ROLLCALL_STATUS, 3, 1_000L)),
            plan,
        )
    }

    @Test
    fun `full benchmark remains explicit`() {
        assertEquals(readOnlyBenchmarkPlan(), networkBenchmarkPlanForMode(FULL_READ_ONLY_MODE))
    }
}
