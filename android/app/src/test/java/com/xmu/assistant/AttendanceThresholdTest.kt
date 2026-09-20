package com.xmu.assistant

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceThresholdTest {
    @Test
    fun `shared threshold reference cases match`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/attendance_threshold_reference_cases.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = JSONArray(text)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val progress = case.optJSONObject("progress")?.let { value ->
                val present = value.getInt("present")
                val total = value.getInt("total")
                val reliable = value.getBoolean("reliable")
                StudentRollcallProgress(
                    observed = total,
                    present = present,
                    absent = (total - present).coerceAtLeast(0),
                    unknown = 0,
                    percentage = if (reliable && total > 0) present * 100.0 / total else null,
                    reliablePercentage = reliable,
                )
            }
            val settings = RollcallSettings(
                waitBeforeAnswerMode = case.getString("mode"),
                waitBeforeAnswerCount = case.getInt("count"),
                waitBeforeAnswerPercent = case.getInt("percent"),
            )
            assertEquals(case.getString("name"), case.getBoolean("expected"), settings.thresholdReached(progress))
        }
    }
}
