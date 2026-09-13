package com.xmu.assistant

import android.app.AlarmManager
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 考试提醒闹钟的注册与取消行为（cancelAll 此前零行为覆盖）。
 * 取消路径改用 FLAG_NO_CREATE 后必须仍能清掉全部已注册槽位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamReminderCancelTest {

    @Test
    fun `cancelAll clears every registered reminder slot`() {
        val context = RuntimeEnvironment.getApplication()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

        val examDate = LocalDate.now().plusDays(10).toString()
        ExamReminder.schedule(
            context,
            ExamReminderSettings(enabled = true, advanceMinutes = 30),
            listOf(XmuExam("e1", "微积分", examDate, "08:00-10:00", "A306", "线下", "期末考试")),
        )
        assertTrue("a future exam must register an alarm", alarms.scheduledAlarms.isNotEmpty())

        ExamReminder.cancelAll(context)

        assertTrue("cancelAll must clear every registered slot", alarms.scheduledAlarms.isEmpty())
    }
}
