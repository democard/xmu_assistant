"""Production answer gate regressions: no real Qt dialogs, accounts, or network."""
import gc
import threading
import types
import unittest
from contextlib import ExitStack
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

import requests
from PySide6.QtCore import QEvent
from PySide6.QtWidgets import QApplication
from xmu_rollcall import config
from xmu_rollcall.desktop_qt import app
from xmu_rollcall.rollcall_models import RollcallEvent


class AnswerTaskGateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = QApplication.instance() or QApplication([])

    def setUp(self):
        QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        gc.collect()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        path = Path(self.temp.name)
        self.stack.enter_context(patch.object(config, "CONFIG_DIR", path))
        self.stack.enter_context(patch.object(config, "CONFIG_FILE", path / "config.json"))
        self.stack.enter_context(patch.object(app.DashboardWindow, "_setup_tray"))
        self.stack.enter_context(patch.object(app.DashboardWindow, "_refresh_after_login"))
        self.stack.enter_context(patch.object(app.QTimer, "singleShot"))
        self.window = app.DashboardWindow()
        self.addCleanup(self.dispose)
        self.source = requests.Session()
        self.addCleanup(self.source.close)
        self.window.session = self.source
        self.window.account = {"id": "fixture", "username": "fixture", "rollcall_settings": {}}
        self.event = RollcallEvent(
            "fixture-rollcall", "Fixture", "Teacher", "数字签到", "unsigned", {},
            remaining_seconds=600, number_code="0042",
        )
        self.window.events_by_id = {"event-1": self.event}
        self.window.event_order = ["event-1"]
        self.window._show_toast = Mock()
        self.window._auto_answer_delay = lambda _event, auto: 5 if auto else 0
        self.events = []
        self.window._emit = self.events.append
        self.dispatched = []
        self.stack.enter_context(patch.object(
            self.window, "_run_thread", side_effect=lambda target, *args: self.dispatched.append((target, args))
        ))

    def dispose(self):
        if self.window.session is not None:
            self.window.session.close()
        self.window.runtime_timer.stop()
        self.window.deleteLater()
        QApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)

    def _consume_answers(self):
        results = [event for event in self.events if event[0] == "answer_result"]
        for result in results:
            app.DashboardWindow._ev_answer_result(self.window, result)
        return results

    def test_double_manual_dispatch_uses_one_session_rollcall_gate(self):
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        first_token = self.window._answer_tasks[(id(self.source), self.event.rollcall_id)]["token"]
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        self.assertEqual(len(self.dispatched), 1)
        self.assertIs(self.window._answer_tasks[(id(self.source), self.event.rollcall_id)]["token"], first_token)
        self.window._show_toast.assert_called_once()

    def test_manual_takes_over_delayed_auto_and_old_result_cannot_overwrite(self):
        auto_token = object()
        self.window._auto_answer_inflight_rollcalls[self.event.rollcall_id] = auto_token
        app.DashboardWindow._answer_event(
            self.window, "event-1", self.event, auto=True,
            auto_snapshot={"task_token": auto_token, "number_code": "0042"},
        )
        auto_target, auto_args = self.dispatched[0]
        auto_cancel = auto_args[-1]

        app.DashboardWindow._answer_event(self.window, "event-1", self.event, auto=False)
        self.assertTrue(auto_cancel.is_set())
        manual_target, manual_args = self.dispatched[1]
        manual_token = manual_args[-2]
        self.assertIsNot(manual_token, auto_token)
        self.assertIs(self.window._answer_tasks[(id(self.source), self.event.rollcall_id)]["token"], manual_token)

        worker_session = requests.Session()
        self.addCleanup(worker_session.close)
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=worker_session), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            engine.return_value.answer.return_value = True
            auto_target(*auto_args)
            auto_results = [event for event in self.events if event[0] == "answer_result"]
            self.assertEqual(auto_results[-1][6], auto_token)
            app.DashboardWindow._ev_answer_result(self.window, auto_results[-1])
            engine.return_value.answer.assert_not_called()
            manual_target(*manual_args)
            engine.return_value.answer.assert_called_once_with("数字签到", "fixture-rollcall", "0042")
        manual_result = [event for event in self.events if event[0] == "answer_result"][-1]
        app.DashboardWindow._ev_answer_result(self.window, manual_result)
        self.assertEqual(self.event.result, "已签到")
        self.assertNotIn((id(self.source), self.event.rollcall_id), self.window._answer_tasks)
        self.assertIn(self.event.rollcall_id, self.window._auto_answer_attempted_rollcalls)

    def test_thread_dispatch_failure_emits_terminal_result_and_releases_gate(self):
        self.window._run_thread.side_effect = RuntimeError("synthetic thread limit")
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        result = next(event for event in self.events if event[0] == "answer_result")
        app.DashboardWindow._ev_answer_result(self.window, result)
        self.assertIn("启动失败", result[3])
        self.assertEqual(self.window._answer_tasks, {})
        self.assertEqual(self.event.result, "失败")

    def test_clone_failure_emits_terminal_result_and_releases_gate(self):
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        target, args = self.dispatched[0]
        with patch("xmu_rollcall.desktop_qt.app.clone_session", side_effect=RuntimeError("synthetic clone failure")):
            target(*args)
        result = next(event for event in self.events if event[0] == "answer_result")
        app.DashboardWindow._ev_answer_result(self.window, result)
        self.assertIn("synthetic clone failure", result[3])
        self.assertEqual(self.window._answer_tasks, {})
        self.assertEqual(self.event.result, "失败")

    def _run_manual_worker(self, target, args, *, answer=None):
        worker_session = requests.Session()
        self.addCleanup(worker_session.close)
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=worker_session), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            engine.return_value.answer.return_value = True
            if answer is not None:
                engine.return_value.answer.side_effect = answer
            target(*args)
            return engine

    def _seed_strategy_config(self):
        config.save_config({
            "accounts": [{"id": "fixture", "username": "fixture", "rollcall_settings": {}}],
            "current_account_id": "fixture",
        })

    def test_manual_task_survives_monitor_pause_before_clone(self):
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        target, args = self.dispatched[0]
        cancel = args[-1]
        self.window.monitor_stop_event = threading.Event()
        self.window.stop_monitor()
        self.assertFalse(cancel.is_set())
        self._run_manual_worker(target, args).return_value.answer.assert_called_once()

    def test_manual_task_survives_strategy_change_before_clone(self):
        self._seed_strategy_config()
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        target, args = self.dispatched[0]
        cancel = args[-1]
        self.window._save_rollcall_strategy_settings()
        self.assertFalse(cancel.is_set())
        self._run_manual_worker(target, args).return_value.answer.assert_called_once()

    def test_automatic_task_is_cancelled_by_pause_or_strategy_change(self):
        for action in ("pause", "strategy"):
            with self.subTest(action=action):
                self.events.clear()
                self.dispatched.clear()
                self.window._answer_tasks.clear()
                self.window._answer_cancellations.clear()
                token = object()
                stop = threading.Event()
                self.window.monitor_stop_event = stop
                self.window._auto_answer_enabled = True
                self.window._auto_answer_inflight_rollcalls[self.event.rollcall_id] = token
                if action == "strategy":
                    self._seed_strategy_config()
                app.DashboardWindow._answer_event(
                    self.window, "event-1", self.event, auto=True,
                    auto_snapshot={"task_token": token, "number_code": "0042"},
                )
                target, args = self.dispatched[0]
                cancel = args[-1]
                if action == "pause":
                    self.window.stop_monitor()
                else:
                    self.window._save_rollcall_strategy_settings()
                self.assertTrue(cancel.is_set())
                worker_session = requests.Session()
                with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=worker_session), \
                     patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
                    engine.return_value.answer.return_value = True
                    target(*args)
                    engine.return_value.answer.assert_not_called()
                    worker_session.close()
                result = [event for event in self.events if event[0] == "answer_result"][-1]
                app.DashboardWindow._ev_answer_result(self.window, result)

    def test_submitting_task_blocks_manual_and_auto_but_other_rollcall_is_independent(self):
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        target, args = self.dispatched[0]
        entered, release = threading.Event(), threading.Event()
        worker_session = requests.Session()
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=worker_session), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            engine.return_value.answer.side_effect = lambda *_: (entered.set(), release.wait(2), True)[-1]
            thread = threading.Thread(target=target, args=args)
            thread.start()
            self.assertTrue(entered.wait(2))
            self.assertEqual(self.window._answer_tasks[(id(self.source), self.event.rollcall_id)]["phase"], "submitting")
            app.DashboardWindow._answer_event(self.window, "event-1", self.event)
            auto_token = object()
            self.window._auto_answer_inflight_rollcalls[self.event.rollcall_id] = auto_token
            app.DashboardWindow._answer_event(
                self.window, "event-1", self.event, auto=True,
                auto_snapshot={"task_token": auto_token},
            )
            self.assertEqual(len(self.dispatched), 1)
            second = self.event.__class__(
                "other-rollcall", "Other", "Teacher", "数字签到", "unsigned", {}, number_code="0001",
            )
            self.window.events_by_id["event-2"] = second
            app.DashboardWindow._answer_event(self.window, "event-2", second)
            self.assertEqual(len(self.dispatched), 2)
            release.set()
            thread.join(2)
            self.assertFalse(thread.is_alive())
            engine.return_value.answer.assert_called_once()
        worker_session.close()

    def test_failed_old_attempt_result_cannot_overwrite_explicit_retry(self):
        self.window._run_thread.side_effect = lambda target, *args: self.dispatched.append((target, args))
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        target, args = self.dispatched[-1]
        with patch("xmu_rollcall.desktop_qt.app.clone_session", side_effect=RuntimeError("clone failed")):
            target(*args)
            old_result = self.events[-1]
        app.DashboardWindow._ev_answer_result(self.window, old_result)
        app.DashboardWindow._answer_event(self.window, "event-1", self.event)
        new_token = self.window._answer_tasks[(id(self.source), self.event.rollcall_id)]["token"]
        app.DashboardWindow._ev_answer_result(self.window, old_result)
        self.assertIs(self.window._answer_tasks[(id(self.source), self.event.rollcall_id)]["token"], new_token)
        self.assertEqual(self.event.result, "处理中")


if __name__ == "__main__":
    unittest.main()
