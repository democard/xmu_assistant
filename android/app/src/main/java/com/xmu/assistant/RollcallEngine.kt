package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

private val defaultRollcallAnswerTransport: QueryHttpTransport by lazy {
    // 签到 PUT 的响应可能丢失；关闭 OkHttp 的连接失败自动重放，确保引擎每次
    // 调用只发送一次写请求。后续是否重试由监控读取本人明细后显式决定。
    OkHttpQueryTransport(XmuHttpClients.query.newBuilder().retryOnConnectionFailure(false).build())
}

class RollcallEngine internal constructor(
    private val cookieHeader: String,
    private val statusTransport: QueryHttpTransport = OkHttpQueryTransport(),
    private val answerTransport: QueryHttpTransport = defaultRollcallAnswerTransport,
    private val radarCoordinateProvider: ((RollcallEvent) -> RadarCoordinate?)? = null,
) {
    private val baseUrl = "https://lnt.xmu.edu.cn"
    private val numberCodeLookup = RollcallNumberCodeLookup(cookieHeader, statusTransport)
    var lastRadarFailure: String? = null
        private set
    private var lastRadarRejectedCode: Int? = null

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
                courseId = item.optRealString("course_id").ifBlank { item.optJSONObject("course")?.optRealString("id").orEmpty() },
                rollcallTime = firstString(item, "rollcall_time", "start_time", "created_at", "time"),
            )
        }
    }

    /** 页面和监控共用：每个活动 id 同轮最多一次只读明细 GET。 */
    fun pollWithDetails(username: String = ""): List<RollcallEvent> {
        val detailById = mutableMapOf<String, JSONObject?>()
        val codeById = mutableMapOf<String, String>()
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
            }
            val parsed = detail?.let { parseStudentRollcallDetails(it, username) }
            val code = if (event.type == "数字签到") codeById.getOrPut(event.id) {
                numberCodeLookup.complete(
                    event.id, detail, event.courseId, event.rollcallTime, event.courseTitle, event.deadline,
                )
            } else ""
            event.copy(
                numberCode = code,
                progress = parsed?.progress,
                ownStatus = parsed?.ownStatus,
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

    fun answerNumber(rollcallId: String, courseId: String = "", rollcallTime: String = ""): Boolean {
        val detail = getStudentRollcallDetail(rollcallId)
        val code = numberCodeLookup.complete(rollcallId, detail, courseId, rollcallTime)
        if (code.isBlank()) return false
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
        lastRadarFailure = null
        lastRadarRejectedCode = null
        val url = "$baseUrl/api/rollcall/$rollcallId/answer"
        val deviceId = UUID.randomUUID().toString()
        fun submit(lat: Double, lon: Double, stage: String): RadarReply? {
            val reply = try {
                putRadarAnswer(url, radarPayload(lat, lon, deviceId))
            } catch (_: IOException) {
                lastRadarRejectedCode = null
                lastRadarFailure = "雷达签到失败：${stage}网络回执未收到，提交结果未知；请核实本人记录"
                return null
            }
            lastRadarFailure = "雷达签到失败：$stage：${reply.detail}"
            lastRadarRejectedCode = reply.code.takeIf { it in setOf(400, 403, 404, 409, 422, 429) }
            return reply
        }
        val distances = mutableListOf<Double>()
        listOf(24.3 to 118.0, 24.6 to 118.2).forEachIndexed { index, (lat, lon) ->
            val reply = submit(lat, lon, "第${index + 1}次距离查询") ?: return false
            if (reply.success) { lastRadarFailure = null; return true }
            val distance = (reply.data?.opt("distance") as? Number)?.toDouble()
            if (distance == null || !distance.isFinite() || distance < 0) {
                lastRadarFailure = "${lastRadarFailure.orEmpty()}；缺少有效的数值 distance"
                return false
            }
            distances += distance
        }
        val candidates = solveRadarCandidates(24.3, 118.0, 24.6, 118.2, distances[0], distances[1])
        if (candidates == null) {
            lastRadarFailure = "雷达签到失败：两次距离无法形成球面交点，请核对距离单位和返回规则"
            return false
        }
        candidates.distinct().forEachIndexed { index, (lat, lon) ->
            val reply = submit(lat, lon, "候选位置${index + 1}提交") ?: return false
            if (reply.success) { lastRadarFailure = null; return true }
        }
        return false
    }

    /**
     * Production radar path: submit the course's selected location exactly once. Unlike the
     * legacy distance solver, this never sends probe coordinates and therefore cannot mutate an
     * attendance record before the user has configured the course.
     */
    internal fun answerRadarAt(rollcallId: String, coordinate: RadarCoordinate): Boolean {
        lastRadarFailure = null
        lastRadarRejectedCode = null
        val reply = try {
            putRadarAnswer(
                "$baseUrl/api/rollcall/$rollcallId/answer",
                radarPayload(coordinate.latitude, coordinate.longitude),
            )
        } catch (_: IOException) {
            lastRadarFailure = "雷达签到失败：网络回执未收到，提交结果未知；请核实本人记录"
            return false
        }
        if (reply.success) return true
        lastRadarRejectedCode = reply.code.takeIf { it in setOf(400, 403, 404, 409, 422, 429) }
        lastRadarFailure = "雷达签到失败：${reply.detail}"
        return false
    }

    fun answer(event: RollcallEvent): Boolean = when (event.type) {
        "数字签到" -> answerNumber(event)
        "雷达签到" -> if (
            if (radarCoordinateProvider == null) {
                // 保留给兼容性测试和旧调用方；应用监控始终注入课程坐标提供器。
                answerRadar(event.id)
            } else {
                val coordinate = radarCoordinateProvider.invoke(event)
                    ?: error("雷达签到未提交：${event.courseTitle}尚未配置课程坐标")
                answerRadarAt(event.id, coordinate)
            }
        ) true else {
            val detail = lastRadarFailure ?: "雷达签到提交失败"
            val code = lastRadarRejectedCode
            if (code != null) throw RollcallAnswerRejectedException(code, detail)
            else error(detail)
        }
        else -> false
    }

    private data class RadarReply(val success: Boolean, val data: JSONObject?, val detail: String, val code: Int)

    private fun putRadarAnswer(url: String, body: JSONObject): RadarReply {
        val response = executePut(url, body)
        if (response.code == 401 ||
            (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) ||
            isKnownLoginForm(response.body)
        ) throw MainSessionExpiredException()
        val parsed = runCatching { JSONObject(response.body) }.getOrNull()
        val contentType = response.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.joinToString().orEmpty()
        val text = response.body.trim()
        val html = parsed == null && (text.startsWith("<") ||
            (text.isNotEmpty() && contentType.contains("text/html", true)))
        return RadarReply(response.code == 200 && !html, parsed, radarResponseDetail(response.code, parsed), response.code)
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

}

internal class RollcallAnswerRejectedException(
    val responseCode: Int,
    detail: String = "数字签到提交失败：平台返回 $responseCode",
) : IllegalStateException(detail)

internal class RollcallAnswerUncertainException(val responseCode: Int) :
    IllegalStateException("数字签到提交结果未知：平台返回 $responseCode")

fun radarPayload(lat: Double, lon: Double, deviceId: String = UUID.randomUUID().toString()): JSONObject = JSONObject()
    .put("accuracy", 35)
    .put("altitude", 0)
    .put("altitudeAccuracy", JSONObject.NULL)
    .put("deviceId", deviceId)
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
): List<Pair<Double, Double>>? =
    solveSphericalRadarCandidates(lat1, lon1, lat2, lon2, distance1, distance2)

internal fun radarResponseDetail(code: Int, data: JSONObject?): String {
    var reason = listOf("message", "msg", "error", "detail", "code")
        .firstNotNullOfOrNull { key ->
            val value = data?.opt(key)
            if (value is String || value is Number) value.toString().trim().takeIf(String::isNotEmpty) else null
        }.orEmpty()
    reason = reason.replace(Regex("bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer [redacted]")
        .replace(Regex("(password|token|cookie|authorization)\\s*[:=]\\s*[^\\s;,]+", RegexOption.IGNORE_CASE), "\$1=[redacted]")
        .replace(Regex("\\s+"), " ").take(200)
    return "HTTP $code" + if (reason.isEmpty()) "" else "，$reason"
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
