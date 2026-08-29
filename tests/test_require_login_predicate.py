"""_require_login 谓词测试（E4）：need_account/silent 两开关 × 登录态矩阵。

以 SimpleNamespace 作宿主直调 DashboardWindow._require_login（方法只读
self.session/self.account 两属性）；QMessageBox.warning 打桩记录调用次数，
断言弹窗抑制语义。不构造任何 GUI 对象、不建 QApplication。
"""

from __future__ import annotations

import types
import unittest

from unittest import mock

from xmu_rollcall.desktop_qt.app import DashboardWindow


class RequireLoginPredicateTest(unittest.TestCase):
    def _call(self, *, session, account, need_account=False, silent=False, warning):
        host = types.SimpleNamespace(session=session, account=account)
        with mock.patch("xmu_rollcall.desktop_qt.app.QMessageBox.warning", warning):
            return DashboardWindow._require_login(host, need_account=need_account, silent=silent)

    def test_no_session_shows_warning_and_rejects(self):
        calls = []
        allowed = self._call(session=None, account=None, warning=lambda *a, **k: calls.append(a))
        self.assertFalse(allowed)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][1:], ("尚未登录", "请先登录。"))

    def test_no_session_silent_rejects_without_dialog(self):
        calls = []
        allowed = self._call(session=None, account=None, silent=True, warning=lambda *a, **k: calls.append(a))
        self.assertFalse(allowed)
        self.assertEqual(calls, [], "silent 必须完全抑制弹窗（后台静默刷新路径）")

    def test_session_without_account_rejects_when_account_required(self):
        calls = []
        allowed = self._call(
            session=object(),
            account=None,
            need_account=True,
            warning=lambda *a, **k: calls.append(a),
        )
        self.assertFalse(allowed)
        self.assertEqual(len(calls), 1)

    def test_session_with_account_passes_account_requirement(self):
        calls = []
        allowed = self._call(
            session=object(),
            account={"id": "u1"},
            need_account=True,
            warning=lambda *a, **k: calls.append(a),
        )
        self.assertTrue(allowed)
        self.assertEqual(calls, [])

    def test_session_alone_passes_when_account_not_required(self):
        calls = []
        allowed = self._call(session=object(), account=None, need_account=False, warning=lambda *a, **k: calls.append(a))
        self.assertTrue(allowed)
        self.assertEqual(calls, [])
