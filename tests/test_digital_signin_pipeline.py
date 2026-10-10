"""Real desktop monitor -> GUI policy -> worker -> engine, with no real transport.

Only widget rendering, notifications and thread scheduling are replaced. The
shared fixture uses synthetic identities and the currently observed JSON shape.
"""

import copy
import json
import threading
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from uuid import UUID

import pytest
import requests

from xmu_rollcall.desktop_qt.app import DashboardWindow
from xmu_rollcall.desktop_qt.core import MonitorWorker


FIXTURE = json.loads((Path(__file__).resolve().parents[1] /
    "android/app/src/test/resources/digital_protocol_fixture.json").read_text(encoding="utf-8"))


def response(url, payload, status=200):
    result = requests.Response()
    result.status_code = status
    result.url = url
    result.headers["Content-Type"] = "application/json"
    result._content = json.dumps(payload).encode("utf-8")
    return result


class Platform:
    headers = {"X-Fixture": "offline"}

    def __init__(self):
        self.activity = copy.deepcopy(FIXTURE["activity"])
        self.detail = copy.deepcopy(FIXTURE["detail"])
        self.timetable = copy.deepcopy(FIXTURE["timetable"])
        self.requests = []
        self.puts = []
        self.discoverable = True
        self.poll_count = 0
        self.on_poll = lambda _: None
        self.put_outcome = 200
        self.detail_status = 200
        self.lookup_status = 200

    def get(self, url, **kwargs):
        self.requests.append(("GET", url))
        path = urlparse(url).path
        if path == "/api/radar/rollcalls":
            self.poll_count += 1
            self.on_poll(self.poll_count)
            rows = [self.activity] if self.discoverable else []
            return response(url, {"rollcalls": rows})
        if path == "/api/rollcall/fixture-number/student_rollcalls":
            return response(url, self.detail, self.detail_status)
        if path == "/api/timetable_rollcalls":
            assert parse_qs(urlparse(url).query) == {
                "course_ids": ["fixture-course"], "rollcall_date": ["2026-06-03"],
            }
            return response(url, self.timetable, self.lookup_status)
        if path == "/api/my-courses":
            return response(url, {"courses": [{"id": "fixture-course", "name": "Fixture course"}]})
        raise AssertionError(f"Unscripted read: {url}")

    def put(self, url, json, **kwargs):
        assert urlparse(url).path == "/api/rollcall/fixture-number/answer_number_rollcall"
        self.requests.append(("PUT", url))
        self.puts.append(copy.deepcopy(json))
        if isinstance(self.put_outcome, Exception):
            raise self.put_outcome
        actual_code = self.timetable["rollcalls"][1]["number_code"]
        status = self.put_outcome if json["numberCode"] == actual_code else 400
        if status == 200:
            self.own_status("on_call")
        return response(url, {}, status)

    def own_status(self, status):
        self.detail["student_rollcalls"][0].update(status=status, rollcall_status=status)


class PollBudget(threading.Event):
    def __init__(self, rounds):
        super().__init__()
        self.rounds = rounds

    def wait(self, timeout=None):
        self.rounds -= 1
        if self.rounds <= 0:
            self.set()
        return self.is_set()


class Check:
    def isChecked(self):
        return True


class Text:
    def setText(self, value):
        pass


class Desktop:
    _ev_rollcall = DashboardWindow._ev_rollcall
    _add_rollcall_event = DashboardWindow._add_rollcall_event
    _ev_rollcall_progress = DashboardWindow._ev_rollcall_progress
    _answer_event = DashboardWindow._answer_event
    _answer_worker = DashboardWindow._answer_worker
    _cancel_pending_answer = DashboardWindow._cancel_pending_answer
    _validate_auto_submission = DashboardWindow._validate_auto_submission
    _ev_answer_result = DashboardWindow._ev_answer_result

    def __init__(self, platform, *, mode="none", delay=0, rounds=1):
        self.session = platform
        self.account = {"id": "fixture-account", "username": "fixture-user", "rollcall_settings": {
            "wait_before_answer_mode": mode, "wait_before_answer_count": 2,
            "wait_before_answer_percent": 50,
        }}
        self.monitor_stop_event = PollBudget(rounds)
        self._auto_answer_epoch = 1
        self._auto_answer_enabled = True
        self._auto_answer_inflight_rollcalls = {}
        self._auto_answer_attempted_rollcalls = set()
        self._answer_cancellations = {}
        self._answer_cancellations_lock = threading.Lock()
        self.auto_answer_check = Check()
        self.events_by_id = {}
        self.event_order = []
        self.event_sequence = 0
        self.metric_last_result = Text()
        self.results = []
        self.delay = delay
        self.before_worker = lambda: None

    def log(self, *_):
        pass

    def _refresh_event_tables(self):
        pass

    def _notify_rollcall(self, *_):
        pass

    def _auto_answer_delay(self, *_):
        return self.delay

    def _update_event_result(self, event_id, result, detail):
        event = self.events_by_id[event_id]
        event.result, event.detail = result, detail

    def _run_thread(self, target, *args):
        self.before_worker()
        target(*args)

    def _emit(self, event):
        if event[0] == "rollcall":
            self._ev_rollcall(event)
        elif event[0] == "rollcall_progress":
            self._ev_rollcall_progress(event)
        elif event[0] == "answer_result":
            self.results.append(event)
            self._ev_answer_result(event)

    def run(self):
        MonitorWorker(self.session, self._emit, self.monitor_stop_event, 1, "fixture-user").run()


