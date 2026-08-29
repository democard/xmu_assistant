"""事件总线契约测试（体检报告 Opt-1，配合 desktop_qt/events.py）。

静态扫描 app.py / core.py 源码文本，三向校验：
1) 所有 ``emit(("kind", ...)`` 发射点的 kind 必须登记在 EVENT_CONTRACTS；
2) ``_handle_event`` 的 ``kind == "..."`` 分支集合必须与注册表完全一致
   （防死条目/防漏登记）；
3) 每个发射点字面量实参个数（含 kind）≥ 登记的最小实参。

只做源码文本与纯数据结构校验，不导入 Qt，任何环境可跑。
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.events import EVENT_CONTRACTS  # noqa: E402

APP_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "app.py"
CORE_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "core.py"
NOTIFICATIONS_PAGE_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "notifications_page.py"
COURSEWARE_PAGE_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "courseware_page.py"
COURSES_PAGE_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "courses_page.py"
# 发射点扫描面（随 Mixin 出库同步扩充，只加不减）
EMIT_SOURCE_PATHS = (APP_PY, CORE_PY, NOTIFICATIONS_PAGE_PY, COURSEWARE_PAGE_PY, COURSES_PAGE_PY)

# 形如  self._emit(("kind", ...))  /  self.emit(("kind", ...))
_EMIT_RE = re.compile(r'\.emit\(\(\s*"(?P<kind>[a-z_]+)"\s*,?')
# 分发表键：_handle_event 已由 if 链改为 kind→handler 查表（2026-08-27），
# 处理器统一命名 _ev_<kind>，表键即处理种类集合
_TABLE_KEY_RE = re.compile(r'^[ \t]*"(?P<kind>[a-z_]+)"\s*:\s*_ev_[a-z_]+\s*,?\s*$', re.MULTILINE)


def _iter_dispatch_kinds(source: str):
    for match in _TABLE_KEY_RE.finditer(source):
        yield match.group("kind")


def _iter_emit_literals(source: str):
    """yield (kind, 实参个数含 kind)。实参个数按顶层逗号计数（括号感知）。"""
    for match in _EMIT_RE.finditer(source):
        kind = match.group("kind")
        # 元组自身的 '(' 是匹配区间内最后一个左括号（第一个属于 .emit( 调用）
        start = source.rindex("(", match.start(), match.end())
        depth = 0
        end = start
        for index in range(start, len(source)):
            ch = source[index]
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
                if depth == 0:
                    end = index
                    break
            elif ch == "#" and depth == 1:
                # 元组内不允许注释（保持单行字面量），出现即视为解析失败
                raise AssertionError(f"emit 字面量元组内出现注释，无法静态计数：{kind}")
        tuple_text = source[start + 1 : end]
        # 去掉字符串字面量内的逗号干扰：先把引号串替换成占位符
        cleaned = re.sub(r'"[^"]*"|\'[^\']*\'', '""', tuple_text)
        args = 1 if cleaned.strip() else 0
        depth = 0
        for ch in cleaned:
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            elif ch == "," and depth == 0:
                args += 1
        yield kind, args


class EventContractTests(unittest.TestCase):
    def test_every_emit_site_kind_is_registered(self):
        for path in EMIT_SOURCE_PATHS:
            for kind, _args in _iter_emit_literals(path.read_text(encoding="utf-8")):
                self.assertIn(
                    kind,
                    EVENT_CONTRACTS,
                    f"{path.name} 发射了未登记的事件种类：{kind}（请同步更新 events.py）",
                )

    def test_handler_branches_match_registry_exactly(self):
        handled = set(_iter_dispatch_kinds(APP_PY.read_text(encoding="utf-8")))
        registered = set(EVENT_CONTRACTS)
        self.assertEqual(
            handled,
            registered,
            f"_handle_event 分支与注册表不一致；多出分支={sorted(handled - registered)}，"
            f"死条目={sorted(registered - handled)}",
        )

    def test_emit_literal_arity_meets_contract(self):
        for path in EMIT_SOURCE_PATHS:
            for kind, args in _iter_emit_literals(path.read_text(encoding="utf-8")):
                min_args, _desc = EVENT_CONTRACTS[kind]
                self.assertGreaterEqual(
                    args,
                    min_args,
                    f"{path.name} 事件 {kind} 只给了 {args} 个实参（含 kind），"
                    f"契约要求 ≥ {min_args}",
                )

    def test_consumers_do_not_blind_unpack_events(self):
        # P0-1 编码约定：_handle_event 内禁止对 event 定长解包（会因追加字段崩溃）
        body = APP_PY.read_text(encoding="utf-8")
        handler_start = body.index("def _handle_event")
        handler_end = body.index("\n    def ", handler_start + 10)
        handler_text = body[handler_start:handler_end]
        offenders = [
            line.strip()
            for line in handler_text.splitlines()
            if "= event" in line and not line.strip().startswith("#")
            and re.search(r"^\s*[\w, ]+\s=\s*event$", line)
        ]
        self.assertEqual(
            offenders,
            [],
            f"_handle_event 内发现对 event 的定长解包，请改下标取值：{offenders}",
        )


class EventRegistryFreezeTests(unittest.TestCase):
    """EVENT_CONTRACTS 整表冻结（E3）：kind 集合与最小实参逐项锁定。

    上面三个校验只保证"发射/消费两侧与注册表一致"，但注册表本身被误删
    条目时（发射点也被同时误删的场合）会静默缩表。整表冻结后，任何增删
    改都必须显式修改本表——防误删登记项。
    """

    FROZEN_CONTRACTS: dict[str, int] = {
        "monitor_status": 2,
        "poll": 4,
        "rollcall": 2,
        "error": 2,
        "login_success": 4,
        "login_failed": 2,
        "restore_failed": 3,
        "answer_result": 4,
        "number_code": 3,
        "merge_session_cookies": 3,
        "notification_result": 3,
        "course_rollcalls": 4,
        "course_rollcalls_error": 4,
        "course_records_verified": 4,
        "course_records_verify_error": 3,
        "courseware_courses": 4,
        "courseware_courses_error": 4,
        "courseware": 4,
        "courseware_error": 4,
        "courseware_download_progress": 5,
        "courseware_download_item_done": 3,
        "courseware_download_done": 6,
    }

    def test_registry_kind_set_and_min_arity_frozen(self):
        actual = {kind: spec[0] for kind, spec in EVENT_CONTRACTS.items()}
        self.assertEqual(
            sorted(actual),
            sorted(self.FROZEN_CONTRACTS),
            "EVENT_CONTRACTS 条目增删必须显式同步本冻结表",
        )
        self.assertEqual(
            actual,
            self.FROZEN_CONTRACTS,
            "EVENT_CONTRACTS 最小实参被修改必须显式同步本冻结表",
        )


class EmitScanSurfaceContractTests(unittest.TestCase):
    """EMIT_SOURCE_PATHS 自身健在锚（E4）：把"扫描面清单"也锚进测试。

    出库历史教训：Mixin 拆页时新模块若含事件发射点而漏登记进
    EMIT_SOURCE_PATHS，上面三个校验会静默缩小扫描面（漏检未登记 kind）。
    这里反向锚定：清单文件集合冻结 + 全目录扫描不放过任何含发射点
    却未登记的模块。
    """

    def test_scan_surface_set_is_frozen(self):
        expected = {
            "app.py",
            "core.py",
            "notifications_page.py",
            "courseware_page.py",
            "courses_page.py",
        }
        self.assertEqual(
            {p.name for p in EMIT_SOURCE_PATHS},
            expected,
            "EMIT_SOURCE_PATHS 增删文件必须显式改这里（同步扩充/裁剪扫描面需过目）",
        )

    def test_no_emitting_module_escapes_scan_surface(self):
        # events.py 是总线/契约定义本体，不是扫描目标；其余任何含发射点的模块必须登记
        exceptions = {"events.py"}
        for path in sorted(APP_PY.parent.glob("*.py")):
            if path.name in exceptions:
                continue
            text = path.read_text(encoding="utf-8")
            if _EMIT_RE.search(text):
                self.assertIn(
                    path,
                    EMIT_SOURCE_PATHS,
                    f"{path.name} 含事件发射点但未登记进 EMIT_SOURCE_PATHS（Mixin 出库需同步扩充）",
                )


if __name__ == "__main__":
    unittest.main()
