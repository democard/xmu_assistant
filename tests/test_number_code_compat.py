"""Offline fixtures only: date fallback is scoped to course + exact activity ID."""

from datetime import datetime, timezone
from unittest.mock import patch

import pytest

from xmu_rollcall.desktop_qt.core import (
    CourseRollcallRecord, fetch_student_rollcall_detail, verify_recent_rollcall_records,
)
from xmu_rollcall.number_code import complete_number_code, matching_timetable_code, rollcall_code_dates
from xmu_rollcall.utils import SessionExpiredError
from xmu_rollcall.verify import send_code


class Response:
    def __init__(self, payload=None, status=200):
        self.status_code = status
        self.payload = payload
        self.headers = {"Content-Type": "application/json"}
        self.url = "https://lnt.xmu.edu.cn/api/fixture"
        self.history = []
        self.text = "fixture"

    def json(self):
        return self.payload


class Session:
    headers = {"X-Test": "fixture"}

    def __init__(self, *responses):
        self.responses = list(responses)
        self.gets = []
        self.puts = []

    def get(self, url, **kwargs):
        self.gets.append(url)
        return self.responses.pop(0)

    def put(self, url, json, **kwargs):
        self.puts.append((url, json))
        return Response()


def timetable():
    return {"rollcalls": [
        {"id": "other", "course": {"id": "c1"}, "number_code": "9999"},
        {"id": "r1", "course": {"id": "c1"}, "is_number": True, "number_code": "0048",
         "student_rollcalls": [{"student_id": "s1", "status": "present"}]},
    ]}


@pytest.mark.parametrize("raw", ["2026-06-03T23:48:00Z", "2026-06-04T07:48:00+08:00", "2026-06-04 07:48:00"])
def test_dates_use_utc_start_not_local_display_day(raw):
    assert rollcall_code_dates(raw) == ["2026-06-03"]


def test_unknown_start_queries_at_most_two_utc_days():
    assert rollcall_code_dates(now=datetime(2026, 6, 4, tzinfo=timezone.utc)) == ["2026-06-04", "2026-06-03"]
    assert rollcall_code_dates(end_time="2026-06-04T00:10:00Z") == ["2026-06-04", "2026-06-03"]


def test_old_null_code_falls_back_and_submits_exact_code_once():
    session = Session(Response({"number_code": None}), Response(timetable()))
    assert send_code(session, "r1", course_id="c1", rollcall_time="2026-06-04 07:48:00")
    assert session.gets[1].endswith("/api/timetable_rollcalls?course_ids=c1&rollcall_date=2026-06-03")
    assert len(session.puts) == 1
    url, body = session.puts[0]
    assert url.endswith("/api/rollcall/r1/answer_number_rollcall")
    assert body["numberCode"] == "0048"
    assert body["deviceId"]


def test_old_valid_code_skips_all_fallback_reads():
    session = Session()
    assert complete_number_code(session, "r1", {"number_code": "0048"}, course_id="c1") == "0048"
    assert session.gets == []


def test_missing_or_wrong_id_never_borrows_a_code_or_submits():
    session = Session(Response({"number_code": None}), Response(timetable()))
    assert not send_code(session, "missing", course_id="c1", rollcall_time="2026-06-03T12:00:00Z")
    assert session.puts == []
    assert matching_timetable_code(timetable(), "r1", "c2") == ""
    assert matching_timetable_code({"rollcalls": [{"id": "r1", "number_code": None,
        "student_rollcalls": [{"number_code": "9999"}]}]}, "r1") == ""


def test_overlay_keeps_legacy_roster_and_does_not_adopt_timetable_roster():
    old = {"number_code": None, "student_rollcalls": [{"user_no": "fixture", "status": "absent"}]}
    session = Session(Response(old), Response(timetable()))
    detail = fetch_student_rollcall_detail(session, "r1", number_code_fallback=True,
                                          course_id="c1", rollcall_time="2026-06-03T12:00:00Z")
    assert detail["number_code"] == "0048"
    assert detail["student_rollcalls"] == old["student_rollcalls"]
    assert old["number_code"] is None
    assert session.puts == []


def test_history_verification_also_fills_missing_code():
    session = Session(Response({"number_code": None}), Response(timetable()))
    record = CourseRollcallRecord("c1", "Fixture", "r1", "2026-06-04 07:48:00", "数字签到", "未知", "finished")
    with patch("xmu_rollcall.desktop_qt.core.ThreadLocalSessions") as workers:
        workers.return_value.__enter__.return_value.get.return_value = session
        updated = verify_recent_rollcall_records(session, "fixture", [record])
    assert updated[0].number_code == "0048"
    assert updated[0].signed_status == "未知"
    assert not updated[0].verified


def test_fallback_401_propagates_but_resource_403_leaves_code_empty():
    with pytest.raises(SessionExpiredError):
        complete_number_code(Session(Response(status=401)), "r1", course_id="c1", rollcall_time="2026-06-03T12:00:00Z")
    assert complete_number_code(Session(Response(status=403)), "r1", course_id="c1", rollcall_time="2026-06-03T12:00:00Z") == ""


def test_unique_enrolled_course_title_resolves_monitor_context():
    session = Session(Response({"courses": [{"id": "c1", "name": "Fixture"}]}), Response(timetable()))
    assert complete_number_code(session, "r1", course_title="Fixture", rollcall_time="2026-06-03T12:00:00Z") == "0048"
    ambiguous = Session(Response({"courses": [{"id": "c1", "name": "Fixture"}, {"id": "c2", "name": "Fixture"}]}))
    assert complete_number_code(ambiguous, "r1", course_title="Fixture") == ""
    assert len(ambiguous.gets) == 1


def test_no_context_does_not_probe_unrelated_courses():
    session = Session()
    assert complete_number_code(session, "r1") == ""
    assert session.gets == []


def test_pause_prevents_fallback_reads_after_legacy_detail():
    session = Session(Response({"number_code": None}))
    assert fetch_student_rollcall_detail(session, "r1", number_code_fallback=True,
        course_id="c1", cancelled=lambda: True) == {"number_code": None}
    assert len(session.gets) == 1
