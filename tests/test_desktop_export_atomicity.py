"""Regression coverage for atomic desktop exports and spreadsheet text cells."""
from __future__ import annotations

import builtins
import csv
import io
import os
import sys
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QTableWidget, QTableWidgetItem, QWidget

from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.core import CourseRollcallRecord, course_rollcall_csv
from xmu_rollcall.desktop_qt.courses_page import CoursesPageMixin


class CourseExportHost(CoursesPageMixin, QWidget):
    def __init__(self, record: CourseRollcallRecord):
        super().__init__()
        self.course_records = [record]
        self.course_table = QTableWidget(1, 8, self)
        item = QTableWidgetItem(record.rollcall_id)
        item.setData(Qt.ItemDataRole.UserRole, (record.course_id, record.rollcall_id))
        self.course_table.setItem(0, 7, item)


class LogExportHost(QWidget):
    def __init__(self):
        super().__init__()
        self.log_messages = ["启动：高等数学🙂"]
        self.logged: list[str] = []

    def log(self, message: str) -> None:
        self.logged.append(message)


def sample_record(**overrides) -> CourseRollcallRecord:
    values = {
        "course_id": "course-1",
        "course_title": "高等数学 A",
        "rollcall_id": "512345678",
        "rollcall_time": "2026-08-27 08:12",
        "rollcall_type": "数字签到",
        "signed_status": "已签到",
        "platform_status": "已提交",
        "detail": "备注🙂",
        "verified": False,
        "attendance_present": 7,
        "attendance_total": 12,
        "attendance_percent": 58.3,
        "number_code": "1234",
    }
    values.update(overrides)
    return CourseRollcallRecord(**values)


