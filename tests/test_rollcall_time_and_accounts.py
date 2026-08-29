"""PC rollcall_models 时间纯函数与 config 账号 CRUD 补测（本轮补测）。

- remaining_text / remaining_seconds_from_deadline / format_duration：此前仅
  test_notifications 一条断言擦过 remaining_seconds>0；Android 同名逻辑已有
  RemainingSecondsFromDeadlineTest，PC 为独立实现零守护。
- 账号 CRUD 面（add_account/get_account_by_id/get_current_account/
  set_current_account/get_all_accounts）：ID 分配、int|str 宽松匹配回退、
  current 指针跟随。
"""

from __future__ import annotations

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.config import (  # noqa: E402
    add_account,
    get_account_by_id,
    get_all_accounts,
    get_current_account,
    set_current_account,
)
from xmu_rollcall.rollcall_models import (  # noqa: E402
    RollcallEvent,
    format_duration,
    remaining_seconds_from_deadline,
)


class FormatDurationTests(unittest.TestCase):
    def test_segments(self):
        # 运行时长时钟回拨钳 0 后经 format_duration（app.py 调用点守卫）
        self.assertEqual(format_duration(0), "0s")
        self.assertEqual(format_duration(59), "59s")
        self.assertEqual(format_duration(60), "1m 0s")
        self.assertEqual(format_duration(125), "2m 5s")
        self.assertEqual(format_duration(3661), "1h 1m 1s")


class RemainingTextTests(unittest.TestCase):
    @staticmethod
    def _event(seconds):
        return RollcallEvent(
            rollcall_id="r", course_title="c", teacher="t",
            rollcall_type="数字签到", status="-", raw={},
            remaining_seconds=seconds,
        )

    def test_unknown_when_none(self):
        self.assertEqual(self._event(None).remaining_text, "未知")

    def test_expired_when_non_positive(self):
        self.assertEqual(self._event(0).remaining_text, "已截止")
        self.assertEqual(self._event(-5).remaining_text, "已截止")

    def test_positive_formats_duration(self):
        self.assertEqual(self._event(60).remaining_text, "1m 0s")


class RemainingSecondsTests(unittest.TestCase):
    def test_empty_and_invalid_return_none(self):
        self.assertIsNone(remaining_seconds_from_deadline(""))
        self.assertIsNone(remaining_seconds_from_deadline(None))
        self.assertIsNone(remaining_seconds_from_deadline("not-a-time"))

    def test_past_deadline_clamps_to_zero(self):
        past = (datetime.now() - timedelta(minutes=5)).isoformat()
        self.assertEqual(remaining_seconds_from_deadline(past), 0)

    def test_future_naive_iso_within_window(self):
        soon = (datetime.now() + timedelta(seconds=5)).isoformat()
        seconds = remaining_seconds_from_deadline(soon)
        self.assertIsNotNone(seconds)
        self.assertGreater(seconds, 0)
        self.assertLessEqual(seconds, 5)

    def test_utc_z_suffix_parsed(self):
        soon_utc = (
            datetime.now(timezone.utc) + timedelta(seconds=5)
        ).isoformat().replace("+00:00", "Z")
        seconds = remaining_seconds_from_deadline(soon_utc)
        self.assertIsNotNone(seconds)
        self.assertGreater(seconds, 0)
        self.assertLessEqual(seconds, 5)


class AccountCrudTests(unittest.TestCase):
    def test_add_account_assigns_next_id_and_follows_current(self):
        config = {"accounts": [{"id": 1, "username": "a"}], "current_account_id": None}
        new_id = add_account(config, "b", "pw", "二号")
        self.assertEqual(new_id, 2)
        self.assertEqual(len(config["accounts"]), 2)
        # current 为空时新账号自动接管
        self.assertEqual(config["current_account_id"], 2)

    def test_add_account_to_empty_config_starts_at_one(self):
        config = {}
        self.assertEqual(add_account(config, "a", "pw", ""), 1)

    def test_lookup_matches_int_and_str_ids(self):
        config = {"accounts": [{"id": 1, "username": "a"}, {"id": "2", "username": "b"}]}
        self.assertEqual(get_account_by_id(config, 1)["username"], "a")
        self.assertEqual(get_account_by_id(config, "2")["username"], "b")
        self.assertIsNone(get_account_by_id(config, 99))
        self.assertIsNone(get_account_by_id(config, None))

    def test_current_account_pointer_roundtrip(self):
        config = {"accounts": [{"id": 1}, {"id": 2}], "current_account_id": 1}
        self.assertEqual(get_current_account(config)["id"], 1)
        set_current_account(config, 2)
        self.assertEqual(get_current_account(config)["id"], 2)
        # 指向不存在账号时回退 None（调用方引导重新选择）
        set_current_account(config, 99)
        self.assertIsNone(get_current_account(config))
        self.assertEqual([a["id"] for a in get_all_accounts(config)], [1, 2])


if __name__ == "__main__":
    unittest.main()
