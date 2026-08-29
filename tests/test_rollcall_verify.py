"""PC 签到「本人是否已签」准确性修复回归（L1 自动核实 / L2 判定纯函数 / 快照兼容）。

覆盖：
- parse_rollcall_time：各时间格式解析与失败兜底（自 app.py 下沉的纯函数）；
- verify_own_status：本人明细五分支判定；
- fetch_course_rollcall_records 阶段二：最近 20 条选样、聚合误判纠正、
  on_verified 回调、SessionExpiredError 透传与熔断；
- CourseRollcallRecord.verified 新字段对旧 UI 快照的兼容往返；
- course_records_verified / course_records_verify_error 事件契约登记。
"""
from __future__ import annotations

import json
import sys
import threading
import unittest
from unittest.mock import patch
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt import app as app_module  # noqa: E402
from xmu_rollcall.desktop_qt import core as core_module  # noqa: E402
from xmu_rollcall.desktop_qt import courses_page as courses_page_module  # noqa: E402
from xmu_rollcall.desktop_qt.core import (  # noqa: E402
    RECENT_VERIFY_LIMIT,
    CourseRollcallRecord,
    fetch_course_rollcall_records,
    fetch_student_rollcall_detail,
    parse_rollcall_time,
    verify_own_status,
    verify_recent_rollcall_records,
)
from xmu_rollcall.desktop_qt.events import EVENT_CONTRACTS  # noqa: E402
from xmu_rollcall.utils import SessionExpiredError  # noqa: E402


class TestParseRollcallTime(unittest.TestCase):
    def test_iso_with_t_and_space(self):
        self.assertEqual(parse_rollcall_time("2026-08-22T08:00:00"), datetime(2026, 8, 22, 8, 0, 0))
        self.assertEqual(parse_rollcall_time("2026-08-22 08:00:30"), datetime(2026, 8, 22, 8, 0, 30))

    def test_offsets_are_normalized_to_naive(self):
        parsed = parse_rollcall_time("2026-08-22T08:00:00Z")
        self.assertIsNone(parsed.tzinfo)
        self.assertEqual(parsed, datetime(2026, 8, 22, 8, 0, 0))
        # 带偏移：与 app 展示层原逻辑一致，剥 tzinfo 按挂钟比较（不换算时区）
        self.assertEqual(parse_rollcall_time("2026-08-22T08:00:00+08:00"), datetime(2026, 8, 22, 8, 0, 0))

    def test_trailing_junk_falls_back_to_19_char_prefix(self):
        # 全串解析失败、但截前 19 字符可解析：走第二候选
        self.assertEqual(parse_rollcall_time("2026-08-22 08:00:00 UTC"), datetime(2026, 8, 22, 8, 0, 0))
        # 小数秒 + 偏移在 Python 3.11 可整串解析：剥 tzinfo 后保留微秒（记录实际行为）
        parsed = parse_rollcall_time("2026-08-22T08:00:00.123456+08:00")
        self.assertEqual(parsed, datetime(2026, 8, 22, 8, 0, 0, 123456))

    def test_invalid_inputs_return_none(self):
        for raw in ("-", "", None, "不是时间", "2026/08/22"):
            self.assertIsNone(parse_rollcall_time(raw), repr(raw))


class TestVerifyOwnStatus(unittest.TestCase):
    USERNAME = "u_test"

    def test_own_record_with_timestamp_is_signed_in(self):
        detail = {"student_rollcalls": [{"user_no": self.USERNAME, "updated_at": "2026-08-22T08:01:00"}]}
        self.assertEqual(verify_own_status(detail, self.USERNAME, "signed"), "已签到")

    def test_own_record_answered_or_submitted_counts_as_timestamp(self):
        for key in ("answered_at", "submitted_at"):
            detail = {"student_rollcalls": [{"user_no": self.USERNAME, key: "2026-08-22T08:01:00"}]}
            self.assertEqual(verify_own_status(detail, self.USERNAME, ""), "已签到", key)

    def test_own_explicit_not_signed_wins_over_aggregate(self):
        detail = {"student_rollcalls": [{"user_no": self.USERNAME, "status": "not_signed"}]}
        self.assertEqual(verify_own_status(detail, self.USERNAME, "signed"), "未签到")

    def test_own_signed_status_classified(self):
        detail = {"student_rollcalls": [{"user_no": self.USERNAME, "status": "present"}]}
        self.assertEqual(verify_own_status(detail, self.USERNAME, ""), "已签到")

    def test_unverifiable_cases_keep_aggregate_none(self):
        # 无本人记录 / 明细为空 / 本人状态词无法分类 → 一律 None（保持聚合值）
        self.assertIsNone(verify_own_status({"student_rollcalls": [{"user_no": "other"}]}, self.USERNAME, "x"))
        self.assertIsNone(verify_own_status(None, self.USERNAME, "x"))
        self.assertIsNone(verify_own_status({}, self.USERNAME, "x"))
        detail = {"student_rollcalls": [{"user_no": self.USERNAME, "status": "weird_state"}]}
        self.assertIsNone(verify_own_status(detail, self.USERNAME, "x"))


