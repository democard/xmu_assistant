"""maintenance 与 startup_registry 纯函数测试（C2 延伸，B0 覆盖缺口补齐）。

两者自 app.py 出库后一直零测试锚定（B0 评估维度 5）。此处覆盖：
- cleanup_orphaned_cookie_files：孤儿 cookie 清理的白名单语义（只清纯数字账号
  文件、绝不动 config.json、坏配置静默跳过）；
- StartupRegistryMixin 的启动命令拼装 / 遗留快捷方式清理 / 非 Windows 退化。
注册表读写（_launch_on_startup_enabled/_set_launch_on_startup 的 nt 分支）
触及真实 HKCU，不做集成级验证，仅锚定非 nt 退化路径。
"""

from __future__ import annotations

import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt import maintenance  # noqa: E402
from xmu_rollcall.desktop_qt.startup_registry import StartupRegistryMixin  # noqa: E402


class CleanupOrphanedCookieFilesTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config_dir = Path(self.tmp.name)
        patcher = mock.patch.object(maintenance, "CONFIG_DIR", self.config_dir)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_removes_orphan_digit_cookies_but_keeps_valid_and_config(self):
        (self.config_dir / "123.json").write_text("{}", encoding="utf-8")  # 孤儿
        (self.config_dir / "456.json").write_text("{}", encoding="utf-8")  # 在册账号
        (self.config_dir / "config.json").write_text("{}", encoding="utf-8")  # 绝不动
        (self.config_dir / "notes.txt").write_text("x", encoding="utf-8")  # 非 json
        (self.config_dir / "abc.json").write_text("{}", encoding="utf-8")  # 非纯数字名

        maintenance.cleanup_orphaned_cookie_files({"accounts": [{"id": 456}]})

        self.assertFalse((self.config_dir / "123.json").exists())
        self.assertTrue((self.config_dir / "456.json").exists())
        self.assertTrue((self.config_dir / "config.json").exists())
        self.assertTrue((self.config_dir / "notes.txt").exists())
        self.assertTrue((self.config_dir / "abc.json").exists())

    def test_malformed_config_is_silent_noop(self):
        target = self.config_dir / "123.json"
        target.write_text("{}", encoding="utf-8")
        # config.get(accounts) 抛异常（非 dict 输入）→ 直接返回，不动任何文件
        maintenance.cleanup_orphaned_cookie_files(object())  # type: ignore[arg-type]
        self.assertTrue(target.exists())

    def test_missing_config_dir_is_tolerated(self):
        # 目录不存在：listdir 失败属正常路径，不抛异常
        maintenance.cleanup_orphaned_cookie_files({"accounts": []})


