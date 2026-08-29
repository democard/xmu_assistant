"""icons.py 资源解析测试（片C 补测：此前零测试）。

app_asset_path 是纯路径解析（onefile _MEIPASS 与源码布局双兼容）；app_icon
需要 QIcon（QApplication 以 offscreen 平台惰性创建一次，不弹任何窗口）。
"""

from __future__ import annotations

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication  # noqa: E402

from xmu_rollcall.desktop_qt.icons import app_asset_path, app_icon  # noqa: E402


class AppAssetPathTest(unittest.TestCase):
    def test_resolves_existing_asset_from_repo_layout(self):
        path = app_asset_path("xmu-assistant.ico")
        self.assertTrue(path.exists(), f"assets 应可从仓库根解析：{path}")
        self.assertEqual(path.name, "xmu-assistant.ico")
        self.assertIn("assets", path.parts)

    def test_missing_asset_falls_back_to_assets_joined_path_without_raising(self):
        path = app_asset_path("no-such-asset.bin")
        self.assertEqual(path.name, "no-such-asset.bin")
        self.assertEqual(path.parent.name, "assets")
        self.assertFalse(path.exists())


class AppIconTest(unittest.TestCase):
    def test_icon_prefers_multi_resolution_ico_and_is_not_null(self):
        app = QApplication.instance() or QApplication([])
        self.assertIsNotNone(app)
        icon = app_icon()
        self.assertIsNotNone(icon)
        self.assertFalse(icon.isNull(), "ICO 在位时必须成功加载多分辨率 ICO")


if __name__ == "__main__":
    unittest.main()
