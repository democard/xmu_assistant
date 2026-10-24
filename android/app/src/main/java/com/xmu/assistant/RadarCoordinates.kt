package com.xmu.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

internal const val RADAR_COORDINATE_SYSTEM = "GCJ-02"

internal data class RadarCoordinate(
    val id: String,
    val courseId: String,
    val courseTitle: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val source: String,
    val preferred: Boolean,
    val accuracyMeters: Float? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class RadarPreset(
    val id: String,
    val name: String,
    val zone: String,
    val latitude: Double,
    val longitude: Double,
    val confidence: String,
)

/**
 * Persistent, account-independent per-course radar locations (kept on logout).
 * A course may own any number of locations; exactly one is preferred.
 * Matching primarily uses the platform course id and falls back to the normalized course title so
 * a location captured before the course list finishes loading remains usable.
 */
internal class RadarCoordinateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun placesFor(courseId: String, courseTitle: String): List<RadarCoordinate> = synchronized(lock) {
        readAll()
        .filter { it.matches(courseId, courseTitle) }
        .sortedWith(
            compareBy<RadarCoordinate> { it.courseId != courseId }
                .thenByDescending { it.preferred }
                .thenBy { it.createdAtMillis },
        )
    }

    fun preferredFor(courseId: String, courseTitle: String): RadarCoordinate? =
        placesFor(courseId, courseTitle).firstOrNull()

    fun add(
        courseId: String,
        courseTitle: String,
        name: String,
        latitude: Double,
        longitude: Double,
        source: String,
        accuracyMeters: Float? = null,
        makePreferred: Boolean = true,
    ): RadarCoordinate = synchronized(lock) {
        require(name.isNotBlank()) { "地点名称不能为空" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "纬度无效" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "经度无效" }
        require(courseId.isNotBlank() || courseTitle.isNotBlank()) { "请先选择课程" }

        val all = readAll().toMutableList()
        val matched = all.filter { it.sameCourse(courseId, courseTitle) }
        val preferred = makePreferred || matched.isEmpty()
        if (preferred) {
            for (index in all.indices) {
                if (all[index].sameCourse(courseId, courseTitle) && all[index].preferred) {
                    all[index] = all[index].copy(preferred = false)
                }
            }
        }
        val item = RadarCoordinate(
            id = UUID.randomUUID().toString(),
            courseId = courseId,
            courseTitle = courseTitle.trim(),
            name = name.trim(),
            latitude = latitude,
            longitude = longitude,
            source = source,
            preferred = preferred,
            accuracyMeters = accuracyMeters?.takeIf { it.isFinite() && it >= 0f },
        )
        all += item
        writeAll(all)
        return item
    }

    fun setPreferred(id: String): Boolean = synchronized(lock) {
        val all = readAll().toMutableList()
        val selected = all.firstOrNull { it.id == id } ?: return false
        for (index in all.indices) {
            if (all[index].sameCourse(selected.courseId, selected.courseTitle)) {
                all[index] = all[index].copy(preferred = all[index].id == id)
            }
        }
        writeAll(all)
        return true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val all = readAll().toMutableList()
        val removed = all.firstOrNull { it.id == id } ?: return false
        all.removeAll { it.id == id }
        if (removed.preferred) {
            val replacementIndex = all.indexOfFirst { it.sameCourse(removed.courseId, removed.courseTitle) }
            if (replacementIndex >= 0) all[replacementIndex] = all[replacementIndex].copy(preferred = true)
        }
        writeAll(all)
        return true
    }

    fun clear() = synchronized(lock) { prefs.edit().remove(KEY_LOCATIONS).commit() }

    private fun readAll(): List<RadarCoordinate> {
        val raw = prefs.getString(KEY_LOCATIONS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val latitude = item.optDouble("latitude", Double.NaN)
                    val longitude = item.optDouble("longitude", Double.NaN)
                    if (!latitude.isFinite() || latitude !in -90.0..90.0) continue
                    if (!longitude.isFinite() || longitude !in -180.0..180.0) continue
                    add(
                        RadarCoordinate(
                            id = item.optRealString("id").ifBlank { UUID.randomUUID().toString() },
                            courseId = item.optRealString("courseId"),
                            courseTitle = item.optRealString("courseTitle"),
                            name = item.optRealString("name").ifBlank { "未命名地点" },
                            latitude = latitude,
                            longitude = longitude,
                            source = item.optRealString("source").ifBlank { "custom" },
                            preferred = item.optBoolean("preferred", false),
                            accuracyMeters = item.optDouble("accuracyMeters", Double.NaN)
                                .takeIf { it.isFinite() && it >= 0.0 }
                                ?.toFloat()
                                ?.takeIf(Float::isFinite),
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(items: List<RadarCoordinate>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("courseId", item.courseId)
                    .put("courseTitle", item.courseTitle)
                    .put("name", item.name)
                    .put("latitude", item.latitude)
                    .put("longitude", item.longitude)
                    .put("source", item.source)
                    .put("preferred", item.preferred)
                    .put("accuracyMeters", item.accuracyMeters ?: JSONObject.NULL)
                    .put("createdAtMillis", item.createdAtMillis),
            )
        }
        prefs.edit().putString(KEY_LOCATIONS, array.toString()).apply()
    }

    private fun RadarCoordinate.matches(id: String, title: String): Boolean {
        // 平台课程 id 可用时必须按 id 隔离，避免同名课程跨学期串用坐标；只有旧记录或
        // 尚未拿到 course_id 的进行中事件才回落到标准化标题匹配。
        if (id.isNotBlank() && courseId.isNotBlank()) return id == courseId
        return title.isNotBlank() && normalizeCourseTitle(title) == normalizeCourseTitle(courseTitle)
    }

    private fun RadarCoordinate.sameCourse(id: String, title: String): Boolean {
        if (id.isNotBlank() || courseId.isNotBlank()) return id.isNotBlank() && id == courseId
        return title.isNotBlank() && normalizeCourseTitle(title) == normalizeCourseTitle(courseTitle)
    }

    private companion object {
        val lock = Any()
        const val PREFS_NAME = "xmu_radar_coordinates"
        const val KEY_LOCATIONS = "course_locations_v1"
    }
}

internal fun loadXiangAnRadarPresets(context: Context): List<RadarPreset> = runCatching {
    val raw = context.assets.open("xiang_an_coordinates_gcj02.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val rows = JSONObject(raw).getJSONArray("locations")
    buildList {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            add(
                RadarPreset(
                    id = row.getString("id"),
                    name = row.getString("name"),
                    zone = row.getString("zone"),
                    latitude = row.getDouble("latitude"),
                    longitude = row.getDouble("longitude"),
                    confidence = row.optRealString("confidence"),
                ),
            )
        }
    }
}.getOrDefault(emptyList())

internal fun normalizeCourseTitle(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("[\\s·•_—–-]+"), "")

/** Standard WGS-84 to GCJ-02 conversion used for coordinates captured from Android location. */
internal fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
    if (longitude !in 72.004..137.8347 || latitude !in 0.8293..55.8271) return latitude to longitude
    var dLat = transformLatitude(longitude - 105.0, latitude - 35.0)
    var dLon = transformLongitude(longitude - 105.0, latitude - 35.0)
    val radLat = latitude / 180.0 * PI
    var magic = sin(radLat)
    magic = 1 - 0.00669342162296594323 * magic * magic
    val sqrtMagic = sqrt(magic)
    dLat = dLat * 180.0 / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * PI)
    dLon = dLon * 180.0 / (6378245.0 / sqrtMagic * kotlin.math.cos(radLat) * PI)
    return latitude + dLat to longitude + dLon
}

private fun transformLatitude(x: Double, y: Double): Double {
    var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
    result += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
    return result
}

private fun transformLongitude(x: Double, y: Double): Double {
    var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
    result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
    return result
}
