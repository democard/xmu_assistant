"""本次全班签到统计的离线原型；不由应用导入，不联网、不提交签到。

只验证公开字段的统计口径，不复制第三方实现。roster_complete 是模拟输入
的完整名单假设，不是已验证的平台保证；上线前须核实权限、分页及名单范围。
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class Progress:
    observed: int = 0
    present: int = 0
    absent: int = 0
    unknown: int = 0
    invalid_rows: int = 0
    duplicate_rows: int = 0
    complete: bool = False
    own_present: bool | None = None

    @property
    def percent(self) -> float | None:
        if not self.complete or not self.observed or self.unknown:
            return None
        return self.present * 100 / self.observed


def entry_status(entry: dict) -> bool | None:
    """on_call 与 on_call_fine 均明确为已签；字段冲突不擅自选择。

    不调用界面“未知显示已签”回退，也不凭顶层活动状态确认本人。
    """
    values = []
    for key in ("status", "rollcall_status", "student_rollcall_status"):
        raw = entry.get(key)
        if raw is None or raw == "":
            continue
        if not isinstance(raw, str):
            return None
        status = raw.strip()
        if status in {"on_call", "on_call_fine"}:
            values.append(True)
        elif status == "absent":
            values.append(False)
        else:
            return None
    return values[0] if values and all(v == values[0] for v in values) else None


def summarize(payload: object, *, roster_complete: bool = False,
              my_user_no: str = "") -> Progress:
    if not isinstance(payload, dict) or not isinstance(payload.get("student_rollcalls"), list):
        return Progress()
    rows = payload["student_rollcalls"]
    invalid = duplicates = 0
    statuses: list[bool | None] = []
    identities: dict[str, int] = {}
    own_statuses: list[bool | None] = []
    for entry in rows:
        if not isinstance(entry, dict):
            invalid += 1
            continue
        status = entry_status(entry)
        identity = entry.get("user_no")
        identity = identity.strip() if isinstance(identity, str) else ""
        if identity and identity == my_user_no.strip():
            own_statuses.append(status)
        if identity and identity in identities:
            duplicates += 1
            # 同一人的冲突记录不能沿用第一条“已签”。
            position = identities[identity]
            if statuses[position] != status:
                statuses[position] = None
            continue
        if identity:
            identities[identity] = len(statuses)
        statuses.append(status)
    own = None
    if own_statuses and all(value == own_statuses[0] for value in own_statuses):
        own = own_statuses[0]
    return Progress(
        observed=len(statuses),
        present=sum(value is True for value in statuses),
        absent=sum(value is False for value in statuses),
        unknown=sum(value is None for value in statuses),
        invalid_rows=invalid,
        duplicate_rows=duplicates,
        complete=roster_complete and not invalid and not duplicates,
        own_present=own,
    )


def make_roster(present: int, total: int, *, field: str = "status",
                signed_value: str = "on_call") -> dict:
    """全部为虚构身份，只构造有效的测试名单。"""
    if not 0 <= present <= total:
        raise ValueError("人数必须满足 0 <= present <= total")
    return {"student_rollcalls": [
        {"user_no": f"simulation-{i}", field: signed_value if i < present else "absent"}
        for i in range(total)
    ]}


def main() -> None:
    examples = [
        ("XMU字段：6/40", make_roster(6, 40), True),
        ("TronClass字段：6/40", make_roster(6, 40, field="rollcall_status", signed_value="on_call_fine"), True),
        ("名单完整性未确认", make_roster(6, 40), False),
        ("空名单", make_roster(0, 0), True),
        ("缺失名单", {}, True),
        ("未知状态", {"student_rollcalls": [{"status": "unknown"}]}, True),
        ("字段矛盾", {"student_rollcalls": [{"status": "absent", "rollcall_status": "on_call_fine"}]}, True),
        ("全员已签但本人不在返回名单", make_roster(2, 2), True),
    ]
    result = []
    for label, payload, complete in examples:
        progress = summarize(payload, roster_complete=complete, my_user_no="not-in-roster")
        result.append({"case": label, **asdict(progress), "percent": progress.percent})
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
