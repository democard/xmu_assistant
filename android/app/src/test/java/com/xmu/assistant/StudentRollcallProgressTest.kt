package com.xmu.assistant

import org.json.JSONArray
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
        assertEquals(0, result.unknown)
        assertEquals(66.66666666666667, result.percentage!!, 0.000001)
        assertTrue(result.reliablePercentage)
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
