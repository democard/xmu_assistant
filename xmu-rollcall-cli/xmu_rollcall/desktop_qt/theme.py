"""XMU blue and white Qt styling for xmu助手.

支持浅色 / 深色两套主题（与 Android 端 XmuMobileTheme 对齐）。
- 浅色：XMU 蓝白主调，海军蓝侧边栏 + 白底卡片
- 深色：GitHub-dark 风格深底 + 浅蓝主色，保证深色界面无刺眼亮块
- 跟随系统：在 Qt 6.5+ 上读取系统色彩方案，否则回退浅色
"""

from __future__ import annotations


WINDOW_MIN_WIDTH = 1080
WINDOW_MIN_HEIGHT = 680


def _light_stylesheet() -> str:
    return """
    * {
        font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
        font-size: 13px;
    }

    QMainWindow, QWidget#Root {
        background: #eef3f8;
        color: #18324f;
    }

    QWidget#Sidebar {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #082a56, stop:1 #0b3f75);
        border-right: 1px solid #d1deeb;
    }

    QLabel#SidebarLogo {
        background: transparent;
    }

    QLabel#Brand {
        color: #ffffff;
        font-size: 22px;
        font-weight: 800;
    }

    QLabel#BrandNote {
        color: #c9ddf0;
        font-size: 12px;
        font-weight: 600;
    }

    QLabel#Subtle {
        color: #4e6076;
    }

    QLabel#PageTitle {
        color: #0b315f;
        font-size: 24px;
        font-weight: 800;
    }

    QLabel#MetricValue {
        color: #0b315f;
        font-size: 20px;
        font-weight: 800;
    }

    QLabel#MetricLabel {
        color: #556779;
        font-size: 12px;
    }

    QLabel#StatusGood {
        color: #157347;
        font-weight: 700;
    }

    QLabel#StatusWarn {
        color: #b4472a;
        font-weight: 700;
    }

    QFrame#Hero {
        background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #ffffff, stop:0.55 #f6faff, stop:1 #d8e9fb);
        border: 1px solid #c3d8ec;
        border-radius: 8px;
    }

    QLabel#HeroTitle {
        color: #0b315f;
        font-size: 28px;
        font-weight: 900;
    }

    QLabel#HeroCaption {
        color: #536b83;
        font-size: 13px;
    }

    QLabel#HeroStatusGood, QLabel#HeroStatusWarn, QLabel#HeroStatusIdle {
        border-radius: 12px;
        padding: 5px 12px;
        font-weight: 800;
        font-size: 13px;
    }

    QLabel#HeroStatusGood {
        background: #e7f6ee;
        color: #11683d;
        border: 1px solid #b8e3ca;
    }

    QLabel#HeroStatusWarn {
        background: #fff1ed;
        color: #a74228;
        border: 1px solid #f2c2b5;
    }

    QLabel#HeroStatusIdle {
        background: #eef5fc;
        color: #245b8d;
        border: 1px solid #bfd8ef;
    }

    QLabel#HeroLogo {
        background: transparent;
    }

    QFrame#IdentityPanel, QFrame#GuardianPanel, QFrame#ActionDock, QFrame#TimelinePanel {
        background: #ffffff;
        border: 1px solid #c9dceb;
        border-radius: 8px;
    }

    QFrame#IdentityPanel {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #ffffff, stop:1 #f1f7fd);
    }

    QLabel#IdentityLogo, QLabel#DialLogo {
        background: transparent;
    }

    QLabel#IdentityTitle {
        color: #0b315f;
        font-size: 24px;
        font-weight: 900;
    }

    QLabel#IdentitySubtitle {
        color: #5d7287;
        font-size: 12px;
        font-weight: 700;
    }

    QFrame#GuardianPanel {
        background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #ffffff, stop:0.55 #f7fbff, stop:1 #dcecfb);
    }

    QLabel#HeroEyebrow {
        color: #4f6f8f;
        font-size: 13px;
        font-weight: 800;
    }

    QLabel#GuardTitle {
        color: #082a56;
        font-size: 34px;
        font-weight: 900;
    }

    QFrame#GuardianDial {
        background: qradialgradient(cx:0.5, cy:0.5, radius:0.72, fx:0.5, fy:0.5, stop:0 #ffffff, stop:0.62 #e8f3ff, stop:0.64 #bcd7ef, stop:0.8 #eef7ff, stop:1 #0b3f75);
        border: 2px solid #0b3f75;
        border-radius: 56px;
    }

    QLabel#DialLabel {
        color: #0b315f;
        font-size: 13px;
        font-weight: 900;
    }

    QFrame#SignalPanel {
        background: transparent;
        border: 0;
    }

    QFrame#SignalTile {
        background: rgba(255, 255, 255, 218);
        border: 1px solid #c9dceb;
        border-radius: 8px;
    }

    QLabel#SignalLabel {
        color: #4a6478;
        font-size: 12px;
        font-weight: 700;
    }

    QLabel#SignalValue {
        color: #0b315f;
        font-size: 18px;
        font-weight: 900;
    }

    QLabel#DockTitle, QLabel#SectionTitle {
        color: #0b315f;
        font-size: 17px;
        font-weight: 900;
    }

    QLabel#SectionHint {
        color: #4d6478;
        font-size: 12px;
    }

    QLabel#InlineHint {
        color: #45607a;
        font-size: 12px;
        font-weight: 600;
    }

    QFrame#EmptyStateCard {
        background: #f7fbff;
        border: 1px dashed #b7d0e7;
        border-radius: 8px;
    }

    QLabel#EmptyStateText {
        color: #475d72;
        font-size: 15px;
        font-weight: 700;
    }

    QLabel#EmptyWatermark {
        background: transparent;
    }

    QLabel#NumberBadge {
        color: #0b315f;
        background: #e8f2fc;
        border: 1px solid #bdd5ec;
        border-radius: 11px;
        padding: 2px 10px;
        font-size: 15px;
        font-weight: 900;
    }

    QLabel#ToastGood, QLabel#ToastWarn {
        border-radius: 8px;
        padding: 8px 12px;
        font-weight: 700;
    }

    QLabel#ToastGood {
        color: #115c3b;
        background: #e8f6ef;
        border: 1px solid #bfe4cf;
    }

    QLabel#ToastWarn {
        color: #8a3320;
        background: #fff0ec;
        border: 1px solid #efc1b5;
    }

    QFrame#GuidePanel {
        background: #f6faff;
        border: 1px solid #c9dceb;
        border-radius: 8px;
    }

    QLabel#WizardStep {
        color: #0b315f;
        background: #ffffff;
        border: 1px solid #c9dceb;
        border-radius: 8px;
        padding: 8px 10px;
        font-weight: 800;
    }

    QPushButton#TutorialJump {
        background: #f6faff;
        color: #0b315f;
        border: 1px solid #b8d2ea;
        border-radius: 8px;
        font-weight: 800;
        padding: 10px 12px;
    }

    QPushButton#TutorialJump:hover {
        background: #e7f2fc;
        border-color: #5e91c7;
    }

    QFrame#Panel {
        background: #ffffff;
        border: 1px solid #d2e0ee;
        border-radius: 8px;
    }

    QFrame#Metric {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #fbfdff, stop:1 #f3f8fd);
        border: 1px solid #d7e5f3;
        border-radius: 8px;
    }

    QListWidget#Navigation {
        background: transparent;
        border: 0;
        outline: 0;
        color: #dbe8f5;
    }

    QListWidget#Navigation::item {
        min-height: 38px;
        padding: 8px 12px;
        border-radius: 8px;
        margin: 2px 8px;
    }

    QListWidget#Navigation::item:selected {
        background: #ffffff;
        color: #0b315f;
        border-left: 4px solid #5e91c7;
        font-weight: 700;
    }

    QListWidget#Navigation::item:hover {
        background: rgba(255, 255, 255, 42);
        color: #ffffff;
    }

    QPushButton {
        background: #ffffff;
        color: #173b63;
        border: 1px solid #b9cadd;
        border-radius: 8px;
        padding: 9px 14px;
        min-height: 24px;
    }

    QPushButton:hover {
        background: #f3f8fd;
        border-color: #7da7d1;
    }

    QPushButton:pressed {
        background: #e3edf7;
    }

    QPushButton#PrimaryButton {
        background: #0d4f8b;
        color: #ffffff;
        border-color: #0d4f8b;
        font-weight: 700;
    }

    QPushButton#PrimaryButton:hover {
        background: #0b3f75;
        border-color: #0b3f75;
    }

    QPushButton#DangerButton {
        background: #b4472a;
        color: #ffffff;
        border-color: #b4472a;
    }

    QLineEdit, QSpinBox, QComboBox {
        background: #ffffff;
        color: #18324f;
        border: 1px solid #b8c9da;
        border-radius: 8px;
        padding: 6px 8px;
        min-height: 20px;
    }

    QLineEdit:focus, QSpinBox:focus, QComboBox:focus {
        border-color: #0d4f8b;
    }

    QComboBox::drop-down {
        border: 0;
        width: 24px;
    }

    QCheckBox {
        color: #344d66;
        spacing: 8px;
        font-weight: 700;
    }

    QCheckBox::indicator {
        width: 16px;
        height: 16px;
        border: 1px solid #8caac4;
        background: #ffffff;
        border-radius: 5px;
    }

    QCheckBox::indicator:checked {
        background: #0d4f8b;
        border-color: #0d4f8b;
    }

    QTableWidget {
        background: #ffffff;
        alternate-background-color: #f6f9fc;
        color: #18324f;
        gridline-color: #e0e9f2;
        border: 1px solid #d4e0ec;
        border-radius: 8px;
        selection-background-color: #d7e8f7;
        selection-color: #0b315f;
    }

    QTableWidget#TimelineTable {
        border: 0;
        border-top: 1px solid #dbe8f3;
        border-radius: 0;
    }

    QHeaderView::section {
        background: #e7f0f8;
        color: #385a79;
        border: 0;
        border-right: 1px solid #d4e0ec;
        border-bottom: 1px solid #d4e0ec;
        padding: 7px 8px;
        font-weight: 700;
    }

    QTextEdit {
        background: #ffffff;
        color: #18324f;
        border: 1px solid #d4e0ec;
        border-radius: 8px;
        padding: 8px;
    }

    QScrollBar:vertical, QScrollBar:horizontal {
        background: #eef3f8;
        border: 0;
        width: 12px;
        height: 12px;
    }

    QScrollBar::handle {
        background: #a7bbce;
        border-radius: 5px;
        min-height: 28px;
    }

    QScrollBar::handle:hover {
        background: #789bbd;
    }

    QScrollBar::add-line, QScrollBar::sub-line {
        width: 0;
        height: 0;
    }
    """


