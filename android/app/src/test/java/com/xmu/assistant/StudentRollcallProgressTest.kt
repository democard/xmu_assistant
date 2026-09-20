package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentRollcallProgressTest {

    @Test
    fun `supports primary and legacy present status fields`() {
        val result = parseStudentRollcallProgress(
            """{"student_rollcalls":[
                {"student_id":"1","status":"on_call"},
                {"student_id":"2","rollcall_status":"on_call_fine"},
                {"student_id":"3","student_rollcall_status":"absent"}
            ]}""",
        )

        assertEquals(3, result.observed)
        assertEquals(2, result.present)
        assertEquals(1, result.absent)
        assertEquals(0, result.leave)
        assertEquals(0, result.unknown)
        assertEquals(66.66666666666667, result.percentage!!, 0.000001)
        assertTrue(result.reliablePercentage)
    }

    @Test
    fun `observed leave statuses are equivalent and old construction defaults leave to zero`() {
        val result = parseStudentRollcallProgress(
            """{"student_rollcalls":[
                {"student_id":"1","status":"on_leave"},
                {"student_id":"2","rollcall_status":"on_personal_leave"},
                {"student_id":"3","status":"on_sick_leave"},
                {"student_id":"4","rollcall_status":"on_public_leave"},
                {"student_id":"5","status":"on_leave","rollcall_status":"on_personal_leave"}
            ]}""",
        )

        assertEquals(5, result.total)
        assertEquals(0, result.present)
        assertEquals(0, result.absent)
        assertEquals(5, result.leave)
        assertEquals(0, result.unknown)
        assertTrue(result.reliablePercentage)
        assertEquals(0.0, result.percentage!!, 0.0)

        val legacyConstruction = StudentRollcallProgress(1, 1, 0, 0, 100.0, true)
        assertEquals(0, legacyConstruction.leave)
    }

    @Test
    fun `late statuses count as present while retaining a reliable roster`() {
        val result = parseStudentRollcallProgress(
            """{"student_rollcalls":[
                {"student_id":"1","status":"late"},
                {"student_id":"2","rollcall_status":"on_call_arrive_late"},
                {"student_id":"3","status":"late","rollcall_status":"on_call_arrive_late"}
            ]}""",
        )

        assertEquals(3, result.total)
        assertEquals(3, result.present)
        assertEquals(0, result.leave)
        assertEquals(0, result.unknown)
        assertEquals(100.0, result.percentage!!, 0.0)
        assertTrue(result.reliablePercentage)
    }

    @Test
    fun `leave or late conflicts with a different class status remain unknown`() {
        val result = parseStudentRollcallProgress(
            """{"student_rollcalls":[
                {"student_id":"1","status":"on_leave","rollcall_status":"on_call"},
                {"student_id":"2","status":"on_personal_leave","rollcall_status":"absent"},
                {"student_id":"3","status":"late","rollcall_status":"absent"}
            ]}""",
        )

        assertEquals(3, result.total)
        assertEquals(0, result.present)
        assertEquals(0, result.absent)
        assertEquals(0, result.leave)
        assertEquals(3, result.unknown)
        assertUnreliable(result)
    }

    @Test
    fun `seventy two present and one leave uses the full roster denominator`() {
        val records = JSONArray()
        repeat(72) { index ->
            records.put(
                JSONObject()
                    .put("user_no", "present-$index")
                    .put("status", "on_call")
                    .put("rollcall_status", "on_call_fine"),
            )
        }
        records.put(
            JSONObject()
                .put("user_no", "leave")
                .put("status", "on_leave")
                .put("rollcall_status", "on_personal_leave"),
        )

        val result = parseStudentRollcallProgress(records)

        assertEquals(73, result.total)
        assertEquals(72, result.present)
        assertEquals(1, result.leave)
        assertEquals(98.63013698630137, result.percentage!!, 0.0000000001)
        assertTrue(result.reliablePercentage)
        assertTrue(
            RollcallSettings(
                waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_PERCENT,
                waitBeforeAnswerPercent = 98,
            ).thresholdReached(result),
        )
        assertFalse(
            RollcallSettings(
                waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_PERCENT,
                waitBeforeAnswerPercent = 99,
            ).thresholdReached(result),
        )
    }

    @Test
    fun `user number identity used by live roster produces reliable percentage`() {
        val result = parseStudentRollcallProgress(
            """[{"user_no":"me","status":"on_call"},{"user_no":"other","status":"absent"}]""",
        )
        assertTrue(result.reliablePercentage)
        assertEquals(50.0, result.percentage!!, 0.0)
    }

    @Test
    fun `unknown and conflicting statuses suppress percentage`() {
        val result = parseStudentRollcallProgress(
            """{"student_rollcalls":[
                {"student_id":"1","status":"on_call","rollcall_status":"absent"},
                {"student_id":"2","status":"later"},
                {"student_id":"3","status":"absent"}
            ]}""",
        )

        assertEquals(3, result.observed)
        assertEquals(0, result.present)
        assertEquals(1, result.absent)
        assertEquals(2, result.unknown)
        assertFalse(result.reliablePercentage)
        assertEquals(null, result.percentage)
    }

    @Test
    fun `empty missing malformed non object and duplicate inputs are unreliable`() {
        assertUnreliable(parseStudentRollcallProgress("{" + "}"))
        assertUnreliable(parseStudentRollcallProgress("""{"student_rollcalls":[]}"""))
        assertUnreliable(parseStudentRollcallProgress("""{"student_rollcalls":[null]}"""))
        assertUnreliable(parseStudentRollcallProgress("not json"))

        val duplicate = parseStudentRollcallProgress(
            """[{"student_id":"1","status":"on_call"},{"student_id":"1","status":"absent"}]""",
        )
        assertEquals(1, duplicate.observed)
        assertEquals(0, duplicate.present)
        assertEquals(0, duplicate.absent)
        assertEquals(1, duplicate.unknown)
        assertFalse(duplicate.reliablePercentage)
        assertEquals(null, duplicate.percentage)
    }

    @Test
    fun `reference minimal status roster needs no identity or extra flag`() {
        val result = parseStudentRollcallProgress(
            """[{"status":"on_call"},{"status":"absent"}]""",
        )
        assertEquals(2, result.observed)
        assertEquals(1, result.present)
        assertEquals(1, result.absent)
        assertEquals(0, result.unknown)
        assertTrue(result.reliablePercentage)
        assertEquals(50.0, result.percentage!!, 0.0)
    }

    @Test
    fun `explicitly partial roster does not produce class percentage`() {
        val result = parseStudentRollcallProgress(
            """{"student_rollcalls":[{"status":"on_call"},{"status":"absent"}]}""",
            rosterComplete = false,
        )
        assertEquals(2, result.total)
        assertUnreliable(result)
    }

    @Test
    fun `shared reference fixtures match PC results`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/attendance_progress_reference_cases.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = JSONArray(text)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val result = parseStudentRollcallProgress(case.getJSONObject("payload"))
            val expected = case.getJSONObject("expected")
            val name = case.getString("name")
            assertEquals(name, expected.getInt("present"), result.present)
            assertEquals(name, expected.getInt("absent"), result.absent)
            assertEquals(name, expected.optInt("leave", 0), result.leave)
            assertEquals(name, expected.optInt("unknown", 0), result.unknown)
            assertEquals(name, expected.getInt("total"), result.total)
            if (expected.isNull("percent")) {
                assertUnreliable(result)
            } else {
                assertTrue(name, result.reliablePercentage)
                assertEquals(name, expected.getDouble("percent"), result.percentage!!, 0.000001)
            }
        }
    }

    private fun assertUnreliable(result: StudentRollcallProgress) {
        assertFalse(result.reliablePercentage)
        assertEquals(null, result.percentage)
    }
}
