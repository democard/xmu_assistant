"""签到情况页 / 课件页 Mixin 纯逻辑行为测试（片C 补测：此前两文件零行为覆盖）。

以 object.__new__ 构造 Mixin 子类宿主（零 Qt 控件构造，与
test_tray_and_notifications_page 同范式），只补齐被测方法实际读取的属性；
下拉框用 SimpleNamespace(currentText=lambda: ...) 模拟。
"""

from __future__ import annotations

import unittest
from datetime import date, datetime, timedelta
from types import SimpleNamespace

from xmu_rollcall.courseware import CourseSummary, CoursewareItem
from xmu_rollcall.desktop_qt.core import CourseRollcallRecord
from xmu_rollcall.desktop_qt.courses_page import CoursesPageMixin
from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin


def _record(signed_status="已签到", rollcall_time="2026-06-01 10:00:00",
            course_title="高等数学", verified=False, course_id="c1") -> CourseRollcallRecord:
    return CourseRollcallRecord(
        course_id=course_id, course_title=course_title, rollcall_id=f"rc-{course_title}",
        rollcall_time=rollcall_time, rollcall_type="数字签到",
        signed_status=signed_status, platform_status="", verified=verified,
    )


def _combo(text: str) -> SimpleNamespace:
    return SimpleNamespace(currentText=lambda: text)


class CoursesPageLogicTest(unittest.TestCase):
    @staticmethod
    def _host(**attrs):
        host = object.__new__(CoursesPageMixin)
        host.__dict__.update(attrs)
        return host

    def test_status_text_maps_raw_status_and_keeps_unknown(self):
        host = self._host()
        self.assertEqual(host._course_status_text("未签到"), "未签")
        self.assertEqual(host._course_status_text("无签到记录"), "无记录")
        self.assertEqual(host._course_status_text("离谱状态"), "离谱状态")
        self.assertEqual(host._course_status_text(""), "未知")

    def test_tooltip_distinguishes_verified_from_aggregate(self):
        self.assertEqual(
            CoursesPageMixin._course_status_tooltip(_record(verified=True)),
            "已按本人签到明细核实",
        )
        self.assertEqual(
            CoursesPageMixin._course_status_tooltip(_record(verified=False)),
            "聚合状态仅供参考，可选中后点『核实所选』",
        )

    def test_time_range_default_accepts_everything_and_filters_today(self):
        today_text = date.today().strftime("%Y-%m-%d") + " 10:00:00"
        old = _record(rollcall_time="2020-01-01 08:00:00")
        today = _record(rollcall_time=today_text)
        self.assertTrue(self._host()._course_record_in_time_range(old))
        host_today = self._host(time_range_combo=_combo("今天"))
        self.assertTrue(host_today._course_record_in_time_range(today))
        self.assertFalse(host_today._course_record_in_time_range(old))
        self.assertFalse(
            host_today._course_record_in_time_range(_record(rollcall_time="-")),
            "时间不可解析的记录在今天/本周档必须被过滤",
        )

    def test_group_label_buckets_today_past_and_unknown(self):
        host = self._host()
        today_text = date.today().strftime("%Y-%m-%d") + " 09:30:00"
        self.assertEqual(host._course_group_label(_record(rollcall_time=today_text)),
                         ("今天", date.today()))
        past = host._course_group_label(_record(rollcall_time="2020-01-02 08:00:00"))
        self.assertEqual(past[0], "2020-01-02")
        self.assertEqual(past[1], date(2020, 1, 2))
        self.assertEqual(host._course_group_label(_record(rollcall_time="-")), ("时间未知", None))

    def test_sorted_records_order_by_priority_then_newest_then_title(self):
        old_unsigned = _record("未签到", "2020-01-01 08:00:00", course_title="A")
        new_unsigned = _record("未签到", "2026-06-01 08:00:00", course_title="B")
        signed = _record("已签到", "2026-06-02 08:00:00", course_title="C")
        unknown = _record("未知", "2026-06-03 08:00:00", course_title="D")
        no_record = _record("无签到记录", "", course_title="E")
        host = self._host()
        ordered = host._sorted_course_records([signed, no_record, new_unsigned, unknown, old_unsigned])
        self.assertEqual(
            [r.course_title for r in ordered],
            ["B", "A", "D", "C", "E"],
            "排序 = 状态优先级（未签<未知<已签<无记录）→ 同级新时间在前 → 课程名",
        )

    def test_summary_base_text_counts_all_records(self):
        host = self._host(course_records=[
            _record("已签到"), _record("已签到"), _record("未签到"),
            _record("未知"), _record("无签到记录"),
        ])
        self.assertEqual(
            host._course_summary_base_text(),
            "共 5 条；已签 2；未签 1；未知 1；无记录 1",
        )