class _FakeResponse:
    def __init__(self, payload=None, status_code=200, url="https://lnt.xmu.edu.cn/api/fake"):
        self._payload = payload
        self.status_code = status_code
        self.url = url
        self.history = []
        self.headers = {"Content-Type": "application/json"}
        self.text = json.dumps(payload) if payload is not None else ""

    def json(self):
        if self._payload is None:
            raise ValueError("no payload")
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _FakeSession:
    """按 URL 子串路由的假会话；student_rollcalls 响应由用例注入并可计数。"""

    def __init__(self, courses, rollcalls_by_course, details_by_rollcall_id):
        self.courses = courses
        self.rollcalls_by_course = rollcalls_by_course
        self.details_by_rollcall_id = details_by_rollcall_id
        self.detail_calls = 0
        self._lock = threading.Lock()

    def get(self, url, headers=None, timeout=None):
        if "/api/profile" in url:
            return _FakeResponse({"id": "u9"})
        if "/api/my-courses" in url:
            return _FakeResponse({"courses": self.courses})
        if "/rollcalls?" in url or url.endswith("/rollcalls"):
            course_id = url.split("/api/course/")[1].split("/")[0]
            return _FakeResponse({"rollcalls": self.rollcalls_by_course[course_id]})
        if "/student_rollcalls" in url:
            with self._lock:
                self.detail_calls += 1
                rollcall_id = url.split("/api/rollcall/")[1].split("/")[0]
            return self.details_by_rollcall_id[rollcall_id]
        raise AssertionError(f"意外请求：{url}")


def _record(rid, minute, course="c1"):
    return {
        "id": rid,
        "rollcall_time": f"2026-07-01T08:{minute:02d}:00",
        "status": "signed",
    }


class TestFetchStageTwoVerify(unittest.TestCase):
    def _fetch_then_verify(self, session):
        """按 worker 的契约顺序调用：先阶段一拉全量，再阶段二核实。"""
        records, _source = fetch_course_rollcall_records(
            session, username="u_test", academic_year="", semester="全部",
        )
        return records, verify_recent_rollcall_records(session, "u_test", records)

    def test_recent_twenty_selected_and_aggregate_misjudgment_corrected(self):
        # 两个课程共 30 条记录（r001..r030，编号越大越新）；
        # 全部聚合状态为 signed，但本人明细显示 r011 未签 → 应被纠正为未签到。
        courses = [{"id": "c1", "name": "课程一"}, {"id": "c2", "name": "课程二"}]
        rollcalls = {
            "c1": [_record(f"r{i:03d}", i) for i in range(1, 16)],
            "c2": [_record(f"r{i:03d}", i) for i in range(16, 31)],
        }
        details = {}
        for i in range(1, 31):
            rid = f"r{i:03d}"
            status = "not_signed" if rid == "r011" else "present"
            details[rid] = _FakeResponse(
                {"student_rollcalls": [{"user_no": "u_test", "status": status}]},
                url=f"https://lnt.xmu.edu.cn/api/rollcall/{rid}/student_rollcalls",
            )
        session = _FakeSession(courses, rollcalls, details)
        records, received = self._fetch_then_verify(session)
        self.assertEqual(len(records), 30)
        self.assertEqual(len(received), RECENT_VERIFY_LIMIT)
        verified_ids = {r.rollcall_id for r in received}
        # 只核实最近 20 条：r011 之后的 20 个编号（r011..r030），更早的 r001..r010 不发请求
        self.assertEqual(verified_ids, {f"r{i:03d}" for i in range(11, 31)})
        corrected = next(r for r in received if r.rollcall_id == "r011")
        self.assertEqual(corrected.signed_status, "未签到")
        self.assertTrue(corrected.verified)
        self.assertTrue(all(r.verified for r in received))
        self.assertEqual(session.detail_calls, RECENT_VERIFY_LIMIT)

    def test_session_expired_propagates_and_stops_queued_verifies(self):
        # 单课程 5 条 → 工作线程 1 条：首个明细即过期 → 类型化异常上抛，
        # 且熔断后剩余排队的核实任务不再发请求（detail_calls == 1）。
        courses = [{"id": "c1", "name": "课程一"}]
        rollcalls = {"c1": [_record(f"r{i}", i) for i in range(1, 6)]}
        expired = _FakeResponse(
            {"error": "login"},
            url="https://c-identity.xmu.edu.cn/authserver/login",
        )
        details = {f"r{i}": expired for i in range(1, 6)}
        session = _FakeSession(courses, rollcalls, details)
        with self.assertRaises(SessionExpiredError):
            self._fetch_then_verify(session)
        self.assertEqual(session.detail_calls, 1)

    def test_unverifiable_details_keep_placeholder_rows_out(self):
        # 无效 rollcall_id（占位 "-"）不参与核实；明细无法判定时回调列表为空且不改写状态。
        courses = [{"id": "c1", "name": "课程一"}]
        rollcalls = {
            "c1": [
                {"id": "-", "rollcall_time": "-", "status": "unknown"},
                dict(_record("r1", 10), status="weird_state"),
            ],
        }
        details = {
            # 明细里没有本人记录 → verify_own_status 返回 None → 不产生更新
            "r1": _FakeResponse(
                {"student_rollcalls": [{"user_no": "someone_else"}]},
                url="https://lnt.xmu.edu.cn/api/rollcall/r1/student_rollcalls",
            ),
        }
        session = _FakeSession(courses, rollcalls, details)
        records, received = self._fetch_then_verify(session)
        self.assertEqual(len(records), 2)
        self.assertEqual(received, [])
        # 占位 "-" 不发请求；有效 r1 拉了明细但无法判定本人状态 → 保持聚合值
        self.assertEqual(session.detail_calls, 1)

    def test_empty_candidates_return_without_requests(self):
        session = _FakeSession([{"id": "c1", "name": "课程一"}], {"c1": []}, {})
        records, received = self._fetch_then_verify(session)
        # 空课程按既有行为产生一条 rollcall_id="-" 的「无签到记录」占位行，
        # 阶段二必须跳过它：零明细请求、零回执
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0].rollcall_id, "-")
        self.assertEqual(received, [])
        self.assertEqual(session.detail_calls, 0)


