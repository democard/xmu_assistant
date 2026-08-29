from __future__ import annotations

import sys
import unittest
from pathlib import Path

# 先插路径再导入：本机有全局安装的 xmu_rollcall 会遮蔽本地源码
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

import requests  # noqa: E402

from xmu_rollcall.utils import (  # noqa: E402
    SessionExpiredError,
    answer_failure_detail,
    merge_worker_session_cookies,
)


def cookies_of(session: requests.Session) -> dict:
    return requests.utils.dict_from_cookiejar(session.cookies)


class MergeWorkerSessionCookiesTests(unittest.TestCase):
    """H2：cookie 回写必须带「登出/换号」守卫（纯函数版）。"""

    def test_matching_account_merges_cookies(self):
        main = requests.Session()
        worker = requests.Session()
        worker.cookies.set("JSESSIONID", "abc")

        merged = merge_worker_session_cookies(main, {"id": 7}, worker, "7")

        self.assertTrue(merged)
        self.assertEqual(cookies_of(main).get("JSESSIONID"), "abc")

    def test_account_switch_stops_cross_account_merge(self):
        """退出 A 登录 B 后，旧 worker 的 A cookie 不得写进 B 会话。"""
        main = requests.Session()
        worker_a = requests.Session()
        worker_a.cookies.set("JSESSIONID", "stale-a")

        merged = merge_worker_session_cookies(main, {"id": 8}, worker_a, "7")

        self.assertFalse(merged)
        self.assertEqual(cookies_of(main), {})

    def test_none_main_session_does_not_crash(self):
        """登出在途主会话为 None → 直接丢弃，不得抛 AttributeError。"""
        worker = requests.Session()
        worker.cookies.set("JSESSIONID", "abc")

        merged = merge_worker_session_cookies(None, {"id": 7}, worker, "7")

        self.assertFalse(merged)

    def test_same_session_is_skipped(self):
        main = requests.Session()
        main.cookies.set("JSESSIONID", "x")
        self.assertFalse(merge_worker_session_cookies(main, {"id": 7}, main, "7"))

    def test_none_worker_account_id_skips_guard(self):
        """没有账号归属信息时不做拦截（兼容旧事件格式），但主会话仍须非空。"""
        main = requests.Session()
        worker = requests.Session()
        worker.cookies.set("JSESSIONID", "abc")
        self.assertTrue(merge_worker_session_cookies(main, {"id": 7}, worker, ""))


class AnswerFailureDetailTests(unittest.TestCase):
    """H3：应答异常分类——会话过期必须是独立语义而非普通失败。"""

    def test_session_expired_maps_to_relogin(self):
        detail, expired = answer_failure_detail(SessionExpiredError("登录已过期"))
        self.assertTrue(expired)
        self.assertEqual(detail, "登录已过期，请重新登录")

    def test_generic_error_is_plain_failure(self):
        detail, expired = answer_failure_detail(RuntimeError("网络失败：500"))
        self.assertFalse(expired)
        self.assertEqual(detail, "网络失败：500")


if __name__ == "__main__":
    unittest.main()
