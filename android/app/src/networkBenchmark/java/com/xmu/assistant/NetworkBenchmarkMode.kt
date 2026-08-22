package com.xmu.assistant

internal const val BENCHMARK_MODE_EXTRA = "xmu_net_benchmark_mode"
internal const val BOUNDED_ROLLCALL_SMOKE_MODE = "bounded_rollcall_smoke_3"
internal const val FULL_READ_ONLY_MODE = "full_read_only"

internal fun networkBenchmarkPlanForMode(mode: String?): List<BenchmarkStep>? = when (mode) {
    BOUNDED_ROLLCALL_SMOKE_MODE -> listOf(
        BenchmarkStep(
            operation = NetworkOperation.ROLLCALL_STATUS,
            repetitions = 3,
            delayBetweenRunsMillis = 1_000L,
        ),
    )
    FULL_READ_ONLY_MODE -> readOnlyBenchmarkPlan()
    else -> null
}
