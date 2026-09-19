"""proxy_guard 首个行为测试：patch 后新建的 requests.Session 禁用继承代理。

disable_system_proxies 用于校园网/隧道环境——系统继承的代理会让请求走不可用
代理。此前该模块零测试引用；此测试同时锁定幂等性（重复调用不得叠加包装）。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

import requests.sessions  # noqa: E402

from xmu_rollcall.proxy_guard import disable_system_proxies  # noqa: E402


class ProxyGuardTests(unittest.TestCase):
    def setUp(self):
        # 该函数改的是 requests.sessions.Session 类属性（进程级状态）：
        # 必须原样还原，避免向同进程的其它用例泄漏包装。
        self._original_init = requests.sessions.Session.__init__
        self._original_flag = getattr(requests.sessions.Session, "_xmu_proxy_patched", False)
        self.addCleanup(setattr, requests.sessions.Session, "__init__", self._original_init)
        self.addCleanup(setattr, requests.sessions.Session, "_xmu_proxy_patched", self._original_flag)

    def test_new_sessions_disable_trust_env_and_proxies_and_patch_is_idempotent(self):
        requests.sessions.Session._xmu_proxy_patched = False

        disable_system_proxies()
        disable_system_proxies()  # 幂等：不得叠加包装

        init_after_patch = requests.sessions.Session.__init__
        session = requests.sessions.Session()
        self.assertFalse(session.trust_env, "必须禁用 trust_env（不读系统代理环境）")
        self.assertEqual(session.proxies, {}, "必须清空 proxies")
        # 再次调用后 __init__ 不变 = 没有二次包装
        disable_system_proxies()
        self.assertIs(requests.sessions.Session.__init__, init_after_patch)


if __name__ == "__main__":
    unittest.main()