class TestWorkerEmitOrderContract(unittest.TestCase):
    """事件顺序契约（真机验收教训）：先发 course_rollcalls 首屏、后跑阶段二核实。

    顺序颠倒时 verified 先落地、被随后的 course_rollcalls 全新记录覆盖，
    摘要卡死在「正在核实…」。以源码文本契约锁定（项目既有哲学：布局/时序类
    改动 JVM/GUI 测不到的用文本断言兜底）。
    """

    def test_worker_emits_first_screen_before_stage_two(self):
        import inspect

        # _course_rollcalls_worker 已随签到情况页出库到 courses_page.py（2026-08-28
        # 第四刀），顺序契约的锚定对象随之迁移（防护等价，只迁不改）。
        text = inspect.getsource(courses_page_module)
        start = text.index("def _course_rollcalls_worker")
        end = text.index("\n    def ", start + 10)
        body = text[start:end]
        emit_pos = body.index('("course_rollcalls"')
        verify_pos = body.index("verify_recent_rollcall_records")
        self.assertLess(
            emit_pos, verify_pos,
            "_course_rollcalls_worker 必须先 emit 首屏再调用阶段二核实（否则核实结果被覆盖）",
        )


class TestVerifiedSnapshotCompat(unittest.TestCase):
    def _records(self, verified):
        return [CourseRollcallRecord(
            course_id="c1", course_title="高等数学", rollcall_id="r1",
            rollcall_time="2026-08-22 08:00:00", rollcall_type="数字签到",
            signed_status="已签到", platform_status="signed", detail="",
            verified=verified,
        )]

    def test_roundtrip_preserves_verified_flag(self):
        text = app_module.ui_snapshot_to_json(42, self._records(True), [])
        account_id, records, _courses = app_module.ui_snapshot_from_json(text)
        self.assertEqual(account_id, "42")
        self.assertTrue(records[0].verified)

    def test_legacy_snapshot_without_verified_key_still_loads(self):
        # 旧快照（无 verified 键）必须能解析：verified 取默认 False，无需 bump 版本
        legacy_record = {
            "course_id": "c1", "course_title": "高等数学", "rollcall_id": "r1",
            "rollcall_time": "2026-08-22 08:00:00", "rollcall_type": "数字签到",
            "signed_status": "未知", "platform_status": "weird", "detail": "",
        }
        text = json.dumps({
            "version": app_module.UI_SNAPSHOT_VERSION,
            "account_id": "7",
            "course_records": [legacy_record],
            "courseware_courses": [],
        })
        parsed = app_module.ui_snapshot_from_json(text)
        self.assertIsNotNone(parsed)
        _account_id, records, _courses = parsed
        self.assertFalse(records[0].verified)
        self.assertEqual(records[0].signed_status, "未知")


