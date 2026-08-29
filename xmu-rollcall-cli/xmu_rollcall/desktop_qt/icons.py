"""应用图标与静态资源解析（从 app.py 第一刀拆分，机械搬移）。

app_asset_path 兼容 PyInstaller onefile（sys._MEIPASS）与源码运行两种布局；
app_icon 首选多分辨率 ICO，解码插件缺失时回退 PNG，保证窗口/任务栏/托盘
永远有应用图标。
"""

from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtGui import QIcon


def app_asset_path(name: str) -> Path:
    bases = []
    bundle_dir = getattr(sys, "_MEIPASS", None)
    if bundle_dir:
        bases.append(Path(bundle_dir))
    bases.extend((Path(__file__).resolve().parents[3], Path.cwd()))
    for base in bases:
        candidate = base / "assets" / name
        if candidate.exists():
            return candidate
    return bases[-1] / "assets" / name


def app_icon() -> QIcon:
    # 首选多分辨率 ICO；若打包环境缺 ICO 解码插件导致加载失败，
    # 回退 PNG（Qt 内置解码），保证窗口/任务栏/托盘永远有应用图标。
    icon = QIcon(str(app_asset_path("xmu-assistant.ico")))
    if not icon.isNull():
        return icon
    for name in ("xmu-assistant-icon.png", "xmu-assistant-mark.png", "xmu-assistant-logo.png"):
        fallback = QIcon(str(app_asset_path(name)))
        if not fallback.isNull():
            return fallback
    return icon
