"""通知页混入（从 app.py 第二刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得通知设置页能力；方法体逐字保留搬移前的实现，
依赖的实例属性（notify_* 控件/notification_summary/tray_icon/metric_notifications）
与宿主回调（_panel/_ui_palette/_show_toast/log/_run_thread/_emit/_update_nav_badges）
仍由 DashboardWindow 持有。
"""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..config import (
    CONFIG_LOCK,
    get_notification_settings,
    load_config,
    save_config,
    set_notification_settings,
)
from ..notifications import (
    build_rollcall_notification,
    friendly_error_message,
    notification_provider_status,
    send_with_settings,
)
from .core import RollcallEvent


class NotificationsPageMixin:
    """通知设置页构建与读写行为（无自有状态，全部经由宿主窗口）。"""

    #: 隐式宿主契约显式化（B3，同 TrayMixin）：依赖宿主持有的属性与回调。
    #: ⚠️ 注意 tray_icon 是**跨混入依赖**——它由 TrayMixin._setup_tray 赋值，
    #: 本混入只读取。因此托盘未初始化（无系统托盘环境）时本页的通知展示路径
    #: 必须能容忍 tray_icon 缺失，改动前先确认该分支。
    REQUIRED_HOST_ATTRS: tuple[str, ...] = (
        "_emit",
        "_panel",
        "_run_thread",
        "_show_toast",
        "_ui_palette",
        "_update_nav_badges",
        "log",
        "metric_notifications",
        "tray_icon",
    )

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
        self.push_tip = QLabel("")
        self.push_tip.setObjectName("Subtle")
        self.push_tip.setTextFormat(Qt.TextFormat.RichText)
        push_layout.addWidget(self.push_tip, 3, 0, 1, 2)
        self._refresh_push_tip()
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

    def _refresh_push_tip(self):
        """PushPlus 提示的内嵌 span 色按当前主题烘焙（富文本不跟随 QSS/palette），
        构造与主题切换共用本入口，防止旧主题色残留到下一次重建。"""
        if not hasattr(self, "push_tip"):
            return
        self.push_tip.setText(
            '温馨提示：PushPlus 会收取 <span style="color:'
            + self._ui_palette()["warn_accent"]
            + ';font-weight:700;">约3.5元实名费用</span>；QQ 邮箱免费使用。'
        )

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
