package com.xmu.assistant

import android.app.Activity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class RankPageUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `small screen cleanup requires confirmation and preserves result`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val epoch = SessionEpoch(); val owner = epoch.attachOwner()
        val result = RankResult("r", 8, 90, "全部成绩", 100, 200, "", rankScoreFingerprint(emptyList()), "cGRm")
        var stored = rankCacheToJson(RankCache(rankDigest("u"), result))
        val state = RankSectionState(context, RequestGate(), epoch, owner, scope,
            { true }, { "c=test" }, { "u" }, { "p" }, { false }, { "" }, {},
            { stored }, { json, active -> if (active()) { stored = json; true } else false }, {}, emptyList())
        try {
            composeRule.setContent { XmuMobileTheme { RankPage(state) {} } }
            composeRule.onNodeWithText("重新获取专业排名").assertExists()
            composeRule.onNodeWithText("清理 PDF").performScrollTo().performClick()
            composeRule.onNodeWithText("清理本地 PDF？").assertIsDisplayed()
            composeRule.onNodeWithText("取消").performClick()
            composeRule.runOnIdle { assertEquals("cGRm", state.cache.result?.pdfBase64) }
            composeRule.onNodeWithText("清理 PDF").performClick()
            composeRule.onNodeWithText("清理", useUnmergedTree = true).performClick()
            composeRule.waitUntil(10000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                !state.loading
            }
            composeRule.onNodeWithText("本地 PDF 已清理。排名与日期仍保留，已导出的副本不受影响。").performScrollTo().assertIsDisplayed()
            composeRule.runOnIdle { assertEquals(result.copy(pdfBase64 = ""), state.cache.result) }
        } finally { scope.cancel() }
    }
}
