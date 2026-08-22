"""Unified rollcall engine used by desktop and future mobile ports."""

from __future__ import annotations

from .rollcall_models import ROLLCALLS_URL, RollcallEvent, build_rollcall_event
from .utils import API_TIMEOUT, SessionExpiredError, headers, response_session_expired
from .verify import send_code, send_radar


class RollcallEngine:
    def __init__(self, session):
        self.session = session

    def poll_payload(self) -> dict:
        """拉取原始 rollcalls payload：请求 + 会话过期判定 + 非 JSON 兜底。

        C2：轮询「拉取→解析」的唯一实现（桌面监控 MonitorWorker 也复用本方法），
        不再允许 engine 与 desktop_qt/core 各自实现一份导致逻辑漂移。
        """
        # API_TIMEOUT=(6,15)：原标量 30s 会同时作用于连接与读取，服务僵死时一次
        # 轮询挂满 30s（叠加应用层重试 ≈100s 发现不了新签到）。统一口径后故障场景
        # 下的检测延迟大幅缩短。
        response = self.session.get(ROLLCALLS_URL, headers=headers, timeout=API_TIMEOUT)
        response.raise_for_status()
        # 会话过期时平台返回 302 跳身份域（requests 自动跟随成 200 登录页）：
        # 显式判定并抛类型化异常，与 Android 端对齐，避免 JSON 解析错误掩盖真实原因
        if response_session_expired(response):
            raise SessionExpiredError("登录已过期，请重新登录")
        try:
            payload = response.json()
        except ValueError as exc:
            raise RuntimeError("平台返回了非 JSON 数据（服务可能异常），请稍后重试") from exc
        return payload

    def build_events(self, payload: dict) -> list[RollcallEvent]:
        """把原始 payload 规整为事件列表（非 dict 项直接滤除）。"""
        return [
            build_rollcall_event(item)
            for item in payload.get("rollcalls", [])
            if isinstance(item, dict)
        ]

    def answer_number(self, rollcall_id: str) -> bool:
        return bool(send_code(self.session, rollcall_id))

    def answer_radar(self, rollcall_id: str) -> bool:
        return bool(send_radar(self.session, rollcall_id))

    def answer(self, rollcall_type: str, rollcall_id: str) -> bool:
        if rollcall_type == "数字签到":
            return self.answer_number(rollcall_id)
        if rollcall_type == "雷达签到":
            return self.answer_radar(rollcall_id)
        return False
