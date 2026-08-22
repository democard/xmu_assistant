package com.xmu.assistant

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal enum class NetworkOperation {
    LOGIN,
    ROLLCALL_STATUS,
    SCORES,
    SCHEDULE,
    COURSES,
    COURSEWARE,
    DOWNLOAD,
    EXAM,
    UNKNOWN,
}

internal data class NetworkTimingSample(
    val operation: NetworkOperation,
    val dnsMillis: Long,
    val connectMillis: Long,
    val tlsMillis: Long,
    val timeToFirstByteMillis: Long,
    val bodyMillis: Long,
    val totalMillis: Long,
    val reusedConnection: Boolean,
    val outcome: String,
    val contextToken: String? = null,
)

/** An in-process-only tag. It is never serialized into an HTTP request. */
internal data class NetworkTimingContext(
    val token: String,
    val operation: NetworkOperation,
)

internal object NetworkTimingContextScope {
    private val context = object : InheritableThreadLocal<NetworkTimingContext?>() {}

    fun currentFor(operation: NetworkOperation): NetworkTimingContext? =
        context.get()?.takeIf { it.operation == operation }

    fun <T> withContext(value: NetworkTimingContext, block: () -> T): T {
        val previous = context.get()
        context.set(value)
        return try {
            block()
        } finally {
            if (previous == null) context.remove() else context.set(previous)
        }
    }
}

