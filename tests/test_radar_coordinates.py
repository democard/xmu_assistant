"""雷达坐标库与「按坐标提交」链路的回归测试（桌面端）。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))
sys.path.insert(0, str(ROOT / "tests"))

import xmu_rollcall.engine as engine_module
from test_send_answer_paths import ScriptedResponse, ScriptedSession
from xmu_rollcall.engine import RollcallEngine
from xmu_rollcall.radar_coordinates import RadarCoordinateStore, load_presets
from xmu_rollcall.verify import send_radar_at


class RadarCoordinateStoreTests(unittest.TestCase):
    def setUp(self):
        import tempfile

        self.tmp = tempfile.TemporaryDirectory()
        self.store = RadarCoordinateStore(Path(self.tmp.name) / "radar.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_add_sets_single_preferred_and_persists(self):
        first = self.store.add("c1", "数学建模", "坤銮楼 B207", 24.60, 118.31)
        second = self.store.add("c1", "数学建模", "学武楼 A305", 24.61, 118.32)
        self.assertTrue(second.preferred)
        reloaded = RadarCoordinateStore(Path(self.tmp.name) / "radar.json")
        places = reloaded.places_for("c1", "数学建模")
        self.assertEqual([place.id for place in places], [second.id, first.id])
        self.assertEqual([place.preferred for place in places], [True, False])

    def test_switch_preferred_and_delete_promotes_next(self):
        first = self.store.add("c1", "数学建模", "A", 24.0, 118.0)
        second = self.store.add("c1", "数学建模", "B", 24.1, 118.1)
        self.assertTrue(self.store.set_preferred(first.id))
        self.assertTrue(self.store.preferred_for("c1", "数学建模").id == first.id)
        self.assertTrue(self.store.delete(first.id))
        self.assertEqual(self.store.preferred_for("c1", "数学建模").id, second.id)

    def test_course_title_fallback_and_isolation(self):
        self.store.add("", "数据科学与数学建模", "A", 24.0, 118.0)
        self.assertIsNotNone(self.store.preferred_for("", "数据科学与 数学建模"))
        self.assertIsNone(self.store.preferred_for("", "大学物理"))
        # 有课程 id 时按 id 隔离，同名课程不会串用坐标
        self.store.add("c2", "数学建模", "B", 24.2, 118.2)
        self.assertIsNone(self.store.preferred_for("c3", "数学建模"))

    def test_presets_ship_with_package(self):
        presets = load_presets()
        self.assertGreater(len(presets), 10)
        self.assertTrue(all(-90 <= item.latitude <= 90 for item in presets))


class EngineRadarCoordinateTests(unittest.TestCase):
    def setUp(self):
        import tempfile

        self.tmp = tempfile.TemporaryDirectory()
        self.store = RadarCoordinateStore(Path(self.tmp.name) / "radar.json")
        self.calls = []
        self._original = engine_module.send_radar_at
        engine_module.send_radar_at = self._fake_send

    def tearDown(self):
        engine_module.send_radar_at = self._original
        self.tmp.cleanup()

    def _fake_send(self, session, rollcall_id, latitude, longitude, *, on_failure=None):
        self.calls.append((rollcall_id, latitude, longitude))
        return True

    def test_configured_course_submits_its_preferred_coordinate_once(self):
        self.store.add("c1", "数学建模", "坤銮楼 B207", 24.60, 118.31)
        engine = RollcallEngine(Mock(headers={}), radar_coordinates=self.store)
        self.assertTrue(
            engine.answer("雷达签到", "r1", course_title="数学建模", course_id="c1")
        )
        self.assertEqual(self.calls, [("r1", 24.60, 118.31)])

    def test_missing_coordinate_refuses_without_submitting(self):
        engine = RollcallEngine(Mock(headers={}), radar_coordinates=self.store)
        with self.assertRaises(RuntimeError) as caught:
            engine.answer("雷达签到", "r1", course_title="大学物理", course_id="c9")
        self.assertIn("尚未配置课程坐标", str(caught.exception))
        self.assertEqual(self.calls, [])


class SendRadarAtTests(unittest.TestCase):
    def test_single_put_uses_configured_coordinate(self):
        session = ScriptedSession(put_responses=[ScriptedResponse(200, {"ok": True})])
        self.assertTrue(send_radar_at(session, "r1", 24.6056, 118.3131))
        self.assertEqual(len(session.puts), 1)
        body = session.puts[0][1]
        self.assertAlmostEqual(body["latitude"], 24.6056, places=6)
        self.assertAlmostEqual(body["longitude"], 118.3131, places=6)

    def test_timeout_does_not_replay(self):
        session = Mock(headers={})
        session.put.side_effect = requests.Timeout("receipt lost")
        details = []
        self.assertFalse(send_radar_at(session, "r1", 24.6, 118.3, on_failure=details.append))
        self.assertEqual(session.put.call_count, 1)
        self.assertIn("未知", details[0])

    def test_rejection_keeps_http_reason(self):
        session = ScriptedSession(put_responses=[ScriptedResponse(403, {"message": "denied"})])
        details = []
        self.assertFalse(send_radar_at(session, "r1", 24.6, 118.3, on_failure=details.append))
        self.assertIn("HTTP 403", details[0])

    def test_invalid_coordinate_is_rejected_before_request(self):
        session = ScriptedSession()
        details = []
        self.assertFalse(send_radar_at(session, "r1", float("nan"), 118.3, on_failure=details.append))
        self.assertEqual(session.puts, [])
        self.assertIn("坐标无效", details[0])


if __name__ == "__main__":
    unittest.main()
