from __future__ import annotations

import json
import sys
import tempfile
import threading
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall import config as config_module
from xmu_rollcall.config import normalize_rollcall_settings
from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.core import CourseRollcallRecord, course_rollcall_csv
from xmu_rollcall.rollcall_models import RollcallEvent
from xmu_rollcall.rollcall_progress import (
    RollcallProgress,
    wait_before_answer_satisfied,
)


class _Check:
    def __init__(self, checked=True):
        self.checked = checked

    def isChecked(self):
        return self.checked


class _Text:
    def __init__(self):
        self.values = []

    def setText(self, value):
        self.values.append(value)


def _progress(present: int, total: int, *, reliable: bool = True, own=None):
    return RollcallProgress(
        observed=total,
        present=present,
        absent=max(0, total - present),
        unknown=0,
        roster_complete=reliable,
        own_present=own,
    )


def _event(kind="雷达签到", *, code="", expired=False):
    return RollcallEvent(
        rollcall_id="r1",
        course_title="测试课程",
        teacher="教师",
        rollcall_type=kind,
        status="rolling",
        raw={"is_expired": expired},
        number_code=code,
    )


class ThresholdReferenceTests(unittest.TestCase):
    def test_shared_android_pc_reference_cases(self):
        path = ROOT / "android/app/src/test/resources/attendance_threshold_reference_cases.json"
        for case in json.loads(path.read_text(encoding="utf-8")):
            raw = case["progress"]
            progress = RollcallProgress() if raw is None else _progress(
                raw["present"], raw["total"], reliable=raw["reliable"]
            )
            with self.subTest(case=case["name"]):
                self.assertEqual(
                    wait_before_answer_satisfied(
                        progress,
                        case["mode"],
                        count=case["count"],
                        percent=case["percent"],
                    ),
                    case["expected"],
                )

    def test_old_placeholder_config_does_not_enable_threshold(self):
        settings = normalize_rollcall_settings({
            "wait_before_answer_mode": "random",
            "wait_before_answer_count_min": 8,
            "wait_before_answer_count_max": 20,
        })
        self.assertEqual(settings["wait_before_answer_mode"], "none")
        self.assertEqual(settings["wait_before_answer_count"], 5)
        self.assertEqual(settings["wait_before_answer_percent"], 15)
        self.assertEqual(settings["wait_before_answer_count_min"], 8)
        self.assertEqual(settings["wait_before_answer_count_max"], 20)

    def test_config_bounds(self):
        low = normalize_rollcall_settings({
            "wait_before_answer_mode": "count",
            "wait_before_answer_count": 0,
            "wait_before_answer_percent": 0,
        })
        high = normalize_rollcall_settings({"wait_before_answer_percent": 101})
        self.assertEqual((low["wait_before_answer_count"], low["wait_before_answer_percent"]), (1, 1))
        self.assertEqual(high["wait_before_answer_percent"], 100)

    def test_config_save_and_reload_keeps_new_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "config.json"
            value = {
                "accounts": [{
                    "id": 1,
                    "username": "u",
                    "password": "",
                    "rollcall_settings": {
                        "wait_before_answer_mode": "percent",
                        "wait_before_answer_count": 9,
                        "wait_before_answer_percent": 23,
                    },
                }],
                "current_account_id": 1,
            }
            with patch.object(config_module, "CONFIG_DIR", Path(directory)), patch.object(
                config_module, "CONFIG_FILE", config_path
            ), patch.object(config_module.secrets, "protect", side_effect=lambda value: value), patch.object(
                config_module.secrets, "unprotect", side_effect=lambda value: value
            ):
                config_module.save_config(value)
                settings = config_module.load_config()["accounts"][0]["rollcall_settings"]
        self.assertEqual(
            (settings["wait_before_answer_mode"], settings["wait_before_answer_count"], settings["wait_before_answer_percent"]),
            ("percent", 9, 23),
        )

    def test_loading_strategy_blocks_all_widget_signals(self):
        class Spin:
            def __init__(self): self.blocks = []
            def blockSignals(self, value): self.blocks.append(value)
            def setValue(self, _value): pass

        class Combo(Spin):
            def findData(self, _value): return 2
            def setCurrentIndex(self, _value): pass

        host = types.SimpleNamespace(
            poll_interval_spin=Spin(),
            wait_before_answer_mode_combo=Combo(),
            wait_before_answer_count_spin=Spin(),
            wait_before_answer_percent_spin=Spin(),
            account={"rollcall_settings": {
                "wait_before_answer_mode": "percent",
                "wait_before_answer_count": 7,
                "wait_before_answer_percent": 20,
            }},
            _refresh_wait_strategy_controls=lambda: None,
        )
        DashboardWindow._load_rollcall_settings(host)
        self.assertEqual(host.wait_before_answer_mode_combo.blocks, [True, False])
        self.assertEqual(host.wait_before_answer_count_spin.blocks, [True, False])
        self.assertEqual(host.wait_before_answer_percent_spin.blocks, [True, False])