internal class NetworkTimingAccumulator(
    private val operation: NetworkOperation,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var callStartNanos: Long? = null
    private var dnsStartNanos: Long? = null
    private var connectStartNanos: Long? = null
    private var tlsStartNanos: Long? = null
    private var responseHeadersStartNanos: Long? = null
    private var responseBodyStartNanos: Long? = null
    private var dnsNanos = 0L
    private var connectNanos = 0L
    private var tlsNanos = 0L
    private var connectionWasReused = false
    private var sawConnectStart = false
    private var terminalSample: NetworkTimingSample? = null

    fun callStart() {
        callStartNanos = nowNanos()
    }

    fun dnsStart() {
        dnsStartNanos = nowNanos()
    }

    fun dnsEnd() {
        closeDns(nowNanos())
    }

    fun connectStart() {
        sawConnectStart = true
        connectStartNanos = nowNanos()
    }

    fun connectEnd() {
        closeConnect(nowNanos())
    }

    fun connectFailed() {
        val endNanos = nowNanos()
        closeTls(endNanos)
        closeConnect(endNanos)
    }

    fun secureConnectStart() {
        val startNanos = nowNanos()
        closeConnect(startNanos)
        tlsStartNanos = startNanos
    }

    fun secureConnectEnd() {
        closeTls(nowNanos())
    }

    fun connectionAcquired(reusedConnection: Boolean) {
        connectionWasReused = reusedConnection
    }

    fun connectionAcquired() {
        connectionAcquired(reusedConnection = !sawConnectStart)
    }

    fun responseHeadersStart() {
        responseHeadersStartNanos = nowNanos()
    }

    fun responseBodyStart() {
        responseBodyStartNanos = nowNanos()
    }

    fun callEnd(): NetworkTimingSample = complete("success")

    fun callFailed(error: IOException): NetworkTimingSample = complete(
        error.javaClass.simpleName.ifBlank { IOException::class.java.simpleName },
    )

    private fun complete(outcome: String): NetworkTimingSample {
        terminalSample?.let { return it }
        val endNanos = nowNanos()
        closeDns(endNanos)
        closeTls(endNanos)
        closeConnect(endNanos)
        return sample(outcome, endNanos).also { terminalSample = it }
    }

    private fun sample(outcome: String, endNanos: Long): NetworkTimingSample {
        return NetworkTimingSample(
            operation = operation,
            dnsMillis = nanosToMillis(dnsNanos),
            connectMillis = nanosToMillis(connectNanos),
            tlsMillis = nanosToMillis(tlsNanos),
            timeToFirstByteMillis = nanosToMillis(
                responseHeadersStartNanos?.let { start -> elapsedBetween(callStartNanos, start) } ?: 0L,
            ),
            bodyMillis = nanosToMillis(
                responseBodyStartNanos?.let { start -> elapsedBetween(start, endNanos) } ?: 0L,
            ),
            totalMillis = nanosToMillis(elapsedBetween(callStartNanos, endNanos)),
            reusedConnection = connectionWasReused,
            outcome = outcome,
        )
    }

    private fun closeDns(endNanos: Long) {
        dnsNanos += elapsedSince(dnsStartNanos, endNanos)
        dnsStartNanos = null
    }

    private fun closeConnect(endNanos: Long) {
        connectNanos += elapsedSince(connectStartNanos, endNanos)
        connectStartNanos = null
    }

    private fun closeTls(endNanos: Long) {
        tlsNanos += elapsedSince(tlsStartNanos, endNanos)
        tlsStartNanos = null
    }

    private fun elapsedSince(startNanos: Long?, endNanos: Long): Long =
        startNanos?.let { elapsedBetween(it, endNanos) } ?: 0L

    private fun elapsedBetween(startNanos: Long?, endNanos: Long): Long =
        startNanos?.let { start -> (endNanos - start).coerceAtLeast(0L) } ?: 0L

    private fun nanosToMillis(nanos: Long): Long = nanos / NANOS_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal object NetworkTimingSamples {
    private val collectors = ConcurrentHashMap<NetworkTimingCollectorKey, ConcurrentLinkedQueue<NetworkTimingSample>>()

    fun register(context: NetworkTimingContext): NetworkTimingSampleCollector {
        val key = NetworkTimingCollectorKey(context.token, context.operation)
        val samples = ConcurrentLinkedQueue<NetworkTimingSample>()
        check(collectors.putIfAbsent(key, samples) == null) { "DuplicateTimingContext" }
        return NetworkTimingSampleCollector(key, samples)
    }

    fun record(sample: NetworkTimingSample) {
        val token = sample.contextToken ?: return
        collectors[NetworkTimingCollectorKey(token, sample.operation)]?.add(sample)
    }

    internal fun unregister(key: NetworkTimingCollectorKey, samples: ConcurrentLinkedQueue<NetworkTimingSample>) =
        collectors.remove(key, samples)
}

internal data class NetworkTimingCollectorKey(val token: String, val operation: NetworkOperation)

internal class NetworkTimingSampleCollector internal constructor(
    private val key: NetworkTimingCollectorKey,
    private val queue: ConcurrentLinkedQueue<NetworkTimingSample>,
) : AutoCloseable {
    fun samples(): List<NetworkTimingSample> = queue.toList()

    override fun close() {
        NetworkTimingSamples.unregister(key, queue)
    }
}

internal class NetworkTimingEventListenerFactory(
    private val enabled: Boolean,
    private val sink: (NetworkTimingSample) -> Unit = NetworkTimingSamples::record,
    private val nowNanos: () -> Long = System::nanoTime,
) : EventListener.Factory {
    override fun create(call: Call): EventListener {
        if (!enabled) return EventListener.NONE
        val operation = call.request().tag(NetworkOperation::class.java) ?: return EventListener.NONE
        val contextToken = call.request().tag(NetworkTimingContext::class.java)?.token
        return NetworkTimingEventListener(NetworkTimingAccumulator(operation, nowNanos), contextToken, sink)
    }
}

private class NetworkTimingEventListener(
    private val accumulator: NetworkTimingAccumulator,
    private val contextToken: String?,
    private val sink: (NetworkTimingSample) -> Unit,
) : EventListener() {
    override fun callStart(call: Call) = accumulator.callStart()

    override fun dnsStart(call: Call, domainName: String) = accumulator.dnsStart()

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = accumulator.dnsEnd()

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = accumulator.connectStart()

    override fun secureConnectStart(call: Call) = accumulator.secureConnectStart()

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = accumulator.secureConnectEnd()

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) = accumulator.connectEnd()

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) = accumulator.connectFailed()

    override fun connectionAcquired(call: Call, connection: Connection) = accumulator.connectionAcquired()

    override fun responseHeadersStart(call: Call) = accumulator.responseHeadersStart()

    override fun responseBodyStart(call: Call) = accumulator.responseBodyStart()

    override fun callEnd(call: Call) {
        emit(accumulator.callEnd())
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit(accumulator.callFailed(ioe))
    }

    private fun emit(sample: NetworkTimingSample) {
        runCatching { sink(sample.copy(contextToken = contextToken)) }
    }
}
