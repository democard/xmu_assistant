"""开机自启的 Windows 注册表逻辑：常量与 _startup_* 方法集合。

自 app.py 机械搬出（tray.py / notifications_page.py 同款手法），方法体逐字保留；
常量仅被本组方法使用，随迁。DashboardWindow 经 StartupRegistryMixin 引用，
行为不变。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

STARTUP_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
STARTUP_VALUE_NAME = "xmu助手"


class StartupRegistryMixin:
    REQUIRED_HOST_ATTRS: tuple[str, ...] = ()

    def _startup_legacy_paths(self) -> tuple[Path, ...]:
        startup_dir = (
            Path(os.environ.get("APPDATA", str(Path.home() / "AppData" / "Roaming")))
            / "Microsoft"
            / "Windows"
            / "Start Menu"
            / "Programs"
            / "Startup"
        )
        return (startup_dir / "xmu助手.vbs", startup_dir / "xmu助手.lnk")

    def _startup_target_path(self) -> Path:
        if getattr(sys, "frozen", False):
            return Path(sys.executable)
        desktop_exe = Path.home() / "Desktop" / "xmu助手.exe"
        if desktop_exe.exists():
            return desktop_exe
        return Path(sys.executable)

    def _startup_command(self) -> str:
        return f'"{self._startup_target_path()}" --startup'

    def _cleanup_legacy_startup_entries(self):
        for path in self._startup_legacy_paths():
            if path.exists():
                path.unlink()

    def _launch_on_startup_enabled(self) -> bool:
        if os.name != "nt":
            return False
        # winreg 仅 Windows 可用：延迟导入，避免非 Windows 平台 import 本模块即崩溃
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_RUN_KEY, 0, winreg.KEY_READ) as key:
                command, _value_type = winreg.QueryValueEx(key, STARTUP_VALUE_NAME)
            return str(command).strip() == self._startup_command()
        except FileNotFoundError:
            return False

    def _set_launch_on_startup(self, enabled: bool):
        if os.name != "nt":
            return
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enabled:
                winreg.SetValueEx(key, STARTUP_VALUE_NAME, 0, winreg.REG_SZ, self._startup_command())
            else:
                try:
                    winreg.DeleteValue(key, STARTUP_VALUE_NAME)
                except FileNotFoundError:
                    pass
        self._cleanup_legacy_startup_entries()
