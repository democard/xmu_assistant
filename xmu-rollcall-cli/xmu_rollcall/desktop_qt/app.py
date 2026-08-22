"""PySide6 desktop dashboard for XMU Rollcall Bot."""

from __future__ import annotations

import os
import json
import shutil
import sys
import tempfile
import threading
import time
import webbrowser
from datetime import date, datetime, timedelta
from pathlib import Path

import requests
from PySide6.QtCore import QObject, Qt, QTimer, Signal
from PySide6.QtGui import QAction, QColor, QFont, QGuiApplication, QIcon, QPalette, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFrame,
    QGraphicsOpacityEffect,
    QGridLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMenu,
    QAbstractItemView,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QStackedWidget,
    QSystemTrayIcon,
    QTableWidget,
    QTableWidgetItem,
    QTextBrowser,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)
from xmulogin import xmulogin

from .. import __version__
from ..config import (
    CONFIG_DIR,
    add_account,
    CONFIG_LOCK,
    get_app_settings,
    get_all_accounts,
    get_cookies_path,
    get_current_account,
    get_notification_settings,
    get_rollcall_settings,
    load_config,
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
    save_config,
    set_current_account,
    set_app_settings,
    set_notification_settings,
    set_rollcall_settings,
)
from ..courseware import (
    CourseSummary,
    CoursewareItem,
    download_courseware,
    fetch_courses,
    fetch_courseware,
    sanitize_filename,
)
from ..engine import RollcallEngine
from ..proxy_guard import disable_system_proxies
from ..notifications import (
    NotificationMessage,
    build_rollcall_notification,
    friendly_error_message,
    notification_provider_status,
    send_with_settings,
)
from ..utils import (
    answer_failure_detail,
    base_url,
    clone_session,
    compute_auto_answer_delay,
    headers,
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
    fetch_course_rollcall_records,
    fetch_number_code,
    format_duration,
)
from .theme import (
    WINDOW_MIN_HEIGHT,
    WINDOW_MIN_WIDTH,
    dark_qpalette,
    palette as theme_palette,
    resolve_theme as resolve_theme_mode,
    stylesheet,
)


disable_system_proxies()

STARTUP_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
STARTUP_VALUE_NAME = "xmu助手"


def app_asset_path(name: str) -> Path:
    bases = []
    bundle_dir = getattr(sys, "_MEIPASS", None)
    if bundle_dir:
        bases.append(Path(bundle_dir))
    bases.extend((Path(__file__).resolve().parents[3], Path.cwd()))
    for base in bases:
        candidate = base / "assets" / name
        if candidate.exists():
            return candidate
    return bases[-1] / "assets" / name


def app_icon() -> QIcon:
    # 首选多分辨率 ICO；若打包环境缺 ICO 解码插件导致加载失败，
    # 回退 PNG（Qt 内置解码），保证窗口/任务栏/托盘永远有应用图标。
    icon = QIcon(str(app_asset_path("xmu-assistant.ico")))
    if not icon.isNull():
        return icon
    for name in ("xmu-assistant-icon.png", "xmu-assistant-mark.png", "xmu-assistant-logo.png"):
        fallback = QIcon(str(app_asset_path(name)))
        if not fallback.isNull():
            return fallback
    return icon


class EventBus(QObject):
    event = Signal(object)


COURSE_STATUS_DISPLAY = {
    "未签到": "未签",
    "未签": "未签",
    "未知": "未知",
    "已签到": "已签",
    "已签": "已签",
    "无签到记录": "无记录",
    "无记录": "无记录",
}

COURSE_STATUS_PRIORITY = {
    "未签": 0,
    "未知": 1,
    "已签": 2,
    "无记录": 3,
}

MAX_EVENT_ROWS = 100
MAX_LOG_LINES = 300


# ---- UI 快照（SWR 缓存先行）-------------------------------------------------
# 最近一次成功拉取的签到记录与课件课程列表落盘；下次启动先渲染快照再后台
# 刷新，页面"秒开"旧数据而非空白等待（平台无缓存头，应用层快照是唯一路径）。
# 损坏/缺失静默跳过；跨账号通过 account_id 校验防串号。
UI_SNAPSHOT_VERSION = 1


def _ui_snapshot_path() -> Path:
    return CONFIG_DIR / "ui_snapshot.json"


def ui_snapshot_to_json(account_id, records, courses) -> str:
    from dataclasses import asdict

    return json.dumps(
        {
            "version": UI_SNAPSHOT_VERSION,
            "account_id": str(account_id or ""),
            "course_records": [asdict(r) for r in records],
            "courseware_courses": [asdict(c) for c in courses],
        },
        ensure_ascii=False,
    )


def ui_snapshot_from_json(text: str):
    """解析快照文本 → (account_id, records, courses)；任何损坏返回 None。"""
    try:
        data = json.loads(text)
        if not isinstance(data, dict) or data.get("version") != UI_SNAPSHOT_VERSION:
            return None
        records = [CourseRollcallRecord(**item) for item in data.get("course_records", [])]
        courses = [CourseSummary(**item) for item in data.get("courseware_courses", [])]
        return str(data.get("account_id", "")), records, courses
    except Exception:
        return None


