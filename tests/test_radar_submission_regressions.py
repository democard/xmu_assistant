"""Offline radar submissions against a spherical-distance server fixture."""

import math
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall.engine import RollcallEngine
from xmu_rollcall.verify import send_radar
from test_send_answer_paths import ScriptedResponse, ScriptedSession


def distance(a, b):
    p, q = map(math.radians, (a[0], b[0]))
    h = math.sin((q - p) / 2) ** 2 + math.cos(p) * math.cos(q) * math.sin(math.radians(b[1] - a[1]) / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(h)))


class SphericalServer(ScriptedSession):
    def __init__(self, target):
        super().__init__()
        self.target = target

    def put(self, url, json=None, **kwargs):
        self.puts.append((url, json))
        meters = distance((json["latitude"], json["longitude"]), self.target)
        return ScriptedResponse(200 if meters < 3 else 422, {"distance": meters, "message": "out of range"})


class RadarSubmissionRegressionTests(unittest.TestCase):
    def test_full_flow_recovers_both_original_fixed_probe_failures(self):
        for target in ((24.4371, 118.0975), (24.45, 118.1)):
            with self.subTest(target=target):
                session = SphericalServer(target)
                self.assertTrue(send_radar(session, "fixture"))
                self.assertLessEqual(len(session.puts), 4)
                accepted = session.puts[-1][1]
                self.assertLess(distance((accepted["latitude"], accepted["longitude"]), target), 0.001)
                self.assertEqual(len({body["deviceId"] for _, body in session.puts}), 1)

    def test_timeout_does_not_replay_a_write(self):
        session = Mock(headers={})
        session.put.side_effect = requests.Timeout("receipt lost")
        details = []
        self.assertFalse(send_radar(session, "fixture", on_failure=details.append))
        self.assertEqual(session.put.call_count, 1)
        self.assertIn("结果未知", details[0])
        self.assertIs(session.put.call_args.kwargs["allow_redirects"], False)

    def test_invalid_distance_stops_before_second_probe(self):
        for value in (float("nan"), float("inf"), -1, True, "42", None):
            with self.subTest(value=value):
                session = ScriptedSession(put_responses=[ScriptedResponse(422, {"distance": value})])
                details = []
                self.assertFalse(send_radar(session, "fixture", on_failure=details.append))
                self.assertEqual(len(session.puts), 1)
                self.assertIn("distance", details[0])
                self.assertIn("HTTP 422", details[0])

    def test_engine_keeps_rejection_reason_without_credentials(self):
        session = ScriptedSession(put_responses=[ScriptedResponse(403, {
            "message": "location denied token=abc cookie=def password=ghi Bearer jkl",
        })])
        engine = RollcallEngine(session)
        self.assertFalse(engine.answer_radar("fixture"))
        self.assertIn("HTTP 403", engine.last_answer_detail)
        self.assertIn("location denied", engine.last_answer_detail)
        for secret in ("abc", "def", "ghi", "jkl"):
            self.assertNotIn(secret, engine.last_answer_detail)

    def test_html_gateway_page_is_not_success(self):
        for text in ("<html><body>Bad gateway</body></html>", "<body>Bad gateway</body>", "<h1>Bad gateway</h1>", "Bad gateway"):
            with self.subTest(text=text):
                response = ScriptedResponse(200)
                response.text = text
                response.headers["Content-Type"] = "text/html"
                session = ScriptedSession(put_responses=[response])
                self.assertFalse(send_radar(session, "fixture"))
                self.assertEqual(len(session.puts), 1)


if __name__ == "__main__":
    unittest.main()
