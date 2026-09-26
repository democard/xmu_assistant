"""Real MonitorWorker + in-memory HTTP responses; no network or account access."""
from __future__ import annotations

import json
import sys
import threading
import unittest
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall.desktop_qt.core import MonitorWorker


def response(url, payload, status=200):
    result = requests.Response()
    result.status_code = status
    result.url = url
    result.headers["Content-Type"] = "application/json"
    result._content = json.dumps(payload).encode("utf-8")
    return result


class ScenarioTransport:
    def __init__(self, detail):
        self.detail = detail
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append(url)
        if url.endswith("/api/radar/rollcalls"):
            return response(url, {"rollcalls": [
                {"rollcall_id": key, "course_title": key, "is_number": True, "status": "unsigned"}
                for key in ("a", "b")
            ]})
        return self.detail(url)


class PollBudget(threading.Event):
    def __init__(self, loops):
        super().__init__()
        self.loops = loops

    def wait(self, timeout=None):
        self.loops -= 1
        if self.loops <= 0:
            self.set()
        return self.is_set()


class MonitorSimulationV2Tests(unittest.TestCase):
    def test_slow_first_detail_does_not_delay_other_new_rollcall_notifications(self):
        entered, release, stop = threading.Event(), threading.Event(), threading.Event()
        emitted = []

        def detail(url):
            entered.set()
            if not release.wait(3):
                raise requests.Timeout("simulation cleanup timeout")
            return response(url, {"student_rollcalls": []})

        transport = ScenarioTransport(detail)
        worker = MonitorWorker(transport, emitted.append, stop, 60)
        worker.start()
        try:
            self.assertTrue(entered.wait(1), "real worker must reach the blocked first detail GET")
            self.assertEqual([event[1].rollcall_id for event in emitted if event[0] == "rollcall"], ["a", "b"])
        finally:
            stop.set()
            release.set()
            worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(len(transport.calls), 2, "pause during first detail must prevent the next detail GET")
        self.assertFalse(any(event[0] == "rollcall_progress" for event in emitted))

    def test_stop_from_first_notification_prevents_further_notifications_and_detail_requests(self):
        stop = threading.Event()
        emitted = []
        transport = ScenarioTransport(lambda url: response(url, {"student_rollcalls": []}))

        def emit(event):
            emitted.append(event)
            if event[0] == "rollcall":
                stop.set()

        MonitorWorker(transport, emit, stop, 60).run()
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual([event[1].rollcall_id for event in emitted if event[0] == "rollcall"], ["a"])
        self.assertEqual(emitted[-1][:2], ("monitor_status", "已停止"))

    def test_transient_detail_failure_recovers_without_duplicate_detection(self):
        emitted = []
        detail_calls = []

        def detail(url):
            detail_calls.append(url)
            if len(detail_calls) == 1:
                raise requests.Timeout("simulated first detail timeout")
            return response(url, {"student_rollcalls": [], "number_code": "0042"})

        MonitorWorker(ScenarioTransport(detail), emitted.append, PollBudget(2), 60).run()
        self.assertEqual([event[1].rollcall_id for event in emitted if event[0] == "rollcall"], ["a", "b"])
        progress_a = [event for event in emitted if event[0] == "rollcall_progress" and event[1] == "a"]
        self.assertEqual([event[3] for event in progress_a], ["", "0042"])
        self.assertEqual(len(detail_calls), 4)

    def test_detail_401_stops_the_real_worker_and_keeps_other_detection_notifications(self):
        emitted = []
        transport = ScenarioTransport(lambda url: response(url, {}, 401))
        MonitorWorker(transport, emitted.append, PollBudget(2), 60).run()
        self.assertEqual(len(transport.calls), 2)
        self.assertEqual([event[1].rollcall_id for event in emitted if event[0] == "rollcall"], ["a", "b"])
        errors = [event for event in emitted if event[0] == "error"]
        self.assertEqual(len(errors), 1)
        self.assertIn("登录已过期", errors[0][1])
        self.assertEqual(emitted[-1][:2], ("monitor_status", "已停止"))


if __name__ == "__main__":
    unittest.main()
