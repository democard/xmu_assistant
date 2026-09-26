"""Actual offscreen Qt regressions for incremental event table refreshes."""
from __future__ import annotations

import os
import sys
import types
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication

from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.theme import palette


class EventTableRefreshTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.host = types.SimpleNamespace(_current_theme_mode="light")
        self.host._ui_palette = lambda: palette(self.host._current_theme_mode)
        self.host._style_status_item = lambda item, status: DashboardWindow._style_status_item(self.host, item, status)
        self.table = DashboardWindow._make_table(self.host, ("time", "course", "status"), (120, 300, 120))
        self.table.resize(330, 180)
        self.table.show()
        self.app.processEvents()
        self.addCleanup(self.table.close)
        self.addCleanup(self.table.deleteLater)

    def render(self, ids, rows=None):
        if rows is None:
            rows = [(key, f"Course {key}", "未签") for key in ids]
        DashboardWindow._set_table_rows(self.host, self.table, rows, ids, centered_columns=(0, 2), status_column=2)

    def ids(self):
        return [self.table.item(row, 0).data(Qt.ItemDataRole.UserRole) for row in range(self.table.rowCount())]

    def test_unchanged_refresh_preserves_items_selection_and_has_no_cell_writes(self):
        ids = [str(i) for i in range(80)]
        self.render(ids)
        self.table.setCurrentCell(30, 1)
        self.app.processEvents()
        before = self.table.item(30, 1)
        vertical = self.table.verticalScrollBar().value()
        horizontal = self.table.horizontalScrollBar().value()
        changes = []
        self.table.model().dataChanged.connect(lambda *args: changes.append(args))
        self.render(ids)
        self.assertIs(self.table.item(30, 1), before)
        self.assertEqual((self.table.currentRow(), self.table.currentColumn()), (30, 1))
        self.assertTrue(self.table.item(30, 0).isSelected())
        self.assertEqual(self.table.verticalScrollBar().value(), vertical)
        self.assertEqual(self.table.horizontalScrollBar().value(), horizontal)
        self.assertEqual(changes, [])

    def test_only_changed_cell_is_written(self):
        self.render(["a", "b"])
        stable = self.table.item(1, 2)
        changes = []
        self.table.model().dataChanged.connect(lambda first, last, roles: changes.append((first.row(), first.column())))
        self.render(["a", "b"], [("a", "Course a", "未签"), ("b", "Updated course", "未签")])
        self.assertEqual(changes, [(1, 1)])
        self.assertIs(self.table.item(1, 2), stable)

    def test_insert_remove_and_reorder_preserve_items_and_selected_id(self):
        self.render(["a", "b", "c"])
        before_b = self.table.item(1, 1)
        before_c = self.table.item(2, 0)
        self.table.setCurrentCell(1, 1)
        self.render(["new", "c", "b"])
        self.assertEqual(self.ids(), ["new", "c", "b"])
        self.assertIs(self.table.item(2, 1), before_b)
        self.assertIs(self.table.item(1, 0), before_c)
        self.assertEqual((self.table.currentRow(), self.table.currentColumn()), (2, 1))
        self.assertTrue(self.table.item(2, 0).isSelected())

    def test_deleted_selection_does_not_silently_select_another_event(self):
        self.render(["a", "b", "c"])
        self.table.setCurrentCell(1, 0)
        self.render(["a", "c"])
        self.assertEqual(self.table.currentRow(), -1)
        self.assertFalse(self.table.selectedItems())

    def test_scroll_anchor_survives_insertion_above_viewport(self):
        ids = [str(i) for i in range(80)]
        self.render(ids)
        self.table.setCurrentCell(30, 1)
        self.table.verticalScrollBar().setValue(20)
        self.table.horizontalScrollBar().setValue(40)
        self.app.processEvents()
        anchor = self.table.item(self.table.rowAt(0), 0).data(Qt.ItemDataRole.UserRole)
        horizontal = self.table.horizontalScrollBar().value()
        self.render(["new", *ids])
        self.app.processEvents()
        self.assertEqual(self.table.item(self.table.rowAt(0), 0).data(Qt.ItemDataRole.UserRole), anchor)
        self.assertEqual(self.table.horizontalScrollBar().value(), horizontal)
        self.assertEqual(self.table.item(self.table.currentRow(), 0).data(Qt.ItemDataRole.UserRole), "30")

    def test_empty_placeholder_span_and_flags_do_not_leak_into_data(self):
        DashboardWindow._set_table_empty_state(self.host, self.table, "Waiting")
        self.assertEqual(self.table.columnSpan(0, 0), 3)
        self.render(["new"])
        self.assertEqual(self.table.columnSpan(0, 0), 1)
        self.assertTrue(self.table.item(0, 0).flags() & Qt.ItemFlag.ItemIsSelectable)
        self.assertEqual(self.ids(), ["new"])

    def test_actual_theme_switch_restyles_retained_status_item(self):
        self.render(["a"])
        status = self.table.item(0, 2)
        light_color = status.background().color().name()
        old_stylesheet, old_palette = self.app.styleSheet(), self.app.palette()
        self.host.events_table = self.table
        self.host._refresh_event_tables = lambda: self.render(["a"])
        self.host._refresh_tutorial_html = lambda: None
        self.host._refresh_push_tip = lambda: None
        try:
            DashboardWindow._apply_theme(self.host, "dark")
            self.assertIs(self.table.item(0, 2), status)
            self.assertEqual(status.background().color().name(), palette("dark")["status_unsigned_bg"].lower())
            self.assertNotEqual(status.background().color().name(), light_color)
        finally:
            self.app.setStyleSheet(old_stylesheet)
            self.app.setPalette(old_palette)

    def test_signals_and_updates_restore_even_when_rendering_raises(self):
        for blocked, updates in ((False, True), (True, False)):
            with self.subTest(blocked=blocked, updates=updates):
                self.table.blockSignals(blocked)
                self.table.setUpdatesEnabled(updates)
                def fail(*args):
                    raise RuntimeError("style failed")
                self.host._style_status_item = fail
                with self.assertRaisesRegex(RuntimeError, "style failed"):
                    self.render(["new"])
                self.assertEqual(self.table.signalsBlocked(), blocked)
                self.assertEqual(self.table.updatesEnabled(), updates)


if __name__ == "__main__":
    unittest.main()
