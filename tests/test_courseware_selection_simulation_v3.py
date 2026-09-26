"""Real Qt selection and course-switch timelines, with offline worker dispatch."""
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QComboBox, QLabel, QLineEdit, QPushButton, QTableWidget, QWidget

from xmu_rollcall.courseware import CourseSummary, CoursewareItem
from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin


def item(name, course="a"):
    return CoursewareItem(
        course_id=course, activity_id=name, activity_title=name, activity_type="material",
        module_name="fixture", syllabus_name="", upload_id=name, reference_id="",
        filename=f"{name}.pdf", size=1, media_type="", published_at="",
        upload_status="ready", allow_download=True, source_url="",
    )


class CoursewareHost(CoursewarePageMixin, QWidget):
    def __init__(self, directory):
        super().__init__()
        self.account = {"id": "fixture"}
        self.session = object()
        self.courses = [CourseSummary("a", "Course A", "2026", "1", ""),
                        CourseSummary("b", "Course B", "2026", "1", "")]
        self.courseware_courses = self.courses
        self.courseware_course_by_display = {course.title: course for course in self.courses}
        self.courseware_combo = QComboBox(self)
        self.courseware_combo.addItems(["请选择课程", "Course A", "Course B"])
        self.courseware_combo.setCurrentIndex(1)
        self.courseware_table = QTableWidget(0, 6, self)
        self.courseware_table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.courseware_download_dir = QLineEdit(str(directory), self)
        self.courseware_summary = QLabel(self)
        self.courseware_items = []
        self.courseware_download_status = {}
        self.courseware_download_in_progress = False
        self.courseware_refresh_in_progress = False
        self.courseware_courses_refresh_in_progress = False
        self.queued = []
        self.logs = []
        self.download_button = QPushButton("下载", self)
        self.download_button.clicked.connect(self.download_selected_courseware)
        self.courseware_combo.currentTextChanged.connect(lambda _: self.refresh_selected_courseware(silent=True))

    def _run_thread(self, target, *args):
        self.queued.append((target, args))

    def _require_login(self, **_):
        return True

    def _ui_palette(self):
        return {"cw_failed": "#ff0000", "cw_ready": "#008800", "cw_downloading": "#999900", "cw_success": "#008800"}

    def _update_nav_badges(self):
        pass

    def _show_toast(self, *_args, **_kwargs):
        pass

    def log(self, text):
        self.logs.append(text)


class CoursewareSelectionSimulationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.host = CoursewareHost(self.temp.name)
        self.addCleanup(self.host.close)
        self.a1, self.a2 = item("first"), item("second")
        self.host._set_courseware_items(self.host.courses[0], [self.a1, self.a2])

    def selected(self):
        return [self.host.courseware_items[row].activity_id for row in self.host._selected_courseware_rows()]

    def test_checked_file_survives_reordered_server_results(self):
        self.host.courseware_table.item(0, 0).setCheckState(Qt.CheckState.Checked)
        self.host._set_courseware_items(self.host.courses[0], [self.a2, self.a1])
        self.assertEqual(["first"], self.selected())
        self.host.download_button.click()
        self.assertEqual([self.a1], self.host.queued[-1][1][0])

    def test_row_selection_survives_reordered_server_results(self):
        self.host.courseware_table.selectRow(0)
        self.host._set_courseware_items(self.host.courses[0], [self.a2, self.a1])
        self.assertEqual(["first"], self.selected())

    def test_removed_checked_and_selected_file_does_not_select_its_replacement(self):
        self.host.courseware_table.item(0, 0).setCheckState(Qt.CheckState.Checked)
        self.host.courseware_table.selectRow(0)
        self.host._set_courseware_items(self.host.courses[0], [self.a2])
        self.assertEqual([], self.selected())

    def test_course_switch_clears_old_download_rows_before_new_request_completes(self):
        self.host.courseware_combo.setCurrentIndex(2)
        self.assertEqual([], self.host.courseware_items)
        self.assertEqual(0, self.host.courseware_table.rowCount())
        self.assertEqual("b", self.host.queued[-1][1][0].course_id)

    def test_switch_while_old_request_runs_retries_new_course_without_exposing_old_rows(self):
        self.host.courseware_refresh_in_progress = True
        self.host.courseware_combo.setCurrentIndex(2)
        self.assertEqual([], self.host.courseware_items)
        self.assertEqual([], self.host.queued)
        DashboardWindow._ev_courseware(self.host, ("courseware", self.host.courses[0], [self.a1], "fixture"))
        self.assertEqual([], self.host.courseware_items)
        self.assertEqual("b", self.host.queued[-1][1][0].course_id)
        b = item("new-course", "b")
        DashboardWindow._ev_courseware(self.host, ("courseware", self.host.courses[1], [b], "fixture"))
        self.assertEqual([b], self.host.courseware_items)

    def test_selecting_placeholder_clears_previously_downloadable_rows(self):
        self.host.courseware_combo.setCurrentIndex(0)
        self.assertEqual([], self.host.courseware_items)
        self.assertEqual(0, self.host.courseware_table.rowCount())

    def test_download_dispatch_rejects_items_from_a_different_selected_course(self):
        self.host.courseware_combo.blockSignals(True)
        self.host.courseware_combo.setCurrentIndex(2)
        self.host.courseware_combo.blockSignals(False)
        with patch("xmu_rollcall.desktop_qt.courseware_page.QMessageBox.warning"):
            self.host._start_courseware_download([self.a1])
        self.assertEqual([], self.host.queued)
        self.assertFalse(self.host.courseware_download_in_progress)

    def test_progress_refresh_keeps_checked_and_current_file(self):
        self.host.courseware_table.item(1, 0).setCheckState(Qt.CheckState.Checked)
        self.host.courseware_table.selectRow(1)
        self.host.courseware_download_status[self.host._courseware_key(self.a2)] = "下载成功"
        self.host._refresh_courseware_table()
        self.assertEqual(["second"], self.selected())
        self.assertEqual(1, self.host.courseware_table.currentRow())
