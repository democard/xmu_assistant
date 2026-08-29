"""format_log_export 导出头与行保留契约（PC 日志导出功能）。"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall import __version__  # noqa: E402
from xmu_rollcall.desktop_qt.core import format_log_export  # noqa: E402


def test_header_contains_time_version_and_count():
    text = format_log_export(["[2026-08-27 10:00:00] 启动监控", "[2026-08-27 10:00:05] 登录成功"])
    lines = text.splitlines()
    assert lines[0] == "xmu助手 运行日志导出"
    assert re.match(r"导出时间：\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$", lines[1])
    assert lines[2] == f"应用版本：v{__version__}"
    assert lines[3] == "日志条数：2"
    # 分隔线之后正文逐行保留
    body = lines[lines.index("=" * 32) + 1 :]
    assert body == [
        "[2026-08-27 10:00:00] 启动监控",
        "[2026-08-27 10:00:05] 登录成功",
    ]


def test_lines_are_kept_verbatim_without_reordering():
    logs = ["[a] c", "[b] a", "[c] b"]
    text = format_log_export(logs)
    assert text.endswith("[a] c\n[b] a\n[c] b\n")
    assert "日志条数：3" in text


def test_empty_log_list_renders_header_only():
    text = format_log_export([])
    assert "日志条数：0" in text
    assert "=" * 32 not in text
