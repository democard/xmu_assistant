package com.xmu.assistant

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
    fun `missing identity keeps counts but does not claim reliable percentage`() {
        val result = parseStudentRollcallProgress(
            """[{"status":"on_call"},{"status":"absent"}]""",
        )
        assertEquals(2, result.observed)
        assertEquals(1, result.present)
        assertEquals(1, result.absent)
        assertEquals(0, result.unknown)
        assertFalse(result.reliablePercentage)
        assertEquals(null, result.percentage)
    }

    private fun assertUnreliable(result: StudentRollcallProgress) {
        assertFalse(result.reliablePercentage)
        assertEquals(null, result.percentage)
    }
}