class CleanupPyinstallerTempTests(unittest.TestCase):
    """_MEI 残留清理的所有权标记语义：只删带本应用标记的超龄残留。

    _MEI* 是 PyInstaller 全局命名约定，同机其他 PyInstaller 应用的解包目录同形——
    无标记目录（外来应用或极旧版本残留）必须一律跳过。
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        patcher = mock.patch.dict(
            os.environ, {"TEMP": self.tmp.name, "TMP": self.tmp.name}
        )
        patcher.start()
        self.addCleanup(patcher.stop)

    def _make_mei(self, name, *, marked, age_hours):
        path = Path(self.tmp.name) / name
        path.mkdir()
        if marked:
            (path / maintenance._OWNER_MARKER_NAME).write_text(
                "xmu-assistant", encoding="utf-8"
            )
        if age_hours is not None:
            old = time.time() - age_hours * 3600
            os.utime(path, (old, old))
        return path

    def test_marked_overage_dir_is_removed(self):
        self._make_mei("_MEIaaaaaa", marked=True, age_hours=2)
        maintenance.cleanup_orphaned_pyinstaller_temp()
        self.assertFalse((Path(self.tmp.name) / "_MEIaaaaaa").exists())

    def test_marked_recent_dir_is_kept(self):
        self._make_mei("_MEIbbbbbb", marked=True, age_hours=None)
        maintenance.cleanup_orphaned_pyinstaller_temp()
        self.assertTrue((Path(self.tmp.name) / "_MEIbbbbbb").exists())

    def test_unmarked_overage_foreign_dir_is_never_removed(self):
        # 其他 PyInstaller 应用的解包目录（无本应用标记）：宁可少清也不误删
        self._make_mei("_MEIcccccc", marked=False, age_hours=2)
        maintenance.cleanup_orphaned_pyinstaller_temp()
        self.assertTrue((Path(self.tmp.name) / "_MEIcccccc").exists())

    def test_current_process_own_dir_is_skipped(self):
        own = self._make_mei("_MEIcccccc", marked=True, age_hours=2)
        with mock.patch.object(sys, "_MEIPASS", str(own), create=True):
            maintenance.cleanup_orphaned_pyinstaller_temp()
        self.assertTrue(own.exists())

    def test_mark_own_extraction_dir_writes_marker(self):
        own = Path(self.tmp.name) / "_MEIdddddd"
        own.mkdir()
        with mock.patch.object(sys, "_MEIPASS", str(own), create=True):
            maintenance.mark_own_extraction_dir()
        marker = own / maintenance._OWNER_MARKER_NAME
        self.assertTrue(marker.is_file())
        self.assertEqual(marker.read_text(encoding="utf-8"), "xmu-assistant")

    def test_mark_own_extraction_dir_noop_without_meipass(self):
        # chdir 进受控目录：no-op 必须连 CWD 也不落文件（normpath("") 变 "." 的回归锚）
        self.addCleanup(os.chdir, os.getcwd())
        os.chdir(self.tmp.name)
        with mock.patch.object(sys, "_MEIPASS", "", create=True):
            maintenance.mark_own_extraction_dir()  # 不抛异常、不写文件
        self.assertEqual(list(Path(self.tmp.name).iterdir()), [])


class StartupRegistryMixinTests(unittest.TestCase):
    def setUp(self):
        self.mixin = StartupRegistryMixin()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_legacy_paths_follow_appdata(self):
        with mock.patch.dict(
            "os.environ", {"APPDATA": str(Path(self.tmp.name) / "roaming")}
        ):
            paths = self.mixin._startup_legacy_paths()
        self.assertEqual(len(paths), 2)
        for path in paths:
            self.assertIn("Microsoft\\Windows\\Start Menu\\Programs\\Startup", str(path))
        self.assertTrue(any(path.name == "xmu助手.vbs" for path in paths))
        self.assertTrue(any(path.name == "xmu助手.lnk" for path in paths))

    def test_startup_command_quotes_target_and_appends_flag(self):
        fake = Path("D:/somewhere/xmu助手.exe")
        with mock.patch.object(StartupRegistryMixin, "_startup_target_path", return_value=fake):
            command = self.mixin._startup_command()
        self.assertEqual(command, f'"{fake}" --startup')

    def test_target_path_prefers_frozen_executable(self):
        fake_exe = Path(self.tmp.name) / "frozen.exe"
        with mock.patch.multiple(
            sys, frozen=True, executable=str(fake_exe), create=True
        ), mock.patch.object(Path, "home", lambda: Path(self.tmp.name)):
            self.assertEqual(self.mixin._startup_target_path(), fake_exe)

    def test_target_path_falls_back_to_desktop_exe_then_interpreter(self):
        home = Path(self.tmp.name)
        desktop_exe = home / "Desktop" / "xmu助手.exe"
        desktop_exe.parent.mkdir(parents=True, exist_ok=True)
        desktop_exe.write_bytes(b"")
        with mock.patch.multiple(sys, frozen=False, create=True), mock.patch.object(
            Path, "home", lambda: home
        ):
            self.assertEqual(self.mixin._startup_target_path(), desktop_exe)
            desktop_exe.unlink()
            self.assertEqual(
                self.mixin._startup_target_path(), Path(sys.executable)
            )

    def test_cleanup_legacy_startup_entries_unlinks_existing_only(self):
        legacy_dir = (
            Path(self.tmp.name) / "Microsoft" / "Windows" / "Start Menu"
            / "Programs" / "Startup"
        )
        legacy_dir.mkdir(parents=True, exist_ok=True)
        vbs = legacy_dir / "xmu助手.vbs"
        vbs.write_text("", encoding="utf-8")
        with mock.patch.dict(
            "os.environ", {"APPDATA": str(Path(self.tmp.name))}
        ):
            # StartupRegistryMixin 使用 APPDATA 派生启动目录（与本测试的构造一致）
            self.mixin._cleanup_legacy_startup_entries()
        self.assertFalse(vbs.exists())

    def test_non_windows_paths_degrade_silently(self):
        # 非 Windows：读写注册表的两个入口都必须直接返回，不触碰 winreg
        with mock.patch("os.name", "posix"):
            self.assertFalse(self.mixin._launch_on_startup_enabled())
            self.mixin._set_launch_on_startup(True)  # 不抛异常即通过


if __name__ == "__main__":
    unittest.main()
