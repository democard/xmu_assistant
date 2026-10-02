package com.xmu.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamReminderPlanTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val settings = ExamReminderSettings(enabled = true)

    @Before fun prepare() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun schedule(id: String): Intent {
        ExamReminder.schedule(context, settings, listOf(
            XmuExam(id, "Synthetic exam $id", LocalDate.now().plusDays(1).toString(), "08:00-10:00", "", "", ""),
        ))
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        // The system already queued this broadcast before cancellation or replacement.
        return Intent(shadowOf(alarm.operation).savedIntent)
    }

    private fun deliver(intent: Intent) {
        ExamReminderReceiver { settings }.onReceive(context, intent)
    }

    @Test fun `old cache read cannot rebuild reminders after cancellation`() {
        val priorPlan = ExamReminderPlans.snapshot(context)
        val oldExams = listOf(
            XmuExam("old-account", "Synthetic exam", LocalDate.now().plusDays(1).toString(), "08:00-10:00", "", "", ""),
        )
        ExamReminder.cancelAll(context)

        ExamReminder.schedule(context, settings, oldExams, expectedPlan = priorPlan)

        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun `legacy alarm stays compatible only until the first plan change`() {
        val legacy = Intent(context, ExamReminderReceiver::class.java)
            .putExtra("exam_id", "legacy-exam")
            .putExtra("exam_course", "Synthetic legacy exam")
        deliver(legacy)
        assertEquals(1, shadowOf(manager).size())
        manager.cancelAll()

        ExamReminder.cancelAll(context)
        deliver(legacy)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun `cancelled plan cannot post an already queued reminder while preference stays enabled`() {
        val queued = schedule("old-account")
        ExamReminder.cancelAll(context)

        deliver(queued)

        assertEquals("Logout cancellation must reject queued reminders too", 0, shadowOf(manager).size())
    }

    @Test fun `replacement plan rejects old queued exam and still delivers its current exam`() {
        val old = schedule("old-time")
        val current = schedule("new-time")

        deliver(old)
        assertEquals("Replacing an exam time must invalidate the old broadcast", 0, shadowOf(manager).size())
        deliver(current)
        assertEquals(1, shadowOf(manager).size())
    }
}
