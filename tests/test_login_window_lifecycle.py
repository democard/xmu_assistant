"""Offline window transitions own and release only their own sessions."""
import tempfile
import gc
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
