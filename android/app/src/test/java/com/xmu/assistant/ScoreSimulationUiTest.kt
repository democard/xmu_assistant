package com.xmu.assistant

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp")
class ScoreSimulationUiTest {
    @get:Rule val composeRule = createComposeRule()

    private fun record(score: Double, credit: Double, gradePoint: Double) = XmuScoreRecord(
        courseCode = "MATH", courseName = "高等数学", term = "2026-2027 第一学期", termCode = "20261",
        credit = credit, score = score, gradePoint = gradePoint,
        countsForStatistics = true, countsForCompletedCredit = true,
    )

    private fun setContent(records: List<XmuScoreRecord>) {
        composeRule.setContent {
            XmuMobileTheme {
                ScorePage(
                    records = records, loading = false, refreshError = "", loggedIn = true,
                    updatedAtMillis = 0L, onRefresh = {}, onShareScore = {},
                )
            }
        }
    }

    @Test fun `simulated grade moves both average and weighted rows`() {
        // 真实 90 分 / 4.0 / 3 学分；模拟 80 分（3.0）/ 1 学分。
        // 平均（等权）：成绩 90 → 85、绩点 4.00 → 3.50；加权：成绩 90 → 87.50、绩点 4.00 → 3.75。
        setContent(listOf(record(90.0, 3.0, 4.0)))

        composeRule.onNodeWithText("+ 添加一门").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction() and hasText("预计成绩")).performScrollTo().performTextInput("80")
        composeRule.onNode(hasSetTextAction() and hasText("学分")).performTextInput("1")

        // 平均成绩这一行必须随模拟变化，而不是只有加权项变化。
        composeRule.onNodeWithTag("sim-avg-score").assertTextEquals("85.00")
        composeRule.onNodeWithTag("sim-avg-gpa").assertTextEquals("3.50")
        composeRule.onNodeWithTag("sim-weighted-score").assertTextEquals("87.50")
        composeRule.onNodeWithTag("sim-weighted-gpa").assertTextEquals("3.75")

        // 顶部概览同步使用合并结果：平均分数与加权分数各一处，模拟区各一处。
        composeRule.onAllNodesWithText("85.00").assertCountEquals(2)
        composeRule.onAllNodesWithText("87.50").assertCountEquals(2)
        composeRule.onNodeWithText("1 已出 · 1 模拟").assertExists()
    }

    @Test fun `invalid row explains why the panel stays unchanged`() {
        setContent(listOf(record(90.0, 3.0, 4.0)))

        composeRule.onNodeWithText("+ 添加一门").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction() and hasText("预计成绩")).performScrollTo().performTextInput("400")
        composeRule.onNode(hasSetTextAction() and hasText("学分")).performTextInput("100")

        composeRule.onNodeWithText("当前行尚未计入：成绩需在 0–100、学分需大于 0").assertExists()
        // 无效行不修改统计：模拟区两侧保持真实成绩。
        composeRule.onNodeWithTag("sim-avg-score").assertTextEquals("90.00")
        composeRule.onNodeWithTag("sim-weighted-score").assertTextEquals("90.00")
    }
}
