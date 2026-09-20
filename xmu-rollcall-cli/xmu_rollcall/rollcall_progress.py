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
    leave: int = 0
    own_leave: bool = False
    own_status_ambiguous: bool = False
    late: int = 0

    @property
    def rate_percent(self) -> float | None:
        """只对非空且结构完整的名单给出明确已签比例。"""
        if not self.roster_complete or not self.observed:
            return None
        return self.present * 100 / self.observed

    @property
    def reliable(self) -> bool:
        """名单可用于自动决策：结构完整且非空。"""
        return self.roster_complete and self.observed > 0


def wait_before_answer_satisfied(
    progress: RollcallProgress,
    mode: str,
    *,
    count: int = 5,
    percent: int = 15,
) -> bool:
    """判断自动签到人数门槛；空或结构不完整名单一律不达标。

    百分比使用交叉相乘，避免先取整导致 5/40 被错误当成 15%。
    ``none`` 表示关闭新增门槛，保持原自动处理行为。
    """
    if mode == "none":
        return True
    if not progress.reliable:
        return False
    if mode == "count":
        return progress.present >= max(1, int(count))
    if mode == "percent":
        threshold = min(100, max(1, int(percent)))
        return progress.present * 100 >= progress.observed * threshold
    return False


def classify_attendance_entry(entry: dict) -> bool | None:
    """返回 True(已签)、False(缺勤) 或 None(未知/字段冲突)。"""
    status = _classify_attendance_entry(entry)
    if status == "present":
        return True
    if status == "absent":
        return False
    return None


def _classify_attendance_entry(entry: dict) -> str:
    """严格合并本人状态；请假对旧的布尔接口仍表现为 None。"""
    values: list[str] = []
    for key in ("status", "rollcall_status", "student_rollcall_status"):
        raw = entry.get(key)
        if raw is None or raw == "":
            continue
        if not isinstance(raw, str):
            return "unknown"
        status = raw.strip().lower()
        if status in {"on_call", "on_call_fine", "late", "on_call_arrive_late"}:
            values.append("present")
        elif status == "absent":
            values.append("absent")
        elif status in {
            "on_leave", "on_personal_leave", "on_sick_leave", "on_public_leave",
        }:
            values.append("leave")
        else:
            return "unknown"
    return values[0] if values and all(value == values[0] for value in values) else "unknown"


def _classify_progress_entry(entry: dict) -> str | None:
    """按详情字段优先级分类班级进度；None 表示该行没有可用字符串状态。"""
    raw = next(
        (
            value.strip().lower()
            for key in ("rollcall_status", "student_rollcall_status", "status")
            if isinstance((value := entry.get(key)), str)
            and value.strip() and value.strip().lower() != "null"
        ),
        None,
    )
    if raw is None:
        return None
    if raw in {"on_call_fine", "on_call"}:
        return "present"
    if raw == "absent":
        return "absent"
    if raw in {"late", "on_call_arrive_late"}:
        return "late"
    if raw in {"on_leave", "on_personal_leave", "on_sick_leave", "on_public_leave"}:
        return "leave"
    return "unknown"


def summarize_rollcall_progress(
    payload: object,
    *,
    roster_complete: bool = True,
    my_user_no: str = "",
) -> RollcallProgress:
    """汇总 ``student_rollcalls``；不把顶层活动状态冒充个人签到结果。"""
    if not isinstance(payload, dict) or not isinstance(payload.get("student_rollcalls"), list):
        return RollcallProgress()

    statuses: list[str] = []
    identities: dict[str, int] = {}
    own_statuses: list[str] = []
    invalid_rows = duplicate_rows = 0
    target = my_user_no.strip().lower()

    for entry in payload["student_rollcalls"]:
        if not isinstance(entry, dict):
            invalid_rows += 1
            continue
        status = _classify_progress_entry(entry)
        if status is None:
            invalid_rows += 1
            status = "unknown"
        own_status = _classify_attendance_entry(entry)
        raw_identity = entry.get("user_no")
        identity = raw_identity.strip().lower() if isinstance(raw_identity, str) else ""
        if (
            (target and identity == target)
            or entry.get("is_current_user") is True
            or entry.get("is_self") is True
        ):
            own_statuses.append(own_status)
        if identity and identity in identities:
            duplicate_rows += 1
            position = identities[identity]
            if statuses[position] != status:
                statuses[position] = "unknown"
            continue
        if identity:
            identities[identity] = len(statuses)
        statuses.append(status)

    own_present = None
    own_leave = False
    own_status_ambiguous = False
    if own_statuses and all(value == own_statuses[0] for value in own_statuses):
        if own_statuses[0] == "present":
            own_present = True
        elif own_statuses[0] == "absent":
            own_present = False
        elif own_statuses[0] == "leave":
            own_leave = True
        else:
            own_status_ambiguous = True
    elif own_statuses:
        own_status_ambiguous = True

    return RollcallProgress(
        observed=len(statuses),
        present=statuses.count("present"),
        absent=statuses.count("absent"),
        unknown=statuses.count("unknown"),
        invalid_rows=invalid_rows,
        duplicate_rows=duplicate_rows,
        roster_complete=(
            roster_complete
            and not invalid_rows
            and not duplicate_rows
        ),
        own_present=own_present,
        leave=statuses.count("leave"),
        own_leave=own_leave,
        own_status_ambiguous=own_status_ambiguous,
        late=statuses.count("late"),
    )
