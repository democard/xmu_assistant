"""Shared table rendering, selection retention, status styling and empty states."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import QAbstractItemView, QHeaderView, QTableWidget, QTableWidgetItem


class TableViewMixin:
    """Host provides the current semantic color palette through _ui_palette."""

    REQUIRED_HOST_ATTRS = ("_ui_palette",)

    def _make_table(self, headers: tuple[str, ...], widths: tuple[int, ...]) -> QTableWidget:
        table = QTableWidget(0, len(headers))
        table.setHorizontalHeaderLabels(headers)
        table.setAlternatingRowColors(True)
        table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        table.verticalHeader().setVisible(False)
        table.horizontalHeader().setStretchLastSection(True)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for index, width in enumerate(widths):
            table.setColumnWidth(index, width)
        return table


    def _set_table_rows(
        self,
        table: QTableWidget,
        rows: list[tuple],
        row_ids: list[str] | None = None,
        centered_columns: tuple[int, ...] = (),
        status_column: int | None = None,
    ):
        if row_ids is not None and (len(row_ids) != len(rows) or len(set(row_ids)) != len(row_ids)):
            raise ValueError("表格行标识必须唯一且与数据行数一致")
        old_ids = [
            table.item(row, 0).data(Qt.ItemDataRole.UserRole) if table.item(row, 0) else None
            for row in range(table.rowCount())
        ]
        current_row, current_column = table.currentRow(), table.currentColumn()
        current_id = old_ids[current_row] if 0 <= current_row < len(old_ids) else None
        had_selection = bool(table.selectedItems())
        top_row = table.rowAt(0)
        top_id = old_ids[top_row] if 0 <= top_row < len(old_ids) else None
        top_item = table.item(top_row, 0) if top_row >= 0 else None
        top_offset = table.visualItemRect(top_item).top() if top_item else 0
        vertical, horizontal = table.verticalScrollBar().value(), table.horizontalScrollBar().value()
        style_signature = (status_column, tuple(sorted(self._ui_palette().items()))) if status_column is not None else None
        style_changed = getattr(table, "_xmu_status_style", None) != style_signature
        signals_blocked = table.blockSignals(True)
        updates_enabled = table.updatesEnabled()
        table.setUpdatesEnabled(False)
        try:
            # 空态占位跨列且不可选，不能作为第一条真实事件继续复用。
            if table.rowCount() and table.columnSpan(0, 0) > 1:
                table.clearSpans()
            if row_ids is not None:
                wanted = set(row_ids)
                live_ids = list(old_ids)
                for index in range(len(live_ids) - 1, -1, -1):
                    if live_ids[index] not in wanted:
                        table.removeRow(index)
                        live_ids.pop(index)
                if not live_ids:
                    table.setRowCount(len(rows))
                else:
                    for index, row_id in enumerate(row_ids):
                        if index < len(live_ids) and live_ids[index] == row_id:
                            continue
                        # 只移动顺序改变的行；保留其原有 item、角色数据和样式。
                        moved = []
                        if row_id in live_ids[index:]:
                            old_index = live_ids.index(row_id, index)
                            moved = [table.takeItem(old_index, column) for column in range(table.columnCount())]
                            table.removeRow(old_index)
                            live_ids.pop(old_index)
                        table.insertRow(index)
                        live_ids.insert(index, row_id)
                        for column, item in enumerate(moved):
                            if item is not None:
                                table.setItem(index, column, item)
            elif table.rowCount() != len(rows):
                table.setRowCount(len(rows))

            for row_index, values in enumerate(rows):
                for column, value in enumerate(values):
                    text = str(value)
                    item = table.item(row_index, column)
                    created = item is None
                    text_changed = created or item.text() != text
                    if created:
                        item = QTableWidgetItem(text)
                    elif text_changed:
                        item.setText(text)
                    if column == 0:
                        row_id = row_ids[row_index] if row_ids is not None else None
                        if item.data(Qt.ItemDataRole.UserRole) != row_id:
                            item.setData(Qt.ItemDataRole.UserRole, row_id)
                    alignment = Qt.AlignmentFlag.AlignCenter if column in centered_columns else Qt.AlignmentFlag(0)
                    if item.textAlignment() != alignment:
                        item.setTextAlignment(alignment)
                    if column == status_column and (text_changed or style_changed):
                        self._style_status_item(item, text)
                    if created:
                        table.setItem(row_index, column, item)
            table._xmu_status_style = style_signature

            if row_ids is not None and current_id is not None:
                if current_id in row_ids:
                    new_row = row_ids.index(current_id)
                    if (table.currentRow(), table.currentColumn()) != (new_row, current_column):
                        table.setCurrentCell(new_row, current_column)
                    if not had_selection:
                        table.clearSelection()
                else:
                    # 删除的事件不能悄悄变为另一条签到的操作目标。
                    table.clearSelection()
                    table.setCurrentCell(-1, -1)
            if row_ids is not None and old_ids != row_ids and top_id in row_ids:
                table.scrollToItem(table.item(row_ids.index(top_id), 0), QAbstractItemView.ScrollHint.PositionAtTop)
                if table.verticalScrollMode() == QAbstractItemView.ScrollMode.ScrollPerPixel:
                    table.verticalScrollBar().setValue(table.verticalScrollBar().value() - top_offset)
            else:
                table.verticalScrollBar().setValue(vertical)
            table.horizontalScrollBar().setValue(horizontal)
        finally:
            table.blockSignals(signals_blocked)
            table.setUpdatesEnabled(updates_enabled)


    def _style_status_item(self, item: QTableWidgetItem, status: str):
        pal = self._ui_palette()
        mapping = {
            "未签": (pal["status_unsigned_fg"], pal["status_unsigned_bg"]),
            "未知": (pal["status_unknown_fg"], pal["status_unknown_bg"]),
            "已签": (pal["status_signed_fg"], pal["status_signed_bg"]),
            "无记录": (pal["status_none_fg"], pal["status_none_bg"]),
            "失败": (pal["status_unsigned_fg"], pal["status_unsigned_bg"]),
            "跳过": (pal["status_none_fg"], pal["status_none_bg"]),
            "处理中": (pal["status_unknown_fg"], pal["status_unknown_bg"]),
        }
        foreground, background = mapping.get(status, (pal["status_none_fg"], pal["status_none_bg"]))
        item.setForeground(QColor(foreground))
        item.setBackground(QColor(background))
        item.setFont(QFont("Microsoft YaHei UI", 9, QFont.Weight.DemiBold))


    def _set_table_empty_state(self, table: QTableWidget, message: str) -> None:
        table.setRowCount(0)
        table.insertRow(0)
        item = QTableWidgetItem(message)
        item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsSelectable)
        item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
        item.setForeground(QColor(self._ui_palette()["empty_text"]))
        table.setItem(0, 0, item)
        table.setSpan(0, 0, 1, table.columnCount())
