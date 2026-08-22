from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall import secrets  # noqa: E402
from xmu_rollcall.config import (  # noqa: E402
    DEFAULT_POLL_INTERVAL_SECONDS,
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
    normalize_app_settings,
    normalize_rollcall_settings,
)


class RollcallSettingsTests(unittest.TestCase):
    def test_poll_interval_has_default(self):
        settings = normalize_rollcall_settings({})

        self.assertEqual(settings["poll_interval_seconds"], DEFAULT_POLL_INTERVAL_SECONDS)

    def test_poll_interval_is_clamped_to_supported_range(self):
        too_low = normalize_rollcall_settings({"poll_interval_seconds": 0})
        too_high = normalize_rollcall_settings({"poll_interval_seconds": 999})

        self.assertEqual(too_low["poll_interval_seconds"], MIN_POLL_INTERVAL_SECONDS)
        self.assertEqual(too_high["poll_interval_seconds"], MAX_POLL_INTERVAL_SECONDS)

    def test_app_settings_launch_on_startup_is_boolean(self):
        settings = normalize_app_settings({"launch_on_startup": 1})

        self.assertIs(settings["launch_on_startup"], True)


class SecretsProtectionTests(unittest.TestCase):
    """DPAPI 加密往返：仅 Windows 平台真实加解密；非 Windows 跳过。"""

    def setUp(self):
        if not secrets.is_supported():
            self.skipTest("DPAPI only available on Windows")

    def test_protect_unprotect_roundtrip(self):
        for plaintext in ("", "short", "统一身份认证密码p@ss!", "x" * 200):
            encrypted = secrets.protect(plaintext)
            if not plaintext:
                self.assertEqual(encrypted, plaintext)
                continue
            self.assertNotEqual(encrypted, plaintext)
            self.assertTrue(encrypted.startswith(secrets.DPAPI_PREFIX))
            self.assertEqual(secrets.unprotect(encrypted), plaintext)

    def test_unprotect_legacy_plaintext_passthrough(self):
        # 旧明文（无 dpapi: 前缀）必须原样返回，保证向后兼容
        self.assertEqual(secrets.unprotect("legacy-plaintext"), "legacy-plaintext")
        self.assertEqual(secrets.unprotect(""), "")

    def test_protect_is_idempotent(self):
        once = secrets.protect("secret")
        twice = secrets.protect(once)
        self.assertEqual(once, twice)


class ConfigEncryptionTests(unittest.TestCase):
    """config.json 与 cookie 文件的加密落盘往返（仅 Windows）。"""

    def setUp(self):
        if not secrets.is_supported():
            self.skipTest("DPAPI only available on Windows")
        from xmu_rollcall import config
        self._tmpdir = tempfile.mkdtemp(prefix="xmu_config_test_")
        self._orig_config_file = config.CONFIG_FILE
        self._orig_config_dir = config.CONFIG_DIR
        config.CONFIG_DIR = Path(self._tmpdir)
        config.CONFIG_FILE = Path(self._tmpdir) / "config.json"
        self.config = config

    def tearDown(self):
        self.config.CONFIG_FILE = self._orig_config_file
        self.config.CONFIG_DIR = self._orig_config_dir
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def test_save_config_encrypts_sensitive_fields_and_roundtrips(self):
        config = self.config
        cfg = {
            "accounts": [
                {
                    "id": 1,
                    "name": "",
                    "username": "user123",
                    "password": "super-secret-pw",
                    "rollcall_settings": normalize_rollcall_settings({}),
                }
            ],
            "current_account_id": 1,
            "notification_settings": {
                "system": {"enabled": False},
                "pushplus": {"enabled": True, "token": "pushplus-token-xyz"},
                "qq_mail": {
                    "enabled": True, "sender": "a@b.c", "password": "qq-auth-code",
                    "recipient": "r@b.c", "smtp_host": "smtp.qq.com", "smtp_port": "465,587",
                },
            },
            "app_settings": normalize_app_settings({}),
        }
        config.save_config(cfg)

        raw = config.CONFIG_FILE.read_text(encoding="utf-8")
        # 落盘不得出现明文凭据
        self.assertNotIn("super-secret-pw", raw)
        self.assertNotIn("pushplus-token-xyz", raw)
        self.assertNotIn("qq-auth-code", raw)
        self.assertIn("dpapi:", raw)

        # 读回应还原为明文（向后兼容/正常使用路径）
        loaded = config.load_config()
        self.assertEqual(loaded["accounts"][0]["password"], "super-secret-pw")
        self.assertEqual(loaded["notification_settings"]["pushplus"]["token"], "pushplus-token-xyz")
        self.assertEqual(loaded["notification_settings"]["qq_mail"]["password"], "qq-auth-code")

    def test_load_config_tolerates_legacy_plaintext(self):
        # 旧版明文配置文件应能直接读入（迁移在下次保存时自动完成）
        config = self.config
        legacy = (
            '{"accounts":[{"id":1,"name":"","username":"u","password":"legacy-pw",'
            '"rollcall_settings":{}}],"current_account_id":1,'
            '"notification_settings":{"system":{"enabled":false},'
            '"pushplus":{"enabled":false,"token":"legacy-tok"},'
            '"qq_mail":{"enabled":false,"sender":"","password":"legacy-code",'
            '"recipient":"","smtp_host":"smtp.qq.com","smtp_port":"465,587"}},'
            '"app_settings":{"launch_on_startup":false}}'
        )
        config.CONFIG_FILE.write_text(legacy, encoding="utf-8")
        loaded = config.load_config()
        self.assertEqual(loaded["accounts"][0]["password"], "legacy-pw")
        self.assertEqual(loaded["notification_settings"]["pushplus"]["token"], "legacy-tok")
        self.assertEqual(loaded["notification_settings"]["qq_mail"]["password"], "legacy-code")


