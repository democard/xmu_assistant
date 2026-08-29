package com.xmu.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class CoursewareDownloadBatchTest {
    @Test
    fun `selected courseware downloads use exactly two workers and preserve results`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstPair = CountDownLatch(2)

        val results = downloadCoursewareInParallel((1..5).toList()) { value ->
            val current = active.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, current) }
            firstPair.countDown()
            check(firstPair.await(2, TimeUnit.SECONDS)) { "two downloads did not overlap" }
            try {
                value * 10
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(2, maximumActive.get())
        assertEquals(listOf(10, 20, 30, 40, 50), results)
    }
}
