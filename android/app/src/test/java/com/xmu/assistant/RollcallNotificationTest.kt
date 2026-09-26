package com.xmu.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollcallNotificationTest {
    private lateinit var service: RollcallMonitorService
    private lateinit var manager: NotificationManager
    private val event = RollcallEvent("notification-test", "微积分", "老师", "雷达签到", "未签")

    @Before
    fun setUp() {
        // Attach only: no worker, foreground startup, encrypted credentials or network is needed.
        service = Robolectric.buildService(RollcallMonitorService::class.java).get()
        manager = service.getSystemService(NotificationManager::class.java)
        service.createNotificationChannels()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        service.onDestroy()
    }

    @Test
    fun `business reminders use a default importance channel while foreground remains low`() {
        assertTrue(service.notifyRollcall(event, NotificationSettings()))
        assertTrue(service.notifyMonitorProblem("网络异常", systemEnabled = true))

        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.size)
        assertTrue(notifications.all { it.channelId == REMINDER_CHANNEL })
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(REMINDER_CHANNEL).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(MONITOR_CHANNEL).importance)
        val rollcall = shadowOf(manager).getNotification(event.id.hashCode())
        assertEquals("xmu助手 签到提醒", rollcall.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("xmurollcall://rollcall/${event.id}", shadowOf(rollcall.contentIntent).savedIntent.dataString)
    }

    @Test
    fun `app local notification preference disables both kinds of reminder`() {
        assertNoLocalReminder(NotificationSettings(systemEnabled = false))
    }

    @Test
    fun `android app notification block disables both kinds of reminder`() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertNoLocalReminder(NotificationSettings())
    }

    @Test
    fun `android 13 permission denial disables both kinds of reminder`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNoLocalReminder(NotificationSettings())
    }

    @Test
    @Config(sdk = [28])
    fun `android before 13 can post reminders without runtime notification permission`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(service.notifyRollcall(event, NotificationSettings()))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun `disabled reminder channel is respected and is not reenabled during creation`() {
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL, "提醒已禁用", NotificationManager.IMPORTANCE_NONE),
        )
        service.createNotificationChannels()

        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(REMINDER_CHANNEL).importance)
        assertNoLocalReminder(NotificationSettings())
    }

    @Test
    fun `pending rollcall is notified once after runtime permission is restored`() {
        val notified = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        fun poll() = processRollcallMonitorPoll(
            events = listOf(event),
            settings = RollcallSettings(),
            notifiedIds = notified,
            completedIds = completed,
            answerAttempts = attempts,
            runIfActive = { action -> action(); true },
            onNotify = { service.notifyRollcall(it, NotificationSettings()) },
            onAnswer = { error("automatic answering is disabled") },
            onSuccess = {},
        )
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        poll()
        assertTrue(notified.isEmpty())
        assertEquals(0, shadowOf(manager).size())

        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        poll()
        assertEquals(setOf(event.id), notified)
        assertEquals(1, shadowOf(manager).size())
        manager.cancelAll()
        poll()
        assertEquals("an accepted notification is not submitted again", 0, shadowOf(manager).size())
    }

    @Test
    fun `disabled local channel still accepts an enabled remote task once without sending in test`() {
        val executor = RecordingExecutor()
        ReflectionHelpers.setField(service, "thirdPartyPushExecutor", executor)
        val notified = mutableSetOf<String>()
        val notify = NotificationSettings(systemEnabled = false, pushPlusEnabled = true)
        repeat(2) {
            processRollcallMonitorPoll(
                listOf(event), RollcallSettings(), notified, mutableSetOf(), mutableMapOf(),
                { action -> action(); true }, { service.notifyRollcall(it, notify) }, { true }, {},
            )
        }
        assertEquals(setOf(event.id), notified)
        assertEquals(1, executor.acceptedTasks)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun `rejected remote task with no local channel is not accepted`() {
        val executor = RecordingExecutor().apply { shutdown() }
        ReflectionHelpers.setField(service, "thirdPartyPushExecutor", executor)
        assertFalse(service.notifyRollcall(event, NotificationSettings(systemEnabled = false, pushPlusEnabled = true)))
        assertEquals(0, executor.acceptedTasks)
    }

    private fun assertNoLocalReminder(settings: NotificationSettings) {
        assertFalse(service.notifyRollcall(event, settings))
        assertFalse(service.notifyMonitorProblem("网络异常", settings.systemEnabled))
        assertEquals(0, shadowOf(manager).size())
    }

    /** Accepts submissions without executing third-party network senders. */
    private class RecordingExecutor : AbstractExecutorService() {
        var acceptedTasks = 0
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException("test executor stopped")
            acceptedTasks++
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown(); return mutableListOf() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }

    companion object {
        private const val MONITOR_CHANNEL = "xmu_assistant_monitor"
        private const val REMINDER_CHANNEL = "xmu_assistant_rollcall_reminders"
    }
}
