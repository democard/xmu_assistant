"""Compare a real Qt history table with files produced by its export action."""
import csv
import os
import sys
import tempfile
import unittest
from dataclasses import replace
from datetime import date, timedelta
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtWidgets import QApplication, QCheckBox, QComboBox, QLabel, QTableWidget, QWidget
from xmu_rollcall.desktop_qt.core import CourseRollcallRecord
from xmu_rollcall.desktop_qt.courses_page import CoursesPageMixin


class HistoryHost(CoursesPageMixin, QWidget):
    def __init__(self):
        super().__init__()
        self.course_table = QTableWidget(0, 8, self)
        self.course_summary = QLabel(self)
        self.time_range_combo = QComboBox(self)
        self.time_range_combo.addItems(["本学期", "今天", "本周"])
        self.only_unsigned_check = QCheckBox(self)
        today, yesterday = date.today(), date.today() - timedelta(days=1)

        def record(key, day, hour, status):
            return CourseRollcallRecord("course-" + key, "虚构课程" + key, key,
                                        f"{day.isoformat()} {hour}:00:00" if day else "未知",
                                        "数字签到", status, "closed")

        self.course_records = [
            record("old", yesterday, "10", "未签到"),
            record("signed", today, "12", "已签到"),
            record("early", today, "09", "未签到"),
            record("unknown-time", None, "00", "未签到"),
            record("late", today, "11", "未签到"),
        ]

    def _ui_palette(self):
        return {"group_row_fg": "#333333", "group_row_bg": "#eeeeee"}

    def _style_status_item(self, *_):
        pass


class HistoryExportSimulationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.host = HistoryHost()
        self.addCleanup(self.host.close)

    def assert_export_matches_table(self, expected, *, refresh=True):
        if refresh:
            self.host._refresh_course_table()
        table_ids = [self.host.course_table.item(row, 7).text()
                     for row in range(self.host.course_table.rowCount())
                     if self.host.course_table.item(row, 7) is not None]
        self.assertEqual(expected, table_ids)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "history.csv"
            with patch("xmu_rollcall.desktop_qt.courses_page.QFileDialog.getSaveFileName",
                       return_value=(str(path), "")), patch(
                           "xmu_rollcall.desktop_qt.courses_page.QMessageBox.information"):
                self.host._export_course_csv()
            self.assertTrue(path.read_bytes().startswith(b"\xef\xbb\xbf"))
            with path.open(encoding="utf-8-sig", newline="") as handle:
                rows = list(csv.reader(handle))
            self.assertEqual(table_ids, [row[11] for row in rows[1:]])

    def test_export_follows_visible_date_status_time_order(self):
        self.assert_export_matches_table(["late", "early", "signed", "old", "unknown-time"])

    def test_unsigned_today_export_matches_filtered_table(self):
        self.host.time_range_combo.setCurrentText("今天")
        self.host.only_unsigned_check.setChecked(True)
        self.assert_export_matches_table(["late", "early"])

    def test_canceled_export_does_not_create_file(self):
        self.host._refresh_course_table()
        with patch("xmu_rollcall.desktop_qt.courses_page.QFileDialog.getSaveFileName", return_value=("", "")), \
                patch("xmu_rollcall.desktop_qt.courses_page.open", create=True) as writer:
            self.host._export_course_csv()
        writer.assert_not_called()

    def test_in_place_verification_exports_current_status_in_existing_row_order(self):
        self.host._refresh_course_table()
        early = next(record for record in self.host.course_records if record.rollcall_id == "early")
        self.host._apply_verified_records([replace(early, signed_status="已签到", verified=True)])
        # Verification preserves row positions; re-sorting by the new status
        # would move early below signed, which no longer matches this table.
        self.assert_export_matches_table(["late", "early", "signed", "old", "unknown-time"], refresh=False)
        exported = self.host._visible_course_records()
        self.assertEqual("已签到", exported[1].signed_status)
        self.assertTrue(exported[1].verified)