def _dark_stylesheet() -> str:
    """深色主题：与 Android 端 darkColorScheme 对齐的深海军蓝 + 浅蓝主色。

    设计原则：
    - 背景层次（深→浅）：主背景 0d1117 → 面板 161b22 → 卡片 1c2330
    - 主色用浅蓝 #6FA8DC（与 Android dark primary 一致）保证深底可读
    - 侧边栏深海军蓝渐变，与浅色版同源但更暗
    - 所有"软色块"（success/warn/idle）用深色变体，避免刺眼亮块
    """
    return """
    * {
        font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
        font-size: 13px;
    }

    QMainWindow, QWidget#Root {
        background: #0d1117;
        color: #e3ebf3;
    }

    QWidget#Sidebar {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #061a2e, stop:1 #0a2540);
        border-right: 1px solid #1f2933;
    }

    QLabel#SidebarLogo {
        background: transparent;
    }

    QLabel#Brand {
        color: #ffffff;
        font-size: 22px;
        font-weight: 800;
    }

    QLabel#BrandNote {
        color: #8fb4d8;
        font-size: 12px;
        font-weight: 600;
    }

    QLabel#Subtle {
        color: #8b9bb0;
    }

    QLabel#PageTitle {
        color: #d8e4f0;
        font-size: 24px;
        font-weight: 800;
    }

    QLabel#MetricValue {
        color: #d8e4f0;
        font-size: 20px;
        font-weight: 800;
    }

    QLabel#MetricLabel {
        color: #8b9bb0;
        font-size: 12px;
    }

    QLabel#StatusGood {
        color: #6fcf97;
        font-weight: 700;
    }

    QLabel#StatusWarn {
        color: #ff8a80;
        font-weight: 700;
    }

    QFrame#Hero {
        background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #161b22, stop:0.55 #131a26, stop:1 #0f1a2c);
        border: 1px solid #2a3441;
        border-radius: 8px;
    }

    QLabel#HeroTitle {
        color: #d8e4f0;
        font-size: 28px;
        font-weight: 900;
    }

    QLabel#HeroCaption {
        color: #8b9bb0;
        font-size: 13px;
    }

    QLabel#HeroStatusGood, QLabel#HeroStatusWarn, QLabel#HeroStatusIdle {
        border-radius: 12px;
        padding: 5px 12px;
        font-weight: 800;
        font-size: 13px;
    }

    QLabel#HeroStatusGood {
        background: #1e3a32;
        color: #6fcf97;
        border: 1px solid #2a4a3a;
    }

    QLabel#HeroStatusWarn {
        background: #3a2020;
        color: #ff8a80;
        border: 1px solid #4a2a2a;
    }

    QLabel#HeroStatusIdle {
        background: #1e2a3a;
        color: #7fb5e8;
        border: 1px solid #2a3a4a;
    }

    QLabel#HeroLogo {
        background: transparent;
    }

    QFrame#IdentityPanel, QFrame#GuardianPanel, QFrame#ActionDock, QFrame#TimelinePanel {
        background: #161b22;
        border: 1px solid #2a3441;
        border-radius: 8px;
    }

    QFrame#IdentityPanel {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #161b22, stop:1 #131a24);
    }

    QLabel#IdentityLogo, QLabel#DialLogo {
        background: transparent;
    }

    QLabel#IdentityTitle {
        color: #d8e4f0;
        font-size: 24px;
        font-weight: 900;
    }

    QLabel#IdentitySubtitle {
        color: #8b9bb0;
        font-size: 12px;
        font-weight: 700;
    }

    QFrame#GuardianPanel {
        background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #161b22, stop:0.55 #131a26, stop:1 #0f1a2c);
    }

    QLabel#HeroEyebrow {
        color: #7fb5e8;
        font-size: 13px;
        font-weight: 800;
    }

    QLabel#GuardTitle {
        color: #6fa8dc;
        font-size: 34px;
        font-weight: 900;
    }

    QFrame#GuardianDial {
        background: qradialgradient(cx:0.5, cy:0.5, radius:0.72, fx:0.5, fy:0.5, stop:0 #1c2530, stop:0.62 #16202c, stop:0.64 #0d4f8b, stop:0.8 #131a26, stop:1 #061a2e);
        border: 2px solid #0d4f8b;
        border-radius: 56px;
    }

    QLabel#DialLabel {
        color: #d8e4f0;
        font-size: 13px;
        font-weight: 900;
    }

    QFrame#SignalPanel {
        background: transparent;
        border: 0;
    }

    QFrame#SignalTile {
        background: rgba(28, 35, 48, 218);
        border: 1px solid #2a3441;
        border-radius: 8px;
    }

    QLabel#SignalLabel {
        color: #8b9bb0;
        font-size: 12px;
        font-weight: 700;
    }

    QLabel#SignalValue {
        color: #d8e4f0;
        font-size: 18px;
        font-weight: 900;
    }

    QLabel#DockTitle, QLabel#SectionTitle {
        color: #d8e4f0;
        font-size: 17px;
        font-weight: 900;
    }

    QLabel#SectionHint {
        color: #8b9bb0;
        font-size: 12px;
    }

    QLabel#InlineHint {
        color: #7a8ba0;
        font-size: 12px;
        font-weight: 600;
    }

    QFrame#EmptyStateCard {
        background: #131a24;
        border: 1px dashed #3a4a5a;
        border-radius: 8px;
    }

    QLabel#EmptyStateText {
        color: #8b9bb0;
        font-size: 15px;
        font-weight: 700;
    }

    QLabel#EmptyWatermark {
        background: transparent;
    }

    QLabel#NumberBadge {
        color: #6fa8dc;
        background: #1e2a3a;
        border: 1px solid #2a3a4a;
        border-radius: 11px;
        padding: 2px 10px;
        font-size: 15px;
        font-weight: 900;
    }

    QLabel#ToastGood, QLabel#ToastWarn {
        border-radius: 8px;
        padding: 8px 12px;
        font-weight: 700;
    }

    QLabel#ToastGood {
        color: #6fcf97;
        background: #1e3a32;
        border: 1px solid #2a4a3a;
    }

    QLabel#ToastWarn {
        color: #ff8a80;
        background: #3a2020;
        border: 1px solid #4a2a2a;
    }

    QFrame#GuidePanel {
        background: #131a24;
        border: 1px solid #2a3441;
        border-radius: 8px;
    }

    QLabel#WizardStep {
        color: #d8e4f0;
        background: #1c2530;
        border: 1px solid #2a3441;
        border-radius: 8px;
        padding: 8px 10px;
        font-weight: 800;
    }

    QPushButton#TutorialJump {
        background: #1c2530;
        color: #6fa8dc;
        border: 1px solid #2a3a4a;
        border-radius: 8px;
        font-weight: 800;
        padding: 10px 12px;
    }

    QPushButton#TutorialJump:hover {
        background: #243040;
        border-color: #4a7a9a;
    }

    QFrame#Panel {
        background: #161b22;
        border: 1px solid #2a3441;
        border-radius: 8px;
    }

    QLabel#Subtle { color: #9fb0c0; }
    QLabel#PageTitle { color: #e3ebf9; font-size: 24px; font-weight: 800; }
    QLabel#MetricValue { color: #e3ebf9; font-size: 20px; font-weight: 800; }
    QLabel#MetricLabel { color: #93a5ba; font-size: 12px; }
    QLabel#StatusGood { color: #8fd6a2; font-weight: 700; }
    QLabel#StatusWarn { color: #f0a35e; font-weight: 700; }

    QLabel { color: #e3ebf9; }

    QFrame#Metric {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1, stop:0 #1c2330, stop:1 #161b22);
        border: 1px solid #2a3441;
        border-radius: 8px;
    }

    QListWidget#Navigation {
        background: transparent;
        border: 0;
        outline: 0;
        color: #8fb4d8;
    }

    QListWidget#Navigation::item {
        min-height: 38px;
        padding: 8px 12px;
        border-radius: 8px;
        margin: 2px 8px;
    }

    QListWidget#Navigation::item:selected {
        background: #1c2530;
        color: #6fa8dc;
        border-left: 4px solid #6fa8dc;
        font-weight: 700;
    }

    QListWidget#Navigation::item:hover {
        background: rgba(255, 255, 255, 24);
        color: #d8e4f0;
    }

    QPushButton {
        background: #1c2530;
        color: #d8e4f0;
        border: 1px solid #3a4a5a;
        border-radius: 8px;
        padding: 9px 14px;
        min-height: 24px;
    }

    QPushButton:hover {
        background: #243040;
        border-color: #5a7a9a;
    }

    QPushButton:pressed {
        background: #2a3848;
    }

    QPushButton#PrimaryButton {
        background: #0d4f8b;
        color: #ffffff;
        border-color: #0d4f8b;
        font-weight: 700;
    }

    QPushButton#PrimaryButton:hover {
        background: #0b3f75;
        border-color: #0b3f75;
    }

    QPushButton#DangerButton {
        background: #b4472a;
        color: #ffffff;
        border-color: #b4472a;
    }

    QLineEdit, QSpinBox, QComboBox {
        background: #0d1117;
        color: #e3ebf3;
        border: 1px solid #3a4a5a;
        border-radius: 8px;
        padding: 6px 8px;
        min-height: 20px;
    }

    QLineEdit:focus, QSpinBox:focus, QComboBox:focus {
        border-color: #4a9eff;
    }

    QComboBox::drop-down {
        border: 0;
        width: 24px;
    }

    QComboBox QAbstractItemView {
        background: #161b22;
        color: #e3ebf3;
        selection-background-color: #0d4f8b;
        outline: 0;
    }

    QCheckBox {
        color: #b0c0d0;
        spacing: 8px;
        font-weight: 700;
    }

    QCheckBox::indicator {
        width: 16px;
        height: 16px;
        border: 1px solid #5a6a7a;
        background: #0d1117;
        border-radius: 5px;
    }

    QCheckBox::indicator:checked {
        background: #4a9eff;
        border-color: #4a9eff;
    }

    QTableWidget {
        background: #161b22;
        alternate-background-color: #131a24;
        color: #e3ebf3;
        gridline-color: #2a3441;
        border: 1px solid #2a3441;
        border-radius: 8px;
        selection-background-color: #1e3a5a;
        selection-color: #d8e4f0;
    }

    QTableWidget#TimelineTable {
        border: 0;
        border-top: 1px solid #2a3441;
        border-radius: 0;
    }

    QHeaderView::section {
        background: #1c2530;
        color: #8b9bb0;
        border: 0;
        border-right: 1px solid #2a3441;
        border-bottom: 1px solid #2a3441;
        padding: 7px 8px;
        font-weight: 700;
    }

    QTextEdit {
        background: #161b22;
        color: #e3ebf3;
        border: 1px solid #2a3441;
        border-radius: 8px;
        padding: 8px;
    }

    QScrollBar:vertical, QScrollBar:horizontal {
        background: #0d1117;
        border: 0;
        width: 12px;
        height: 12px;
    }

    QScrollBar::handle {
        background: #3a4a5a;
        border-radius: 5px;
        min-height: 28px;
    }

    QScrollBar::handle:hover {
        background: #5a6a7a;
    }

    QScrollBar::add-line, QScrollBar::sub-line {
        width: 0;
        height: 0;
    }
    """


