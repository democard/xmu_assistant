"""策略页「达到人数 / 达到比例」必须可见且可编辑（桌面端回归）。"""

from __future__ import annotations

import os
import sys
import types
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtWidgets import QApplication, QFrame
from xmu_rollcall.desktop_qt.app import DashboardWindow


class StrategyWaitControlsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        host = types.SimpleNamespace()
        host._panel = lambda _title="": QFrame()
        host._save_rollcall_strategy_settings = lambda *_args: None
        host._save_app_settings = lambda *_args: None
        host._export_logs = lambda *_args: None
        host._save_theme_mode = lambda *_args: None
        for name in ("_build_settings_page", "_refresh_wait_strategy_controls"):
            setattr(host, name, types.MethodType(getattr(DashboardWindow, name), host))
        self.host = host
        self.page = host._build_settings_page()
        self.page.resize(900, 700)
        self.page.show()
        self.app.processEvents()
        self.addCleanup(self.page.deleteLater)

    def select(self, mode: str):
        combo = self.host.wait_before_answer_mode_combo
        combo.setCurrentIndex(combo.findData(mode))
        self.app.processEvents()

    def test_both_wait_inputs_stay_visible_in_every_mode(self):
        count = self.host.wait_before_answer_count_spin
        percent = self.host.wait_before_answer_percent_spin
        for mode in ("none", "count", "percent"):
            with self.subTest(mode=mode):
                self.select(mode)
                self.assertFalse(count.isHidden())
                self.assertFalse(percent.isHidden())
                self.assertTrue(count.isVisibleTo(self.page))
                self.assertTrue(percent.isVisibleTo(self.page))

    def test_mode_enables_only_its_own_input_and_value_is_editable(self):
        count = self.host.wait_before_answer_count_spin
        percent = self.host.wait_before_answer_percent_spin

        self.select("count")
        self.assertTrue(count.isEnabled())
        self.assertFalse(percent.isEnabled())
        count.setValue(42)
        self.assertEqual(count.value(), 42)

        self.select("percent")
        self.assertTrue(percent.isEnabled())
        self.assertFalse(count.isEnabled())
        percent.setValue(33)
        self.assertEqual(percent.value(), 33)

        self.select("none")
        self.assertFalse(count.isEnabled())
        self.assertFalse(percent.isEnabled())


if __name__ == "__main__":
    unittest.main()
