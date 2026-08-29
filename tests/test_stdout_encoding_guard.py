"""main() stdout 编码防护契约（配合 2026-08-28 A1 修复）。

第十五轮实测：非 UTF8 stdout 管道（cp1252 控制台重定向）下，log() 的中文
裸 print 抛 UnicodeEncodeError 使 exe 启动即崩（_restore_ui_snapshot 启动
路径即触发）。main() 入口一次性把 stdout reconfigure 到 UTF8 替换策略。
本测试双重守护：
1) 行为级：模拟 cp1252 管道 → 应用与 main() 相同的守卫 → 中文 print 不再抛错；
2) 源码级：main() 必须保留 reconfigure 调用（防止重构时静默丢失）。
"""

from __future__ import annotations

import io
import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_PY = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt" / "app.py"


class StdoutEncodingGuardTests(unittest.TestCase):
    def test_reconfigure_guard_makes_cp1252_pipe_print_chinese(self):
        read_fd, write_fd = os.pipe()
        old_stdout = sys.stdout
        try:
            # 与非 UTF8 控制台管道同构：编码 cp1252 的文本流
            sys.stdout = io.TextIOWrapper(os.fdopen(write_fd, "wb"), encoding="cp1252")
            with self.assertRaises(UnicodeEncodeError):
                print("中文日志", flush=True)
            # 应用 main() 同款守卫后：不抛错，字节按 UTF8 落管道
            try:
                sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass
            print("中文日志", flush=True)
            data = os.read(read_fd, 256)
        finally:
            sys.stdout.close()
            sys.stdout = old_stdout
            os.close(read_fd)
        self.assertIn("中文日志".encode("utf-8"), data)

    def test_main_keeps_stdout_reconfigure_guard(self):
        source = APP_PY.read_text(encoding="utf-8")
        main_body = source[source.index("def main():"):]
        self.assertIn(
            'sys.stdout.reconfigure(encoding="utf-8", errors="replace")',
            main_body,
            "main() 的 stdout UTF8 重配置防护不许删（exe 非 UTF8 管道启动即崩）",
        )


if __name__ == "__main__":
    unittest.main()
