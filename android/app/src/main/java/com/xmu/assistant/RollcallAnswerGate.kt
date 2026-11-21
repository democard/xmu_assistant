package com.xmu.assistant

import java.security.MessageDigest

/** Serializes foreground/monitor writes and remembers only confirmed accepted answers. */
internal object RollcallAnswerGate {
    private const val ACCEPTED_TTL_MILLIS = 10 * 60 * 1000L
    private const val MAX_ACCEPTED = 512
    private val lock = Any()
    private val accepted = linkedMapOf<String, Long>()

    fun answerOnce(
        accountId: String,
        cookieHeader: String,
        rollcallId: String,
        canAnswer: () -> Boolean = { true },
        answer: () -> Boolean,
    ): Boolean = synchronized(lock) {
        // Re-check after waiting: the caller may have logged out or stopped its worker.
        if (!canAnswer()) throw RollcallAnswerNotSubmittedException("请求已失效，未提交签到")
        val key = "$accountId:${cookieFingerprint(cookieHeader)}:$rollcallId"
        val now = elapsedMillis()
        accepted.entries.removeIf { now - it.value >= ACCEPTED_TTL_MILLIS }
        if (key in accepted) return@synchronized true
        val result = answer()
        if (result) {
            accepted[key] = elapsedMillis()
            while (accepted.size > MAX_ACCEPTED) accepted.remove(accepted.keys.first())
        }
        result
    }

    private fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L

    private fun cookieFingerprint(cookieHeader: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(cookieHeader.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
