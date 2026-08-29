package com.xmu.assistant

import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BenchmarkSimulationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `local fixture exercises production timing path with isolation reuse and failure`() {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val coursesCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += request.method.orEmpty() to path
                return when (path) {
                    "/rollcall" -> fixture("""{"rollcalls":[]}""")
                    "/scores/terms" -> fixture("""{"terms":[{"id":"fixture-term"}]}""")
                    "/scores/rows" -> fixture("""{"rows":[{"score":90}]}""")
                    "/courses" -> if (coursesCalls.incrementAndGet() == 4) {
                        MockResponse()
                            .setBody("""{"courses":[{"id":"incomplete-fixture"}]}""".repeat(128))
                            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    } else {
                        fixture("""{"courses":[{"id":"fixture-course"}]}""")
                    }
                    "/courseware" -> fixture("""{"items":[{"id":"fixture-item"}]}""")
                        .setBodyDelay(2, TimeUnit.MILLISECONDS)
                    "/foreign" -> fixture("""{"foreign":true}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .eventListenerFactory(NetworkTimingEventListenerFactory(enabled = true))
            .build()
        val transport = OkHttpQueryTransport(client)
        val injectedForeignRequest = AtomicBoolean()
        val delays = mutableListOf<Long>()
        val records = mutableListOf<BenchmarkRunRecord>()
        val completions = mutableListOf<BenchmarkCompletion>()
        val runner = ReadOnlyBenchmarkRunner(
            operationExecutor = { operation ->
                when (operation) {
                    NetworkOperation.ROLLCALL_STATUS -> {
                        val response = transport.get("/rollcall", operation)
                        assertTrue(JSONObject(response.body).has("rollcalls"))
                    }
                    NetworkOperation.SCORES -> {
                        if (injectedForeignRequest.compareAndSet(false, true)) {
                            NetworkTimingContextScope.withContext(
                                NetworkTimingContext("foreign-token", NetworkOperation.SCORES),
                            ) {
                                transport.get("/foreign", NetworkOperation.SCORES)
                            }
                        }
                        val terms = transport.post("/scores/terms", operation)
                        assertEquals("fixture-term", JSONObject(terms.body).getJSONArray("terms").getJSONObject(0).getString("id"))
                        val rows = transport.post("/scores/rows", operation)
                        assertEquals(90, JSONObject(rows.body).getJSONArray("rows").getJSONObject(0).getInt("score"))
                    }
                    NetworkOperation.COURSES -> {
                        val response = transport.get("/courses", operation)
                        assertEquals("fixture-course", JSONObject(response.body).getJSONArray("courses").getJSONObject(0).getString("id"))
                    }
                    NetworkOperation.COURSEWARE -> {
                        val response = transport.get("/courseware", operation)
                        assertEquals("fixture-item", JSONObject(response.body).getJSONArray("items").getJSONObject(0).getString("id"))
                    }
                    else -> error("UnsafeBenchmarkOperation")
                }
                BenchmarkOperationResult()
            },
            delay = delays::add,
            onRun = records::add,
            onCompletion = completions::add,
        )

        val completion = runner.run("local-fixture")

        assertTrue(completion.completed)
        assertEquals(28, completion.completedRuns)
        assertEquals(listOf(completion), completions)
        assertEquals(24, delays.size)
        assertTrue(delays.all { it == 1_000L })
        assertEquals(setOf(
            NetworkOperation.ROLLCALL_STATUS,
            NetworkOperation.SCORES,
            NetworkOperation.COURSES,
            NetworkOperation.COURSEWARE,
        ), records.map { it.context.operation }.toSet())
        records.groupBy { it.context.operation }.values.forEach { assertEquals(7, it.size) }
        assertFalse(records.any { it.context.operation in setOf(NetworkOperation.LOGIN, NetworkOperation.DOWNLOAD) })

        val scoreRecords = records.filter { it.context.operation == NetworkOperation.SCORES }
        assertTrue(scoreRecords.all { it.aggregate.requestCount == 2 })
        val failedCourse = records.single { it.context.operation == NetworkOperation.COURSES && it.outcome != "success" }
        assertNotEquals("success", failedCourse.outcome)
        assertEquals(1, failedCourse.aggregate.requestFailureCount)
        assertTrue(failedCourse.aggregate.requestFailureClasses.contains(failedCourse.outcome))
        assertFalse(records.flatMap { it.aggregate.requestFailureClasses }.contains("foreign-token"))

        assertEquals(7, requests.count { it == "GET" to "/rollcall" })
        assertEquals(7, requests.count { it == "POST" to "/scores/terms" })
        assertEquals(7, requests.count { it == "POST" to "/scores/rows" })
        assertEquals(7, requests.count { it == "GET" to "/courses" })
        assertEquals(7, requests.count { it == "GET" to "/courseware" })
        assertEquals(1, requests.count { it == "GET" to "/foreign" })
        assertEquals(36, requests.size)
        assertEquals(35, records.sumOf { it.aggregate.requestCount })

        val successfulRequestAggregates = records.map { it.aggregate }.filter { it.requestSuccessCount > 0 }
        assertTrue(successfulRequestAggregates.first().reusedConnectionCount < successfulRequestAggregates.first().requestCount)
        assertTrue(successfulRequestAggregates.drop(1).any { it.reusedConnectionCount > 0 })
        printSanitizedEvidence(records)
    }

    private fun OkHttpQueryTransport.get(path: String, operation: NetworkOperation): QueryHttpResponse =
        execute(QueryHttpRequest(server.url(path).toString(), operation = operation))

    private fun OkHttpQueryTransport.post(path: String, operation: NetworkOperation): QueryHttpResponse =
        execute(
            QueryHttpRequest(
                url = server.url(path).toString(),
                method = "POST",
                contentType = "application/json; charset=utf-8",
                body = "{}",
                operation = operation,
            ),
        )

    private fun fixture(body: String): MockResponse = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun printSanitizedEvidence(records: List<BenchmarkRunRecord>) {
        records.groupBy { it.context.operation }.forEach { (operation, operationRecords) ->
            val requestCount = operationRecords.sumOf { it.aggregate.requestCount }
            val failureCount = operationRecords.sumOf { it.aggregate.requestFailureCount }
            val reuseCount = operationRecords.sumOf { it.aggregate.reusedConnectionCount }
            val failureClasses = operationRecords
                .flatMap { it.aggregate.requestFailureClasses }
                .toSortedSet()
                .joinToString(",")
                .ifBlank { "-" }
            val outcomes = operationRecords.map { it.outcome }.toSortedSet().joinToString(",")
            fun phases(selector: (BenchmarkTimingAggregate) -> Long): Pair<Long, Long> {
                val values = operationRecords.map { selector(it.aggregate) }.sorted()
                return values[values.size / 2] to values[((values.size * 95 + 99) / 100 - 1).coerceIn(values.indices)]
            }
            val dns = phases(BenchmarkTimingAggregate::dnsMedianMillis)
            val connect = phases(BenchmarkTimingAggregate::connectMedianMillis)
            val tls = phases(BenchmarkTimingAggregate::tlsMedianMillis)
            val ttfb = phases(BenchmarkTimingAggregate::timeToFirstByteMedianMillis)
            val body = phases(BenchmarkTimingAggregate::bodyMedianMillis)
            val total = phases(BenchmarkTimingAggregate::totalMedianMillis)
            println(
                    "SIMULATED_BASELINE operation=${operation.name} highLevelSamples=${operationRecords.size} " +
                    "requests=$requestCount failures=$failureCount reused=$reuseCount " +
                    "failureClasses=$failureClasses outcomes=$outcomes " +
                    "dnsMedianMs=${dns.first} dnsP95Ms=${dns.second} " +
                    "connectMedianMs=${connect.first} connectP95Ms=${connect.second} " +
                    "tlsMedianMs=${tls.first} tlsP95Ms=${tls.second} " +
                    "ttfbMedianMs=${ttfb.first} ttfbP95Ms=${ttfb.second} " +
                    "bodyMedianMs=${body.first} bodyP95Ms=${body.second} " +
                    "totalMedianMs=${total.first} totalP95Ms=${total.second}",
            )
        }
    }
}
