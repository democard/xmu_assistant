"""infer_signed_status / classify_rollcall_status 回归测试（A5 双端对齐）。

背景：桌面端原实现用子串匹配且「已签」先判，平台状态 not_signed/unsigned 含
signed 子串被误判为已签到（Android 端 A5 已修分词+先判未签，桌面端漏改）。
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.core import classify_rollcall_status, infer_signed_status  # noqa: E402


class ClassifyRollcallStatusTests(unittest.TestCase):
    def test_not_signed_is_absent_not_signed_in(self):
        # 回归主体："not_signed" 分词为 [not, signed]，不得命中 signed 分支
        self.assertEqual(classify_rollcall_status("not_signed"), "未签到")

    def test_unsigned_is_absent(self):
        self.assertEqual(classify_rollcall_status("unsigned"), "未签到")

    def test_absent_word_list(self):
        for raw in ("absent", "missed", "unanswered", "miss"):
            self.assertEqual(classify_rollcall_status(raw), "未签到", raw)

    def test_signed_word_list(self):
        for raw in ("signed", "present", "attended", "on_call_fine", "fine", "done"):
            self.assertEqual(classify_rollcall_status(raw), "已签到", raw)

    def test_substring_lookalikes_are_unknown(self):
        # 子串误判防护：dismiss 不命中 miss、define/refine 不命中 fine
        for raw in ("dismissed", "define", "refiner", "designer"):
            self.assertIsNone(classify_rollcall_status(raw), raw)

    def test_chinese_statuses(self):
        self.assertEqual(classify_rollcall_status("未签到"), "未签到")
        self.assertEqual(classify_rollcall_status("未签"), "未签到")
        self.assertEqual(classify_rollcall_status("已签到"), "已签到")
        self.assertEqual(classify_rollcall_status("已签"), "已签到")

    def test_empty_and_unknown(self):
        self.assertIsNone(classify_rollcall_status(""))
        self.assertIsNone(classify_rollcall_status(None))
        self.assertIsNone(classify_rollcall_status("unknown"))


class InferSignedStatusTests(unittest.TestCase):
    def test_platform_not_signed_reports_unsigned(self):
        rollcall = {"status": "not_signed"}
        signed, platform = infer_signed_status(rollcall, None, "u1")
        self.assertEqual(signed, "未签到")
        self.assertEqual(platform, "not_signed")

    def test_platform_signed_reports_signed(self):
        signed, _ = infer_signed_status({"status": "present"}, None, "u1")
        self.assertEqual(signed, "已签到")

    def test_own_record_timestamp_wins(self):
        detail = {"student_rollcalls": [{"user_no": "u1", "updated_at": "2026-08-22T08:00:00"}]}
        signed, _ = infer_signed_status({"status": "not_signed"}, detail, "u1")
        self.assertEqual(signed, "已签到")

    def test_own_record_not_signed_not_overridden_by_global_signed(self):
        # 全局 status=signed 但本人记录明确 not_signed：以本人记录为准报未签
        detail = {"student_rollcalls": [{"user_no": "u1", "status": "not_signed"}]}
        signed, _ = infer_signed_status({"status": "signed"}, detail, "u1")
        self.assertEqual(signed, "未签到")

    def test_unparseable_status_is_unknown(self):
        signed, _ = infer_signed_status({"status": "weird_state"}, None, "u1")
        self.assertEqual(signed, "未知")


if __name__ == "__main__":
    unittest.main()
