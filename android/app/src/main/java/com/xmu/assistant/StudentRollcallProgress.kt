package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * A read-only summary of the student_rollcalls payload.
 *
 * [percentage] is deliberately nullable: it is populated only when the input
 * is a non-empty, complete, duplicate-free list of object records with a
 * recognizable student identity and an unambiguous status for every record.
 */
data class StudentRollcallProgress(
    val observed: Int,
    val present: Int,
    val absent: Int,
    val unknown: Int,
    val percentage: Double?,
    val reliablePercentage: Boolean,
) {
    val total: Int
        get() = observed
}

private enum class StudentRollcallStatus {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

private data class ParsedStudentRollcall(
    val status: StudentRollcallStatus,
    val identity: String?,
)

/** Parse a response object, treating a missing or non-array student_rollcalls as incomplete. */
fun parseStudentRollcallProgress(response: JSONObject): StudentRollcallProgress {
    if (!response.has("student_rollcalls") || response.isNull("student_rollcalls")) {
        return emptyUnreliableProgress()
    }
    val value = response.opt("student_rollcalls")
    return if (value is JSONArray) {
        parseStudentRollcallProgress(value)
    } else {
        emptyUnreliableProgress()
    }
}

/** Parse a JSON array containing student rollcall records. */
fun parseStudentRollcallProgress(records: JSONArray): StudentRollcallProgress {
    var complete = records.length() > 0
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
            complete = false
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
        if (parsed.status == StudentRollcallStatus.UNKNOWN) {
            complete = false
        }
    }

    val observed = statuses.size
    val present = statuses.count { it == StudentRollcallStatus.PRESENT }
    val absent = statuses.count { it == StudentRollcallStatus.ABSENT }
    val unknown = statuses.count { it == StudentRollcallStatus.UNKNOWN }
    val reliable = complete && observed > 0
    return StudentRollcallProgress(
        observed = observed,
        present = present,
        absent = absent,
        unknown = unknown,
        percentage = if (reliable) present * 100.0 / observed else null,
        reliablePercentage = reliable,
    )
}

/** Parse a JSON string containing either the response object or the array itself. */
fun parseStudentRollcallProgress(json: String): StudentRollcallProgress {
    val text = json.trim()
    if (text.isBlank()) return emptyUnreliableProgress()
    return runCatching {
        when {
            text.startsWith("{") -> parseStudentRollcallProgress(JSONObject(text))
            text.startsWith("[") -> parseStudentRollcallProgress(JSONArray(text))
            else -> emptyUnreliableProgress()
        }
    }.getOrElse { emptyUnreliableProgress() }
}

private fun parseStudentRollcall(record: JSONObject): ParsedStudentRollcall {
    val statuses = STATUS_FIELDS.mapNotNull { field ->
        if (!record.has(field) || record.isNull(field)) return@mapNotNull null
        val raw = record.optString(field).trim().lowercase()
        if (raw.isBlank() || raw == "null") null else classifyStatus(raw)
    }
    val distinctStatuses = statuses.toSet()
    val status = when {
        distinctStatuses.size != 1 -> StudentRollcallStatus.UNKNOWN
        else -> distinctStatuses.single()
    }
    return ParsedStudentRollcall(status, studentIdentity(record))
}

private fun classifyStatus(raw: String): StudentRollcallStatus = when (raw) {
    "on_call", "on_call_fine" -> StudentRollcallStatus.PRESENT
    "absent" -> StudentRollcallStatus.ABSENT
    else -> StudentRollcallStatus.UNKNOWN
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
)

private val STATUS_FIELDS = listOf("status", "rollcall_status", "student_rollcall_status")
private val IDENTITY_FIELDS = listOf(
    "user_no", "student_id", "studentId", "student_no", "studentNo",
    "user_id", "userId", "username", "id",
)
