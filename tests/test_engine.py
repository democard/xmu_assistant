from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import requests


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.engine import RollcallEngine  # noqa: E402
from xmu_rollcall.utils import SessionExpiredError  # noqa: E402


class JsonResponse:
    def __init__(self, payload, status_code=200):
        self.payload = payload
        self.status_code = status_code
        self.url = "https://lnt.xmu.edu.cn/api/radar/rollcalls"
        self.headers = {"Content-Type": "application/json"}
        self.history = []
        self.text = ""

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code} response")

    def json(self):
        return self.payload


class FakeSession:
    def __init__(self, payload, status_code=200):
        self.payload = payload
        self.status_code = status_code

    def get(self, url, **kwargs):
        return JsonResponse(self.payload, self.status_code)


class RollcallEngineTests(unittest.TestCase):
    def test_poll_payload_401_raises_session_expired(self):
        engine = RollcallEngine(FakeSession({"error": "unauthorized"}, status_code=401))

        with self.assertRaises(SessionExpiredError):
            engine.poll_payload()

    def test_poll_payload_returns_raw_dict(self):
        engine = RollcallEngine(FakeSession({"rollcalls": [{"rollcall_id": "1"}]}))

        payload = engine.poll_payload()

        self.assertEqual(payload, {"rollcalls": [{"rollcall_id": "1"}]})

    def test_poll_payload_normalizes_non_list_rollcalls(self):
        # 平台异常形态（null/标量）不得让下游 for / len() 抛 TypeError 并每 interval
        # 复报「轮询失败」：在单一来源 poll_payload 归一化为空列表。
        for malformed in (None, 0, "rollcalls", {"a": 1}):
            with self.subTest(rollcalls=malformed):
                engine = RollcallEngine(FakeSession({"rollcalls": malformed}))

                payload = engine.poll_payload()

                self.assertEqual(payload["rollcalls"], [])
                self.assertEqual(engine.build_events(payload), [])

    def test_build_events_filters_non_dict_items(self):
        engine = RollcallEngine(FakeSession({}))

        events = engine.build_events({
            "rollcalls": [
                {"rollcall_id": "1", "course_title": "A", "is_number": True},
                "not-a-dict",
                None,
            ]
        })

        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].rollcall_id, "1")

    def test_answer_dispatches_by_rollcall_type(self):
        engine = RollcallEngine(FakeSession({"rollcalls": []}))

        with patch("xmu_rollcall.engine.send_code", return_value=True) as send_code:
            self.assertTrue(engine.answer("数字签到", "r1"))
            send_code.assert_called_once()

        with patch("xmu_rollcall.engine.send_radar", return_value=True) as send_radar:
            self.assertTrue(engine.answer("雷达签到", "r2"))
            send_radar.assert_called_once()

        self.assertFalse(engine.answer("二维码签到", "r3"))


    def test_poll_payload_rejects_non_dict_json(self):
        # 平台异常形态（顶层 list/str）不得穿透为 AttributeError 循环报错：
        # 按可重试 RuntimeError 上抛，走既有「轮询失败」用户文案
        engine = RollcallEngine(FakeSession(["not", "an", "object"]))
        with self.assertRaises(RuntimeError):
            engine.poll_payload()

    def test_poll_payload_rejects_string_json(self):
        engine = RollcallEngine(FakeSession("plain text"))
        with self.assertRaises(RuntimeError):
            engine.poll_payload()



if __name__ == "__main__":
    unittest.main()
