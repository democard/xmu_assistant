package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * A read-only summary of the student_rollcalls payload.
 *
 * [percentage] is deliberately nullable: it is populated only when the input
 * is a non-empty, complete, duplicate-free list of object records with a usable
 * string status for every record. Unrecognized non-empty statuses remain valid
 * denominator rows but never count as signed. Student identity is optional.
 */
data class StudentRollcallProgress(
    val observed: Int,
    val present: Int,
    val absent: Int,
    val unknown: Int,
    val percentage: Double?,
    val reliablePercentage: Boolean,
    val leave: Int = 0,
    val late: Int = 0,
) {
    val total: Int
        get() = observed
}

data class StudentRollcallDetails(
    val progress: StudentRollcallProgress?,
    val numberCode: String,
    val ownStatus: String?,
)

/** 一份特定 rollcall_id 的只读明细同时产出统计、数字码和本人状态。 */
fun parseStudentRollcallDetails(
    response: JSONObject,
    username: String = "",
): StudentRollcallDetails {
    val parsedProgress = parseStudentRollcallProgress(response).takeIf { it.observed > 0 }
    val students = response.optJSONArray("student_rollcalls")
    val ownRecords = mutableListOf<JSONObject>()
    if (students != null) {
        for (index in 0 until students.length()) {
            val student = students.optJSONObject(index) ?: continue
            val userNo = listOf("user_no", "username", "student_no", "number", "account")
                .firstNotNullOfOrNull { key -> student.optRealString(key).takeIf { it.isNotBlank() } }
            if ((username.isNotBlank() && userNo == username) ||
                student.optBoolean("is_current_user") || student.optBoolean("is_self")
            ) {
                ownRecords += student
            }
        }
    }
    return StudentRollcallDetails(
        progress = parsedProgress,
        // 响应来自 /api/rollcall/{id}/student_rollcalls，递归范围已限定为该活动。
        numberCode = findNumberCode(response).orEmpty(),
        ownStatus = parsedOwnRollcallStatus(ownRecords),
    )
}

private enum class StudentRollcallStatus {
    PRESENT,
    ABSENT,
    LEAVE,
    LATE,
    UNKNOWN,
}

private data class ParsedStudentRollcall(
    val status: StudentRollcallStatus,
    val identity: String?,
    val structurallyValid: Boolean,
)

/** Parse a response object, treating a missing or non-array student_rollcalls as incomplete. */
fun parseStudentRollcallProgress(
    response: JSONObject,
    rosterComplete: Boolean = true,
): StudentRollcallProgress {
    if (!response.has("student_rollcalls") || response.isNull("student_rollcalls")) {
        return emptyUnreliableProgress()
    }
    val value = response.opt("student_rollcalls")
    return if (value is JSONArray) {
        parseStudentRollcallProgress(value, rosterComplete)
    } else {
        emptyUnreliableProgress()
    }
}

/** Parse a JSON array containing student rollcall records. */
fun parseStudentRollcallProgress(
    records: JSONArray,
    rosterComplete: Boolean = true,
): StudentRollcallProgress {
    var complete = rosterComplete && records.length() > 0
    val statuses = mutableListOf<StudentRollcallStatus>()
    val identities = mutableMapOf<String, Int>()

    for (index in 0 until records.length()) {
        val value = records.opt(index)
        if (value !is JSONObject) {
            complete = false
            continue
        }

        val parsed = parseStudentRollcall(value)
        val identity = parsed.identity
        if (identity == null) {
            // 原接口的状态名单即可计数，学号只用于去重，不能作为统计前提。
            statuses += parsed.status
        } else if (identity in identities) {
            val previousIndex = identities.getValue(identity)
            if (statuses[previousIndex] != parsed.status) {
                statuses[previousIndex] = StudentRollcallStatus.UNKNOWN
            }
            complete = false
        } else {
            identities[identity] = statuses.size
            statuses += parsed.status
        }
        if (!parsed.structurallyValid) {
            complete = false
        }
    }

    val observed = statuses.size
    val present = statuses.count { it == StudentRollcallStatus.PRESENT }
    val absent = statuses.count { it == StudentRollcallStatus.ABSENT }
    val leave = statuses.count { it == StudentRollcallStatus.LEAVE }
    val late = statuses.count { it == StudentRollcallStatus.LATE }
    val unknown = statuses.count { it == StudentRollcallStatus.UNKNOWN }
    val reliable = complete && observed > 0
    return StudentRollcallProgress(
        observed = observed,
        present = present,
        absent = absent,
        unknown = unknown,
        percentage = if (reliable) present * 100.0 / observed else null,
        reliablePercentage = reliable,
        leave = leave,
        late = late,
    )
}

