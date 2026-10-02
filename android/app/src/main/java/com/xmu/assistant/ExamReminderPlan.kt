package com.xmu.assistant

import android.content.Context
import java.util.UUID

/** 包装 nullable 值，区分“旧安装尚无计划标识”与“不校验预期计划”。 */
internal data class ExamReminderPlanToken internal constructor(internal val value: String?)

/**
 * 提醒计划的失效边界：取消、重排和广播接收共用一把锁及持久化标识。
 * 只保存随机标识，不保存账号或考试内容；进程重建后仍能识别已作废的广播。
 */
internal object ExamReminderPlans {
    internal const val EXTRA_TOKEN = "exam_reminder_plan"
    private const val PREFS = "exam_reminder"
    private const val KEY_TOKEN = "active_plan"
    private val lock = Any()

    fun snapshot(context: Context): ExamReminderPlanToken = synchronized(lock) {
        ExamReminderPlanToken(readToken(context))
    }

    fun replace(
        context: Context,
        expected: ExamReminderPlanToken? = null,
        action: (String) -> Unit,
    ): Boolean = synchronized(lock) {
        if (expected != null && expected.value != readToken(context)) return@synchronized false
        val token = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token).apply()
        action(token)
        true
    }

    fun runIfCurrent(context: Context, token: String?, action: () -> Unit): Boolean = synchronized(lock) {
        // 升级前已注册的无标识闹钟可继续使用；首次取消/重排之后全部失效。
        if (token != readToken(context)) return@synchronized false
        action()
        true
    }

    private fun readToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)
}
