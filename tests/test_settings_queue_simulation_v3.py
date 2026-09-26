"""Offline Qt save-click/close sequences through the real settings worker."""
import os
import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from PySide6.QtWidgets import QApplication, QCheckBox, QLabel, QLineEdit, QPushButton, QWidget
from xmu_rollcall import config
from xmu_rollcall.desktop_qt.notifications_page import NotificationsPageMixin
from xmu_rollcall.desktop_qt.tray import TrayMixin


class SettingsHost(NotificationsPageMixin, TrayMixin, QWidget):
    def __init__(self):
        super().__init__()
        self.quitting = False
        self.tray_icon = Mock()
        for name in ("notify_system_check", "notify_pushplus_check", "notify_qq_check"):
            setattr(self, name, QCheckBox(self))
        for name in ("notify_pushplus_token", "notify_qq_sender", "notify_qq_password",
                     "notify_qq_recipient", "notify_qq_host", "notify_qq_port"):
            setattr(self, name, QLineEdit(self))
        self.notification_summary = QLabel(self)
        self.metric_notifications = QLabel(self)
        self.queued, self.events, self.logs = [], [], []
        self.sent = []
        self.save_button = QPushButton("保存", self)
        self.save_button.clicked.connect(self.save_notification_settings)
        self.test_button = QPushButton("测试", self)
        self.test_button.clicked.connect(self.test_notifications)

    def _run_thread(self, target, *args):
        self.queued.append((target, args))

    def _emit(self, event):
        self.events.append(event)

    def _update_nav_badges(self):
        pass

    def _show_toast(self, *_args, **_kwargs):
        pass

    def _send_test_notification(self, settings):
        self.sent.append(settings)

    def log(self, message):
        self.logs.append(message)

    def finish_next_save(self):
        target, args = self.queued.pop(0)
        target(*args)
        self._ev_notification_settings_saved(self.events.pop(0))


class SettingsQueueSimulationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.stack.enter_context(patch.object(config, "CONFIG_DIR", self.directory))
        self.stack.enter_context(patch.object(config, "CONFIG_FILE", self.directory / "config.json"))
        self.stack.enter_context(patch.object(config.secrets, "protect", side_effect=lambda value: value))
        self.stack.enter_context(patch.object(config.secrets, "unprotect", side_effect=lambda value: value))
        self.stack.enter_context(patch("xmu_rollcall.desktop_qt.notifications_page.QMessageBox.critical"))
        config.save_config({"accounts": [], "current_account_id": None})
        self.host = SettingsHost()
        self.addCleanup(self.dispose)

    def dispose(self):
        self.host.quitting = True
        self.host.close()

    def test_file_lock_failure_keeps_later_save_and_test_in_click_order(self):
        self.host.notify_pushplus_token.setText("synthetic-first")
        self.host.test_button.click()
        self.host.notify_pushplus_token.setText("synthetic-latest")
        self.host.test_button.click()
        self.assertEqual(1, len(self.host.queued))
        with patch.object(config.os, "replace", side_effect=PermissionError("simulated config lock")):
            self.host.finish_next_save()
        self.assertEqual([], self.host.sent)
        self.assertEqual(1, len(self.host.queued))
        changed = config.load_config()
        changed["current_account_id"] = "synthetic-new-account"
        config.save_config(changed)
        self.host.finish_next_save()
        saved = config.load_config()
        self.assertEqual("synthetic-latest", saved["notification_settings"]["pushplus"]["token"])
        self.assertEqual("synthetic-new-account", saved["current_account_id"])
        self.assertEqual(1, len(self.host.sent))
        self.assertEqual("synthetic-latest", self.host.sent[0]["pushplus"]["token"])
        self.assertEqual([], self.host._notification_save_pending)

    def test_close_to_tray_keeps_queued_settings_save_alive(self):
        self.host.show()
        self.host.notify_pushplus_token.setText("synthetic-hidden-window")
        self.host.save_button.click()
        self.assertFalse(self.host.close(), "tray close must ignore application shutdown")
        self.assertFalse(self.host.isVisible())
        self.assertEqual(1, len(self.host.queued))
        self.host.finish_next_save()
        self.assertEqual("synthetic-hidden-window", config.load_config()["notification_settings"]["pushplus"]["token"])
        self.assertEqual([], self.host._notification_save_pending)
        self.assertEqual([], self.host.sent)
