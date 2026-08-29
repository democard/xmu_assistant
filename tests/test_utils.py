from __future__ import annotations

import sys
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

# 必须先插路径再导入：本机有全局安装的 xmu_rollcall 会遮蔽本地源码（其他测试同款做法）
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from xmu_rollcall.utils import (  # noqa: E402
    RetryCancelled,
    SessionExpiredError,
    late_worker_result_accepted,
    ordered_endpoints,
    remember_good_endpoint,
    reset_endpoint_memory,
    retry_request,
    tune_session,
)


class LateWorkerResultTests(unittest.TestCase):
    """体检报告 P1-2：登出/换号后晚到的刷新结果守卫。"""

    def test_logged_out_rejects_everything(self):
        self.assertFalse(late_worker_result_accepted(None, "1"))
        self.assertFalse(late_worker_result_accepted(None, ""))

    def test_missing_snapshot_is_accepted_for_legacy_events(self):
        self.assertTrue(late_worker_result_accepted({"id": 1}, ""))

    def test_matching_account_accepted(self):
        self.assertTrue(late_worker_result_accepted({"id": 1}, "1"))
        # id 统一按字符串比对（config 里可能是 int）
        self.assertTrue(late_worker_result_accepted({"id": 1}, 1))

    def test_switched_account_rejected(self):
        self.assertFalse(late_worker_result_accepted({"id": 2}, "1"))


class RetryRequestTests(unittest.TestCase):
    def test_does_not_retry_session_expired(self):
        """P1：SessionExpiredError 必须直接上抛，不得退避重试（否则重复打身份域）。"""
        calls = 0

        def boom():
            nonlocal calls
            calls += 1
            raise SessionExpiredError("登录已过期")

        with self.assertRaises(SessionExpiredError):
            retry_request(boom, max_attempts=3, delay=0, label="test")

        self.assertEqual(calls, 1)

    def test_retries_transient_exception_then_raises(self):
        calls = 0

        def boom():
            nonlocal calls
            calls += 1
            raise OSError("网络抖动")

        with self.assertRaises(OSError):
            retry_request(boom, max_attempts=3, delay=0, label="test")

        self.assertEqual(calls, 3)

    def test_returns_result_on_success(self):
        result = retry_request(lambda: "ok", max_attempts=3, delay=0, label="test")
        self.assertEqual(result, "ok")

    def test_interrupted_by_stop_event(self):
        stop = threading.Event()
        stop.set()

        def never_called():
            raise AssertionError("不应执行")

        with self.assertRaises(RetryCancelled):
            retry_request(never_called, max_attempts=3, delay=0, stop_event=stop, label="test")

    def test_does_not_retry_session_expired_even_with_stop_event(self):
        calls = 0
        stop = threading.Event()

        def boom():
            nonlocal calls
            calls += 1
            raise SessionExpiredError("登录已过期")

        with self.assertRaises(SessionExpiredError):
            retry_request(boom, max_attempts=5, delay=0, stop_event=stop, label="test")

        self.assertEqual(calls, 1)


class SecretsFallbackTests(unittest.TestCase):
    def test_protect_falls_back_to_plaintext_when_win32crypt_missing(self):
        """P4：依赖清单缺 pywin32 时 protect() 必须显示回退明文（不崩、不伪装加密）。"""
        import builtins

        from xmu_rollcall import secrets as secrets_mod

        real_import = builtins.__import__

        def fake_import(name, *args, **kwargs):
            if name == "win32crypt":
                raise ImportError("No module named 'win32crypt'")
            return real_import(name, *args, **kwargs)

        with patch("builtins.__import__", side_effect=fake_import):
            self.assertEqual(secrets_mod.protect("secret"), "secret")

    def test_protect_is_idempotent_on_encrypted_value(self):
        from xmu_rollcall import secrets as secrets_mod

        # 已带 dpapi: 前缀的密文不再重复加密（防止二次写入损坏）
        self.assertEqual(secrets_mod.protect("dpapi:abc"), "dpapi:abc")


class EndpointMemoryTests(unittest.TestCase):
    """课程端点记忆：上次成功者下次首选，省掉必败探测请求（提速）。"""

    def setUp(self):
        reset_endpoint_memory()

    def tearDown(self):
        reset_endpoint_memory()

    def test_no_memory_keeps_original_order(self):
        endpoints = ordered_endpoints("courses", ("/a", "/b", "/c"))
        self.assertEqual(endpoints, ["/a", "/b", "/c"])

    def test_remembered_endpoint_moves_to_front(self):
        remember_good_endpoint("courses", "/b")
        endpoints = ordered_endpoints("courses", ("/a", "/b", "/c"))
        self.assertEqual(endpoints, ["/b", "/a", "/c"])

    def test_unknown_purpose_is_ignored(self):
        remember_good_endpoint("other", "/x")
        endpoints = ordered_endpoints("courses", ("/a", "/b"))
        self.assertEqual(endpoints, ["/a", "/b"])

    def test_remembered_endpoint_not_in_candidates(self):
        remember_good_endpoint("courses", "/gone")
        endpoints = ordered_endpoints("courses", ("/a", "/b"))
        self.assertEqual(endpoints, ["/a", "/b"])


