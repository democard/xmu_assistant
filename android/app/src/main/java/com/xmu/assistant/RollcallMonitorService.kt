package com.xmu.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class RollcallMonitorService : Service() {
    @Volatile private var workerToken: Long? = null

    // 第三方推送专用单线程执行器：签到事件可能短时集中到达，原先每个事件裸起
    // 一个 daemon 线程，线程数随事件数无界增长；推送发送本身慢（各自 15s 超时）
    // 且相互独立，串行单线程足够并天然保序，onDestroy 时统一关停。
    private val thirdPartyPushExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xmu-notify-third-party").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "xmu助手后台监控", NotificationManager.IMPORTANCE_LOW)
        )
        // Android 14+ (targetSdk 34+) 系统要求：两参 startForeground 在某些 ROM
        // （实测 ColorOS/Android 16 的 ForegroundServiceTypeLoggerModule 报
        // "does not have any types"）不会向下游传 FGS 类型。改三参 + 显式类型，
        // 让系统始终把它当作 dataSync 前台服务，避免被判为"无类型服务"被停。
        // 三参重载要求 API29+；API26-28 走两参（无类型语义可传，系统按默认处理），
        // 否则旧设备 onCreate 即 NoSuchMethodError 崩溃。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                foregroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification())
        }
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
        // 停止接收新推送任务；在途/排队中的发送走完（daemon 线程不阻塞进程退出）
        thirdPartyPushExecutor.shutdown()
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
                                evictSeenOverflow(seen, seenSet, MAX_SEEN_ROLLCALLS)
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
        // onDestroy 后执行器已 shutdown，提交会被拒绝（与轮询线程存在短暂竞速）；
        // 此时服务已销毁、推送无意义，记日志兜住即可，不让异常冒泡成监控失败。
        runCatching {
            thirdPartyPushExecutor.execute {
                // 第三方推送失败不重试不阻断本地通知，但必须留下日志：
                // token/授权码失效后该通道会长期静默死亡，无日志则完全无法察觉。
                if (notify.pushPlusEnabled) {
                    runCatching {
                        PushPlusSender(notify.pushPlusToken).send("xmu助手 签到提醒", rollcallNotificationBody(event, actionUrl))
                    }.onFailure { Log.w(TAG, "PushPlus 推送失败：${it.message}", it) }
                }
                if (notify.qqMailEnabled) {
                    runCatching {
                        QQMailSender(notify.qqMailSender, notify.qqMailPassword, notify.qqMailRecipient, notify.qqMailPorts)
                            .send("xmu助手 签到提醒", rollcallNotificationBody(event, actionUrl))
                    }.onFailure { Log.w(TAG, "QQ 邮箱推送失败：${it.message}", it) }
                }
            }
        }.onFailure { Log.w(TAG, "第三方推送任务提交失败（服务已销毁）：${it.message}") }
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
        private const val TAG = "RollcallMonitorService"
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
        // 只比对 SHA-256 指纹（P1-4）：prefs 里不再保存原始会话凭据；
        // 旧版本的明文记录与指纹必然不等，首次升级后 seen 列表重建一次（自愈）。
        val storedCookie = prefs.getString(SEEN_COOKIE_KEY, "") ?: ""
        if (storedCookie != cookieFingerprint(cookieHeader)) return emptyList()
        prefs.getString(SEEN_LIST_KEY, null)?.let { ordered ->
            return ordered.split('\n').filter { it.isNotBlank() }
        }
        // 兼容旧版 StringSet 格式（无序，下次持久化时自动迁移为有序列表）
        return prefs.getStringSet(SEEN_SET_KEY, emptySet())?.toList().orEmpty()
    }

    /** 持久化 seen 列表（只存签到 id 与 cookie 的 SHA-256 指纹，均非敏感；保序存储保证驱逐最旧）。
     *  绑定账号指纹防止跨账号误用——完整 Cookie 属加密存储范畴，绝不落入普通 prefs（体检报告 P1-4）。 */
    private fun persistSeenRollcalls(cookieHeader: String, seen: Collection<String>) {
        if (cookieHeader.isBlank()) return
        getSharedPreferences(SEEN_PREFS, MODE_PRIVATE)
            .edit()
            .putString(SEEN_COOKIE_KEY, cookieFingerprint(cookieHeader))
            .putString(SEEN_LIST_KEY, seen.joinToString("\n"))
            .remove(SEEN_SET_KEY)
            .apply()
    }
}

internal fun shouldStartMonitorWorker(monitorDesired: Boolean, cookieHeader: String): Boolean =
    monitorDesired && cookieHeader.isNotBlank()

/**
 * 会话 Cookie 快照的 SHA-256 指纹（小写 hex）。
 *
 * 用途：监控去重集合需要「绑定当时的登录态」防跨账号误判，但完整 Cookie 属于
 * 加密凭据，不能明文落普通 SharedPreferences（体检报告 P1-4）——指纹足以完成
 * 等值比对且不可逆推回原凭据。空串约定返回空串，便于调用方短路。
 */
internal fun cookieFingerprint(cookieHeader: String): String {
    if (cookieHeader.isBlank()) return ""
    val digest = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(cookieHeader.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * seen 环溢出逐出：新事件 id 入队入集后调用，超过上限时从最老端逐出并同步
 * 集合（防重复通知的内存上界，PC 端孪生有行为测试）。纯逻辑抽离便于直测。
 */
internal fun evictSeenOverflow(seen: ArrayDeque<String>, seenSet: MutableSet<String>, maxSeen: Int) {
    while (seen.size > maxSeen) {
        seenSet.remove(seen.removeFirst())
    }
}
