package com.xmu.assistant

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RollcallCardsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `regular width shows count percentage code and explicit missing progress`() {
        composeRule.setContent {
            XmuMobileTheme {
                Box(Modifier.width(390.dp).height(1600.dp).testTag("page")) {
                    RollcallStatusPage(
                        events = listOf(
                            event("高等数学", "数字签到", progress(18, 40), "001204"),
                            event("大学英语", "雷达签到", null),
                        ),
                        openedEventId = "", loading = false, refreshError = "", updatedAtMillis = 0,
                        historyItems = emptyList(), historyLoading = false, historyError = "",
                        historyUpdatedAtMillis = 0, loggedIn = true, onRefresh = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("18 / 40 人").assertExists()
        composeRule.onNodeWithText("45%").assertExists()
        composeRule.onNodeWithText("签到码 001204").assertExists()
        composeRule.onNodeWithText("签到进度").assertExists()
        composeRule.onNodeWithText("暂未获取").assertExists()
        val activeTitle = composeRule.onNodeWithText("高等数学").fetchSemanticsNode().boundsInRoot
        val activeCount = composeRule.onNodeWithText("18 / 40 人").fetchSemanticsNode().boundsInRoot
        val activeTeacher = composeRule.onNodeWithText("发起人：陈老师").fetchSemanticsNode().boundsInRoot
        val activeCode = composeRule.onNodeWithText("签到码 001204").fetchSemanticsNode().boundsInRoot
        assertTrue(activeCount.left >= activeTitle.right)
        assertTrue(activeCode.top >= activeTeacher.bottom)
    }

    @Test
    fun `history digital code follows count and percentage in right facts column`() {
        composeRule.setContent {
            XmuMobileTheme {
                Box(Modifier.width(302.dp)) {
                    RollcallRecordCard(
                        "历史课程", "数字签到 · 09-20 08:00", listOf("本人状态：已签"),
                        progress(7, 10), numberCode = "0099", numberCodeInFacts = true,
                    )
                }
            }
        }
        val count = composeRule.onNodeWithText("7 / 10 人").fetchSemanticsNode().boundsInRoot
        val percent = composeRule.onNodeWithText("70%").fetchSemanticsNode().boundsInRoot
        val code = composeRule.onNodeWithText("签到码 0099").fetchSemanticsNode().boundsInRoot
        assertEquals(count.right, percent.right, 1f)
        assertTrue(code.top >= percent.bottom)
        composeRule.onNodeWithText("复制").assertDoesNotExist()
    }

    @Test
    fun `320dp large font moves facts below long readable title`() {
        val title = "数据结构与算法分析课程设计（翔安校区实验班）"
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                XmuMobileTheme {
                    Box(Modifier.width(320.dp).height(1000.dp).testTag("narrow-page")) {
                        RollcallRecordCard(title, "数字签到 · 进行中", listOf("发起人：名字很长的老师"), progress(18, 40), numberCode = "000042")
                    }
                }
            }
        }
        val titleBounds = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val teacherBounds = composeRule.onNodeWithText("发起人：名字很长的老师").fetchSemanticsNode().boundsInRoot
        val factsBounds = composeRule.onNodeWithText("18 / 40 人").fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.width > 150f)
        assertTrue(factsBounds.top >= teacherBounds.bottom)
    }

    @Test
    fun `copy preserves leading zeroes and radar omits code`() {
        composeRule.setContent {
            XmuMobileTheme {
                RollcallRecordCard("线性代数", "数字签到 · 进行中", emptyList(), null, numberCode = "001204")
            }
        }
        composeRule.onNodeWithText("复制").performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("001204", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
    }

    private fun progress(present: Int, total: Int) = StudentRollcallProgress(
        total, present, total - present, 0, present * 100.0 / total, true,
    )

    private fun event(title: String, type: String, progress: StudentRollcallProgress?, code: String = "") =
        RollcallEvent(
            "$type-$title", title, if (title == "高等数学") "陈老师" else "李老师", type, "进行中",
            numberCode = code, progress = progress,
        )
}
