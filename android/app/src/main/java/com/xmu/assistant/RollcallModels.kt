package com.xmu.assistant

const val COURSEWARE_STATUS_AVAILABLE = "可下载"
const val COURSEWARE_STATUS_DOWNLOADING = "下载中"
const val COURSEWARE_STATUS_SUCCESS = "下载成功"
const val COURSEWARE_STATUS_FAILED = "下载失败"
const val COURSEWARE_STATUS_ENTRY_SAVED = "已保存入口"
const val COURSEWARE_STATUS_LIMITED = "平台限制"

data class LoginResult(
    val cookieHeader: String,
    val displayName: String = "",
)

data class RollcallEvent(
    val id: String,
    val courseTitle: String,
    val teacher: String,
    val type: String,
    val status: String,
    val numberCode: String = "",
    val deadline: String = "",
    val result: String = "待处理",
    /** 截止剩余秒数（≤0 已截止，null 未知）：驱动监控自适应密集轮询。 */
    val remainingSeconds: Long? = null,
)

data class NotificationSettings(
    val systemEnabled: Boolean = true,
    val pushPlusEnabled: Boolean = false,
    val pushPlusToken: String = "",
    val qqMailEnabled: Boolean = false,
    val qqMailSender: String = "",
    val qqMailPassword: String = "",
    val qqMailRecipient: String = "",
    val qqMailPorts: String = "465,587",
)

data class RollcallSettings(
    val pollIntervalSeconds: Int = 30,
    val autoAnswerNumber: Boolean = false,
    val autoAnswerRadar: Boolean = false,
)

data class CourseSummary(
    val id: String,
    val title: String,
    val term: String = "",
    val semesterCode: String = "",
) {
    val displayName: String
        get() = if (term.isBlank()) title else "$title  [$term]"
}

data class CoursewareUiItem(
    val id: String,
    val courseId: String,
    val activityId: String,
    val title: String,
    val filename: String,
    val type: String,
    val moduleName: String = "",
    val referenceId: String = "",
    val sourceUrl: String = "",
    val downloadStatus: String = COURSEWARE_STATUS_AVAILABLE,
    val failureReason: String = "",
)

fun normalizedRollcallStatus(raw: String): String {
    val lowered = raw.lowercase()
    // 单词边界分词再精确比对：避免 "dismiss" 命中 "miss"、"define/refine" 命中 "fine" 等子串误判
    val tokens = lowered.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
    return when {
        // 先判「未签」：unsigned 含 signed 子串，顺序颠倒会把 unsigned 误判为已签；
        // "not_signed" 分词成 [not, signed] 会命中下方 signed 分支误判已签，必须在此先行拦截；
        // missed/unanswered 与桌面端 infer_signed_status 词表对齐
        raw in listOf("未签", "未签到") ||
            "unsigned" in tokens || "absent" in tokens || "miss" in tokens ||
            "missed" in tokens || "unanswered" in tokens ||
            ("not" in tokens && "signed" in tokens) -> "未签"
        raw in listOf("已签", "已签到") ||
            "signed" in tokens || "fine" in tokens || "success" in tokens ||
            "present" in tokens || "attended" in tokens || "done" in tokens -> "已签"
        else -> "未知"
    }
}

/**
 * 解析签到截止时间为剩余秒数（与桌面端 rollcall_models.remaining_seconds_from_deadline 对齐）。
 * 支持 ISO-8601 变体：带 Z / 带偏移 / 无时区（按本地时区解释）；小数秒截断到秒。
 * 已过期的截止返回 0（非负钳位），无法解析返回 null。
 */
fun remainingSecondsFromDeadline(deadline: String): Long? {
    val text = deadline.trim()
    if (text.isBlank()) return null
    for (candidate in listOf(text, text.take(19))) {
        val instant = runCatching {
            java.time.OffsetDateTime.parse(candidate.replace("Z", "+00:00")).toInstant()
        }.recoverCatching {
            java.time.LocalDateTime.parse(candidate).atZone(java.time.ZoneId.systemDefault()).toInstant()
        }.getOrNull() ?: continue
        return maxOf(0L, java.time.Duration.between(java.time.Instant.now(), instant).seconds)
    }
    return null
}

fun shortCoursewareError(error: String): String {
    val lowered = error.lowercase()
    return when {
        // 下载被平台拒绝（403 版权保护/防盗链）：不是登录过期，不应误导用户重新登录
        listOf("被平台拒绝", "无下载权限").any { it in error } -> COURSEWARE_STATUS_LIMITED
        listOf("网络失败", "timeout", "timed out", "connection", "network", "dns", "proxy").any { it in lowered } -> "网络失败"
        listOf("登录过期", "401", "unauthorized").any { it in lowered } -> "登录过期"
        listOf("forbidden", "permission").any { it in lowered } -> "平台限制"
        listOf("登录态", "无权访问", "权限", "拒绝").any { it in error } -> "登录过期"
        listOf("404", "not found").any { it in lowered } -> "平台未提供地址"
        listOf("资源", "地址", "reference_id", "签名", "未返回", "缺少", "失效").any { it in error } -> "平台未提供地址"
        else -> COURSEWARE_STATUS_FAILED
    }
}

fun coursewareFailureStatus(error: String): String = "$COURSEWARE_STATUS_FAILED（${shortCoursewareError(error)}）"
