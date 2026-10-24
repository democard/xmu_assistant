"""Offline window transitions own and release only their own sessions."""
import tempfile
import gc
import threading
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest import mock

import requests
from PySide6.QtCore import QEvent
from PySide6.QtWidgets import QApplication, QMessageBox
from xmu_rollcall import config
from xmu_rollcall.desktop_qt import app


class LoginWindowLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        # Earlier widget tests defer native deletion. Flush them before the
        # complete window reapplies the application-wide stylesheet.
        QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        gc.collect()
        QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.stack.enter_context(mock.patch.object(config, "CONFIG_DIR", directory))
        self.stack.enter_context(mock.patch.object(config, "CONFIG_FILE", directory / "config.json"))
        self.stack.enter_context(mock.patch.object(app.DashboardWindow, "_setup_tray"))
        self.stack.enter_context(mock.patch.object(app.DashboardWindow, "_run_thread"))
        self.stack.enter_context(mock.patch.object(app.DashboardWindow, "_refresh_after_login"))
        self.stack.enter_context(mock.patch.object(app.QTimer, "singleShot"))
        self.window = app.DashboardWindow()
        self.addCleanup(self.dispose_window)

    def dispose_window(self):
        if self.window.session is not None:
            self.window.session.close()
        self.window.runtime_timer.stop()
        self.window.deleteLater()
        QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)

    def new_session(self):
        session = requests.Session()
        session.close = mock.Mock(wraps=session.close)
        self.addCleanup(session.close)
        return session

    def test_switching_account_releases_previous_session_only(self):
        old, new = self.new_session(), self.new_session()
        self.window.session = old
        self.window.account = {"id": 1, "username": "fixture-old"}
        self.window._ev_login_success((
            "login_success", new, {"id": 2, "username": "fixture-new"}, self.window._login_epoch,
        ))
        self.assertIs(new, self.window.session)
        old.close.assert_called_once()
        new.close.assert_not_called()

    def test_same_account_login_entry_reopens_query_gates_but_keeps_skip_decisions(self):
        old, new = self.new_session(), self.new_session()
        self.window.session = old
        self.window.account = {"id": 1, "username": "fixture"}
        self.window.events_by_id = {}
        self.window.course_refresh_in_progress = True
        self.window.course_verify_in_progress = True
        self.window.courseware_courses_refresh_in_progress = True
        self.window.courseware_refresh_in_progress = True
        self.window._auto_answer_inflight_rollcalls["in-flight"] = object()
        self.window._auto_answer_attempted_rollcalls.update({"explicit-skip", "already-answered"})
        cancel = threading.Event()
        task_token = object()
        key = (id(old), "in-flight")
        self.window._answer_tasks[key] = {
            "token": task_token, "phase": "waiting", "cancel": cancel,
            "event_id": "event-1", "auto": True,
        }
        self.window._answer_cancellations["event-1"] = cancel

        self.window._ev_login_success((
            "login_success", new, {"id": 1, "username": "fixture"}, self.window._login_epoch,
        ))

        self.assertIs(self.window.session, new)
        self.assertTrue(cancel.is_set())
        self.assertEqual(self.window._answer_tasks, {})
        self.assertEqual(self.window._answer_cancellations, {})
        self.assertEqual(self.window._auto_answer_inflight_rollcalls, {})
        for flag in (
            "course_refresh_in_progress", "course_verify_in_progress",
            "courseware_courses_refresh_in_progress", "courseware_refresh_in_progress",
        ):
            self.assertFalse(getattr(self.window, flag), flag)
        self.assertEqual(
            self.window._auto_answer_attempted_rollcalls,
            {"explicit-skip", "already-answered"},
        )
        old.close.assert_called_once()

    def test_logout_releases_previous_session(self):
        session = self.new_session()
        self.window.session = session
        self.window.account = {"id": 1, "username": "fixture"}
        with mock.patch.object(QMessageBox, "question", return_value=QMessageBox.StandardButton.Yes):
            self.window.logout()
        self.assertIsNone(self.window.session)
        session.close.assert_called_once()

    def test_failed_persistence_worker_start_releases_login_gate_and_candidate(self):
        candidate = self.new_session()
        self.window._login_in_progress = True
        with mock.patch.object(self.window, "_run_thread", side_effect=RuntimeError("synthetic thread limit")), \
             mock.patch.object(QMessageBox, "critical"):
            self.window._ev_login_success((
                "login_success", candidate, None, self.window._login_epoch,
                "fixture-user", "fixture-password", "Fixture",
            ))
        self.assertFalse(self.window._login_in_progress)
        self.assertFalse(self.window._login_persisting)
        self.assertIsNone(self.window.session)
        candidate.close.assert_called_once()

    def seed_login(self):
        self.window.session = self.new_session()
        self.window.account = {"id": 1, "username": "fixture"}
        self.window._set_login_status("已登录", warn=False)

    def seed_radar_course(self):
        from xmu_rollcall.courseware import CourseSummary
        from xmu_rollcall.radar_coordinates import RadarCoordinateStore
        directory = config.CONFIG_DIR
        self.window.radar_coordinates = RadarCoordinateStore(directory / "places.json")
        self.window.radar_coordinates.add("fixture-course", "Fixture", "Fixture place", 24.0, 118.0)
        self.window.courseware_courses = [CourseSummary("fixture-course", "Fixture")]
        self.window.refresh_radar_course_options()
        self.assertEqual(self.window.radar_course_combo.count(), 1)
        return directory / "places.json"

    def test_success_clears_password_and_switch_clears_radar_ui_without_deleting_places(self):
        self.seed_login()
        path = self.seed_radar_course()
        saved = path.read_bytes()
        self.window.password_input.setText("synthetic-password")
        self.window._snapshot_account_id = ""  # Active account still determines ownership.
        self.window._ev_login_success(("login_success", self.new_session(), {"id": 2}, self.window._login_epoch))
        self.assertEqual(self.window.password_input.text(), "")
        self.assertEqual(self.window.radar_course_combo.count(), 0)
        self.assertEqual(self.window.radar_place_list.item(0).text(), "先刷新课程，再选择课程")
        self.assertEqual(path.read_bytes(), saved)

    def test_logout_clears_radar_ui_without_deleting_saved_places(self):
        self.seed_login()
        path = self.seed_radar_course()
        saved = path.read_bytes()
        with mock.patch.object(QMessageBox, "question", return_value=QMessageBox.StandardButton.Yes):
            self.window.logout()
        self.assertEqual(self.window.radar_course_combo.count(), 0)
        self.assertEqual(self.window.radar_place_list.item(0).text(), "先刷新课程，再选择课程")
        self.assertEqual(path.read_bytes(), saved)

    def test_terminal_monitor_status_updates_badge_tray_and_runtime_before_thread_exits(self):
        import threading
        self.seed_login()
        self.window.monitor_stop_event = threading.Event()
        self.window.monitor_worker = mock.Mock(is_alive=mock.Mock(return_value=True))
        self.window.started_at = 1
        self.window.tray_status_action = mock.Mock()
        self.window.tray_last_check_action = mock.Mock()
        self.window.tray_toggle_monitor_action = mock.Mock()
        self.window._refresh_guard_status()
        self.assertEqual(self.window.guard_status_badge.text(), "正在守护")
        self.window._ev_monitor_status(("monitor_status", "已停止", self.window.monitor_stop_event))
        self.assertEqual(self.window.metric_monitor.text(), "已停止")
        self.assertEqual(self.window.guard_status_badge.text(), "已暂停")
        self.window.tray_status_action.setText.assert_called_with("状态：已暂停")
        self.assertIsNone(self.window.started_at)
        self.assertTrue(self.window.monitor_stop_event.is_set())

    def test_expired_error_invalidates_session_and_pending_login_and_clears_ui(self):
        self.seed_login()
        self.seed_radar_course()
        old_session = self.window.session
        epoch = self.window._login_epoch
        with mock.patch.object(self.window, "_record_background_error") as notify, \
             mock.patch.object(QMessageBox, "question") as question:
            self.window._ev_error(("error", "登录已过期，请重新登录", None, old_session))
        notify.assert_called_once_with("登录已过期，请重新登录", immediate=True)
        question.assert_not_called()
        self.assertIsNone(self.window.session)
        self.assertIsNone(self.window.account)
        self.assertEqual(self.window._login_epoch, epoch + 1)
        self.assertIn("登录已过期", self.window.top_status.text())
        self.assertEqual(self.window.guard_status_badge.text(), "未登录")
        self.assertEqual(self.window.radar_course_combo.count(), 0)
        old_session.close.assert_called_once()

    def test_refresh_gate_remains_held_until_phase_two_finishes(self):
        self.seed_login()
        self.window.course_refresh_in_progress = True
        with mock.patch.object(self.window, "_show_toast"):
            self.window._ev_course_rollcalls(("course_rollcalls", [], "fixture", "1"))
        self.assertTrue(self.window.course_refresh_in_progress)
        self.window._verify_selected_rollcall()
        self.window._run_thread.assert_not_called()
        self.window._ev_course_records_verified(("course_records_verified", [], "1", "auto"))
        self.assertFalse(self.window.course_refresh_in_progress)

    def test_manual_verification_blocks_full_refresh(self):
        self.seed_login()
        self.window.course_verify_in_progress = True
        self.window.refresh_course_rollcalls()
        self.window._run_thread.assert_not_called()

    def test_repeated_login_gives_visible_feedback(self):
        self.window.username_input.setText("synthetic")
        self.window.password_input.setText("synthetic-password")
        self.window._login_in_progress = True
        with mock.patch.object(self.window, "_show_toast") as toast:
            self.window.login()
        toast.assert_called_once_with("正在登录，请稍候")
        self.window._run_thread.assert_not_called()

    def test_cancel_download_invalidates_batch_and_drops_late_progress(self):
        self.seed_login()
        token = self.window.courseware_download_batch_token = object()
        self.window.courseware_download_in_progress = True
        self.window.courseware_download_status = {"a": "下载中", "b": "下载成功"}
        self.window.cancel_courseware_download()
        self.assertIsNone(self.window.courseware_download_batch_token)
        self.assertFalse(self.window.courseware_download_in_progress)
        self.assertEqual(self.window.courseware_download_status, {"a": "已取消", "b": "下载成功"})
        message = self.window.courseware_summary.text()
        self.assertEqual(message, "已取消下载；已完成文件保留，可重新下载")
        self.window._ev_courseware_download_progress(("courseware_download_progress", token, "1", 1, 1, "a", "a", 100, 100))
        self.assertEqual(self.window.courseware_summary.text(), message)
        self.assertEqual(self.window.courseware_download_status["a"], "已取消")

    def test_download_byte_progress_displays_received_total_and_percentage(self):
        self.seed_login()
        token = self.window.courseware_download_batch_token = object()
        self.window._ev_courseware_download_progress(("courseware_download_progress", token, "1", 1, 1, "fixture.pdf", "a", 1048576, 2097152))
        self.assertIn("1.0 MB / 2.0 MB（50%）", self.window.courseware_summary.text())

    def test_tray_quit_hides_icon(self):
        self.window.tray_icon = mock.Mock()
        with mock.patch.object(app.QApplication, "quit") as quit_app:
            self.window._quit_from_tray()
        self.window.tray_icon.hide.assert_called_once()
        quit_app.assert_called_once()
