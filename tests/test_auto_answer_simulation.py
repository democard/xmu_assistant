"""Real desktop answer orchestration with synthetic sessions and no network."""
import threading
import time
import types
import unittest
from unittest.mock import Mock, patch

import requests
from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.rollcall_models import RollcallEvent


class AutoAnswerSimulationTests(unittest.TestCase):
    def setUp(self):
        self.session = requests.Session()
        self.addCleanup(self.session.close)
        self.host = types.SimpleNamespace(
            session=self.session, account={"id": "fixture", "rollcall_settings": {
                "number_delay_min": 0, "number_delay_max": 0,
            }},
            _answer_cancellations={}, _answer_cancellations_lock=threading.Lock(),
            _emit=Mock(), log=Mock(),
        )
        for name in ("_answer_worker", "_cancel_pending_answer", "_auto_answer_delay"):
            setattr(self.host, name, types.MethodType(getattr(DashboardWindow, name), self.host))
        self.event = RollcallEvent("fixture-call", "Fixture", "Teacher", "数字签到", "unsigned", {},
                                  remaining_seconds=600, number_code="0042")
        self.engine_patch = patch("xmu_rollcall.desktop_qt.app.RollcallEngine")
        self.engine = self.engine_patch.start().return_value
        self.addCleanup(self.engine_patch.stop)
        self.engine.answer.return_value = True

    def test_real_worker_waits_then_submits_and_cleans_slot(self):
        start = time.monotonic()
        self.host._answer_worker("e", self.event, delay=0.03)
        self.assertGreaterEqual(time.monotonic() - start, 0.03)
        self.engine.answer.assert_called_once_with("数字签到", "fixture-call", "0042")
        self.assertEqual(self.host._answer_cancellations, {})

    def test_real_cancel_interrupts_delay_without_submitting(self):
        worker = threading.Thread(target=self.host._answer_worker, args=("e", self.event, 5))
        worker.start()
        try:
            deadline = time.monotonic() + 2
            while "e" not in self.host._answer_cancellations and time.monotonic() < deadline:
                time.sleep(0.001)
            self.assertIn("e", self.host._answer_cancellations)
            self.host._cancel_pending_answer("e")
            worker.join(2)
            self.assertFalse(worker.is_alive())
            self.engine.answer.assert_not_called()
            results = [call.args[0] for call in self.host._emit.call_args_list if call.args[0][0] == "answer_result"]
            self.assertEqual(results[0][3], "已取消")
        finally:
            self.host._cancel_pending_answer("e")
            worker.join(2)

    def test_real_delay_policy_skips_delay_near_deadline(self):
        self.host.account["rollcall_settings"].update(number_delay_min=10, number_delay_max=30)
        self.event.remaining_seconds = 5
        self.assertEqual(self.host._auto_answer_delay(self.event, True), 0)
        self.host._answer_worker("e", self.event, self.host._auto_answer_delay(self.event, True))
        self.engine.answer.assert_called_once()

    def test_manual_and_disabled_auto_delay_are_zero(self):
        self.assertEqual(self.host._auto_answer_delay(self.event, True), 0)
        self.host.account["rollcall_settings"].update(number_delay_min=10, number_delay_max=30)
        self.assertEqual(self.host._auto_answer_delay(self.event, False), 0)

    def test_real_worker_finally_preserves_new_owners_cancel_signal(self):
        successor = threading.Event()
        def replace_owner(_timeout):
            self.host._answer_cancellations["e"] = successor
            return True
        previous = Mock()
        previous.wait.side_effect = replace_owner
        with patch("xmu_rollcall.desktop_qt.app.threading.Event", return_value=previous):
            self.host._answer_worker("e", self.event, delay=5)
        self.assertIs(self.host._answer_cancellations["e"], successor)
        self.host._cancel_pending_answer("e")
        self.assertTrue(successor.is_set())
        self.engine.answer.assert_not_called()

    def test_session_changed_during_wait_cancels_before_engine(self):
        wait = Mock()
        def change_session(_timeout):
            self.host.session = None
            return False
        wait.wait.side_effect = change_session
        with patch("xmu_rollcall.desktop_qt.app.threading.Event", return_value=wait):
            self.host._answer_worker("e", self.event, delay=1)
        self.engine.answer.assert_not_called()
        self.assertEqual(self.host._answer_cancellations, {})