class TuneSessionTests(unittest.TestCase):
    def test_tunes_real_session_and_is_idempotent_safe(self):
        import requests

        session = requests.Session()
        tune_session(session)
        https_adapter = session.get_adapter("https://lnt.xmu.edu.cn/api/profile")
        http_adapter = session.get_adapter("http://lnt.xmu.edu.cn/api/profile")
        # 挂载了自定义 max_retries（默认适配器 max_retries=0）
        self.assertEqual(https_adapter.max_retries.total, 2)
        self.assertEqual(http_adapter.max_retries.total, 2)
        # 重复调用不崩（重新挂载覆盖）
        tune_session(session)

    def test_fake_session_passthrough(self):
        class Fake:
            pass

        fake = Fake()
        self.assertIs(tune_session(fake), fake)


class SaveSessionTests(unittest.TestCase):
    def test_save_and_load_roundtrip_leaves_no_tmp(self):
        """原子写盘：save 后无 .tmp 残留，load 能还原 cookie（DPAPI 加密往返兼容）。"""
        import os
        import tempfile

        import requests

        from xmu_rollcall.utils import load_session, save_session

        session = requests.Session()
        session.cookies.set("session_token", "abc123")
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "cookies.json")
            save_session(session, path)
            self.assertTrue(os.path.exists(path))
            self.assertFalse(os.path.exists(path + ".tmp"))
            restored = requests.Session()
            self.assertTrue(load_session(restored, path))
            self.assertEqual(restored.cookies.get("session_token"), "abc123")


class ResponseSessionExpiredTests(unittest.TestCase):
    """会话过期判定分支覆盖（B1，此前零测试）：final URL / history+HTML / 标记词 / 流式 peek_body。"""

    @staticmethod
    def _response(url="", history=None, content_type="application/json", body="", peek_body=True):
        from xmu_rollcall.utils import response_session_expired

        class R:
            pass

        r = R()
        r.url = url
        r.history = history or []
        r.headers = {"Content-Type": content_type}
        r.text = body
        return response_session_expired(r, peek_body=peek_body)

    def test_identity_host_in_final_url_is_expired_even_without_history(self):
        self.assertTrue(self._response(url="https://c-identity.xmu.edu.cn/auth/realms/xmu/login"))
        self.assertTrue(self._response(url="https://ids.xmu.edu.cn/authserver/login"))

    def test_plain_api_response_without_redirect_is_not_expired(self):
        self.assertFalse(self._response(url="https://lnt.xmu.edu.cn/api/profile"))

    def test_redirect_to_html_login_page_markers(self):
        history = [object()]
        self.assertTrue(
            self._response(
                url="https://lnt.xmu.edu.cn/login",
                history=history,
                content_type="text/html",
                body='<form action="https://ids.xmu.edu.cn/authserver/login">pwdencryptsalt',
            )
        )
        self.assertTrue(
            self._response(
                url="https://lnt.xmu.edu.cn/login",
                history=history,
                content_type="text/html",
                body="window.location=/auth/realms/xmu/protocol/openid-connect/auth",
            )
        )

    def test_redirect_to_non_html_is_not_expired(self):
        self.assertFalse(
            self._response(
                url="https://lnt.xmu.edu.cn/x",
                history=[object()],
                content_type="application/json",
            )
        )

    def test_redirect_to_html_without_markers_is_not_expired(self):
        self.assertFalse(
            self._response(
                url="https://lnt.xmu.edu.cn/error",
                history=[object()],
                content_type="text/html",
                body="<html><body>server error</body></html>",
            )
        )

    def test_streaming_peek_body_false_treats_html_redirect_as_expired(self):
        # 流式下载不能读 body 探测：跳转过且 Content-Type 为 HTML 即按登录页处理
        self.assertTrue(
            self._response(
                url="https://lnt.xmu.edu.cn/x",
                history=[object()],
                content_type="text/html",
                peek_body=False,
            )
        )


class RetryRequestGuardTests(unittest.TestCase):
    """retry_request 配置边角防御（2026-08-30 体检 P3）。"""

    def test_max_attempts_below_one_raises_value_error(self):
        # 修复前：max_attempts=0 循环零次执行后 raise None → TypeError 掩盖配置错误
        from xmu_rollcall import utils

        with self.assertRaises(ValueError):
            utils.retry_request(lambda: 1, max_attempts=0, label="probe")
        with self.assertRaises(ValueError):
            utils.retry_request(lambda: 1, max_attempts=-1, label="probe")

    def test_max_attempts_one_executes_once_and_returns(self):
        from xmu_rollcall import utils

        calls = []

        def fn():
            calls.append(1)
            return "ok"

        self.assertEqual(utils.retry_request(fn, max_attempts=1, label="probe"), "ok")
        self.assertEqual(len(calls), 1)



if __name__ == "__main__":
    unittest.main()