class SessionCookieEncryptionTests(unittest.TestCase):
    """cookie 文件加密往返（仅 Windows）。"""

    def setUp(self):
        if not secrets.is_supported():
            self.skipTest("DPAPI only available on Windows")
        from xmu_rollcall import utils
        import requests
        self._tmpdir = tempfile.mkdtemp(prefix="xmu_cookie_test_")
        self._path = str(Path(self._tmpdir) / "1.json")
        self.utils = utils
        self.requests = requests

    def tearDown(self):
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def test_save_and_load_session_roundtrip(self):
        utils = self.utils
        sess = self.requests.Session()
        sess.cookies.set("session", "cookie-value-123")
        utils.save_session(sess, self._path)

        raw = open(self._path, encoding="utf-8").read()
        self.assertNotIn("cookie-value-123", raw)
        self.assertIn("dpapi:", raw)

        loaded = self.requests.Session()
        self.assertTrue(utils.load_session(loaded, self._path))
        self.assertEqual(loaded.cookies.get("session"), "cookie-value-123")


class SessionCloneIsolationTests(unittest.TestCase):
    """clone_session 的 cookiejar 独立性（A2）。"""

    def test_clone_does_not_share_cookiejar(self):
        import requests
        from xmu_rollcall import utils
        src = requests.Session()
        src.cookies.set("session", "src-value")
        clone = utils.clone_session(src)
        # 写 clone 不影响 src
        clone.cookies.set("session", "clone-value")
        self.assertEqual(src.cookies.get("session"), "src-value")
        self.assertEqual(clone.cookies.get("session"), "clone-value")
        # 写 src 不影响 clone
        src.cookies.set("other", "x")
        self.assertIsNone(clone.cookies.get("other"))


class AutoAnswerDelayTests(unittest.TestCase):
    """compute_auto_answer_delay 截止保护与配置消费（A3 第一期）。"""

    def _settings(self, number_min=10, number_max=30, radar_min=0, radar_max=0):
        return {
            "number_delay_min": number_min, "number_delay_max": number_max,
            "radar_delay_min": radar_min, "radar_delay_max": radar_max,
        }

    def test_disabled_when_max_zero(self):
        from xmu_rollcall import utils
        self.assertEqual(utils.compute_auto_answer_delay("数字签到", self._settings(number_min=0, number_max=0), 600), 0.0)

    def test_delay_within_range_when_enough_remaining(self):
        from xmu_rollcall import utils
        for _ in range(50):
            delay = utils.compute_auto_answer_delay("数字签到", self._settings(10, 30), 600)
            self.assertGreaterEqual(delay, 10.0)
            self.assertLessEqual(delay, 30.0)

    def test_deadline_protection_skips_delay(self):
        # 报告 A3 要求：剩余 5s + 配置延迟 10-30s → 立即提交（0）
        from xmu_rollcall import utils
        self.assertEqual(utils.compute_auto_answer_delay("数字签到", self._settings(10, 30), 5), 0.0)
        # 剩余 15：触发条件 15 <= delay + 10 ⇔ delay >= 5，而最小延迟 10 >= 5 恒成立，
        # 与 delay 随机取值无关，必为立即提交 —— 无需"某次 delay"的前提
        self.assertEqual(utils.compute_auto_answer_delay("数字签到", self._settings(10, 30), 15), 0.0)

    def test_radar_delay_applied(self):
        from xmu_rollcall import utils
        delay = utils.compute_auto_answer_delay("雷达签到", self._settings(0, 0, 5, 8), 600)
        self.assertGreaterEqual(delay, 5.0)
        self.assertLessEqual(delay, 8.0)

    def test_manual_answer_unaffected_by_settings(self):
        # 手动应答在 app 层传 auto=False 直接返回 0（不经此函数）；此处仅验证函数对未知类型返回 0
        from xmu_rollcall import utils
        self.assertEqual(utils.compute_auto_answer_delay("二维码签到", self._settings(), 600), 0.0)


if __name__ == "__main__":
    unittest.main()
