"""Owned worker sessions are released; borrowed source sessions stay usable."""

import threading
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest
import requests

from xmu_rollcall import utils
from xmu_rollcall.desktop_qt import core, courses_page, courseware_page


def record(number=1):
    return core.CourseRollcallRecord(
        course_id="synthetic-course", course_title="Synthetic course",
        rollcall_id=str(number), rollcall_time="2026-01-01 12:00:00",
        rollcall_type="数字签到", signed_status="未知", platform_status="unknown",
    )


@pytest.mark.parametrize("operation", ["courses", "courseware", "rollcalls", "verify", "download"])
@pytest.mark.parametrize("fails", [False, True])
def test_page_worker_closes_owned_session_after_success_or_failure(operation, fails):
    with requests.Session() as source, ExitStack() as stack:
        worker = utils.clone_session(source)
        stack.callback(worker.close)  # Also release the old implementation's leaked session.
        worker.close = Mock(wraps=worker.close)
        source.close = Mock(wraps=source.close)
        worker.cookies.set("worker-cookie", "synthetic", domain="example.test")
        events = []
        host = SimpleNamespace(
            session=source, account={"id": "synthetic", "username": "synthetic-user"},
            _emit=events.append, _courseware_key=lambda item: "synthetic-item",
            _short_courseware_error=str, courseware_download_batch_token=None,
        )
        module = courses_page if operation in ("rollcalls", "verify") else courseware_page
        stack.enter_context(patch.object(module, "clone_session", return_value=worker))
        failure = RuntimeError("synthetic request failure") if fails else None

        if operation == "courses":
            stack.enter_context(patch.object(module, "fetch_courses", return_value=([], "fixture"), side_effect=failure))
            courseware_page.CoursewarePageMixin._courseware_courses_worker(host)
        elif operation == "courseware":
            stack.enter_context(patch.object(module, "fetch_courseware", return_value=[], side_effect=failure))
            courseware_page.CoursewarePageMixin._courseware_worker(host, SimpleNamespace(course_id="synthetic"))
        elif operation == "rollcalls":
            stack.enter_context(patch.object(module, "fetch_course_rollcall_records", return_value=([], "fixture"), side_effect=failure))
            stack.enter_context(patch.object(module, "verify_recent_rollcall_records", return_value=[]))
            courses_page.CoursesPageMixin._course_rollcalls_worker(host)
        elif operation == "verify":
            stack.enter_context(patch.object(module, "fetch_student_rollcall_detail", return_value=None, side_effect=failure))
            courses_page.CoursesPageMixin._course_verify_one_worker(host, record(), "synthetic-user", "synthetic")
        else:
            stack.enter_context(patch.object(module, "download_courseware", return_value=Path("fixture.pdf"), side_effect=failure))
            item = SimpleNamespace(filename="fixture.pdf", activity_title="Fixture")
            courseware_page.CoursewarePageMixin._courseware_download_worker(host, [item], Path("unused"))
            merge = next(event for event in events if event[0] == "merge_session_cookies")
            assert merge[1].cookies.get("worker-cookie") == "synthetic"
            assert events[-1][0] == "courseware_download_done"

        assert events
        worker.close.assert_called_once()
        source.close.assert_not_called()


@pytest.mark.parametrize("stage", ["fetch", "verify"])
@pytest.mark.parametrize("fails", [False, True])
def test_rollcall_pool_releases_all_sessions_after_workers_finish(stage, fails):
    with requests.Session() as source, ExitStack() as stack:
        workers = []
        active_workers = 0
        lock = threading.Lock()
        barrier = threading.Barrier(core.COURSE_ROLLCALL_WORKERS)
        source.close = Mock(wraps=source.close)

        def clone(original):
            worker = utils.clone_session(original)
            close = worker.close
            stack.callback(close)

            def close_after_workers():
                assert active_workers == 0, "a sibling request is still using its pool"
                close()

            worker.close = Mock(side_effect=close_after_workers)
            with lock:
                workers.append(worker)
            return worker

        def fetch_detail(*_args, **_kwargs):
            nonlocal active_workers
            with lock:
                active_workers += 1
            try:
                barrier.wait(timeout=10)
                if fails:
                    raise utils.SessionExpiredError("synthetic expiry")
                return None
            finally:
                with lock:
                    active_workers -= 1

        stack.enter_context(patch.object(core, "clone_session", side_effect=clone))
        if stage == "fetch":
            stack.enter_context(patch.object(core, "get_profile_user_id", return_value="synthetic"))

            def fetch_json(session, endpoints, label, **kwargs):
                if session is source:
                    return {"courses": [{"id": str(i), "name": "Synthetic"} for i in range(4)]}, "fixture"
                fetch_detail()
                return {"rollcalls": []}, "fixture"

            stack.enter_context(patch.object(core, "fetch_first_json", side_effect=fetch_json))
            operation = lambda: core.fetch_course_rollcall_records(source, "synthetic", "")
        else:
            stack.enter_context(patch.object(core, "fetch_student_rollcall_detail", side_effect=fetch_detail))
            operation = lambda: core.verify_recent_rollcall_records(source, "synthetic", [record(i) for i in range(4)])
        if fails:
            with pytest.raises(utils.SessionExpiredError):
                operation()
        else:
            operation()
        assert len(workers) == 4
        for worker in workers:
            worker.close.assert_called_once()
        source.close.assert_not_called()


