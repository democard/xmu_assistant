"""公共统计解析器的离线演示；不联网、不提交签到。

按原项目的明细名单口径计数；已知只传部分名单时用 roster_complete=False。
演示数据均为合成数据，不代表学校接口实测结果。
"""
from __future__ import annotations

import json
import sys
from dataclasses import asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.rollcall_progress import summarize_rollcall_progress


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
        progress = summarize_rollcall_progress(payload, roster_complete=complete, my_user_no="not-in-roster")
        result.append({"case": label, **asdict(progress), "rate_percent": progress.rate_percent})
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
