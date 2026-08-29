package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class RollcallEngine internal constructor(
    private val cookieHeader: String,
    private val statusTransport: QueryHttpTransport = OkHttpQueryTransport(),
) {
    private val baseUrl = "https://lnt.xmu.edu.cn"

    fun pollOnce(): List<RollcallEvent> {
        val json = getStatusJson("$baseUrl/api/radar/rollcalls")
        val rollcalls = json.optJSONArray("rollcalls") ?: JSONArray()
        return (0 until rollcalls.length()).mapNotNull { index ->
            val item = rollcalls.optJSONObject(index) ?: return@mapNotNull null
            // optRealString：显式 null 会被 optString 读成 "null"，穿透 isBlank 守卫
            val id = item.optRealString("rollcall_id").ifBlank { item.optRealString("id") }
            // 无 id 的事件无法去重（所有空 id 共享同一个去重键，后续事件会被误判重复丢弃），直接跳过
            if (id.isBlank()) return@mapNotNull null
            RollcallEvent(
                id = id,
                courseTitle = item.optString("course_title", item.optString("course_name", "未知课程")),
                teacher = listOf(item.optString("department_name"), item.optString("created_by_name"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "未知" },
                type = when {
                    item.optBoolean("is_radar") -> "雷达签到"
                    item.optBoolean("is_number") -> "数字签到"
                    else -> "二维码签到"
                },
                status = normalizedRollcallStatus(item.optString("status", "unknown")),
                deadline = firstString(item, "deadline", "end_time", "expired_at", "expire_at", "expires_at"),
                remainingSeconds = remainingSecondsFromDeadline(
                    firstString(item, "deadline", "end_time", "expired_at", "expire_at", "expires_at"),
                ),
            )
        }
    }

    private fun getStatusJson(url: String): JSONObject {
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        val response = statusTransport.execute(
            QueryHttpRequest(
                url = url,
                method = "GET",
                headers = headers,
                operation = NetworkOperation.ROLLCALL_STATUS,
            ),
        )
        if (response.code == 401 || response.code == 403) throw MainSessionExpiredException()
        // 会话过期时平台返回 302 跳身份域而非 401（query 客户端 followRedirects=false），
        // 误判为「网络失败」会让自动续登永不触发、签到静默失效
        if (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) {
            throw MainSessionExpiredException()
        }
        if (response.code !in 200..299) error("网络失败：${response.code}")
        return JSONObject(response.body)
    }

    fun answerNumber(rollcallId: String): Boolean {
        val detail = getJson("$baseUrl/api/rollcall/$rollcallId/student_rollcalls")
        val code = findNumberCode(detail) ?: return false
        val body = JSONObject()
            .put("deviceId", UUID.randomUUID().toString())
            .put("numberCode", code)
        return putJson("$baseUrl/api/rollcall/$rollcallId/answer_number_rollcall", body)
    }

    fun answerRadar(rollcallId: String): Boolean {
        val url = "$baseUrl/api/rollcall/$rollcallId/answer"
        val lat1 = 24.3
        val lon1 = 118.0
        val lat2 = 24.6
        val lon2 = 118.2
        val first = putJsonWithResponse(url, radarPayload(lat1, lon1))
        if (first.first) return true
        val second = putJsonWithResponse(url, radarPayload(lat2, lon2))
        if (second.first) return true
        // org.json 的 optDouble 对缺失键返回 NaN 而非 null，?: 拦不住；必须显式
        // has + isFinite，否则 NaN 贯穿 solveRadarCandidates（NaN 所有比较均 false）
        // 生成含 NaN 的候选坐标 → JSON 序列化抛异常 → 该签到每轮重试无限漏答（审查 MEDIUM）。
        val distance1 = first.second
            ?.takeIf { it.has("distance") }
            ?.optDouble("distance")
            ?.takeIf { it.isFinite() } ?: return false
        val distance2 = second.second
            ?.takeIf { it.has("distance") }
            ?.optDouble("distance")
            ?.takeIf { it.isFinite() } ?: return false
        val candidates = solveRadarCandidates(lat1, lon1, lat2, lon2, distance1, distance2) ?: return false
        return candidates.any { (lat, lon) -> putJson(url, radarPayload(lat, lon)) }
    }

    fun answer(event: RollcallEvent): Boolean = when (event.type) {
        "数字签到" -> answerNumber(event.id)
        "雷达签到" -> answerRadar(event.id)
        else -> false
    }

    private fun getJson(url: String): JSONObject {
        val conn = open(url)
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == 401 || code == 403) throw MainSessionExpiredException()
            // 302 跳身份域 = 会话过期（open() 已关闭自动跟随，重定向在此显式判定）
            if (code in 300..399 && isIdentityRedirect(url, conn.getHeaderField("Location"))) {
                throw MainSessionExpiredException()
            }
            if (code !in 200..299) error("网络失败：$code")
            // 流必须关闭（use），连接必须 disconnect：否则 keep-alive 连接无法归还池，
            // 常驻轮询下 socket/句柄持续累积
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun putJson(url: String, body: JSONObject): Boolean = putJsonWithResponse(url, body).first

    private fun putJsonWithResponse(url: String, body: JSONObject): Pair<Boolean, JSONObject?> {
        val conn = open(url)
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw MainSessionExpiredException()
            // 302 跳身份域 = 会话过期：与 GET 路径同判定，避免误当「应答失败」重试后续坐标
            if (code in 300..399 && isIdentityRedirect(url, conn.getHeaderField("Location"))) {
                throw MainSessionExpiredException()
            }
            val text = runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            return Pair(code in 200..299, text.takeIf { it.isNotBlank() }?.let { JSONObject(it) })
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            // 关闭自动跟随重定向：GET 跳转会把 Cookie 头原样转发给目标主机（含跨域），
            // 且 302→登录页 200 HTML 会被误判为普通解析失败而非会话过期；
            // 重定向由调用方显式判定（身份域 = 会话过期）。
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (cookieHeader.isNotBlank()) setRequestProperty("Cookie", cookieHeader)
        }
    }
}

