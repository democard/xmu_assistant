"""登出后在途监控事件的丢弃守卫（本轮体检 P1）。

MonitorWorker 的事件（poll/rollcall/monitor_status）不携带账号/代数信息；
登出时 stop_monitor 打断 worker，但已入队的 in-flight 事件仍会送达 GUI：
- poll 晚到会把登出清理面刚复位的 metric_last_check/metric_rollcall_count
  写回旧账号数值；
- rollcall 晚到会灌回已清空的今日签到表并经此触发通知；
- monitor_status 晚到的「已停止」会覆盖「未启动」。

以 SimpleNamespace 作宿主直调 DashboardWindow 方法（与 test_login_gate 同范式），
不构造任何 GUI 对象。完整方案（worker 事件签名扩账号字段）在案待拍板，本守卫
先堵登出场景。
"""

from __future__ import annotations

import threading
import types
import unittest

from xmu_rollcall.desktop_qt.app import DashboardWindow


class MonitorEventLogoutGuardTest(unittest.TestCase):
    def _host(self):
        host = types.SimpleNamespace(
            session=None,
            account=None,
            logs=[],
            metric_texts={},
        )
        host.log = host.logs.append

        def make_metric(name):
            metric = types.SimpleNamespace(texts=[])
            metric.setText = metric.texts.append
            host.metric_texts[name] = metric
            return metric

        host.metric_monitor = make_metric("monitor")
        host.metric_last_check = make_metric("last_check")
        host.metric_rollcall_count = make_metric("rollcall_count")
        host._reset_background_error_state = lambda: None
        host._refresh_tray_menu = lambda: None
        host._add_rollcall_event = lambda record: host.logs.append(f"added:{record}")
        return host

    def test_late_monitor_status_discarded_after_logout(self):
        host = self._host()
        DashboardWindow._ev_monitor_status(host, ("monitor_status", "已停止"))
        self.assertEqual(host.metric_texts["monitor"].texts, [])
        self.assertTrue(any("忽略登出后迟到的监控状态" in m for m in host.logs))

    def test_late_poll_discarded_after_logout(self):
        host = self._host()
        DashboardWindow._ev_poll(host, ("poll", "x", 1750000000.0, 5))
        self.assertEqual(host.metric_texts["last_check"].texts, [])
        self.assertEqual(host.metric_texts["rollcall_count"].texts, [])
        self.assertTrue(any("忽略登出后迟到的轮询结果" in m for m in host.logs))

    def test_late_rollcall_discarded_after_logout(self):
        host = self._host()
        DashboardWindow._ev_rollcall(host, ("rollcall", {"id": "r1"}))
        self.assertFalse(any(str(m).startswith("added:") for m in host.logs))
        self.assertTrue(any("忽略登出后迟到的签到事件" in m for m in host.logs))

    def test_poll_still_lands_when_logged_in(self):
        host = self._host()
        host.session = object()
        host.account = {"id": 1}
        DashboardWindow._ev_poll(host, ("poll", "x", 1750000000.0, 5))
        self.assertEqual(host.metric_texts["rollcall_count"].texts, ["5"])
        self.assertEqual(len(host.metric_texts["last_check"].texts), 1)


class MonitorStatusPauseMappingTest(unittest.TestCase):
    """主动暂停后 worker 收尾的「已停止」不得覆盖「已暂停」（指标行与按
    is_running 计算的守护徽章/托盘长期不一致）；异常终态保持「已停止」。"""

    def _host(self, stop_event):
        host = types.SimpleNamespace(
            session=object(),
            account={"id": 1},
            monitor_stop_event=stop_event,
            logs=[],
        )
        host.log = host.logs.append
        host.metric_monitor = types.SimpleNamespace(texts=[])
        host.metric_monitor.setText = host.metric_monitor.texts.append
        return host

    def test_worker_exit_after_manual_pause_shows_paused(self):
        stop_event = threading.Event()
        stop_event.set()
        host = self._host(stop_event)
        DashboardWindow._ev_monitor_status(host, ("monitor_status", "已停止"))
        self.assertEqual(host.metric_monitor.texts, ["已暂停"])

    def test_abnormal_exit_keeps_stopped_text(self):
        host = self._host(threading.Event())  # stop_event 未设 = 非用户主动停止
        DashboardWindow._ev_monitor_status(host, ("monitor_status", "已停止"))
        self.assertEqual(host.metric_monitor.texts, ["已停止"])

    def test_other_status_texts_pass_through(self):
        host = self._host(threading.Event())
        DashboardWindow._ev_monitor_status(host, ("monitor_status", "已启动"))
        self.assertEqual(host.metric_monitor.texts, ["已启动"])


