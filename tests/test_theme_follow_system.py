"""「跟随系统」主题运行期联动回归（2026-08-30 体检 P1-1）。

缺陷背景：_apply_theme 一进来就把 _current_theme_mode 覆写为已解析值
（light/dark），colorSchemeChanged 回调若回读该已解析值再 resolve 恒等，
系统运行期切深浅时应用永不联动（设置页 tooltip 承诺落空）。

行为契约（SimpleNamespace 宿主直调，与 test_login_gate 同范式）：
- _theme_mode_setting 保存原始设定，_apply_theme 解析后不被覆写；
- 非法 mode 归一到 "system"；
- 回调 lambda 必须引用原始设定（源码契约，防回退到已解析值）。
"""

from __future__ import annotations

import inspect
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.app import DashboardWindow  # noqa: E402


class ThemeFollowSystemTest(unittest.TestCase):
    def _host(self) -> types.SimpleNamespace:
        # 若套件中其他测试已创建 QApplication，_apply_theme 会继续走样式表应用
        # 与 _refresh_tutorial_html——补 no-op 让宿主在两种环境下都可直调
        return types.SimpleNamespace(
            _current_theme_mode="light",
            _theme_mode_setting="light",
            _refresh_tutorial_html=lambda: None,
            _refresh_push_tip=lambda: None,
        )

    def test_apply_theme_preserves_raw_system_setting(self):
        host = self._host()
        DashboardWindow._apply_theme(host, "system")
        self.assertEqual(host._theme_mode_setting, "system")
        # 解析值允许 light/dark（无 QApplication 时 resolve 回退 light）
        self.assertIn(host._current_theme_mode, ("light", "dark"))

    def test_apply_theme_explicit_modes_roundtrip(self):
        host = self._host()
        DashboardWindow._apply_theme(host, "dark")
        self.assertEqual(host._theme_mode_setting, "dark")
        self.assertEqual(host._current_theme_mode, "dark")
        DashboardWindow._apply_theme(host, "light")
        self.assertEqual(host._theme_mode_setting, "light")
        self.assertEqual(host._current_theme_mode, "light")

    def test_apply_theme_normalizes_unknown_mode_to_system(self):
        host = self._host()
        DashboardWindow._apply_theme(host, "not-a-mode")
        self.assertEqual(host._theme_mode_setting, "system")

    def test_color_scheme_callback_uses_raw_setting_source_contract(self):
        # 回调若回读已解析值，"system" 在第一次解析后就永远丢失——用源码契约
        # 钉死 lambda 引用原始设定，防后续维护回退
        source = inspect.getsource(DashboardWindow)
        self.assertIn(
            "lambda _scheme: self._apply_theme(self._theme_mode_setting)", source
        )
        self.assertNotIn(
            "lambda _scheme: self._apply_theme(self._current_theme_mode)", source
        )


class ThemeRefreshItemsTest(unittest.TestCase):
    """切主题后重刷三张数据表与 PushPlus 提示：item 级前景色/富文本
    span 色在数据填充时按当时主题烘焙，QSS/palette 变更不会重绘既有 item
    （此前会残留旧主题色直到下一次数据刷新）。"""

    def test_apply_theme_refreshes_data_tables_and_push_tip(self):
        calls = []
        host = types.SimpleNamespace(
            _current_theme_mode="light",
            _theme_mode_setting="light",
            _refresh_tutorial_html=lambda: calls.append("tutorial"),
            _refresh_event_tables=lambda: calls.append("events"),
            _refresh_course_table=lambda: calls.append("courses"),
            _refresh_courseware_table=lambda: calls.append("courseware"),
            _refresh_push_tip=lambda: calls.append("push_tip"),
            events_table=object(),
            course_table=object(),
            courseware_table=object(),
        )
        fake_app = types.SimpleNamespace(
            setStyleSheet=lambda *_: None,
            setPalette=lambda *_: None,
        )
        with mock.patch(
            "xmu_rollcall.desktop_qt.app.QApplication.instance",
            return_value=fake_app,
        ):
            DashboardWindow._apply_theme(host, "dark")
        for name in ("tutorial", "events", "courses", "courseware", "push_tip"):
            self.assertIn(name, calls)


if __name__ == "__main__":
    unittest.main()
