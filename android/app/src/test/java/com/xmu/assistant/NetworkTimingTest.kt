package com.xmu.assistant

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTimingTest {
    @Test
    fun `accumulator records the expected privacy-safe phases`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.SCORES, clock::now)

        accumulator.callStart()
        clock.advanceMillis(2); accumulator.dnsStart()
        clock.advanceMillis(3); accumulator.dnsEnd()
        clock.advanceMillis(4); accumulator.connectStart()
        clock.advanceMillis(5); accumulator.connectEnd()
        accumulator.connectionAcquired(reusedConnection = false)
        clock.advanceMillis(7); accumulator.responseHeadersStart()
        accumulator.responseBodyStart()
        clock.advanceMillis(11)

        assertEquals(
            NetworkTimingSample(
                operation = NetworkOperation.SCORES,
                dnsMillis = 3,
                connectMillis = 5,
                tlsMillis = 0,
                timeToFirstByteMillis = 21,
                bodyMillis = 11,
                totalMillis = 32,
                reusedConnection = false,
                outcome = "success",
            ),
            accumulator.callEnd(),
        )
    }

    @Test
    fun `multiple connect attempts accumulate and failures expose only the class`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.SCORES, clock::now)

        accumulator.callStart()
        accumulator.connectStart()
        clock.advanceMillis(4); accumulator.connectEnd()
        clock.advanceMillis(2); accumulator.connectStart()
        clock.advanceMillis(6); accumulator.connectEnd()
        accumulator.connectionAcquired(reusedConnection = false)
        clock.advanceMillis(3)

        val sample = accumulator.callFailed(IOException("https://private.example/?cookie=secret"))

        assertEquals(10, sample.connectMillis)
        assertEquals("IOException", sample.outcome)
        assertTrue(sample.toString().contains("IOException"))
        assertTrue(!sample.toString().contains("private.example"))
    }

    @Test
    fun `failed connection attempt is finalized before the next successful connection`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.SCORES, clock::now)

        accumulator.callStart()
        accumulator.connectStart()
        clock.advanceMillis(2); accumulator.secureConnectStart()
        clock.advanceMillis(3); accumulator.connectFailed()
        clock.advanceMillis(4); accumulator.connectStart()
        clock.advanceMillis(5); accumulator.connectEnd()
        accumulator.connectionAcquired(reusedConnection = false)
        clock.advanceMillis(1)

        val sample = accumulator.callEnd()

        assertEquals(7, sample.connectMillis)
        assertEquals(3, sample.tlsMillis)
        assertEquals(15, sample.totalMillis)
        assertEquals("success", sample.outcome)
    }

    @Test
    fun `terminal failure safely finalizes every open phase without response timing`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.COURSES, clock::now)

        accumulator.callStart()
        clock.advanceMillis(1); accumulator.dnsStart()
        clock.advanceMillis(2); accumulator.connectStart()
        clock.advanceMillis(3); accumulator.secureConnectStart()
        clock.advanceMillis(4)

        val sample = accumulator.callFailed(IOException("fixture failure"))

        assertEquals(9, sample.dnsMillis)
        assertEquals(3, sample.connectMillis)
        assertEquals(4, sample.tlsMillis)
        assertEquals(0, sample.timeToFirstByteMillis)
        assertEquals(0, sample.bodyMillis)
        assertEquals(10, sample.totalMillis)
        assertEquals("IOException", sample.outcome)
    }

    @Test
    fun `terminal sampling is idempotent and never produces negative timing`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.COURSES, clock::now)

        accumulator.callStart()
        clock.advanceMillis(3); accumulator.dnsStart()
        clock.advanceMillis(4); accumulator.connectStart()
        clock.rewindMillis(20)

        val failure = accumulator.callFailed(IOException("fixture failure"))
        clock.advanceMillis(100)
        val repeated = accumulator.callEnd()

        assertEquals(failure, repeated)
        assertTrue(listOf(
            failure.dnsMillis,
            failure.connectMillis,
            failure.tlsMillis,
            failure.timeToFirstByteMillis,
            failure.bodyMillis,
            failure.totalMillis,
        ).all { it >= 0 })
    }

    @Test
    fun `disabled and untagged calls use the no-op listener`() {
        val call = OkHttpClient().newCall(Request.Builder().url("https://fixture.invalid/").build())

        assertSame(EventListener.NONE, NetworkTimingEventListenerFactory(enabled = false).create(call))
        assertSame(EventListener.NONE, NetworkTimingEventListenerFactory(enabled = true).create(call))
    }

    @Test
    fun `TLS callbacks split TCP and TLS timing without overlap`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.LOGIN, clock::now)

        accumulator.callStart()
        accumulator.connectStart()
        clock.advanceMillis(5); accumulator.secureConnectStart()
        clock.advanceMillis(7); accumulator.secureConnectEnd()
        accumulator.connectEnd()

        val sample = accumulator.callEnd()

        assertEquals(5, sample.connectMillis)
        assertEquals(7, sample.tlsMillis)
        assertEquals(12, sample.totalMillis)
    }

    @Test
    fun `factory listener preserves non-overlapping TCP and TLS callback timing`() {
        val clock = FakeNanoClock(0L)
        val samples = mutableListOf<NetworkTimingSample>()
        val call = OkHttpClient().newCall(
            Request.Builder()
                .url("https://fixture.invalid/")
                .tag(NetworkOperation::class.java, NetworkOperation.LOGIN)
                .build(),
        )
        val listener = NetworkTimingEventListenerFactory(
            enabled = true,
            sink = samples::add,
            nowNanos = clock::now,
        ).create(call)
        val address = InetSocketAddress.createUnresolved("fixture.invalid", 443)

        listener.callStart(call)
        listener.connectStart(call, address, Proxy.NO_PROXY)
        clock.advanceMillis(5); listener.secureConnectStart(call)
        clock.advanceMillis(7); listener.secureConnectEnd(call, null)
        listener.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_1_1)
        listener.callEnd(call)

        assertEquals(NetworkOperation.LOGIN, samples.single().operation)
        assertEquals(5, samples.single().connectMillis)
        assertEquals(7, samples.single().tlsMillis)
        assertEquals(12, samples.single().totalMillis)
    }

    @Test
    fun `connection acquired without connect start is marked reused`() {
        val clock = FakeNanoClock(0L)
        val accumulator = NetworkTimingAccumulator(NetworkOperation.COURSES, clock::now)

        accumulator.callStart()
        accumulator.connectionAcquired()
        clock.advanceMillis(2)

        val sample = accumulator.callEnd()

        assertTrue(sample.reusedConnection)
        assertEquals(0, sample.connectMillis)
        assertEquals(0, sample.tlsMillis)
    }

    private class FakeNanoClock(private var nanos: Long) {
        fun now(): Long = nanos

        fun advanceMillis(millis: Long) {
            nanos += millis * NANOS_PER_MILLISECOND
        }

        fun rewindMillis(millis: Long) {
            nanos -= millis * NANOS_PER_MILLISECOND
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