class DashboardWindow(QMainWindow):
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

        self.event_sequence = 0
        self.events_by_id: dict[str, RollcallEvent] = {}
        self.event_order: list[str] = []
        self.course_records: list[CourseRollcallRecord] = []
        self.courseware_courses: list[CourseSummary] = []
        self.courseware_items: list[CoursewareItem] = []
        self.courseware_course_by_display: dict[str, CourseSummary] = {}
        self.courseware_download_status: dict[str, str] = {}

        self.course_refresh_in_progress = False
        self.courseware_courses_refresh_in_progress = False
        self.courseware_refresh_in_progress = False
        self.courseware_download_in_progress = False
        # UI 快照归属的账号 id：启动恢复时记录，登录成功时校验防跨账号串号
        self._snapshot_account_id = ""
        # 当前生效主题（resolved 后的 light/dark），动态取色的依据
        self._current_theme_mode = "system"
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
        # 跟随系统模式下，系统深浅切换时联动重刷（Qt 6.5+ colorSchemeChanged）
        try:
            QGuiApplication.styleHints().colorSchemeChanged.connect(
                lambda _scheme: self._apply_theme(self._current_theme_mode)
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
        self._update_nav_badges()

    def _setup_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self.tray_icon = QSystemTrayIcon(self)
        self.tray_icon.setIcon(app_icon())
        self.tray_icon.setToolTip("xmu助手")
        self.tray_menu = QMenu(self)
        self.tray_status_action = QAction("状态：未登录", self)
        self.tray_status_action.setEnabled(False)
        self.tray_last_check_action = QAction("最近检查：-", self)
        self.tray_last_check_action.setEnabled(False)
        self.tray_toggle_monitor_action = QAction("启动监控", self)
        self.tray_toggle_monitor_action.triggered.connect(self._toggle_monitor_from_tray)
        show_action = QAction("打开 xmu助手", self)
        show_action.triggered.connect(self._show_from_tray)
        quit_action = QAction("退出", self)
        quit_action.triggered.connect(self._quit_from_tray)
        self.tray_menu.addAction(self.tray_status_action)
        self.tray_menu.addAction(self.tray_last_check_action)
        self.tray_menu.addSeparator()
        self.tray_menu.addAction(show_action)
        self.tray_menu.addAction(self.tray_toggle_monitor_action)
        self.tray_menu.addSeparator()
        self.tray_menu.addAction(quit_action)
        self.tray_menu.aboutToShow.connect(self._refresh_tray_menu)
        self.tray_icon.setContextMenu(self.tray_menu)
        self.tray_icon.activated.connect(self._handle_tray_activated)
        self.tray_icon.show()
        self._refresh_tray_menu()

    def _handle_tray_activated(self, reason):
        if reason in (
            QSystemTrayIcon.ActivationReason.Trigger,
            QSystemTrayIcon.ActivationReason.DoubleClick,
        ):
            self._show_from_tray()

    def _show_from_tray(self):
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def _toggle_monitor_from_tray(self):
        if self.monitor_worker and self.monitor_worker.is_alive():
            self.stop_monitor()
        else:
            self.start_monitor()

    def _refresh_tray_menu(self):
        if not hasattr(self, "tray_status_action"):
            return
        is_running = bool(
            self.monitor_worker
            and self.monitor_worker.is_alive()
            and not (self.monitor_stop_event and self.monitor_stop_event.is_set())
        )
        if not self.account:
            status = "未登录"
        elif is_running:
            status = "正在监控"
        else:
            status = "已暂停"
        last_check = "-"
        if hasattr(self, "metric_last_check"):
            last_check = self.metric_last_check.text() or "-"
        self.tray_status_action.setText(f"状态：{status}")
        self.tray_last_check_action.setText(f"最近检查：{last_check}")
        self.tray_toggle_monitor_action.setText("暂停监控" if is_running else "启动监控")

    def _quit_from_tray(self):
        self.quitting = True
        self.stop_monitor()
        QApplication.quit()

    def closeEvent(self, event):
        if self.tray_icon and not self.quitting:
            event.ignore()
            self.hide()
            self.tray_icon.showMessage("xmu助手", "已在后台常驻，监控会继续运行。")
            return
        super().closeEvent(event)

    def _show_page(self, row: int):
        if row < 0:
            return
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
        self.time_range_combo.currentTextChanged.connect(lambda _text: self._refresh_course_table())
        refresh_button = QPushButton("刷新签到情况数据")
        refresh_button.setObjectName("PrimaryButton")
        refresh_button.clicked.connect(self.refresh_course_rollcalls)
        self.only_unsigned_check = QCheckBox("只显示未签")
        self.only_unsigned_check.stateChanged.connect(self._refresh_course_table)
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
        toolbar_layout.addWidget(self.course_summary, 1)
        layout.addWidget(toolbar)

        self.course_table = self._make_table(
            ("日期", "课程", "签到时间", "类型", "状态", "签到 ID"),
            (130, 340, 160, 100, 90, 120),
        )
        layout.addWidget(self.course_table, 1)
        return page

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
        self.courseware_academic_year_combo.currentTextChanged.connect(
            lambda _text: self._apply_courseware_course_filters()
        )
        self.courseware_semester_combo = QComboBox()
        self.courseware_semester_combo.addItems(("全部", "第一学期", "第二学期", "第三学期"))
        self.courseware_semester_combo.setFixedWidth(110)
        self.courseware_semester_combo.currentTextChanged.connect(lambda _text: self._apply_courseware_course_filters())
        self.courseware_combo = QComboBox()
        self.courseware_combo.setMinimumWidth(300)
        self.courseware_combo.currentTextChanged.connect(lambda _text: self.refresh_selected_courseware(silent=True))
        refresh_courses = QPushButton("刷新课程")
        refresh_courses.clicked.connect(self.refresh_courseware_courses)
        refresh_items = QPushButton("刷新课件")
        refresh_items.setObjectName("PrimaryButton")
        refresh_items.clicked.connect(self.refresh_selected_courseware)
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
        self.courseware_table.cellDoubleClicked.connect(lambda _row, _column: self.activate_selected_courseware())
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
        download_button.clicked.connect(self.download_selected_courseware)
        select_all_button = QPushButton("全选")
        select_all_button.setToolTip("选中当前课程下读取到的所有课件。")
        select_all_button.clicked.connect(self.select_all_courseware)
        open_button = QPushButton("打开平台页面")
        open_button.clicked.connect(self.open_selected_courseware)
        choose_button = QPushButton("选择目录")
        choose_button.clicked.connect(self.choose_courseware_download_dir)
        self.courseware_download_dir = QLineEdit(str(Path.home() / "Downloads" / "XMU-Courseware"))
        actions.addWidget(download_button)
        actions.addWidget(select_all_button)
        actions.addWidget(open_button)
        actions.addWidget(choose_button)
        actions.addWidget(self.courseware_download_dir, 1)
        layout.addLayout(actions)
        return page

    def _build_notifications_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(12)

        system_panel = self._panel("本机通知")
        system_layout = QGridLayout(system_panel)
        system_layout.setContentsMargins(16, 40, 16, 16)
        self.notify_system_check = QCheckBox("开启系统通知")
        system_layout.addWidget(self.notify_system_check, 0, 0, 1, 2)
        self.notify_system_status = QLabel("未开启")
        self.notify_system_status.setObjectName("Subtle")
        system_layout.addWidget(QLabel("当前状态"), 1, 0)
        system_layout.addWidget(self.notify_system_status, 1, 1)
        layout.addWidget(system_panel)

        push_panel = self._panel("微信 PushPlus")
        push_layout = QGridLayout(push_panel)
        push_layout.setContentsMargins(16, 40, 16, 16)
        self.notify_pushplus_check = QCheckBox("开启微信通知")
        self.notify_pushplus_token = QLineEdit()
        self.notify_pushplus_token.setEchoMode(QLineEdit.Password)
        self.notify_pushplus_token.setPlaceholderText("填写 PushPlus token")
        push_layout.addWidget(self.notify_pushplus_check, 0, 0, 1, 2)
        push_layout.addWidget(QLabel("Token"), 1, 0)
        push_layout.addWidget(self.notify_pushplus_token, 1, 1)
        self.notify_pushplus_status = QLabel("未开启")
        self.notify_pushplus_status.setObjectName("Subtle")
        push_layout.addWidget(QLabel("当前状态"), 2, 0)
        push_layout.addWidget(self.notify_pushplus_status, 2, 1)
        push_tip = QLabel(
            '温馨提示：PushPlus 会收取 <span style="color:'
            + self._ui_palette()["warn_accent"]
            + ';font-weight:700;">约3.5元实名费用</span>；QQ 邮箱免费使用。'
        )
        push_tip.setObjectName("Subtle")
        push_tip.setTextFormat(Qt.TextFormat.RichText)
        push_layout.addWidget(push_tip, 3, 0, 1, 2)
        layout.addWidget(push_panel)

        qq_panel = self._panel("QQ 邮箱通知")
        qq_layout = QGridLayout(qq_panel)
        qq_layout.setContentsMargins(16, 40, 16, 16)
        self.notify_qq_check = QCheckBox("开启 QQ 邮箱提醒")
        self.notify_qq_sender = QLineEdit()
        self.notify_qq_password = QLineEdit()
        self.notify_qq_password.setEchoMode(QLineEdit.Password)
        self.notify_qq_recipient = QLineEdit()
        self.notify_qq_host = QLineEdit("smtp.qq.com")
        self.notify_qq_port = QLineEdit("465,587")
        self.notify_qq_sender.setPlaceholderText("例如 student@example.invalid")
        self.notify_qq_password.setPlaceholderText("填写 QQ 邮箱生成的 SMTP 授权码")
        self.notify_qq_recipient.setPlaceholderText("接收提醒的邮箱")
        self.notify_qq_host.setPlaceholderText("默认 smtp.qq.com")
        self.notify_qq_port.setPlaceholderText("465,587")
        self.notify_qq_port.setToolTip("可填写多个端口，例如 465,587；465 使用 SSL，587 使用 STARTTLS。")
        qq_layout.addWidget(self.notify_qq_check, 0, 0, 1, 2)
        qq_layout.addWidget(QLabel("发件 QQ 邮箱"), 1, 0)
        qq_layout.addWidget(self.notify_qq_sender, 1, 1)
        qq_layout.addWidget(QLabel("SMTP 授权码"), 2, 0)
        qq_layout.addWidget(self.notify_qq_password, 2, 1)
        qq_layout.addWidget(QLabel("收件邮箱"), 3, 0)
        qq_layout.addWidget(self.notify_qq_recipient, 3, 1)
        qq_layout.addWidget(QLabel("SMTP 服务器"), 4, 0)
        qq_layout.addWidget(self.notify_qq_host, 4, 1)
        qq_layout.addWidget(QLabel("端口"), 5, 0)
        qq_layout.addWidget(self.notify_qq_port, 5, 1)
        self.notify_qq_status = QLabel("未开启")
        self.notify_qq_status.setObjectName("Subtle")
        qq_layout.addWidget(QLabel("当前状态"), 6, 0)
        qq_layout.addWidget(self.notify_qq_status, 6, 1)
        layout.addWidget(qq_panel)

        actions = QHBoxLayout()
        save_button = QPushButton("保存通知设置")
        save_button.setObjectName("PrimaryButton")
        save_button.clicked.connect(self.save_notification_settings)
        test_button = QPushButton("发送测试通知")
        test_button.clicked.connect(self.test_notifications)
        self.notification_summary = QLabel("通知用于提醒并打开 App，签到仍由已登录的 xmu助手执行。")
        self.notification_summary.setObjectName("Subtle")
        actions.addWidget(save_button)
        actions.addWidget(test_button)
        actions.addWidget(self.notification_summary, 1)
        layout.addLayout(actions)
        layout.addStretch(1)
        self._connect_notification_status_updates()
        return page

    def _build_tutorial_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        guide = QTextBrowser()
        guide.setReadOnly(True)
        guide.setOpenLinks(False)
        guide.anchorClicked.connect(lambda url: guide.scrollToAnchor(url.fragment()))
        self.tutorial_guide = guide
        guide.setHtml(self._tutorial_html())
        layout.addWidget(guide, 1)
        return page

    def _refresh_tutorial_html(self):
        """主题切换后重建教程 HTML（颜色由当前调色板注入，深浅两态一致）。"""
        if getattr(self, "tutorial_guide", None) is not None:
            self.tutorial_guide.setHtml(self._tutorial_html())

    def _tutorial_html(self) -> str:
        pal = self._ui_palette()
        html = """
        <div style="font-family:'Microsoft YaHei','Segoe UI',sans-serif; color:@TUT_TEXT@; line-height:1.72;">
          <h2 style="margin-top:0;">xmu助手教程</h2>
          <p>按下面的清单设置即可。账号、Cookie、通知 Token 和下载目录只保存在本机。</p>
          <table cellspacing="8" cellpadding="10" style="margin:4px 0 16px 0;">
            <tr>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#enable" style="color:@TUT_TEXT@;text-decoration:none;">签到启用教程</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#monitor" style="color:@TUT_TEXT@;text-decoration:none;">开启监控</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#auto" style="color:@TUT_TEXT@;text-decoration:none;">开启自动签到</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#pushplus" style="color:@TUT_TEXT@;text-decoration:none;">设置微信通知</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#qqmail" style="color:@TUT_TEXT@;text-decoration:none;">设置 QQ 邮箱通知</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#courseware" style="color:@TUT_TEXT@;text-decoration:none;">下载课件</a></td>
              <td style="background:@NAV_BG@;border:1px solid @NAV_BORDER@;border-radius:8px;"><a href="#faq" style="color:@TUT_TEXT@;text-decoration:none;">常见问题</a></td>
            </tr>
          </table>

          <h3><a name="enable"></a>签到启用教程</h3>
          <ol>
            <li>在“首页”输入学号和密码，点击“登录”。</li>
            <li>看到账号状态变成“已登录”后，再点击“启动监控”。</li>
            <li>关闭窗口不会退出软件；它会留在托盘继续运行。</li>
            <li>要彻底退出，请右键托盘图标，点击“退出”。</li>
          </ol>

          <h3><a name="monitor"></a>开启监控</h3>
          <ol>
            <li>点击“启动监控”后，软件会按策略页的轮询间隔检查签到。</li>
            <li>检测到签到会写入“今日签到事件”，并按通知设置提醒你。</li>
            <li>如果连续网络异常，软件会少量提醒，不会每次轮询都打扰你。</li>
          </ol>

          <h3><a name="auto"></a>开启自动签到</h3>
          <ol>
            <li>勾选“开启自动签到”。</li>
            <li>数字签到和雷达签到会按当前策略自动处理。</li>
            <li>二维码签到只提醒，不会自动提交。</li>
          </ol>

          <h3><a name="pushplus"></a>设置微信通知</h3>
          <ol>
            <li>打开 PushPlus 官网，用微信登录并复制 token。</li>
            <li>在“通知”页勾选“开启微信通知”，把 token 填入 Token。</li>
            <li>点击“保存通知设置”，再点击“发送测试通知”。</li>
            <li>如果失败，通常是 token 没复制完整，重新复制后再试。</li>
          </ol>

          <h3><a name="qqmail"></a>设置 QQ 邮箱通知</h3>
          <ol>
            <li>在 QQ 邮箱<span style="color:@WARN@;font-weight:700;">网页版</span>开启 SMTP 服务，并生成授权码。（打开网页-&gt;点击设置-&gt;点击账号与安全-&gt;安全设置-&gt;下滑找到生成入口）</li>
            <li>“发件 QQ 邮箱”填写你的 QQ 邮箱。</li>
            <li>“SMTP 授权码”填写邮箱生成的授权码，不要填写 QQ 密码。</li>
            <li>“收件邮箱”填写接收提醒的邮箱，可以和发件邮箱相同。</li>
            <li>“端口”可填写多个，例如 465,587；软件会按顺序尝试，465 使用 SSL，587 使用 STARTTLS。</li>
            <li>保存后发送测试通知；能收到邮件才算配置成功。</li>
          </ol>

          <h3><a name="courseware"></a>下载课件</h3>
          <ol>
            <li>进入“课程课件”，选择学年、学期和课程，点击“刷新课件”。</li>
            <li>勾选要下载的课件；需要全下时点击“全选”。</li>
            <li>点击“下载”。文件会直接保存，视频/网页/H5 等可能保存为入口文件。</li>
            <li>下载失败时，该课件行会直接显示短原因，弹窗会列出完整原因。</li>
          </ol>

          <h3><a name="faq"></a>常见问题</h3>
          <ol>
            <li>提示登录过期：回到“首页”重新登录。</li>
            <li>通知收不到：先看“通知”页状态是否为“已配置”，再发送测试通知。</li>
            <li>课件读取失败：可能是平台权限、资源失效或登录过期，稍后重试或重新登录。</li>
            <li>不想后台运行：右键托盘图标，点击“退出”。</li>
          </ol>
        </div>
        """
        return (
            html
            .replace("@TUT_TEXT@", pal["tutorial_text"])
            .replace("@NAV_BG@", pal["tutorial_nav_bg"])
            .replace("@NAV_BORDER@", pal["tutorial_nav_border"])
            .replace("@WARN@", pal["warn_accent"])
        )

    def _build_settings_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        panel = self._panel("监控策略")
        panel_layout = QGridLayout(panel)
        panel_layout.setContentsMargins(16, 40, 16, 16)
        panel_layout.setColumnStretch(0, 1)
        panel_layout.setColumnStretch(1, 2)
        panel_layout.setColumnStretch(2, 1)
        self.poll_interval_spin = QSpinBox()
        self.poll_interval_spin.setRange(MIN_POLL_INTERVAL_SECONDS, MAX_POLL_INTERVAL_SECONDS)
        self.poll_interval_spin.setValue(DEFAULT_POLL_INTERVAL_SECONDS)
        self.poll_interval_spin.setSuffix(" 秒")
        self.poll_interval_spin.setToolTip("启动监控时默认使用这个轮询间隔。")
        self.poll_interval_spin.setMinimumWidth(240)
        self.poll_interval_save_button = QPushButton("确认更改")
        self.poll_interval_save_button.setObjectName("PrimaryButton")
        self.poll_interval_save_button.clicked.connect(self._save_poll_interval_setting)
        self.poll_interval_status = QLabel("")
        self.poll_interval_status.setObjectName("StatusGood")
        self.poll_interval_spin.valueChanged.connect(lambda _value: self.poll_interval_status.clear())
        panel_layout.addWidget(QLabel("默认轮询间隔（1-300秒）"), 0, 0)
        panel_layout.addWidget(self.poll_interval_spin, 0, 1, Qt.AlignmentFlag.AlignCenter)
        panel_layout.addWidget(self.poll_interval_save_button, 0, 2, Qt.AlignmentFlag.AlignRight)
        panel_layout.addWidget(self.poll_interval_status, 1, 0, 1, 3, Qt.AlignmentFlag.AlignLeft)
        layout.addWidget(panel)

        app_panel = self._panel("应用行为")
        app_layout = QGridLayout(app_panel)
        app_layout.setContentsMargins(16, 40, 16, 16)
        self.launch_on_startup_check = QCheckBox("开机自动启动 xmu助手")
        self.launch_on_startup_check.stateChanged.connect(self._save_app_settings)
        self.launch_on_startup_status = QLabel("")
        self.launch_on_startup_status.setObjectName("StatusGood")
        behavior_tip = QLabel("关闭窗口后会进入托盘继续运行；彻底退出请右键托盘图标选择“退出”。")
        behavior_tip.setObjectName("Subtle")
        app_layout.addWidget(self.launch_on_startup_check, 0, 0)
        app_layout.addWidget(self.launch_on_startup_status, 0, 1)
        app_layout.addWidget(behavior_tip, 1, 0, 1, 2)
        layout.addWidget(app_panel)

        appearance_panel = self._panel("外观")
        appearance_layout = QGridLayout(appearance_panel)
        appearance_layout.setContentsMargins(16, 40, 16, 16)
        appearance_layout.setColumnStretch(0, 1)
        appearance_layout.setColumnStretch(1, 2)
        appearance_layout.setColumnStretch(2, 1)
        self.theme_mode_combo = QComboBox()
        self.theme_mode_combo.addItems(("跟随系统", "浅色", "深色"))
        self.theme_mode_combo.setMinimumWidth(240)
        self.theme_mode_combo.setToolTip("选择主题模式；跟随系统会在系统切换深浅色时自动联动。")
        self.theme_mode_combo.currentIndexChanged.connect(self._save_theme_mode)
        self.theme_mode_status = QLabel("")
        self.theme_mode_status.setObjectName("StatusGood")
        appearance_layout.addWidget(QLabel("主题模式"), 0, 0)
        appearance_layout.addWidget(self.theme_mode_combo, 0, 1, Qt.AlignmentFlag.AlignCenter)
        appearance_layout.addWidget(self.theme_mode_status, 0, 2, Qt.AlignmentFlag.AlignRight)
        appearance_tip = QLabel("深色模式在夜间使用更护眼；跟随系统会读取 Windows 的深浅色设置。")
        appearance_tip.setObjectName("Subtle")
        appearance_layout.addWidget(appearance_tip, 1, 0, 1, 3)
        layout.addWidget(appearance_panel)
        layout.addStretch(1)
        return page

    def _panel(self, title: str) -> QFrame:
        frame = QFrame()
        frame.setObjectName("Panel")
        label = QLabel(title, frame)
        label.setObjectName("Subtle")
        label.move(16, 12)
        return frame

    def _metric(self, layout: QGridLayout, row: int, column: int, label: str, value: str) -> QLabel:
        frame = QFrame()
        frame.setObjectName("Metric")
        frame.setMinimumHeight(76)
        frame_layout = QVBoxLayout(frame)
        frame_layout.setContentsMargins(14, 10, 14, 10)
        frame_layout.setSpacing(4)
        label_widget = QLabel(label)
        label_widget.setObjectName("MetricLabel")
        value_widget = QLabel(value)
        value_widget.setObjectName("MetricValue")
        value_widget.setMinimumHeight(26)
        value_widget.setWordWrap(True)
        frame_layout.addWidget(label_widget)
        frame_layout.addWidget(value_widget)
        layout.addWidget(frame, row, column)
        return value_widget

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

    def _set_table_rows(self, table: QTableWidget, rows: list[tuple], row_ids: list[str] | None = None):
        # O(n) 重建：一次 setRowCount 后直接 setItem，
        # 避免 insertRow 循环触发 Qt 每行内部重排（O(n²)）。
        table.setRowCount(0)
        table.setRowCount(len(rows))
        for row_index, values in enumerate(rows):
            for column, value in enumerate(values):
                item = QTableWidgetItem(str(value))
                if row_ids and column == 0:
                    item.setData(Qt.ItemDataRole.UserRole, row_ids[row_index])
                if column in (0, 3, 4, 5, 6, 7, 8):
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                if table.objectName() == "TimelineTable" and column == 5:
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

    def _startup_legacy_paths(self) -> tuple[Path, ...]:
        startup_dir = (
            Path(os.environ.get("APPDATA", str(Path.home() / "AppData" / "Roaming")))
            / "Microsoft"
            / "Windows"
            / "Start Menu"
            / "Programs"
            / "Startup"
        )
        return (startup_dir / "xmu助手.vbs", startup_dir / "xmu助手.lnk")

    def _startup_target_path(self) -> Path:
        if getattr(sys, "frozen", False):
            return Path(sys.executable)
        desktop_exe = Path.home() / "Desktop" / "xmu助手.exe"
        if desktop_exe.exists():
            return desktop_exe
        return Path(sys.executable)

    def _startup_command(self) -> str:
        return f'"{self._startup_target_path()}" --startup'

    def _cleanup_legacy_startup_entries(self):
        for path in self._startup_legacy_paths():
            if path.exists():
                path.unlink()

    def _launch_on_startup_enabled(self) -> bool:
        if os.name != "nt":
            return False
        # winreg 仅 Windows 可用：延迟导入，避免非 Windows 平台 import 本模块即崩溃
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_RUN_KEY, 0, winreg.KEY_READ) as key:
                command, _value_type = winreg.QueryValueEx(key, STARTUP_VALUE_NAME)
            return str(command).strip() == self._startup_command()
        except FileNotFoundError:
            return False

    def _set_launch_on_startup(self, enabled: bool):
        if os.name != "nt":
            return
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enabled:
                winreg.SetValueEx(key, STARTUP_VALUE_NAME, 0, winreg.REG_SZ, self._startup_command())
            else:
                try:
                    winreg.DeleteValue(key, STARTUP_VALUE_NAME)
                except FileNotFoundError:
                    pass
        self._cleanup_legacy_startup_entries()

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

    def _load_notification_settings(self):
        try:
            settings = get_notification_settings(load_config())
            self.notify_system_check.setChecked(settings["system"]["enabled"])
            self.notify_pushplus_check.setChecked(settings["pushplus"]["enabled"])
            self.notify_pushplus_token.setText(settings["pushplus"]["token"])
            self.notify_qq_check.setChecked(settings["qq_mail"]["enabled"])
            self.notify_qq_sender.setText(settings["qq_mail"]["sender"])
            self.notify_qq_password.setText(settings["qq_mail"]["password"])
            self.notify_qq_recipient.setText(settings["qq_mail"]["recipient"])
            self.notify_qq_host.setText(settings["qq_mail"]["smtp_host"])
            self.notify_qq_port.setText(str(settings["qq_mail"].get("smtp_port", "465,587")))
            self._refresh_notification_metric(settings)
        except Exception as exc:
            self.log(f"读取通知设置失败：{exc}")

    def _notification_settings_from_ui(self) -> dict:
        return {
            "system": {"enabled": self.notify_system_check.isChecked()},
            "pushplus": {
                "enabled": self.notify_pushplus_check.isChecked(),
                "token": self.notify_pushplus_token.text(),
            },
            "qq_mail": {
                "enabled": self.notify_qq_check.isChecked(),
                "sender": self.notify_qq_sender.text(),
                "password": self.notify_qq_password.text(),
                "recipient": self.notify_qq_recipient.text(),
                "smtp_host": self.notify_qq_host.text(),
                "smtp_port": self.notify_qq_port.text().strip() or "465,587",
            },
        }

    def _connect_notification_status_updates(self):
        for checkbox in (self.notify_system_check, self.notify_pushplus_check, self.notify_qq_check):
            checkbox.stateChanged.connect(self._refresh_notification_status_from_ui)
        for field in (
            self.notify_pushplus_token,
            self.notify_qq_sender,
            self.notify_qq_password,
            self.notify_qq_recipient,
            self.notify_qq_host,
        ):
            field.textChanged.connect(self._refresh_notification_status_from_ui)
        self.notify_qq_port.textChanged.connect(self._refresh_notification_status_from_ui)

    def _refresh_notification_status_from_ui(self, *_args):
        self._refresh_notification_metric(self._notification_settings_from_ui())

    def save_notification_settings(self):
        try:
            # 读-改-写整体持锁：与登录写回（_login_worker，后台线程）并发时
            # 不加锁会互相覆盖丢失修改
            with CONFIG_LOCK:
                config = load_config()
                set_notification_settings(config, self._notification_settings_from_ui())
                save_config(config)
            settings = get_notification_settings(config)
            self._refresh_notification_metric(settings)
            self.notification_summary.setText("通知设置已保存。")
            self.log("通知设置已保存。")
            self._show_toast("通知设置已保存")
        except Exception as exc:
            self.log(f"保存通知设置失败：{exc}")
            self._show_toast("通知设置保存失败", ok=False)
            QMessageBox.critical(self, "保存失败", friendly_error_message(exc, "settings"))

    def test_notifications(self):
        self.save_notification_settings()
        settings = get_notification_settings(load_config())
        external_enabled = settings["pushplus"]["enabled"] or settings["qq_mail"]["enabled"]
        if not (settings["system"]["enabled"] or external_enabled):
            self.notification_summary.setText("未开启任何通知，请先开启本机、微信或 QQ 邮箱通知。")
            self._show_toast("未开启任何通知", ok=False)
            return
        message = build_rollcall_notification(
            RollcallEvent(
                rollcall_id="test",
                course_title="测试课程",
                teacher="xmu助手",
                rollcall_type="测试通知",
                status="test",
                raw={},
                result="测试",
            ),
            "xmurollcall://rollcall/test",
        )
        if self.notify_system_check.isChecked():
            self._show_system_notification(message.title, message.body)
        if external_enabled:
            self._send_external_notification(message, "测试通知已发送")
        else:
            self.notification_summary.setText("测试通知已发送")
            self._show_toast("测试通知已发送")

    def _refresh_notification_metric(self, settings: dict):
        statuses = notification_provider_status(settings)
        if hasattr(self, "notify_system_status"):
            self.notify_system_status.setText(statuses["system"])
            self.notify_pushplus_status.setText(statuses["pushplus"])
            self.notify_qq_status.setText(statuses["qq_mail"])
        enabled = []
        if statuses["system"] == "已配置":
            enabled.append("本机")
        if statuses["pushplus"] == "已配置":
            enabled.append("微信")
        if statuses["qq_mail"] == "已配置":
            enabled.append("QQ")
        self.metric_notifications.setText(" / ".join(enabled) if enabled else "未开启")
        self._update_nav_badges()

    def _show_system_notification(self, title: str, body: str):
        if self.tray_icon:
            self.tray_icon.showMessage(title, body)

    def _send_external_notification(self, message, success_text: str = "通知已发送"):
        self._run_thread(self._notification_worker, message, success_text)

    def _notification_worker(self, message, success_text: str):
        try:
            settings = get_notification_settings(load_config())
            errors = send_with_settings(settings, message)
            if errors:
                self._emit(("notification_result", False, "；".join(errors)))
            else:
                self._emit(("notification_result", True, success_text))
        except Exception as exc:
            self._emit(("notification_result", False, friendly_error_message(exc, "notification")))

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

    def login(self):
        username = self.username_input.text().strip()
        password = self.password_input.text().strip()
        if not username or not password:
            QMessageBox.warning(self, "缺少信息", "请输入学号和密码。")
            return
        self._set_login_status("正在登录...", warn=True)
        self.log("开始登录。")
        self._run_thread(self._login_worker, username, password, self._login_epoch)

    def _login_worker(self, username: str, password: str, login_epoch: int = -1):
        try:
            session = xmulogin(type=3, username=username, password=password)
            if not session:
                self._emit(("login_failed", "登录失败，请检查学号或密码。"))
                return

            try:
                profile = session.get(f"{base_url}/api/profile", headers=headers, timeout=(6, 15)).json()
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
            self._set_login_status("正在自动恢复会话...", warn=True)
            self.log("检测到上次登录会话，正在自动恢复。")
            self._run_thread(self._restore_worker, True, self._login_epoch)
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
        self.course_refresh_in_progress = False
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
        self._answer_event(event_id, self.events_by_id[event_id])

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

    def refresh_course_rollcalls(self, silent=False):
        if not self.session or not self.account:
            if not silent:
                QMessageBox.warning(self, "尚未登录", "请先登录。")
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
        try:
            username = str(self.account.get("username", "")) if self.account else ""
            records, source = fetch_course_rollcall_records(
                self.session,
                username=username,
                academic_year=academic_year,
                semester=semester,
            )
            self._emit(("course_rollcalls", records, source))
        except Exception as exc:
            self._emit(("course_rollcalls_error", str(exc), silent))

    def refresh_courseware_courses(self, silent=False):
        if not self.session:
            if not silent:
                QMessageBox.warning(self, "尚未登录", "请先在首页登录。")
            return
        if self.courseware_courses_refresh_in_progress:
            return
        self.courseware_courses_refresh_in_progress = True
        self.courseware_summary.setText("正在读取课程列表...")
        self.log("开始读取课件课程列表。")
        self._run_thread(self._courseware_courses_worker, silent)

    def _courseware_courses_worker(self, silent=False):
        try:
            courses, source = fetch_courses(self.session)
            self._emit(("courseware_courses", courses, source))
        except Exception as exc:
            self._emit(("courseware_courses_error", str(exc), silent))

    def refresh_selected_courseware(self, silent=False):
        if not self.session:
            if not silent:
                QMessageBox.warning(self, "尚未登录", "请先在首页登录。")
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
        try:
            self._emit(("courseware", course, fetch_courseware(self.session, course.course_id)))
        except Exception as exc:
            self._emit(("courseware_error", str(exc), silent))

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

    def _refresh_after_login(self):
        self.log("登录完成，开始后台刷新签到情况和课件课程。")
        self.refresh_course_rollcalls(silent=True)
        self.refresh_courseware_courses(silent=True)

    def _save_ui_snapshot(self):
        """把当前签到记录/课件课程列表落盘（SWR 缓存先行，原子写）。失败静默。"""
        try:
            text = ui_snapshot_to_json(
                (self.account or {}).get("id"),
                self.course_records,
                self.courseware_courses,
            )
            path = _ui_snapshot_path()
            tmp = f"{path}.tmp"
            with open(tmp, "w", encoding="utf-8") as file:
                file.write(text)
            os.replace(tmp, path)
        except Exception:
            pass

    def _restore_ui_snapshot(self):
        """启动时恢复上次快照：页面先显示旧数据，后台刷新完成后覆盖。"""
        try:
            parsed = ui_snapshot_from_json(_ui_snapshot_path().read_text(encoding="utf-8"))
        except Exception:
            return
        if not parsed:
            return
        account_id, records, courses = parsed
        self._snapshot_account_id = account_id
        if records:
            self.course_records = records
            self._refresh_course_table()
            self.log(f"已从本地快照恢复 {len(records)} 条签到记录（缓存先行）。")
        if courses:
            self._set_courseware_courses(courses, "本地快照")

    def _handle_event(self, event):
        kind = event[0]
        if kind == "login_success":
            _, session, account = event
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
                self.course_records = []
                self.courseware_courses = []
                self._snapshot_account_id = ""
                self._refresh_course_table()
                try:
                    _ui_snapshot_path().unlink(missing_ok=True)
                except Exception:
                    pass
            else:
                self._snapshot_account_id = str(account.get("id"))
            display = account.get("name") or account.get("username") or "已登录"
            self._set_login_status("已登录", warn=False)
            self.metric_account.setText(display)
            self._load_rollcall_settings(account)
            self.log(f"登录成功：{display}")
            self._show_toast(f"{display} 已登录")
            self._refresh_after_login()
        elif kind == "login_failed":
            self._set_login_status("未登录", warn=True)
            self.metric_account.setText("未登录")
            self.log(f"登录失败：{event[1]}")
            self._show_toast("登录失败，请检查账号或网络", ok=False)
            QMessageBox.critical(self, "登录失败", friendly_error_message(event[1], "login"))
        elif kind == "restore_failed":
            _, message, silent = event
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
        elif kind == "monitor_status":
            self.metric_monitor.setText(event[1])
            self.log(f"监控状态：{event[1]}")
        elif kind == "poll":
            _, query_count, checked_at, rollcall_count = event
            self._reset_background_error_state()
            self.metric_query_count.setText(str(query_count))
            self.metric_last_check.setText(time.strftime("%H:%M:%S", time.localtime(checked_at)))
            self.metric_rollcall_count.setText(str(rollcall_count))
            self._refresh_tray_menu()
        elif kind == "rollcall":
            self._add_rollcall_event(event[1])
        elif kind == "answer_result":
            _, event_id, ok, detail = event
            self._update_event_result(event_id, "已签到" if ok else "失败", detail)
            self.metric_last_result.setText(detail)
            self.log(f"签到处理结果：{detail}")
            rollcall = self.events_by_id.get(event_id)
            if rollcall:
                self._notify_rollcall(event_id, rollcall)
        elif kind == "merge_session_cookies":
            # GUI 线程单点写：worker 克隆会话的 cookie 回写主会话（含登出/换号守卫）
            worker_session = event[1]
            worker_account_id = event[2] if len(event) > 2 else ""
            self._merge_worker_session(worker_session, worker_account_id)
        elif kind == "number_code":
            event_id = event[1]
            code = event[2]
            detail = event[3] if len(event) > 3 else ""
            self._update_event_code(event_id, code, detail)
        elif kind == "course_rollcalls":
            _, records, source = event
            self.course_refresh_in_progress = False
            self.course_records = records
            self._refresh_course_table()
            self._update_nav_badges()
            self._save_ui_snapshot()
            self.log(f"签到情况刷新完成。{source}")
        elif kind == "course_rollcalls_error":
            self.course_refresh_in_progress = False
            self.course_summary.setText("刷新失败")
            self.log(f"签到情况刷新失败：{event[1]}")
            if not event[2]:
                self._show_retry_error(
                    "刷新失败",
                    friendly_error_message(event[1], "courseware"),
                    self.refresh_course_rollcalls,
                )
        elif kind == "courseware_courses":
            self.courseware_courses_refresh_in_progress = False
            self._set_courseware_courses(event[1], event[2])
            self._update_nav_badges()
            self._save_ui_snapshot()
        elif kind == "courseware_courses_error":
            self.courseware_courses_refresh_in_progress = False
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
        elif kind == "courseware":
            self.courseware_refresh_in_progress = False
            self._set_courseware_items(event[1], event[2])
            self._update_nav_badges()
        elif kind == "courseware_error":
            self.courseware_refresh_in_progress = False
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
        elif kind == "courseware_download_progress":
            _, index, total, filename, key = event
            self.courseware_download_status[key] = "下载中"
            self._refresh_courseware_table()
            self._update_nav_badges()
            self.courseware_summary.setText(f"正在下载 {index}/{total}：{filename}")
        elif kind == "courseware_download_item_done":
            _, key, status = event
            self.courseware_download_status[key] = status
            self._refresh_courseware_table()
            self._update_nav_badges()
        elif kind == "courseware_download_done":
            self.courseware_download_in_progress = False
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
        elif kind == "error":
            self._record_background_error(event[1])
        elif kind == "notification_result":
            _, ok, detail = event
            self.notification_summary.setText(detail)
            self.log(("通知发送成功：" if ok else "通知发送失败：") + detail)
            self._show_toast(detail, ok=ok)

    def _open_path(self, path: str | Path):
        try:
            os.startfile(str(path))
        except Exception as exc:
            self.log(f"打开路径失败：{exc}")
            QMessageBox.warning(self, "打开失败", "无法打开该目录，请检查路径是否存在。")

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

    def _record_background_error(self, detail: str):
        self.background_error_count += 1
        friendly = friendly_error_message(detail, "monitor")
        self.log(f"后台监控异常：{detail}")
        if self.background_error_count < 3 or self.background_error_notified:
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
        try:
            self._emit(("number_code", event_id, fetch_number_code(self.session, event.rollcall_id)))
        except Exception as exc:
            self._emit(("number_code", event_id, "", str(exc)))

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
        self._set_table_rows(self.events_table, rows, self.event_order)

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

    def _course_status_text(self, status: str) -> str:
        return COURSE_STATUS_DISPLAY.get(status, status or "未知")

    def _course_status_priority(self, record: CourseRollcallRecord) -> int:
        return COURSE_STATUS_PRIORITY.get(self._course_status_text(record.signed_status), 1)

    def _course_record_datetime(self, record: CourseRollcallRecord) -> datetime | None:
        text = str(record.rollcall_time or "").strip()
        if not text or text == "-":
            return None
        for candidate in (text, text[:19]):
            try:
                return datetime.fromisoformat(candidate.replace("Z", "+00:00")).replace(tzinfo=None)
            except ValueError:
                continue
        return None

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
            self.course_table.setItem(row, column, item)

    def _set_course_empty_state(self, message: str) -> None:
        self.course_table.setRowCount(0)
        self.course_table.insertRow(0)
        item = QTableWidgetItem(message)
        item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsSelectable)
        item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
        item.setForeground(QColor(self._ui_palette()["empty_text"]))
        self.course_table.setItem(0, 0, item)
        self.course_table.setSpan(0, 0, 1, self.course_table.columnCount())

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
            self._set_course_empty_state(empty_message)
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

        # 单遍累加状态计数，避免 4 次 sum() 各自全量遍历 course_records
        status_counts: dict[str, int] = {}
        for record in self.course_records:
            status_counts[record.signed_status] = status_counts.get(record.signed_status, 0) + 1
        signed = status_counts.get("已签到", 0)
        unsigned = status_counts.get("未签到", 0)
        unknown = status_counts.get("未知", 0)
        no_rollcalls = status_counts.get("无签到记录", 0)
        summary = f"共 {len(self.course_records)} 条；已签 {signed}；未签 {unsigned}；未知 {unknown}；无记录 {no_rollcalls}"
        if self.only_unsigned_check.isChecked():
            summary += f"；当前显示 {len(records)} 条未签"
        self.course_summary.setText(summary)

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
        if status.startswith("下载失败"):
            return pal["cw_failed"]
        return {
            "可下载": pal["cw_ready"],
            "下载中": pal["cw_downloading"],
            "下载成功": pal["cw_success"],
            "下载失败": pal["cw_failed"],
        }.get(status, pal["cw_ready"])

    def _tick_runtime(self):
        if self.started_at and self.monitor_worker and self.monitor_worker.is_alive():
            self.metric_runtime.setText(format_duration(int(time.time() - self.started_at)))

    def log(self, message: str):
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{timestamp}] {message}"
        self.log_messages.append(line)
        self.log_messages = self.log_messages[-MAX_LOG_LINES:]
        if hasattr(self, "log_text"):
            self.log_text.append(line)
        # 同步打印到 stdout：供后台运行时重定向到日志文件观测（否则日志只在内存里，
        # 外部无法发现"轮询失败/会话过期"等问题）
        print(line, flush=True)

    def _build_overview_page(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(16)

        cockpit = QHBoxLayout()
        cockpit.setSpacing(16)

        identity_panel = QFrame()
        identity_panel.setObjectName("IdentityPanel")
        identity_panel.setMinimumWidth(280)
        identity_layout = QVBoxLayout(identity_panel)
        identity_layout.setContentsMargins(18, 18, 18, 18)
        identity_layout.setSpacing(14)

        identity_head = QHBoxLayout()
        identity_logo = QLabel()
        identity_logo.setObjectName("IdentityLogo")
        identity_logo.setFixedSize(58, 58)
        identity_logo.setAlignment(Qt.AlignmentFlag.AlignCenter)
        pixmap = QPixmap(str(app_asset_path("xmu-assistant-mark.png")))
        if not pixmap.isNull():
            identity_logo.setPixmap(
                pixmap.scaled(
                    54,
                    54,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )
        identity_title_box = QVBoxLayout()
        identity_title_box.setSpacing(2)
        identity_title = QLabel("xmu\u52a9\u624b")
        identity_title.setObjectName("IdentityTitle")
        identity_subtitle = QLabel("\u6821\u56ed\u7b7e\u5230\u5b88\u62a4\u4e2d\u5fc3")
        identity_subtitle.setObjectName("IdentitySubtitle")
        identity_title_box.addWidget(identity_title)
        identity_title_box.addWidget(identity_subtitle)
        identity_head.addWidget(identity_logo)
        identity_head.addLayout(identity_title_box, 1)
        identity_layout.addLayout(identity_head)

        self.guard_status_badge = QLabel("\u672a\u767b\u5f55")
        self.guard_status_badge.setObjectName("HeroStatusWarn")
        identity_layout.addWidget(self.guard_status_badge)

        login_form = QGridLayout()
        login_form.setHorizontalSpacing(10)
        login_form.setVerticalSpacing(9)
        self.username_input = QLineEdit()
        self.password_input = QLineEdit()
        self.password_input.setEchoMode(QLineEdit.Password)
        login_form.addWidget(QLabel("\u5b66\u53f7"), 0, 0)
        login_form.addWidget(self.username_input, 0, 1)
        login_form.addWidget(QLabel("\u5bc6\u7801"), 1, 0)
        login_form.addWidget(self.password_input, 1, 1)

        login_button = QPushButton("\u767b\u5f55")
        login_button.setObjectName("PrimaryButton")
        login_button.setMinimumHeight(42)
        login_button.clicked.connect(self.login)
        logout_button = QPushButton("\u9000\u51fa\u767b\u5f55")
        logout_button.setMinimumHeight(42)
        logout_button.clicked.connect(self.logout)
        buttons = QHBoxLayout()
        buttons.setSpacing(10)
        buttons.addWidget(login_button)
        buttons.addWidget(logout_button)
        login_form.addLayout(buttons, 2, 0, 1, 2)
        identity_layout.addLayout(login_form)
        identity_layout.addStretch(1)

        guardian_panel = QFrame()
        guardian_panel.setObjectName("GuardianPanel")
        guardian_layout = QVBoxLayout(guardian_panel)
        guardian_layout.setContentsMargins(22, 18, 22, 22)
        guardian_layout.setSpacing(10)

        hero_row = QHBoxLayout()
        hero_row.setSpacing(18)
        hero_text = QVBoxLayout()
        hero_text.setSpacing(6)
        hero_eyebrow = QLabel("\u4eca\u65e5\u5b88\u62a4")
        hero_eyebrow.setObjectName("HeroEyebrow")
        self.guard_title_label = QLabel("\u672a\u767b\u5f55")
        self.guard_title_label.setObjectName("GuardTitle")
        hero_text.addWidget(hero_eyebrow)
        hero_text.addWidget(self.guard_title_label)
        hero_row.addLayout(hero_text, 1)

        dial = QFrame()
        dial.setObjectName("GuardianDial")
        dial.setFixedSize(112, 112)
        dial_layout = QVBoxLayout(dial)
        dial_layout.setContentsMargins(10, 10, 10, 10)
        dial_layout.setAlignment(Qt.AlignmentFlag.AlignCenter)
        dial_logo = QLabel()
        dial_logo.setObjectName("DialLogo")
        dial_logo.setFixedSize(52, 52)
        dial_logo.setAlignment(Qt.AlignmentFlag.AlignCenter)
        if not pixmap.isNull():
            dial_logo.setPixmap(
                pixmap.scaled(
                    50,
                    50,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )
        dial_label = QLabel("\u5b88\u62a4\u4e2d\u5fc3")
        dial_label.setObjectName("DialLabel")
        dial_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        dial_layout.addStretch(1)
        dial_layout.addWidget(dial_logo)
        dial_layout.addWidget(dial_label)
        dial_layout.addStretch(1)
        hero_row.addWidget(dial)
        guardian_layout.addLayout(hero_row)
        guardian_layout.addSpacing(2)

        signal_panel = QFrame()
        signal_panel.setObjectName("SignalPanel")
        signal_layout = QGridLayout(signal_panel)
        signal_layout.setContentsMargins(0, 0, 0, 0)
        signal_layout.setHorizontalSpacing(10)
        signal_layout.setVerticalSpacing(10)
        self.metric_account = self._signal_tile(signal_layout, 0, 0, "\u8d26\u53f7", "\u672a\u767b\u5f55")
        self.metric_monitor = self._signal_tile(signal_layout, 0, 1, "\u76d1\u63a7", "\u672a\u542f\u52a8")
        self.metric_auto_answer = self._signal_tile(signal_layout, 0, 2, "\u81ea\u52a8\u7b7e\u5230", "\u672a\u5f00\u542f")
        self.metric_notifications = self._signal_tile(signal_layout, 0, 3, "\u901a\u77e5", "\u672a\u5f00\u542f")
        self.metric_last_result = self._signal_tile(signal_layout, 1, 0, "\u6700\u8fd1\u7ed3\u679c", "-")
        self.metric_rollcall_count = self._signal_tile(signal_layout, 1, 1, "\u5f53\u524d\u7b7e\u5230", "0")
        self.metric_runtime = self._signal_tile(signal_layout, 1, 2, "\u8fd0\u884c\u65f6\u957f", "0s")
        self.metric_last_check = self._signal_tile(signal_layout, 1, 3, "\u6700\u8fd1\u68c0\u67e5", "-")
        for badge in (self.metric_rollcall_count, self.metric_runtime, self.metric_last_check):
            badge.setObjectName("NumberBadge")
        guardian_layout.addWidget(signal_panel)

        cockpit.addWidget(identity_panel, 1)
        cockpit.addWidget(guardian_panel, 3)
        layout.addLayout(cockpit)

        action_panel = QFrame()
        action_panel.setObjectName("ActionDock")
        action_layout = QHBoxLayout(action_panel)
        action_layout.setContentsMargins(18, 14, 18, 14)
        action_layout.setSpacing(12)
        action_label = QLabel("\u5feb\u6377\u5904\u7406")
        action_label.setObjectName("DockTitle")
        start_button = QPushButton("\u542f\u52a8\u76d1\u63a7")
        start_button.setObjectName("PrimaryButton")
        start_button.setMinimumHeight(44)
        start_button.clicked.connect(self.start_monitor)
        stop_button = QPushButton("\u6682\u505c\u76d1\u63a7")
        stop_button.setMinimumHeight(44)
        stop_button.clicked.connect(self.stop_monitor)
        answer_button = QPushButton("\u5904\u7406\u9009\u4e2d\u7b7e\u5230")
        answer_button.setObjectName("PrimaryButton")
        answer_button.setMinimumHeight(44)
        answer_button.clicked.connect(self.answer_selected_rollcall)
        skip_button = QPushButton("\u6807\u8bb0\u4e3a\u8df3\u8fc7")
        skip_button.setMinimumHeight(44)
        skip_button.clicked.connect(self.skip_selected_rollcall)
        self.auto_answer_check = QCheckBox("\u5f00\u542f\u81ea\u52a8\u7b7e\u5230")
        self.auto_answer_check.setMinimumHeight(44)
        self.auto_answer_check.stateChanged.connect(self._refresh_auto_answer_status)
        self.auto_answer_rule_label = QLabel("\u6570\u5b57/\u96f7\u8fbe\u81ea\u52a8\u5904\u7406\uff1b\u4e8c\u7ef4\u7801\u4ec5\u63d0\u9192")
        self.auto_answer_rule_label.setObjectName("InlineHint")
        self.metric_query_count = QLabel("0")
        self.metric_query_count.hide()
        self.metric_background = QLabel("\u5df2\u542f\u7528")
        self.metric_background.hide()
        action_layout.addWidget(action_label)
        action_layout.addWidget(start_button, 1)
        action_layout.addWidget(stop_button, 1)
        action_layout.addWidget(answer_button, 1)
        action_layout.addWidget(skip_button, 1)
        action_layout.addWidget(self.auto_answer_check)
        action_layout.addWidget(self.auto_answer_rule_label)
        layout.addWidget(action_panel)

        events_panel = QFrame()
        events_panel.setObjectName("TimelinePanel")
        events_layout = QVBoxLayout(events_panel)
        events_layout.setContentsMargins(18, 16, 18, 18)
        events_layout.setSpacing(12)
        events_head = QHBoxLayout()
        events_title = QLabel("\u4eca\u65e5\u7b7e\u5230\u4e8b\u4ef6")
        events_title.setObjectName("SectionTitle")
        events_head.addWidget(events_title)
        events_head.addStretch(1)
        self.events_table = self._make_table(
            (
                "\u65f6\u95f4",
                "\u5269\u4f59",
                "\u8bfe\u7a0b",
                "\u53d1\u8d77\u4eba",
                "\u7c7b\u578b",
                "\u72b6\u6001",
                "\u7b7e\u5230\u7801",
            ),
            (92, 80, 280, 150, 100, 90, 120),
        )
        self.events_table.setObjectName("TimelineTable")
        self.events_table.setMinimumHeight(180)
        events_layout.addLayout(events_head)
        events_layout.addWidget(self.events_table, 1)
        layout.addWidget(events_panel, 1)
        self._refresh_auto_answer_status()
        return page

    def _refresh_auto_answer_status(self, *_args):
        if hasattr(self, "metric_auto_answer"):
            self.metric_auto_answer.setText("\u5df2\u5f00\u542f" if self.auto_answer_check.isChecked() else "\u672a\u5f00\u542f")
        if hasattr(self, "auto_answer_rule_label"):
            self.auto_answer_rule_label.setText(
                "\u6570\u5b57/\u96f7\u8fbe\u5c06\u81ea\u52a8\u5904\u7406"
                if self.auto_answer_check.isChecked()
                else "\u5f00\u542f\u540e\uff1a\u6570\u5b57/\u96f7\u8fbe\u81ea\u52a8\uff0c\u4e8c\u7ef4\u7801\u4ec5\u63d0\u9192"
            )
        self._refresh_guard_status()

    def _refresh_guard_status(self):
        if not hasattr(self, "guard_status_badge"):
            return
        stop_requested = bool(self.monitor_stop_event and self.monitor_stop_event.is_set())
        is_running = bool(self.monitor_worker and self.monitor_worker.is_alive() and not stop_requested)
        if not self.account:
            text = "\u672a\u767b\u5f55"
            object_name = "HeroStatusWarn"
        elif is_running:
            text = "\u6b63\u5728\u5b88\u62a4"
            object_name = "HeroStatusGood"
        else:
            text = "\u5df2\u6682\u505c"
            object_name = "HeroStatusIdle"
        self.guard_status_badge.setText(text)
        self.guard_status_badge.setObjectName(object_name)
        self.guard_status_badge.style().unpolish(self.guard_status_badge)
        self.guard_status_badge.style().polish(self.guard_status_badge)
        if hasattr(self, "guard_title_label"):
            self.guard_title_label.setText(text)
        self._refresh_tray_menu()

    def _set_login_status(self, text: str, warn: bool):
        self.top_status.setText(text)
        self.sidebar_status.setText(text)
        object_name = "StatusWarn" if warn else "StatusGood"
        self.top_status.setObjectName(object_name)
        self.sidebar_status.setObjectName(object_name)
        self.top_status.style().unpolish(self.top_status)
        self.top_status.style().polish(self.top_status)
        self.sidebar_status.style().unpolish(self.sidebar_status)
        self.sidebar_status.style().polish(self.sidebar_status)
        self._refresh_guard_status()

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


# 单例互斥量名：OS 在进程退出时自动释放。选 Win32 命名互斥量而非 Qt QLocalServer，
# 后者依赖 PySide6.QtNetwork 的命名管道后端，在 PyInstaller onefile 打包后不可靠。
SINGLE_INSTANCE_MUTEX_NAME = "xmu_assistant_dashboard_single"
# 首个实例持有的互斥量句柄（保活到进程结束，避免 GC 提前关闭导致单例失效）
_single_instance_mutex_handle = None
# 跨进程「双击唤起开窗口」命名事件：FindWindow 按标题找窗口的方案在目标实例为
# --startup、主窗口从未创建/不可见（仅托盘）时够不到，改用命名事件置位通知。
SHOW_EVENT_NAME = "xmu_assistant_dashboard_show"
# 独占实例持有的事件句柄（保活到进程退出）；新实例双击时 OpenEvent + SetEvent。
_show_event_handle = None
# 监听 QTimer 的强引用（局部 QTimer 会被 GC 提前销毁导致不再触发）
_active_show_timers: list[QTimer] = []
MAIN_WINDOW_TITLE = "xmu助手"  # 与 setWindowTitle 一致，供 FindWindow 唤起已有实例
# _MEI 解包残留的安全清理年龄：仅删 1 小时前的目录，避免误删正在被并发启动实例使用的解包目录
_MEI_CLEANUP_MIN_AGE_SECONDS = 3600


def acquire_single_instance() -> bool:
    """创建命名互斥量。True=本实例是首个（持锁）；False=已有实例在跑。

    非 Windows 或无 pywin32 时退化为不限制（不阻塞启动）。
    """
    global _single_instance_mutex_handle
    try:
        import win32event
        import win32api
        import winerror
    except ImportError:
        return True
    try:
        handle = win32event.CreateMutex(None, False, SINGLE_INSTANCE_MUTEX_NAME)
    except Exception:
        return True
    # ERROR_ALREADY_EXISTS(=183) 定义在 winerror（不在 win32event/win32con）：
    # CreateMutex 后立即查 LastError，已存在同名互斥量=首个实例已持有
    if win32api.GetLastError() == winerror.ERROR_ALREADY_EXISTS:
        try:
            win32api.CloseHandle(handle)
        except Exception:
            pass
        return False
    _single_instance_mutex_handle = handle  # 持有到进程退出，OS 自动释放
    # 首个实例额外创建命名「显示主窗口」事件：供后续实例双击唤起时跨进程置位。
    global _show_event_handle
    try:
        _show_event_handle = win32event.CreateEvent(None, True, False, SHOW_EVENT_NAME)
    except Exception:
        _show_event_handle = None
    return True


def raise_existing_instance_window() -> None:
    """找到已有实例的主窗口并唤到前台（用户再次双击 exe 时把托盘里的窗口弹出）。"""
    try:
        import win32gui
        import win32con
    except ImportError:
        return
    try:
        hwnd = win32gui.FindWindow(None, MAIN_WINDOW_TITLE)
        if not hwnd:
            return
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        win32gui.SetForegroundWindow(hwnd)
    except Exception:
        pass


def activate_existing_instance() -> None:
    """非首个实例：通知独占实例把主窗口显示到前台。

    优先用命名事件（SetEvent）——FindWindow 按标题找窗口在目标为 --startup、
    主窗口从未创建/仅托盘时找不到；事件机制与窗口是否显示无关、更可靠。
    目标不支持事件机制（旧版 exe / 无 pywin32）时降级回标题查找。
    """
    try:
        import win32event
        import win32api
    except ImportError:
        raise_existing_instance_window()
        return
    try:
        handle = win32event.OpenEvent(win32event.EVENT_MODIFY_STATE, False, SHOW_EVENT_NAME)
        if handle:
            win32event.SetEvent(handle)
            win32api.CloseHandle(handle)
            return
    except Exception:
        pass
    raise_existing_instance_window()


def _show_event_tick(window: "DashboardWindow") -> None:
    """事件已置位：把主窗口显示到前台并复位事件（QTimer 周期回调）。"""
    try:
        import win32event
    except ImportError:
        return
    try:
        if win32event.WaitForSingleObject(_show_event_handle, 0) != win32event.WAIT_OBJECT_0:
            return
        win32event.ResetEvent(_show_event_handle)
    except Exception:
        return
    # 从未显示（仅托盘/--startup）→ 显示；最小化 → 恢复正常；再置顶激活
    if not window.isVisible():
        window.show()
    elif window.isMinimized():
        window.showNormal()
    if not window.isActiveWindow():
        window.raise_()
        window.activateWindow()


def install_show_event_watcher(window: "DashboardWindow") -> None:
    """独占实例侧：监听「双击唤起」事件并周期检查（覆盖仅托盘常驻场景）。

    目标实例即使主窗口从未创建/不可见，也能在收到事件时把窗口显示出来，
    彻底避免「双击没反应」（旧 FindWindow 方案在窗口未创建时静默失效）。
    """
    try:
        import win32event  # noqa: F401  验证 pywin32 可用
    except ImportError:
        return
    if _show_event_handle is None:
        return
    timer = QTimer()
    timer.setInterval(500)
    timer.timeout.connect(lambda: _show_event_tick(window))
    timer.start()
    _active_show_timers.append(timer)  # 保强引用防 GC


def cleanup_orphaned_pyinstaller_temp() -> None:
    """清理 %TEMP% 下残留的 _MEI* 解包目录。

    onefile exe 每次启动在 %TEMP%\\_MEIxxxxxx 解包；崩溃/被杀时该目录不自清，常驻托盘
    长期运行会成片堆积（单份约 80MB）。本函数扫描 %TEMP%，删除非当前进程、且超过
    _MEI_CLEANUP_MIN_AGE_SECONDS 年龄的 _MEI 目录（年龄保护：避免删到正在被并发启动
    实例使用的解包目录）。
    """
    current_mei = os.path.normcase(getattr(sys, "_MEIPASS", "") or "")
    temp_dir = os.environ.get("TEMP") or os.environ.get("TMP") or tempfile.gettempdir()
    try:
        entries = os.listdir(temp_dir)
    except OSError:
        return
    now = time.time()
    for name in entries:
        if not name.startswith("_MEI"):  # 仅处理 PyInstaller onefile 解包目录
            continue
        path = os.path.join(temp_dir, name)
        if not os.path.isdir(path):
            continue
        if current_mei and os.path.normcase(path) == current_mei:  # 跳过当前进程的解包目录
            continue
        try:
            # 年龄保护：跳过近期创建/修改的目录（可能正被并发启动实例使用）
            if now - os.path.getmtime(path) < _MEI_CLEANUP_MIN_AGE_SECONDS:
                continue
        except OSError:
            continue
        shutil.rmtree(path, ignore_errors=True)


def cleanup_orphaned_cookie_files(config: dict) -> None:
    """删除 CONFIG_DIR 中不属于任何账号的 {id}.json cookie 文件。

    账号被删除后其 cookie 缓存会遗留。保守地只删纯数字命名的 {id}.json（排除 config.json 等）。
    """
    try:
        valid_ids = {
            str(a.get("id")) for a in config.get("accounts", []) if a.get("id") is not None
        }
    except Exception:
        return
    try:
        entries = os.listdir(CONFIG_DIR)
    except OSError:
        return
    for name in entries:
        if not name.endswith(".json"):
            continue
        stem = name[:-len(".json")]
        if not stem.isdigit() or stem in valid_ids:  # 仅清纯数字账号 cookie，绝不动 config.json
            continue
        try:
            os.remove(os.path.join(CONFIG_DIR, name))
        except OSError:
            pass


def _background_disk_cleanup() -> None:
    """后台执行磁盘冗余清理：_MEI 残留（可能慢，堆积多目录）放后台不阻塞窗口显示。"""
    cleanup_orphaned_pyinstaller_temp()
    try:
        cleanup_orphaned_cookie_files(load_config())
    except Exception:
        pass


def main():
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
