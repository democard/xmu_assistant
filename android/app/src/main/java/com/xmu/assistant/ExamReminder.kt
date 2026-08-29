package com.xmu.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 考试提醒设置（普适：只含提醒偏好，不含账号信息）。 */
data class ExamReminderSettings(
    val enabled: Boolean = false,
    /** 提前提醒分钟数，0-60。 */
    val advanceMinutes: Int = 30,
    /** 是否使用全屏通知（闹钟式）；需要用户额外授权。 */
    val fullScreenEnabled: Boolean = false,
)

/**
 * 考试提醒调度：
 * - 每场未完成考试在「开考前 advanceMinutes 分钟」触发一次提醒（AlarmManager 精确闹钟）
 * - 基础：高优先级通知（响铃+震动+横幅），无需额外权限
 * - 可选：全屏通知（闹钟式锁屏亮屏），需引导授权
 * - 点击提醒只关闭通知，不跳转
 */
internal object ExamReminder {
    internal const val CHANNEL_ID = "xmu_assistant_exam"
    private const val TAG = "ExamReminder"
    private const val REQUEST_CODE_BASE = 20000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "考试提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "考试开始前的强提醒"
                    enableVibration(true)
                },
            )
        }
    }

    /**
     * 为当前考试列表重建提醒计划：取消旧计划，为每场未完成考试调度一个闹钟。
     * 幂等：重复调用只保留最新计划。
     */
    fun schedule(context: Context, settings: ExamReminderSettings, exams: List<XmuExam>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        cancelAll(context)
        if (!settings.enabled) return

        val now = System.currentTimeMillis()
        // 先按触发时刻过滤"未来考试"再截断 100 槽（requestCode 与 cancelAll 槽位一致）：
        // 已过提醒时间的考试不占槽位，避免过期场次挤占未来场次的提醒（take 截断丢提醒）。
        val futureExams = exams
            .mapNotNull { exam ->
                examReminderTriggerAtMillis(exam, settings.advanceMinutes)
                    ?.takeIf { it > now }
                    ?.let { exam to it }
            }
            .take(100)
        var requestCode = REQUEST_CODE_BASE
        futureExams.forEach { (exam, triggerAt) ->
            val pendingIntent = reminderPendingIntent(context, requestCode, exam)
            requestCode += 1
            // 精确闹钟：Android 12+ 需要 USE_EXACT_ALARM 或 SCHEDULE_EXACT_ALARM 权限。
            // 无权限时回退 setWindow（约 10 分钟内触发），保证基本提醒可用。
            // canScheduleExactAlarm 先查后用非原子：权限在 100 槽循环期间被并发吊销时
            // setExactAndAllowWhileIdle 抛 SecurityException，未捕获会让重排协程崩进程
            // ——逐槽兜底记日志继续，剩余槽位由下次重排/cancelAll 收敛
            runCatching {
                if (canScheduleExactAlarm(context)) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setWindow(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        10 * 60 * 1000L,
                        pendingIntent,
                    )
                }
            }.onFailure {
                android.util.Log.w(TAG, "考试提醒注册失败（槽位 ${requestCode - 1}）：${it.message}")
            }
        }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        // 取消一段范围内的 requestCode（预留 100 个槽位）
        for (code in REQUEST_CODE_BASE until REQUEST_CODE_BASE + 100) {
            alarmManager.cancel(reminderPendingIntent(context, code, XmuExam(id = code.toString(), courseName = "", date = "", timeRange = "", room = "", mode = "", examName = "")))
        }
    }

    fun canScheduleExactAlarm(context: Context): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /** 是否已授予全屏通知权限（Android 14+ 需要；低版本默认可用）。 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.USE_FULL_SCREEN_INTENT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** 生成"打开闹钟与提醒设置"的 Intent（引导用户授权精确闹钟）。 */
    @android.annotation.SuppressLint("InlinedApi")
    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            // 用运行时包名：build variant 加 applicationIdSuffix（如 .debug）时硬编码包名会跳到不存在的授权页
            data = android.net.Uri.parse("package:${context.packageName}")
        }

    /** 生成"全屏通知"系统设置 Intent（Android 14+ 应用通知设置页）。 */
    fun fullScreenSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    private fun reminderPendingIntent(context: Context, requestCode: Int, exam: XmuExam): PendingIntent {
        val intent = Intent(context, ExamReminderReceiver::class.java).apply {
            putExtra("exam_id", exam.id)
            putExtra("exam_course", exam.courseName)
            putExtra("exam_date", exam.date)
            putExtra("exam_time", exam.timeRange)
            putExtra("exam_room", exam.room)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 考试提醒触发时刻：考试日期 + 开始时间 - advanceMinutes。
     *  开始时刻永远在考试当天（结束早于开始仅表示结束跨到次日，不影响开始日期）。解析失败返回 null。 */
    internal fun examReminderTriggerAtMillis(exam: XmuExam, advanceMinutes: Int): Long? {
        val date = runCatching { java.time.LocalDate.parse(exam.date) }.getOrNull() ?: return null
        val parts = exam.timeRange.split("-").map { it.trim() }
        if (parts.size != 2) return null
        val startTime = runCatching { java.time.LocalTime.parse(parts[0]) }.getOrNull() ?: return null
        val trigger = java.time.LocalDateTime.of(date, startTime).minusMinutes(advanceMinutes.toLong())
        return trigger.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/** 考试提醒广播接收器：收到闹钟后发高优先级通知（可选全屏）。点击只关闭。 */
internal class ExamReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val exam = XmuExam(
            id = intent.getStringExtra("exam_id").orEmpty(),
            courseName = intent.getStringExtra("exam_course").orEmpty(),
            date = intent.getStringExtra("exam_date").orEmpty(),
            timeRange = intent.getStringExtra("exam_time").orEmpty(),
            room = intent.getStringExtra("exam_room").orEmpty(),
            mode = "",
            examName = "",
        )
        if (exam.courseName.isBlank()) return

        ExamReminder.ensureChannel(context)
        val settings = loadReminderSettings(context)
        val builder = Notification.Builder(context, ExamReminder.CHANNEL_ID)
            .setContentTitle("考试提醒：${exam.courseName}")
            .setContentText("${exam.date} ${exam.timeRange} · ${exam.room.ifBlank { "线上考试" }}")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
            .setPriority(Notification.PRIORITY_HIGH)

        if (settings.fullScreenEnabled && ExamReminder.canUseFullScreenIntent(context)) {
            val fullScreenIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreenIntent, true)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        // 通知 id 与 ExamCache 去重键同构：空 id（hashCode 恒为 0）会导致多场考试通知互相覆盖
        val notificationKey = exam.id.ifBlank { "${exam.courseName}|${exam.date}|${exam.timeRange}" }
        manager.notify(notificationKey.hashCode(), builder.build())
    }
}

/** 从 AssistantSettings 读取提醒设置（存储层复用现有加密 prefs）。 */
internal fun loadReminderSettings(context: Context): ExamReminderSettings {
    val settings = AssistantSettings(context)
    return ExamReminderSettings(
        enabled = settings.examReminderEnabled,
        advanceMinutes = settings.examReminderAdvanceMinutes.coerceIn(0, 60),
        fullScreenEnabled = settings.examReminderFullScreen,
    )
}
