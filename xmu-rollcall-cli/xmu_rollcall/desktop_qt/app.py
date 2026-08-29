"""PySide6 desktop dashboard for XMU Rollcall Bot."""

from __future__ import annotations

import os
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

import requests
from PySide6.QtCore import QObject, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont, QGuiApplication, QKeySequence, QPalette, QPixmap, QShortcut
from PySide6.QtWidgets import (
    QApplication,
    QFileDialog,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QAbstractItemView,
    QMessageBox,
    QStackedWidget,
    QSystemTrayIcon,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)
from xmulogin import xmulogin

from .icons import app_asset_path, app_icon
from .maintenance import _background_disk_cleanup
from .notifications_page import NotificationsPageMixin
from .settings_page import SettingsPageMixin
from .tutorial_page import TutorialPageMixin
from .overview_page import OverviewPageMixin
from .courseware_page import CoursewarePageMixin
from .courses_page import CoursesPageMixin
from .single_instance import (
    acquire_single_instance,
    activate_existing_instance,
    install_show_event_watcher,
)
from .startup_registry import StartupRegistryMixin
from .tray import TrayMixin
from ..config import (
    add_account,
    CONFIG_LOCK,
    get_app_settings,
    get_all_accounts,
    get_cookies_path,
    get_current_account,
    get_notification_settings,
    get_rollcall_settings,
    load_config,
    save_config,
    set_current_account,
    set_app_settings,
    set_rollcall_settings,
)
from ..courseware import (
    CourseSummary,
    CoursewareItem,
    reset_modules_cache,
)
from ..engine import RollcallEngine
from ..proxy_guard import disable_system_proxies
from ..notifications import (
    NotificationMessage,
    build_rollcall_notification,
    friendly_error_message,
    notification_provider_status,
)
from ..utils import (
    API_TIMEOUT,
    answer_failure_detail,
    base_url,
    clone_session,
    compute_auto_answer_delay,
    headers,
    late_worker_result_accepted,
    load_session,
    merge_worker_session_cookies,
    save_session,
    tune_session,
    verify_session,
)
from .core import (
    DEFAULT_POLL_INTERVAL_SECONDS,
    CourseRollcallRecord,
    MonitorWorker,
    RollcallEvent,
    current_academic_year_label,
    fetch_number_code,
    format_duration,
    format_log_export,
)
from .theme import (
    WINDOW_MIN_HEIGHT,
    WINDOW_MIN_WIDTH,
    dark_qpalette,
    palette as theme_palette,
    resolve_theme as resolve_theme_mode,
    stylesheet,
)


class EventBus(QObject):
    event = Signal(object)


MAX_EVENT_ROWS = 100
MAX_LOG_LINES = 300


from .ui_snapshot import (
    UI_SNAPSHOT_VERSION,
    UiSnapshotMixin,
    _ui_snapshot_path,
    ui_snapshot_from_json,
    ui_snapshot_to_json,
)


