"""签到情况导出与统计纯函数（core.course_rollcall_csv / course_rollcall_stats）。

导出为纯本地聚合：不触网络、不依赖 Qt，序列化行为在此锁定；
UI 接线（按钮/文件对话框）由 app.py 负责，不在本文件覆盖范围。
"""

import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.core import (  # noqa: E402
    CourseRollcallRecord,
    CourseRollcallStat,
    course_display_status,
    course_rollcall_csv,
    course_rollcall_stats,
)


def record(**overrides) -> CourseRollcallRecord:
    base = dict(
        course_id="1001",
        course_title="高等数学A-2",
        rollcall_id="512345678",
        rollcall_time="2026-08-27 08:12",
        rollcall_type="数字签到",
        signed_status="已签到",
        platform_status="已提交",
        detail="",
        verified=False,
    )
    base.update(overrides)
    return CourseRollcallRecord(**base)


class CourseRollcallCsvTest(unittest.TestCase):
    def test_csv_header_and_row_order_match_input(self):
        rows = [record(signed_status="未签到"), record(course_title="大学英语(四)")]
        text = course_rollcall_csv(rows)
        lines = text.splitlines()
        self.assertEqual(
            lines[0],
            "日期,课程,签到时间,类型,状态,平台状态,已核实,签到ID,备注",
        )
        self.assertEqual(len(lines), 3)
        # 行序与输入一致（导出即所见）
        self.assertIn("高等数学A-2", lines[1])
        self.assertIn("大学英语(四)", lines[2])

    def test_status_column_uses_display_wording(self):
        text = course_rollcall_csv([record(signed_status="未签到", verified=True)])
        line = text.splitlines()[1]
        self.assertIn(",未签,", line)
        self.assertIn(",是,", line)

    def test_fields_with_comma_and_quote_are_rfc4180_escaped(self):
        text = course_rollcall_csv(
            [record(course_title='高数, "拔尖"班', detail="迟到, 已补签")]
        )
        line = text.splitlines()[1]
        self.assertIn('"高数, ""拔尖""班"', line)
        self.assertIn('"迟到, 已补签"', line)

    def test_unparseable_time_yields_empty_date_and_empty_input_only_header(self):
        text = course_rollcall_csv([record(rollcall_time="not-a-time")])
        self.assertTrue(text.splitlines()[1].startswith(","))
        only_header = course_rollcall_csv([])
        self.assertEqual(only_header.splitlines(), [only_header.rstrip("\n")])

    def test_csv_module_roundtrip_parses_export(self):
        import csv

        rows = [record(), record(course_title="含,逗号", signed_status="未知")]
        parsed = list(csv.reader(io.StringIO(course_rollcall_csv(rows))))
        self.assertEqual(len(parsed), 3)
        self.assertEqual(parsed[1][1], "高等数学A-2")
        self.assertEqual(parsed[2][4], "未知")


class CourseRollcallStatsTest(unittest.TestCase):
    def test_aggregates_by_course_and_sorts_by_title(self):
        stats = course_rollcall_stats(
            [
                record(course_title="大学英语(四)", signed_status="未签到"),
                record(course_title="高等数学A-2", signed_status="已签到"),
                record(course_title="高等数学A-2", signed_status="已签到"),
                record(course_title="高等数学A-2", signed_status="未知"),
            ]
        )
        # 排序按课程名 Unicode 码点（'大' U+5927 < '高' U+9AD8），输出确定性
        self.assertEqual([s.course_title for s in stats], ["大学英语(四)", "高等数学A-2"])
        top = stats[1]
        self.assertEqual(
            (top.total_rollcalls, top.signed, top.unsigned, top.unknown), (3, 2, 0, 1)
        )
        self.assertAlmostEqual(top.signed_rate, 2 / 3)

    def test_placeholder_rows_are_excluded_from_denominator(self):
        stats = course_rollcall_stats(
            [
                record(course_title="体育", signed_status="无签到记录"),
                record(course_title="体育", signed_status="已签到"),
            ]
        )
        self.assertEqual(len(stats), 1)
        self.assertEqual(stats[0].total_rollcalls, 1)
        self.assertEqual(stats[0].signed, 1)

    def test_zero_events_course_has_zero_rate_without_division_error(self):
        stats = course_rollcall_stats([record(course_title="体育", signed_status="无签到记录")])
        self.assertEqual(stats[0].total_rollcalls, 0)
        self.assertEqual(stats[0].signed_rate, 0.0)

    def test_empty_records_return_empty_list(self):
        self.assertEqual(course_rollcall_stats([]), [])

    def test_stat_is_frozen_dataclass(self):
        stat = CourseRollcallStat("课程", 0, 0, 0, 0)
        with self.assertRaises(Exception):
            stat.signed = 1  # type: ignore[misc]

    def test_display_status_passes_unknown_values_through(self):
        self.assertEqual(course_display_status("奇怪状态"), "奇怪状态")
        self.assertEqual(course_display_status(""), "未知")
        self.assertEqual(course_display_status("已签到"), "已签")


if __name__ == "__main__":
    unittest.main()
