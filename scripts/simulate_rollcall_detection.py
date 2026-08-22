from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.core import build_rollcall_event  # noqa: E402


SAMPLE_LOG = """
[2026-07-15 10:00:01] 检测到数字签到：课程：高等数学，剩余时间：未知
[2026-07-15 10:00:02] 检测到雷达签到：课程：大学物理，剩余时间：未知
[2026-07-15 10:00:03] 检测到二维码签到：课程：大学英语，二维码只提醒
""".strip()


def classify_line(line: str) -> str | None:
    if re.search(r"(二维码|二維碼|QR\s*code|qrcode|qr)", line, re.IGNORECASE):
        return "qr"
    if re.search(r"(雷达|雷達|radar)", line, re.IGNORECASE):
        return "radar"
    if re.search(r"(数字|數字|number)", line, re.IGNORECASE):
        return "number"
    return None


def extract_course(line: str) -> str:
    patterns = (
        r"(?:课程|課程|course)\s*[:：=]\s*([^,，;；\]\[]+)",
        r"(?:签到|簽到)\s*[:：]\s*([^,，;；\]\[]+)",
    )
    for pattern in patterns:
        match = re.search(pattern, line, re.IGNORECASE)
        if match:
            return match.group(1).strip()
    return "日志模拟课程"


def extract_rollcall_id(line: str, index: int) -> str:
    # \b 词边界：裸 "id" 不加边界会把 "valid:"/"mid=" 等任意含 id 的文本误匹配为签到 id
    match = re.search(r"\b(?:rollcall_id|rollcall|id)\s*[:：=]\s*([A-Za-z0-9_-]+)", line, re.IGNORECASE)
    if match:
        return match.group(1)
    return f"log-{index:03d}"


def replay_rollcall_log(text: str):
    events = []
    for line in text.splitlines():
        rollcall_kind = classify_line(line)
        if not rollcall_kind:
            continue
        index = len(events) + 1
        payload = {
            "rollcall_id": extract_rollcall_id(line, index),
            "course_title": extract_course(line),
            "created_by_name": "log replay",
            "status": "simulated",
            "is_number": rollcall_kind == "number",
            "is_radar": rollcall_kind == "radar",
        }
        events.append(build_rollcall_event(payload))
    return events


def event_to_dict(event) -> dict:
    return {
        "id": event.rollcall_id,
        "course": event.course_title,
        "type": event.rollcall_type,
        "remaining": event.remaining_text,
        "simulated_action": "auto-answer" if event.raw.get("is_number") or event.raw.get("is_radar") else "notify-only",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Replay copied XMU rollcall log text as simulated detection events.")
    parser.add_argument("log_file", nargs="?", help="Text file copied from the old dashboard log window.")
    parser.add_argument("--json", action="store_true", help="Print JSON instead of a short text report.")
    args = parser.parse_args()

    text = Path(args.log_file).read_text(encoding="utf-8") if args.log_file else SAMPLE_LOG
    events = replay_rollcall_log(text)
    data = [event_to_dict(event) for event in events]

    if args.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0

    print(f"detected={len(data)}")
    for item in data:
        print(f"{item['id']} | {item['course']} | {item['type']} | {item['simulated_action']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
