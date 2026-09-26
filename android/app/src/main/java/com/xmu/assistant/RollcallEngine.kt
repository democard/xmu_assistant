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

private val defaultRollcallAnswerTransport: QueryHttpTransport by lazy {
    // 数字签到 PUT 的响应可能丢失；关闭 OkHttp 的连接失败自动重放，确保引擎每次
    // 调用只发送一次写请求。后续是否重试由监控读取本人明细后显式决定。
    OkHttpQueryTransport(XmuHttpClients.query.newBuilder().retryOnConnectionFailure(false).build())
}

class RollcallEngine internal constructor(
    private val cookieHeader: String,
    private val statusTransport: QueryHttpTransport = OkHttpQueryTransport(),
    private val answerTransport: QueryHttpTransport = defaultRollcallAnswerTransport,
) {
    private val baseUrl = "https://lnt.xmu.edu.cn"

    fun pollOnce(): List<RollcallEvent> {
        val json = getStatusJson("$baseUrl/api/radar/rollcalls")
        // 空数组是正常的「没有签到」；缺字段/错误类型则不能当作健康空列表，
        // 否则网关错误 JSON 会让监控清除失败状态并静默漏掉签到。
        val rollcalls = json.optJSONArray("rollcalls")
            ?: error("签到列表格式异常：缺少有效的 rollcalls 数组")
        return (0 until rollcalls.length()).mapNotNull { index ->
            val item = rollcalls.optJSONObject(index) ?: return@mapNotNull null
            // optRealString：显式 null 会被 optString 读成 "null"，穿透 isBlank 守卫
            val id = item.optRealString("rollcall_id").ifBlank { item.optRealString("id") }
            // 无 id 的事件无法去重（所有空 id 共享同一个去重键，后续事件会被误判重复丢弃），直接跳过
            if (id.isBlank()) return@mapNotNull null
            val deadline = firstString(
                item, "deadline", "rollcall_end_time", "end_time", "expired_at", "expire_at", "expires_at",
            )
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
                deadline = deadline,
                remainingSeconds = remainingSecondsFromDeadline(deadline),
                isExpired = item.optBoolean("is_expired", false),
            )
        }
    }

    /** 页面和监控共用：每个活动 id 同轮最多一次只读明细 GET。 */
    fun pollWithDetails(username: String = ""): List<RollcallEvent> {
        val detailById = mutableMapOf<String, JSONObject?>()
        return pollOnce().map { event ->
            val detail = if (detailById.containsKey(event.id)) {
                detailById[event.id]
            } else {
                val loaded = try {
                    getStudentRollcallDetail(event.id)
                } catch (error: MainSessionExpiredException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                detailById[event.id] = loaded
                loaded
            } ?: return@map event
            val parsed = parseStudentRollcallDetails(detail, username)
            event.copy(
                numberCode = if (event.type == "数字签到") parsed.numberCode else "",
                progress = parsed.progress,
                ownStatus = parsed.ownStatus,
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
        if (isKnownLoginForm(response.body)) throw MainSessionExpiredException()
        return JSONObject(response.body)
    }

    private fun getStudentRollcallDetail(rollcallId: String): JSONObject? {
        val url = "$baseUrl/api/rollcall/$rollcallId/student_rollcalls"
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Accept" to "application/json, text/plain, */*",
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        val response = statusTransport.execute(
            QueryHttpRequest(url, "GET", headers, operation = NetworkOperation.ROLLCALL_STATUS),
        )
        if (response.code == 401) throw MainSessionExpiredException()
        if (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) {
            throw MainSessionExpiredException()
        }
        if (response.code == 403) return null
        if (response.code !in 200..299) error("网络失败：${response.code}")
        if (isKnownLoginForm(response.body)) throw MainSessionExpiredException()
        return JSONObject(response.body)
    }

    fun answerNumber(rollcallId: String): Boolean {
        val detail = getStudentRollcallDetail(rollcallId) ?: return false
        val code = findNumberCode(detail) ?: return false
        val body = JSONObject()
            .put("deviceId", UUID.randomUUID().toString())
            .put("numberCode", code)
        return putNumberAnswer("$baseUrl/api/rollcall/$rollcallId/answer_number_rollcall", body)
    }

    private fun answerNumber(event: RollcallEvent): Boolean {
        if (event.numberCode.isBlank()) return false
        val body = JSONObject()
            .put("deviceId", UUID.randomUUID().toString())
            .put("numberCode", event.numberCode)
        return putNumberAnswer("$baseUrl/api/rollcall/${event.id}/answer_number_rollcall", body)
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
        "数字签到" -> answerNumber(event)
        "雷达签到" -> answerRadar(event.id)
        else -> false
    }

    private fun putJson(url: String, body: JSONObject): Boolean = putJsonWithResponse(url, body).first

    private fun putJsonWithResponse(url: String, body: JSONObject): Pair<Boolean, JSONObject?> {
        // 雷达链路保持原实现和原错误语义；本轮只修数字签到。
        val conn = open(url)
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code == 401 || code == 403) throw MainSessionExpiredException()
            if (code in 300..399 && isIdentityRedirect(url, conn.getHeaderField("Location"))) {
                throw MainSessionExpiredException()
            }
            val text = runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val parsed = text.takeIf { it.isNotBlank() }
                ?.let { value -> runCatching { JSONObject(value) }.getOrNull() }
            return Pair(code in 200..299, parsed)
        } finally {
            conn.disconnect()
        }
    }

    /** 数字签到需要把明确的 HTTP 拒绝与回执不明（transport 抛异常）分开。 */
    private fun putNumberAnswer(url: String, body: JSONObject): Boolean {
        val response = executePut(url, body)
        if (response.code == 401) throw MainSessionExpiredException()
        if (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) {
            throw MainSessionExpiredException()
        }
        // 身份域可能直接返回 200 登录表单；这并不是签到成功回执。
        // 只识别已知登录页，不改变 403 等明确拒绝的含义。
        if (response.code in 200..299 && isKnownLoginForm(response.body)) {
            throw MainSessionExpiredException()
        }
        return when {
            response.code in 200..299 -> true
            response.code in setOf(400, 403, 404, 409, 422, 429) ->
                throw RollcallAnswerRejectedException(response.code)
            else -> throw RollcallAnswerUncertainException(response.code)
        }
    }

    private fun executePut(url: String, body: JSONObject): QueryHttpResponse {
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Accept" to "application/json, text/plain, */*",
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        return answerTransport.execute(
            QueryHttpRequest(
                url = url,
                method = "PUT",
                headers = headers,
                contentType = "application/json; charset=utf-8",
                body = body.toString(),
                oneShot = true,
                operation = NetworkOperation.ROLLCALL_STATUS,
            ),
        )
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (cookieHeader.isNotBlank()) setRequestProperty("Cookie", cookieHeader)
        }
    }
}

internal class RollcallAnswerRejectedException(val responseCode: Int) :
    IllegalStateException("数字签到提交失败：平台返回 $responseCode")

internal class RollcallAnswerUncertainException(val responseCode: Int) :
    IllegalStateException("数字签到提交结果未知：平台返回 $responseCode")

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
    keys.firstNotNullOfOrNull { key -> json.optRealString(key).takeIf { it.isNotBlank() } } ?: ""

private val numberCodeKeys = arrayOf("number_code", "numberCode", "rollcall_number_code", "rollcallNumberCode")

fun findNumberCode(value: Any?, depth: Int = 0): String? {
    if (depth > 10 || value == null) return null
    return when (value) {
        is JSONObject -> {
            // optRealString：显式 null 会被 optString 读成 "null" 字面量，穿透
            // isNotBlank 守卫→提交 numberCode="null" 且被标记已处理（同文件
            // rollcall_id 同款陷阱，firstNotNullOfOrNull 系列均已迁移唯此处漏改）
            firstString(value, *numberCodeKeys).takeIf { it.isNotBlank() }
                ?: value.keys().asSequence().firstNotNullOfOrNull { findNumberCode(value.opt(it), depth + 1) }
        }
        is JSONArray -> (0 until value.length()).asSequence().firstNotNullOfOrNull { findNumberCode(value.opt(it), depth + 1) }
        else -> null
    }
}
