"""Rollcall 领域模型与共享常量（中立模块，无 UI/会话层依赖）。

C2 重构产物：把曾位于 desktop_qt/core.py 的签到事件模型与解析函数抽到这里，
使 xmu_rollcall/engine.py 不再反向依赖桌面 UI 层（desktop_qt.core），
从而解除 `engine ↔ desktop_qt/core` 循环导入——MonitorWorker 得以直接复用
engine.RollcallEngine 的轮询逻辑，轮询「拉取→解析→去重」只保留一份实现。

desktop_qt/core.py 从本模块再导出（re-export）这些符号，既有导入方（app.py、
engine.py、tests/test_notifications.py）无需改动。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime

from .utils import base_url


ROLLCALLS_URL = f"{base_url}/api/radar/rollcalls"


@dataclass
class RollcallEvent:
    rollcall_id: str
    course_title: str
    teacher: str
    rollcall_type: str
    status: str
    raw: dict
    detected_at: float = field(default_factory=time.time)
    result: str = "待处理"
    detail: str = ""
    number_code: str = ""
    deadline: str = ""
    remaining_seconds: int | None = None

    @property
    def remaining_text(self) -> str:
        if self.remaining_seconds is None:
            return "未知"
        if self.remaining_seconds <= 0:
            return "已截止"
        return format_duration(self.remaining_seconds)


def build_rollcall_event(rollcall: dict) -> RollcallEvent:
    if rollcall.get("is_radar"):
        rollcall_type = "雷达签到"
    elif rollcall.get("is_number"):
        rollcall_type = "数字签到"
    else:
        rollcall_type = "二维码签到"

    teacher_parts = [
        str(rollcall.get("department_name") or "").strip(),
        str(rollcall.get("created_by_name") or "").strip(),
    ]
    teacher = " ".join(part for part in teacher_parts if part) or "未知"

    deadline = str(first_value(
        rollcall,
        ("deadline", "end_time", "expired_at", "expire_at", "expires_at", "rollcall_end_time"),
        "",
    ))

    return RollcallEvent(
        rollcall_id=str(rollcall.get("rollcall_id", "")),
        course_title=str(rollcall.get("course_title") or rollcall.get("course_name") or "未知课程"),
        teacher=teacher,
        rollcall_type=rollcall_type,
        status=str(rollcall.get("status") or "unknown"),
        raw=rollcall,
        deadline=deadline,
        remaining_seconds=remaining_seconds_from_deadline(deadline),
    )


def remaining_seconds_from_deadline(deadline: str) -> int | None:
    if not deadline:
        return None
    text = str(deadline).strip()
    for candidate in (text, text[:19]):
        try:
            parsed = datetime.fromisoformat(candidate.replace("Z", "+00:00"))
            now = datetime.now(parsed.tzinfo) if parsed.tzinfo else datetime.now()
            return max(0, int((parsed - now).total_seconds()))
        except ValueError:
            continue
    return None


def first_value(data: dict, keys: tuple[str, ...], default=""):
    for key in keys:
        value = data.get(key)
        if value not in (None, ""):
            return value
    return default


def format_duration(seconds: int) -> str:
    hours = seconds // 3600
    minutes = (seconds % 3600) // 60
    secs = seconds % 60
    if hours:
        return f"{hours}h {minutes}m {secs}s"
    if minutes:
        return f"{minutes}m {secs}s"
    return f"{secs}s"
