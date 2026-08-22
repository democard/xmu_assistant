package com.xmu.assistant

internal data class BenchmarkOperationResult(
    val hasPartialCoursewareFailure: Boolean = false,
)

internal data class BenchmarkRunRecord(
    val context: NetworkTimingContext,
    val count: Int,
    val outcome: String,
    val aggregate: BenchmarkTimingAggregate,
)

internal data class BenchmarkCompletion(
    val completedRuns: Int,
    val outcome: String,
    val completed: Boolean,
)

/** Owns the complete lifecycle of each isolated, high-level benchmark run. */
internal class ReadOnlyBenchmarkRunner(
    private val plan: List<BenchmarkStep> = readOnlyBenchmarkPlan(),
    private val operationExecutor: (NetworkOperation) -> BenchmarkOperationResult,
    private val delay: (Long) -> Unit = Thread::sleep,
    private val onRun: (BenchmarkRunRecord) -> Unit = {},
    private val onCompletion: (BenchmarkCompletion) -> Unit = {},
) {
    fun run(launchMarker: String): BenchmarkCompletion {
        var completedRuns = 0
        var completionOutcome = "success"
        var completed = true
        try {
            plan.forEach { step ->
                repeat(step.repetitions) { repetition ->
                    val context = NetworkTimingContext(
                        token = "$launchMarker-${completedRuns + 1}",
                        operation = step.operation,
                    )
                    val collector = NetworkTimingSamples.register(context)
                    var result: Result<BenchmarkOperationResult>
                    try {
                        result = runCatching {
                            NetworkTimingContextScope.withContext(context) {
                                operationExecutor(step.operation)
                            }
                        }
                    } finally {
                        collector.close()
                    }
                    completedRuns += 1
                    onRun(
                        BenchmarkRunRecord(
                            context = context,
                            count = repetition + 1,
                            outcome = benchmarkOutcome(
                                operation = step.operation,
                                error = result.exceptionOrNull(),
                                hasPartialCoursewareFailure = result.getOrNull()?.hasPartialCoursewareFailure == true,
                            ),
                            aggregate = aggregateBenchmarkTiming(collector.samples()),
                        ),
                    )
                    if (repetition + 1 < step.repetitions) delay(step.delayBetweenRunsMillis)
                }
            }
        } catch (error: Throwable) {
            completed = false
            completionOutcome = error.javaClass.simpleName.ifBlank { "Failure" }
        }
        return BenchmarkCompletion(completedRuns, completionOutcome, completed).also(onCompletion)
    }
}
