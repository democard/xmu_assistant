package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAutomationSummaryTest {
    @Test
    fun `type summary covers all saved combinations`() {
        assertEquals("仅提醒", autoAnswerTypeSummary(RollcallSettings()))
        assertEquals("仅数字", autoAnswerTypeSummary(RollcallSettings(autoAnswerNumber = true)))
        assertEquals("仅雷达", autoAnswerTypeSummary(RollcallSettings(autoAnswerRadar = true)))
        assertEquals(
            "数字与雷达",
            autoAnswerTypeSummary(RollcallSettings(autoAnswerNumber = true, autoAnswerRadar = true)),
        )
    }

    @Test
    fun `threshold summary covers none count and percent`() {
        assertEquals("不等待人数条件", waitBeforeAnswerSummary(RollcallSettings()))
        assertEquals(
            "达到 9 人",
            waitBeforeAnswerSummary(
                RollcallSettings(waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_COUNT, waitBeforeAnswerCount = 9),
            ),
        )
        assertEquals(
            "达到 35%",
            waitBeforeAnswerSummary(
                RollcallSettings(waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_PERCENT, waitBeforeAnswerPercent = 35),
            ),
        )
    }

    @Test
    fun `home shortcut toggles both types without changing threshold`() {
        val original = RollcallSettings(
            autoAnswerNumber = true,
            autoAnswerRadar = false,
            waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_PERCENT,
            waitBeforeAnswerCount = 7,
            waitBeforeAnswerPercent = 23,
        )
        val enabled = toggleAllAutoAnswer(original, true)
        assertTrue(enabled.autoAnswerNumber)
        assertTrue(enabled.autoAnswerRadar)
        assertEquals(original.waitBeforeAnswerMode, enabled.waitBeforeAnswerMode)
        assertEquals(original.waitBeforeAnswerCount, enabled.waitBeforeAnswerCount)
        assertEquals(original.waitBeforeAnswerPercent, enabled.waitBeforeAnswerPercent)
        val disabled = toggleAllAutoAnswer(enabled, false)
        assertFalse(disabled.autoAnswerNumber)
        assertFalse(disabled.autoAnswerRadar)
        assertEquals(23, disabled.waitBeforeAnswerPercent)
    }

    @Test
    fun `only active threshold input can block save`() {
        assertTrue(strategyRollcallSaveEnabled("30", WAIT_BEFORE_ANSWER_NONE, "", ""))
        assertFalse(strategyRollcallSaveEnabled("30", WAIT_BEFORE_ANSWER_COUNT, "0", "15"))
        assertFalse(strategyRollcallSaveEnabled("30", WAIT_BEFORE_ANSWER_COUNT, "", "15"))
        assertTrue(strategyRollcallSaveEnabled("30", WAIT_BEFORE_ANSWER_COUNT, "6", "bad"))
        assertFalse(strategyRollcallSaveEnabled("30", WAIT_BEFORE_ANSWER_PERCENT, "5", "101"))
        assertTrue(strategyRollcallSaveEnabled("30", WAIT_BEFORE_ANSWER_PERCENT, "bad", "20"))
        assertFalse(strategyRollcallSaveEnabled("301", WAIT_BEFORE_ANSWER_NONE, "5", "15"))
    }
}
