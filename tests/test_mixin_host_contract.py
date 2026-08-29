"""Mixin 宿主契约扫描测试（B3，照 theme/契约冻结范式）。

app.py 出库后页面/能力以 Mixin 形式挂回 DashboardWindow，宿主契约此前只存在于
各模块 docstring 里（纯文档约定，改了不报错）。本测试把契约变成可执行断言：

1. **防漂移**：从源码重算各 Mixin 的真实宿主依赖（self.X 引用 − 自身赋值 −
   自有方法 − Qt 内建），与类上声明的 REQUIRED_HOST_ATTRS 逐字比对。
   混入新增/去掉一个宿主依赖而没登记，本测试立刻失败。
2. **防落空**：登记的每个名字都必须真能在宿主面（app.py + 其它 desktop_qt
   模块）找到定义（def 或 self.X =），确保契约不是空头承诺。

只增不许减：新增宿主依赖请同步 REQUIRED_HOST_ATTRS 并说明理由。
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESKTOP_QT = ROOT / "xmu-rollcall-cli" / "xmu_rollcall" / "desktop_qt"

from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.courses_page import CoursesPageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.notifications_page import NotificationsPageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.overview_page import OverviewPageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.settings_page import SettingsPageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.startup_registry import StartupRegistryMixin  # noqa: E402
from xmu_rollcall.desktop_qt.tray import TrayMixin  # noqa: E402
from xmu_rollcall.desktop_qt.tutorial_page import TutorialPageMixin  # noqa: E402
from xmu_rollcall.desktop_qt.ui_snapshot import UiSnapshotMixin  # noqa: E402

# Qt / QMainWindow 内建成员：混入直接调用但不属于本项目宿主契约
QT_BUILTINS = {
    "activateWindow", "close", "hide", "isVisible", "raise_", "show",
    "showMinimized", "showNormal", "setWindowTitle", "update",
}

MIXINS = (
    ("courseware_page.py", "CoursewarePageMixin", CoursewarePageMixin),
    ("courses_page.py", "CoursesPageMixin", CoursesPageMixin),
    ("notifications_page.py", "NotificationsPageMixin", NotificationsPageMixin),
    ("overview_page.py", "OverviewPageMixin", OverviewPageMixin),
    ("settings_page.py", "SettingsPageMixin", SettingsPageMixin),
    ("startup_registry.py", "StartupRegistryMixin", StartupRegistryMixin),
    ("tray.py", "TrayMixin", TrayMixin),
    ("tutorial_page.py", "TutorialPageMixin", TutorialPageMixin),
    ("ui_snapshot.py", "UiSnapshotMixin", UiSnapshotMixin),
)


def _class_body(source: str, class_name: str) -> str:
    match = re.search(rf"class {class_name}\b.*?(?=\nclass |\Z)", source, re.S)
    assert match, f"{class_name} not found"
    return match.group(0)


def _host_dependencies(body: str) -> set[str]:
    """重算混入的宿主依赖：self.X 引用中，剔除自身赋值、自有方法与 Qt 内建。"""
    assigned = set(re.findall(r"self\.(\w+)\s*=(?!=)", body))
    own_methods = set(re.findall(r"\n    def (\w+)", body))
    referenced = set(re.findall(r"self\.(\w+)", body))
    return referenced - assigned - own_methods - QT_BUILTINS


class MixinHostContractTests(unittest.TestCase):
    def setUp(self):
        self.sources = {
            filename: (DESKTOP_QT / filename).read_text(encoding="utf-8")
            for filename, _class_name, _mixin in MIXINS
        }
        # 宿主面：app.py + 除本混入自身以外的所有 desktop_qt 模块
        # （DashboardWindow 由多个混入组合而成）
        self.host_surface = {
            path.name: path.read_text(encoding="utf-8")
            for path in sorted(DESKTOP_QT.glob("*.py"))
        }
        self.assertIn("app.py", self.host_surface, "宿主面缺少 app.py")

    def test_declared_contract_matches_source_dependencies(self):
        """登记清单必须与源码真实依赖逐字一致（双向防漂移）。"""
        for filename, class_name, mixin in MIXINS:
            with self.subTest(mixin=class_name):
                body = _class_body(self.sources[filename], class_name)
                actual = _host_dependencies(body)
                declared = set(mixin.REQUIRED_HOST_ATTRS)
                self.assertEqual(
                    declared, actual,
                    f"{class_name}.REQUIRED_HOST_ATTRS 与源码依赖不一致"
                    f"（多登记={sorted(declared - actual)}，漏登记={sorted(actual - declared)}）",
                )

    def test_declared_attrs_are_sorted_and_unique(self):
        """清单保持有序无重复：diff 可读，避免同名重复登记掩盖缺失。"""
        for _filename, class_name, mixin in MIXINS:
            with self.subTest(mixin=class_name):
                values = tuple(mixin.REQUIRED_HOST_ATTRS)
                self.assertEqual(len(values), len(set(values)), f"{class_name} 清单有重复项")
                self.assertEqual(values, tuple(sorted(values)), f"{class_name} 清单未按字典序")

    def test_every_declared_attr_is_provided_by_the_host(self):
        """登记的宿主依赖必须真能在宿主面找到定义（def 或 self.X =）。"""
        for filename, class_name, mixin in MIXINS:
            with self.subTest(mixin=class_name):
                for attr in mixin.REQUIRED_HOST_ATTRS:
                    provided = any(
                        re.search(rf"^\s*def {re.escape(attr)}\s*\(", text, re.M) is not None
                        or re.search(rf"self\.{re.escape(attr)}\s*=(?!=)", text) is not None
                        for module_name, text in self.host_surface.items()
                        if module_name != filename
                    )
                    self.assertTrue(
                        provided,
                        f"{class_name} 依赖的宿主成员 {attr!r} 在宿主面（app.py + 其它 "
                        f"desktop_qt 模块）找不到定义",
                    )

    def test_tray_icon_is_a_declared_cross_mixin_dependency(self):
        """tray_icon 由 TrayMixin 赋值、被 NotificationsPageMixin 读取——锁住这条跨混入依赖。"""
        self.assertIn("tray_icon", NotificationsPageMixin.REQUIRED_HOST_ATTRS)
        tray_body = _class_body(self.sources["tray.py"], "TrayMixin")
        self.assertIn("self.tray_icon = QSystemTrayIcon", tray_body)
        # 反向：它不属于 TrayMixin 的宿主依赖（是自己建的）
        self.assertNotIn("tray_icon", TrayMixin.REQUIRED_HOST_ATTRS)

    def test_mixins_are_composed_into_the_main_window(self):
        """宿主组合关系本身也要有锚：混入必须真的挂进 DashboardWindow。"""
        app_source = self.host_surface["app.py"]
        for _filename, class_name, _mixin in MIXINS:
            with self.subTest(mixin=class_name):
                self.assertRegex(
                    app_source,
                    rf"class DashboardWindow\([^)]*{re.escape(class_name)}",
                    f"{class_name} 未出现在 DashboardWindow 的基类列表中",
                )


if __name__ == "__main__":
    unittest.main()
