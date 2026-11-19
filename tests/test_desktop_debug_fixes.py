"""Regression tests for desktop bugfixes and UI-thread optimizations."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from PySide6.QtWidgets import QApplication, QLabel  # noqa: E402

import xmu_rollcall.config as config_module  # noqa: E402
from xmu_rollcall.courseware import CourseSummary, CoursewareItem  # noqa: E402
import xmu_rollcall.desktop_qt.app as app_module  # noqa: E402
from xmu_rollcall.desktop_qt.core import CourseRollcallRecord  # noqa: E402
from xmu_rollcall.desktop_qt.courses_page import CoursesPageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin  # noqa: E402
import xmu_rollcall.desktop_qt.ui_snapshot as ui_snapshot_module  # noqa: E402


class DesktopDebugFixesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt_app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.config_dir = Path(self.temp_dir.name)
        self.patchers = [
            mock.patch.object(config_module, "CONFIG_DIR", self.config_dir),
            mock.patch.object(config_module, "CONFIG_FILE", self.config_dir / "config.json"),
            mock.patch.object(ui_snapshot_module, "CONFIG_DIR", self.config_dir),
        ]
        for patcher in self.patchers:
            patcher.start()
        self.window = app_module.DashboardWindow()

    def tearDown(self):
        self.window.runtime_timer.stop()
        self.window.close()
        for patcher in reversed(self.patchers):
            patcher.stop()
        self.temp_dir.cleanup()

    def test_radar_latitude_label_is_in_row_6_column_0(self):
        grid = self.window.radar_custom_lat.parentWidget().layout()
        lat_pos = grid.getItemPosition(grid.indexOf(self.window.radar_custom_lat))
        self.assertEqual(lat_pos[:2], (6, 1))
        label_pos = None
        for index in range(grid.count()):
            widget = grid.itemAt(index).widget()
            if isinstance(widget, QLabel) and widget.text() == "纬度":
                label_pos = grid.getItemPosition(index)[:2]
                break
        self.assertEqual(label_pos, (6, 0))

    def test_radar_write_oserror_shows_warning_instead_of_crashing(self):
        course = CourseSummary("c1", "数学建模")
        self.window.courseware_courses = [course]
        self.window.refresh_radar_course_options()
        self.window.radar_custom_name.setText("坤銮楼")

        with (
            mock.patch.object(self.window.radar_coordinates, "add", side_effect=OSError("disk full")),
            mock.patch.object(self.window, "_radar_warn") as warn,
        ):
            self.window._radar_add_preset()
            self.window._radar_add_custom()
        self.assertEqual(warn.call_count, 2)
        self.assertEqual(warn.call_args_list[0].args[0], "保存失败")
        self.assertEqual(warn.call_args_list[1].args[0], "保存失败")

        with (
            mock.patch.object(self.window, "_selected_radar_place_id", return_value="p1"),
            mock.patch.object(
                self.window.radar_coordinates, "set_preferred", side_effect=OSError("read only")
            ),
            mock.patch.object(
                self.window.radar_coordinates, "delete", side_effect=OSError("read only")
            ),
            mock.patch.object(self.window, "_radar_warn") as warn,
        ):
            self.window._radar_set_preferred()
            self.window._radar_delete()
        self.assertEqual(warn.call_count, 2)

    def test_course_summary_includes_leave_count_when_present(self):
        host = object.__new__(CoursesPageMixin)
        host.course_records = [
            CourseRollcallRecord("c1", "高数", "r1", "2026-10-01 08:00:00", "数字签到", "已签到", ""),
            CourseRollcallRecord("c1", "高数", "r2", "2026-10-02 08:00:00", "数字签到", "请假", ""),
        ]
        self.assertEqual(
            host._course_summary_base_text(),
            "共 2 条；已签 1；未签 0；未知 0；无记录 0；请假 1",
        )

    def test_courseware_items_reset_prompt_when_selection_cleared(self):
        host = object.__new__(CoursewarePageMixin)
        summary = SimpleNamespace(text="正在读取《高数》课件...", setText=lambda t: setattr(summary, "text", t))
        host.courseware_summary = summary
        host.courseware_combo = SimpleNamespace(count=lambda: 1, currentIndex=lambda: 0)
        host._selected_courseware_course = lambda: None
        host._refresh_courseware_table = lambda: None
        host._update_nav_badges = lambda: None

        host._set_courseware_items(CourseSummary("c1", "高数"), [])
        self.assertEqual(summary.text, "请选择课程")

    def test_download_progress_skips_full_table_rebuild_when_already_downloading(self):
        self.window.session = object()
        self.window.account = {"id": "1", "username": "u1"}
        token = self.window.courseware_download_batch_token = object()
        item = CoursewareItem(
            "c1", "a1", "讲义", "material", "第一章", "", "u1", "ref1",
            "slides.pdf", 1024, "file", "", "ready", True,
        )
        self.window.courseware_items = [item]
        key = self.window._courseware_key(item)

        with (
            mock.patch.object(self.window, "_refresh_courseware_table") as refresh_table,
            mock.patch.object(self.window, "_update_nav_badges") as update_badges,
        ):
            self.window._ev_courseware_download_progress(
                ("courseware_download_progress", token, "1", 1, 1, "slides.pdf", key, 512, 1024)
            )
            self.window._ev_courseware_download_progress(
                ("courseware_download_progress", token, "1", 1, 1, "slides.pdf", key, 1024, 1024)
            )

        self.assertEqual(refresh_table.call_count, 1)
        self.assertEqual(update_badges.call_count, 1)
        self.assertIn("100%", self.window.courseware_summary.text())

    def test_verified_records_refresh_persists_ui_snapshot(self):
        self.window.session = object()
        self.window.account = {"id": "1", "username": "u1"}
        record = CourseRollcallRecord("c1", "高数", "r1", "2026-10-01 08:00:00", "数字签到", "未知", "")
        self.window.course_records = [record]
        updated = CourseRollcallRecord(
            "c1", "高数", "r1", "2026-10-01 08:00:00", "数字签到", "已签到", "", verified=True
        )
        with mock.patch.object(self.window, "_save_ui_snapshot") as save_snapshot:
            self.window._ev_course_records_verified(("course_records_verified", [updated], "1", "auto"))
        save_snapshot.assert_called_once_with()

    def test_log_trims_in_place_to_max_lines(self):
        buf = self.window.log_messages
        for index in range(app_module.MAX_LOG_LINES + 25):
            self.window.log(f"line-{index}")
        self.assertIs(self.window.log_messages, buf)
        self.assertEqual(len(self.window.log_messages), app_module.MAX_LOG_LINES)
        self.assertIn(f"line-{app_module.MAX_LOG_LINES + 24}", self.window.log_messages[-1])
