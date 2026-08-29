package com.xmu.assistant

internal data class BenchmarkStep(
    val operation: NetworkOperation,
    val repetitions: Int,
    val delayBetweenRunsMillis: Long,
)

/**
 * A deliberately small, sequential plan for the opt-in benchmark build.
 * It contains no downloads or roll-call submission operations.
 */
internal fun readOnlyBenchmarkPlan(): List<BenchmarkStep> = listOf(
    BenchmarkStep(NetworkOperation.ROLLCALL_STATUS, repetitions = 7, delayBetweenRunsMillis = 1_000L),
    BenchmarkStep(NetworkOperation.SCORES, repetitions = 7, delayBetweenRunsMillis = 1_000L),
    BenchmarkStep(NetworkOperation.COURSES, repetitions = 7, delayBetweenRunsMillis = 1_000L),
    BenchmarkStep(NetworkOperation.COURSEWARE, repetitions = 7, delayBetweenRunsMillis = 1_000L),
)

internal data class BenchmarkTimingAggregate(
    val requestCount: Int,
    val requestSuccessCount: Int,
    val requestFailureCount: Int,
    val requestFailureClasses: Set<String>,
    val dnsMedianMillis: Long,
    val connectMedianMillis: Long,
    val tlsMedianMillis: Long,
    val timeToFirstByteMedianMillis: Long,
    val bodyMedianMillis: Long,
    val totalMedianMillis: Long,
    val reusedConnectionCount: Int,
)

internal fun aggregateBenchmarkTiming(samples: List<NetworkTimingSample>): BenchmarkTimingAggregate {
    fun median(selector: (NetworkTimingSample) -> Long): Long =
        samples.map(selector).sorted().let { values -> values.getOrElse(values.size / 2) { 0L } }
    val failures = samples.filter { it.outcome != "success" }
    return BenchmarkTimingAggregate(
        requestCount = samples.size,
        requestSuccessCount = samples.count { it.outcome == "success" },
        requestFailureCount = failures.size,
        requestFailureClasses = failures.map { it.outcome }.toSet(),
        dnsMedianMillis = median(NetworkTimingSample::dnsMillis),
        connectMedianMillis = median(NetworkTimingSample::connectMillis),
        tlsMedianMillis = median(NetworkTimingSample::tlsMillis),
        timeToFirstByteMedianMillis = median(NetworkTimingSample::timeToFirstByteMillis),
        bodyMedianMillis = median(NetworkTimingSample::bodyMillis),
        totalMedianMillis = median(NetworkTimingSample::totalMillis),
        reusedConnectionCount = samples.count(NetworkTimingSample::reusedConnection),
    )
}

internal fun benchmarkOutcome(
    operation: NetworkOperation,
    error: Throwable?,
    hasPartialCoursewareFailure: Boolean,
): String = when {
    operation == NetworkOperation.COURSEWARE && hasPartialCoursewareFailure -> "PartialCoursewareFailure"
    error == null -> "success"
    else -> error.javaClass.simpleName.ifBlank { "Failure" }
}