fun radarPayload(lat: Double, lon: Double): JSONObject = JSONObject()
    .put("accuracy", 35)
    .put("altitude", 0)
    .put("altitudeAccuracy", JSONObject.NULL)
    .put("deviceId", UUID.randomUUID().toString())
    .put("heading", JSONObject.NULL)
    .put("latitude", lat)
    .put("longitude", lon)
    .put("speed", JSONObject.NULL)

fun solveRadarCandidates(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
    distance1: Double,
    distance2: Double,
): List<Pair<Double, Double>>? {
    val lat0 = (lat1 + lat2) / 2
    val lon0 = (lon1 + lon2) / 2
    val p1 = latLonToXY(lat1, lon1, lat0, lon0)
    val p2 = latLonToXY(lat2, lon2, lat0, lon0)
    val d = hypot(p2.first - p1.first, p2.second - p1.second)
    if (d > distance1 + distance2 || d < abs(distance1 - distance2) || d == 0.0) return null
    val along = (distance1.pow(2) - distance2.pow(2) + d.pow(2)) / (2 * d)
    val h2 = distance1.pow(2) - along.pow(2)
    if (h2 < 0) return null
    val height = sqrt(h2)
    val midX = p1.first + along * (p2.first - p1.first) / d
    val midY = p1.second + along * (p2.second - p1.second) / d
    val offX = -(p2.second - p1.second) * height / d
    val offY = (p2.first - p1.first) * height / d
    return listOf(
        xyToLatLon(midX + offX, midY + offY, lat0, lon0),
        xyToLatLon(midX - offX, midY - offY, lat0, lon0),
    )
}

private fun latLonToXY(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
    val radius = 6371000.0
    val x = Math.toRadians(lon - lon0) * radius * cos(Math.toRadians(lat0))
    val y = Math.toRadians(lat - lat0) * radius
    return Pair(x, y)
}

private fun xyToLatLon(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double> {
    val radius = 6371000.0
    val lat = lat0 + Math.toDegrees(y / radius)
    val lon = lon0 + Math.toDegrees(x / (radius * cos(Math.toRadians(lat0))))
    return Pair(lat, lon)
}

fun firstString(json: JSONObject, vararg keys: String): String =
    keys.firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } } ?: ""

fun findNumberCode(value: Any?, depth: Int = 0): String? {
    if (depth > 10 || value == null) return null
    return when (value) {
        is JSONObject -> {
            // optRealString：显式 null 会被 optString 读成 "null" 字面量，穿透
            // isNotBlank 守卫→提交 numberCode="null" 且被标记已处理（同文件
            // rollcall_id 同款陷阱，firstNotNullOfOrNull 系列均已迁移唯此处漏改）
            value.optRealString("number_code").takeIf { it.isNotBlank() }
                ?: value.keys().asSequence().firstNotNullOfOrNull { findNumberCode(value.opt(it), depth + 1) }
        }
        is JSONArray -> (0 until value.length()).asSequence().firstNotNullOfOrNull { findNumberCode(value.opt(it), depth + 1) }
        else -> null
    }
}
