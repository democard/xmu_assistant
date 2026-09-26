"""应答在途或结果排队时换会话：旧结果不得写入新会话或发送通知。"""

from __future__ import annotations

import sys
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.utils import SessionExpiredError


class AnswerSessionGuardTests(unittest.TestCase):
    def _host(self):
        host = types.SimpleNamespace(
            session=object(), account={"id": "A"}, emitted=[], updates=[], metrics=[],
            notifications=[], errors=[], logs=[],
            events_by_id={"event-1": types.SimpleNamespace(rollcall_id="r1")},
            _auto_answer_inflight_rollcalls={}, _auto_answer_attempted_rollcalls=set(),
            _answer_cancellations={}, _answer_cancellations_lock=threading.Lock(),
        )
        host._emit = host.emitted.append
        host.log = host.logs.append
        host._update_event_result = lambda *args: host.updates.append(args)
        host.metric_last_result = types.SimpleNamespace(setText=host.metrics.append)
        host._notify_rollcall = lambda *args: host.notifications.append(args)
        host._record_background_error = lambda *args, **kwargs: host.errors.append((args, kwargs))
        return host

    def _run_worker(self, host, answer):
        source = host.session
        rollcall = types.SimpleNamespace(rollcall_id="r1", rollcall_type="数字签到", number_code="1234")
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=object()), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            engine.return_value.answer.side_effect = answer
            DashboardWindow._answer_worker(host, "event-1", rollcall, 0, None, source, "A")
        return source

    def _consume(self, host):
        for event in host.emitted:
            if event[0] == "answer_result":
                DashboardWindow._ev_answer_result(host, event)
            elif event[0] == "error":
                DashboardWindow._ev_error(host, event)

    def test_success_from_old_session_is_ignored_after_account_switch(self):
        host = self._host()

        def answer(*args):
            host.session = object()
            host.account = {"id": "B"}
            host.events_by_id.clear()
            return True

        self._run_worker(host, answer)
        self._consume(host)
        self.assertEqual(host.updates, [])
        self.assertEqual(host.metrics, [])
        self.assertEqual(host.notifications, [])

    def test_expired_old_session_does_not_alert_after_same_account_relogin(self):
        host = self._host()

        def answer(*args):
            host.session = object()
            raise SessionExpiredError("登录已过期")

        self._run_worker(host, answer)
        self._consume(host)
        self.assertEqual(host.errors, [])
        self.assertEqual(host.updates, [])
        self.assertEqual(host.notifications, [])

    def test_result_queued_before_relogin_is_rejected_at_delivery(self):
        host = self._host()
        self._run_worker(host, lambda *args: True)
        host.session = object()
        self._consume(host)
        self.assertEqual(host.updates, [])
        self.assertEqual(host.metrics, [])
        self.assertEqual(host.notifications, [])

    def test_cancellation_during_clone_does_not_overwrite_new_session_event(self):
        host = self._host()
        source = host.session

        def clone(_source):
            host.session = object()
            return object()

        with patch("xmu_rollcall.desktop_qt.app.clone_session", side_effect=clone), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            DashboardWindow._answer_worker(host, "event-1", object(), 0, None, source, "A")
        engine.assert_not_called()
        self._consume(host)
        self.assertEqual(host.updates, [])
        self.assertEqual(host.metrics, [])

    def test_delayed_cancellation_does_not_overwrite_new_session_event(self):
        host = self._host()
        source = host.session

        def wait(_delay):
            host.session = object()
            return True

        cancel = types.SimpleNamespace(wait=wait)
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=object()), \
             patch("xmu_rollcall.desktop_qt.app.threading.Event", return_value=cancel), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            DashboardWindow._answer_worker(host, "event-1", object(), 1, {}, source, "A")
        engine.assert_not_called()
        self._consume(host)
        self.assertEqual(host.updates, [])
        self.assertEqual(host.metrics, [])
        self.assertEqual(host._answer_cancellations, {})

    def test_auto_recheck_cancellation_cannot_clear_new_task_after_relogin(self):
        host = self._host()
        source = host.session
        task = object()
        host._auto_answer_inflight_rollcalls["r1"] = task

        def validate(*args):
            host.session = object()
            return False, "已取消（登录状态已变更）", "", True

        host._validate_auto_submission = validate
        rollcall = types.SimpleNamespace(number_code="1234")
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=object()), \
             patch("xmu_rollcall.desktop_qt.app.RollcallEngine") as engine:
            DashboardWindow._answer_worker(
                host, "event-1", rollcall, 0, {"task_token": task}, source, "A",
            )
        engine.assert_not_called()
        self._consume(host)
        self.assertIs(host._auto_answer_inflight_rollcalls["r1"], task)
        self.assertEqual(host._auto_answer_attempted_rollcalls, set())
        self.assertEqual(host.updates, [])

    def test_logged_out_result_is_rejected_if_login_finishes_before_delivery(self):
        host = self._host()
        host.session = None
        DashboardWindow._answer_worker(host, "event-1", object())
        host.session = object()
        self._consume(host)
        self.assertEqual(host.updates, [])
        self.assertEqual(host.metrics, [])
        self.assertEqual(host.notifications, [])

    def test_current_session_success_still_updates_and_notifies(self):
        host = self._host()
        self._run_worker(host, lambda *args: True)
        self._consume(host)
        self.assertEqual(host.updates, [("event-1", "已签到", "提交成功")])
        self.assertEqual(host.metrics, ["提交成功"])
        self.assertEqual(len(host.notifications), 1)

    def test_current_session_expiry_still_alerts(self):
        host = self._host()

        def answer(*args):
            raise SessionExpiredError("登录已过期")

        self._run_worker(host, answer)
        self._consume(host)
        self.assertEqual(len(host.errors), 1)
        self.assertTrue(host.errors[0][1]["immediate"])
        self.assertEqual(host.updates[0][1], "失败")


if __name__ == "__main__":
    unittest.main()
