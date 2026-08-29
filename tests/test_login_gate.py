"""登录在途门测试（体检 P2）：auto_restore_current_session 占用/放行语义。

以 SimpleNamespace 作宿主直调 DashboardWindow 方法（与
test_require_login_predicate 同范式）：模块级 load_config/get_current_account/
get_cookies_path 打桩，cookie 路径指向真实存在的测试文件以通过 os.path.exists
探测；不构造任何 GUI 对象、不建 QApplication。

锁定行为：
- 恢复与手动登录共用 _login_in_progress 一把门：门占用时自动恢复跳过
  （否则恢复+手动登录同 epoch 并发，晚到成功被丢弃但其写盘已落地 → 重启漂移）；
- 门空闲时恢复置位并按 (silent=True, 当前 epoch) 拉起 _restore_worker；
- _run_thread 同步抛错时门必须复位（否则恢复路径异常后登录被永久挡住）；
- _ev_restore_failed 必须放行门（恢复失败后手动登录不被「登录已在进行中」挡住）。
"""

from __future__ import annotations

import types
import unittest
from pathlib import Path
from unittest import mock

from xmu_rollcall.desktop_qt.app import DashboardWindow

_EXISTING_FILE = Path(__file__).resolve()


class AutoRestoreLoginGateTest(unittest.TestCase):
    def _host(self, *, session=None, in_progress=False):
        host = types.SimpleNamespace(
            session=session,
            account=None,
            _login_in_progress=in_progress,
            _login_epoch=7,
            logs=[],
            threads=[],
            metric_account=types.SimpleNamespace(setText=lambda *_: None),
            _restore_worker=lambda silent=False, login_epoch=-1: None,
        )
        host.log = host.logs.append
        host._set_login_status = lambda *a, **k: None
        return host

    def _patch_fs(self):
        return (
            mock.patch("xmu_rollcall.desktop_qt.app.load_config", return_value={}),
            mock.patch(
                "xmu_rollcall.desktop_qt.app.get_current_account",
                return_value={"id": 1},
            ),
            mock.patch(
                "xmu_rollcall.desktop_qt.app.get_cookies_path",
                return_value=str(_EXISTING_FILE),
            ),
        )

    def test_restore_skipped_when_login_already_in_progress(self):
        host = self._host(in_progress=True)
        host._run_thread = lambda target, *args: host.threads.append((target, args))
        p1, p2, p3 = self._patch_fs()
        with p1, p2, p3:
            DashboardWindow.auto_restore_current_session(host)
        self.assertEqual(host.threads, [], "门占用时不得拉起恢复 worker")
        # 跳过不释放持有者的门：门仍归在途登录所有
        self.assertTrue(host._login_in_progress)
        self.assertTrue(any("登录已在进行中" in msg for msg in host.logs))

    def test_restore_takes_gate_and_spawns_worker_with_epoch(self):
        host = self._host()
        host._run_thread = lambda target, *args: host.threads.append((target, args))
        p1, p2, p3 = self._patch_fs()
        with p1, p2, p3:
            DashboardWindow.auto_restore_current_session(host)
        self.assertTrue(host._login_in_progress, "恢复必须占用登录在途门")
        self.assertEqual(len(host.threads), 1)
        target, args = host.threads[0]
        self.assertIs(target, host._restore_worker)
        self.assertEqual(args, (True, 7), "silent=True + 当前登录代数快照")

    def test_restore_gate_released_when_thread_spawn_raises(self):
        host = self._host()

        def boom(target, *args):
            raise RuntimeError("spawn failed")

        host._run_thread = boom
        p1, p2, p3 = self._patch_fs()
        with p1, p2, p3:
            DashboardWindow.auto_restore_current_session(host)
        self.assertFalse(host._login_in_progress, "拉起失败必须复位门")
        self.assertTrue(any("自动恢复检查失败" in msg for msg in host.logs))

    def test_restore_failed_releases_login_gate(self):
        host = self._host(in_progress=True)
        with mock.patch("xmu_rollcall.desktop_qt.app.QMessageBox.critical") as box:
            DashboardWindow._ev_restore_failed(
                host, ("restore_failed", "登录态已失效，请重新登录。", True)
            )
        self.assertFalse(host._login_in_progress, "恢复失败必须放行登录在途门")
        box.assert_not_called()  # silent=True 抑制弹窗（既有语义不受影响）


class LoginFailedSessionGuardTest(unittest.TestCase):
    """已登录状态下换号直登失败：迟到的失败不得把界面翻回「未登录」
    （否则侧栏「未登录」/守护徽章/托盘三态矛盾，旧账号监控照跑）——
    镜像 _ev_restore_failed 的 session 守卫先例。"""

    def _host(self, session):
        host = types.SimpleNamespace(
            session=session,
            account={"id": 2, "name": "B"} if session is not None else None,
            _login_in_progress=True,
            logs=[],
            metric_texts=[],
            status_calls=[],
        )
        host.log = host.logs.append
        host.metric_account = types.SimpleNamespace(setText=host.metric_texts.append)
        host._set_login_status = lambda *a, **k: host.status_calls.append(a)
        host._show_toast = lambda *a, **k: None
        return host

    def test_late_login_failure_keeps_logged_in_state(self):
        host = self._host(session=object())
        with mock.patch("xmu_rollcall.desktop_qt.app.QMessageBox.critical") as box:
            DashboardWindow._ev_login_failed(host, ("login_failed", "账号或密码错误"))
        self.assertFalse(host._login_in_progress, "失败必须放行登录在途门")
        self.assertEqual(host.metric_texts, [], "不得把账号指标翻成未登录")
        self.assertEqual(host.status_calls, [], "不得改写登录状态徽章")
        self.assertEqual(box.call_count, 1, "失败本身仍要弹窗告知")
        self.assertTrue(
            any("保持当前登录状态" in message for message in host.logs),
            "日志应注明保持原登录态",
        )

    def test_login_failure_without_session_shows_logged_out(self):
        host = self._host(session=None)
        with mock.patch("xmu_rollcall.desktop_qt.app.QMessageBox.critical"):
            DashboardWindow._ev_login_failed(host, ("login_failed", "账号或密码错误"))
        self.assertFalse(host._login_in_progress)
        self.assertEqual(host.metric_texts, ["未登录"])
        self.assertEqual(host.status_calls, [("未登录",)])


if __name__ == "__main__":
    unittest.main()
