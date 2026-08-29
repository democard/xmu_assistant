package com.xmu.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestGateTest {
    @Test
    fun `gate blocks duplicate key and releases it after finish`() {
        val gate = RequestGate()

        assertTrue(gate.tryStart("scores"))
        assertFalse(gate.tryStart("scores"))
        assertTrue(gate.tryStart("courses"))
        gate.finish("scores")
        assertTrue(gate.tryStart("scores"))
    }

    @Test
    fun `two concurrent callers admit exactly one identical request`() {
        val gate = RequestGate()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val admitted = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    if (gate.tryStart("rollcall")) admitted.incrementAndGet()
                }
            }
            check(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(2, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(1, admitted.get())
    }
}
