"""数字签到码获取失败分流测试（体检 P3 对齐 _answer_worker 过期分支）。

以 SimpleNamespace 作宿主直调 DashboardWindow._number_code_worker：
clone_session 与 fetch_number_code 打桩，_emit 捕获事件，不建 QApplication。

锁定行为：
- 会话过期（SessionExpiredError）：额外发后台 error 事件（计数/通知去重
  由 _record_background_error 既有阈值兜底），签到码事件携带过期文案；
- 普通失败：维持原单事件语义，不发后台 error。
"""

from __future__ import annotations

import types
import unittest
from unittest import mock

from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.utils import SessionExpiredError


class NumberCodeExpiryDispatchTest(unittest.TestCase):
    def _host(self):
        host = types.SimpleNamespace(
            session=object(),
            emits=[],
        )
        host._emit = host.emits.append
        return host

    def _run(self, host, exc):
        with mock.patch(
            "xmu_rollcall.desktop_qt.app.clone_session", return_value=object()
        ), mock.patch(
            "xmu_rollcall.desktop_qt.app.fetch_number_code", side_effect=exc
        ):
            DashboardWindow._number_code_worker(
                host, "event-1", types.SimpleNamespace(rollcall_id="rc-9")
            )

    def test_session_expired_dispatches_background_error(self):
        host = self._host()
        self._run(host, SessionExpiredError("登录已过期，请重新登录"))
        kinds = [event[0] for event in host.emits]
        self.assertIn("error", kinds, "过期必须走后台错误分流引导重新登录")
        error_event = next(event for event in host.emits if event[0] == "error")
        self.assertEqual(
            error_event[1], "签到码获取失败：登录已过期，请重新登录"
        )
        detail_event = next(event for event in host.emits if event[0] == "number_code")
        self.assertEqual(detail_event[1], "event-1")
        self.assertEqual(detail_event[2], "")
        self.assertEqual(detail_event[3], "登录已过期，请重新登录")

    def test_generic_failure_keeps_single_event_semantics(self):
        host = self._host()
        self._run(host, RuntimeError("接口超时"))
        kinds = [event[0] for event in host.emits]
        self.assertNotIn("error", kinds, "普通失败不得触发后台错误分流")
        self.assertEqual(host.emits[-1], ("number_code", "event-1", "", "接口超时"))


if __name__ == "__main__":
    unittest.main()
