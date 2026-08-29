package com.xmu.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * RollcallEngine 顶层提取函数行为锁定（2026-08-30 补测债）：
 * findNumberCode 递归提取 / firstString 首个非空键 / radarPayload 载荷形态。
 * 几何求解（solveRadarCandidates）见 RollcallEngineAnswerTest。
 */
class RollcallEngineExtractTest {

    // ===== findNumberCode =====

    @Test
    fun `findNumberCode treats explicit json null as absent and keeps digging`() {
        // 平台显式 null code：optString 读成 "null" 字面量会被当有效码提交（旧实现），
        // 必须视为缺失并继续深搜嵌套结构
        val payload = org.json.JSONObject(
            "{\"number_code\":null,\"data\":{\"number_code\":\"486\"}}",
        )
        org.junit.Assert.assertEquals("486", findNumberCode(payload))
        val nullOnly = org.json.JSONObject("{\"number_code\":null}")
        org.junit.Assert.assertNull(findNumberCode(nullOnly))
    }

    @Test
    fun `findNumberCode extracts direct field`() {
        assertEquals("123456", findNumberCode(JSONObject().put("number_code", "123456")))
    }

    @Test
    fun `findNumberCode extracts from nested object`() {
        val root = JSONObject().put("data", JSONObject().put("rollcall", JSONObject().put("number_code", "8888")))
        assertEquals("8888", findNumberCode(root))
    }

    @Test
    fun `findNumberCode extracts from array elements`() {
        val root = JSONObject().put(
            "list",
            JSONArray().put(JSONObject().put("name", "a")).put(JSONObject().put("number_code", "777")),
        )
        assertEquals("777", findNumberCode(root))
    }

    @Test
    fun `findNumberCode prefers shallower sibling and skips blanks`() {
        // 同层先查 number_code 字段（命中即返回），再按键序递归
        val root = JSONObject()
            .put("number_code", "")
            .put("nested", JSONObject().put("number_code", "424242"))
        assertEquals("424242", findNumberCode(root))
    }

    @Test
    fun `findNumberCode returns null on absent blank and scalar values`() {
        assertNull(findNumberCode(null))
        assertNull(findNumberCode(JSONObject()))
        assertNull(findNumberCode(JSONObject().put("number_code", "   ")))
        assertNull(findNumberCode("plain string"))
        assertNull(findNumberCode(42))
        assertNull(findNumberCode(JSONArray()))
    }

    @Test
    fun `findNumberCode enforces recursion depth bound`() {
        // 12 层嵌套超过 depth>10 上限：防平台返回循环/超深结构时栈失控
        var deep: Any = JSONObject().put("number_code", "999")
        repeat(12) { deep = JSONObject().put("child", deep) }
        assertNull(findNumberCode(deep))
        // 10 层内仍可命中
        var shallow: Any = JSONObject().put("number_code", "999")
        repeat(9) { shallow = JSONObject().put("child", shallow) }
        assertEquals("999", findNumberCode(shallow))
    }

    // ===== firstString =====

    @Test
    fun `firstString returns first non blank key in order`() {
        val json = JSONObject().put("a", "").put("b", "x").put("c", "y")
        assertEquals("x", firstString(json, "a", "b", "c"))
        assertEquals("y", firstString(json, "c"))
        assertEquals("", firstString(json, "missing"))
    }

    @Test
    fun `firstString treats json null as blank`() {
        val json = JSONObject().put("a", JSONObject.NULL).put("b", "fallback")
        assertEquals("fallback", firstString(json, "a", "b"))
    }

    // ===== radarPayload =====

    @Test
    fun `radar payload carries fixed fields and echoes coordinates`() {
        val payload = radarPayload(lat = 24.4371, lon = 118.0975)
        assertEquals(35, payload.getInt("accuracy"))
        assertEquals(0, payload.getInt("altitude"))
        assertEquals(24.4371, payload.getDouble("latitude"), 1e-9)
        assertEquals(118.0975, payload.getDouble("longitude"), 1e-9)
        // 方向/速度未知用 JSON null；deviceId 为可解析 UUID（平台去重依赖）
        assertEquals(JSONObject.NULL, payload.get("heading"))
        assertEquals(JSONObject.NULL, payload.get("speed"))
        UUID.fromString(payload.getString("deviceId"))
    }

    @Test
    fun `radar payload generates fresh device id per call`() {
        assertNotEquals(
            radarPayload(0.0, 0.0).getString("deviceId"),
            radarPayload(0.0, 0.0).getString("deviceId"),
        )
    }
}
