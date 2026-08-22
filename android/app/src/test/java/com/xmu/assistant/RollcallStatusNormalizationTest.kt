package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** 签到状态归一化回归测试（此前 normalizedRollcallStatus 零覆盖）。 */
class RollcallStatusNormalizationTest {

    @Test
    fun `unsigned must be judged before the signed substring`() {
        // "unsigned" 包含 "signed"：顺序颠倒会把 unsigned 误判为已签
        assertEquals("未签", normalizedRollcallStatus("unsigned"))
        assertEquals("未签", normalizedRollcallStatus("UNSIGNED"))
        assertEquals("未签", normalizedRollcallStatus("未签"))
        assertEquals("未签", normalizedRollcallStatus("未签到"))
        assertEquals("未签", normalizedRollcallStatus("absent"))
        assertEquals("未签", normalizedRollcallStatus("miss"))
    }

    @Test
    fun `signed variants normalize to signed`() {
        assertEquals("已签", normalizedRollcallStatus("signed"))
        assertEquals("已签", normalizedRollcallStatus("Fine"))
        assertEquals("已签", normalizedRollcallStatus("success"))
        assertEquals("已签", normalizedRollcallStatus("已签"))
        assertEquals("已签", normalizedRollcallStatus("已签到"))
    }

    @Test
    fun `unknown raw status maps to unknown`() {
        assertEquals("未知", normalizedRollcallStatus(""))
        assertEquals("未知", normalizedRollcallStatus("pending"))
        assertEquals("未知", normalizedRollcallStatus("expired"))
        assertEquals("未知", normalizedRollcallStatus("未知状态"))
    }

    @Test
    fun `substring lookalikes must not be misjudged as status`() {
        // "miss"/"fine" 子串误判回归：保证边界分词，而不是 contains
        assertEquals("未知", normalizedRollcallStatus("dismiss"))
        assertEquals("未知", normalizedRollcallStatus("define"))
        assertEquals("未知", normalizedRollcallStatus("refine"))
        assertEquals("未知", normalizedRollcallStatus("missing"))
    }

    @Test
    fun `not_signed must be judged unsigned before the signed token`() {
        // "not_signed" 分词成 [not, signed]，若 signed 分支先行会误判「已签」（双端一致性 A5）
        assertEquals("未签", normalizedRollcallStatus("not_signed"))
        assertEquals("未签", normalizedRollcallStatus("not signed"))
        assertEquals("未签", normalizedRollcallStatus("NOT_SIGNED"))
    }

    @Test
    fun `desktop status vocabulary is recognized identically`() {
        // 与桌面端 infer_signed_status 词表对齐（A5）：missed/unanswered 未签，present/attended/done 已签
        assertEquals("未签", normalizedRollcallStatus("missed"))
        assertEquals("未签", normalizedRollcallStatus("unanswered"))
        assertEquals("已签", normalizedRollcallStatus("present"))
        assertEquals("已签", normalizedRollcallStatus("attended"))
        assertEquals("已签", normalizedRollcallStatus("done"))
    }
}
