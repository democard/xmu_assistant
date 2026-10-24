"""Query worker events retain their source login generation, without network."""
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall.desktop_qt import app, core, courses_page, courseware_page


def record():
    return core.CourseRollcallRecord(
        course_id="fixture-course", course_title="Fixture", rollcall_id="fixture-call",
        rollcall_time="2026-01-01 12:00:00", rollcall_type="数字签到",
        signed_status="未知", platform_status="unknown",
    )


@pytest.mark.parametrize("operation", ["courses", "courseware", "rollcalls", "verify"])
@pytest.mark.parametrize("failure_stage", [None, "fetch", "clone"])
def test_real_query_worker_late_events_do_not_touch_same_account_new_session(operation, failure_stage):
    old_source, new_source = object(), object()
    worker_session = SimpleNamespace(close=Mock())
    events = []
    fresh_records = ["new session records"]
    host = SimpleNamespace(
        session=old_source, account={"id": "fixture", "username": "old-user"},
        _emit=events.append, log=Mock(), course_records=fresh_records,
        course_refresh_in_progress=True, course_verify_in_progress=True,
        courseware_courses_refresh_in_progress=True, courseware_refresh_in_progress=True,
    )
    module = courses_page if operation in ("rollcalls", "verify") else courseware_page

    def clone(source):
        assert source is old_source
        host.session = new_source
        host.account = {"id": "fixture", "username": "old-user"}
        if failure_stage == "clone":
            raise RuntimeError("synthetic clone failure")
        return worker_session

    fetch_name = {
        "courses": "fetch_courses", "courseware": "fetch_courseware",
        "rollcalls": "fetch_course_rollcall_records", "verify": "fetch_student_rollcall_detail",
    }[operation]
    result = ([], "fixture") if operation in ("courses", "rollcalls") else ([] if operation == "courseware" else None)
    failure = RuntimeError("synthetic fetch failure") if failure_stage == "fetch" else None
    with patch.object(module, "clone_session", side_effect=clone), \
         patch.object(module, fetch_name, return_value=result, side_effect=failure), \
         patch.object(courses_page, "verify_recent_rollcall_records", return_value=[]):
        if operation == "courses":
            courseware_page.CoursewarePageMixin._courseware_courses_worker(host)
        elif operation == "courseware":
            courseware_page.CoursewarePageMixin._courseware_worker(host, SimpleNamespace(course_id="fixture-course"))
        elif operation == "rollcalls":
            courses_page.CoursesPageMixin._course_rollcalls_worker(host)
        else:
            courses_page.CoursesPageMixin._course_verify_one_worker(host, record(), "old-user", "fixture")

    assert events
    for event in events:
        assert event[-1] is old_source
        getattr(app.DashboardWindow, "_ev_" + event[0])(host, event)
    assert host.course_records is fresh_records
    assert host.course_refresh_in_progress
    assert host.course_verify_in_progress
    assert host.courseware_courses_refresh_in_progress
    assert host.courseware_refresh_in_progress


def test_rollcall_worker_keeps_username_account_and_source_from_before_clone_switch():
    old_source, new_source = object(), object()
    worker_session = SimpleNamespace(close=Mock())
    events = []
    host = SimpleNamespace(session=old_source, account={"id": "old", "username": "old-user"}, _emit=events.append)
    def clone(source):
        assert source is old_source
        host.session = new_source
        host.account = {"id": "new", "username": "new-user"}
        return worker_session
    with patch.object(courses_page, "clone_session", side_effect=clone), \
         patch.object(courses_page, "fetch_course_rollcall_records", return_value=([], "fixture")) as fetch, \
         patch.object(courses_page, "verify_recent_rollcall_records", return_value=[]) as verify:
        courses_page.CoursesPageMixin._course_rollcalls_worker(host)
    assert fetch.call_args.kwargs["username"] == "old-user"
    assert verify.call_args.args[1] == "old-user"
    assert events[0][3] == "old"
    assert events[1][2] == "old"
    assert all(event[-1] is old_source for event in events)


@pytest.mark.parametrize("with_source", [False, True])
def test_current_source_and_legacy_courseware_results_still_unlock_and_apply(with_source):
    source = object()
    host = SimpleNamespace(
        session=source, account={"id": "fixture"}, courseware_refresh_in_progress=True,
        _set_courseware_items=Mock(), _update_nav_badges=Mock(), log=Mock(),
    )
    course = SimpleNamespace(course_id="fixture-course")
    event = ("courseware", course, [], "fixture") + ((source,) if with_source else ())
    app.DashboardWindow._ev_courseware(host, event)
    assert not host.courseware_refresh_in_progress
    host._set_courseware_items.assert_called_once_with(course, [])


def test_queued_manual_verify_keeps_dispatch_source_instead_of_a_later_session():
    old_source, new_source = object(), object()
    host = SimpleNamespace(session=new_source, account={"id": "new"}, _emit=Mock())
    worker = SimpleNamespace(close=Mock())
    with patch.object(courses_page, "clone_session", return_value=worker) as clone, \
         patch.object(courses_page, "fetch_student_rollcall_detail", return_value=None):
        courses_page.CoursesPageMixin._course_verify_one_worker(host, record(), "old-user", "old", old_source)
    clone.assert_called_once_with(old_source)
    assert host._emit.call_args.args[0][-1] is old_source