class DashboardWindow(
    TrayMixin,
    NotificationsPageMixin,
    TutorialPageMixin,
    SettingsPageMixin,
    StartupRegistryMixin,
    UiSnapshotMixin,
    OverviewPageMixin,
    CoursewarePageMixin,
    CoursesPageMixin,
    QMainWindow,
):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("xmu助手")
        self.setWindowIcon(app_icon())
        self.resize(1280, 780)
        self.setMinimumSize(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)

        self.bus = EventBus()
        self.bus.event.connect(self._handle_event)

        self.session = None
        self.account = None
        self.monitor_worker: MonitorWorker | None = None
        self.monitor_stop_event: threading.Event | None = None
        self.started_at: float | None = None
        # 自动延迟应答的取消信号表：手动应答/跳过/停止/登出时 set 立即打断等待。
        # worker 线程（登记/摘除）与 GUI 线程（取消）并发访问，配锁保证
        # 「比较+摘除」复合操作的原子性（TOCTOU：否则旧 worker 极端交错下
        # 会误删新 worker 刚登记的取消信号 → 新延迟应答无法取消、到点照常提交）。
        self._answer_cancellations: dict[str, threading.Event] = {}
        self._answer_cancellations_lock = threading.Lock()
        # 监控重启代数：真正启动/暂停各 +1；QTimer 排队的重启回调校验代数，
        # 排队期间若用户又点启动/暂停（代数变了）则放弃本次自动拉起，避免误重启。
        self._monitor_restart_epoch = 0
        # 登录代数：每次成功落地登录 +1，登出 +1；login/restore worker 启动时快照，
        # 晚到的 login_success 校验代数一致才落地——防止在途 worker 把刚登出的
        # 会话"复活"为已登录（状态错乱）。
        self._login_epoch = 0
        # 登录在途门：login() 可被连点/换号直登并发触发，双 worker 写盘顺序与
        # emit 顺序相反时会造成 UI 账号与磁盘当前账号漂移（见 login() 注释）
        self._login_in_progress = False

        self.event_sequence = 0
        self.events_by_id: dict[str, RollcallEvent] = {}
        self.event_order: list[str] = []
        self.course_records: list[CourseRollcallRecord] = []
        self.courseware_courses: list[CourseSummary] = []
        self.courseware_items: list[CoursewareItem] = []
        self.courseware_course_by_display: dict[str, CourseSummary] = {}
        self.courseware_download_status: dict[str, str] = {}

        self.course_refresh_in_progress = False
        # L2 手动「核实所选」互斥门：与整表刷新独立，进行中时禁用按钮
        self.course_verify_in_progress = False
        self.courseware_courses_refresh_in_progress = False
        self.courseware_refresh_in_progress = False
        self.courseware_download_in_progress = False
        # UI 快照归属的账号 id：启动恢复时记录，登录成功时校验防跨账号串号
        self._snapshot_account_id = ""
        # 当前生效主题（resolved 后的 light/dark），动态取色的依据
        self._current_theme_mode = "system"
        # 用户选择的原始主题模式（system/light/dark）：_apply_theme 会把
        # _current_theme_mode 覆写为已解析值（light/dark），运行期「跟随系统」
        # 联动重刷必须回读原始设定，否则系统切深浅永远不生效
        self._theme_mode_setting = "system"
        self.quitting = False
        self.tray_icon: QSystemTrayIcon | None = None
        self.log_messages: list[str] = []
        self.background_error_count = 0
        self.background_error_notified = False
        self.nav_titles = ("首页", "签到情况", "课程课件", "通知", "教程", "策略")

        self._build_ui()
        self._setup_tray()
        self._load_notification_settings()
        self._load_app_settings()
        # SWR 缓存先行：先渲染上次快照（若有），登录后的后台刷新会覆盖
        self._restore_ui_snapshot()
        self._load_current_account_hint()
        # 跟随系统模式下，系统深浅切换时联动重刷（Qt 6.5+ colorSchemeChanged）；
        # 必须传原始设定（system），传已解析值会把模式钉死在当前色
        try:
            QGuiApplication.styleHints().colorSchemeChanged.connect(
                lambda _scheme: self._apply_theme(self._theme_mode_setting)
            )
        except Exception:
            pass

        self.runtime_timer = QTimer(self)
        self.runtime_timer.timeout.connect(self._tick_runtime)
        self.runtime_timer.start(1000)

        QTimer.singleShot(400, self.auto_restore_current_session)

    def _build_ui(self):
        root = QWidget()
        root.setObjectName("Root")
        self.setCentralWidget(root)

        layout = QHBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        sidebar = QWidget()
        sidebar.setObjectName("Sidebar")
        sidebar.setFixedWidth(200)
        sidebar_layout = QVBoxLayout(sidebar)
        sidebar_layout.setContentsMargins(16, 18, 16, 18)
        sidebar_layout.setSpacing(14)

        brand_row = QHBoxLayout()
        brand_row.setSpacing(10)
        brand_logo = QLabel()
        brand_logo.setObjectName("SidebarLogo")
        brand_logo.setFixedSize(36, 36)
        brand_logo.setAlignment(Qt.AlignmentFlag.AlignCenter)
        brand_pixmap = QPixmap(str(app_asset_path("xmu-assistant-mark.png")))
        if not brand_pixmap.isNull():
            brand_logo.setPixmap(
                brand_pixmap.scaled(
                    34,
                    34,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )
        brand_copy = QVBoxLayout()
        brand_copy.setSpacing(1)
        brand = QLabel("xmu助手")
        brand.setObjectName("Brand")
        brand_copy.addWidget(brand)
        brand_row.addWidget(brand_logo)
        brand_row.addLayout(brand_copy, 1)
        sidebar_layout.addLayout(brand_row)

        self.nav = QListWidget()
        self.nav.setObjectName("Navigation")
        for title in self.nav_titles:
            item = QListWidgetItem(title)
            item.setData(Qt.ItemDataRole.UserRole, title)
            self.nav.addItem(item)
        self.nav.currentRowChanged.connect(self._show_page)
        sidebar_layout.addWidget(self.nav, 1)

        self.sidebar_status = QLabel("未登录")
        self.sidebar_status.setObjectName("StatusWarn")
        sidebar_layout.addWidget(self.sidebar_status)

        self.pages = QStackedWidget()
        content = QWidget()
        content_layout = QVBoxLayout(content)
        content_layout.setContentsMargins(20, 16, 20, 16)
        content_layout.setSpacing(14)

        self.page_title = QLabel("首页")
        self.page_title.setObjectName("PageTitle")
        self.top_status = QLabel("未登录")
        self.top_status.setObjectName("StatusWarn")
        title_row = QHBoxLayout()
        title_row.addWidget(self.page_title)
        title_row.addStretch(1)
        title_row.addWidget(self.top_status)
        content_layout.addLayout(title_row)
        content_layout.addWidget(self.pages, 1)
        self.toast_label = QLabel("")
        self.toast_label.setObjectName("Toast")
        self.toast_label.setVisible(False)
        content_layout.addWidget(self.toast_label)

        layout.addWidget(sidebar)
        layout.addWidget(content, 1)

        self.pages.addWidget(self._build_overview_page())
        self.pages.addWidget(self._build_courses_page())
        self.pages.addWidget(self._build_courseware_page())
        self.pages.addWidget(self._build_notifications_page())
        self.pages.addWidget(self._build_tutorial_page())
        self.pages.addWidget(self._build_settings_page())
        self.nav.setCurrentRow(0)
        self._setup_shortcuts()
        self._update_nav_badges()

    def _setup_shortcuts(self):
        """全局键盘加速器：Ctrl+1..6 直达页签；Ctrl+R 触发当前页刷新。"""
        for index in range(len(self.nav_titles)):
            shortcut = QShortcut(QKeySequence(f"Ctrl+{index + 1}"), self)
            shortcut.activated.connect(lambda checked=False, row=index: self._show_page(row))
        refresh_shortcut = QShortcut(QKeySequence("Ctrl+R"), self)
        refresh_shortcut.activated.connect(self._refresh_current_page)

    def _refresh_current_page(self):
        # 仅签到情况/课件页映射既有刷新入口；其余页面没有网络刷新语义，明确提示
        index = self.pages.currentIndex()
        if index == 1:
            self.refresh_course_rollcalls()
        elif index == 2:
            self.refresh_courseware_courses()
        else:
            title = self.nav_titles[index] if 0 <= index < len(self.nav_titles) else "当前"
            self._show_toast(f"{title}不支持刷新", ok=False)

    def _show_page(self, row: int):
        if row < 0:
            return
        # 同步侧边栏高亮：Ctrl+N 等入口不经导航点击；不同步时高亮停留在
        # 旧页，再点旧高亮项因 currentRow 未变不发信号，页签切不回去
        if self.nav.currentRow() != row:
            self.nav.setCurrentRow(row)  # 触发 currentRowChanged → 本方法，幂等收敛
        self.pages.setCurrentIndex(row)
        self.page_title.setText(self.nav_titles[row] if row < len(self.nav_titles) else "")

    def _show_toast(self, text: str, ok: bool = True, timeout_ms: int = 3200):
        if not hasattr(self, "toast_label"):
            return
        self.toast_label.setText(text)
        self.toast_label.setObjectName("ToastGood" if ok else "ToastWarn")
        self.toast_label.style().unpolish(self.toast_label)
        self.toast_label.style().polish(self.toast_label)
        self.toast_label.setVisible(True)
        # 自增代数：连续弹 toast 时旧 singleShot 到点不得提前隐藏新 toast
        self._toast_seq = getattr(self, "_toast_seq", 0) + 1
        toast_seq = self._toast_seq
        QTimer.singleShot(timeout_ms, lambda: self._hide_toast_if_current(toast_seq))

    def _hide_toast_if_current(self, toast_seq: int):
        if toast_seq == getattr(self, "_toast_seq", None) and hasattr(self, "toast_label"):
            self.toast_label.setVisible(False)

    def _nav_count_suffix(self, count: int) -> str:
        return f" · {count}" if count > 0 else ""

    def _update_nav_badges(self):
        if not hasattr(self, "nav"):
            return
        downloading = sum(1 for status in self.courseware_download_status.values() if status == "下载中")
        notification_suffix = ""
        try:
            statuses = notification_provider_status(self._notification_settings_from_ui())
            if "缺少信息" in statuses.values():
                notification_suffix = " · 缺配置"
        except Exception:
            notification_suffix = ""
        suffixes = {
            "课程课件": self._nav_count_suffix(downloading),
            "通知": notification_suffix,
        }
        for index, title in enumerate(self.nav_titles):
            item = self.nav.item(index)
            if item:
                item.setText(f"{title}{suffixes.get(title, '')}")

    def _panel(self, title: str) -> QFrame:
        frame = QFrame()
        frame.setObjectName("Panel")
        label = QLabel(title, frame)
        label.setObjectName("Subtle")
        label.move(16, 12)
        return frame

    def _signal_tile(self, layout: QGridLayout, row: int, column: int, label: str, value: str) -> QLabel:
        frame = QFrame()
        frame.setObjectName("SignalTile")
        frame.setMinimumHeight(64)
        frame_layout = QVBoxLayout(frame)
        frame_layout.setContentsMargins(14, 7, 14, 7)
        frame_layout.setSpacing(1)
        label_widget = QLabel(label)
        label_widget.setObjectName("SignalLabel")
        value_widget = QLabel(value)
        value_widget.setObjectName("SignalValue")
        value_widget.setMinimumHeight(22)
        value_widget.setWordWrap(True)
        frame_layout.addWidget(label_widget)
        frame_layout.addWidget(value_widget)
        layout.addWidget(frame, row, column)
        return value_widget

    def _academic_year_options(self) -> list[str]:
        current = current_academic_year_label()
        try:
            start_year = int(current.split("-", 1)[0])
        except (ValueError, IndexError):
            start_year = datetime.now().year
        return [f"{year}-{year + 1}" for year in range(start_year + 1, start_year - 6, -1)]

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
        # O(n) 重建：一次 setRowCount 后直接 setItem，
        # 避免 insertRow 循环触发 Qt 每行内部重排（O(n²)）。
        # 列布局（居中列/状态列）由调用点显式传入——通用助手不内嵌特定表知识。
        table.setRowCount(0)
        table.setRowCount(len(rows))
        for row_index, values in enumerate(rows):
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))
                if row_ids and column == 0:
                    item.setData(Qt.ItemDataRole.UserRole, row_ids[row_index])
                if column in centered_columns:
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                if status_column is not None and column == status_column:
                    self._style_status_item(item, str(value))
                table.setItem(row_index, column, item)

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

    def _run_thread(self, target, *args):
        threading.Thread(target=target, args=args, daemon=True).start()

    def _require_login(self, *, need_account: bool = False, silent: bool = False) -> bool:
        """刷新入口登录守卫：未登录（可选地也要求账号在位）时弹「尚未登录」提示。

        返回是否可继续。原四处近似样板（课件课程/课件详情/签到情况刷新/手动核实）
        收敛于此；文案统一为「请先登录。」。
        """
        logged_in = bool(self.session) and (not need_account or bool(self.account))
        if not logged_in and not silent:
            QMessageBox.warning(self, "尚未登录", "请先登录。")
        return logged_in

    def _emit(self, event: tuple):
        self.bus.event.emit(event)

    def _load_current_account_hint(self):
        try:
            account = get_current_account(load_config())
            if account:
                self.username_input.setText(account.get("username", ""))
                self._load_rollcall_settings(account)
        except Exception as exc:
            self.log(f"读取配置失败：{exc}")

    def _load_rollcall_settings(self, account: dict | None = None):
        if not hasattr(self, "poll_interval_spin"):
            return
        account = account or self.account
        if not account:
            return
        settings = get_rollcall_settings(account)
        value = settings.get("poll_interval_seconds", DEFAULT_POLL_INTERVAL_SECONDS)
        self.poll_interval_spin.blockSignals(True)
        self.poll_interval_spin.setValue(value)
        self.poll_interval_spin.blockSignals(False)

    def _save_poll_interval_setting(self, *_args):
        if not hasattr(self, "poll_interval_spin"):
            return
        try:
            # 读-改-写复合操作整体持 CONFIG_LOCK：与登录写回（后台线程）并发时
            # 不加锁会丢失更新（最坏把刚登录新增的账号整条写丢）
            with CONFIG_LOCK:
                config = load_config()
                account = get_current_account(config)
                if not account:
                    return
                settings = get_rollcall_settings(account)
                settings["poll_interval_seconds"] = self.poll_interval_spin.value()
                set_rollcall_settings(account, settings)
                save_config(config)
            if self.account and str(self.account.get("id")) == str(account.get("id")):
                self.account["rollcall_settings"] = get_rollcall_settings(account)
            if hasattr(self, "poll_interval_status"):
                self.poll_interval_status.setText("更改成功")
            self._show_toast(f"轮询间隔已改为 {self.poll_interval_spin.value()} 秒")
        except Exception as exc:
            if hasattr(self, "poll_interval_status"):
                self.poll_interval_status.setText("更改失败")
            self.log(f"保存轮询间隔失败：{exc}")
            self._show_toast("轮询间隔保存失败", ok=False)

    def _load_app_settings(self):
        if not hasattr(self, "launch_on_startup_check"):
            return
        try:
            config = load_config()
            settings = get_app_settings(config)
            actual_enabled = self._launch_on_startup_enabled()
            if settings["launch_on_startup"] != actual_enabled:
                # 读-改-写整体持锁：与登录写回并发时不丢更新
                with CONFIG_LOCK:
                    config = load_config()
                    settings = get_app_settings(config)
                    settings["launch_on_startup"] = actual_enabled
                    set_app_settings(config, settings)
                    save_config(config)
            self.launch_on_startup_check.blockSignals(True)
            self.launch_on_startup_check.setChecked(actual_enabled)
            self.launch_on_startup_check.blockSignals(False)
            # 主题模式：加载并应用到当前 QApplication（main() 已应用一次，
            # 这里再做一次幂等应用，保证设置页 UI 与实际样式一致）
            theme_mode = settings.get("theme_mode", "system")
            index_map = {"system": 0, "light": 1, "dark": 2}
            self.theme_mode_combo.blockSignals(True)
            self.theme_mode_combo.setCurrentIndex(index_map.get(theme_mode, 0))
            self.theme_mode_combo.blockSignals(False)
            # 体检报告 P1-1：__init__ 把 _current_theme_mode 初始化为 "system" 且
            # 从未按持久化设置纠正——强制深色+系统浅色时 main() 的深色样式表之下，
            # _ui_palette()/教程 HTML 等动态取色仍按浅色解析，且 colorSchemeChanged
            # 回调会拿旧值覆盖用户选择。此处幂等补应用，同步内存态与持久化设置。
            self._apply_theme(theme_mode)
        except Exception as exc:
            self.log(f"读取应用设置失败：{exc}")

    def _save_theme_mode(self, *_args):
        """保存主题模式并立即重新应用样式表。"""
        if not hasattr(self, "theme_mode_combo"):
            return
        index = self.theme_mode_combo.currentIndex()
        mode = {0: "system", 1: "light", 2: "dark"}.get(index, "system")
        try:
            with CONFIG_LOCK:  # 读-改-写整体持锁，防与登录写回并发丢失更新
                config = load_config()
                settings = get_app_settings(config)
                settings["theme_mode"] = mode
                set_app_settings(config, settings)
                save_config(config)
            self._apply_theme(mode)
            self.theme_mode_status.setObjectName("StatusGood")
            self.theme_mode_status.setText("已切换")
            self.theme_mode_status.style().unpolish(self.theme_mode_status)
            self.theme_mode_status.style().polish(self.theme_mode_status)
            labels = {"system": "跟随系统", "light": "浅色", "dark": "深色"}
            self._show_toast(f"主题已切换为 {labels.get(mode, mode)}")
        except Exception as exc:
            self.theme_mode_status.setObjectName("StatusWarn")
            self.theme_mode_status.setText("切换失败")
            self.theme_mode_status.style().unpolish(self.theme_mode_status)
            self.theme_mode_status.style().polish(self.theme_mode_status)
            self.log(f"保存主题模式失败：{exc}")
            self._show_toast("主题切换失败", ok=False)

    def _apply_theme(self, mode: str):
        """重新应用整个 QApplication 的样式表与原生调色板，并刷新依赖主题的部件。"""
        self._theme_mode_setting = mode if mode in ("system", "light", "dark") else "system"
        self._current_theme_mode = resolve_theme_mode(mode)
        app = QApplication.instance()
        if app is None:
            return
        app.setStyleSheet(stylesheet(mode))
        # 原生对话框/tooltip 不吃 QSS：深色时同步设置 QPalette，
        # 浅色时恢复默认，避免弹窗亮白突兀
        if self._current_theme_mode == "dark":
            app.setPalette(dark_qpalette())
        else:
            app.setPalette(QPalette())
        self._refresh_tutorial_html()
        # item 级前景色/富文本 span 色在数据填充时按当时主题烘焙，QSS/palette
        # 变更不会重绘既有 item——切主题后重刷三张数据表与 PushPlus 提示，
        # 防旧主题色残留到下一次数据刷新（hasattr 防御与 _refresh_tray_menu 同款）。
        if hasattr(self, "events_table"):
            self._refresh_event_tables()
        if hasattr(self, "course_table"):
            self._refresh_course_table()
        if hasattr(self, "courseware_table"):
            self._refresh_courseware_table()
        self._refresh_push_tip()

    def _ui_palette(self) -> dict[str, str]:
        """当前主题的扩展调色板（状态徽标/分组行/教程等动态取色入口）。"""
        return theme_palette(self._current_theme_mode)

    def _save_app_settings(self, *_args):
        if not hasattr(self, "launch_on_startup_check"):
            return
        enabled = self.launch_on_startup_check.isChecked()
        try:
            with CONFIG_LOCK:  # 读-改-写整体持锁，防与登录写回并发丢失更新
                config = load_config()
                settings = get_app_settings(config)
                settings["launch_on_startup"] = enabled
                self._set_launch_on_startup(enabled)
                set_app_settings(config, settings)
                save_config(config)
            self.launch_on_startup_status.setObjectName("StatusGood")
            self.launch_on_startup_status.setText("更改成功")
            self.launch_on_startup_status.style().unpolish(self.launch_on_startup_status)
            self.launch_on_startup_status.style().polish(self.launch_on_startup_status)
            self._show_toast("开机自启设置已保存")
        except Exception as exc:
            self.launch_on_startup_check.blockSignals(True)
            self.launch_on_startup_check.setChecked(not enabled)
            self.launch_on_startup_check.blockSignals(False)
            self.launch_on_startup_status.setObjectName("StatusWarn")
            self.launch_on_startup_status.setText("更改失败")
            self.launch_on_startup_status.style().unpolish(self.launch_on_startup_status)
            self.launch_on_startup_status.style().polish(self.launch_on_startup_status)
            self.log(f"保存应用设置失败：{exc}")
            self._show_toast("开机自启设置失败", ok=False)

    def _current_poll_interval(self) -> int:
        return self.poll_interval_spin.value()

    def _export_logs(self):
        """把内存中的最近日志导出为文本文件（utf-8-sig，便于问题反馈）。"""
        default_name = f"xmu助手日志_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
        path, _selected = QFileDialog.getSaveFileName(self, "导出日志", default_name, "文本文件 (*.txt)")
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8-sig", newline="") as file:
                file.write(format_log_export(self.log_messages))
        except OSError as exc:
            QMessageBox.warning(self, "导出失败", f"写入文件失败：{exc}")
            return
        self.log(f"已导出 {len(self.log_messages)} 条运行日志")
        QMessageBox.information(self, "导出成功", f"已导出 {len(self.log_messages)} 条日志：\n{path}")

    def login(self):
        username = self.username_input.text().strip()
        password = self.password_input.text().strip()
        if not username or not password:
            QMessageBox.warning(self, "缺少信息", "请输入学号和密码。")
            return
        # 登录在途门（与 Android accountTransitionInProgress 同语义）：并发登录时
        # 后启动 worker 先完成写盘、先启动 worker 的成功事件被 _login_epoch 丢弃，
        # UI 显示后启动账号、磁盘当前账号却是先写盘账号——重启自动恢复即漂移。
        if self._login_in_progress:
            self.log("登录已在进行中，忽略本次触发。")
            return
        self._login_in_progress = True
        try:
            self._set_login_status("正在登录...", warn=True)
            self.log("开始登录。")
            self._run_thread(self._login_worker, username, password, self._login_epoch)
        except Exception:
            self._login_in_progress = False
            raise

    def _login_worker(self, username: str, password: str, login_epoch: int = -1):
        try:
            session = xmulogin(type=3, username=username, password=password)
            if not session:
                self._emit(("login_failed", "登录失败，请检查学号或密码。"))
                return

            try:
                profile = session.get(f"{base_url}/api/profile", headers=headers, timeout=API_TIMEOUT).json()
                name = profile.get("name") or username
            except Exception:
                name = username

            # 读-改-写整体持锁：与通知设置保存（GUI 线程）并发时互相覆盖丢失修改
            with CONFIG_LOCK:
                config = load_config()
                account = next((item for item in get_all_accounts(config) if item.get("username") == username), None)
                if account is None:
                    account_id = add_account(config, username, password, name)
                    account = next(item for item in get_all_accounts(config) if item.get("id") == account_id)
                else:
                    account["password"] = password
                    account["name"] = name

                set_current_account(config, account["id"])
                set_rollcall_settings(account, get_rollcall_settings(account))
                save_config(config)
            save_session(session, get_cookies_path(account["id"]))
            self._emit(("login_success", session, account, login_epoch))
        except Exception as exc:
            self._emit(("login_failed", str(exc)))

    def auto_restore_current_session(self):
        if self.session:
            return
        try:
            account = get_current_account(load_config())
            if not account:
                return
            cookies_path = get_cookies_path(account.get("id", 1))
            if not os.path.exists(cookies_path):
                return
            # 登录在途门（与 login() 同语义）：恢复也占用同一把门。否则恢复校验
            # 在途时用户手动登录，两 worker 同 epoch 并发——恢复先落地后，手动
            # 登录的成功事件被 _login_epoch 丢弃，但其 cookie/current_account_id
            # 已写盘（磁盘=新账号、UI=恢复账号），重启自动恢复即漂移到另一账号。
            if self._login_in_progress:
                self.log("登录已在进行中，跳过会话自动恢复。")
                return
            self._login_in_progress = True
            try:
                self._set_login_status("正在自动恢复会话...", warn=True)
                self.log("检测到上次登录会话，正在自动恢复。")
                self._run_thread(self._restore_worker, True, self._login_epoch)
            except Exception:
                self._login_in_progress = False
                raise
        except Exception as exc:
            self.log(f"自动恢复检查失败：{exc}")

    def _restore_worker(self, silent=False, login_epoch: int = -1):
        try:
            account = get_current_account(load_config())
            if not account:
                self._emit(("restore_failed", "没有可恢复的当前账号，请先登录。", silent))
                return
            session = requests.Session()
            if not load_session(session, get_cookies_path(account.get("id", 1))):
                self._emit(("restore_failed", "Cookie 读取失败，请重新登录。", silent))
                return
            profile = verify_session(session)
            if profile is None:
                # 网络故障无法判定（如开机自启时网络尚未就绪）：
                # 不能误报「登录态已失效」——本地 Cookie 保留，网络恢复后仍可自动登录
                self._emit(("restore_failed", "网络连接失败，无法校验登录态，请检查网络后重试。", silent))
                return
            if not profile:
                self._emit(("restore_failed", "登录态已失效，请重新登录。", silent))
                return
            self._emit(("login_success", session, account, login_epoch))
        except Exception as exc:
            self._emit(("restore_failed", str(exc), silent))

    def logout(self):
        if not self.account and not self.session:
            self._set_login_status("未登录", warn=True)
            self.metric_account.setText("未登录")
            return
        if QMessageBox.question(self, "退出登录", "确定要退出登录并清除当前账号的本地 Cookie 吗？") != QMessageBox.StandardButton.Yes:
            return

        # 取消所有在途的自动延迟应答：登出后不应再有任何提交
        # （锁内取快照：与 worker 线程的登记/摘除并发，避免迭代时字典被改）
        with self._answer_cancellations_lock:
            pending_ids = list(self._answer_cancellations.keys())
        for event_id in pending_ids:
            self._cancel_pending_answer(event_id)
        self.stop_monitor()
        # 登录代数 +1：在途 login/restore worker 的晚到成功结果据此被丢弃
        self._login_epoch += 1
        account = self.account
        self.session = None
        self.account = None
        self.password_input.clear()
        self._set_login_status("未登录", warn=True)
        self.metric_account.setText("未登录")
        self.metric_monitor.setText("未启动")
        self.metric_last_result.setText("-")
        # 指标行随登出一并复位：当前签到/最近检查/运行时长不得残留旧账号数值
        # （与事件表/课程表清理面同批，防未登录态面板半新半旧）
        self.metric_rollcall_count.setText("0")
        self.metric_last_check.setText("-")
        self.metric_runtime.setText("0s")
        self.started_at = None
        # 后台错误计数随会话一并复位：登出前攒的计数不得让下个会话第 1 次
        # 错误就凑满 3 次阈值
        self._reset_background_error_state()
        # 章节缓存按 course_id 键控无账号维度：登出即失效，防换号后 TTL 窗口
        # 内新账号命中同课程读到旧账号视角
        reset_modules_cache()
        self.course_refresh_in_progress = False
        # L2 手动「核实所选」互斥门：与整表刷新独立，进行中时禁用按钮
        self.course_verify_in_progress = False
        self.courseware_courses_refresh_in_progress = False
        self.courseware_refresh_in_progress = False
        self.courseware_download_in_progress = False
        self.courseware_courses = []
        self.courseware_items = []
        self.courseware_download_status = {}
        self.courseware_course_by_display = {}
        self.courseware_combo.clear()
        # 清空上一账号的签到数据：登出/换号后「签到情况」与首页事件表
        # 不得残留旧账号的签到记录（防串号展示）
        self.events_by_id = {}
        self.event_order = []
        self.course_records = []
        self._refresh_event_tables()
        self._refresh_course_table()
        self._refresh_courseware_table()
        self.courseware_summary.setText("请选择课程")
        self._update_nav_badges()
        # 登出后重启不得展示旧账号快照：_restore_ui_snapshot 启动无登录门，
        # 不删会让未登录态首屏残留旧账号签到/课件（清理面承诺漂移）
        try:
            _ui_snapshot_path().unlink(missing_ok=True)
        except Exception:
            pass
        self._show_toast("已退出登录")

        if account:
            try:
                cookies_path = get_cookies_path(account.get("id", 1))
                if os.path.exists(cookies_path):
                    os.remove(cookies_path)
                self.log("已退出登录并清除本地 Cookie。")
            except Exception as exc:
                self.log(f"清除 Cookie 失败：{exc}")

    def answer_selected_rollcall(self):
        event_id = self._selected_event_id()
        if not event_id:
            QMessageBox.information(self, "未选择", "请先选择一条签到事件。")
            return
        event = self.events_by_id.get(event_id)
        if event is None:
            # 选中行停留期间被新事件挤出（MAX_EVENT_ROWS 淘汰）后再点击：
            # 裸索引会 KeyError，按未选择引导重新选取
            QMessageBox.information(self, "事件已过期", "该签到事件已被移出列表，请重新选择。")
            return
        self._answer_event(event_id, event)

    def skip_selected_rollcall(self):
        event_id = self._selected_event_id()
        if event_id:
            self._cancel_pending_answer(event_id)
            self._update_event_result(event_id, "已跳过", "用户手动跳过")

    def _selected_event_id(self) -> str:
        row = self.events_table.currentRow()
        if row < 0 or not self.events_table.item(row, 0):
            return ""
        return self.events_table.item(row, 0).data(Qt.ItemDataRole.UserRole) or ""

    def _answer_event(self, event_id: str, event: RollcallEvent, auto: bool = False):
        if not self.session:
            if auto:
                # 自动路径不弹模态打扰：登出/换号后在途事件晚到导致会话为空时，
                # 静默跳过并留日志（与二维码 auto 不弹框的动改一致）。
                self.log(f"自动应答已跳过：当前未登录（{event.rollcall_type}）。")
                return
            QMessageBox.warning(self, "尚未登录", "请先登录。")
            return
        if auto and self.monitor_stop_event is not None and self.monitor_stop_event.is_set():
            # 监控已暂停/停止后才送到的在途事件：用户已停监控，不应再自动提交签到。
            # （stop_monitor 只能取消已注册的延迟应答，晚到的信号在这里兜底拦截。）
            self.log(f"自动应答已跳过：监控已停止（{event.rollcall_type}）。")
            return
        if event.rollcall_type == "二维码签到":
            self._update_event_result(event_id, "需手动", "二维码签到需要手动扫码")
            if auto:
                # 自动路径不得用模态框打断用户（监控挂在后台时会阻塞交互）
                self._show_toast("检测到二维码签到：暂不支持自动处理，请手动扫码。")
            else:
                QMessageBox.information(self, "需要手动处理", "二维码签到暂不支持自动处理，请手动扫码。")
            return
        # 取消该事件在途的延迟应答（手动重答 / 监控对同一事件再次触发的自动路径）：
        # 不取消的话旧延迟 worker 到点照常提交，新旧双双提交造成重复应答请求。
        self._cancel_pending_answer(event_id)
        self._update_event_result(event_id, "处理中", "正在提交")
        delay = self._auto_answer_delay(event, auto)
        self._run_thread(self._answer_worker, event_id, event, delay)

    def _auto_answer_delay(self, event: RollcallEvent, auto: bool) -> float:
        """自动应答的拟人化延迟（秒）。0 = 立即提交。

        - 仅自动应答生效（手动按钮点击 auto=False 不延迟）；
        - 截止保护：剩余时间 ≤ 延迟 + 10s 时跳过延迟立即提交，避免延迟期间签到截止；
        - manual_confirm 模式本期不接入（按报告 A3 延后），自动应答照常立即/延迟提交。
        """
        if not auto:
            return 0.0
        settings = get_rollcall_settings(self.account or {})
        delay = compute_auto_answer_delay(event.rollcall_type, settings, event.remaining_seconds)
        if delay <= 0:
            return 0.0
        if event.remaining_seconds is not None and event.remaining_seconds <= delay + 10:
            self.log(f"签到 {event.rollcall_type} 剩余 {event.remaining_seconds}s 不足，跳过延迟立即提交。")
            return 0.0
        self.log(f"自动应答将于 {delay:.1f}s 后提交（{event.rollcall_type}）。")
        return delay

    def _cancel_pending_answer(self, event_id: str):
        """取消某事件在途的自动延迟应答（手动应答/跳过/停止/登出时调用）。"""
        # 「取出+set」持锁：与 worker 的登记/摘除并发时保持原子
        with self._answer_cancellations_lock:
            cancel = self._answer_cancellations.pop(event_id, None)
        if cancel is not None:
            cancel.set()

    def _answer_worker(self, event_id: str, event: RollcallEvent, delay: float = 0.0):
        # A2 二期（H3）：应答是唯一写 cookie 的并发路径（PUT Set-Cookie）。克隆会话隔离
        # 写入口，避免与其余只读 worker / GUI 读共享 cookiejar 发生跨线程竞争写；
        # session 为 None（登出在途）时明确返回，不再偶发 AttributeError。
        session = clone_session(self.session) if self.session is not None else None
        if session is None:
            self._emit(("answer_result", event_id, False, "已退出登录"))
            return
        # 账号归属快照：merge 回写时只允许写回同一账号的主会话（防登出/换号污染）
        worker_account_id = str((self.account or {}).get("id") or "")
        ok = False
        detail = "提交失败"
        try:
            if delay > 0:
                # 可取消等待：用户手动应答/跳过/停止监控/登出可立即打断
                cancel = threading.Event()
                with self._answer_cancellations_lock:
                    self._answer_cancellations[event_id] = cancel
                try:
                    if cancel.wait(delay):
                        self._emit(("answer_result", event_id, False, "已取消"))
                        return
                finally:
                    # 仅当槽内仍是「自己的」取消信号时才移除：同一事件的新 worker 随后
                    # 可能已覆盖槽位，无条件按 event_id pop 会误删新信号 → 该签到此后
                    # 无法再被取消/跳过，到点照常提交 → 重复签到（旧 worker 慢退场竞态）。
                    # 比较与摘除在同一临界区内（TOCTOU 防护）。
                    with self._answer_cancellations_lock:
                        if self._answer_cancellations.get(event_id) is cancel:
                            self._answer_cancellations.pop(event_id, None)
            # 二次校验（提交前）：delay 唤醒后/立即提交前，登出或换号在途则放弃。
            # 网络提交不可中断，只能在此拦截（worker_account_id 为入口时的账号快照，
            # 换号后 id 不同、登出后 session 为 None 均命中）。
            if self.session is None or str((self.account or {}).get("id") or "") != worker_account_id:
                self._emit(("answer_result", event_id, False, "已取消（登录状态已变更）"))
                return
            ok = RollcallEngine(session).answer(event.rollcall_type, event.rollcall_id)
            detail = "提交成功" if ok else "提交失败"
        except Exception as exc:
            ok = False
            detail, session_expired = answer_failure_detail(exc)
            if session_expired:
                # 会话过期是终态：与监控过期分支一致，走后台错误通知引导重新登录
                # （线程安全：仅 emit，不在 worker 线程内碰 Qt 控件）
                self._emit(("error", "应答失败：登录已过期，请重新登录"))
        finally:
            # 把 worker 克隆内新增/旋转的 cookie 合并回主会话（GUI 线程收到后单点写）
            self._emit(("merge_session_cookies", session, worker_account_id))
        self._emit(("answer_result", event_id, ok, detail))

    def _merge_worker_session(self, worker_session, worker_account_id: str):
        """GUI 线程单点写：把应答 worker 克隆会话的 cookie 回写主会话（含登出/换号守卫）。

        守卫逻辑封装在 utils.merge_worker_session_cookies（纯函数，便于单测）。
        """
        merge_worker_session_cookies(self.session, self.account, worker_session, worker_account_id)

    def _refresh_after_login(self):
        self.log("登录完成，开始后台刷新签到情况和课件课程。")
        self.refresh_course_rollcalls(silent=True)
        self.refresh_courseware_courses(silent=True)

    # ---- 事件处理器：原 _handle_event 的 if 链按 kind 拆分 --------------------
    # 函数体逐字保留，仅把控制流从 elif 链改为查表派发。分组顺序即下方
    # _EVENT_HANDLERS 的键序：登录会话 / 首页监控与轮询 / 会话合并 /
    # 签到情况页 / 课程课件页 / 全局错误 / 通知页。

    def _ev_login_success(self, event):
        # 切片取前三元而非整包解包：发射端为 (kind, session, account, login_epoch)
        # 四元组，整包解包会对多余字段抛 ValueError（GUI 槽内无声失败，
        # session/account 永不落地——2026-08-24 体检 P0-1）。切片写法容忍
        # 发射端后续追加字段，worker_epoch 仍按下标单独读取。
        self._login_in_progress = False
        _, session, account = event[:3]
        worker_epoch = event[3] if len(event) > 3 else None
        if worker_epoch is not None and worker_epoch != self._login_epoch:
            # 代数不一致：worker 在途期间发生了登出/换号，晚到的成功结果不得落地，
            # 否则会把刚清空的 session/account "复活"为已登录（状态错乱）。
            self.log("忽略迟到的登录结果（登录状态在途期间已变更）。")
            return
        self._login_epoch += 1
        # 换号/重登：旧监控线程绑定的是旧账号的 clone 会话，若不停止会继续按旧
        # 账号轮询并触发自动应答（GUI 已切到新账号，用新会话提交旧签到 = 跨账号
        # 污染）。先停旧监控（stop_monitor 幂等，安全）。
        if self.monitor_worker is not None and self.monitor_worker.is_alive():
            self.stop_monitor()
        self.session = tune_session(session)
        self.account = account
        # 跨账号防护：本地快照属于其他账号时立即丢弃，避免串号展示
        if self._snapshot_account_id and str(account.get("id")) != self._snapshot_account_id:
            # 清理面与登出路径对齐：换号后首页事件表/课件详情表/下载状态
            # 不得残留旧账号数据（登出清理面同款，防串号展示）
            self.course_records = []
            self.courseware_courses = []
            self.courseware_items = []
            self.courseware_download_status = {}
            self.courseware_course_by_display = {}
            # 与 courseware_page 填充惯例一致：clear 触发的 currentTextChanged
            # 联动在数据已清空时仅靠兜底 no-op，显式阻断重排即翻车的隐患
            self.courseware_combo.blockSignals(True)
            self.courseware_combo.clear()
            self.courseware_combo.blockSignals(False)
            self.events_by_id = {}
            self.event_order = []
            self._snapshot_account_id = ""
            # 互斥旗标随数据面一并复位（与 logout 清理面同款）：旧账号刷新/下载
            # 在途时直接登录新账号，残留 True 会让新账号的自动刷新被旗标检查
            # 静默跳过（页面空白）、新下载被拦截直至旧批次事件到达
            self.course_refresh_in_progress = False
            self.course_verify_in_progress = False
            self.courseware_courses_refresh_in_progress = False
            self.courseware_refresh_in_progress = False
            self.courseware_download_in_progress = False
            # 指标行随换号一并复位（与 logout 复位面对齐）：最近结果/当前签到/
            # 最近检查/运行时长不得残留旧账号数值（旧监控已在上方停止）
            self.metric_last_result.setText("-")
            self.metric_rollcall_count.setText("0")
            self.metric_last_check.setText("-")
            self.metric_runtime.setText("0s")
            self.started_at = None
            self._refresh_event_tables()
            self._refresh_course_table()
            self._refresh_courseware_table()
            self.courseware_summary.setText("请选择课程")
            try:
                _ui_snapshot_path().unlink(missing_ok=True)
            except Exception:
                pass  # 删不掉（占用/权限）不阻断登录：快照已按账号判定失效，不再被展示
        else:
            self._snapshot_account_id = str(account.get("id"))
        # 新会话新计数：换号/重登后的错误统计不应继承旧会话（同 logout 复位口径）
        self._reset_background_error_state()
        display = account.get("name") or account.get("username") or "已登录"
        self._set_login_status("已登录", warn=False)
        self.metric_account.setText(display)
        self._load_rollcall_settings(account)
        self.log(f"登录成功：{display}")
        self._show_toast(f"{display} 已登录")
        self._refresh_after_login()

    def _ev_login_failed(self, event):
        self._login_in_progress = False
        if self.session is not None:
            # 已登录状态下换号直登失败：迟到的失败结果不得把界面翻回「未登录」，
            # 否则侧栏「未登录」/守护徽章「正在守护」/托盘「正在监控」三态矛盾
            # （旧账号 session 与监控线程仍在跑）——镜像 _ev_restore_failed 的
            # session 守卫先例，保持原登录态仅提示失败。
            self.log(f"登录失败（保持当前登录状态）：{event[1]}")
            self._show_toast("登录失败，请检查账号或网络", ok=False)
            QMessageBox.critical(self, "登录失败", friendly_error_message(event[1], "login"))
            return
        self._set_login_status("未登录", warn=True)
        self.metric_account.setText("未登录")
        self.log(f"登录失败：{event[1]}")
        self._show_toast("登录失败，请检查账号或网络", ok=False)
        QMessageBox.critical(self, "登录失败", friendly_error_message(event[1], "login"))

    def _ev_restore_failed(self, event):
        # 恢复占用登录在途门后，失败必须放行同一把门（_restore_failed 仅由
        # _restore_worker 发射，与 auto_restore_current_session 的占用严格配对）；
        # 否则恢复失败后手动登录会被门挡住（「登录已在进行中」）。
        self._login_in_progress = False
        message = event[1]
        silent = bool(event[2]) if len(event) > 2 else False
        if self.session is not None:
            # 迟到的恢复失败不得覆盖刚完成的手动登录：自动恢复校验可能耗时数十秒，
            # 期间用户手动登录成功后，晚到的 restore_failed 若无条件执行会把界面置回
            # 「未登录」，与真实 session 状态不一致（状态错乱）。
            self.log(f"忽略迟到的会话恢复失败（当前已登录）：{message}")
        else:
            self._set_login_status("未登录", warn=True)
            self.metric_account.setText("未登录")
            self.log(f"会话恢复失败：{message}")
            if not silent:
                QMessageBox.critical(self, "恢复失败", friendly_error_message(message, "login"))

    def _ev_monitor_status(self, event):
        if self.session is None:
            # 登出后在途监控线程的晚到状态：登出清理面已复位指标（如「未启动」），
            # 晚到的「已停止」不得再覆盖，直接丢弃留痕。
            self.log(f"忽略登出后迟到的监控状态：{event[1]}")
            return
        text = event[1]
        if (
            text == "已停止"
            and self.account
            and self.monitor_stop_event
            and self.monitor_stop_event.is_set()
        ):
            # 用户主动暂停（stop_monitor 已设停止信号并显示「已暂停」）后，
            # worker 收尾的「已停止」会把指标行覆盖回停止口径，与守护徽章/托盘
            # （按 is_running 计算，暂停即非运行）长期不一致——映射回暂停口径。
            # 异常终态（stop_event 未设，如会话过期停机）保持「已停止」如实展示。
            text = "已暂停"
        self.metric_monitor.setText(text)
        self.log(f"监控状态：{text}")

    def _ev_poll(self, event):
        if self.session is None:
            # 登出清理面已复位指标行：晚到的轮询结果会把最近检查/当前签到数
            # 写回旧账号数值，直接丢弃留痕。
            self.log("忽略登出后迟到的轮询结果。")
            return
        checked_at = event[2]
        rollcall_count = event[3]
        self._reset_background_error_state()
        self.metric_last_check.setText(time.strftime("%H:%M:%S", time.localtime(checked_at)))
        self.metric_rollcall_count.setText(str(rollcall_count))
        self._refresh_tray_menu()

    def _ev_rollcall(self, event):
        if self.session is None:
            # 登出清理面已清空今日签到表：晚到的旧账号签到事件不得灌回，
            # 也不得经此触发通知，直接丢弃留痕。
            self.log("忽略登出后迟到的签到事件。")
            return
        self._add_rollcall_event(event[1])

    def _ev_answer_result(self, event):
        if self.session is None:
            # 登出后在途应答 worker 的晚到结果：事件表已清空（_update 无害），
            # 但 metric_last_result 会被无条件改写、通知不该再发——丢弃留痕
            #（与 _ev_poll/_ev_rollcall 同族守卫）。
            self.log(f"忽略登出后迟到的应答结果：{event[3]}")
            return
        event_id = event[1]
        ok = event[2]
        detail = event[3]
        self._update_event_result(event_id, "已签到" if ok else "失败", detail)
        self.metric_last_result.setText(detail)
        self.log(f"签到处理结果：{detail}")
        rollcall = self.events_by_id.get(event_id)
        if rollcall:
            self._notify_rollcall(event_id, rollcall)

    def _ev_number_code(self, event):
        event_id = event[1]
        code = event[2]
        detail = event[3] if len(event) > 3 else ""
        self._update_event_code(event_id, code, detail)

    def _ev_merge_session_cookies(self, event):
        # GUI 线程单点写：worker 克隆会话的 cookie 回写主会话（含登出/换号守卫）
        worker_session = event[1]
        worker_account_id = event[2] if len(event) > 2 else ""
        self._merge_worker_session(worker_session, worker_account_id)

    def _ev_course_rollcalls(self, event):
        _, records, source = event[:3]
        # P1-2：登出/换号后晚到的结果只清状态旗标，不回填旧账号数据
        worker_account_id = event[3] if len(event) > 3 else ""
        self.course_refresh_in_progress = False
        if late_worker_result_accepted(self.account, worker_account_id):
            self.course_records = records
            self._refresh_course_table()
            self._update_nav_badges()
            self._save_ui_snapshot()
            self.log(f"签到情况刷新完成。{source}")
            # L1 阶段二随后补发 course_records_verified：先在摘要行预告，
            # 提醒当前状态还是聚合值（完成后追加「核实完成」）
            if records:
                self.course_summary.setText(self.course_summary.text() + "；正在核实最近签到明细…")
        else:
            self.log("忽略晚到的签到情况刷新结果（账号已切换或已登出）。")

    def _ev_course_rollcalls_error(self, event):
        worker_account_id = event[3] if len(event) > 3 else ""
        self.course_refresh_in_progress = False
        if late_worker_result_accepted(self.account, worker_account_id):
            self.course_summary.setText("刷新失败")
            self.log(f"签到情况刷新失败：{event[1]}")
            if not event[2]:
                self._show_retry_error(
                    "刷新失败",
                    friendly_error_message(event[1], "courseware"),
                    self.refresh_course_rollcalls,
                )
        else:
            self.log("忽略晚到的签到情况刷新失败（账号已切换或已登出）。")

    def _ev_course_records_verified(self, event):
        records = event[1] if len(event) > 1 else []
        worker_account_id = event[2] if len(event) > 2 else ""
        origin = event[3] if len(event) > 3 else "auto"
        if origin == "manual":
            # 旗标无条件清、数据仅在守卫通过时落地（P1-2 同款姿势）
            self.course_verify_in_progress = False
            self._update_verify_button_state()
        if not late_worker_result_accepted(self.account, worker_account_id):
            self.log("忽略晚到的签到核实结果（账号已切换或已登出）。")
            return
        applied = self._apply_verified_records(records)
        if origin == "manual":
            for record in records or []:
                verdict_text = (
                    f"{self._course_status_text(record.signed_status)}（已按本人明细核实）"
                    if record.verified
                    else "无法从本人明细判定，保持聚合状态"
                )
                self.log(f"核实完成：《{record.course_title}》{record.rollcall_time} → {verdict_text}")
            self._write_course_summary()
        elif self.course_records:
            # auto：定向更新后重算计数摘要并追加完成标记（筛选开启时
            # _apply_verified_records 已整表重建，此处统一走摘要重写）
            self._write_course_summary("；核实完成")
            self.log(f"最近签到明细核实完成：更新 {applied} 条。")

    def _ev_course_records_verify_error(self, event):
        worker_account_id = event[2] if len(event) > 2 else ""
        self.course_verify_in_progress = False
        self._update_verify_button_state()
        if not late_worker_result_accepted(self.account, worker_account_id):
            self.log("忽略晚到的签到核实失败（账号已切换或已登出）。")
            return
        self.course_summary.setText("核实所选失败")
        self.log(f"核实所选签到失败：{event[1]}")
        self._show_retry_error(
            "核实失败",
            friendly_error_message(event[1], "courseware"),
            self._verify_selected_rollcall,
        )

    def _ev_courseware_courses(self, event):
        # P1-2：与 course_rollcalls 同款账号守卫
        worker_account_id = event[3] if len(event) > 3 else ""
        self.courseware_courses_refresh_in_progress = False
        if late_worker_result_accepted(self.account, worker_account_id):
            self._set_courseware_courses(event[1], event[2])
            self._update_nav_badges()
            self._save_ui_snapshot()
        else:
            self.log("忽略晚到的课件课程列表结果（账号已切换或已登出）。")

    def _ev_courseware_courses_error(self, event):
        worker_account_id = event[3] if len(event) > 3 else ""
        self.courseware_courses_refresh_in_progress = False
        if not late_worker_result_accepted(self.account, worker_account_id):
            self.log("忽略晚到的课件课程列表读取失败（账号已切换或已登出）。")
            return
        self.courseware_summary.setText("课程列表读取失败")
        self._refresh_courseware_empty_state()
        self._show_toast("课程列表读取失败", ok=False)
        self.log(f"课件课程列表读取失败：{event[1]}")
        if not event[2]:
            self._show_retry_error(
                "课程列表读取失败",
                friendly_error_message(event[1], "courseware"),
                self.refresh_courseware_courses,
            )

    def _ev_courseware(self, event):
        # P1-2：与 courseware_courses 同款账号守卫——课件详情是 8 线程池
        # 逐活动抓取，登出/换号后晚到的结果不得回填界面
        worker_account_id = event[3] if len(event) > 3 else ""
        self.courseware_refresh_in_progress = False
        if not late_worker_result_accepted(self.account, worker_account_id):
            self.log("忽略晚到的课件列表结果（账号已切换或已登出）。")
            return
        self._set_courseware_items(event[1], event[2])
        self._update_nav_badges()

    def _ev_courseware_error(self, event):
        worker_account_id = event[3] if len(event) > 3 else ""
        self.courseware_refresh_in_progress = False
        if not late_worker_result_accepted(self.account, worker_account_id):
            self.log("忽略晚到的课件读取失败（账号已切换或已登出）。")
            return
        self.courseware_summary.setText("课件读取失败")
        self._refresh_courseware_empty_state()
        self._show_toast("课件读取失败", ok=False)
        self.log(f"课程课件读取失败：{event[1]}")
        if not event[2]:
            self._show_retry_error(
                "课件读取失败",
                friendly_error_message(event[1], "courseware"),
                self.refresh_selected_courseware,
            )

    def _ev_courseware_download_progress(self, event):
        index = event[1]
        total = event[2]
        filename = event[3]
        key = event[4]
        self.courseware_download_status[key] = "下载中"
        self._refresh_courseware_table()
        self._update_nav_badges()
        self.courseware_summary.setText(f"正在下载 {index}/{total}：{filename}")

    def _ev_courseware_download_item_done(self, event):
        if self.session is None:
            # 登出后晚到的逐项结果：状态表已清空，不得写入孤儿键
            return
        key = event[1]
        status = event[2]
        self.courseware_download_status[key] = status
        self._refresh_courseware_table()
        self._update_nav_badges()

    def _ev_courseware_download_done(self, event):
        # 互斥旗标复位无条件（新批次可能已合法占用登出后的空闲态）；
        # 展示面（摘要/弹窗/通知）在登出后丢弃——晚到的旧批次结果不得改写
        # 已清空的摘要、弹登出后模态框。换号场景的旗标解锁绕过需事件携带
        # worker 账号快照（签名变更，在案待拍板），本守卫先堵登出场景。
        self.courseware_download_in_progress = False
        if self.session is None:
            self.log("忽略登出后迟到的课件下载完成事件。")
            return
        _, downloaded, entries, errors, destination = event[:5]
        raw_errors = event[5] if len(event) > 5 else errors
        self.courseware_summary.setText(
            f"下载完成 {len(downloaded)} 个；保存入口 {len(entries)} 个；失败 {len(errors)} 个"
        )
        self.log(f"课件下载完成：文件 {len(downloaded)} 个，入口 {len(entries)} 个，目录：{destination}")
        if errors:
            self.log("课件下载失败：" + "；".join(raw_errors))
        self._update_nav_badges()
        self._show_toast(
            f"下载完成：文件 {len(downloaded)} 个，入口 {len(entries)} 个，失败 {len(errors)} 个",
            ok=not bool(errors),
        )
        self._show_courseware_download_result(downloaded, entries, errors, destination)

    def _ev_error(self, event):
        if self.session is None:
            # 登出后在途 worker 的晚到错误：不得再走紧急通知/第三方推送
            #（与 poll/rollcall/monitor_status/answer_result 同族守卫），丢弃留痕。
            self.log(f"忽略登出后迟到的错误事件：{event[1]}")
            return
        # 会话过期是终态错误（监控已停止、不会自愈重试，只发这一次）：
        # 若仍走「连续 3 次」阈值将永远凑不满，托盘常驻用户对监控停摆零感知。
        # 「登录已过期」文案族由 SessionExpiredError 全部来源共用（engine/
        # request_probe/number_code 分流），子串判定与 answer_failure_detail 同法。
        self._record_background_error(event[1], immediate=("登录已过期" in event[1]))

    def _ev_notification_result(self, event):
        ok = event[1]
        detail = event[2]
        self.notification_summary.setText(detail)
        self.log(("通知发送成功：" if ok else "通知发送失败：") + detail)
        self._show_toast(detail, ok=ok)

    # ---- 事件分发表：kind → 处理器 -------------------------------------------
    # 键集合与 events.EVENT_CONTRACTS 及全部发射点由 tests/test_event_contract.py
    # 做三方一致性校验；分组键序同上注。
    _EVENT_HANDLERS = {
        # 登录与会话（首页登录面板）
        "login_success": _ev_login_success,
        "login_failed": _ev_login_failed,
        "restore_failed": _ev_restore_failed,
        # 首页监控概览（指标行/事件流/通知弹窗）
        "monitor_status": _ev_monitor_status,
        "poll": _ev_poll,
        "rollcall": _ev_rollcall,
        "answer_result": _ev_answer_result,
        "number_code": _ev_number_code,
        # 会话合并（worker 克隆会话回写主会话的内部事件）
        "merge_session_cookies": _ev_merge_session_cookies,
        # 签到情况页
        "course_rollcalls": _ev_course_rollcalls,
        "course_rollcalls_error": _ev_course_rollcalls_error,
        "course_records_verified": _ev_course_records_verified,
        "course_records_verify_error": _ev_course_records_verify_error,
        # 课程课件页（含下载进度链路）
        "courseware_courses": _ev_courseware_courses,
        "courseware_courses_error": _ev_courseware_courses_error,
        "courseware": _ev_courseware,
        "courseware_error": _ev_courseware_error,
        "courseware_download_progress": _ev_courseware_download_progress,
        "courseware_download_item_done": _ev_courseware_download_item_done,
        "courseware_download_done": _ev_courseware_download_done,
        # 全局后台错误（设置页应用行为面板可见）
        "error": _ev_error,
        # 通知页
        "notification_result": _ev_notification_result,
    }

    def _handle_event(self, event):
        kind = event[0]
        handler = self._EVENT_HANDLERS.get(kind)
        if handler is None:
            return
        handler(self, event)

    def _open_path(self, path: str | Path):
        try:
            os.startfile(str(path))
        except Exception as exc:
            self.log(f"打开路径失败：{exc}")
            QMessageBox.warning(self, "打开失败", "无法打开该目录，请检查路径是否存在。")

    def _show_retry_error(self, title: str, message_text: str, retry_callback):
        message = QMessageBox(QMessageBox.Icon.Warning, title, message_text, QMessageBox.StandardButton.Cancel, self)
        retry_button = message.addButton("重试", QMessageBox.ButtonRole.AcceptRole)
        relogin_button = message.addButton("重新登录", QMessageBox.ButtonRole.ActionRole)
        message.button(QMessageBox.StandardButton.Cancel).setText("稍后再说")
        message.exec()
        clicked = message.clickedButton()
        if clicked is retry_button:
            retry_callback()
        elif clicked is relogin_button:
            self.nav.setCurrentRow(0)
            self.password_input.setFocus()

    def _reset_background_error_state(self):
        self.background_error_count = 0
        self.background_error_notified = False

    def _record_background_error(self, detail: str, immediate: bool = False):
        self.background_error_count += 1
        friendly = friendly_error_message(detail, "monitor")
        self.log(f"后台监控异常：{detail}")
        if immediate:
            # 终态错误（会话过期）只发生一次且监控已停：跳过 3 次阈值立即通知，
            # 但保留一次性去重（不重启监控不重复打扰）
            if self.background_error_notified:
                return
        elif self.background_error_count < 3 or self.background_error_notified:
            return
        self.background_error_notified = True
        message = NotificationMessage("xmu助手 监控异常", friendly)
        try:
            settings = get_notification_settings(load_config())
            if settings["system"]["enabled"]:
                self._show_system_notification(message.title, message.body)
            if settings["pushplus"]["enabled"] or settings["qq_mail"]["enabled"]:
                self._send_external_notification(message, "后台异常提醒已发送")
        except Exception as exc:
            self.log(f"后台异常提醒发送失败：{exc}")

    def _add_rollcall_event(self, event: RollcallEvent):
        self.event_sequence += 1
        event_id = f"event-{self.event_sequence}"
        self.events_by_id[event_id] = event
        self.event_order.insert(0, event_id)
        for old_event_id in self.event_order[MAX_EVENT_ROWS:]:
            self.events_by_id.pop(old_event_id, None)
        del self.event_order[MAX_EVENT_ROWS:]
        self.metric_last_result.setText(f"检测到 {event.rollcall_type}")
        self.log(f"检测到签到：{event.course_title} / {event.rollcall_type}")
        self._refresh_event_tables()

        if event.rollcall_type == "数字签到":
            self._run_thread(self._number_code_worker, event_id, event)
        self._notify_rollcall(event_id, event)
        if self.auto_answer_check.isChecked():
            self._answer_event(event_id, event, auto=True)

    def _notify_rollcall(self, event_id: str, event: RollcallEvent):
        message = build_rollcall_notification(event, f"xmurollcall://rollcall/{event_id}")
        try:
            settings = get_notification_settings(load_config())
            if settings["system"]["enabled"]:
                self._show_system_notification(message.title, message.body)
            if settings["pushplus"]["enabled"] or settings["qq_mail"]["enabled"]:
                self._send_external_notification(message)
        except Exception as exc:
            self.log(f"通知准备失败：{exc}")

    def _number_code_worker(self, event_id: str, event: RollcallEvent):
        # 克隆会话：与监控/其他 worker 并发时的 cookiejar 隔离（同 _answer_worker 纪律）
        worker_session = clone_session(self.session) if self.session is not None else None
        if worker_session is None:
            self._emit(("number_code", event_id, "", "登录状态已变更，请重新登录。"))
            return
        try:
            self._emit(("number_code", event_id, fetch_number_code(worker_session, event.rollcall_id)))
        except Exception as exc:
            detail, session_expired = answer_failure_detail(exc)
            if session_expired:
                # 会话过期是终态：与 _answer_worker 过期分支一致，走后台错误
                # 计数/通知引导重新登录（线程安全：仅 emit）——不再把过期吞成
                # 单格文案；后台错误计数有 3 次阈值+一次通知去重，不制造噪音。
                self._emit(("error", f"签到码获取失败：{detail}"))
            self._emit(("number_code", event_id, "", detail))

    def _event_values(self, event: RollcallEvent):
        return (
            time.strftime("%H:%M:%S", time.localtime(event.detected_at)),
            event.remaining_text,
            event.course_title,
            event.teacher,
            event.rollcall_type,
            self._event_status_text(event),
            event.number_code or "-",
        )

    def _event_status_text(self, event: RollcallEvent) -> str:
        if event.result in ("已签到", "已签"):
            return "已签"
        if event.result in ("失败",):
            return "失败"
        if event.result in ("处理中",):
            return "处理中"
        if event.result in ("已跳过", "跳过", "需手动"):
            return "跳过"
        return "未签"

    def _refresh_event_tables(self):
        rows = [self._event_values(self.events_by_id[event_id]) for event_id in self.event_order]
        if not hasattr(self, "events_table"):
            return
        if rows:
            # 首页事件表（7 列：时间/剩余/课程/发起人/类型/状态/签到码）：
            # 时间·发起人·类型·状态·签到码 居中，剩余/课程列左对齐；状态列着色。
            # （旧实现的 (0,3,4..8) 中 7、8 对 7 列表是死列号，参数化时一并剔除。）
            self._set_table_rows(
                self.events_table,
                rows,
                self.event_order,
                centered_columns=(0, 3, 4, 5, 6),
                status_column=5,
            )
            return
        # 首页事件表空态：与「签到情况」页同款跨列灰字占位，
        # 按登录/监控状态给引导文案（登录与监控启停都会经
        # _refresh_guard_status 回刷，文案不会滞留在旧状态）。
        if not self.account:
            message = "登录并启动监控后，检测到的签到会显示在这里"
        elif self.monitor_worker and self.monitor_worker.is_alive() and not (
            self.monitor_stop_event and self.monitor_stop_event.is_set()
        ):
            message = "监控运行中，暂未检测到签到"
        else:
            message = "启动监控后，检测到的签到会显示在这里"
        self._set_table_empty_state(self.events_table, message)

    def _update_event_result(self, event_id: str, result: str, detail: str):
        event = self.events_by_id.get(event_id)
        if not event:
            return
        event.result = result
        event.detail = detail
        self._refresh_event_tables()

    def _update_event_code(self, event_id: str, code: str, detail: str = ""):
        event = self.events_by_id.get(event_id)
        if not event:
            return
        if code:
            event.number_code = code
            if not event.detail:
                event.detail = "已获取签到码"
            self.log(f"数字签到码已获取：{event.course_title} / {code}")
        elif detail:
            event.detail = f"签到码获取失败：{detail}"
            self.log(event.detail)
        else:
            event.detail = "未获取到签到码"
            self.log(f"未获取到数字签到码：{event.course_title}")
        self._refresh_event_tables()

    def _set_table_empty_state(self, table: QTableWidget, message: str) -> None:
        table.setRowCount(0)
        table.insertRow(0)
        item = QTableWidgetItem(message)
        item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsSelectable)
        item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
        item.setForeground(QColor(self._ui_palette()["empty_text"]))
        table.setItem(0, 0, item)
        table.setSpan(0, 0, 1, table.columnCount())

    def _tick_runtime(self):
        if self.started_at and self.monitor_worker and self.monitor_worker.is_alive():
            # 系统时钟回拨（NTP 校正）会产生负时长：钳 0 展示（remaining_text 同款守卫）
            self.metric_runtime.setText(format_duration(max(0, int(time.time() - self.started_at))))

    def log(self, message: str):
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{timestamp}] {message}"
        self.log_messages.append(line)
        self.log_messages = self.log_messages[-MAX_LOG_LINES:]
        # 同步打印到 stdout：供后台运行时重定向到日志文件观测（否则日志只在内存里，
        # 外部无法发现"轮询失败/会话过期"等问题）；可见日志页已移除，仅内存+stdout
        print(line, flush=True)

    def start_monitor(self):
        if not self.session or not self.account:
            QMessageBox.warning(self, "\u5c1a\u672a\u767b\u5f55", "\u8bf7\u5148\u767b\u5f55\u3002")
            return
        worker = self.monitor_worker
        if worker and worker.is_alive():
            if self.monitor_stop_event and self.monitor_stop_event.is_set():
                # 旧线程已收到停止信号但可能仍在重试/请求中：不在此同步 join 等待
                # （join(timeout=2.0) 会阻塞 GUI 线程最坏 2s、事件循环卡死），改由
                # QTimer 有界重启异步拉起（_continue_monitor_restart 校验代数，
                # 用户等待期间点暂停则自动放弃）。此前直接 return 则旧线程随后退出
                # → 监控静默停摆（暂停后立即启动无效）。
                self.log("旧监控线程正在退出，稍后自动重启监控...")
                self._refresh_guard_status()
                self._restart_monitor_later()
                return
            self._refresh_guard_status()
            return
        self._monitor_restart_epoch += 1
        self.monitor_stop_event = threading.Event()
        self.monitor_worker = MonitorWorker(
            self.session,
            self._emit,
            self.monitor_stop_event,
            self._current_poll_interval(),
        )
        self.started_at = time.time()
        self.monitor_worker.start()
        self.metric_monitor.setText("\u5df2\u542f\u52a8")
        self._refresh_guard_status()
        self.log("\u76d1\u63a7\u5df2\u542f\u52a8\u3002")
        self._show_toast("监控已启动")

    def _restart_monitor_later(self, attempts_left: int = 3):
        """旧监控线程（在途网络请求不可中断）退出后再自动拉起新监控（M3）。

        有界重试：避免无限轮询；登出/无会话时直接放弃；排队期间用户又点启动/暂
        停（代数变化）则放弃本次拉起。
        """
        if attempts_left <= 0:
            self.log("监控线程未能及时退出，请稍后手动重新启动。")
            return
        if not (self.account and self.session):
            return
        epoch = self._monitor_restart_epoch
        QTimer.singleShot(2000, lambda: self._continue_monitor_restart(attempts_left - 1, epoch))

    def _continue_monitor_restart(self, attempts_left: int, epoch: int):
        # 排队等待期间用户可能又点了暂停（stop_monitor 代数 +1）或重新启动
        # （start_monitor 代数 +1）：代数已变 → 放弃本次自动拉起，不得在用户
        # 「暂停」状态下被莫名重新开工（批 D 遗留误重启）。
        if epoch != self._monitor_restart_epoch:
            self.log("监控状态已变更，取消自动重启。")
            return
        if not (self.account and self.session):
            return
        worker = self.monitor_worker
        if worker is not None and worker.is_alive():
            self._restart_monitor_later(attempts_left)
            return
        self.start_monitor()

    def stop_monitor(self):
        if self.monitor_stop_event:
            self.monitor_stop_event.set()
        # 暂停/登出：打断任何已排队的自动重启（QTimer 回调校验代数后放弃）
        self._monitor_restart_epoch += 1
        # 取消所有在途的自动延迟应答：监控已停，不应再有延迟提交（锁内取快照）
        with self._answer_cancellations_lock:
            pending_ids = list(self._answer_cancellations.keys())
        for event_id in pending_ids:
            self._cancel_pending_answer(event_id)
        if hasattr(self, "metric_monitor"):
            self.metric_monitor.setText("\u5df2\u6682\u505c" if self.account else "\u672a\u542f\u52a8")
        self._refresh_guard_status()
        self.log("\u6b63\u5728\u6682\u505c\u76d1\u63a7\u3002")
        self._show_toast("监控已暂停")


