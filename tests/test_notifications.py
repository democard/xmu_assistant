from __future__ import annotations

import smtplib
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.config import normalize_notification_settings  # noqa: E402
from xmu_rollcall.desktop_qt.core import build_rollcall_event  # noqa: E402
from xmu_rollcall.notifications import (  # noqa: E402
    QQMailNotifier,
    PushPlusNotifier,
    build_rollcall_notification,
    friendly_error_message,
    notification_provider_status,
)


class FakeResponse:
    status_code = 200

    def raise_for_status(self):
        return None

    def json(self):
        # PushPlus 成功约定：HTTP 200 + {"code":200}
        return {"code": 200}


class FakeSession:
    def __init__(self):
        self.calls = []

    def post(self, url, json=None, timeout=None):
        self.calls.append((url, json, timeout))
        return FakeResponse()


class FakeSMTP:
    instances = []

    def __init__(self, host, port, timeout=None):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.started_tls = False
        self.login_args = None
        self.messages = []
        FakeSMTP.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def starttls(self):
        self.started_tls = True

    def login(self, username, password):
        self.login_args = (username, password)

    def send_message(self, message):
        self.messages.append(message)


class FakeSMTPSSL(FakeSMTP):
    fail_init = False

    def __init__(self, host, port, timeout=None):
        if self.fail_init:
            raise TimeoutError("465 timed out")
        super().__init__(host, port, timeout)

    def starttls(self):
        raise AssertionError("SSL port must not call starttls")


class NotificationTests(unittest.TestCase):
    def test_normalize_notification_settings_defaults_and_provider_config(self):
        settings = normalize_notification_settings(
            {
                "pushplus": {"enabled": True, "token": " token "},
                "qq_mail": {"enabled": True, "sender": "sender@example.invalid", "password": "pw", "recipient": "recipient@example.invalid"},
            }
        )

        self.assertTrue(settings["system"]["enabled"])
        self.assertTrue(settings["pushplus"]["enabled"])
        self.assertEqual(settings["pushplus"]["token"], "token")
        self.assertTrue(settings["qq_mail"]["enabled"])
        self.assertEqual(settings["qq_mail"]["smtp_host"], "smtp.qq.com")
        self.assertEqual(settings["qq_mail"]["smtp_port"], "465,587")

    def test_notification_provider_status_marks_missing_information(self):
        statuses = notification_provider_status(
            normalize_notification_settings(
                {
                    "system": {"enabled": False},
                    "pushplus": {"enabled": True, "token": ""},
                    "qq_mail": {"enabled": True, "sender": "sender@example.invalid", "password": "", "recipient": "recipient@example.invalid"},
                }
            )
        )

        self.assertEqual(statuses["system"], "未开启")
        self.assertEqual(statuses["pushplus"], "缺少信息")
        self.assertEqual(statuses["qq_mail"], "缺少信息")

    def test_friendly_error_message_translates_common_failures(self):
        self.assertIn("Token", friendly_error_message("invalid token", "pushplus"))
        self.assertIn("授权码", friendly_error_message("SMTP authentication failed", "qq_mail"))
        self.assertIn("网络连接失败", friendly_error_message("Connection timed out"))
        self.assertIn("登录已过期", friendly_error_message("403 Forbidden"))
        self.assertIn("资源地址已失效", friendly_error_message("404 Client Error: NOT FOUND"))

    def test_build_rollcall_event_exposes_deadline_and_remaining_seconds(self):
        event = build_rollcall_event(
            {
                "rollcall_id": "r1",
                "course_title": "数学",
                "is_number": True,
                "status": "absent",
                "deadline": "2099-01-01T00:00:00+08:00",
            }
        )

        self.assertEqual(event.deadline, "2099-01-01T00:00:00+08:00")
        self.assertGreater(event.remaining_seconds, 0)
        self.assertIn("数学", build_rollcall_notification(event, "xmurollcall://rollcall/r1").body)

    def test_pushplus_notifier_sends_rollcall_message(self):
        session = FakeSession()
        notifier = PushPlusNotifier("token", session=session)

        notifier.send("签到提醒", "课程：数学")

        self.assertEqual(session.calls[0][0], "https://www.pushplus.plus/send")
        self.assertEqual(session.calls[0][1]["token"], "token")
        self.assertEqual(session.calls[0][1]["title"], "签到提醒")

    def test_pushplus_body_code_failure_raises(self):
        class BodyResponse:
            status_code = 200

            def raise_for_status(self):
                return None

            def json(self):
                # PushPlus 失败约定：HTTP 200 + code!=200（token 非法/限流）
                return {"code": 500, "msg": "token非法"}

        class BodySession:
            def post(self, url, json=None, timeout=None):
                return BodyResponse()

        notifier = PushPlusNotifier("token", session=BodySession())
        with self.assertRaises(RuntimeError) as ctx:
            notifier.send("签到提醒", "课程：数学")
        self.assertIn("token非法", str(ctx.exception))

    def test_pushplus_non_json_body_still_succeeds(self):
        class HtmlResponse:
            status_code = 200

            def raise_for_status(self):
                return None

            def json(self):
                raise ValueError("no json")

        class HtmlSession:
            def post(self, url, json=None, timeout=None):
                return HtmlResponse()

        # 网关异常页等非 JSON body 无法判读，维持不拦截（零行为变化锁定）
        PushPlusNotifier("token", session=HtmlSession()).send("签到提醒", "课程：数学")

    def test_qq_mail_notifier_sends_mail_with_tls(self):
        with patch.object(smtplib, "SMTP", FakeSMTP):
            notifier = QQMailNotifier(
                sender="sender@example.invalid",
                password="fixture-mail-password",
                recipient="recipient@example.invalid",
                smtp_host="smtp.qq.com",
                smtp_port=587,
            )
            notifier.send("签到提醒", "课程：数学")

        smtp = FakeSMTP.instances[-1]
        self.assertTrue(smtp.started_tls)
        self.assertEqual(smtp.login_args, ("sender@example.invalid", "fixture-mail-password"))
        self.assertEqual(smtp.messages[0]["To"], "recipient@example.invalid")

    def test_qq_mail_notifier_tries_multiple_ports_until_success(self):
        FakeSMTP.instances.clear()
        FakeSMTPSSL.fail_init = True

        with patch.object(smtplib, "SMTP_SSL", FakeSMTPSSL), patch.object(smtplib, "SMTP", FakeSMTP):
            notifier = QQMailNotifier(
                sender="sender@example.invalid",
                password="fixture-mail-password",
                recipient="recipient@example.invalid",
                smtp_host="smtp.qq.com",
                smtp_port="465,587",
            )
            notifier.send("签到提醒", "课程：数学")

        smtp = FakeSMTP.instances[-1]
        self.assertEqual(smtp.port, 587)
        self.assertTrue(smtp.started_tls)
        self.assertEqual(smtp.messages[0]["Subject"], "签到提醒")


if __name__ == "__main__":
    unittest.main()
