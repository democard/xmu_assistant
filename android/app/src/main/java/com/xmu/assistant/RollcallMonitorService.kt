package com.xmu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import kotlin.concurrent.thread

class RollcallMonitorService : Service() {
    @Volatile private var workerToken: Long? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "xmu助手后台监控", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            foregroundNotification(),
            // Android 14+ (targetSdk 34+) 系统要求：两参 startForeground 在某些 ROM
            // （实测 ColorOS/Android 16 的 ForegroundServiceTypeLoggerModule 报
            // "does not have any types"）不会向下游传 FGS 类型。改三参 + 显式类型，
            // 让系统始终把它当作 dataSync 前台服务，避免被判为"无类型服务"被停。
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = AssistantSettings(this)
        if (!shouldStartMonitorWorker(settings.monitorDesired, settings.cookieHeader)) {
            monitorWorkerCoordinator.requestInvalidateCurrent()
            workerToken = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        monitorWorkerCoordinator.start(settings.monitorDesired)?.let { token ->
            workerToken = token
            thread(name = "xmu-rollcall-monitor", isDaemon = true) { monitorLoop(token) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        workerToken?.let(monitorWorkerCoordinator::requestInvalidate)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isMonitorRunActive(token: Long, settings: AssistantSettings): Boolean =
        monitorWorkerCoordinator.isCurrent(token, settings.monitorDesired)

    /**
     * 分段可中断等待：每 500ms 检查一次监控是否仍在运行，让「停止监控」立即生效，
     * 而不是等满整个轮询间隔（最长 300s）。等待本身不发起任何网络请求。
     */
    private fun awaitInterruptible(token: Long, settings: AssistantSettings, totalMillis: Long) {
        val deadline = System.currentTimeMillis() + totalMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isMonitorRunActive(token, settings)) return
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun monitorLoop(token: Long) {
        val settings = AssistantSettings(this)
        // seen 去重集合持久化（观察记录③，自由区修复）：
        // 进程被杀后重启，恢复已处理过的签到 id，避免重复通知/重复应答。
        // 约束：绑定账号（cookie 变化即清空）；恢复后不跳过判读（pollOnce 照常，
        // seen 仅用于去重轮询间隔内的重复返回，不改变"什么算新签到"的判定）。
        // 按处理先后保序恢复：超限驱逐删最旧，不会误删最近处理过的事件。
        // cookie 快照：整个运行周期以此为准，持久化也绑定快照——
        // 若读实时 cookie，运行中换号会把旧账号已处理 id 记到新账号名下，
        // 新账号同 id 签到随后被 restoreSeen 误判「已处理」，不通知也不应答。
        val cookieSnapshot = settings.cookieHeader
        val restoredSeen = restoreSeenRollcalls(cookieSnapshot)
        val seen = ArrayDeque(restoredSeen)
        val seenSet = restoredSeen.toMutableSet()
        try {
            while (isMonitorRunActive(token, settings)) {
                val rollcallSettings = settings.rollcall()
                val intervalSeconds = rollcallSettings.pollIntervalSeconds.coerceIn(1, 300)
                try {
                    val liveCookie = settings.cookieHeader
                    if (liveCookie.isBlank()) error("登录已过期，请重新登录")
                    // 运行中换号/重登：seen 与新账号不匹配，退出循环，由重登流程重启监控
                    if (liveCookie != cookieSnapshot) return
                    val engine = RollcallEngine(liveCookie)
                    // 先过滤再处理：去重标记在处理成功（通知+可选应答完成）后才写入，
                    // 中途停止/异常时已处理事件不丢、未处理事件下一轮仍会通知。
                    val allEvents = engine.pollOnce()
                    // 自适应轮询节奏（与桌面端 MonitorWorker 对齐）：存在进行中的签到
                    // （remainingSeconds > 0）时切密集轮询，空闲时段维持用户设定间隔。
                    // 平均发现延迟从 interval/2 降到 ACTIVE_POLL_INTERVAL_SECONDS/2；
                    // 密集期只覆盖课堂签到窗口，日均请求量几乎不变。
                    val activeRollcall = allEvents.any { (it.remainingSeconds ?: 0L) > 0 }
                    val newEvents = allEvents.filter { it.id !in seenSet }
                    try {
                        processActiveMonitorPoll(
                            events = newEvents,
                            runIfActive = { action ->
                                monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired, action)
                            },
                            onNotify = ::notifyRollcall,
                            shouldAnswer = { event ->
                                (event.type == "数字签到" && rollcallSettings.autoAnswerNumber) ||
                                    (event.type == "雷达签到" && rollcallSettings.autoAnswerRadar)
                            },
                            onAnswer = engine::answer,
                            onSuccess = settings::recordMonitorSuccess,
                            onProcessed = { event ->
                                seenSet.add(event.id)
                                seen.addLast(event.id)
                                while (seen.size > MAX_SEEN_ROLLCALLS) {
                                    seenSet.remove(seen.removeFirst())
                                }
                            },
                        )
                    } finally {
                        persistSeenRollcalls(cookieSnapshot, seen)
                    }
                    if (!isMonitorRunActive(token, settings)) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            updateForegroundNotification(intervalSeconds)
                        }
                    ) return
                    if (!isMonitorRunActive(token, settings)) return
                    // 分段可中断等待：停止监控时最多 500ms 响应，不等满整个轮询间隔；
                    // 有进行中的签到时用密集间隔（与桌面端自适应轮询一致）
                    val waitSeconds =
                        if (activeRollcall) minOf(intervalSeconds, ACTIVE_POLL_INTERVAL_SECONDS)
                        else intervalSeconds
                    awaitInterruptible(token, settings, waitSeconds * 1000L)
                } catch (error: MainSessionExpiredException) {
                    // 会话过期是终态：不再每 5s 无限空转重试（否则后台永久弱扫描服务器且
                    // 永不触发重登）。记录一次失败 + 通知一次后退出 worker，交由上层
                    // 会话恢复/重登流程接管（Service 内不自行发起 CAS 登录）。
                    if (!isMonitorRunActive(token, settings)) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            settings.recordMonitorFailure(friendlyMessage(error))
                        }
                    ) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            notifyMonitorProblem("登录已过期，请重新登录")
                        }
                    ) return
                    return
                } catch (error: Exception) {
                    if (!isMonitorRunActive(token, settings)) return
                    val message = friendlyMessage(error)
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            settings.recordMonitorFailure(message)
                        }
                    ) return
                    if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                            updateForegroundNotification(intervalSeconds)
                        }
                    ) return
                    // 连续失败计数持久化：进程重启后可能已 ≥ 阈值，用 == 相等判断会
                    // 永远不再通知；改为每累计满阈值次通知一次（3/6/9…），跨重启仍有效。
                    if (settings.monitorConsecutiveFailures % ERROR_NOTIFY_THRESHOLD == 0) {
                        if (!monitorWorkerCoordinator.runIfCurrent(token, settings.monitorDesired) {
                                notifyMonitorProblem(message)
                            }
                        ) return
                    }
                    if (!isMonitorRunActive(token, settings)) return
                    awaitInterruptible(token, settings, 5000L)
                }
            }
        } finally {
            monitorWorkerCoordinator.complete(token)
            if (workerToken == token) workerToken = null
            // worker 因会话过期/换号/停止退出后，Service 若继续前台常驻会顶着
            // 「监控运行中」的过期快照空转（用户误以为还在监控）。没有活跃 run
            // 时自停，前台通知随服务移除；若监控仍被需要，START_STICKY/重登流程
            // 会重新拉起并 start 新 worker（hasCurrent 为原子查询，不会误杀新 run）。
            runCatching {
                if (!monitorWorkerCoordinator.hasCurrent()) stopSelf()
            }
        }
    }

    private fun notifyRollcall(event: RollcallEvent) {
        val manager = getSystemService(NotificationManager::class.java)
        val actionUrl = "xmurollcall://rollcall/${event.id}"
        val pendingIntent = PendingIntent.getActivity(
            this,
            event.id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl), this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 本地通知（快速、无网络）：留在 gate 互斥区内，保证登出/暂停时不会漏发本地提醒
        manager.notify(
            event.id.hashCode(),
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("xmu助手 签到提醒")
                .setContentText("${event.courseTitle} / ${event.type}")
                .setStyle(Notification.BigTextStyle().bigText(rollcallNotificationBody(event, actionUrl)))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        )

        // 第三方推送（PushPlus/QQMail）网络发送较慢（各自 15s 超时），
        // 移出互斥区到独立线程：否则登出/暂停要等几十秒（观察记录②，自由区修复）。
        val notify = AssistantSettings(this).notifications()
        thread(name = "xmu-notify-third-party", isDaemon = true) {
            if (notify.pushPlusEnabled) {
                runCatching {
                    PushPlusSender(notify.pushPlusToken).send("xmu助手 签到提醒", rollcallNotificationBody(event, actionUrl))
                }
            }
            if (notify.qqMailEnabled) {
                runCatching {
                    QQMailSender(notify.qqMailSender, notify.qqMailPassword, notify.qqMailRecipient, notify.qqMailPorts)
                        .send("xmu助手 签到提醒", rollcallNotificationBody(event, actionUrl))
                }
            }
        }
    }

    private fun notifyMonitorProblem(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            MONITOR_PROBLEM_NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("xmu助手 后台监控异常")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(mainPendingIntent())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun updateForegroundNotification(intervalSeconds: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_NOTIFICATION_ID, foregroundNotification(intervalSeconds))
    }

    private fun foregroundNotification(intervalSeconds: Int? = null): Notification {
        val settings = AssistantSettings(this)
        val lastCheck = formatMonitorTime(settings.monitorLastCheckMillis)
        val failures = settings.monitorConsecutiveFailures
        val text = if (failures > 0) {
            "最近失败 $failures 次：${settings.monitorLastError.ifBlank { "请检查网络或登录状态" }}"
        } else {
            "监控运行中 · 最近检查：$lastCheck · 间隔 ${intervalSeconds ?: settings.rollcall().pollIntervalSeconds} 秒"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("xmu助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPendingIntent())
            .setOngoing(true)
            .build()
    }

    private fun mainPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private val monitorWorkerCoordinator = MonitorWorkerCoordinator()

        fun requestInvalidateActiveRun() {
            monitorWorkerCoordinator.requestInvalidateCurrent()
        }

        fun awaitActiveRunQuiescence() {
            monitorWorkerCoordinator.awaitQuiescence()
        }

        private const val SEEN_PREFS = "rollcall_seen"
        private const val SEEN_COOKIE_KEY = "cookie"
        private const val SEEN_SET_KEY = "seen_ids"
        private const val SEEN_LIST_KEY = "seen_ids_ordered"
        private const val CHANNEL_ID = "xmu_assistant_monitor"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val MONITOR_PROBLEM_NOTIFICATION_ID = 1002
        private const val ERROR_NOTIFY_THRESHOLD = 3
        private const val MAX_SEEN_ROLLCALLS = 300
        /** 自适应轮询：存在进行中的签到时的密集轮询间隔上限（秒），与桌面端对齐。 */
        private const val ACTIVE_POLL_INTERVAL_SECONDS = 5
    }

    /** 恢复已处理过的签到 id 列表（进程被杀后去重不丢失，按处理先后保序）。
     *  绑定账号：cookie 变化（换账号/重登）即返回空，避免把新账号的签到误判为已处理。 */
    private fun restoreSeenRollcalls(cookieHeader: String): List<String> {
        if (cookieHeader.isBlank()) return emptyList()
        val prefs = getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
        val storedCookie = prefs.getString(SEEN_COOKIE_KEY, "") ?: ""
        if (storedCookie != cookieHeader) return emptyList()
        prefs.getString(SEEN_LIST_KEY, null)?.let { ordered ->
            return ordered.split('\n').filter { it.isNotBlank() }
        }
        // 兼容旧版 StringSet 格式（无序，下次持久化时自动迁移为有序列表）
        return prefs.getStringSet(SEEN_SET_KEY, emptySet())?.toList().orEmpty()
    }

    /** 持久化 seen 列表（只存签到 id，非敏感；保序存储保证驱逐最旧）。绑定账号 cookie 防止跨账号误用。 */
    private fun persistSeenRollcalls(cookieHeader: String, seen: Collection<String>) {
        if (cookieHeader.isBlank()) return
        getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
            .edit()
            .putString(SEEN_COOKIE_KEY, cookieHeader)
            .putString(SEEN_LIST_KEY, seen.joinToString("\n"))
            .remove(SEEN_SET_KEY)
            .apply()
    }
}

internal fun shouldStartMonitorWorker(monitorDesired: Boolean, cookieHeader: String): Boolean =
    monitorDesired && cookieHeader.isNotBlank()