class ThresholdDispatchTests(unittest.TestCase):
    def _host(self, *, mode="percent", kind="雷达签到"):
        token = threading.Event()
        event = _event(kind)
        host = types.SimpleNamespace(
            session=object(),
            account={"id": 1, "username": "me", "rollcall_settings": {
                "wait_before_answer_mode": mode,
                "wait_before_answer_count": 6,
                "wait_before_answer_percent": 15,
            }},
            monitor_stop_event=token,
            auto_answer_check=_Check(True),
            _auto_answer_inflight_rollcalls={},
            _auto_answer_attempted_rollcalls=set(),
            _auto_answer_enabled=True,
            event_sequence=0,
            events_by_id={},
            event_order=[],
            metric_last_result=_Text(),
            log=lambda *_: None,
            _refresh_event_tables=lambda: None,
            notifications=[],
            answers=[],
        )
        host._notify_rollcall = lambda *args: host.notifications.append(args)
        host._answer_event = lambda *args, **kwargs: host.answers.append((args, kwargs))
        host._update_event_result = lambda event_id, result, detail: setattr(
            host.events_by_id[event_id], "result", result
        )
        DashboardWindow._add_rollcall_event(host, event)
        return host, token

    def _emit_progress(self, host, token, progress, code=""):
        DashboardWindow._ev_rollcall_progress(
            host, ("rollcall_progress", "r1", progress, code, token, _event(code=code))
        )

    def test_below_then_reached_submits_once_and_detection_notifies_once(self):
        host, token = self._host()
        self._emit_progress(host, token, _progress(5, 40))
        self._emit_progress(host, token, _progress(6, 40))
        self._emit_progress(host, token, _progress(7, 40))
        self.assertEqual(len(host.answers), 1)
        self.assertEqual(len(host.notifications), 1)

    def test_count_threshold_and_already_signed(self):
        host, token = self._host(mode="count")
        self._emit_progress(host, token, _progress(5, 40))
        self.assertEqual(host.answers, [])
        self._emit_progress(host, token, _progress(6, 40))
        self.assertEqual(len(host.answers), 1)

        signed, signed_token = self._host(mode="count")
        self._emit_progress(signed, signed_token, _progress(8, 40, own=True))
        self.assertEqual(signed.answers, [])
        self.assertEqual(signed.events_by_id["event-1"].result, "已签到")

    def test_number_rollcall_waits_for_code_then_dispatches_once(self):
        host, token = self._host(mode="count", kind="数字签到")
        self._emit_progress(host, token, _progress(6, 40), code="")
        self.assertEqual(host.answers, [])
        self.assertNotIn("r1", host._auto_answer_inflight_rollcalls)
        self._emit_progress(host, token, _progress(6, 40), code="2468")
        self._emit_progress(host, token, _progress(7, 40), code="2468")
        self.assertEqual(len(host.answers), 1)
        self.assertEqual(host.events_by_id["event-1"].number_code, "2468")

    def test_retryable_final_recheck_does_not_permanently_mark_processed(self):
        host, token = self._host(mode="count")
        self._emit_progress(host, token, _progress(6, 40))
        self.assertEqual(len(host.answers), 1)
        task_token = host._auto_answer_inflight_rollcalls["r1"]
        DashboardWindow._ev_answer_result(
            host,
            ("answer_result", "event-1", False, "继续等待（人数回落）", False, False, task_token),
        )
        self._emit_progress(host, token, _progress(6, 40))
        self.assertEqual(len(host.answers), 2)
        self.assertEqual(len(host.notifications), 1)

    def test_mode_change_waits_for_old_worker_before_new_task(self):
        host, token = self._host(mode="count")
        self._emit_progress(host, token, _progress(6, 40))
        old_token = host._auto_answer_inflight_rollcalls["r1"]

        host.account["rollcall_settings"]["wait_before_answer_count"] = 8
        host._auto_answer_epoch = getattr(host, "_auto_answer_epoch", 0) + 1
        self._emit_progress(host, token, _progress(8, 40))
        self.assertIs(host._auto_answer_inflight_rollcalls["r1"], old_token)
        self.assertEqual(len(host.answers), 1, "旧 worker 未回执前不得并发第二笔")

        DashboardWindow._ev_answer_result(
            host, ("answer_result", "event-1", False, "策略已变更", False, False, old_token)
        )
        self.assertNotIn("r1", host._auto_answer_inflight_rollcalls)
        self.assertNotIn("r1", host._auto_answer_attempted_rollcalls)

        self._emit_progress(host, token, _progress(8, 40))
        new_token = host._auto_answer_inflight_rollcalls["r1"]
        self.assertIsNot(old_token, new_token)
        self.assertEqual(len(host.answers), 2)
        DashboardWindow._ev_answer_result(
            host, ("answer_result", "event-1", True, "提交成功", True, True, new_token)
        )
        self.assertNotIn("r1", host._auto_answer_inflight_rollcalls)
        self.assertIn("r1", host._auto_answer_attempted_rollcalls)

    def test_stopped_and_old_worker_progress_are_ignored(self):
        host, token = self._host(mode="count")
        token.set()
        self._emit_progress(host, token, _progress(10, 40))
        self.assertEqual(host.answers, [])

        host, _current = self._host(mode="count")
        old = threading.Event()
        self._emit_progress(host, old, _progress(10, 40))
        self.assertEqual(host.answers, [])

    def test_pc_rows_keep_attendance_and_number_code_as_separate_fields(self):
        event = _event(kind="数字签到", code="2468")
        event.attendance_present = 6
        event.attendance_total = 40
        event.attendance_percent = 15.0
        host = types.SimpleNamespace(
            _event_status_text=lambda value: DashboardWindow._event_status_text(
                types.SimpleNamespace(), value
            )
        )
        values = DashboardWindow._event_values(host, event)
        self.assertEqual(values[-2:], ("6/40 (15.0%)", "2468"))
        self.assertNotIn("未知", values)

        record = CourseRollcallRecord(
            course_id="c", course_title="课程", rollcall_id="r1",
            rollcall_time="2026-09-20 08:00:00", rollcall_type="数字签到",
            signed_status="已签到", platform_status="on_call",
            attendance_present=6, attendance_total=40, attendance_percent=15.0,
            number_code="2468",
        )
        exported = course_rollcall_csv([record])
        self.assertIn("已签人数,总人数,已签比例,签到码", exported.splitlines()[0])
        self.assertIn(",6,40,15.0%,2468,", exported.splitlines()[1])

    def test_manual_answer_bypasses_automatic_threshold(self):
        calls = []
        host = types.SimpleNamespace(
            session=object(),
            account={"rollcall_settings": {
                "wait_before_answer_mode": "count",
                "wait_before_answer_count": 999,
            }},
            _cancel_pending_answer=lambda _event_id: None,
            _update_event_result=lambda *_args: None,
            _auto_answer_delay=lambda _event, _auto: 0.0,
            _run_thread=lambda *args: calls.append(args),
            _answer_worker=object(),
        )
        DashboardWindow._answer_event(host, "event-1", _event(), auto=False)
        self.assertEqual(len(calls), 1)
        self.assertIsNone(calls[0][-1])