def partial_write_failure(target: Path):
    real_open = builtins.open
    real_fdopen = os.fdopen

    class PartialWriter:
        def __init__(self, stream):
            self.stream = stream

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            self.stream.close()
            return False

        def write(self, text):
            self.stream.write(text[: max(1, len(text) // 2)])
            self.stream.flush()
            raise OSError("simulated disk full")

    def failing_open(path, *args, **kwargs):
        stream = real_open(path, *args, **kwargs)
        if Path(path) == target and args and "w" in args[0]:
            return PartialWriter(stream)
        return stream

    def failing_fdopen(fd, *args, **kwargs):
        return PartialWriter(real_fdopen(fd, *args, **kwargs))

    return patch("builtins.open", new=failing_open), patch("os.fdopen", new=failing_fdopen)


class DesktopExportAtomicityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def make_host(self, kind: str):
        if kind == "course":
            return CourseExportHost(sample_record())
        return LogExportHost()

    def invoke_export(self, kind: str, host, path: str):
        module = (
            "xmu_rollcall.desktop_qt.courses_page"
            if kind == "course"
            else "xmu_rollcall.desktop_qt.app"
        )
        with patch(
            module + ".QFileDialog.getSaveFileName", return_value=(path, "")
        ), patch(module + ".QMessageBox.warning") as warning, patch(
            module + ".QMessageBox.information"
        ) as information:
            if kind == "course":
                host._export_course_csv()
            else:
                DashboardWindow._export_logs(host)
        return warning, information

    def assert_failure_preserves_file(self, kind: str, failure: str) -> None:
        host = self.make_host(kind)
        self.addCleanup(host.close)
        if failure == "unicode":
            if kind == "course":
                host.course_records = [sample_record(course_title=chr(0xD800))]
            else:
                host.log_messages = [chr(0xD800)]
        with tempfile.TemporaryDirectory() as directory:
            suffix = ".csv" if kind == "course" else ".txt"
            target = Path(directory) / ("existing-export" + suffix)
            old_contents = b"previous export must survive"
            target.write_bytes(old_contents)

            first_patch, second_patch = nullcontext(), nullcontext()
            if failure == "write":
                first_patch, second_patch = partial_write_failure(target)
            elif failure == "replace":
                first_patch = patch("os.replace", side_effect=OSError("simulated replace failure"))
            elif failure == "flush":
                first_patch = patch("os.fsync", side_effect=OSError("simulated sync failure"))

            with first_patch, second_patch:
                warning, information = self.invoke_export(kind, host, str(target))

            self.assertEqual(target.read_bytes(), old_contents)
            warning.assert_called_once()
            information.assert_not_called()
            self.assertEqual(set(target.parent.iterdir()), {target})
            if kind == "log":
                self.assertEqual(host.logged, [])

    def test_course_csv_partial_write_failure_preserves_existing_file(self):
        self.assert_failure_preserves_file("course", "write")

    def test_course_csv_replace_failure_preserves_existing_file(self):
        self.assert_failure_preserves_file("course", "replace")

    def test_log_partial_write_failure_preserves_existing_file(self):
        self.assert_failure_preserves_file("log", "write")

    def test_log_replace_failure_preserves_existing_file(self):
        self.assert_failure_preserves_file("log", "replace")

    def test_sync_failure_preserves_existing_export_and_cleans_temporary_file(self):
        for kind in ("course", "log"):
            with self.subTest(kind=kind):
                self.assert_failure_preserves_file(kind, "flush")

    def test_unicode_error_is_shown_and_existing_file_survives(self):
        for kind in ("course", "log"):
            with self.subTest(kind=kind):
                self.assert_failure_preserves_file(kind, "unicode")

    def test_valid_unicode_exports_replace_existing_file_without_temporary_files(self):
        for kind in ("course", "log"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                host = self.make_host(kind)
                self.addCleanup(host.close)
                target = Path(directory) / "导出🙂.txt"
                target.write_bytes(b"old")
                warning, information = self.invoke_export(kind, host, str(target))
                warning.assert_not_called()
                information.assert_called_once()
                contents = target.read_bytes()
                self.assertTrue(contents.startswith(b"\xef\xbb\xbf"))
                self.assertIn("🙂", target.read_text(encoding="utf-8-sig"))
                self.assertEqual(set(target.parent.iterdir()), {target})

    def test_cancelled_save_does_not_write_or_report_success(self):
        for kind in ("course", "log"):
            with self.subTest(kind=kind):
                host = self.make_host(kind)
                self.addCleanup(host.close)
                with patch("tempfile.mkstemp") as create_temporary:
                    warning, information = self.invoke_export(kind, host, "")
                create_temporary.assert_not_called()
                warning.assert_not_called()
                information.assert_not_called()
                if kind == "log":
                    self.assertEqual(host.logged, [])

    def test_csv_formula_like_text_is_neutralized_without_mutating_records(self):
        formula = ' \t=HYPERLINK("https://example.invalid", "课程🙂")\n备注'
        normal = '高等数学, "拔尖"\n班🙂'
        dangerous = sample_record(course_title=formula, detail=formula)
        ordinary = sample_record(course_id="course-2", rollcall_id="2", course_title=normal)
        rows = list(csv.reader(io.StringIO(course_rollcall_csv([dangerous, ordinary]))))

        self.assertEqual(rows[1][1], "'" + formula)
        self.assertEqual(rows[1][12], "'" + formula)
        self.assertEqual(rows[1][5:8], ["7", "12", "58.3%"])
        self.assertEqual(rows[2][1], normal)
        self.assertEqual(dangerous.course_title, formula)
        self.assertEqual(dangerous.detail, formula)

    def test_csv_all_text_fields_neutralize_formula_prefix_after_whitespace(self):
        fields = {
            "course_title": 1,
            "rollcall_time": 2,
            "rollcall_type": 3,
            "signed_status": 4,
            "number_code": 8,
            "platform_status": 9,
            "rollcall_id": 11,
            "detail": 12,
        }
        for prefix in ("=", "+", "-", "@", " \t=", "\r\n+", "\u2003-", "\t@"):
            for field, column in fields.items():
                with self.subTest(prefix=prefix, field=field):
                    value = prefix + "SUM(A1:A2)"
                    record = sample_record(**{field: value})
                    row = list(csv.reader(io.StringIO(course_rollcall_csv([record]))))[1]
                    self.assertEqual(row[column], "'" + value)
                    self.assertEqual((row[5], row[6], row[7]), ("7", "12", "58.3%"))
                    self.assertEqual(getattr(record, field), value)


if __name__ == "__main__":
    unittest.main()
