package com.xmu.assistant

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarGeometryTest {
    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val p1 = Math.toRadians(a.first)
        val p2 = Math.toRadians(b.first)
        val s1 = sin((p2 - p1) / 2)
        val s2 = sin(Math.toRadians(b.second - a.second) / 2)
        val h = (s1 * s1 + cos(p1) * cos(p2) * s2 * s2).coerceIn(0.0, 1.0)
        return 2 * RADAR_EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    private fun recover(a: Pair<Double, Double>, b: Pair<Double, Double>, target: Pair<Double, Double>): List<Pair<Double, Double>> {
        val d1 = distance(a, target)
        val d2 = distance(b, target)
        val result = solveSphericalRadarCandidates(a.first, a.second, b.first, b.second, d1, d2)
        assertNotNull(result)
        val candidates = result!!
        assertEquals(2, candidates.size)
        assertTrue(candidates.minOf { distance(it, target) } < 0.001)
        for (point in candidates) {
            assertEquals(d1, distance(a, point), 0.001)
            assertEquals(d2, distance(b, point), 0.001)
        }
        return candidates
    }

    @Test fun fixedProbesRecoverTargets() {
        for (target in listOf(24.4371 to 118.0975, 24.45 to 118.1)) {
            recover(24.3 to 118.0, 24.6 to 118.2, target)
        }
    }

    @Test fun antimeridianPolesLargeCirclesAndSmallSpans() {
        recover(10.0 to 179.0, 10.0 to -179.0, 10.5 to 180.0)
        recover(89.0 to 0.0, 89.0 to 90.0, 90.0 to 0.0)
        recover(0.0 to 0.0, 0.0 to 30.0, 0.0 to 160.0)
        recover(0.0 to 0.0, 0.0 to 0.001, 0.0002 to 0.0004)
    }

    @Test fun tangenciesAndZeroRadiusReturnDuplicatePoint() {
        for (target in listOf(0.0 to 0.4, 0.0 to -0.2, 0.0 to 0.0, 0.0 to 180.0)) {
            val candidates = recover(0.0 to 0.0, 0.0 to 1.0, target)
            assertEquals(candidates[0], candidates[1])
        }
    }

    @Test fun impossibleAndDegenerateCircles() {
        for ((d1, d2) in listOf(1000.0 to 1000.0, 200000.0 to 1000.0,
            (PI * RADAR_EARTH_RADIUS_METERS - 1000) to (PI * RADAR_EARTH_RADIUS_METERS - 1000))) {
            assertNull(solveSphericalRadarCandidates(0.0, 0.0, 0.0, 1.0, d1, d2))
        }
        for (lon in listOf(0.0, 360.0, 180.0)) {
            assertNull(solveSphericalRadarCandidates(0.0, 0.0, 0.0, lon, 1000.0, 1000.0))
        }
    }

    @Test fun invalidValues() {
        for (index in 0..5) {
            for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val a = doubleArrayOf(24.3, 118.0, 24.6, 118.2, 50000.0, 50000.0)
                a[index] = value
                assertNull(solveSphericalRadarCandidates(a[0], a[1], a[2], a[3], a[4], a[5]))
            }
        }
        assertNull(solveSphericalRadarCandidates(91.0, 0.0, 0.0, 1.0, 1000.0, 1000.0))
        assertNull(solveSphericalRadarCandidates(0.0, 0.0, -91.0, 1.0, 1000.0, 1000.0))
        assertNull(solveSphericalRadarCandidates(0.0, 0.0, 0.0, 1.0, -1.0, 1000.0))
        assertNull(solveSphericalRadarCandidates(0.0, 0.0, 0.0, 1.0, 1000.0, PI * RADAR_EARTH_RADIUS_METERS + 1))
    }
}