class TestEventContractRegistration(unittest.TestCase):
    def test_verify_events_registered_with_expected_arity(self):
        self.assertIn("course_records_verified", EVENT_CONTRACTS)
        min_args, _desc = EVENT_CONTRACTS["course_records_verified"]
        self.assertGreaterEqual(min_args, 4)  # kind + records + worker_account_id + origin
        self.assertIn("course_records_verify_error", EVENT_CONTRACTS)
        min_args, _desc = EVENT_CONTRACTS["course_records_verify_error"]
        self.assertGreaterEqual(min_args, 3)  # kind + message + worker_account_id

    def test_default_record_is_unverified(self):
        record = CourseRollcallRecord(
            course_id="c", course_title="t", rollcall_id="r", rollcall_time="-",
            rollcall_type="签到", signed_status="未知", platform_status="-",
        )
        self.assertFalse(record.verified)


class NormalizeCourseTermDictTests(unittest.TestCase):
    """平台偶发把 term 字段下发为对象：dict 解包取值而非 str(dict) 落展示。"""

    def test_dict_term_unwrapped_to_name(self):
        from xmu_rollcall.desktop_qt.core import normalize_course

        course = normalize_course({
            "id": "c1", "name": "课程一",
            "term_name": {"name": "2025-2026 第二学期"},
        })
        self.assertEqual(course["term"], "2025-2026 第二学期")

    def test_plain_term_unchanged(self):
        from xmu_rollcall.desktop_qt.core import normalize_course

        course = normalize_course({"id": "c1", "name": "课程一", "term": "2025-2026"})
        self.assertEqual(course["term"], "2025-2026")


class FetchStudentRollcallDetailTests(unittest.TestCase):
    """200 + 非 JSON（网关错误页直返 200 且无重定向）按资源级失败返回 None，
    不穿透 L1 核实/数字签到码 worker（旧实现 json() 的 ValueError 直接上抛）。"""

    @staticmethod
    def _response(status_code=200, payload=None, json_error=False):
        class Response:
            def __init__(self):
                self.status_code = status_code
                self.headers = {"Content-Type": "text/html"}
                self._payload = payload
                self._json_error = json_error

            def json(self):
                if self._json_error:
                    raise ValueError("Expecting value")
                return self._payload

        return Response()

    def test_non_json_200_returns_none(self):
        response = self._response(json_error=True)

        class Session:
            def get(self, url, **kwargs):
                return response

        self.assertIsNone(fetch_student_rollcall_detail(Session(), "r1"))

    def test_json_200_returns_payload(self):
        response = self._response(payload={"student_rollcalls": []})

        class Session:
            def get(self, url, **kwargs):
                return response

        self.assertEqual(fetch_student_rollcall_detail(Session(), "r1"), {"student_rollcalls": []})

    def test_empty_rollcall_id_returns_none_without_request(self):
        class Session:
            def get(self, url, **kwargs):
                raise AssertionError("empty id must short-circuit")

        self.assertIsNone(fetch_student_rollcall_detail(Session(), ""))


class TestFetchStageOneStopOnExpired(unittest.TestCase):
    def test_stage_one_session_expired_stops_queued_courses(self):
        # 单 worker 强制顺序：course1 首端点 401（终态）上抛后，同池排队的
        # course2 不得再发任何请求（镜像阶段二熔断语义；旧实现会继续探测）。
        courses = [{"id": "c1", "name": "课程一"}, {"id": "c2", "name": "课程二"}]

        class RoutingSession:
            def __init__(self):
                self.course_calls = []
                self._lock = threading.Lock()

            def get(self, url, headers=None, timeout=None):
                if "/api/profile" in url:
                    return _FakeResponse({"id": "u9"})
                if "/api/my-courses" in url:
                    return _FakeResponse({"courses": courses})
                if "/rollcalls" in url:
                    course_id = url.split("/api/course/")[1].split("/")[0]
                    with self._lock:
                        self.course_calls.append(course_id)
                    if course_id == "c1":
                        return _FakeResponse({}, status_code=401)
                    return _FakeResponse({"rollcalls": []})
                raise AssertionError(f"意外请求：{url}")

        session = RoutingSession()
        with patch.object(
            core_module, "COURSE_ROLLCALL_WORKERS", 1,
        ), self.assertRaises(SessionExpiredError):
            fetch_course_rollcall_records(
                session, username="u_test", academic_year="", semester="全部",
            )
        self.assertEqual(session.course_calls, ["c1"])


if __name__ == "__main__":
    unittest.main()
