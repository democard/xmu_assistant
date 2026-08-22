package com.xmu.assistant

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** 进程级前台标志：由 MainActivity.onStart/onStop 维护，后台同步 worker 借此避开与用户操作并发刷新。 */
internal object AppForegroundTracker {
    @Volatile
    var foreground: Boolean = false
}

/**
 * 桌面小卡片每日自动同步 Worker。
 *
 * 每天定时（默认早上 7:00）在后台拉一次课表，写入缓存并同步桌面小卡片，
 * 让用户不打开 App 也能看到当天最新课程。安全策略：
 * - App 前台活跃时跳过本次（用户自己刷新会同步小卡片，避免双登录/双请求）；
 * - 未登录或小卡片开关关闭时跳过，不发任何网络请求；
 * - 必须联网才执行；
 * - 复用与手动刷新完全相同的取课表逻辑（fetchScheduleWithNetworkRetry），
 *   失败由 WorkManager 指数退避重试。
 */
class ScheduleWidgetSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (AppForegroundTracker.foreground) return Result.success()
        val settings = AssistantSettings(context)
        if (settings.username.isBlank() || settings.password.isBlank()) return Result.success()
        if (!settings.widgetEnabled) return Result.success()

        return try {
            val result = fetchScheduleWithNetworkRetry(
                username = settings.username,
                password = settings.password,
                scoreCookieHeader = settings.scoreCookieHeader,
                mayRelogin = { true },
            )
            val updatedAt = System.currentTimeMillis()
            val termCode = result.termCode
            val entries = result.entries
            // 保留已有反推日历；官方表没有的学期继续用「服务端当前周次 + 当天」反推并缓存，
            // 与 MainActivity.refreshSchedule 保持同一套逻辑。
            val calendars = HashMap(loadScheduleSnapshotFromFile(context).inferredCalendars)
            val official = xmuAcademicCalendarForTerm(termCode)
            if (official != null) {
                // 官方校历表已覆盖该学期：删除旧反推条目，防止过期反推数据继续生效
                calendars.remove(termCode)
            } else if (result.currentWeek != null) {
                xmuTryInferCalendar(
                    termCode = termCode,
                    currentWeek = result.currentWeek,
                    today = LocalDate.now(),
                )?.let { calendars[termCode] = it }
            }
            val snapshot = XmuScheduleSnapshot(
                entries = entries,
                termCode = termCode,
                updatedAtMillis = updatedAt,
                inferredCalendars = calendars,
            )
            saveScheduleSnapshotToFile(context, snapshot)
            settings.scoreCookieHeader = result.jwCookie
            syncScheduleWidget(
                context,
                entries,
                termCode,
                inferredCalendar = calendars[termCode],
                manualWeek = settings.manualAcademicWeek(termCode),
            )
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            // Worker 被取消（重新入队/约束失效）必须优雅终止，不能转成 retry 与取消语义对抗
            throw error
        } catch (error: AcademicLoginBlockedException) {
            // 登录被服务端拒绝/限流是永久性失败：指数退避重试只会反复打 CAS 登录（风控红线）
            Result.failure()
        } catch (error: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "schedule-widget-daily-sync"
        private const val TARGET_HOUR = 7

        /** 注册/更新每日同步任务：初始延迟到下一个目标时刻（默认早上 7:00），此后每 24h 一次。 */
        fun scheduleDaily(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.toLocalDate().atTime(TARGET_HOUR, 0).atZone(now.zone)
            if (!next.isAfter(now)) {
                next = next.plusDays(1)
            }
            val initialDelay = ChronoUnit.MILLIS.between(now, next)
            val request = PeriodicWorkRequestBuilder<ScheduleWidgetSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP 才是幂等：UPDATE 会取消旧周期链并按当前时刻重算初始延迟，
                // 7:00 前打开一次 App 会把当天的同步推迟到次日（小卡片整日不刷新）。
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
