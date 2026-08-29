"""托盘/通知页纯逻辑补测（E3，续 maintenance/startup 范式）。

鸭子类型 stub 宿主驱动 Mixin 方法内的判定逻辑，不构造任何 Qt 控件对象：
- TrayMixin._refresh_tray_menu 的 未登录/正在监控/已暂停 三态文案与启停按钮文案；
- TrayMixin._handle_tray_activated 的单击/双击才唤起窗口；
- TrayMixin._toggle_monitor_from_tray 的按 worker 存活分流；
- TrayMixin.closeEvent 的托盘常驻路径（ignore + hide + 提示，不退出）；
- NotificationsPageMixin._notification_settings_from_ui 的全量收集与 QQ 端口缺省回填；
- NotificationsPageMixin._refresh_notification_metric 的已配置通道聚合（本机/微信/QQ）。
"""

from __future__ import annotations

import unittest

from PySide6.QtWidgets import QSystemTrayIcon

from xmu_rollcall.desktop_qt.notifications_page import NotificationsPageMixin
from xmu_rollcall.desktop_qt.tray import TrayMixin


class StubAction:
    def __init__(self) -> None:
        self.texts: list[str] = []

    def setText(self, value: str) -> None:
        self.texts.append(value)


class StubWorker:
    def __init__(self, alive: bool) -> None:
        self._alive = alive

    def is_alive(self) -> bool:
        return self._alive


class StubStopEvent:
    def __init__(self, set_: bool) -> None:
        self._set = set_

    def is_set(self) -> bool:
        return self._set


class StubLabel:
    def __init__(self, text: str = "") -> None:
        self._text = text

    def text(self) -> str:
        return self._text


class StubCheck:
    def __init__(self, checked: bool) -> None:
        self._checked = checked

    def isChecked(self) -> bool:
        return self._checked


class StubField:
    def __init__(self, value: str) -> None:
        self._value = value

    def text(self) -> str:
        return self._value


class StubCloseEvent:
    def __init__(self) -> None:
        self.ignored = False

    def ignore(self) -> None:
        self.ignored = True


class TrayHost(TrayMixin):
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)
        self.started = False
        self.stopped = False
        self.shown = False
        self.hidden = False

    def start_monitor(self):
        self.started = True

    def stop_monitor(self):
        self.stopped = True

    def showNormal(self):
        self.shown = True

    def raise_(self):
        pass

    def activateWindow(self):
        pass

    def hide(self):
        self.hidden = True


def new_tray_host(**overrides) -> TrayHost:
    base = dict(
        tray_status_action=StubAction(),
        tray_last_check_action=StubAction(),
        tray_toggle_monitor_action=StubAction(),
        monitor_worker=None,
        monitor_stop_event=None,
        account=None,
        metric_last_check=StubLabel("12:00"),
    )
    base.update(overrides)
    return TrayHost(**base)


