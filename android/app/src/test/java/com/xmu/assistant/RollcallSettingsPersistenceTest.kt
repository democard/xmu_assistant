package com.xmu.assistant

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollcallSettingsPersistenceTest {
    @Test
    fun `custom wait mode and values survive save and reload`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("rollcall_settings_test", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AssistantSettings.forTest(context, prefs)
        val expected = RollcallSettings(
            pollIntervalSeconds = 17,
            autoAnswerNumber = true,
            autoAnswerRadar = false,
            waitBeforeAnswerMode = WAIT_BEFORE_ANSWER_PERCENT,
            waitBeforeAnswerCount = 7,
            waitBeforeAnswerPercent = 23,
        )
        store.saveRollcall(expected)
        assertEquals(expected, AssistantSettings.forTest(context, prefs).rollcall())
    }
}
