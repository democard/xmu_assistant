"""课程课件页混入（从 app.py 第三刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得课程课件页能力；方法体逐字保留搬移前的实现，
依赖的实例属性（courseware_* 控件与状态/session/account）与宿主回调
（_panel/_academic_year_options/_make_table/_run_thread/_emit/_show_toast/
_update_nav_badges/_open_path/_ui_palette/log）仍由 DashboardWindow 持有。
事件处理器 _ev_courseware_* 与 _EVENT_HANDLERS 表仍留在 app.py（分发表单一来源）。
"""

from __future__ import annotations

import webbrowser
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QPixmap
from PySide6.QtWidgets import (
    QAbstractItemView,
    QComboBox,
    QFileDialog,
    QFrame,
    QGraphicsOpacityEffect,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..courseware import (
    CourseSummary,
    CoursewareItem,
    download_courseware,
    fetch_courses,
    fetch_courseware,
    sanitize_filename,
)
from ..notifications import friendly_error_message
from ..utils import clone_session
from .core import current_academic_year_label
from .icons import app_asset_path


class CoursewarePageMixin:
    REQUIRED_HOST_ATTRS: tuple[str, ...] = (
        "_academic_year_options",
        "_emit",
        "_make_table",
        "_open_path",
        "_panel",
        "_require_login",
        "_run_thread",
        "_show_toast",
        "_ui_palette",
        "_update_nav_badges",
        "account",
        "log",
        "session",
    )

    """课程课件页构建、刷新 worker、下载链路与表格渲染（无自有状态，全部经由宿主窗口）。"""

    def _build_courseware_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)

        toolbar = self._panel("筛选")
        toolbar_layout = QHBoxLayout(toolbar)
        toolbar_layout.setContentsMargins(16, 40, 16, 16)
        self.courseware_academic_year_combo = QComboBox()
        self.courseware_academic_year_combo.addItems(self._academic_year_options())
        self.courseware_academic_year_combo.setCurrentText(current_academic_year_label())
        self.courseware_academic_year_combo.setFixedWidth(130)
        self.courseware_semester_combo = QComboBox()
        self.courseware_semester_combo.addItems(("全部", "第一学期", "第二学期", "第三学期"))
        self.courseware_semester_combo.setFixedWidth(110)
        self.courseware_combo = QComboBox()
        self.courseware_combo.setMinimumWidth(300)
        refresh_courses = QPushButton("刷新课程")
        refresh_items = QPushButton("刷新课件")
        refresh_items.setObjectName("PrimaryButton")
        self.courseware_summary = QLabel("登录后自动读取课程和课件。")
        self.courseware_summary.setObjectName("Subtle")
        toolbar_layout.addWidget(QLabel("学年"))
        toolbar_layout.addWidget(self.courseware_academic_year_combo)
        toolbar_layout.addWidget(QLabel("学期"))
        toolbar_layout.addWidget(self.courseware_semester_combo)
        toolbar_layout.addWidget(QLabel("课程"))
        toolbar_layout.addWidget(self.courseware_combo)
        toolbar_layout.addWidget(refresh_courses)
        toolbar_layout.addWidget(refresh_items)
        toolbar_layout.addWidget(self.courseware_summary, 1)
        layout.addWidget(toolbar)

        self.courseware_table = self._make_table(
            ("", "章节", "课件活动", "文件名", "类型", "下载状态"),
            (46, 130, 330, 360, 100, 140),
        )
        self.courseware_table.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        layout.addWidget(self.courseware_table, 1)
        self.courseware_empty_frame = QFrame()
        self.courseware_empty_frame.setObjectName("EmptyStateCard")
        empty_layout = QVBoxLayout(self.courseware_empty_frame)
        empty_layout.setContentsMargins(18, 18, 18, 18)
        empty_layout.setSpacing(8)
        empty_layout.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.courseware_empty_logo = QLabel()
        self.courseware_empty_logo.setObjectName("EmptyWatermark")
        self.courseware_empty_logo.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.courseware_empty_logo.setFixedSize(78, 78)
        empty_logo = QPixmap(str(app_asset_path("xmu-assistant-mark.png")))
        if not empty_logo.isNull():
            self.courseware_empty_logo.setPixmap(
                empty_logo.scaled(
                    70,
                    70,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )
            opacity = QGraphicsOpacityEffect(self.courseware_empty_logo)
            opacity.setOpacity(0.18)
            self.courseware_empty_logo.setGraphicsEffect(opacity)
        self.courseware_empty_hint = QLabel("登录后会自动读取课程，也可以点击刷新课程。")
        self.courseware_empty_hint.setObjectName("EmptyStateText")
        self.courseware_empty_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        empty_layout.addWidget(self.courseware_empty_logo)
        empty_layout.addWidget(self.courseware_empty_hint)
        layout.addWidget(self.courseware_empty_frame)

        actions = QHBoxLayout()
        download_button = QPushButton("下载")
        download_button.setObjectName("PrimaryButton")
        download_button.setToolTip("下载当前选中的课件；需要全部下载时先点全选。")
        select_all_button = QPushButton("全选")
        select_all_button.setToolTip("选中当前课程下读取到的所有课件。")
        open_button = QPushButton("打开平台页面")
        choose_button = QPushButton("选择目录")
        self.courseware_download_dir = QLineEdit(str(Path.home() / "Downloads" / "XMU-Courseware"))
        actions.addWidget(download_button)
        actions.addWidget(select_all_button)
        actions.addWidget(open_button)
        actions.addWidget(choose_button)
        actions.addWidget(self.courseware_download_dir, 1)
        layout.addLayout(actions)

        # 信号槽集中连接（B5）：仅位置收拢至 build 尾，连接序与语义不变
        self.courseware_academic_year_combo.currentTextChanged.connect(
            lambda _text: self._apply_courseware_course_filters()
        )
        self.courseware_semester_combo.currentTextChanged.connect(lambda _text: self._apply_courseware_course_filters())
        self.courseware_combo.currentTextChanged.connect(lambda _text: self.refresh_selected_courseware(silent=True))
        refresh_courses.clicked.connect(self.refresh_courseware_courses)
        refresh_items.clicked.connect(self.refresh_selected_courseware)
        self.courseware_table.cellDoubleClicked.connect(lambda _row, _column: self.activate_selected_courseware())
        download_button.clicked.connect(self.download_selected_courseware)
        select_all_button.clicked.connect(self.select_all_courseware)
        open_button.clicked.connect(self.open_selected_courseware)
        choose_button.clicked.connect(self.choose_courseware_download_dir)
        return page

    def _short_courseware_error(self, error) -> str:
        raw = str(error or "")
        lowered = raw.lower()
        if any(keyword in lowered for keyword in ("timeout", "timed out", "connection", "network", "dns", "proxy")):
            return "网络失败"
        if any(keyword in lowered for keyword in ("401", "403", "unauthorized", "forbidden", "permission")):
            return "登录过期"
        if any(keyword in raw for keyword in ("登录态", "无权访问", "权限", "拒绝")):
            return "登录过期"
        if any(keyword in lowered for keyword in ("404", "not found")):
            return "平台未提供地址"
        if any(keyword in raw for keyword in ("资源", "地址", "reference_id", "签名", "未返回", "缺少", "失效")):
            return "平台未提供地址"
        message = friendly_error_message(error, "courseware")
        if "网络" in message:
            return "网络失败"
        if "权限" in message or "登录已过期" in message:
            return "登录过期"
        if any(word in message for word in ("资源", "地址", "提供")):
            return "平台未提供地址"
        return "下载失败"

    def refresh_courseware_courses(self, silent=False):
        if not self._require_login(silent=silent):
            return
        if self.courseware_courses_refresh_in_progress:
            return
        self.courseware_courses_refresh_in_progress = True
        self.courseware_summary.setText("正在读取课程列表...")
        self.log("开始读取课件课程列表。")
        self._run_thread(self._courseware_courses_worker, silent)

    def _courseware_courses_worker(self, silent=False):
        # 账号快照：同 _course_rollcalls_worker（体检报告 P1-2）
        worker_account_id = str((self.account or {}).get("id") or "")
        # 克隆会话：与签到情况 worker 并发启动（_refresh_after_login），
        # 不能直接共用主 Session（requests.Session 非线程安全）
        worker_session = clone_session(self.session) if self.session is not None else None
        if worker_session is None:
            self._emit(("courseware_courses_error", "登录状态已变更，请重新登录。", silent, worker_account_id))
            return
        try:
            courses, source = fetch_courses(worker_session)
            self._emit(("courseware_courses", courses, source, worker_account_id))
        except Exception as exc:
            self._emit(("courseware_courses_error", str(exc), silent, worker_account_id))

    def refresh_selected_courseware(self, silent=False):
        if not self._require_login(silent=silent):
            return
        course = self._selected_courseware_course()
        if not course:
            if not silent:
                QMessageBox.information(self, "未选择课程", "请先选择一门课程。")
            return
        if self.courseware_refresh_in_progress:
            return
        self.courseware_refresh_in_progress = True
        self.courseware_summary.setText(f"正在读取《{course.title}》课件...")
        self.log(f"开始读取课程课件：{course.title}。")
        self._run_thread(self._courseware_worker, course, silent)

    def _courseware_worker(self, course: CourseSummary, silent=False):
        # 账号快照：课件详情是 8 线程池逐活动抓取，大课程可跑几十秒，
        # 晚到结果据此被 handler 丢弃（P1-2，同 courseware_courses 范式）；
        # 先于会话克隆取（同 _courseware_courses_worker）——会话为 None 的早退
        # 分支也要携带，空串会被 late_worker_result_accepted 视为接受
        worker_account_id = str((self.account or {}).get("id") or "")
        # 克隆会话：fetch_courseware 入口的活动列表/modules 请求不走其内部
        # 线程级 clone，在此隔离主 Session（同 _courseware_courses_worker 纪律）
        worker_session = clone_session(self.session) if self.session is not None else None
        if worker_session is None:
            self._emit(("courseware_error", "登录状态已变更，请重新登录。", silent, worker_account_id))
            return
        try:
            self._emit(("courseware", course, fetch_courseware(worker_session, course.course_id), worker_account_id))
        except Exception as exc:
            self._emit(("courseware_error", str(exc), silent, worker_account_id))

    def choose_courseware_download_dir(self):
        selected = QFileDialog.getExistingDirectory(
            self,
            "选择课件下载目录",
            self.courseware_download_dir.text() or str(Path.home()),
        )
        if selected:
            self.courseware_download_dir.setText(selected)

    def download_selected_courseware(self):
        rows = self._selected_courseware_rows()
        if not rows:
            QMessageBox.information(self, "未选择", "请先选择课件，或点击全选后再下载。")
            return
        selected_items = [self.courseware_items[row] for row in rows if row < len(self.courseware_items)]
        self._start_courseware_download(selected_items)

    def select_all_courseware(self):
        if not self.courseware_items:
            QMessageBox.information(self, "没有课件", "当前课程还没有读取到课件。")
            return
        for row in range(self.courseware_table.rowCount()):
            checkbox = self.courseware_table.item(row, 0)
            if checkbox:
                checkbox.setCheckState(Qt.CheckState.Checked)
        self.courseware_table.selectAll()
        self.courseware_summary.setText(f"已全选 {len(self.courseware_items)} 条课件，点击下载开始处理。")
        self._show_toast(f"已全选 {len(self.courseware_items)} 条课件")

    def _selected_courseware_rows(self) -> list[int]:
        rows = {
            row
            for row in range(self.courseware_table.rowCount())
            if self.courseware_table.item(row, 0)
            and self.courseware_table.item(row, 0).checkState() == Qt.CheckState.Checked
        }
        if rows:
            return sorted(rows)
        rows = {
            index.row()
            for index in self.courseware_table.selectionModel().selectedRows()
            if index.row() >= 0
        }
        if not rows and self.courseware_table.currentRow() >= 0:
            rows.add(self.courseware_table.currentRow())
        return sorted(rows)

    def activate_selected_courseware(self):
        row = self.courseware_table.currentRow()
        if row < 0 or row >= len(self.courseware_items):
            return
        item = self.courseware_items[row]
        self._start_courseware_download([item])

    def open_selected_courseware(self):
        row = self.courseware_table.currentRow()
        if row < 0 or row >= len(self.courseware_items):
            QMessageBox.information(self, "未选择", "请先选择一个课件活动。")
            return
        item = self.courseware_items[row]
        url = item.entry_url
        if not webbrowser.open(url):
            QMessageBox.critical(self, "打开失败", "无法调用系统浏览器打开平台页面。")

    def _start_courseware_download(self, items: list[CoursewareItem]):
        if self.courseware_download_in_progress:
            QMessageBox.information(self, "正在下载", "请等待当前下载任务完成。")
            return
        course = self._selected_courseware_course()
        if not course:
            return
        if not self.courseware_download_dir.text().strip():
            # 空目录文本：Path("")/课程名 得相对路径，会在启动目录（开始菜单启动
            # 时可能是系统目录）静默建目录写文件——前置拦截
            QMessageBox.warning(self, "下载目录为空", "请先在上方填写下载目录。")
            return
        self.courseware_download_in_progress = True
        for item in items:
            self.courseware_download_status[self._courseware_key(item)] = "下载中"
        self._refresh_courseware_table()
        self._update_nav_badges()
        destination = Path(self.courseware_download_dir.text()) / sanitize_filename(course.title)
        self.courseware_summary.setText(f"准备处理 {len(items)} 个课件...")
        self._show_toast(f"开始处理 {len(items)} 个课件")
        self._run_thread(self._courseware_download_worker, items, destination)

    def _courseware_download_worker(self, items: list[CoursewareItem], destination: Path):
        downloaded = []
        entries = []
        errors = []
        raw_errors = []
        # 会话与账号快照（与 _answer_worker 同范式）：下载全程用克隆会话——独立
        # cookiejar，不与 GUI 线程/其它 worker 竞争写主 jar；账号 id 快照用于逐项
        # 校验，防止登出在途时误报失败、换号后用新账号会话续下旧账号课件（跨账号污染）。
        # 批次级 clone 用完即弃：不做模块级缓存（D4 教训——模块级会话会滞留旧 cookie）。
        worker_session = clone_session(self.session) if self.session is not None else None
        worker_account_id = str((self.account or {}).get("id") or "")
        try:
            for index, item in enumerate(items, start=1):
                key = self._courseware_key(item)
                self._emit(("courseware_download_progress", index, len(items), item.filename or item.activity_title, key))
                # 逐项校验登录状态：网络提交不可中断，只能在每项开始前拦截。
                if (
                    worker_session is None
                    or self.session is None
                    or str((self.account or {}).get("id") or "") != worker_account_id
                ):
                    name = item.filename or item.activity_title
                    errors.append(f"{name}：已取消（登录状态已变更）")
                    self._emit(("courseware_download_item_done", key, "已取消（登录状态已变更）"))
                    continue
                try:
                    target = download_courseware(worker_session, item, destination)
                    if target.suffix.lower() == ".url":
                        entries.append(target)
                    else:
                        downloaded.append(target)
                    self._emit(("courseware_download_item_done", key, "下载成功"))
                except Exception as exc:
                    name = item.filename or item.activity_title
                    errors.append(f"{name}：{friendly_error_message(exc, 'courseware')}")
                    raw_errors.append(f"{name}：{exc}")
                    self._emit(("courseware_download_item_done", key, f"下载失败：{self._short_courseware_error(exc)}"))
        finally:
            # M4：无论成功还是中途异常都必须发出完成事件（GUI 据此复位
            # courseware_download_in_progress），否则异常逃逸会让后续下载永久被拦。
            # 先合并克隆内新增/旋转的 cookie 回主会话（GUI 单点写），再发完成事件。
            self._emit(("merge_session_cookies", worker_session, worker_account_id))
            self._emit(("courseware_download_done", downloaded, entries, errors, destination, raw_errors))

    def _show_courseware_download_result(self, downloaded, entries, errors, destination):
        title = "部分下载失败" if errors else "下载完成"
        icon = QMessageBox.Icon.Warning if errors else QMessageBox.Icon.Information
        message = QMessageBox(icon, title, "", QMessageBox.StandardButton.Ok, self)
        message.setText(f"文件 {len(downloaded)} 个，入口 {len(entries)} 个，失败 {len(errors)} 个。")
        details = ""
        if errors:
            details = "\n".join(errors[:20])
            message.setInformativeText("有些课件没有成功处理，下面列出了原因。")
            message.setDetailedText(details)
        else:
            message.setInformativeText(f"已保存到：\n{destination}")
        open_button = message.addButton("打开下载目录", QMessageBox.ButtonRole.ActionRole)
        message.exec()
        if message.clickedButton() is open_button:
            self._open_path(destination)

    def _selected_courseware_course(self) -> CourseSummary | None:
        return self.courseware_course_by_display.get(self.courseware_combo.currentText())

    def _courseware_course_matches_filters(self, course: CourseSummary) -> bool:
        academic_year = self.courseware_academic_year_combo.currentText().strip()
        if academic_year and academic_year not in course.term and academic_year not in course.search_text:
            return False

        semester = self.courseware_semester_combo.currentText().strip()
        semester_code = course.semester_code or course.term
        if semester == "第一学期":
            return semester_code.endswith("-1")
        if semester == "第二学期":
            return semester_code.endswith("-2")
        if semester == "第三学期":
            return semester_code.endswith("-3")
        return True

    def _refresh_courseware_course_combo(self, previous: str = "") -> list[str]:
        self.courseware_course_by_display = {}
        displays = []
        for course in self.courseware_courses:
            if not self._courseware_course_matches_filters(course):
                continue
            display = course.display_name
            if display in self.courseware_course_by_display:
                display = f"{display}  ({course.course_id})"
            self.courseware_course_by_display[display] = course
            displays.append(display)

        self.courseware_combo.blockSignals(True)
        self.courseware_combo.clear()
        self.courseware_combo.addItem("请选择课程")
        self.courseware_combo.addItems(displays)
        if previous in self.courseware_course_by_display:
            self.courseware_combo.setCurrentText(previous)
        self.courseware_combo.blockSignals(False)
        return displays

    def _apply_courseware_course_filters(self):
        previous = self.courseware_combo.currentText()
        displays = self._refresh_courseware_course_combo(previous)
        if not self._selected_courseware_course():
            self._reset_courseware_selection_prompt()
        self.log(f"课件课程筛选完成，当前显示 {len(displays)} 门。")

    def _courseware_can_download(self, item: CoursewareItem) -> bool:
        return bool(item.entry_url) or bool(item.upload_id)

    def _set_courseware_courses(self, courses: list[CourseSummary], source: str):
        previous = self.courseware_combo.currentText()
        self.courseware_courses = courses
        displays = self._refresh_courseware_course_combo(previous)

        self.log(f"课件课程列表读取完成，共 {len(courses)} 门，当前筛选 {len(displays)} 门；接口：{source}")
        if self._selected_courseware_course():
            self.courseware_summary.setText(f"共 {len(courses)} 门课程；当前显示 {len(displays)} 门")
        else:
            self._reset_courseware_selection_prompt()
        self._refresh_courseware_empty_state()

    def _set_courseware_items(self, course: CourseSummary, items: list[CoursewareItem]):
        selected = self._selected_courseware_course()
        if not selected:
            self._refresh_courseware_empty_state()
            return
        if selected.course_id != course.course_id:
            self.refresh_selected_courseware(silent=True)
            return
        self.courseware_items = items
        self.courseware_download_status = {}
        self._refresh_courseware_table()
        direct = sum(1 for item in items if item.upload_id and item.upload_status == "ready")
        entries = sum(1 for item in items if not item.upload_id and item.entry_url)
        missing = sum(1 for item in items if not item.upload_id and not item.entry_url)
        self.courseware_summary.setText(f"已读取 {len(items)} 条课件；可直接下载 {direct} 条；需保存入口 {entries} 条；受限 {missing} 条")
        self.log(f"课程课件读取完成：{course.title}，共 {len(items)} 条，直接下载 {direct} 条，入口 {entries} 条。")
        self._refresh_courseware_empty_state()
        self._update_nav_badges()

    def _refresh_courseware_table(self):
        type_names = {
            "material": "资料",
            "online_video": "视频",
            "slide": "课件",
            "web_link": "链接",
            "page": "页面",
            "scorm": "SCORM",
            "h5_courseware": "H5课件",
        }
        checked_keys = {
            self._courseware_key(self.courseware_items[row])
            for row in range(min(self.courseware_table.rowCount(), len(self.courseware_items)))
            if self.courseware_table.item(row, 0)
            and self.courseware_table.item(row, 0).checkState() == Qt.CheckState.Checked
        }
        self.courseware_table.setRowCount(0)
        for row_index, item in enumerate(self.courseware_items):
            self.courseware_table.insertRow(row_index)
            checkbox = QTableWidgetItem("")
            checkbox.setFlags(
                Qt.ItemFlag.ItemIsEnabled
                | Qt.ItemFlag.ItemIsSelectable
                | Qt.ItemFlag.ItemIsUserCheckable
            )
            checkbox.setCheckState(
                Qt.CheckState.Checked
                if self._courseware_key(item) in checked_keys
                else Qt.CheckState.Unchecked
            )
            checkbox.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            self.courseware_table.setItem(row_index, 0, checkbox)

            values = (
                item.module_name,
                item.activity_title,
                item.filename or "-",
                type_names.get(item.activity_type, item.media_type or item.activity_type or "-"),
                self.courseware_download_status.get(self._courseware_key(item), "可下载"),
            )
            for column, value in enumerate(values, start=1):
                cell = QTableWidgetItem(str(value))
                if column in (1, 4, 5):
                    cell.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                if column == 5:
                    cell.setForeground(QColor(self._courseware_status_color(str(value))))
                self.courseware_table.setItem(row_index, column, cell)
        self._refresh_courseware_empty_state()

    def _refresh_courseware_empty_state(self):
        if not hasattr(self, "courseware_empty_hint"):
            return
        if self.courseware_refresh_in_progress or self.courseware_courses_refresh_in_progress:
            text = "正在读取课程课件，请稍候。"
        elif not self.account:
            text = "登录后会自动读取课程，也可以点击刷新课程。"
        elif not self.courseware_courses:
            text = "还没有读取到课程，请点击刷新课程或稍后再试。"
        elif not self._selected_courseware_course():
            text = "请选择课程"
        elif not self.courseware_items:
            text = "这门课暂时没有读取到课件。"
        else:
            text = ""
        self.courseware_empty_hint.setText(text)
        self.courseware_empty_frame.setVisible(bool(text))
        self.courseware_table.setVisible(not bool(text) or bool(self.courseware_items))

    def _reset_courseware_selection_prompt(self):
        if not hasattr(self, "courseware_combo"):
            return
        if self.courseware_combo.count() and self.courseware_combo.currentIndex() != 0:
            self.courseware_combo.blockSignals(True)
            self.courseware_combo.setCurrentIndex(0)
            self.courseware_combo.blockSignals(False)
        self.courseware_items = []
        self.courseware_download_status = {}
        self._refresh_courseware_table()
        self.courseware_summary.setText("请选择课程")
        self._update_nav_badges()

    def _courseware_key(self, item: CoursewareItem) -> str:
        return f"{item.course_id}:{item.activity_id}:{item.upload_id}:{item.filename}"

    def _courseware_status_color(self, status: str) -> str:
        pal = self._ui_palette()
        if status.startswith("下载失败") or status.startswith("已取消"):
            # 已取消（登录状态已变更）不是可下载态：回落绿色会伪装成可下载，
            # 与失败同用警示色，提示需要重新发起下载
            return pal["cw_failed"]
        return {
            "可下载": pal["cw_ready"],
            "下载中": pal["cw_downloading"],
            "下载成功": pal["cw_success"],
            "下载失败": pal["cw_failed"],
        }.get(status, pal["cw_ready"])
