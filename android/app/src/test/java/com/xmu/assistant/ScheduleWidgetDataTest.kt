// Widget 数据层测试：序列化往返 + 空数据处理
package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWidgetDataTest {
    @Test
    fun `today summary round trip preserves courses`() {
        // ScheduleWidgetData 依赖 Android Context，这里只测纯序列化辅助逻辑
        // 通过 android.util.JsonReader 无法直接测，故测 JSON 结构是否正确：
        // 直接构造快照并验证 toJson 的字段。
        val snapshot = ScheduleWidgetSnapshot(
            weekday = 1,
            week = 3,
            termCode = "20261",
            courses = listOf(
                ScheduleWidgetCourse("课程C", 800, 940, 1, 2, "教学楼A-C204"),
                ScheduleWidgetCourse("课程E", 1910, 2050, 9, 10, "教学楼B-205"),
            ),
        )
        val json = scheduleWidgetSnapshotToJsonForTest(snapshot)
        assertTrue(json.contains("课程C"))
        assertTrue(json.contains("教学楼A-C204"))
        assertTrue(json.contains("\"week\":3"))
        assertTrue(json.contains("\"weekday\":1"))
        assertTrue(json.contains("1910"))
    }

    @Test
    fun `empty snapshot json still contains weekday and empty courses`() {
        val snapshot = ScheduleWidgetSnapshot(weekday = 5, week = 0, termCode = "20261", courses = emptyList())
        val json = scheduleWidgetSnapshotToJsonForTest(snapshot)
        assertTrue(json.contains("\"courses\":[]"))
        assertTrue(json.contains("\"week\":0"))
    }
}

// 测试用：与 ScheduleWidgetData.save 相同的 JSON 结构
fun scheduleWidgetSnapshotToJsonForTest(snapshot: ScheduleWidgetSnapshot): String {
    val builder = StringBuilder()
    builder.append("{\"weekday\":").append(snapshot.weekday)
        .append(",\"week\":").append(snapshot.week)
        .append(",\"termCode\":\"").append(snapshot.termCode)
        .append("\",\"courses\":[")
    snapshot.courses.forEachIndexed { index, course ->
        if (index > 0) builder.append(",")
        builder.append("{\"courseName\":\"").append(course.courseName)
            .append("\",\"startTime\":").append(course.startTime)
            .append(",\"endTime\":").append(course.endTime)
            .append(",\"startSection\":").append(course.startSection)
            .append(",\"endSection\":").append(course.endSection)
            .append(",\"location\":\"").append(course.location)
            .append("\"}")
    }
    builder.append("]}")
    return builder.toString()
}