def stylesheet(theme_mode: str = "light") -> str:
    """根据主题模式返回对应样式表。

    theme_mode:
        "light"  - 强制浅色
        "dark"   - 强制深色
        "system" - 跟随系统（Qt 6.5+ 读取系统色彩方案，否则回退浅色）
    """
    resolved = resolve_theme(theme_mode)
    if resolved == "dark":
        return _dark_stylesheet() + _DARK_EXTRAS
    return _light_stylesheet() + _LIGHT_EXTRAS


def resolve_theme(theme_mode: str) -> str:
    """把 "system" 解析为 "light" 或 "dark"。"""
    if theme_mode == "dark":
        return "dark"
    if theme_mode == "light":
        return "light"
    # system：跟随系统色彩方案
    try:
        from PySide6.QtGui import QGuiApplication

        hints = QGuiApplication.styleHints()
        # Qt 6.5+ 才有 colorScheme
        scheme = getattr(hints, "colorScheme", None)
        if scheme is not None:
            # Qt.ColorScheme.Dark
            return "dark" if str(scheme).endswith("Dark") else "light"
    except Exception:
        pass
    return "light"


# ---- 扩展调色板（单一色彩来源）----------------------------------------------
# 状态徽标、分组行、空态文字、课件状态、教程 HTML 的浅/深两套色值。
# app.py 一律从 palette() 取值，禁止散落硬编码 hex——
# 深色模式下亮色残留的根源就是双份手工维护漂移。
PALETTES: dict[str, dict[str, str]] = {
    "light": {
        "status_unsigned_fg": "#8a1c1c",
        "status_unsigned_bg": "#fde8e8",
        "status_unknown_fg": "#8a5a00",
        "status_unknown_bg": "#fff4ce",
        "status_signed_fg": "#166534",
        "status_signed_bg": "#e7f6ec",
        "status_none_fg": "#5f6b7a",
        "status_none_bg": "#eef2f6",
        "group_row_bg": "#e9eff5",
        "group_row_fg": "#334155",
        "empty_text": "#687586",
        "cw_ready": "#0b3a6d",
        "cw_downloading": "#a15c00",
        "cw_success": "#087443",
        "cw_failed": "#b42318",
        "warn_accent": "#c62828",
        "tutorial_text": "#0b315f",
        "tutorial_nav_bg": "#eef5fc",
        "tutorial_nav_border": "#c8ddec",
    },
    "dark": {
        "status_unsigned_fg": "#f2a9a4",
        "status_unsigned_bg": "#3d2427",
        "status_unknown_fg": "#e3b45f",
        "status_unknown_bg": "#3b3120",
        "status_signed_fg": "#8fd6a2",
        "status_signed_bg": "#1d3527",
        "status_none_fg": "#9fb0c0",
        "status_none_bg": "#232a32",
        "group_row_bg": "#202a36",
        "group_row_fg": "#c9d6e6",
        "empty_text": "#93a5ba",
        "cw_ready": "#82b8ea",
        "cw_downloading": "#dfae55",
        "cw_success": "#79cf92",
        "cw_failed": "#f08a80",
        "warn_accent": "#ff8a80",
        "tutorial_text": "#cfe0f2",
        "tutorial_nav_bg": "#1d2733",
        "tutorial_nav_border": "#33414f",
    },
}


