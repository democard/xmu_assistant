"""设置页混入（从 app.py 第六刀拆分，机械搬移不改逻辑）。

DashboardWindow 通过继承本混入获得设置页能力；方法体逐字保留搬移前的实现，
依赖的实例属性（poll_interval_* / launch_on_startup_* / theme_mode_*）由本混入
构建并写回宿主，宿主回调（_panel/_save_poll_interval_setting/_save_app_settings/
_export_logs/_save_theme_mode）仍由 DashboardWindow 持有。
"""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QGridLayout,
    QLabel,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .. import __version__
from ..config import (
    DEFAULT_POLL_INTERVAL_SECONDS,
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
)


class SettingsPageMixin:
    """设置页构建与保存回调接线（无自有状态，全部经由宿主窗口）。"""

    REQUIRED_HOST_ATTRS = (
        "_export_logs",
        "_panel",
        "_save_app_settings",
        "_save_poll_interval_setting",
        "_save_theme_mode",
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
        panel_layout.addWidget(
            QLabel(f"默认轮询间隔（{MIN_POLL_INTERVAL_SECONDS}-{MAX_POLL_INTERVAL_SECONDS}秒）"), 0, 0
        )
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
        version_label = QLabel(f"当前版本 v{__version__}")
        version_label.setObjectName("Subtle")
        app_layout.addWidget(version_label, 2, 0, 1, 2)
        export_log_button = QPushButton("导出运行日志")
        export_log_button.setToolTip("把本次运行最近 300 条日志保存为文本文件，便于反馈问题。")
        export_log_button.clicked.connect(self._export_logs)
        app_layout.addWidget(export_log_button, 3, 0)
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
