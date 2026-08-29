"""签到情况页混入（从 app.py 第四刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得签到情况页能力；方法体逐字保留搬移前的实现，
依赖的实例属性（course_table/course_summary/course_records/筛选控件/session/account
与 *_in_progress 互斥位）与宿主回调（_panel/_academic_year_options/_make_table/
_style_status_item/_set_table_empty_state/_ui_palette/_run_thread/_emit/log）仍由
DashboardWindow 持有。事件处理器 _ev_course_* 与 _EVENT_HANDLERS 表仍留在 app.py
（分发表单一来源）；_set_table_empty_state 为首页事件表共用的通用助手，也留在 app.py。
"""

from __future__ import annotations

from dataclasses import replace
from datetime import date, datetime, timedelta

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..utils import clone_session
from .core import (
    COURSE_STATUS_DISPLAY,
    COURSE_STATUS_PRIORITY,
    CourseRollcallRecord,
    course_rollcall_csv,
    course_rollcall_stats,
    current_academic_year_label,
    fetch_course_rollcall_records,
    fetch_student_rollcall_detail,
    parse_rollcall_time,
    verify_own_status,
    verify_recent_rollcall_records,
)


class CoursesPageMixin:
    REQUIRED_HOST_ATTRS: tuple[str, ...] = (
        "_academic_year_options",
        "_emit",
        "_make_table",
        "_panel",
        "_require_login",
        "_run_thread",
        "_set_table_empty_state",
        "_style_status_item",
        "_ui_palette",
        "account",
        "course_records",
        "log",
        "session",
    )

    """签到情况页构建、刷新/核实 worker、表格渲染与导出统计（无自有状态，全部经由宿主窗口）。"""

    def _build_courses_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)

        toolbar = self._panel("筛选")
        toolbar_layout = QHBoxLayout(toolbar)
        toolbar_layout.setContentsMargins(16, 40, 16, 16)
        self.academic_year_combo = QComboBox()
        self.academic_year_combo.addItems(self._academic_year_options())
        self.academic_year_combo.setCurrentText(current_academic_year_label())
        self.academic_year_combo.setFixedWidth(130)
        self.semester_combo = QComboBox()
        self.semester_combo.addItems(("全部", "第一学期", "第二学期", "第三学期"))
        self.semester_combo.setFixedWidth(110)
        self.time_range_combo = QComboBox()
        self.time_range_combo.addItems(("今天", "本周", "本学期"))
        self.time_range_combo.setCurrentText("本学期")
        self.time_range_combo.setFixedWidth(100)
        refresh_button = QPushButton("刷新")
        refresh_button.setObjectName("PrimaryButton")
        self.only_unsigned_check = QCheckBox("只显示未签")
        # L2 手动核实入口：对选中行按本人签到明细判定状态（默认无选中禁用）
        self.verify_selected_button = QPushButton("核实所选")
        self.verify_selected_button.setEnabled(False)
        # 纯本地导出/统计：对当前筛选后的记录出 CSV（utf-8-sig）与按课程签到率
        export_csv_button = QPushButton("导出 CSV")
        stats_button = QPushButton("签到率统计")
        self.course_summary = QLabel("登录后点击刷新，查看签到情况。")
        self.course_summary.setObjectName("Subtle")
        toolbar_layout.addWidget(QLabel("学年"))
        toolbar_layout.addWidget(self.academic_year_combo)
        toolbar_layout.addWidget(QLabel("学期"))
        toolbar_layout.addWidget(self.semester_combo)
        toolbar_layout.addWidget(QLabel("时间范围"))
        toolbar_layout.addWidget(self.time_range_combo)
        toolbar_layout.addWidget(refresh_button)
        toolbar_layout.addWidget(self.only_unsigned_check)
        toolbar_layout.addWidget(self.verify_selected_button)
        toolbar_layout.addWidget(export_csv_button)
        toolbar_layout.addWidget(stats_button)
        toolbar_layout.addWidget(self.course_summary, 1)
        layout.addWidget(toolbar)

        self.course_table = self._make_table(
            ("日期", "课程", "签到时间", "类型", "状态", "签到 ID"),
            (130, 340, 160, 100, 90, 120),
        )
        layout.addWidget(self.course_table, 1)
        # 信号槽集中连接（B5）：仅位置收拢至 build 尾，连接序与语义不变
        self.time_range_combo.currentTextChanged.connect(lambda _text: self._refresh_course_table())
        refresh_button.clicked.connect(self.refresh_course_rollcalls)
        self.only_unsigned_check.stateChanged.connect(self._refresh_course_table)
        self.verify_selected_button.clicked.connect(self._verify_selected_rollcall)
        export_csv_button.clicked.connect(self._export_course_csv)
        stats_button.clicked.connect(self._show_course_stats)
        self.course_table.itemSelectionChanged.connect(self._update_verify_button_state)
        return page

    def refresh_course_rollcalls(self, silent=False):
        if not self._require_login(need_account=True, silent=silent):
            return
        if self.course_refresh_in_progress:
            return
        self.course_refresh_in_progress = True
        self.course_summary.setText("正在刷新签到情况...")
        self.log("开始刷新签到情况。")
        # Qt 控件只允许 GUI 线程访问：在此取快照传入工作线程，
        # 否则 worker 线程直接读 combo 会偶发崩溃/未定义行为
        academic_year = self.academic_year_combo.currentText().strip()
        semester = self.semester_combo.currentText().strip()
        self._run_thread(self._course_rollcalls_worker, silent, academic_year, semester)

    def _course_rollcalls_worker(self, silent=False, academic_year="", semester=""):
        # 账号快照：登出/换号后晚到的结果据此被 handler 丢弃（体检报告 P1-2）
        worker_account_id = str((self.account or {}).get("id") or "")
        # 全程用克隆会话：requests.Session 非线程安全，登录完成后本 worker 与
        # 课件课程 worker 并发启动（_refresh_after_login），直接共用主 Session
        # 会竞争 cookiejar/连接池；而 fetch_course_rollcall_records 入口的
        # profile+课程列表请求不走其内部线程级 clone，必须在此隔离。
        # 只读 GET 用完即弃，不回写主会话。
        worker_session = clone_session(self.session) if self.session is not None else None
        if worker_session is None:
            self._emit(("course_rollcalls_error", "登录状态已变更，请重新登录。", silent, worker_account_id))
            return
        try:
            username = str(self.account.get("username", "")) if self.account else ""
            records, source = fetch_course_rollcall_records(
                worker_session,
                username=username,
                academic_year=academic_year,
                semester=semester,
            )
            # 事件顺序契约（真机验收教训）：必须先发首屏聚合状态、再跑阶段二
            # 核实——顺序颠倒时 verified 先落地、随后被 course_rollcalls 的
            # 全新记录整体覆盖，摘要也会卡在「正在核实…」。
            self._emit(("course_rollcalls", records, source, worker_account_id))
            verified = verify_recent_rollcall_records(worker_session, username, records)
            self._emit(("course_records_verified", verified, worker_account_id, "auto"))
        except Exception as exc:
            self._emit(("course_rollcalls_error", str(exc), silent, worker_account_id))

    def _verify_selected_rollcall(self):
        """L2 手动核实：对表格选中行按本人签到明细判定状态并原位更新。"""
        if not self._require_login(need_account=True):
            return
        if self.course_verify_in_progress:
            return
        rollcall_id = self._selected_course_rollcall_id()
        if not rollcall_id:
            QMessageBox.information(self, "未选中记录", "请先在表格中选中一条具体签到记录。")
            return
        record = next((r for r in self.course_records if r.rollcall_id == rollcall_id), None)
        if record is None:
            QMessageBox.information(self, "未找到记录", "该行数据已过期，请先刷新签到情况。")
            return
        self.course_verify_in_progress = True
        self._update_verify_button_state()
        self.course_summary.setText(f"正在核实《{record.course_title}》的签到状态...")
        self.log(f"开始核实所选签到：{record.course_title} / {record.rollcall_time}。")
        username = str(self.account.get("username", ""))
        worker_account_id = str((self.account or {}).get("id") or "")
        # 快照传值：worker 不与 GUI 共享可变记录对象
        self._run_thread(self._course_verify_one_worker, replace(record), username, worker_account_id)

    def _course_verify_one_worker(self, record: CourseRollcallRecord, username: str, worker_account_id: str):
        # 克隆会话：与 GUI 线程/其他 worker 隔离（同 _course_rollcalls_worker 纪律）
        worker_session = clone_session(self.session) if self.session is not None else None
        if worker_session is None:
            self._emit(("course_records_verify_error", "登录状态已变更，请重新登录。", worker_account_id))
            return
        try:
            detail = fetch_student_rollcall_detail(worker_session, record.rollcall_id)
            verdict = verify_own_status(detail, username, record.signed_status)
        except Exception as exc:
            # 会话过期等失败走独立错误事件：GUI 侧解锁按钮并给重试横幅
            self._emit(("course_records_verify_error", str(exc), worker_account_id))
            return
        updated = replace(
            record,
            signed_status=verdict or record.signed_status,
            verified=verdict is not None,
        )
        self._emit(("course_records_verified", [updated], worker_account_id, "manual"))

    def _course_status_text(self, status: str) -> str:
        return COURSE_STATUS_DISPLAY.get(status, status or "未知")

    @staticmethod
    def _course_status_tooltip(record: CourseRollcallRecord) -> str:
        """状态格 tooltip：如实区分「本人明细核实」与「聚合推断」两个可信级。"""
        if record.verified:
            return "已按本人签到明细核实"
        return "聚合状态仅供参考，可选中后点『核实所选』"

    def _course_status_priority(self, record: CourseRollcallRecord) -> int:
        return COURSE_STATUS_PRIORITY.get(self._course_status_text(record.signed_status), 1)

    def _course_record_datetime(self, record: CourseRollcallRecord) -> datetime | None:
        # 解析逻辑已下沉为 core.parse_rollcall_time 纯函数（L1 选样复用），此处委托
        return parse_rollcall_time(record.rollcall_time)

    def _course_record_in_time_range(self, record: CourseRollcallRecord) -> bool:
        selected = self.time_range_combo.currentText() if hasattr(self, "time_range_combo") else "本学期"
        if selected == "本学期":
            return True

        record_time = self._course_record_datetime(record)
        if record_time is None:
            return False

        today = date.today()
        record_date = record_time.date()
        if selected == "今天":
            return record_date == today
        if selected == "本周":
            week_start = today - timedelta(days=today.weekday())
            week_end = week_start + timedelta(days=6)
            return week_start <= record_date <= week_end
        return True

    def _course_group_label(self, record: CourseRollcallRecord) -> tuple[str, date | None]:
        record_time = self._course_record_datetime(record)
        if record_time is None:
            return "时间未知", None
        record_date = record_time.date()
        if record_date == date.today():
            return "今天", record_date
        return record_date.isoformat(), record_date

    def _sorted_course_records(self, records: list[CourseRollcallRecord]) -> list[CourseRollcallRecord]:
        def sort_time(record: CourseRollcallRecord) -> float:
            record_time = self._course_record_datetime(record)
            if record_time is None:
                return 0
            return record_time.toordinal() * 86400 + record_time.hour * 3600 + record_time.minute * 60 + record_time.second

        return sorted(
            records,
            key=lambda record: (
                self._course_status_priority(record),
                -sort_time(record),
                record.course_title,
            ),
        )

    def _insert_course_group_row(self, row: int, label: str, count: int) -> None:
        self.course_table.insertRow(row)
        item = QTableWidgetItem(f"{label}  ({count} 条)")
        item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsSelectable)
        item.setFont(QFont("Microsoft YaHei UI", 10, QFont.Weight.DemiBold))
        pal = self._ui_palette()
        item.setForeground(QColor(pal["group_row_fg"]))
        item.setBackground(QColor(pal["group_row_bg"]))
        self.course_table.setItem(row, 0, item)
        self.course_table.setSpan(row, 0, 1, self.course_table.columnCount())

    def _insert_course_record_row(self, row: int, group_label: str, record: CourseRollcallRecord) -> None:
        self.course_table.insertRow(row)
        status_text = self._course_status_text(record.signed_status)
        values = (
            group_label,
            record.course_title,
            record.rollcall_time,
            record.rollcall_type,
            status_text,
            record.rollcall_id,
        )
        for column, value in enumerate(values):
            item = QTableWidgetItem(str(value))
            if column in (0, 2, 3, 4, 5):
                item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            if column == 4:
                self._style_status_item(item, status_text)
                item.setToolTip(self._course_status_tooltip(record))
            self.course_table.setItem(row, column, item)

    def _refresh_course_table(self):
        records = [record for record in self.course_records if self._course_record_in_time_range(record)]
        if self.only_unsigned_check.isChecked():
            records = [record for record in records if self._course_status_text(record.signed_status) == "未签"]

        self.course_table.setRowCount(0)
        if not records:
            if not self.course_records:
                empty_message = "登录后可查看签到情况"
            elif self.time_range_combo.currentText() == "今天":
                empty_message = "今天暂无签到记录"
            elif self.time_range_combo.currentText() == "本周":
                empty_message = "本周暂无签到记录"
            else:
                empty_message = "当前学期暂无签到记录"
            self._set_table_empty_state(self.course_table, empty_message)
        else:
            grouped: dict[str, list[CourseRollcallRecord]] = {}
            group_dates: dict[str, date | None] = {}
            for record in records:
                label, group_date = self._course_group_label(record)
                grouped.setdefault(label, []).append(record)
                group_dates[label] = group_date

            labels = sorted(
                grouped,
                key=lambda label: group_dates[label] or date.min,
                reverse=True,
            )
            row = 0
            for label in labels:
                group_records = self._sorted_course_records(grouped[label])
                self._insert_course_group_row(row, label, len(group_records))
                row += 1
                for record in group_records:
                    self._insert_course_record_row(row, label, record)
                    row += 1

        # 摘要行：全量计数 + 筛选可见数（核实事件的定向更新路径复用同一实现）
        self._write_course_summary()

    def _write_course_summary(self, suffix: str = "") -> None:
        """按当前内存记录与筛选状态重写摘要行（不重建表格）。

        suffix：追加在末尾的核实进度词（如「；核实完成」）。
        """
        text = self._course_summary_base_text()
        if self.only_unsigned_check.isChecked():
            visible = sum(
                1 for record in self.course_records
                if self._course_record_in_time_range(record)
                and self._course_status_text(record.signed_status) == "未签"
            )
            text += f"；当前显示 {visible} 条未签"
        self.course_summary.setText(text + suffix)

    def _course_summary_base_text(self) -> str:
        """全量记录的状态计数摘要（不含筛选后缀），整表刷新与定向更新共用。"""
        # 单遍累加状态计数，避免 4 次 sum() 各自全量遍历 course_records
        status_counts: dict[str, int] = {}
        for record in self.course_records:
            status_counts[record.signed_status] = status_counts.get(record.signed_status, 0) + 1
        signed = status_counts.get("已签到", 0)
        unsigned = status_counts.get("未签到", 0)
        unknown = status_counts.get("未知", 0)
        no_rollcalls = status_counts.get("无签到记录", 0)
        return f"共 {len(self.course_records)} 条；已签 {signed}；未签 {unsigned}；未知 {unknown}；无记录 {no_rollcalls}"

    def _visible_course_records(self) -> list[CourseRollcallRecord]:
        """当前筛选（时间范围 + 只看未签）下的可见记录——导出与统计共用同一口径，
        保证「导出即所见、统计即所见」。"""
        records = [
            record for record in self.course_records
            if self._course_record_in_time_range(record)
        ]
        if self.only_unsigned_check.isChecked():
            records = [
                record for record in records
                if self._course_status_text(record.signed_status) == "未签"
            ]
        return records

    def _export_course_csv(self):
        """把当前筛选后的签到记录导出为 CSV（utf-8-sig，Excel 可直接打开）。"""
        records = self._visible_course_records()
        if not records:
            QMessageBox.information(self, "导出 CSV", "当前筛选下没有签到记录可导出。")
            return
        default_name = f"xmu签到情况_{date.today().isoformat()}.csv"
        path, _selected = QFileDialog.getSaveFileName(self, "导出 CSV", default_name, "CSV 文件 (*.csv)")
        if not path:
            return
        try:
            # utf-8-sig：带 BOM，Excel 双击直开不乱码；newline="" 交给 csv 已生成的行尾
            with open(path, "w", encoding="utf-8-sig", newline="") as file:
                file.write(course_rollcall_csv(records))
        except OSError as exc:
            QMessageBox.warning(self, "导出失败", f"写入文件失败：{exc}")
            return
        QMessageBox.information(self, "导出成功", f"已导出 {len(records)} 条签到记录：\n{path}")

    def _show_course_stats(self):
        """按课程聚合的签到率统计（纯本地计算，弹窗表格展示）。"""
        records = [
            record for record in self.course_records
            if self._course_record_in_time_range(record)
        ]
        stats = course_rollcall_stats(records)
        if not stats:
            QMessageBox.information(self, "签到率统计", "当前时间范围内没有签到记录。")
            return
        dialog = QDialog(self)
        dialog.setWindowTitle(f"签到率统计（{self.time_range_combo.currentText()}）")
        dialog.resize(560, 380)
        layout = QVBoxLayout(dialog)
        table = self._make_table(
            ("课程", "签到数", "已签", "未签", "未知", "签到率"),
            (200, 70, 60, 60, 60, 80),
        )
        for row, stat in enumerate(stats):
            table.insertRow(row)
            values = (
                stat.course_title,
                str(stat.total_rollcalls),
                str(stat.signed),
                str(stat.unsigned),
                str(stat.unknown),
                f"{stat.signed_rate:.0%}",
            )
            for column, value in enumerate(values):
                table.setItem(row, column, QTableWidgetItem(value))
        table.sortItems(0, Qt.SortOrder.AscendingOrder)
        layout.addWidget(table, 1)
        note = QLabel("「无签到记录」的占位行不计入签到数与签到率分母；未知状态按未签出勤计。")
        note.setObjectName("Subtle")
        layout.addWidget(note)
        dialog.exec()

    def _selected_course_rollcall_id(self) -> str:
        """当前选中行的第 6 列（签到 ID）；组行/空态行/未选中返回空串。"""
        if not hasattr(self, "course_table"):
            return ""
        items = self.course_table.selectedItems()
        if not items:
            return ""
        id_item = self.course_table.item(items[0].row(), 5)
        if id_item is None:
            return ""
        text = id_item.text().strip()
        return "" if text == "-" else text

    def _update_verify_button_state(self):
        """选中有效记录且无核实进行中时启用「核实所选」。"""
        if not hasattr(self, "verify_selected_button"):
            return
        self.verify_selected_button.setEnabled(
            not self.course_verify_in_progress and bool(self._selected_course_rollcall_id())
        )

    def _apply_verified_records(self, updates) -> int:
        """把核实结果按 rollcall_id 定向写回内存与表格，返回实际变化条数。

        默认原位替换状态格文本/样式/tooltip——不重建表格，保住滚动位置与
        选中行；「只显示未签」开启时定向删除会让行号错乱，退化为整表重建，
        但先保存滚动位置与选中行再恢复，体感一致。
        """
        by_id: dict[str, CourseRollcallRecord] = {}
        for record in updates or []:
            rid = str(record.rollcall_id or "").strip()
            if rid and rid != "-":
                by_id[rid] = record
        if not by_id:
            return 0

        changed = 0
        for index, record in enumerate(self.course_records):
            updated = by_id.get(record.rollcall_id)
            if updated is None:
                continue
            if (record.signed_status, record.verified) != (updated.signed_status, updated.verified):
                changed += 1
            self.course_records[index] = updated

        if self.only_unsigned_check.isChecked():
            bar = self.course_table.verticalScrollBar()
            scroll_pos = bar.value()
            selected_id = self._selected_course_rollcall_id()
            self._refresh_course_table()
            row = self._course_row_of_rollcall_id(selected_id)
            if row >= 0:
                self.course_table.selectRow(row)
            bar.setValue(scroll_pos)
            return changed

        # 定向原位更新：只改状态格，不触碰其余单元格
        for row in range(self.course_table.rowCount()):
            id_item = self.course_table.item(row, 5)
            if id_item is None:
                continue
            updated = by_id.get(id_item.text().strip())
            if updated is None:
                continue
            status_item = self.course_table.item(row, 4)
            if status_item is None:
                continue
            status_text = self._course_status_text(updated.signed_status)
            status_item.setText(status_text)
            self._style_status_item(status_item, status_text)
            status_item.setToolTip(self._course_status_tooltip(updated))
        return changed

    def _course_row_of_rollcall_id(self, rollcall_id: str) -> int:
        if not rollcall_id:
            return -1
        for row in range(self.course_table.rowCount()):
            id_item = self.course_table.item(row, 5)
            if id_item is not None and id_item.text().strip() == rollcall_id:
                return row
        return -1
