package com.xmu.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedParallelTest {
    @Test
    fun `bounded map limits active workers preserves order and shuts down`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstWave = CountDownLatch(3)
        lateinit var executor: ExecutorService

        val results = boundedParallelMap(
            items = (1..6).toList(),
            maxParallel = 3,
            executorFactory = { size ->
                Executors.newFixedThreadPool(size).also { executor = it }
            },
        ) { value ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            firstWave.countDown()
            check(firstWave.await(2, TimeUnit.SECONDS)) { "three workers did not overlap" }
            try {
                value * 10
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(listOf(10, 20, 30, 40, 50, 60), results)
        assertEquals(3, maximumActive.get())
        assertTrue(executor.isShutdown)
    }

    @Test
    fun `empty bounded map does not create an executor`() {
        val factoryCalled = AtomicBoolean(false)

        val results = boundedParallelMap<Int, Int>(
            items = emptyList(),
            maxParallel = 3,
            executorFactory = { size ->
                factoryCalled.set(true)
                Executors.newFixedThreadPool(size)
            },
        ) { it * 10 }

        assertTrue(results.isEmpty())
        assertFalse(factoryCalled.get())
    }
}
