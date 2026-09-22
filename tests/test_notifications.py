from __future__ import annotations

import smtplib
import ssl
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


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

    def __init__(self, host, port, timeout=None, context=None):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.tls_context = context
        self.started_tls = False
        self.login_args = None
        self.messages = []
        FakeSMTP.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def starttls(self, context=None):
        self.tls_context = context
        self.started_tls = True

    def login(self, username, password):
        self.login_args = (username, password)

    def send_message(self, message):
        self.messages.append(message)


class FakeSMTPSSL(FakeSMTP):
    fail_init = False

    def __init__(self, host, port, timeout=None, context=None):
        if self.fail_init:
            raise TimeoutError("465 timed out")
        super().__init__(host, port, timeout, context)

    def starttls(self, context=None):
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

    def test_build_rollcall_event_does_not_turn_a_null_id_into_the_string_none(self):
        # 平台给 rollcall_id: null 时 str(None) 会产出字面 "None"：它非空，于是被当成
        # 合法事件进轮询（去重后后续 null 事件被静默吞掉），自动应答还会去打
        # /api/rollcall/None（必 404）。空值必须归一为 ""。
        for raw in (None, "", "   "):
            with self.subTest(rollcall_id=raw):
                event = build_rollcall_event({"rollcall_id": raw, "course_title": "数学"})

                self.assertNotEqual(event.rollcall_id, "None")
                self.assertFalse(event.rollcall_id.strip())

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

    def test_pushplus_non_json_body_does_not_confirm_acceptance(self):
        class HtmlResponse:
            status_code = 200

            def raise_for_status(self):
                return None

            def json(self):
                raise ValueError("no json")

        class HtmlSession:
            def post(self, url, json=None, timeout=None):
                return HtmlResponse()

        with self.assertRaisesRegex(RuntimeError, "无法确认通知请求是否已受理"):
            PushPlusNotifier("token", session=HtmlSession()).send("签到提醒", "课程：数学")

    def test_pushplus_malformed_ack_is_rejected_without_resending(self):
        for payload in ({}, {"code": None}, [], {"code": True}, {"code": 200.5}):
            with self.subTest(payload=payload):
                session = Mock()
                session.post.return_value.json.return_value = payload
                with self.assertRaises(RuntimeError):
                    PushPlusNotifier("fixture-token", session=session).send("test", "test")
                self.assertEqual(session.post.call_count, 1)

    def test_pushplus_string_business_code_is_accepted(self):
        session = Mock()
        session.post.return_value.json.return_value = {"code": "200"}
        PushPlusNotifier("fixture-token", session=session).send("test", "test")
        self.assertEqual(session.post.call_count, 1)

    def test_unknown_pushplus_ack_does_not_blame_user_token(self):
        message = friendly_error_message(
            RuntimeError("PushPlus 返回异常，无法确认通知请求是否已受理"), "pushplus",
        )
        self.assertIn("无法确认", message)
        self.assertNotIn("Token", message)

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
        # STARTTLS 必须带校验上下文（smtplib 默认上下文 verify_mode=CERT_NONE，不验证证书）
        self.assertIsNotNone(smtp.tls_context)
        self.assertEqual(smtp.tls_context.verify_mode, ssl.CERT_REQUIRED)
        self.assertEqual(smtp.login_args, ("sender@example.invalid", "fixture-mail-password"))
        self.assertEqual(smtp.messages[0]["To"], "recipient@example.invalid")

    def test_qq_mail_ssl_port_passes_a_verifying_tls_context(self):
        FakeSMTPSSL.fail_init = False  # 上一个用例的 fail_init 不得影响本用例
        with patch.object(smtplib, "SMTP_SSL", FakeSMTPSSL):
            notifier = QQMailNotifier(
                sender="sender@example.invalid",
                password="fixture-mail-password",
                recipient="recipient@example.invalid",
                smtp_host="smtp.qq.com",
                smtp_port=465,
            )
            notifier.send("签到提醒", "课程：数学")

        smtp_ssl = FakeSMTPSSL.instances[-1]
        self.assertFalse(smtp_ssl.started_tls)  # 465 不得再走 starttls
        self.assertIsNotNone(smtp_ssl.tls_context)
        self.assertEqual(smtp_ssl.tls_context.verify_mode, ssl.CERT_REQUIRED)
        self.assertEqual(smtp_ssl.login_args, ("sender@example.invalid", "fixture-mail-password"))

    def test_qq_mail_notifier_tries_multiple_ports_until_success(self):
        FakeSMTP.instances.clear()
        was_failing = FakeSMTPSSL.fail_init
        FakeSMTPSSL.fail_init = True
        self.addCleanup(setattr, FakeSMTPSSL, "fail_init", was_failing)  # 不向后续用例泄漏

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

    def test_qq_mail_rejects_invalid_explicit_smtp_port_without_connecting(self):
        notifier = QQMailNotifier(
            sender="sender@example.invalid",
            password="fixture-mail-password",
            recipient="recipient@example.invalid",
            smtp_port="not-a-port",
        )
        with patch.object(smtplib, "SMTP_SSL") as smtp_ssl, patch.object(smtplib, "SMTP") as smtp:
            with self.assertRaisesRegex(ValueError, "SMTP port is invalid"):
                notifier.send("签到提醒", "课程：数学")
        smtp_ssl.assert_not_called()
        smtp.assert_not_called()

    def test_qq_mail_rejects_out_of_range_explicit_smtp_port_without_connecting(self):
        notifier = QQMailNotifier(
            sender="sender@example.invalid",
            password="fixture-mail-password",
            recipient="recipient@example.invalid",
            smtp_port="0,65536",
        )
        with patch.object(smtplib, "SMTP_SSL") as smtp_ssl, patch.object(smtplib, "SMTP") as smtp:
            with self.assertRaisesRegex(ValueError, "SMTP port is invalid"):
                notifier.send("签到提醒", "课程：数学")
        smtp_ssl.assert_not_called()
        smtp.assert_not_called()

    def test_qq_mail_empty_port_uses_default_ssl_port(self):
        FakeSMTPSSL.fail_init = False
        FakeSMTPSSL.instances.clear()
        with patch.object(smtplib, "SMTP_SSL", FakeSMTPSSL), patch.object(smtplib, "SMTP", FakeSMTP):
            QQMailNotifier(
                sender="sender@example.invalid",
                password="fixture-mail-password",
                recipient="recipient@example.invalid",
                smtp_port="",
            ).send("签到提醒", "课程：数学")
        self.assertEqual(FakeSMTPSSL.instances[-1].port, 465)

    def test_qq_mail_mixed_ports_attempts_only_valid_ports(self):
        FakeSMTP.instances.clear()
        ssl_ports = []

        def fail_ssl(host, port, timeout=None, context=None):
            ssl_ports.append(port)
            raise TimeoutError("465 timed out")

        with patch.object(smtplib, "SMTP_SSL", side_effect=fail_ssl), patch.object(smtplib, "SMTP", FakeSMTP):
            QQMailNotifier(
                sender="sender@example.invalid",
                password="fixture-mail-password",
                recipient="recipient@example.invalid",
                smtp_port="465,garbage,0,587,65536",
            ).send("签到提醒", "课程：数学")
        self.assertEqual(ssl_ports, [465])
        self.assertEqual([smtp.port for smtp in FakeSMTP.instances], [587])


if __name__ == "__main__":
    unittest.main()