def main():
    # 代理守卫必须在任何 requests.Session 创建前打补丁：校园网路径不穿系统代理
    # （trust_env=False + 空 proxies）。原先在模块级执行会让测试 import 即被静默
    # 改环境；移入启动入口后源码运行与 PyInstaller launcher 都经此生效。
    disable_system_proxies()
    # 非 UTF8 stdout 管道（如 cp1252 控制台重定向）下，log() 的中文 print 会抛
    # UnicodeEncodeError 使 exe 启动即崩；启动时一次性把 stdout 切到 UTF8 替换
    # 策略，重定向观测日志不再依赖宿主控制台代码页（无 reconfigure 的流静默跳过）
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    # 单例：命名互斥量必须在创建 QApplication 前获取——否则第二个实例已实例化 Qt 应用、
    # 托盘图标后才退出，会短暂出现双窗口/双托盘。互斥量是 OS 级原语，进程退出自动释放。
    is_startup = "--startup" in sys.argv
    if not acquire_single_instance():
        # 已有实例在跑：--startup 静默退出；否则通知已有实例把主窗口显示到前台
        # （命名事件置位 + FindWindow 兜底，覆盖"仅托盘/窗口未创建"的唤起场景）
        if not is_startup:
            activate_existing_instance()
        return 0
    # 首个实例：磁盘冗余清理放后台线程（_MEI 残留可能堆积多份、rmtree 较慢，
    # 同步执行会延迟主窗口显示；后台 daemon 线程不阻塞，也不影响后续登录/监控）
    threading.Thread(target=_background_disk_cleanup, daemon=True).start()
    # 任务栏/托盘归属：不设置 AppUserModelID 时 Windows 按引擎分组，
    # 任务栏显示默认图标而非应用 logo（用户可见的图标错乱根因）。
    try:
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("democard.xmu.assistant")
    except Exception:
        pass
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    # 读取持久化的主题模式（与 Android 端对齐：system / light / dark）
    try:
        theme_mode = get_app_settings(load_config()).get("theme_mode", "system")
    except Exception:
        theme_mode = "system"
    app.setStyleSheet(stylesheet(theme_mode))
    # 启动路径同样要设原生调色板：否则深色下未被子样式覆盖的
    # 普通标签/复选框文字回落到系统默认黑色（通知/策略页黑字根因）
    if resolve_theme_mode(theme_mode) == "dark":
        app.setPalette(dark_qpalette())
    window = DashboardWindow()
    # 双击唤起监听：即使本实例以 --startup 隐藏/仅托盘常驻，后续双击也能被拉出到前台
    install_show_event_watcher(window)
    if is_startup:
        if not window.tray_icon:
            window.showMinimized()
    else:
        window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