class DownloadDoneLogoutGuardTest(unittest.TestCase):
    """登出后晚到的下载批次结果：旗标复位保留，展示面（摘要/弹窗）丢弃。"""

    def _host(self, session):
        class Host:
            pass

        h = Host()
        h.session = session
        h.logs = []
        h.log = h.logs.append
        h.courseware_download_in_progress = True
        h.courseware_download_status = {}
        h.courseware_summary = types.SimpleNamespace(texts=[])
        h.courseware_summary.setText = h.courseware_summary.texts.append
        h._refresh_courseware_table = lambda: None
        h._update_nav_badges = lambda: None
        h._show_toast = lambda *a, **k: h.logs.append("toast")
        h._show_courseware_download_result = lambda *a: h.logs.append("modal")
        return h

    def test_late_download_done_after_logout_keeps_flag_reset_only(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(None)
        DW._ev_courseware_download_done(
            host, ("courseware_download_done", ["a"], [], [], "D:/x"),
        )
        self.assertFalse(host.courseware_download_in_progress, "旗标复位必须无条件保留")
        self.assertEqual(host.courseware_summary.texts, [], "摘要不得被晚到批次改写")
        self.assertFalse(any(str(m) == "modal" for m in host.logs))

    def test_download_done_lands_when_logged_in(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(object())
        DW._ev_courseware_download_done(
            host, ("courseware_download_done", ["a"], [], [], "D:/x"),
        )
        self.assertFalse(host.courseware_download_in_progress)
        self.assertEqual(len(host.courseware_summary.texts), 1)
        self.assertTrue(any(str(m) == "modal" for m in host.logs))


class ErrorEventLogoutGuardTest(unittest.TestCase):
    """登出后晚到 error 不得触发紧急通知/第三方推送。"""

    def test_late_error_discarded_after_logout(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = types.SimpleNamespace(session=None, logs=[], recorded=[])
        host.log = host.logs.append
        host._record_background_error = lambda *a, **k: host.recorded.append(a)
        DW._ev_error(host, ("error", "轮询失败：登录已过期，请重新登录"))
        self.assertEqual(host.recorded, [])
        self.assertTrue(any("忽略登出后迟到的错误事件" in str(m) for m in host.logs))


class AnswerResultLogoutGuardTest(unittest.TestCase):
    """登出后在途应答 worker 的晚到结果不得改写指标行/触发通知。"""

    def test_late_answer_result_discarded_after_logout(self):
        from unittest import mock

        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = types.SimpleNamespace(session=None, logs=[])
        host.log = host.logs.append
        host.metric_last_result = types.SimpleNamespace(texts=[])
        host.metric_last_result.setText = host.metric_last_result.texts.append
        host._update_event_result = lambda *a: host.logs.append("updated")
        host._notify_rollcall = lambda *a: host.logs.append("notified")
        DW._ev_answer_result(host, ("answer_result", "r1", True, "提交成功"))
        self.assertEqual(host.metric_last_result.texts, [])
        self.assertFalse(any(str(m) == "updated" for m in host.logs))
        self.assertFalse(any(str(m) == "notified" for m in host.logs))
        self.assertTrue(any("忽略登出后迟到的应答结果" in str(m) for m in host.logs))

    def test_answer_result_lands_when_logged_in(self):
        from unittest import mock

        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = types.SimpleNamespace(session=object(), logs=[], events_by_id={})
        host.log = host.logs.append
        host.metric_last_result = types.SimpleNamespace(texts=[])
        host.metric_last_result.setText = host.metric_last_result.texts.append
        host._update_event_result = lambda *a: None
        host._notify_rollcall = lambda *a: None
        DW._ev_answer_result(host, ("answer_result", "r1", True, "提交成功"))
        self.assertEqual(host.metric_last_result.texts, ["提交成功"])


class AnswerSelectedEvictedRowGuardTest(unittest.TestCase):
    """选中行被 MAX_EVENT_ROWS 淘汰后再点「应答」：裸索引 KeyError，
    改 .get() 判空引导重新选择。"""

    def test_evicted_selected_row_shows_hint_instead_of_crash(self):
        from unittest import mock

        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = types.SimpleNamespace(
            events_by_id={},  # 选中 id 已被淘汰
            answer_calls=[],
        )
        host._selected_event_id = lambda: "r-gone"
        host._answer_event = lambda *a: host.answer_calls.append(a)
        with mock.patch("xmu_rollcall.desktop_qt.app.QMessageBox.information") as box:
            DW.answer_selected_rollcall(host)
        self.assertEqual(host.answer_calls, [])
        self.assertEqual(box.call_count, 1)

    def test_live_selected_row_answers_normally(self):
        from unittest import mock

        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        event = object()
        host = types.SimpleNamespace(events_by_id={"r1": event}, answer_calls=[])
        host._selected_event_id = lambda: "r1"
        host._answer_event = lambda *a: host.answer_calls.append(a)
        with mock.patch("xmu_rollcall.desktop_qt.app.QMessageBox.information"):
            DW.answer_selected_rollcall(host)
        self.assertEqual(host.answer_calls, [("r1", event)])


if __name__ == "__main__":
    unittest.main()
