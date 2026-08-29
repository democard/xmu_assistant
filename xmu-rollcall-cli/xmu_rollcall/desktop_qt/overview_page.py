"""首页总览混入（从 app.py 第五刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得首页（监控/登录域）的纯构建与只读渲染能力；
方法体逐字保留搬移前的实现，依赖的宿主回调（_signal_tile/_make_table/
_refresh_event_tables/_refresh_tray_menu/login/logout/start_monitor/
stop_monitor/answer_selected_rollcall/skip_selected_rollcall/log）与监控
守卫状态（monitor_worker/monitor_stop_event）仍由 DashboardWindow 持有。
start_monitor/stop_monitor/_restart_monitor_later 等监控启停守卫逻辑不随迁。
"""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QCheckBox,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from .icons import app_asset_path


class OverviewPageMixin:
    REQUIRED_HOST_ATTRS: tuple[str, ...] = (
        "_make_table",
        "_refresh_event_tables",
        "_refresh_tray_menu",
        "_signal_tile",
        "account",
        "answer_selected_rollcall",
        "login",
        "logout",
        "monitor_stop_event",
        "monitor_worker",
        "sidebar_status",
        "skip_selected_rollcall",
        "start_monitor",
        "stop_monitor",
        "top_status",
    )

    """首页驾驶舱构建与登录/监控状态只读渲染（无自有状态，全部经由宿主窗口）。"""

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
        logout_button = QPushButton("\u9000\u51fa\u767b\u5f55")
        logout_button.setMinimumHeight(42)
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
        stop_button = QPushButton("\u6682\u505c\u76d1\u63a7")
        stop_button.setMinimumHeight(44)
        answer_button = QPushButton("\u5904\u7406\u9009\u4e2d\u7b7e\u5230")
        answer_button.setObjectName("PrimaryButton")
        answer_button.setMinimumHeight(44)
        skip_button = QPushButton("\u6807\u8bb0\u4e3a\u8df3\u8fc7")
        skip_button.setMinimumHeight(44)
        self.auto_answer_check = QCheckBox("\u5f00\u542f\u81ea\u52a8\u7b7e\u5230")
        self.auto_answer_check.setMinimumHeight(44)
        self.auto_answer_rule_label = QLabel("\u6570\u5b57/\u96f7\u8fbe\u81ea\u52a8\u5904\u7406\uff1b\u4e8c\u7ef4\u7801\u4ec5\u63d0\u9192")
        self.auto_answer_rule_label.setObjectName("InlineHint")
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
        # 信号槽集中连接（B5）：仅位置收拢至 build 尾，连接序与语义不变
        login_button.clicked.connect(self.login)
        logout_button.clicked.connect(self.logout)
        start_button.clicked.connect(self.start_monitor)
        stop_button.clicked.connect(self.stop_monitor)
        answer_button.clicked.connect(self.answer_selected_rollcall)
        skip_button.clicked.connect(self.skip_selected_rollcall)
        self.auto_answer_check.stateChanged.connect(self._refresh_auto_answer_status)
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
        # 登录/监控启停状态变化会改变首页事件表的空态文案，在此统一回刷
        if hasattr(self, "events_table"):
            self._refresh_event_tables()
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
