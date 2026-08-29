"""PC 签到提交 send_code/send_radar 成功路径补测（本轮补测，纯增测试）。

此前守护集中在会话过期分流（test_review_regressions 的 3 用例）；成功路径
零断言——数字码提取后提交载荷、雷达打点顺序/候选提交顺序/短路行为、
「每次打点独立 deviceId、同一打点重试共用同一 body」的指纹语义均未锁定。
全部走 ScriptedSession 注入，零真实网络。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.verify import send_code, send_radar  # noqa: E402


class ScriptedResponse:
    def __init__(self, status_code=200, payload=None):
        self.status_code = status_code
        self._payload = payload
        self.headers = {"Content-Type": "application/json"}
        self.url = "https://lnt.xmu.edu.cn/api/fixture"
        self.history = []
        self.text = "fixture"

    def json(self):
        if self._payload is None:
            raise ValueError("no payload")
        return self._payload

    def raise_for_status(self):
        return None


class ScriptedSession:
    """按脚本顺序出队响应；记录全部 GET/PUT 调用载荷。"""

    def __init__(self, get_responses=(), put_responses=()):
        self.headers = {"X-Fixture-Token": "t"}
        self.gets = []
        self.puts = []
        self._get_queue = list(get_responses)
        self._put_queue = list(put_responses)

    def get(self, url, **kwargs):
        self.gets.append(url)
        return self._get_queue.pop(0)

    def put(self, url, json=None, **kwargs):
        self.puts.append((url, json))
        return self._put_queue.pop(0)


class SendCodePathTests(unittest.TestCase):
    def test_happy_path_submits_extracted_code(self):
        session = ScriptedSession(
            get_responses=[ScriptedResponse(200, {"data": {"number_code": "486"}})],
            put_responses=[ScriptedResponse(200)],
        )
        self.assertTrue(send_code(session, "r1"))
        url, payload = session.puts[0]
        self.assertTrue(url.endswith("/api/rollcall/r1/answer_number_rollcall"))
        self.assertEqual(payload["numberCode"], "486")
        self.assertTrue(payload["deviceId"])

    def test_missing_number_code_skips_submit(self):
        session = ScriptedSession(
            get_responses=[ScriptedResponse(200, {"data": {"other": 1}})],
        )
        self.assertFalse(send_code(session, "r1"))
        self.assertEqual(session.puts, [])

    def test_code_fetch_non_200_returns_false(self):
        session = ScriptedSession(
            get_responses=[ScriptedResponse(503)],
        )
        self.assertFalse(send_code(session, "r1"))
        self.assertEqual(session.puts, [])


class SendRadarPathTests(unittest.TestCase):
    def test_first_put_success_short_circuits(self):
        session = ScriptedSession(put_responses=[ScriptedResponse(200)])
        self.assertTrue(send_radar(session, "r1"))
        self.assertEqual(len(session.puts), 1)

    def test_full_chain_submits_pings_then_candidate_in_order(self):
        # 两打点各返回 distance=50km（锚点相距约 39km，圆有交点），第三发候选即 200
        session = ScriptedSession(
            put_responses=[
                ScriptedResponse(201, {"distance": 50000}),
                ScriptedResponse(201, {"distance": 50000}),
                ScriptedResponse(200),
            ]
        )
        self.assertTrue(send_radar(session, "r1"))
        self.assertEqual(len(session.puts), 3)
        first = session.puts[0][1]
        second = session.puts[1][1]
        candidate = session.puts[2][1]
        self.assertEqual(first["latitude"], 24.3)
        self.assertEqual(first["longitude"], 118.0)
        self.assertEqual(second["latitude"], 24.6)
        self.assertEqual(second["longitude"], 118.2)
        # 候选坐标来自圆交解算，不等于任何打点
        self.assertNotEqual((candidate["latitude"], candidate["longitude"]), (24.3, 118.0))
        self.assertNotEqual((candidate["latitude"], candidate["longitude"]), (24.6, 118.2))
        # 每次打点独立 deviceId（现行为锁定：跨打点换指纹、同一 body 内重试不换）
        device_ids = {first["deviceId"], second["deviceId"], candidate["deviceId"]}
        self.assertEqual(len(device_ids), 3)

    def test_second_ping_success_stops_before_solving(self):
        session = ScriptedSession(
            put_responses=[
                ScriptedResponse(201, {"distance": 50000}),
                ScriptedResponse(200),
            ]
        )
        self.assertTrue(send_radar(session, "r1"))
        self.assertEqual(len(session.puts), 2)

    def test_non_numeric_distance_fails_cleanly_without_candidates(self):
        session = ScriptedSession(
            put_responses=[
                ScriptedResponse(201, {"distance": "corrupt"}),
                ScriptedResponse(201, {"distance": 50000}),
            ]
        )
        self.assertFalse(send_radar(session, "r1"))
        self.assertEqual(len(session.puts), 2)


if __name__ == "__main__":
    unittest.main()
