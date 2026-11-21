"""Real Qt course-switch failures with queued, fully offline worker results."""

import os
import sys
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtWidgets import QApplication, QComboBox, QLabel, QLineEdit, QTableWidget, QWidget

from xmu_rollcall.courseware import CourseSummary, CoursewareItem
from xmu_rollcall.desktop_qt import courseware_page
from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin


def _item(course_id):
    return CoursewareItem(
        course_id=course_id, activity_id="file", activity_title="File", activity_type="material",
        module_name="fixture", syllabus_name="", upload_id="file", reference_id="",
        filename="fixture.pdf", size=1, media_type="", published_at="",
        upload_status="ready", allow_download=True, source_url="",
    )


class _CoursewareHost(CoursewarePageMixin, QWidget):
    def __init__(self):
        super().__init__()
        self.session = object()
        self.account = {"id": "fixture"}
        self.courseware_courses = [
            CourseSummary("a", "Course A", "2026", "1", ""),
            CourseSummary("b", "Course B", "2026", "1", ""),
        ]
        self.courseware_course_by_display = {course.title: course for course in self.courseware_courses}
        self.courseware_combo = QComboBox(self)
        self.courseware_combo.addItems(["Choose a course", "Course A", "Course B"])
        self.courseware_combo.setCurrentIndex(1)
        self.courseware_summary = QLabel(self)
        self.courseware_table = QTableWidget(0, 6, self)
        self.courseware_items = []
        self.courseware_download_status = {}
        self.courseware_refresh_in_progress = False
        self.courseware_courses_refresh_in_progress = False
        self.courseware_download_in_progress = False
        self.courseware_download_batch_token = None
        self.courseware_download_dir = QLineEdit("fixture-downloads", self)
        self.queued = []
        self.events = []
        self.toasts = []
        self.retry_errors = []
        self.logs = []
        self.courseware_combo.currentTextChanged.connect(lambda _: self.refresh_selected_courseware(silent=True))

    def _run_thread(self, target, *args):
        self.queued.append((target, args))

    def _emit(self, event):
        self.events.append(event)

    def _require_login(self, **_):
        return True

    def _update_nav_badges(self):
        pass

    def _show_toast(self, *args, **kwargs):
        self.toasts.append((args, kwargs))

    def _show_retry_error(self, *args):
        self.retry_errors.append(args)

    def log(self, text):
        self.logs.append(text)

    def _ui_palette(self):
        return {"cw_failed": "#ff0000", "cw_ready": "#008800", "cw_downloading": "#999900", "cw_success": "#008800"}


class CoursewareSwitchRetryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.host = _CoursewareHost()
        self.addCleanup(self.host.close)
        self.host._set_courseware_items(self.host.courseware_courses[0], [_item("a")])

    def fail_queued_request(self, *, clone_available=True):
        target, args = self.host.queued.pop(0)
        worker = SimpleNamespace(close=Mock()) if clone_available else None
        with patch.object(courseware_page, "clone_session", return_value=worker), patch.object(
            courseware_page, "fetch_courseware", side_effect=RuntimeError("synthetic request failure"),
        ):
            target(*args)
        event = self.host.events.pop(0)
        self.assertEqual(event[0], "courseware_error")
        DashboardWindow._ev_courseware_error(self.host, event)
        return event

    def test_old_failure_loads_new_selected_course_and_accepts_its_result(self):
        self.host.refresh_selected_courseware(silent=True)
        self.host.courseware_combo.setCurrentIndex(2)
        self.assertEqual(self.host.courseware_items, [])
        self.assertEqual(len(self.host.queued), 1)

        self.fail_queued_request()

        self.assertEqual(len(self.host.queued), 1)
        self.assertEqual(self.host.queued[0][1][0].course_id, "b")
        self.assertTrue(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.toasts, [])
        item = _item("b")
        DashboardWindow._ev_courseware(self.host, (
            "courseware", self.host.courseware_courses[1], [item], "fixture", self.host.session,
        ))
        self.assertEqual(self.host.courseware_items, [item])
        self.assertFalse(self.host.courseware_refresh_in_progress)

    def test_clone_failure_also_loads_new_selected_course(self):
        self.host.refresh_selected_courseware(silent=True)
        self.host.courseware_combo.setCurrentIndex(2)

        self.fail_queued_request(clone_available=False)

        self.assertEqual(len(self.host.queued), 1)
        self.assertEqual(self.host.queued[0][1][0].course_id, "b")
        self.assertEqual(self.host.toasts, [])

    def test_old_failure_after_clearing_selection_keeps_selection_prompt(self):
        self.host.refresh_selected_courseware(silent=True)
        self.host.courseware_combo.setCurrentIndex(2)
        self.host.courseware_combo.setCurrentIndex(0)
        expected_summary = self.host.courseware_summary.text()

        self.fail_queued_request()

        self.assertEqual(self.host.courseware_summary.text(), expected_summary)
        self.assertEqual(self.host.queued, [])
        self.assertEqual(self.host.courseware_items, [])
        self.assertFalse(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.toasts, [])

    def test_old_failure_after_account_switch_keeps_new_request_state(self):
        self.host.refresh_selected_courseware(silent=True)
        self.host.courseware_combo.setCurrentIndex(2)
        self.host.account = {"id": "new-account"}
        self.host.session = object()
        self.host.courseware_summary.setText("New account request in progress")

        self.fail_queued_request()

        self.assertEqual(self.host.courseware_summary.text(), "New account request in progress")
        self.assertTrue(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.queued, [])
        self.assertEqual(self.host.toasts, [])

    def test_old_failure_after_same_account_relogin_keeps_new_request_state(self):
        self.host.refresh_selected_courseware(silent=True)
        self.host.session = object()
        self.host.courseware_summary.setText("New session request in progress")

        self.fail_queued_request()

        self.assertEqual(self.host.courseware_summary.text(), "New session request in progress")
        self.assertTrue(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.queued, [])
        self.assertEqual(self.host.toasts, [])

    def test_current_course_failure_reports_once_without_automatic_retry(self):
        for silent in (False, True):
            with self.subTest(silent=silent):
                self.host.toasts.clear()
                self.host.retry_errors.clear()
                self.host.refresh_selected_courseware(silent=silent)

                self.fail_queued_request()

                self.assertEqual(self.host.queued, [])
                self.assertFalse(self.host.courseware_refresh_in_progress)
                self.assertEqual(self.host.courseware_summary.text(), "课件读取失败")
                self.assertEqual(len(self.host.toasts), 1)
                self.assertEqual(len(self.host.retry_errors), 0 if silent else 1)

    def test_legacy_failure_keeps_existing_manual_retry_behavior(self):
        self.host.courseware_refresh_in_progress = True

        DashboardWindow._ev_courseware_error(self.host, ("courseware_error", "synthetic failure", False, "fixture"))

        self.assertFalse(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.queued, [])
        self.assertEqual(len(self.host.retry_errors), 1)


class CoursewareDispatchFailureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.host = _CoursewareHost()
        self.addCleanup(self.host.close)
        self.first = _item("a")
        self.second = replace(self.first, activity_id="second", filename="second.pdf")
        self.host._set_courseware_items(self.host.courseware_courses[0], [self.first, self.second])
        self.host._show_courseware_download_result = Mock()

    def fail_dispatch(self, action, on_start=None):
        def start_failure():
            if on_start is not None:
                on_start()
            raise RuntimeError("synthetic thread limit")

        # Use the production dispatcher: its Thread.start exception must reach
        # the entry point, which owns the already-set loading/status fields.
        with patch.object(self.host, "_run_thread", DashboardWindow._run_thread.__get__(self.host)), patch(
            "xmu_rollcall.desktop_qt.app.threading.Thread.start", side_effect=start_failure,
        ):
            action()

    def deliver_events(self):
        while self.host.events:
            event = self.host.events.pop(0)
            DashboardWindow._EVENT_HANDLERS[event[0]](self.host, event)

    def test_course_list_dispatch_failure_uses_error_event_and_allows_manual_retry(self):
        source = self.host.session
        self.fail_dispatch(self.host.refresh_courseware_courses)
        self.assertEqual(self.host.events[0][0], "courseware_courses_error")
        self.assertEqual(self.host.events[0][3], "fixture")
        self.assertIs(self.host.events[0][-1], source)

        self.deliver_events()

        self.assertFalse(self.host.courseware_courses_refresh_in_progress)
        self.assertEqual(self.host.courseware_summary.text(), "课程列表读取失败")
        self.assertEqual(len(self.host.retry_errors), 1)
        self.host.retry_errors[0][2]()
        self.assertEqual(len(self.host.queued), 1)
        self.assertTrue(self.host.courseware_courses_refresh_in_progress)

    def test_detail_dispatch_failure_uses_course_session_error_and_allows_manual_retry(self):
        source = self.host.session
        self.fail_dispatch(self.host.refresh_selected_courseware)
        self.assertEqual(self.host.events[0][0], "courseware_error")
        self.assertEqual(self.host.events[0][3:5], ("fixture", "a"))
        self.assertIs(self.host.events[0][-1], source)

        self.deliver_events()

        self.assertFalse(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.courseware_summary.text(), "课件读取失败")
        self.assertEqual(len(self.host.retry_errors), 1)
        self.host.retry_errors[0][2]()
        self.assertEqual(len(self.host.queued), 1)
        self.assertTrue(self.host.courseware_refresh_in_progress)

    def test_download_dispatch_failure_marks_every_target_and_releases_batch_for_retry(self):
        items = [self.first, self.second]
        self.fail_dispatch(lambda: self.host._start_courseware_download(items))
        token = self.host.courseware_download_batch_token
        self.assertIsNotNone(token)
        self.assertTrue(all(event[1] is token for event in self.host.events))
        self.assertTrue(all(event[2] == "fixture" for event in self.host.events))

        self.deliver_events()

        self.assertFalse(self.host.courseware_download_in_progress)
        self.assertIsNone(self.host.courseware_download_batch_token)
        for item in items:
            self.assertTrue(self.host.courseware_download_status[self.host._courseware_key(item)].startswith("下载失败"))
        self.assertIn("失败 2 个", self.host.courseware_summary.text())
        self.assertEqual(len(self.host._show_courseware_download_result.call_args.args[2]), 2)
        self.host._start_courseware_download(items)
        self.assertEqual(len(self.host.queued), 1)
        self.assertTrue(self.host.courseware_download_in_progress)
        self.assertIsNot(self.host.courseware_download_batch_token, token)

    def test_late_course_list_dispatch_error_does_not_clear_new_session_request(self):
        source = self.host.session

        def relogin():
            self.host.session = object()
            self.host.courseware_summary.setText("New session course request")

        self.fail_dispatch(self.host.refresh_courseware_courses, relogin)
        self.assertIs(self.host.events[0][-1], source)

        self.deliver_events()

        self.assertTrue(self.host.courseware_courses_refresh_in_progress)
        self.assertEqual(self.host.courseware_summary.text(), "New session course request")
        self.assertEqual(self.host.retry_errors, [])

    def test_late_detail_dispatch_error_does_not_clear_new_account_request(self):
        source = self.host.session

        def switch_account():
            self.host.session = object()
            self.host.account = {"id": "new-account"}
            self.host.courseware_summary.setText("New account detail request")

        self.fail_dispatch(self.host.refresh_selected_courseware, switch_account)
        self.assertEqual(self.host.events[0][3], "fixture")
        self.assertIs(self.host.events[0][-1], source)

        self.deliver_events()

        self.assertTrue(self.host.courseware_refresh_in_progress)
        self.assertEqual(self.host.courseware_summary.text(), "New account detail request")
        self.assertEqual(self.host.retry_errors, [])

    def test_late_download_dispatch_failure_does_not_overwrite_new_batch(self):
        new_token = object()
        key = self.host._courseware_key(self.first)

        def replace_batch():
            self.host.session = object()
            self.host.account = {"id": "new-account"}
            self.host.courseware_download_batch_token = new_token
            self.host.courseware_download_status = {key: "下载中"}
            self.host.courseware_summary.setText("New account download")

        self.fail_dispatch(lambda: self.host._start_courseware_download([self.first]), replace_batch)
        self.assertTrue(all(event[1] is not new_token for event in self.host.events))
        self.assertTrue(all(event[2] == "fixture" for event in self.host.events))

        self.deliver_events()

        self.assertTrue(self.host.courseware_download_in_progress)
        self.assertIs(self.host.courseware_download_batch_token, new_token)
        self.assertEqual(self.host.courseware_download_status, {key: "下载中"})
        self.assertEqual(self.host.courseware_summary.text(), "New account download")
        self.host._show_courseware_download_result.assert_not_called()
