package com.xmu.assistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

const val RADAR_EARTH_RADIUS_METERS = 6371000.0
private const val RADAR_ANGLE_EPS = 1e-12

/**
 * Intersection of two spherical great-circle distance circles. Distances are
 * meters, coordinates degrees. Tangency returns the same point twice; invalid
 * circles and coincident/antipodal centers return null. This geometric model
 * does not establish the server's actual distance metric.
 */
fun solveSphericalRadarCandidates(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
    distance1: Double, distance2: Double,
): List<Pair<Double, Double>>? {
    if (!listOf(lat1, lon1, lat2, lon2, distance1, distance2).all { it.isFinite() }) return null
    if (abs(lat1) > 90 || abs(lat2) > 90) return null
    if (distance1 !in 0.0..PI * RADAR_EARTH_RADIUS_METERS ||
        distance2 !in 0.0..PI * RADAR_EARTH_RADIUS_METERS) return null

    fun vector(lat: Double, lon: Double): DoubleArray {
        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon % 360.0)
        return doubleArrayOf(cos(phi) * cos(lam), cos(phi) * sin(lam), sin(phi))
    }
    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
    val a = vector(lat1, lon1)
    val b = vector(lat2, lon2)
    val centersCross = cross(a, b)
    val sinDelta = hypot(hypot(centersCross[0], centersCross[1]), centersCross[2])
    val cosDelta = a.indices.sumOf { a[it] * b[it] }.coerceIn(-1.0, 1.0)
    val delta = atan2(sinDelta, cosDelta)
    if (sinDelta <= RADAR_ANGLE_EPS) return null
    val r1 = distance1 / RADAR_EARTH_RADIUS_METERS
    val r2 = distance2 / RADAR_EARTH_RADIUS_METERS
    val margin = minOf(r1 + r2 - delta, delta - abs(r1 - r2), 2 * PI - r1 - r2 - delta)
    if (margin < -RADAR_ANGLE_EPS) return null
    val normal = DoubleArray(3) { centersCross[it] / sinDelta }
    val tangent = cross(normal, a)
    val x = cos(r1)
    // Stable form of cos(r2)-cos(delta)*cos(r1) for small angular radii.
    val sd = sin(delta / 2)
    val s1 = sin(r1 / 2)
    val s2 = sin(r2 / 2)
    val y = 2 * (sd * sd - s2 * s2 + cosDelta * s1 * s1) / sinDelta
    val sinR1 = sin(r1)
    val h2 = (sinR1 - abs(y)) * (sinR1 + abs(y))
    val height = when {
        margin <= RADAR_ANGLE_EPS -> 0.0
        h2 < 0 -> return null
        else -> sqrt(h2)
    }
    fun coordinate(sign: Double): Pair<Double, Double> {
        val p = DoubleArray(3) { x * a[it] + y * tangent[it] + sign * height * normal[it] }
        return Math.toDegrees(atan2(p[2], hypot(p[0], p[1]))) to Math.toDegrees(atan2(p[1], p[0]))
    }
    return listOf(coordinate(1.0), coordinate(-1.0))
}
