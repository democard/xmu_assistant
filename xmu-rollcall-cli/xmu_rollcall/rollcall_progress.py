"""TronClass 单次点名进度的纯解析逻辑。

本模块不发网络请求，也不参与签到提交。默认按明细接口返回的名单统计，
与参考项目一致；调用方明确只传部分名单时，须设置 roster_complete=False。
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RollcallProgress:
    observed: int = 0
    present: int = 0
    absent: int = 0
    unknown: int = 0
    invalid_rows: int = 0
    duplicate_rows: int = 0
    roster_complete: bool = False
    own_present: bool | None = None

    @property
    def rate_percent(self) -> float | None:
        """只对非空、完整且状态明确的名单给出班级签到比例。"""
        if not self.roster_complete or not self.observed or self.unknown:
            return None
        return self.present * 100 / self.observed


def classify_attendance_entry(entry: dict) -> bool | None:
    """返回 True(已签)、False(缺勤) 或 None(未知/字段冲突)。"""
    values: list[bool] = []
    for key in ("status", "rollcall_status", "student_rollcall_status"):
        raw = entry.get(key)
        if raw is None or raw == "":
            continue
        if not isinstance(raw, str):
            return None
        status = raw.strip().lower()
        if status in {"on_call", "on_call_fine"}:
            values.append(True)
        elif status == "absent":
            values.append(False)
        else:
            return None
    return values[0] if values and all(value == values[0] for value in values) else None


def summarize_rollcall_progress(
    payload: object,
    *,
    roster_complete: bool = True,
    my_user_no: str = "",
) -> RollcallProgress:
    """汇总 ``student_rollcalls``；不把顶层活动状态冒充个人签到结果。"""
    if not isinstance(payload, dict) or not isinstance(payload.get("student_rollcalls"), list):
        return RollcallProgress()

    statuses: list[bool | None] = []
    identities: dict[str, int] = {}
    own_statuses: list[bool | None] = []
    invalid_rows = duplicate_rows = 0
    target = my_user_no.strip().lower()

    for entry in payload["student_rollcalls"]:
        if not isinstance(entry, dict):
            invalid_rows += 1
            continue
        status = classify_attendance_entry(entry)
        raw_identity = entry.get("user_no")
        identity = raw_identity.strip().lower() if isinstance(raw_identity, str) else ""
        if target and identity == target:
            own_statuses.append(status)
        if identity and identity in identities:
            duplicate_rows += 1
            position = identities[identity]
            if statuses[position] != status:
                statuses[position] = None
            continue
        if identity:
            identities[identity] = len(statuses)
        statuses.append(status)

    own_present = None
    if own_statuses and all(value == own_statuses[0] for value in own_statuses):
        own_present = own_statuses[0]

    return RollcallProgress(
        observed=len(statuses),
        present=sum(value is True for value in statuses),
        absent=sum(value is False for value in statuses),
        unknown=sum(value is None for value in statuses),
        invalid_rows=invalid_rows,
        duplicate_rows=duplicate_rows,
        roster_complete=(
            roster_complete
            and not invalid_rows
            and not duplicate_rows
        ),
        own_present=own_present,
    )
