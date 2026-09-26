"""Real notification worker/Qt-result simulations with only delivery stubbed."""
import os
import sys
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtWidgets import QApplication, QLabel, QWidget
from xmu_rollcall.config import get_notification_settings
from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.core import RollcallEvent
from xmu_rollcall.desktop_qt.notifications_page import NotificationsPageMixin
from xmu_rollcall.notifications import PushPlusNotifier


class NotificationHost(NotificationsPageMixin, QWidget):
    _notify_rollcall = DashboardWindow._notify_rollcall
    _ev_notification_result = DashboardWindow._ev_notification_result

    def __init__(self):
        super().__init__()
        self.session = object()
        self.account = {"id": "synthetic-account"}
        self.notification_summary = QLabel("current summary", self)
        self.queued, self.events, self.logs, self.toasts = [], [], [], []
        self._notification_settings_cache = get_notification_settings({})
        self._notification_settings_cache["system"]["enabled"] = False
        self._notification_settings_cache["pushplus"].update(enabled=True, token="synthetic-token")

    def _run_thread(self, target, *args):
        self.queued.append((target, args))

    def _emit(self, event):
        self.events.append(event)

    def log(self, detail):
        self.logs.append(detail)

    def _show_toast(self, detail, *, ok=True):
        self.toasts.append((detail, ok))

    def queue_rollcall(self):
        event = RollcallEvent("synthetic-event", "虚构课程", "虚构教师", "数字签到", "active", {})
        self._notify_rollcall(event.rollcall_id, event)

    def finish_worker(self):
        target, args = self.queued.pop(0)
        target(*args)
        for event in self.events:
            self._ev_notification_result(event)
        self.events.clear()


class NotificationLifecycleSimulationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        self.host = NotificationHost()
        self.addCleanup(self.host.close)
        # Production config reads are redirected to synthetic settings; the real
        # send_with_settings still performs provider error normalization.
        config_read = patch("xmu_rollcall.desktop_qt.notifications_page.load_config", return_value={})
        self.config_read = config_read.start()
        self.addCleanup(config_read.stop)
        settings_read = patch("xmu_rollcall.desktop_qt.notifications_page.get_notification_settings",
                              return_value=self.host._notification_settings_cache)
        self.settings_read = settings_read.start()
        self.addCleanup(settings_read.stop)
        sender = patch.object(PushPlusNotifier, "send", autospec=True)
        self.sender = sender.start()
        self.addCleanup(sender.stop)

    def test_logout_before_worker_starts_cancels_external_delivery(self):
        self.host.queue_rollcall()
        self.host.session = None
        self.host.finish_worker()
        self.sender.assert_not_called()
        self.assertEqual([], self.host.toasts)

    def test_same_account_relogin_before_worker_starts_cancels_old_delivery(self):
        self.host.queue_rollcall()
        self.host.session = object()
        self.host.finish_worker()
        self.sender.assert_not_called()
        self.assertEqual("current summary", self.host.notification_summary.text())

    def test_same_account_relogin_during_delivery_discards_late_success(self):
        self.host.queue_rollcall()
        self.sender.side_effect = lambda *_: setattr(self.host, "session", object())
        self.host.finish_worker()
        self.sender.assert_called_once()
        self.assertEqual("current summary", self.host.notification_summary.text())
        self.assertEqual([], self.host.toasts)

    def test_account_switch_during_delivery_discards_late_failure(self):
        self.host.queue_rollcall()

        def fail_after_switch(*_):
            self.host.session = object()
            self.host.account = {"id": "synthetic-second-account"}
            raise OSError("synthetic connection lost")

        self.sender.side_effect = fail_after_switch
        self.host.finish_worker()
        self.assertEqual("current summary", self.host.notification_summary.text())
        self.assertEqual([], self.host.toasts)

    def test_logged_out_explicit_test_reports_success(self):
        self.host.session = None
        self.host._send_test_notification(self.host._notification_settings_cache)
        self.host.finish_worker()
        self.sender.assert_called_once()
        self.assertEqual("测试通知已发送", self.host.notification_summary.text())
        self.assertEqual([("测试通知已发送", True)], self.host.toasts)

    def test_logged_out_explicit_test_reports_provider_failure(self):
        self.host.session = None
        self.sender.side_effect = OSError("synthetic connection lost")
        self.host._send_test_notification(self.host._notification_settings_cache)
        self.host.finish_worker()
        self.assertIn("微信通知失败", self.host.notification_summary.text())
        self.assertEqual(1, len(self.host.toasts))
        self.assertFalse(self.host.toasts[0][1])

    def test_current_session_background_result_is_visible(self):
        self.host.queue_rollcall()
        self.host.finish_worker()
        self.sender.assert_called_once()
        self.assertEqual("通知已发送", self.host.notification_summary.text())

    def test_queued_background_uses_settings_chosen_when_event_arrived(self):
        self.host.queue_rollcall()
        # A later settings save points to a different recipient before the
        # original event's queued worker has started.
        later_settings = deepcopy(self.host._notification_settings_cache)
        later_settings["pushplus"]["token"] = "synthetic-new-recipient"
        self.host._notification_settings_cache = later_settings
        self.settings_read.return_value = later_settings
        self.host.finish_worker()
        self.assertEqual("synthetic-token", self.sender.call_args.args[0].token)
