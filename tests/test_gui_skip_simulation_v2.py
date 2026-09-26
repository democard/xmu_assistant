"""Qt selection/buttons -> real event handlers and answer worker, with fake PUT only."""
from __future__ import annotations

import json
import os
import sys
import threading
import types
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

import requests
from PySide6.QtWidgets import QApplication, QCheckBox, QLabel, QPushButton
from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.theme import palette
from xmu_rollcall.rollcall_models import RollcallEvent
from xmu_rollcall.rollcall_progress import RollcallProgress


class OfflineAnswerSession:
    headers = {}

    def __init__(self):
        self.puts = []

    def put(self, url, **kwargs):
        self.puts.append((url, kwargs["json"]))
        response = requests.Response()
        response.status_code = 200
        response.url = url
        response.headers["Content-Type"] = "application/json"
        response._content = json.dumps({}).encode()
        return response


class GuiSkipSimulationV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        event = RollcallEvent("r1", "Course", "Teacher", "数字签到", "unsigned", {}, number_code="0042")
        host = self.host = types.SimpleNamespace(
            session=OfflineAnswerSession(), account={"id": "a", "username": "me", "rollcall_settings": {"wait_before_answer_mode": "none"}},
            monitor_stop_event=threading.Event(), monitor_worker=None,
            _auto_answer_epoch=0, _auto_answer_enabled=True,
            _auto_answer_inflight_rollcalls={}, _auto_answer_attempted_rollcalls=set(),
            _answer_cancellations={}, _answer_cancellations_lock=threading.Lock(),
            events_by_id={"event-1": event}, event_order=["event-1"],
            metric_last_result=QLabel(), auto_answer_check=QCheckBox(),
            queued=[], emitted=[], notifications=[], log=lambda *args: None,
            _ui_palette=lambda: palette("light"),
        )
        methods = (
            "_set_table_rows", "_style_status_item", "_event_values", "_event_status_text", "_refresh_event_tables",
            "_update_event_result", "_selected_event_id", "skip_selected_rollcall", "answer_selected_rollcall",
            "_answer_event", "_answer_worker", "_cancel_pending_answer", "_validate_auto_submission",
            "_ev_rollcall_progress", "_ev_answer_result",
        )
        for name in methods:
            setattr(host, name, types.MethodType(getattr(DashboardWindow, name), host))
        host._run_thread = lambda target, *args: host.queued.append((target, args))
        host._auto_answer_delay = lambda event, auto: 0
        host._emit = host.emitted.append
        host._notify_rollcall = lambda *args: host.notifications.append(args)
        host.auto_answer_check.setChecked(True)
        host.events_table = DashboardWindow._make_table(host, tuple(str(i) for i in range(7)), (80,) * 7)
        host._refresh_event_tables()
        host.events_table.setCurrentCell(0, 0)
        self.skip_button, self.answer_button = QPushButton("Skip"), QPushButton("Answer")
        self.skip_button.clicked.connect(host.skip_selected_rollcall)
        self.answer_button.clicked.connect(host.answer_selected_rollcall)
        for widget in (host.events_table, host.metric_last_result, host.auto_answer_check, self.skip_button, self.answer_button):
            self.addCleanup(widget.deleteLater)

    def progress(self, *, ambiguous=False):
        value = RollcallProgress(observed=2, present=1, absent=1, roster_complete=True, own_present=False,
                                 own_status_ambiguous=ambiguous)
        self.host._ev_rollcall_progress(("rollcall_progress", "r1", value, "0042", self.host.monitor_stop_event))

    def drain_results(self):
        events, self.host.emitted = self.host.emitted[:], []
        self.host._emit = self.host.emitted.append
        for event in events:
            if event[0] == "answer_result":
                self.host._ev_answer_result(event)

    def run_queued(self):
        target, args = self.host.queued.pop(0)
        target(*args)
        self.drain_results()

    def test_skip_before_threshold_update_prevents_later_automatic_dispatch(self):
        self.skip_button.click()
        self.progress()
        self.progress()
        self.assertEqual(self.host.queued, [])
        self.assertEqual(self.host.events_table.item(0, 4).text(), "跳过")
        self.assertEqual(self.host.session.puts, [])

    def test_skip_after_dispatch_before_worker_starts_prevents_submission(self):
        self.progress()
        self.assertEqual(len(self.host.queued), 1)
        self.skip_button.click()
        self.run_queued()
        self.assertEqual(self.host.session.puts, [])
        self.assertEqual(self.host.events_by_id["event-1"].result, "已跳过")
        self.assertEqual(self.host.notifications, [])

    def test_skip_during_real_delay_keeps_skip_after_cancel_result_delivery(self):
        self.host._auto_answer_delay = lambda event, auto: 5 if auto else 0
        self.progress()
        target, args = self.host.queued.pop(0)
        worker = threading.Thread(target=target, args=args)
        worker.start()
        try:
            for _ in range(100):
                with self.host._answer_cancellations_lock:
                    if "event-1" in self.host._answer_cancellations:
                        break
                threading.Event().wait(0.005)
            else:
                self.fail("real answer worker did not register its cancelable delay")
            self.skip_button.click()
        finally:
            self.host._cancel_pending_answer("event-1")
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.drain_results()
        self.assertEqual(self.host.events_by_id["event-1"].result, "已跳过")
        self.assertEqual(self.host.session.puts, [])
        self.progress()
        self.assertEqual(self.host.queued, [])

    def test_manual_button_can_submit_skipped_event_without_rearming_automatic_work(self):
        self.skip_button.click()
        self.answer_button.click()
        self.run_queued()
        self.assertEqual(len(self.host.session.puts), 1)
        self.assertEqual(self.host.session.puts[0][1]["numberCode"], "0042")
        self.assertEqual(self.host.events_by_id["event-1"].result, "已签到")
        self.progress()
        self.assertEqual(self.host.queued, [])

    def test_ambiguous_next_progress_does_not_erase_explicit_skip(self):
        self.skip_button.click()
        self.progress(ambiguous=True)
        self.assertEqual(self.host.events_by_id["event-1"].result, "已跳过")
        self.assertEqual(self.host.events_by_id["event-1"].detail, "用户手动跳过")
        self.assertEqual(self.host.queued, [])


if __name__ == "__main__":
    unittest.main()
