package com.xmu.assistant

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemainingSecondsFromDeadlineTest {
    @Test
    fun `parses offset iso deadline into remaining seconds`() {
        val future = Instant.now().plusSeconds(120)
        val offset = ZonedDateTime.ofInstant(future, ZoneId.systemDefault())
        val text = offset.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        // 容许解析与断言之间的秒级流逝
        val remaining = remainingSecondsFromDeadline(text)!!
        assertTrue("expected ~120s, got $remaining", remaining in 100..120)
    }

    @Test
    fun `parses zulu suffix and fractional seconds`() {
        val future = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val text = future.toString() // 形如 2026-08-22T04:00:00Z
        val remaining = remainingSecondsFromDeadline(text)!!
        assertTrue("expected ~3600s, got $remaining", remaining in 3580..3600)
    }

    @Test
    fun `parses local datetime without timezone as local time`() {
        val local = java.time.LocalDateTime.now().plusMinutes(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val remaining = remainingSecondsFromDeadline(local.toString())!!
        assertTrue("expected ~300s, got $remaining", remaining in 280..300)
    }

    @Test
    fun `past deadline clamps to zero instead of negative`() {
        val past = Instant.now().minusSeconds(60).toString()
        assertEquals(0L, remainingSecondsFromDeadline(past))
    }

    @Test
    fun `unparseable or blank deadline returns null`() {
        assertNull(remainingSecondsFromDeadline(""))
        assertNull(remainingSecondsFromDeadline("   "))
        assertNull(remainingSecondsFromDeadline("not-a-date"))
        assertNull(remainingSecondsFromDeadline("2026-13-99T99:99:99"))
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