class TestTrayMenuLogic(unittest.TestCase):
    def test_logged_out_without_account_shows_not_logged_in(self):
        host = new_tray_host(account=None)
        host._refresh_tray_menu()
        self.assertIn("状态：未登录", host.tray_status_action.texts)
        self.assertEqual(host.tray_toggle_monitor_action.texts, ["启动监控"])

    def test_live_worker_with_account_shows_monitoring(self):
        host = new_tray_host(
            account={"id": "u1"},
            monitor_worker=StubWorker(alive=True),
            monitor_stop_event=StubStopEvent(set_=False),
        )
        host._refresh_tray_menu()
        self.assertIn("状态：正在监控", host.tray_status_action.texts)
        self.assertEqual(host.tray_toggle_monitor_action.texts, ["暂停监控"])

    def test_stopping_worker_shows_paused_and_restart_label(self):
        # 停止中（stop_event 已置位）即使线程尚未退出也按「已暂停」呈现
        host = new_tray_host(
            account={"id": "u1"},
            monitor_worker=StubWorker(alive=True),
            monitor_stop_event=StubStopEvent(set_=True),
        )
        host._refresh_tray_menu()
        self.assertIn("状态：已暂停", host.tray_status_action.texts)
        self.assertEqual(host.tray_toggle_monitor_action.texts, ["启动监控"])

    def test_single_click_or_double_click_shows_window(self):
        host = new_tray_host()
        host._handle_tray_activated(QSystemTrayIcon.ActivationReason.Trigger)
        self.assertTrue(host.shown)
        other = new_tray_host()
        other._handle_tray_activated(QSystemTrayIcon.ActivationReason.Context)
        self.assertFalse(other.shown)

    def test_toggle_monitor_routes_by_running_predicate(self):
        # 分流与菜单显示谓词一致（is_running）：存活且未请求停止 → 暂停
        running = new_tray_host(
            monitor_worker=StubWorker(alive=True),
            monitor_stop_event=StubStopEvent(set_=False),
        )
        running._toggle_monitor_from_tray()
        self.assertTrue(running.stopped and not running.started)
        idle = new_tray_host(monitor_worker=StubWorker(alive=False))
        idle._toggle_monitor_from_tray()
        self.assertTrue(idle.started and not idle.stopped)

    def test_toggle_monitor_during_stop_window_routes_to_start(self):
        # 停止中（已请求停止未退出）：菜单显示「启动监控」，动作也必须走启动
        # （旧裸 is_alive 分流会再执行 stop，取消排队的自动重启——本轮修复）
        stopping = new_tray_host(
            monitor_worker=StubWorker(alive=True),
            monitor_stop_event=StubStopEvent(set_=True),
        )
        stopping._toggle_monitor_from_tray()
        self.assertTrue(stopping.started and not stopping.stopped)

    def test_close_event_hides_to_tray_instead_of_quitting(self):
        class StubTrayIcon:
            messages = []

            def showMessage(self, title, body):
                self.messages.append((title, body))

        tray_icon = StubTrayIcon()
        host = TrayHost(tray_icon=tray_icon, quitting=False)
        event = StubCloseEvent()
        host.closeEvent(event)
        self.assertTrue(event.ignored)
        self.assertTrue(host.hidden)
        self.assertEqual(tray_icon.messages, [("xmu助手", "已在后台常驻，监控会继续运行。")])


class NotificationsHost(NotificationsPageMixin):
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)
        self.badge_updates = 0

    def _update_nav_badges(self):
        self.badge_updates += 1


class TestNotificationsPageLogic(unittest.TestCase):
    def _settings_host(self, port_text: str = "465"):
        return NotificationsHost(
            notify_system_check=StubCheck(True),
            notify_pushplus_check=StubCheck(True),
            notify_pushplus_token=StubField("tok"),
            notify_qq_check=StubCheck(False),
            notify_qq_sender=StubField("s@a.com"),
            notify_qq_password=StubField("pw"),
            notify_qq_recipient=StubField("r@b.com"),
            notify_qq_host=StubField("smtp.qq.com"),
            notify_qq_port=StubField(port_text),
        )

    def test_settings_from_ui_collects_all_channels(self):
        settings = self._settings_host()._notification_settings_from_ui()
        self.assertEqual(
            settings,
            {
                "system": {"enabled": True},
                "pushplus": {"enabled": True, "token": "tok"},
                "qq_mail": {
                    "enabled": False,
                    "sender": "s@a.com",
                    "password": "pw",
                    "recipient": "r@b.com",
                    "smtp_host": "smtp.qq.com",
                    "smtp_port": "465",
                },
            },
        )

    def test_blank_qq_port_falls_back_to_default(self):
        settings = self._settings_host(port_text="   ")._notification_settings_from_ui()
        self.assertEqual(settings["qq_mail"]["smtp_port"], "465,587")

    def test_metric_aggregates_configured_channels(self):
        host = NotificationsHost(
            notify_system_status=StubAction(),
            notify_pushplus_status=StubAction(),
            notify_qq_status=StubAction(),
            metric_notifications=StubAction(),
        )
        host._refresh_notification_metric(
            {
                "system": {"enabled": True},
                "pushplus": {"enabled": True, "token": "tok"},
                "qq_mail": {"enabled": False},
            }
        )
        self.assertEqual(host.metric_notifications.texts, ["本机 / 微信"])
        self.assertEqual(host.notify_qq_status.texts, ["未开启"])
        self.assertEqual(host.badge_updates, 1)

    def test_metric_shows_disabled_when_nothing_configured(self):
        host = NotificationsHost(
            notify_system_status=StubAction(),
            notify_pushplus_status=StubAction(),
            notify_qq_status=StubAction(),
            metric_notifications=StubAction(),
        )
        host._refresh_notification_metric(
            {"system": {"enabled": False}, "pushplus": {"enabled": False}, "qq_mail": {"enabled": False}}
        )
        self.assertEqual(host.metric_notifications.texts, ["未开启"])
