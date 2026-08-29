package com.xmu.assistant

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyBenchmarkRunnerTest {
    @Test
    fun `runner owns plan contexts aggregation failure continuation completion and delay`() {
        var attempt = 0
        val delays = mutableListOf<Long>()
        val runs = mutableListOf<BenchmarkRunRecord>()
        val completions = mutableListOf<BenchmarkCompletion>()
        val runner = ReadOnlyBenchmarkRunner(
            plan = listOf(BenchmarkStep(NetworkOperation.COURSES, repetitions = 2, delayBetweenRunsMillis = 17)),
            operationExecutor = { operation ->
                assertEquals(NetworkOperation.COURSES, operation)
                attempt += 1
                if (attempt == 1) throw IOException("fixture-only")
                BenchmarkOperationResult()
            },
            delay = delays::add,
            onRun = runs::add,
            onCompletion = completions::add,
        )

        val completion = runner.run("fixture")

        assertEquals(listOf(17L), delays)
        assertEquals(listOf("IOException", "success"), runs.map { it.outcome })
        assertEquals(listOf(1, 2), runs.map { it.count })
        assertEquals(2, completion.completedRuns)
        assertEquals(listOf(completion), completions)
        assertTrue(completion.completed)
        NetworkTimingSamples.register(NetworkTimingContext("fixture-1", NetworkOperation.COURSES)).close()
    }
}
