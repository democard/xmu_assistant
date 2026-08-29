from __future__ import annotations

import datetime
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall import diag_log  # noqa: E402


class DiagLogTests(unittest.TestCase):
    """diag_log：打包 exe 内 print 不可见，诊断线索改落 CONFIG_DIR/diag.log。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config_dir = Path(self.tmp.name)
        # diag_log 每次写入时读取 config.CONFIG_DIR，打补丁即可定向到临时目录
        patcher = mock.patch.object(diag_log.config, "CONFIG_DIR", self.config_dir)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_normal_write_appends_message(self):
        diag_log.log("保存会话缓存失败（x）：boom")
        diag_log.log("第二条")

        content = (self.config_dir / diag_log.DIAG_LOG_NAME).read_text(encoding="utf-8")
        self.assertIn("保存会话缓存失败（x）：boom", content)
        self.assertIn("第二条", content)
        self.assertEqual(content.count("\n"), 2)

    def test_unwritable_location_is_silent_noop(self):
        # 目标位置是一个文件而非目录：open 必然失败，但绝不允许抛异常影响主流程
        blocking_file = self.config_dir / "not-a-dir"
        blocking_file.write_text("", encoding="utf-8")
        with mock.patch.object(diag_log.config, "CONFIG_DIR", blocking_file):
            diag_log.log("写不进去也不能炸")
        # 同样覆盖父目录缺失的情形
        with mock.patch.object(diag_log.config, "CONFIG_DIR", self.config_dir / "no-such-dir"):
            diag_log.log("父目录缺失同样静默")

    def test_content_contains_timestamp(self):
        before = datetime.datetime.now() - datetime.timedelta(seconds=1)
        diag_log.log("带时间戳的一行")
        after = datetime.datetime.now() + datetime.timedelta(seconds=1)

        first_field = (self.config_dir / diag_log.DIAG_LOG_NAME).read_text(
            encoding="utf-8"
        ).split(" ", 1)[0]
        stamp = datetime.datetime.strptime(first_field, "%Y-%m-%d")
        self.assertGreaterEqual(stamp.date(), before.date())
        self.assertLessEqual(stamp.date(), after.date())


class SecretsWarnChannelTests(unittest.TestCase):
    """secrets 安全降级告警必须双通道：print + diag.log（exe 无 stdout，2026-08-30 体检 P2-5）。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config_dir = Path(self.tmp.name)
        patcher = mock.patch.object(diag_log.config, "CONFIG_DIR", self.config_dir)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_secrets_warn_writes_diag_log(self):
        from xmu_rollcall import secrets

        with mock.patch("builtins.print"):
            secrets._warn("警告：DPAPI 加密失败，按明文存储：boom")
        content = (self.config_dir / diag_log.DIAG_LOG_NAME).read_text(encoding="utf-8")
        self.assertIn("DPAPI 加密失败", content)

    def test_secrets_warn_diag_failure_never_raises(self):
        # diag 写入失败（目录变文件）时 print 通道照常、不抛异常
        from xmu_rollcall import secrets

        blocking = self.config_dir / "diag.log"
        blocking.write_text("", encoding="utf-8")
        real_open = open

        def bad_open(*args, **kwargs):
            raise OSError("disk unavailable")

        with mock.patch("builtins.print"), mock.patch("xmu_rollcall.diag_log.open", bad_open):
            secrets._warn("警告：未安装 pywin32")



if __name__ == "__main__":
    unittest.main()