def palette(theme_mode: str = "system") -> dict[str, str]:
    """按主题模式返回扩展调色板（浅/深两套）。"""
    return PALETTES[resolve_theme(theme_mode)]


def dark_qpalette():
    """深色原生控件调色板：覆盖 QSS 管不到的原生对话框
    （QMessageBox / QFileDialog / QToolTip / 输入法候选等）。

    需在 QApplication 创建后使用；返回 QPalette 实例。
    """
    from PySide6.QtGui import QColor, QPalette

    p = QPalette()
    c = QColor
    p.setColor(QPalette.ColorRole.Window, c("#0d1117"))
    p.setColor(QPalette.ColorRole.WindowText, c("#e3ebf9"))
    p.setColor(QPalette.ColorRole.Base, c("#161b22"))
    p.setColor(QPalette.ColorRole.AlternateBase, c("#1c2530"))
    p.setColor(QPalette.ColorRole.ToolTipBase, c("#161b22"))
    p.setColor(QPalette.ColorRole.ToolTipText, c("#e3ebf9"))
    p.setColor(QPalette.ColorRole.Text, c("#e3ebf9"))
    p.setColor(QPalette.ColorRole.PlaceholderText, c("#6b7d8f"))
    p.setColor(QPalette.ColorRole.Button, c("#1c2530"))
    p.setColor(QPalette.ColorRole.ButtonText, c("#e3ebf9"))
    p.setColor(QPalette.ColorRole.BrightText, c("#ffffff"))
    p.setColor(QPalette.ColorRole.Link, c("#6fa8dc"))
    p.setColor(QPalette.ColorRole.Highlight, c("#0d4f8b"))
    p.setColor(QPalette.ColorRole.HighlightedText, c("#ffffff"))
    p.setColor(QPalette.ColorGroup.Disabled, QPalette.ColorRole.Text, c("#6b7d8f"))
    p.setColor(QPalette.ColorGroup.Disabled, QPalette.ColorRole.ButtonText, c("#6b7d8f"))
    p.setColor(QPalette.ColorGroup.Disabled, QPalette.ColorRole.WindowText, c("#6b7d8f"))
    return p


# 浅色补充：下拉弹出列表此前缺失（与深色不对称）
_LIGHT_EXTRAS = """
QComboBox QAbstractItemView {
    background-color: #ffffff;
    color: #17312e;
    border: 1px solid #c8ddec;
    outline: 0;
}
QComboBox QAbstractItemView::item { min-height: 26px; }
QComboBox QAbstractItemView::item:selected {
    background-color: #0d4f8b;
    color: #ffffff;
}
"""

# 深色补充：原生弹层（tooltip/菜单/消息框）此前不跟随暗色
_DARK_EXTRAS = """
QToolTip { background-color: #161b22; color: #e3ebf9; border: 1px solid #33414f; padding: 4px; }
QMenu { background-color: #161b22; color: #e3ebf9; border: 1px solid #2a3644; }
QMenu::item:selected { background-color: #0d4f8b; }
QMessageBox, QDialog { background-color: #0d1117; color: #e3ebf9; }
QMessageBox QLabel { color: #e3ebf9; }
"""