class CoursewarePageLogicTest(unittest.TestCase):
    @staticmethod
    def _host(**attrs):
        host = object.__new__(CoursewarePageMixin)
        host.__dict__.update(attrs)
        return host

    @staticmethod
    def _item(entry_url="", upload_id="", filename="讲义.pdf", course_id="c1",
              activity_id="a1") -> CoursewareItem:
        return CoursewareItem(
            course_id=course_id, activity_id=activity_id, activity_title="第一章",
            activity_type="file", module_name="m", syllabus_name="s",
            upload_id=upload_id, reference_id="", filename=filename, size=1,
            media_type="", published_at="", upload_status="", allow_download=True,
            source_url=entry_url,
        )

    def test_short_error_categories(self):
        host = self._host()
        self.assertEqual(host._short_courseware_error("Connection timeout after 30s"), "网络失败")
        self.assertEqual(host._short_courseware_error("HTTP 403 Forbidden"), "登录过期")
        self.assertEqual(host._short_courseware_error("接口提示：登录态已失效"), "登录过期")
        self.assertEqual(host._short_courseware_error("404 not found"), "平台未提供地址")
        self.assertEqual(host._short_courseware_error("平台未返回资源地址"), "平台未提供地址")
        self.assertEqual(host._short_courseware_error("完全陌生的错误"), "下载失败")

    def test_course_matches_year_and_semester_filters(self):
        course = CourseSummary(course_id="c1", title="高数", term="2025-2026-1",
                               semester_code="2025-2026-1", search_text="高等数学")
        no_filter = self._host(
            courseware_academic_year_combo=_combo(""),
            courseware_semester_combo=_combo("全部"),
        )
        self.assertTrue(no_filter._courseware_course_matches_filters(course))
        year_hit = self._host(
            courseware_academic_year_combo=_combo("2025-2026"),
            courseware_semester_combo=_combo("全部"),
        )
        self.assertTrue(year_hit._courseware_course_matches_filters(course))
        year_miss = self._host(
            courseware_academic_year_combo=_combo("1999-2000"),
            courseware_semester_combo=_combo("全部"),
        )
        self.assertFalse(year_miss._courseware_course_matches_filters(course))
        semester_hit = self._host(
            courseware_academic_year_combo=_combo(""),
            courseware_semester_combo=_combo("第一学期"),
        )
        self.assertTrue(semester_hit._courseware_course_matches_filters(course))
        semester_miss = self._host(
            courseware_academic_year_combo=_combo(""),
            courseware_semester_combo=_combo("第二学期"),
        )
        self.assertFalse(semester_miss._courseware_course_matches_filters(course))

    def test_can_download_requires_entry_url_or_upload_id(self):
        self.assertTrue(self._host()._courseware_can_download(self._item(entry_url="https://x/y")))
        self.assertTrue(self._host()._courseware_can_download(self._item(upload_id="u1")))
        # entry_url 是派生 property：course_id+activity_id 在即恒有平台入口可打开，
        # 「不可下载」的必要条件是三处来源全空
        self.assertFalse(self._host()._courseware_can_download(
            self._item(upload_id="", course_id="", activity_id="")
        ))

    def test_courseware_key_is_composite_identity(self):
        item = self._item(upload_id="u1", filename="讲义.pdf", course_id="c1", activity_id="a1")
        self.assertEqual(self._host()._courseware_key(item), "c1:a1:u1:讲义.pdf")


if __name__ == "__main__":
    unittest.main()