@pytest.mark.parametrize("mode", ["none", "count", "percent"])
@pytest.mark.parametrize("course_context", ["id", "unique_title"])
def test_real_desktop_pipeline_fills_null_code_and_submits_once(mode, course_context):
    platform = Platform()
    if course_context == "unique_title":
        platform.activity.pop("course_id")
    desktop = Desktop(platform, mode=mode, rounds=2)
    desktop.run()
    assert len(platform.puts) == 1
    assert platform.puts[0]["numberCode"] == "0042"
    assert set(platform.puts[0]) == {"deviceId", "numberCode"}
    UUID(platform.puts[0]["deviceId"])
    assert desktop.results[0][2] is True
    assert desktop.events_by_id["event-1"].result == "已签到"


@pytest.mark.parametrize("status", ["on_call", "on_personal_leave", "unrecognized_status"])
def test_own_signed_leave_or_ambiguous_status_blocks_the_full_pipeline(status):
    platform = Platform()
    platform.own_status(status)
    Desktop(platform).run()
    assert platform.puts == []


def test_no_code_never_sends_empty_code_or_borrows_other_activity_code():
    platform = Platform()
    platform.timetable["rollcalls"][1]["number_code"] = None
    Desktop(platform).run()
    assert platform.puts == []


def test_delayed_worker_refreshes_code_before_submitting():
    platform = Platform()
    desktop = Desktop(platform, delay=0.001)
    desktop.before_worker = lambda: platform.timetable["rollcalls"][1].update(number_code="0048")
    desktop.run()
    assert [body["numberCode"] for body in platform.puts] == ["0048"]
    assert sum("/timetable_rollcalls?" in url for _, url in platform.requests) == 2


@pytest.mark.parametrize("reason", ["paused", "ended", "signed", "threshold_fell"])
def test_delayed_submission_rechecks_platform_and_user_changes(reason):
    platform = Platform()
    desktop = Desktop(platform, mode="count", delay=0.001)
    def change():
        if reason == "paused":
            desktop.monitor_stop_event.set()
        elif reason == "ended":
            platform.discoverable = False
        elif reason == "signed":
            platform.own_status("on_call")
        else:
            platform.detail["student_rollcalls"][2].update(status="absent", rollcall_status="absent")
    desktop.before_worker = change
    desktop.run()
    assert platform.puts == []


def test_threshold_waits_for_a_later_poll_then_submits():
    platform = Platform()
    platform.detail["student_rollcalls"][2].update(status="absent", rollcall_status="absent")
    def advance(round_number):
        if round_number == 2:
            platform.detail["student_rollcalls"][2].update(status="on_call", rollcall_status="on_call")
    platform.on_poll = advance
    Desktop(platform, mode="count", rounds=3).run()
    assert len(platform.puts) == 1


def test_empty_discovery_does_not_fall_back_to_web_timetable_for_discovery():
    platform = Platform()
    platform.discoverable = False
    Desktop(platform).run()
    assert platform.puts == []
    assert len(platform.requests) == 1


@pytest.mark.parametrize("outcome", [400, requests.Timeout("offline lost acknowledgement")])
def test_desktop_failed_write_is_not_retried_automatically(outcome):
    platform = Platform()
    platform.put_outcome = outcome
    desktop = Desktop(platform, rounds=3)
    desktop.run()
    assert len(platform.puts) == 1
    assert desktop.events_by_id["event-1"].result == "失败"


def test_code_missing_on_later_poll_must_not_reuse_stale_code():
    platform = Platform()
    platform.detail["student_rollcalls"][2].update(status="absent", rollcall_status="absent")
    def advance(round_number):
        if round_number == 2:
            platform.detail["student_rollcalls"][2].update(status="on_call", rollcall_status="on_call")
            platform.timetable["rollcalls"][1]["number_code"] = None
    platform.on_poll = advance
    Desktop(platform, mode="count", rounds=2).run()
    assert platform.puts == [], "A missing fresh code must not reuse the earlier cached code"


def test_delayed_recheck_failed_reads_must_not_authorize_a_stale_code_write():
    platform = Platform()
    desktop = Desktop(platform, delay=0.001)
    def reject_reads():
        platform.detail_status = 403
        platform.lookup_status = 403
    desktop.before_worker = reject_reads
    desktop.run()
    assert platform.puts == []
    assert desktop.results[0][4] is False


def test_missing_code_later_recovered_submits_the_fresh_code_once():
    platform = Platform()
    platform.detail["student_rollcalls"][2].update(status="absent", rollcall_status="absent")
    def advance(round_number):
        if round_number == 2:
            platform.detail["student_rollcalls"][2].update(status="on_call", rollcall_status="on_call")
            platform.timetable["rollcalls"][1]["number_code"] = None
        elif round_number == 3:
            platform.timetable["rollcalls"][1]["number_code"] = "0048"
    platform.on_poll = advance
    Desktop(platform, mode="count", rounds=4).run()
    assert [body["numberCode"] for body in platform.puts] == ["0048"]
