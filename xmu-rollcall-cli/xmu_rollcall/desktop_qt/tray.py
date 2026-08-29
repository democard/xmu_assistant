"""系统托盘混入（从 app.py 第一刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得托盘能力；方法体逐字保留搬移前的实现，
依赖的实例属性（tray_icon/tray_menu/tray_status_action/tray_last_check_action/
tray_toggle_monitor_action/quitting/metric_last_check/monitor_worker/
monitor_stop_event/account）与回调（start_monitor/stop_monitor）仍由
DashboardWindow 持有。
"""

from __future__ import annotations

from PySide6.QtGui import QAction
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from .icons import app_icon


class TrayMixin:
    """托盘生命周期与菜单行为（无自有状态，全部经由宿主窗口）。"""

    #: 隐式宿主契约显式化（B3，照 theme/契约冻结范式）：本混入依赖宿主
    #: （DashboardWindow）持有的属性与回调。只许增不许删——宿主改名/删属性、
    #: 或混入新增依赖而未登记，都会让 tests/test_mixin_host_contract.py 立刻报错。
    #: 口径：不含混入自身赋值的属性、自有方法与 Qt 内建（hide/raise_ 等）。
    REQUIRED_HOST_ATTRS: tuple[str, ...] = (
        "account",
        "metric_last_check",
        "monitor_stop_event",
        "monitor_worker",
        "start_monitor",
        "stop_monitor",
    )

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
        # 与 _refresh_tray_menu 的显示谓词一致（is_running = 存活且未请求停止）：
        # 停止中窗口（已请求停止未退出）菜单显示「启动监控」，点击应走
        # start_monitor → _restart_monitor_later 异步拉起，而非再次 stop_monitor
        # 把用户在主窗口排队的自动重启再次取消（此前裸 is_alive 分流与菜单矛盾）。
        is_running = bool(
            self.monitor_worker
            and self.monitor_worker.is_alive()
            and not (self.monitor_stop_event and self.monitor_stop_event.is_set())
        )
        if is_running:
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
