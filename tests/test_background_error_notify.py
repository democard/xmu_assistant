"""后台错误通知阈值行为（2026-08-30 体检 P1-2）。

缺陷背景：会话过期是终态错误（MonitorWorker 发一次 error 即 break），
而 _record_background_error 要求累计 ≥3 次才通知——终态错误永远凑不满
阈值，托盘常驻用户对监控停摆零感知（临时网络错误反而 3 次就通知）。

行为契约（SimpleNamespace 宿主直调 + 模块级配置函数打桩）：
- 终态（immediate=True）第 1 次即通知；
- 终态仍保留一次性去重（不重启监控不重复打扰）；
- 瞬时错误维持「连续 3 次」阈值与去重（既有行为不变）；
- _ev_error 对「登录已过期」文案族自动置 immediate。
"""

from __future__ import annotations

import sys
import types
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.app import DashboardWindow  # noqa: E402


def _settings(system=True, pushplus=False, qq=False):
    return {
        "system": {"enabled": system},
        "pushplus": {"enabled": pushplus},
        "qq_mail": {"enabled": qq},
    }


class BackgroundErrorNotifyTest(unittest.TestCase):
    def _host(self):
        host = types.SimpleNamespace(
            background_error_count=0,
            background_error_notified=False,
            logs=[],
            system_notifications=[],
            external_notifications=[],
            # _ev_error 已加登出守卫：登录态（session 非空）下走记录分支
            session=object(),
        )
        host.log = host.logs.append
        host._show_system_notification = lambda *a, **k: host.system_notifications.append(a)
        host._send_external_notification = lambda *a, **k: host.external_notifications.append(a)
        return host

    def _patched(self, settings):
        return (
            mock.patch(
                "xmu_rollcall.desktop_qt.app.load_config", return_value={}
            ),
            mock.patch(
                "xmu_rollcall.desktop_qt.app.get_notification_settings",
                return_value=settings,
            ),
        )

    def test_immediate_terminal_error_notifies_on_first_occurrence(self):
        host = self._host()
        with self._patched(_settings())[0], self._patched(_settings())[1]:
            DashboardWindow._record_background_error(
                host, "轮询失败：登录已过期，请重新登录", immediate=True
            )
        self.assertEqual(len(host.system_notifications), 1)
        self.assertTrue(host.background_error_notified)

    def test_immediate_error_respects_once_only_dedup(self):
        host = self._host()
        p1, p2 = self._patched(_settings())
        with p1, p2:
            DashboardWindow._record_background_error(host, "轮询失败：登录已过期", immediate=True)
            DashboardWindow._record_background_error(host, "轮询失败：登录已过期", immediate=True)
        self.assertEqual(len(host.system_notifications), 1)

    def test_transient_error_below_threshold_stays_silent(self):
        host = self._host()
        p1, p2 = self._patched(_settings())
        with p1, p2:
            DashboardWindow._record_background_error(host, "轮询失败：ConnectionError")
            DashboardWindow._record_background_error(host, "轮询失败：Timeout")
        self.assertEqual(host.system_notifications, [])
        self.assertFalse(host.background_error_notified)

    def test_transient_error_notifies_on_third_occurrence(self):
        host = self._host()
        p1, p2 = self._patched(_settings())
        with p1, p2:
            for _ in range(3):
                DashboardWindow._record_background_error(host, "轮询失败：Timeout")
        self.assertEqual(len(host.system_notifications), 1)

    def test_external_channels_follow_immediate_path(self):
        host = self._host()
        p1, p2 = self._patched(_settings(system=False, pushplus=True))
        with p1, p2:
            DashboardWindow._record_background_error(host, "轮询失败：登录已过期", immediate=True)
        self.assertEqual(host.system_notifications, [])
        self.assertEqual(len(host.external_notifications), 1)

    def test_ev_error_marks_session_expired_detail_as_immediate(self):
        host = self._host()
        captured = []
        host._record_background_error = lambda detail, immediate=False: captured.append(
            (detail, immediate)
        )
        DashboardWindow._ev_error(host, ("error", "轮询失败：登录已过期，请重新登录"))
        DashboardWindow._ev_error(host, ("error", "轮询失败：Connection reset"))
        self.assertEqual(captured[0], ("轮询失败：登录已过期，请重新登录", True))
        self.assertEqual(captured[1], ("轮询失败：Connection reset", False))


if __name__ == "__main__":
    unittest.main()
