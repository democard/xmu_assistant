from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.engine import RollcallEngine  # noqa: E402


class JsonResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeSession:
    def __init__(self, payload):
        self.payload = payload

    def get(self, url, **kwargs):
        return JsonResponse(self.payload)


class RollcallEngineTests(unittest.TestCase):
    def test_poll_payload_returns_raw_dict(self):
        engine = RollcallEngine(FakeSession({"rollcalls": [{"rollcall_id": "1"}]}))

        payload = engine.poll_payload()

        self.assertEqual(payload, {"rollcalls": [{"rollcall_id": "1"}]})

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
