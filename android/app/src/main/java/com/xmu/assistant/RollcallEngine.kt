package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject

internal class RollcallAnswerNotSubmittedException(message: String) : IllegalStateException(message)

class RollcallEngine internal constructor(
    private val cookieHeader: String,
    private val statusTransport: QueryHttpTransport = OkHttpQueryTransport(),
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
                courseTitle = firstString(item, "course_title", "course_name").ifBlank { "未知课程" },
                teacher = listOf(item.optRealString("department_name"), item.optRealString("created_by_name"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "未知" },
                type = when {
                    item.optBoolean("is_radar") -> "雷达签到"
                    item.optBoolean("is_number") -> "数字签到"
                    else -> "二维码签到"
                },
                status = normalizedRollcallStatus(item.optRealString("status", "unknown")),
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
            event.copy(
                numberCode = "",
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
}

internal class RollcallAnswerRejectedException(
    val responseCode: Int,
    detail: String = "签到操作被平台拒绝：$responseCode",
) : IllegalStateException(detail)

internal class RollcallAnswerUncertainException(val responseCode: Int) :
    IllegalStateException("签到状态未知：$responseCode")

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
