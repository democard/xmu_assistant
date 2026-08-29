package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkBenchmarkSafetyTest {
    @Test
    fun `benchmark activity never reads credentials or invokes login`() {
        val source = sequenceOf(
            File("src/networkBenchmark/java/com/xmu/assistant/NetworkBenchmarkActivity.kt"),
            File("android/app/src/networkBenchmark/java/com/xmu/assistant/NetworkBenchmarkActivity.kt"),
        ).first { it.isFile }.readText()

        assertFalse(source.contains("settings.username"))
        assertFalse(source.contains("settings.password"))
        assertFalse(source.contains("TronclassLogin"))
        assertFalse(source.contains(".login("))
        assertFalse(source.contains("NetworkTimingSamples.register"))
        assertFalse(source.contains("NetworkTimingContextScope.withContext"))
        assertFalse(source.contains("aggregateBenchmarkTiming("))
        org.junit.Assert.assertTrue(source.contains("ReadOnlyBenchmarkRunner("))

        val simulationSource = sequenceOf(
            File("src/test/java/com/xmu/assistant/BenchmarkSimulationTest.kt"),
            File("android/app/src/test/java/com/xmu/assistant/BenchmarkSimulationTest.kt"),
        ).first { it.isFile }.readText()
        assertFalse(simulationSource.contains("NetworkTimingSample("))
    }
}
