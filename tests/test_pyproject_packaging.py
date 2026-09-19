"""pyproject 打包配置守护：声明必须覆盖 xmu-rollcall-cli 下所有真实子包。

此前 packages = ["xmu_rollcall"] 漏掉 desktop_qt 子包，pip install 产出的
wheel 不含全部 UI 文件，入口点 xmu-dashboard 直接 ModuleNotFoundError
（开发一直走 PYTHONPATH，从未暴露）。本用例扫描磁盘上的真实子包，与
[tool.setuptools] 的声明（显式列表或 packages.find 的 include 通配）求差。
"""

from __future__ import annotations

import fnmatch
import sys
import tomllib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYPROJECT = ROOT / "xmu-rollcall-cli" / "pyproject.toml"


class PyprojectPackagingTests(unittest.TestCase):
    def test_declared_packages_cover_every_real_subpackage(self):
        if sys.version_info < (3, 11):
            self.skipTest("tomllib requires 3.11+")

        tool = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))["tool"]["setuptools"]
        actual = {
            ".".join(init.parent.relative_to(PYPROJECT.parent).parts)
            for init in (PYPROJECT.parent / "xmu_rollcall").rglob("__init__.py")
        }
        self.assertTrue(actual, "xmu_rollcall 下应至少存在一个子包")

        packages = tool.get("packages")
        if isinstance(packages, list):  # 显式列表
            declared = set(packages)
        else:  # [tool.setuptools.packages.find] 的 include 通配
            patterns = (packages or {}).get("find", {}).get("include") or ["*"]
            declared = {
                name
                for name in actual
                if any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns)
            }

        self.assertEqual(
            set(),
            actual - declared,
            "pyproject 的包声明漏掉以下子包（pip install 后入口点会挂）",
        )


class PyInstallerSpecFilterTests(unittest.TestCase):
    """spec 的 PySide6 过滤规则必须命中真实落盘路径。

    iconengines 规则此前漏写 plugins 段（PyInstaller 6.x 实际路径是
    PySide6/plugins/iconengines/），规则从未生效，qsvgicon.dll（依赖已被
    spec 删除的 Qt6Svg）一直被打进 exe——注释宣称的裁剪是假的。
    """

    def test_iconengine_filter_targets_the_real_plugin_path(self):
        import re

        spec_text = (ROOT / "xmu-assistant.spec").read_text(encoding="utf-8")
        match = re.search(r"r'(PySide6[^']*iconengines[^']*)'", spec_text)
        self.assertIsNotNone(match, "spec 里应存在 iconengines 过滤规则")

        pattern = match.group(1)
        self.assertIsNotNone(
            re.search(pattern, "PySide6/plugins/iconengines/qsvgicon.dll"),
            "iconengines 过滤规则必须命中真实落盘路径 PySide6/plugins/iconengines/",
        )


if __name__ == "__main__":
    unittest.main()