/** Parse a JSON string containing either the response object or the array itself. */
fun parseStudentRollcallProgress(
    json: String,
    rosterComplete: Boolean = true,
): StudentRollcallProgress {
    val text = json.trim()
    if (text.isBlank()) return emptyUnreliableProgress()
    return runCatching {
        when {
            text.startsWith("{") -> parseStudentRollcallProgress(JSONObject(text), rosterComplete)
            text.startsWith("[") -> parseStudentRollcallProgress(JSONArray(text), rosterComplete)
            else -> emptyUnreliableProgress()
        }
    }.getOrElse { emptyUnreliableProgress() }
}

private fun parseStudentRollcall(record: JSONObject): ParsedStudentRollcall {
    val raw = STATUS_FIELDS.firstNotNullOfOrNull { field ->
        if (!record.has(field) || record.isNull(field)) return@firstNotNullOfOrNull null
        (record.opt(field) as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }
    return ParsedStudentRollcall(
        status = raw?.let(::classifyStatus) ?: StudentRollcallStatus.UNKNOWN,
        identity = studentIdentity(record),
        structurallyValid = raw != null,
    )
}

private fun classifyStatus(raw: String): StudentRollcallStatus = when (knownRollcallStatus(raw)) {
    KnownRollcallStatus.PRESENT -> StudentRollcallStatus.PRESENT
    KnownRollcallStatus.ABSENT -> StudentRollcallStatus.ABSENT
    KnownRollcallStatus.LEAVE -> StudentRollcallStatus.LEAVE
    KnownRollcallStatus.LATE -> StudentRollcallStatus.LATE
    KnownRollcallStatus.UNKNOWN -> StudentRollcallStatus.UNKNOWN
}

/** 合并本人所有匹配行；迟到细化已到场，缺勤细化未到场，跨语义组冲突保持未知。 */
internal fun parsedOwnRollcallStatus(record: JSONObject): String? =
    parsedOwnRollcallStatus(listOf(record))

private enum class OwnRollcallStatusGroup { PRESENT, ABSENT, LEAVE, UNKNOWN }

internal fun parsedOwnRollcallStatus(records: Iterable<JSONObject>): String? {
    val statusesByRecord = records.map { record ->
        OWN_STATUS_FIELDS.mapNotNull { field ->
            record.optRealString(field).takeIf { it.isNotBlank() }?.let(::historyRollcallStatus)
        }.toSet()
    }
    if (statusesByRecord.all { it.isEmpty() }) return null
    if (statusesByRecord.any { it.isEmpty() }) return STATUS_UNKNOWN
    val statuses = statusesByRecord.flatten().toSet()
    val groups = statuses.map { status ->
        when (status) {
            STATUS_SIGNED, STATUS_LATE -> OwnRollcallStatusGroup.PRESENT
            "未签", "缺勤" -> OwnRollcallStatusGroup.ABSENT
            STATUS_LEAVE -> OwnRollcallStatusGroup.LEAVE
            else -> OwnRollcallStatusGroup.UNKNOWN
        }
    }.toSet()
    if (groups.size != 1 || OwnRollcallStatusGroup.UNKNOWN in groups) return STATUS_UNKNOWN
    return when (groups.single()) {
        OwnRollcallStatusGroup.PRESENT -> if (STATUS_LATE in statuses) STATUS_LATE else STATUS_SIGNED
        OwnRollcallStatusGroup.ABSENT -> if ("缺勤" in statuses) "缺勤" else "未签"
        OwnRollcallStatusGroup.LEAVE -> STATUS_LEAVE
        OwnRollcallStatusGroup.UNKNOWN -> STATUS_UNKNOWN
    }
}

private fun studentIdentity(record: JSONObject): String? {
    for (field in IDENTITY_FIELDS) {
        if (!record.has(field) || record.isNull(field)) continue
        val value = record.optString(field).trim()
        if (value.isNotBlank() && value.lowercase() != "null") {
            return "$field:$value"
        }
    }
    return null
}

private fun emptyUnreliableProgress() = StudentRollcallProgress(
    observed = 0,
    present = 0,
    absent = 0,
    unknown = 0,
    percentage = null,
    reliablePercentage = false,
    leave = 0,
    late = 0,
)

private val STATUS_FIELDS = listOf("rollcall_status", "student_rollcall_status", "status")
private val OWN_STATUS_FIELDS = listOf("status", "rollcall_status", "student_rollcall_status", "state")
private val IDENTITY_FIELDS = listOf(
    "user_no", "student_id", "studentId", "student_no", "studentNo",
    "user_id", "userId", "username", "id",
)