@pytest.mark.parametrize("exit_mode", ["stopped", "expired", "emit_failure"])
def test_monitor_closes_its_session_on_every_exit(exit_mode):
    with requests.Session() as source:
        stop = threading.Event()
        if exit_mode == "stopped":
            stop.set()
        emit = Mock(side_effect=RuntimeError("synthetic UI failure") if exit_mode == "emit_failure" else None)
        with patch.object(core, "RollcallEngine") as engine_factory:
            engine_factory.return_value.poll_payload.side_effect = utils.SessionExpiredError("synthetic expiry")
            worker = core.MonitorWorker(source, emit, stop, 30)
        source.close = Mock(wraps=source.close)
        worker.session.close = Mock(wraps=worker.session.close)
        try:
            if exit_mode == "emit_failure":
                with pytest.raises(RuntimeError, match="synthetic UI failure"):
                    worker.run()
            else:
                worker.run()
            worker.session.close.assert_called_once()
            source.close.assert_not_called()
        finally:
            if not worker.session.close.called:
                worker.session.close()


def test_borrowed_fake_session_is_not_closed_by_monitor_or_page_worker():
    borrowed = SimpleNamespace(close=Mock())
    stop = threading.Event()
    stop.set()
    worker = core.MonitorWorker(borrowed, lambda event: None, stop, 30)
    worker.run()
    host = SimpleNamespace(session=borrowed, account={"id": "synthetic"}, _emit=lambda event: None)
    with patch.object(courseware_page, "fetch_courses", return_value=([], "fixture")):
        courseware_page.CoursewarePageMixin._courseware_courses_worker(host)
    borrowed.close.assert_not_called()


@pytest.mark.parametrize("outcome", ["success", "failure", "cancelled", "recheck", "delay"])
def test_answer_worker_releases_clone_but_preserves_cookies_for_gui_merge(outcome):
    from xmu_rollcall.desktop_qt import app

    with requests.Session() as source, ExitStack() as stack:
        worker = utils.clone_session(source)
        stack.callback(worker.close)
        worker.close = Mock(wraps=worker.close)
        source.close = Mock(wraps=source.close)
        worker.cookies.set("rotated-cookie", "synthetic", domain="example.test")
        events = []
        host = SimpleNamespace(
            session=source, account={"id": "synthetic"}, _emit=events.append,
            _answer_cancellations={}, _answer_cancellations_lock=threading.Lock(),
            _validate_auto_submission=lambda *_args: (False, "synthetic cancellation", "", True),
        )
        if outcome == "cancelled":
            host.session = object()
        if outcome == "delay":
            stack.enter_context(patch.object(app.threading, "Event", return_value=SimpleNamespace(wait=lambda _: True)))
        stack.enter_context(patch.object(app, "clone_session", return_value=worker))
        engine = stack.enter_context(patch.object(app, "RollcallEngine"))
        engine.return_value.answer.return_value = True
        if outcome == "failure":
            engine.return_value.answer.side_effect = utils.SessionExpiredError("synthetic expiry")
        event = SimpleNamespace(number_code="1234", rollcall_type="数字签到", rollcall_id="synthetic")
        app.DashboardWindow._answer_worker(
            host, "synthetic-event", event, 1 if outcome == "delay" else 0,
            {} if outcome == "recheck" else None, source, "synthetic",
        )
        worker.close.assert_called_once()
        source.close.assert_not_called()
        merge = next(event for event in events if event[0] == "merge_session_cookies")
        utils.merge_cookies(source, merge[1])
        assert source.cookies.get("rotated-cookie") == "synthetic"
        if outcome in {"cancelled", "recheck", "delay"}:
            engine.assert_not_called()
