package com.xmu.assistant

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors
import java.util.UUID

/**
 * Present only in the networkBenchmark APK.  It reads existing encrypted
 * settings, keeps every result in memory, and executes only read-only calls.
 */
class NetworkBenchmarkActivity : Activity() {
    private val benchmarkExecutor = Executors.newSingleThreadExecutor()
    private val boundedStatusTransport by lazy {
        OkHttpQueryTransport(
            XmuHttpClients.query.newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.NETWORK_METRICS)
        val plan = networkBenchmarkPlanForMode(intent.getStringExtra(BENCHMARK_MODE_EXTRA))
        if (plan == null) {
            finish()
            return
        }
        val launchMarker = intent.getStringExtra(LAUNCH_MARKER_EXTRA)
            ?.takeIf { it.matches(Regex("[a-f0-9]{8}")) }
            ?: UUID.randomUUID().toString().take(8)
        benchmarkExecutor.execute {
            try {
                runBenchmark(launchMarker, plan)
            } finally {
                benchmarkExecutor.shutdown()
                runOnUiThread(::finish)
            }
        }
    }

    private fun runBenchmark(launchMarker: String, plan: List<BenchmarkStep>) {
        val settings = AssistantSettings(applicationContext)
        val cookieHeader = settings.cookieHeader
        if (cookieHeader.isBlank()) {
            logCompletion(launchMarker, BenchmarkCompletion(0, "MissingExistingSession", false))
            return
        }
        val cachedCourseId = academicCacheFromJson(settings.academicCacheJson).courses.firstOrNull()?.id.orEmpty()
        ReadOnlyBenchmarkRunner(
            plan = plan,
            operationExecutor = { operation -> runStep(operation, settings, cookieHeader, cachedCourseId) },
            onRun = ::logAggregate,
            onCompletion = { completion -> logCompletion(launchMarker, completion) },
        ).run(launchMarker)
    }

    private fun logAggregate(record: BenchmarkRunRecord) {
        val aggregate = record.aggregate
        val failureClasses = aggregate.requestFailureClasses.sorted().joinToString(",").ifBlank { "-" }
        Log.i(
            LOG_TAG,
            "run=${record.context.token} operation=${record.context.operation.name} outcome=${record.outcome} " +
                "count=${record.count} requests=${aggregate.requestCount} " +
                "requestSuccessCount=${aggregate.requestSuccessCount} requestFailureCount=${aggregate.requestFailureCount} " +
                "requestFailureClasses=$failureClasses dnsMs=${aggregate.dnsMedianMillis} connectMs=${aggregate.connectMedianMillis} " +
                "tlsMs=${aggregate.tlsMedianMillis} ttfbMs=${aggregate.timeToFirstByteMedianMillis} " +
                "bodyMs=${aggregate.bodyMedianMillis} totalMs=${aggregate.totalMedianMillis} " +
                "reused=${aggregate.reusedConnectionCount == aggregate.requestCount && aggregate.requestCount > 0}",
        )
    }

    private fun logCompletion(launchMarker: String, completion: BenchmarkCompletion) {
        Log.i(
            LOG_TAG,
            "run=$launchMarker-complete operation=BENCHMARK_COMPLETE outcome=${completion.outcome} " +
                "count=${completion.completedRuns} requests=0 requestSuccessCount=0 requestFailureCount=0 " +
                "requestFailureClasses=- dnsMs=0 connectMs=0 tlsMs=0 ttfbMs=0 bodyMs=0 totalMs=0 reused=false",
        )
    }

    private fun runStep(
        operation: NetworkOperation,
        settings: AssistantSettings,
        cookieHeader: String,
        cachedCourseId: String,
    ): BenchmarkOperationResult = when (operation) {
        NetworkOperation.LOGIN -> error("UnsafeBenchmarkOperation")
        NetworkOperation.ROLLCALL_STATUS -> {
            RollcallEngine(cookieHeader, boundedStatusTransport).pollOnce()
            BenchmarkOperationResult()
        }
        NetworkOperation.SCORES -> {
            XmuScoreAutoQueryClient.existingSessionOnly(settings.scoreCookieHeader).fetchScoresFromExistingSession()
            BenchmarkOperationResult()
        }
        NetworkOperation.COURSES -> {
            CoursewareClient(applicationContext, cookieHeader).fetchCourses()
            BenchmarkOperationResult()
        }
        NetworkOperation.COURSEWARE -> {
            check(cachedCourseId.isNotBlank()) { "MissingCachedCourse" }
            val items = CoursewareClient(applicationContext, cookieHeader).fetchCourseware(cachedCourseId)
            BenchmarkOperationResult(hasPartialCoursewareFailure = items.any { it.failureReason.isNotBlank() })
        }
        NetworkOperation.DOWNLOAD,
        NetworkOperation.UNKNOWN,
        -> error("UnsafeBenchmarkOperation")
    }

    private companion object {
        const val LOG_TAG = "XmuNetBench"
        const val LAUNCH_MARKER_EXTRA = "xmu_net_benchmark_run"
    }
}
