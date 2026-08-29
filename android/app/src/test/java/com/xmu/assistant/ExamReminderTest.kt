package com.xmu.assistant

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 考试提醒触发时刻计算（开考前 N 分钟）。 */
class ExamReminderTest {

    @Test
    fun `trigger is exam start minus advance minutes`() {
        val exam = XmuExam(
            id = "1",
            courseName = "微积分I-2",
            date = "2026-06-14",
            timeRange = "08:00-10:00",
            room = "A306",
            mode = "线下",
            examName = "期末考试",
        )
        val trigger = ExamReminder.examReminderTriggerAtMillis(exam, advanceMinutes = 30)
        val expected = java.time.LocalDateTime.of(2026, 6, 14, 7, 30)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }

    @Test
    fun `zero advance triggers exactly at exam start`() {
        val exam = XmuExam("1", "考试", "2026-06-14", "08:00-10:00", "", "线下", "")
        val trigger = ExamReminder.examReminderTriggerAtMillis(exam, advanceMinutes = 0)
        val expected = java.time.LocalDateTime.of(2026, 6, 14, 8, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }

    @Test
    fun `malformed date or time yields no trigger`() {
        val noDate = XmuExam("1", "考试", "", "08:00-10:00", "", "线下", "")
        assertNull(ExamReminder.examReminderTriggerAtMillis(noDate, 30))

        val noTime = XmuExam("1", "考试", "2026-06-14", "", "", "线下", "")
        assertNull(ExamReminder.examReminderTriggerAtMillis(noTime, 30))

        val malformed = XmuExam("1", "考试", "2026-06-14", "不定时", "", "线下", "")
        assertNull(ExamReminder.examReminderTriggerAtMillis(malformed, 30))
    }

    @Test
    fun `midnight crossing exam still triggers on the exam date`() {
        // 结束时刻早于开始时刻（23:30-00:30）只表示结束跨到次日；
        // 开始时刻永远在考试当天，提醒必须按当天开始时刻计算
        val exam = XmuExam("1", "跨午夜考试", "2026-06-14", "23:30-00:30", "", "线下", "")
        val trigger = ExamReminder.examReminderTriggerAtMillis(exam, advanceMinutes = 30)
        val expected = java.time.LocalDateTime.of(2026, 6, 14, 23, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }

    @Test
    fun `normal day crossing keeps same date when end after start`() {
        val exam = XmuExam("1", "正常考试", "2026-06-14", "22:00-23:59", "", "线下", "")
        val trigger = ExamReminder.examReminderTriggerAtMillis(exam, advanceMinutes = 30)
        val expected = java.time.LocalDateTime.of(2026, 6, 14, 21, 30)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }
}
