"""PC 页面快捷键源码契约：Ctrl+1..6 切页 + Ctrl+R 刷新映射。"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "app.py"


def _app_source() -> str:
    return APP_PY.read_text(encoding="utf-8")


def test_shortcuts_are_installed_from_nav_titles_and_trigger_page_switch():
    source = _app_source()
    assert "from PySide6.QtGui import" in source and "QShortcut" in source and "QKeySequence" in source
    # Ctrl+N 必须按 nav_titles 数量循环绑定，切页走既有 _show_page
    binding = re.search(r'for index in range\(len\(self\.nav_titles\)\):\s+'
                        r'shortcut = QShortcut\(QKeySequence\(f"Ctrl\+\{index \+ 1\}"\), self\)\s+'
                        r'shortcut\.activated\.connect\([^\n]*_show_page\(row\)', source)
    assert binding, "Ctrl+1..N shortcuts must route through the existing _show_page"


def test_ctrl_r_maps_only_real_refresh_entries():
    source = _app_source()
    refresh_body = source[source.index("def _refresh_current_page") : source.index("\n    def ", source.index("def _refresh_current_page"))]
    # 签到情况/课件页必须复用既有刷新方法（不另起网络口径）
    assert "self.refresh_course_rollcalls()" in refresh_body
    assert "self.refresh_courseware_courses()" in refresh_body
    # 其余页面必须有明确提示分支，不允许静默无响应
    assert "_show_toast(" in refresh_body
