package com.xmu.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 体检报告 P1-5：optString 对显式 null 返回字符串 "null" 的陷阱。
 * optRealString 必须把 NULL / "null" 字面量 / 空白一律归为缺失。
 */
class OptRealStringTest {
    @Test
    fun `explicit null reads as missing`() {
        val obj = JSONObject("""{"rollcall_id": null}""")
        assertEquals("", obj.optRealString("rollcall_id"))
        assertEquals("fallback", obj.optRealString("rollcall_id", "fallback"))
    }

    @Test
    fun `literal null string reads as missing`() {
        val obj = JSONObject("""{"id": "null"}""")
        assertEquals("", obj.optRealString("id"))
    }

    @Test
    fun `missing key returns fallback`() {
        assertEquals("fb", JSONObject("{}").optRealString("id", "fb"))
        assertEquals("", JSONObject("{}").optRealString("id"))
    }

    @Test
    fun `numeric id passes through as string`() {
        val obj = JSONObject("""{"rollcall_id": 12345}""")
        assertEquals("12345", obj.optRealString("rollcall_id"))
    }

    @Test
    fun `value containing null substring passes through`() {
        val obj = JSONObject("""{"KSRWID": "null-island-42"}""")
        assertEquals("null-island-42", obj.optRealString("KSRWID"))
    }
}
