from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_SRC = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "xmu" / "assistant"


def source(name: str) -> str:
    return (ANDROID_SRC / name).read_text(encoding="utf-8")


class AndroidScoreCopyPasteTests(unittest.TestCase):
    # 历史用例清理记录（2026-08-19，守卫测试过期修复轮）：
    # - test_github_score_project_is_vendored_verbatim：断言 docs/vendor/XMUScoreAutoQuery
    #   目录逐字存在，但该目录从未提交进本仓库（成绩客户端仅以注释引用其来源），
    #   iterdir() 直接抛错。成绩客户端对 vendored 规则的忠实性已由本类保留用例覆盖。
    # - test_android_active_code_has_no_schedule_feature：禁止出现课表功能——
    #   课表/考试模块早已合法落地，守卫意图已失效。

    def test_android_score_client_copies_percent_rule_and_keeps_other_results(self):
        client = source("XmuScoreAutoQueryClient.kt")
        models = source("XmuScoreModels.kt")
        # MainActivity 拆分后，成绩 UI 文案移入 UiComponents.kt / Pages.kt
        ui = source("UiComponents.kt") + source("Pages.kt")

        self.assertIn("appShow?appId=4768574631264620", client)
        self.assertIn("cxycjdxnxq.do", client)
        self.assertIn("xscjcx.do", client)
        self.assertIn("fetchTermWave", client)
        self.assertIn("maxParallel = MAX_PARALLEL_TERMS", client)
        # 并发度 3→5：真实场景 6-8 个学期，5 并发 1-2 波即可拉完（约 4-5 秒）
        self.assertIn("const val MAX_PARALLEL_TERMS = 5", client)
        self.assertIn("failedTerms", client)
        self.assertIn("fetchTermWave(failedTerms)", client)
        self.assertIn("loginAndGetToken()", client)
        self.assertIn("jar.clear()", client)
        self.assertIn("failures += ", client)
        self.assertIn("if (records.isEmpty() && failures.isNotEmpty())", client)
        self.assertIn("scoreGradeMode", client)
        self.assertIn('"P/NP"', client)
        self.assertIn('"P" else "NP"', client)
        self.assertIn("'SFYX'", client)
        self.assertIn("'SHOWMAXCJ'", client)
        self.assertIn("'XNXQDM'", client)
        self.assertIn('"*order" to "-XNXQDM,-KCH,-KXH"', client)
        self.assertIn('"querySetting" to queryTemplate(termCode)', client)
        self.assertIn('replace("*", "%2A")', client)
        self.assertIn("[{'name': 'SFYX'", client)
        self.assertIn("'caption': '是否有效'", client)
        self.assertIn("'caption': '显示最高成绩'", client)
        self.assertIn("'value': '$escapedTerm'", client)
        self.assertNotIn("queryTemplate(termCode).toString()", client)
        self.assertIn("成绩接口返回格式异常", client)
        self.assertIn('optString("DJCJLXDM_DISPLAY") == "百分制"', client)
        self.assertIn('optString("KCM")', client)
        self.assertIn('optString("ZCJ")', client)
        self.assertIn('optString("XFJD")', client)
        self.assertIn('optString("XF")', client)

        self.assertIn("resultText", models)
        self.assertIn("gradeMode", models)
        self.assertIn("countsForStatistics", models)
        self.assertIn("countsForCompletedCredit", models)
        self.assertIn("completedCredits", models)
        self.assertIn("records.filter { it.countsForCompletedCredit }.sumOf { it.credit }", models)

        combined = client + models + ui
        self.assertNotIn("gradePointFromScore", combined)
        self.assertNotIn("isNonGpa", combined)
        self.assertNotIn("isNonPercent", combined)
        self.assertNotIn("不计绩点", combined)
        self.assertNotIn("不计均分", combined)

        self.assertIn("平均绩点", ui)
        self.assertIn("加权绩点", ui)
        self.assertIn("平均分数", ui)
        self.assertIn("加权分数", ui)
        self.assertIn("已修总学分", ui)
        self.assertIn("scoreRecordDetail", ui)
        self.assertIn('"成绩 $result', ui)
        self.assertNotIn('"结果 $result', ui)
        self.assertIn("scoreResultText", ui)
        self.assertIn("scoreGradeModeText", ui)
        self.assertIn("isTwoLevelScore", ui)
        self.assertIn('"P/NP"', ui)
        self.assertIn('"成绩 $result · 学分', ui)
        self.assertIn('"成绩 $result · ${scoreGradeModeText(record)}', ui)
        self.assertIn("成绩", ui)
        self.assertIn("暂无成绩", ui)
        self.assertNotIn("暂无百分制成绩", ui)
        self.assertNotIn("只显示 GitHub 成绩脚本筛选出的百分制成绩", ui)
        # "Empty key" 特判已在源头类型化（AcademicLoginBlockedException /
        # MainSessionExpiredException），friendlyMessage 按类型识别，无需字符串匹配


if __name__ == "__main__":
    unittest.main()
