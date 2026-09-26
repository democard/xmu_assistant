package com.xmu.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowNotificationManager

// Robolectric 4.14 has no API 34 special-access shadow. Model the platform's
// separate switch while retaining real notification storage in its base shadow.
@Implements(NotificationManager::class)
class ReminderSpecialAccessShadow : ShadowNotificationManager() {
    @Implementation(minSdk = 34)
    protected fun canUseFullScreenIntent(): Boolean = allowed

    companion object {
        var allowed = false
        @JvmStatic @Resetter fun resetAccess() { allowed = false }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ReminderSpecialAccessShadow::class])
class ExamReminderLifecycleSimulationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun prepare() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.USE_FULL_SCREEN_INTENT)
        ReminderSpecialAccessShadow.allowed = false
    }

    private fun deliver(enabled: Boolean = true, fullScreen: Boolean = false) {
        val intent = Intent(context, ExamReminderReceiver::class.java)
            .putExtra("exam_id", "fixture-exam")
            .putExtra("exam_course", "合成考试")
            .putExtra("exam_date", LocalDate.now().plusDays(1).toString())
            .putExtra("exam_time", "08:00-10:00")
        ExamReminderReceiver { ExamReminderSettings(enabled = enabled, fullScreenEnabled = fullScreen) }
            .onReceive(context, intent)
    }

    @Test fun `manifest permission does not override revoked Android 14 special access`() {
        assertFalse(ExamReminder.canUseFullScreenIntent(context))
        ReminderSpecialAccessShadow.allowed = true
        assertTrue(ExamReminder.canUseFullScreenIntent(context))
        ReminderSpecialAccessShadow.allowed = false
        assertFalse(ExamReminder.canUseFullScreenIntent(context))
    }

    @Test fun `Android 14 settings link opens this package special access page`() {
        val intent = ExamReminder.fullScreenSettingsIntent(context)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test @Config(sdk = [33])
    fun `older Android settings retain the normal notification page`() {
        val intent = ExamReminder.fullScreenSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(ExamReminder.canUseFullScreenIntent(context))
    }

    @Test fun `already queued broadcast is discarded after reminder preference is disabled`() {
        deliver(enabled = false)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun `enabled reminder still posts a normal notification`() {
        deliver()
        val notice = shadowOf(manager).allNotifications.single()
        assertEquals(ExamReminder.CHANNEL_ID, notice.channelId)
        assertNull(notice.fullScreenIntent)
    }

    @Test fun `denied full screen access falls back to a regular reminder`() {
        deliver(fullScreen = true)
        assertNull(shadowOf(manager).allNotifications.single().fullScreenIntent)
    }

    @Test fun `granted full screen access attaches the activity intent`() {
        ReminderSpecialAccessShadow.allowed = true
        deliver(fullScreen = true)
        assertNotNull(shadowOf(manager).allNotifications.single().fullScreenIntent)
    }

    @Test fun `revoked runtime notification permission drops a queued broadcast`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        deliver()
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun `disabled app notifications drop a queued broadcast`() {
        shadowOf(manager).setNotificationsEnabled(false)
        deliver()
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun `alarm capacity keeps the nearest 100 exams regardless of cache order`() {
        val today = LocalDate.now()
        val exams = (110 downTo 1).map { day ->
            XmuExam("exam-$day", "考试 $day", today.plusDays(day.toLong()).toString(), "08:00-10:00", "", "", "")
        }
        ExamReminder.schedule(context, ExamReminderSettings(enabled = true), exams)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        val ids = alarms.map { shadowOf(it.operation).savedIntent.getStringExtra("exam_id") }.toSet()
        assertEquals((1..100).map { "exam-$it" }.toSet(), ids)
        ExamReminder.cancelAll(context)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}
