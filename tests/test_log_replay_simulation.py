from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from simulate_rollcall_detection import event_to_dict, replay_rollcall_log  # noqa: E402


class LogReplaySimulationTests(unittest.TestCase):
    def test_replays_number_radar_and_qr_lines(self):
        events = replay_rollcall_log(
            "\n".join(
                [
                    "[2026-07-15] 检测到数字签到：课程：高等数学",
                    "[2026-07-15] 检测到雷达签到：课程：大学物理",
                    "[2026-07-15] 检测到二维码签到：课程：大学英语",
                    "[2026-07-15] 普通轮询，无签到",
                ]
            )
        )

        self.assertEqual(len(events), 3)
        self.assertEqual([event.course_title for event in events], ["高等数学", "大学物理", "大学英语"])
        self.assertEqual(event_to_dict(events[0])["simulated_action"], "auto-answer")
        self.assertEqual(event_to_dict(events[1])["simulated_action"], "auto-answer")
        self.assertEqual(event_to_dict(events[2])["simulated_action"], "notify-only")


if __name__ == "__main__":
    unittest.main()
