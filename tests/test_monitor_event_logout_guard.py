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

    def test_old_worker_token_is_rejected_after_restart(self):
        host = self._host()
        host.session = object()
        host.account = {"id": 2}
        old_token = threading.Event()
        host.monitor_stop_event = threading.Event()

        DashboardWindow._ev_poll(host, ("poll", 1, 1750000000.0, 5, old_token))
        DashboardWindow._ev_rollcall(host, ("rollcall", {"id": "old"}, old_token))
        DashboardWindow._ev_monitor_status(host, ("monitor_status", "已停止", old_token))

        self.assertEqual(host.metric_texts["last_check"].texts, [])
        self.assertFalse(any(str(m).startswith("added:") for m in host.logs))
        self.assertEqual(host.metric_texts["monitor"].texts, [])

    def test_current_worker_token_still_lands(self):
        host = self._host()
        host.session = object()
        host.account = {"id": 2}
        token = threading.Event()
        host.monitor_stop_event = token

        DashboardWindow._ev_poll(host, ("poll", 1, 1750000000.0, 5, token))
        DashboardWindow._ev_rollcall(host, ("rollcall", {"id": "new"}, token))
        DashboardWindow._ev_monitor_status(host, ("monitor_status", "运行中", token))

        self.assertEqual(host.metric_texts["rollcall_count"].texts, ["5"])
        self.assertTrue(any(str(m).startswith("added:") for m in host.logs))
        self.assertEqual(host.metric_texts["monitor"].texts, ["运行中"])

    def test_cancelled_current_token_drops_data_events(self):
        host = self._host()
        host.session = object()
        host.account = {"id": 1}
        token = threading.Event()
        token.set()
        host.monitor_stop_event = token

        DashboardWindow._ev_poll(host, ("poll", 1, 1750000000.0, 5, token))
        DashboardWindow._ev_rollcall(host, ("rollcall", {"id": "late"}, token))

        self.assertEqual(host.metric_texts["last_check"].texts, [])
        self.assertFalse(any(str(m).startswith("added:") for m in host.logs))


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

    def test_late_running_status_after_manual_pause_is_discarded(self):
        stop_event = threading.Event()
        stop_event.set()
        host = self._host(stop_event)

        DashboardWindow._ev_monitor_status(
            host,
            ("monitor_status", "运行中", stop_event),
        )

        self.assertEqual(host.metric_monitor.texts, [])
        self.assertTrue(any("已取消监控任务迟到" in message for message in host.logs))

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

    def test_old_batch_done_after_account_switch_is_dropped_without_clearing_new_batch(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(object())
        host.courseware_download_batch_token = "new-batch"
        host.courseware_download_in_progress = True
        DW._ev_courseware_download_done(
            host, ("courseware_download_done", "old-batch", "A", ["a"], [], [], "D:/x", []),
        )
        self.assertTrue(host.courseware_download_in_progress)
        self.assertEqual(host.courseware_summary.texts, [])
        self.assertFalse(any(str(m) == "modal" for m in host.logs))


class DownloadProgressLogoutGuardTest(unittest.TestCase):
    """登出后晚到的下载进度：不得重建状态表/摘要/徽标。

    _courseware_download_worker 在每项循环开头、逐项登录校验**之前**发进度事件，
    登出后剩余各项仍会依次送入；item_done/done 已各有守卫，进度这处此前漏了。
    """

    def _host(self, session):
        host = types.SimpleNamespace(session=session, logs=[])
        host.log = host.logs.append
        host.courseware_download_status = {}
        host._refresh_courseware_table = lambda: host.logs.append("table")
        host._update_nav_badges = lambda: host.logs.append("badges")
        host.courseware_summary = types.SimpleNamespace(texts=[])
        host.courseware_summary.setText = host.courseware_summary.texts.append
        return host

    def test_late_progress_after_logout_is_dropped(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(None)
        DW._ev_courseware_download_progress(
            host, ("courseware_download_progress", 2, 5, "讲义.pdf", "k1"),
        )
        self.assertEqual(host.courseware_download_status, {})
        self.assertEqual(host.courseware_summary.texts, [])
        self.assertFalse(any(str(m) in ("table", "badges") for m in host.logs))

    def test_progress_lands_when_logged_in(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(object())
        DW._ev_courseware_download_progress(
            host, ("courseware_download_progress", 2, 5, "讲义.pdf", "k1"),
        )
        self.assertEqual(host.courseware_download_status, {"k1": "下载中"})
        self.assertEqual(len(host.courseware_summary.texts), 1)

    def test_old_batch_progress_after_account_switch_is_dropped(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(object())
        host.courseware_download_batch_token = "new-batch"
        DW._ev_courseware_download_progress(
            host, ("courseware_download_progress", "old-batch", "A", 2, 5, "讲义.pdf", "k1"),
        )
        self.assertEqual(host.courseware_download_status, {})


class DownloadLoginGenerationGuardTest(unittest.TestCase):
    def _host(self, account_id):
        host = types.SimpleNamespace(
            session=object(), account={"id": account_id, "username": account_id},
            _login_in_progress=True, _login_epoch=7, monitor_worker=None,
            _snapshot_account_id=account_id, logs=[], course_records=[],
            courseware_courses=[], courseware_items=[], courseware_download_status={"old": "下载中"},
            courseware_course_by_display={}, events_by_id={}, event_order=[],
            _auto_answer_inflight_rollcalls={}, _auto_answer_attempted_rollcalls=set(),
            course_refresh_in_progress=False, course_verify_in_progress=False,
            courseware_courses_refresh_in_progress=False, courseware_refresh_in_progress=False,
            courseware_download_in_progress=True, courseware_download_batch_token=object(),
            started_at=object(),
        )
        host.log = host.logs.append
        host.stop_monitor = lambda: None
        host._reset_background_error_state = lambda: None
        host._set_login_status = lambda *a, **k: None
        host._load_rollcall_settings = lambda *a, **k: None
        host._refresh_after_login = lambda: None
        host._refresh_event_tables = lambda: None
        host._refresh_course_table = lambda: None
        host._refresh_courseware_table = lambda: None
        host._show_toast = lambda *a, **k: None
        host._show_courseware_download_result = lambda *a, **k: None
        host._update_nav_badges = lambda: None
        host.merges = []
        host._merge_worker_session = lambda *a: host.merges.append(a)
        host.cancelled_answers = []
        host._cancel_all_pending_answers = lambda: host.cancelled_answers.append(True)
        host.courseware_summary = types.SimpleNamespace(setText=lambda *_: None)
        host.courseware_combo = types.SimpleNamespace(blockSignals=lambda *_: None, clear=lambda: None)
        for name in ("metric_last_result", "metric_rollcall_count", "metric_last_check", "metric_runtime"):
            setattr(host, name, types.SimpleNamespace(setText=lambda *_: None))
        host.metric_account = types.SimpleNamespace(setText=lambda *_: None)
        return host

    def _login_success(self, host, account_id):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        new_session = object()
        DW._ev_login_success(
            host,
            ("login_success", new_session, {"id": account_id, "username": account_id}, host._login_epoch),
        )
        return new_session

    def _assert_old_events_dropped(self, host, old_token, old_account):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        DW._ev_courseware_download_progress(
            host, ("courseware_download_progress", old_token, old_account, 1, 1, "a", "a")
        )
        DW._ev_courseware_download_item_done(
            host, ("courseware_download_item_done", old_token, old_account, "a", "成功")
        )
        DW._ev_courseware_download_done(
            host, ("courseware_download_done", old_token, old_account, ["a"], [], [], "D:/a", [])
        )
        DW._ev_merge_session_cookies(host, ("merge_session_cookies", object(), old_account, old_token))
        self.assertEqual(host.courseware_download_status, {})
        self.assertFalse(host.courseware_download_in_progress)
        self.assertEqual(host.merges, [])

    def test_real_login_success_invalidates_old_batch_for_account_switch_and_relogin(self):
        host = self._host("A")
        host.courseware_download_in_progress = True
        host.courseware_download_batch_token = object()
        old_token = host.courseware_download_batch_token
        old_session = host.session
        new_session = self._login_success(host, "B")
        self.assertIs(host.session, new_session)
        self.assertIsNot(host.session, old_session)
        self.assertIsNone(host.courseware_download_batch_token)
        self._assert_old_events_dropped(host, old_token, "A")

        host.courseware_download_batch_token = object()
        old_token = host.courseware_download_batch_token
        old_session = host.session
        new_session = self._login_success(host, "B")
        self.assertIs(host.session, new_session)
        self.assertIsNot(host.session, old_session)
        self.assertIsNone(host.courseware_download_batch_token)
        host.courseware_download_in_progress = True
        host.courseware_download_batch_token = object()
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW
        DW._ev_courseware_download_done(host, ("courseware_download_done", old_token, "B", ["a"], [], [], "D:/a", []))
        self.assertTrue(host.courseware_download_in_progress)

    def test_same_account_relogin_invalidates_real_old_batch(self):
        host = self._host("A")
        old_token = host.courseware_download_batch_token
        old_session = host.session
        new_session = self._login_success(host, "A")
        self.assertIs(host.session, new_session)
        self.assertIsNot(host.session, old_session)
        self.assertIsNone(host.courseware_download_batch_token)
        self._assert_old_events_dropped(host, old_token, "A")

    def test_same_account_relogin_rejects_old_answer_cookie_merge(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host("A")
        old_source_session = host.session
        new_session = self._login_success(host, "A")
        DW._ev_merge_session_cookies(
            host, ("merge_session_cookies", object(), "A", None, old_source_session)
        )
        self.assertIs(host.session, new_session)
        self.assertEqual(host.merges, [])
        self.assertEqual(host.cancelled_answers, [True])

        worker_session = object()
        DW._ev_merge_session_cookies(
            host, ("merge_session_cookies", worker_session, "A", None, new_session)
        )
        self.assertEqual(host.merges, [(worker_session, "A")])

    def test_current_batch_done_releases_lock(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host("B")
        token = object()
        host.courseware_download_batch_token = token
        DW._ev_courseware_download_done(host, ("courseware_download_done", token, "B", ["a"], [], [], "D:/a", []))
        self.assertFalse(host.courseware_download_in_progress)
        self.assertIsNone(host.courseware_download_batch_token)


class AnswerCookieGenerationGuardTest(unittest.TestCase):
    def test_answer_dispatch_captures_source_session_before_worker_starts(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        source_session = object()
        launched = []
        host = types.SimpleNamespace(
            session=source_session,
            account={"id": "A"},
            monitor_stop_event=None,
            _cancel_pending_answer=lambda *_: None,
            _update_event_result=lambda *_: None,
            _auto_answer_delay=lambda *_: 0,
            _answer_worker=lambda *_: None,
            _run_thread=lambda *args: launched.append(args),
        )
        event = types.SimpleNamespace(rollcall_type="数字签到")

        DW._answer_event(host, "event-1", event)

        self.assertEqual(len(launched), 1)
        self.assertIs(launched[0][5], source_session)
        self.assertEqual(launched[0][6], "A")

    def test_relogin_before_worker_submission_cancels_old_source(self):
        from unittest.mock import patch
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        old_source = object()
        emitted = []
        host = types.SimpleNamespace(session=object(), account={"id": "A"}, _emit=emitted.append)
        with patch("xmu_rollcall.desktop_qt.app.clone_session", return_value=object()):
            DW._answer_worker(host, "event-1", object(), 0, None, old_source, "A")

        self.assertTrue(any(item[0] == "answer_result" and "已取消" in item[3] for item in emitted))
        merge = next(item for item in emitted if item[0] == "merge_session_cookies")
        self.assertIs(merge[4], old_source)


class LateRefreshFlagGuardTest(unittest.TestCase):
    def test_old_account_results_do_not_unlock_current_refreshes(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        cases = (
            ("_ev_course_rollcalls", ("course_rollcalls", [], "manual", "A"), "course_refresh_in_progress"),
            ("_ev_course_rollcalls_error", ("course_rollcalls_error", "error", True, "A"), "course_refresh_in_progress"),
            ("_ev_course_records_verified", ("course_records_verified", [], "A", "manual"), "course_verify_in_progress"),
            ("_ev_course_records_verify_error", ("course_records_verify_error", "error", "A"), "course_verify_in_progress"),
            ("_ev_courseware_courses", ("courseware_courses", [], "manual", "A"), "courseware_courses_refresh_in_progress"),
            ("_ev_courseware_courses_error", ("courseware_courses_error", "error", True, "A"), "courseware_courses_refresh_in_progress"),
            ("_ev_courseware", ("courseware", [], "manual", "A"), "courseware_refresh_in_progress"),
            ("_ev_courseware_error", ("courseware_error", "error", True, "A"), "courseware_refresh_in_progress"),
        )
        for handler, event, flag in cases:
            with self.subTest(handler=handler):
                host = types.SimpleNamespace(account={"id": "B"}, logs=[])
                host.log = host.logs.append
                host._update_verify_button_state = lambda: None
                setattr(host, flag, True)

                getattr(DW, handler)(host, event)

                self.assertTrue(getattr(host, flag))


class MonitorRestartBudgetTest(unittest.TestCase):
    def test_restart_waits_for_longer_than_one_network_timeout(self):
        from unittest.mock import patch
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        checks = []
        launches = []
        worker = types.SimpleNamespace(is_alive=lambda: checks.append(True) or len(checks) <= 10)
        host = types.SimpleNamespace(
            account={"id": "A"}, session=object(), _monitor_restart_epoch=7,
            monitor_worker=worker, log=lambda *_: None,
            metric_monitor=types.SimpleNamespace(setText=lambda *_: None),
            _show_toast=lambda *args, **kwargs: None,
            start_monitor=lambda: launches.append(True),
        )
        host._restart_monitor_later = lambda attempts_left: DW._restart_monitor_later(host, attempts_left)
        host._continue_monitor_restart = lambda attempts_left, epoch: DW._continue_monitor_restart(
            host, attempts_left, epoch
        )
        with patch("xmu_rollcall.desktop_qt.app.QTimer.singleShot") as single_shot:
            single_shot.side_effect = lambda delay, callback: callback()
            DW._restart_monitor_later(host)

        self.assertEqual(launches, [True])
        self.assertEqual(len(checks), 11)
        self.assertTrue(all(call.args[0] == 2000 for call in single_shot.call_args_list))

    def test_exhausted_restart_is_visible(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        statuses = []
        toasts = []
        host = types.SimpleNamespace(
            log=lambda *_: None,
            metric_monitor=types.SimpleNamespace(setText=statuses.append),
            _show_toast=lambda *args, **kwargs: toasts.append((args, kwargs)),
        )

        DW._restart_monitor_later(host, attempts_left=0)

        self.assertEqual(statuses, ["重启失败"])
        self.assertTrue(toasts)
        self.assertFalse(toasts[0][1]["ok"])


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


class NotificationResultLogoutGuardTest(unittest.TestCase):
    """登出后在途通知 worker 的晚到结果不得改写通知页摘要/弹 Toast。"""

    def _host(self, session):
        host = types.SimpleNamespace(session=session, logs=[])
        host.log = host.logs.append
        host.notification_summary = types.SimpleNamespace(texts=[])
        host.notification_summary.setText = host.notification_summary.texts.append
        host._show_toast = lambda *a, **k: host.logs.append("toast")
        return host

    def test_late_notification_result_discarded_after_logout(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(None)
        DW._ev_notification_result(host, ("notification_result", True, "测试通知已发送"))
        self.assertEqual(host.notification_summary.texts, [])
        self.assertFalse(any(str(m) == "toast" for m in host.logs))
        self.assertTrue(any("忽略登出后迟到的通知结果" in str(m) for m in host.logs))

    def test_notification_result_lands_when_logged_in(self):
        from xmu_rollcall.desktop_qt.app import DashboardWindow as DW

        host = self._host(object())
        DW._ev_notification_result(host, ("notification_result", False, "SMTP 认证失败"))
        self.assertEqual(host.notification_summary.texts, ["SMTP 认证失败"])
        self.assertTrue(any(str(m) == "toast" for m in host.logs))


if __name__ == "__main__":
    unittest.main()
