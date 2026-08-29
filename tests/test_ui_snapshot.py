from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import CourseSummary  # noqa: E402
from xmu_rollcall.desktop_qt import app as app_module  # noqa: E402
from xmu_rollcall.desktop_qt.core import CourseRollcallRecord  # noqa: E402


class UiSnapshotTests(unittest.TestCase):
    """SWR 缓存先行：UI 快照序列化往返与损坏兜底。"""

    def test_roundtrip_preserves_records_and_courses(self):
        records = [
            CourseRollcallRecord(
                course_id="c1",
                course_title="高等数学",
                rollcall_id="r1",
                rollcall_time="2026-08-22 08:00:00",
                rollcall_type="数字签到",
                signed_status="已签到",
                platform_status="signed",
                detail="",
            ),
        ]
        courses = [CourseSummary("c1", "高等数学", "2025-2026", "1", "搜索文本")]
        text = app_module.ui_snapshot_to_json(42, records, courses)

        parsed = app_module.ui_snapshot_from_json(text)
        self.assertIsNotNone(parsed)
        account_id, restored_records, restored_courses = parsed
        self.assertEqual(account_id, "42")
        self.assertEqual(restored_records[0].course_title, "高等数学")
        self.assertEqual(restored_records[0].signed_status, "已签到")
        self.assertEqual(restored_courses[0].title, "高等数学")
        self.assertEqual(restored_courses[0].semester_code, "1")

    def test_corrupted_json_returns_none(self):
        self.assertIsNone(app_module.ui_snapshot_from_json("{broken json"))
        self.assertIsNone(app_module.ui_snapshot_from_json("[]"))

    def test_wrong_version_returns_none(self):
        import json

        text = json.dumps({"version": 999, "account_id": "1", "course_records": [], "courseware_courses": []})
        self.assertIsNone(app_module.ui_snapshot_from_json(text))


if __name__ == "__main__":
    unittest.main()
