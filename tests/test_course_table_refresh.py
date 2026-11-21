"""Exercise course-table selection changes with real Qt widgets and no services."""

import os
import sys
import unittest
from dataclasses import replace
from datetime import date
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QLabel,
    QPushButton,
    QTableWidget,
    QWidget,
)

from xmu_rollcall.desktop_qt.core import CourseRollcallRecord
from xmu_rollcall.desktop_qt.courses_page import CoursesPageMixin


class _CoursesHost(CoursesPageMixin, QWidget):
    def __init__(self):
        super().__init__()
        self.course_table = QTableWidget(0, 8, self)
        self.course_summary = QLabel(self)
        self.time_range_combo = QComboBox(self)
        self.time_range_combo.addItems(["本学期", "今天", "本周"])
        self.only_unsigned_check = QCheckBox(self)
        self.verify_selected_button = QPushButton(self)
        self.verify_selected_button.setEnabled(False)
        self.course_verify_in_progress = False
        self.course_refresh_in_progress = False
        self.course_records = [
            CourseRollcallRecord(
                "course", "课程", key, f"{date.today().isoformat()} 08:00:00",
                "数字签到", status, "closed",
            )
            for key, status in (("signed", "已签到"), ("unsigned", "未签到"))
        ]
        self.course_table.itemSelectionChanged.connect(self._update_verify_button_state)
        self.time_range_combo.currentIndexChanged.connect(self._refresh_course_table)
        self.only_unsigned_check.stateChanged.connect(self._refresh_course_table)
        self._refresh_course_table()

    def _ui_palette(self):
        return {"group_row_fg": "#333333", "group_row_bg": "#eeeeee"}

    def _style_status_item(self, *_):
        pass


class CourseTableRefreshTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.host = _CoursesHost()
        self.addCleanup(self.host.close)

    def select_record(self, rollcall_id):
        row = self.host._course_row_of_rollcall_id(rollcall_id)
        self.assertGreaterEqual(row, 0)
        self.host.course_table.selectRow(row)
        self.assertEqual(self.host._selected_course_rollcall_id(), rollcall_id)
        self.assertTrue(self.host.verify_selected_button.isEnabled())

    def test_unsigned_filter_disables_verify_after_selected_record_removed(self):
        self.select_record("signed")

        self.host.only_unsigned_check.setChecked(True)

        self.assertEqual(self.host._selected_course_rollcall_id(), "")
        self.assertFalse(self.host.verify_selected_button.isEnabled())
        self.assertGreaterEqual(self.host._course_row_of_rollcall_id("unsigned"), 0)

    def test_verification_filters_selected_record_and_disables_verify(self):
        unsigned = self.host.course_records[1]
        self.host.course_records.append(replace(unsigned, rollcall_id="remaining"))
        self.host.only_unsigned_check.setChecked(True)
        self.select_record("unsigned")

        changed = self.host._apply_verified_records([
            replace(unsigned, signed_status="已签到", verified=True),
        ])

        self.assertEqual(changed, 1)
        self.assertEqual(self.host._course_row_of_rollcall_id("unsigned"), -1)
        self.assertEqual(self.host._selected_course_rollcall_id(), "")
        self.assertFalse(self.host.verify_selected_button.isEnabled())

    def test_refresh_restores_original_signal_and_update_states(self):
        table = self.host.course_table
        for signals_blocked in (False, True):
            for updates_enabled in (False, True):
                with self.subTest(signals_blocked=signals_blocked, updates_enabled=updates_enabled):
                    table.blockSignals(False)
                    self.select_record("unsigned")
                    table.blockSignals(signals_blocked)
                    table.setUpdatesEnabled(updates_enabled)

                    self.host._refresh_course_table()

                    self.assertEqual(table.signalsBlocked(), signals_blocked)
                    self.assertEqual(table.updatesEnabled(), updates_enabled)
                    self.assertFalse(self.host.verify_selected_button.isEnabled())

    def test_verification_preserves_valid_selection_and_verify_button(self):
        self.host.only_unsigned_check.setChecked(True)
        self.select_record("unsigned")
        signed = self.host.course_records[0]

        self.host._apply_verified_records([replace(signed, verified=True)])

        self.assertEqual(self.host._selected_course_rollcall_id(), "unsigned")
        self.assertTrue(self.host.verify_selected_button.isEnabled())
