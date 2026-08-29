"""Notification helpers for xmu助手."""

from __future__ import annotations

import smtplib
from dataclasses import dataclass
from email.message import EmailMessage

import requests

from .utils import API_TIMEOUT, SessionExpiredError


@dataclass
class NotificationMessage:
    title: str
    body: str
    action_url: str = ""


def build_rollcall_notification(event, action_url: str = "") -> NotificationMessage:
    remaining = event.remaining_text if getattr(event, "remaining_text", "") else "未知"
    lines = [
        f"课程：{event.course_title}",
        f"类型：{event.rollcall_type}",
        f"剩余：{remaining}",
        f"状态：{event.result}",
    ]
    if action_url:
        lines.append(f"打开：{action_url}")
    return NotificationMessage("xmu助手 签到提醒", "\n".join(lines), action_url)


def notification_provider_status(settings: dict) -> dict[str, str]:
    pushplus = settings.get("pushplus", {})
    qq_mail = settings.get("qq_mail", {})
    return {
        "system": "已配置" if settings.get("system", {}).get("enabled") else "未开启",
        "pushplus": _enabled_status(pushplus, ("token",)),
        "qq_mail": _enabled_status(qq_mail, ("sender", "password", "recipient", "smtp_host", "smtp_port")),
    }


def _enabled_status(settings: dict, required_keys: tuple[str, ...]) -> str:
    if not settings.get("enabled"):
        return "未开启"
    if all(str(settings.get(key, "")).strip() for key in required_keys):
        return "已配置"
    return "缺少信息"


def friendly_error_message(error, area: str = "general") -> str:
    # 类型优先：会话过期已在源头类型化（SessionExpiredError），直接给出可操作提示
    if isinstance(error, SessionExpiredError):
        return "登录已过期，请重新登录。"
    text = str(error or "").strip()
    lowered = text.lower()
    if any(keyword in lowered for keyword in ("timeout", "timed out", "connection", "network", "dns", "proxy")):
        return "网络连接失败，请稍后重试。"
    if any(keyword in lowered for keyword in ("401", "403", "unauthorized", "forbidden", "permission", "登录已过期", "登录态已失效", "sessionexpired")):
        return "权限不足或登录已过期，请重新登录。"
    if any(keyword in lowered for keyword in ("404", "not found")):
        return "平台没有提供这个资源，或资源地址已失效。"
    if any(keyword in lowered for keyword in ("token", "令牌")) or area == "pushplus":
        return "Token 可能不正确，请检查是否复制完整。"
    if any(keyword in lowered for keyword in ("smtp", "auth", "login", "password", "qq mail")) or area == "qq_mail":
        return "QQ 邮箱授权码或 SMTP 配置可能有误，请确认使用授权码而不是 QQ 密码。"
    if any(keyword in lowered for keyword in ("empty", "incomplete", "missing", "缺少")):
        return "缺少必要信息，请补齐后再试。"
    if area == "login":
        return "登录失败，请检查账号密码或稍后重试。"
    if area == "courseware":
        return "当前无法读取或下载该课件，请稍后重试或重新登录。"
    return "操作失败，请稍后重试。"


class PushPlusNotifier:
    def __init__(self, token: str, session=None):
        self.token = token
        # 不为每次发送新建 Session：send_with_settings 每发一次就实例化一次，
        # 自建 Session 从不 close 会持续泄漏连接；无调用方传入时用 requests.post 直发
        self.session = session

    def send(self, title: str, body: str) -> None:
        if not self.token:
            raise ValueError("PushPlus token is empty")
        poster = self.session.post if self.session is not None else requests.post
        response = poster(
            "https://www.pushplus.plus/send",
            json={"token": self.token, "title": title, "content": body, "template": "txt"},
            timeout=API_TIMEOUT,
        )
        response.raise_for_status()
        # PushPlus 的失败约定是 HTTP 200 + body {"code":500,"msg":...}（token
        # 非法/限流均如此）：只看状态码会把失败当成功，签到提醒静默丢失。
        # 非 JSON body（网关异常页等）无法判读，维持不拦截。
        try:
            payload = response.json()
        except ValueError:
            return
        if isinstance(payload, dict):
            code = payload.get("code")
            if code is not None and str(code) != "200":
                raise RuntimeError(f"PushPlus 发送失败：{payload.get('msg') or code}")


class QQMailNotifier:
    def __init__(
        self,
        sender: str,
        password: str,
        recipient: str,
        smtp_host: str = "smtp.qq.com",
        smtp_port: int | str | list[int] | tuple[int, ...] = "465,587",
    ):
        self.sender = sender
        self.password = password
        self.recipient = recipient
        self.smtp_host = smtp_host
        self.smtp_port = smtp_port

    def _ports(self) -> list[int]:
        values = self.smtp_port if isinstance(self.smtp_port, (list, tuple)) else str(self.smtp_port).split(",")
        ports: list[int] = []
        for value in values:
            try:
                port = int(str(value).strip())
            except (TypeError, ValueError):
                continue
            if port and port not in ports:
                ports.append(port)
        return ports or [465, 587]

    def send(self, title: str, body: str) -> None:
        if not (self.sender and self.password and self.recipient):
            raise ValueError("QQ mail settings are incomplete")
        message = EmailMessage()
        message["Subject"] = title
        message["From"] = self.sender
        message["To"] = self.recipient
        message.set_content(body)

        errors: list[str] = []
        for port in self._ports():
            try:
                if port == 465:
                    with smtplib.SMTP_SSL(self.smtp_host, port, timeout=15) as smtp:
                        smtp.login(self.sender, self.password)
                        smtp.send_message(message)
                else:
                    with smtplib.SMTP(self.smtp_host, port, timeout=15) as smtp:
                        smtp.starttls()
                        smtp.login(self.sender, self.password)
                        smtp.send_message(message)
                return
            except Exception as exc:
                errors.append(f"{port}: {exc}")
        raise RuntimeError("；".join(errors))


def send_with_settings(settings: dict, message: NotificationMessage) -> list[str]:
    errors: list[str] = []
    pushplus = settings.get("pushplus", {})
    if pushplus.get("enabled"):
        try:
            PushPlusNotifier(pushplus.get("token", "")).send(message.title, message.body)
        except Exception as exc:
            errors.append(f"微信通知失败：{friendly_error_message(exc, 'pushplus')}")

    qq_mail = settings.get("qq_mail", {})
    if qq_mail.get("enabled"):
        try:
            QQMailNotifier(
                sender=qq_mail.get("sender", ""),
                password=qq_mail.get("password", ""),
                recipient=qq_mail.get("recipient", ""),
                smtp_host=qq_mail.get("smtp_host", "smtp.qq.com"),
                smtp_port=qq_mail.get("smtp_port", "465,587"),
            ).send(message.title, message.body)
        except Exception as exc:
            errors.append(f"QQ通知失败：{friendly_error_message(exc, 'qq_mail')}")
    return errors