class SubmissionGuardTests(unittest.TestCase):
    def _host(self, *, mode="percent"):
        token = threading.Event()
        return types.SimpleNamespace(
            monitor_stop_event=token,
            _auto_answer_epoch=3,
            _auto_answer_enabled=True,
            session=object(),
            account={"id": 1, "username": "me", "rollcall_settings": {
                "wait_before_answer_mode": mode,
                "wait_before_answer_count": 6,
                "wait_before_answer_percent": 15,
            }},
        ), token

    @staticmethod
    def _context(token, *, mode="percent", epoch=3, recheck=True):
        return {
            "monitor_token": token,
            "epoch": epoch,
            "mode": mode,
            "count": 6,
            "percent": 15,
            "username": "me",
            "account_id": "1",
            "recheck_activity": recheck,
            "progress": _progress(6, 40),
            "number_code": "2468",
            "current_event": _event(code="2468"),
        }

    def test_explicit_expired_without_deadline_never_submits(self):
        host, token = self._host()
        context = self._context(token)
        context["current_event"] = _event(expired=True)
        allowed, reason, _, _ = DashboardWindow._validate_auto_submission(
            host, object(), _event(expired=True), context
        )
        self.assertFalse(allowed)
        self.assertIn("已结束", reason)

    def test_stop_mode_change_and_epoch_change_cancel_old_task(self):
        host, token = self._host()
        token.set()
        self.assertFalse(DashboardWindow._validate_auto_submission(
            host, object(), _event(), self._context(token)
        )[0])

        host, token = self._host(mode="count")
        self.assertFalse(DashboardWindow._validate_auto_submission(
            host, object(), _event(), self._context(token, mode="percent")
        )[0])
        host, token = self._host()
        self.assertFalse(DashboardWindow._validate_auto_submission(
            host, object(), _event(), self._context(token, epoch=2)
        )[0])
        host, token = self._host()
        host.account["id"] = 2
        self.assertFalse(DashboardWindow._validate_auto_submission(
            host, object(), _event(), self._context(token)
        )[0])

    def test_submit_recheck_blocks_signed_or_fallen_threshold(self):
        host, token = self._host()
        current = _event()

        class Engine:
            def __init__(self, _session): pass
            def poll_payload(self): return {"rollcalls": []}
            def build_events(self, _payload): return [current]

        signed = {"student_rollcalls": [
            {"user_no": "me", "status": "on_call"},
            *({"user_no": f"u{i}", "status": "absent"} for i in range(39)),
        ]}
        with patch("xmu_rollcall.desktop_qt.app.RollcallEngine", Engine), patch(
            "xmu_rollcall.desktop_qt.app.fetch_student_rollcall_detail", return_value=signed
        ):
            allowed, reason, _, _ = DashboardWindow._validate_auto_submission(
                host, object(), _event(), self._context(token)
            )
        self.assertFalse(allowed)
        self.assertIn("本人已签到", reason)

        below = {"student_rollcalls": [
            *({"user_no": f"p{i}", "status": "on_call"} for i in range(5)),
            *({"user_no": f"a{i}", "status": "absent"} for i in range(35)),
        ]}
        with patch("xmu_rollcall.desktop_qt.app.RollcallEngine", Engine), patch(
            "xmu_rollcall.desktop_qt.app.fetch_student_rollcall_detail", return_value=below
        ):
            allowed, reason, _, _ = DashboardWindow._validate_auto_submission(
                host, object(), _event(), self._context(token)
            )
        self.assertFalse(allowed)
        self.assertIn("未达到", reason)

    def test_none_mode_survives_missing_statistics(self):
        host, token = self._host(mode="none")
        allowed, _, _, _ = DashboardWindow._validate_auto_submission(
            host,
            object(),
            _event(),
            self._context(token, mode="none", recheck=False),
        )
        self.assertTrue(allowed)

    def test_master_switch_is_rechecked_after_network_returns(self):
        host, token = self._host()
        current = _event()

        class Engine:
            def __init__(self, _session): pass
            def poll_payload(self):
                host._auto_answer_enabled = False
                return {"rollcalls": []}
            def build_events(self, _payload): return [current]

        roster = {"student_rollcalls": [
            *({"user_no": f"p{i}", "status": "on_call"} for i in range(6)),
            *({"user_no": f"a{i}", "status": "absent"} for i in range(34)),
        ]}
        with patch("xmu_rollcall.desktop_qt.app.RollcallEngine", Engine), patch(
            "xmu_rollcall.desktop_qt.app.fetch_student_rollcall_detail", return_value=roster
        ):
            allowed, reason, _, terminal = DashboardWindow._validate_auto_submission(
                host, object(), _event(), self._context(token)
            )
        self.assertFalse(allowed)
        self.assertFalse(terminal)
        self.assertIn("已关闭", reason)


if __name__ == "__main__":
    unittest.main()
