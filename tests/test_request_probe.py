from __future__ import annotations

import sys
import unittest
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import CourseSummary  # noqa: E402,F401  (包内依赖语义锚定)
from xmu_rollcall.courseware import _first_value, _get_json, _unwrap_list  # noqa: E402
from xmu_rollcall.desktop_qt.core import fetch_first_json, unwrap_list  # noqa: E402
from xmu_rollcall.rollcall_models import first_value  # noqa: E402
from xmu_rollcall.utils import SessionExpiredError  # noqa: E402


class FakeResponse:
    def __init__(self, status_code=200, payload=None, url="", history=None,
                 content_type="application/json", body_text=""):
        self.status_code = status_code
        self._payload = payload
        self.url = url
        self.history = history or []
        self.headers = {"Content-Type": content_type}
        self.text = body_text

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.exceptions.HTTPError(f"{self.status_code} Error")


class FakeSession:
    """按脚本依次返回响应的假会话：记录每次请求的 URL 供断言。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls: list[str] = []

    def get(self, url, headers=None, timeout=None):
        self.calls.append(url)
        return self.responses.pop(0)


ENDPOINTS = (
    "/api/my-courses?per_page=1000",
    "/api/courses?role=student&per_page=1000",
)
OK_JSON = {"courses": [{"id": "c1", "name": "高等数学"}]}


class FetchFirstJsonTests(unittest.TestCase):
    """core.fetch_first_json 行为锚定（批次B 去重前立此存照）。"""

    def test_first_200_json_wins_and_returns_endpoint(self):
        session = FakeSession([
            FakeResponse(status_code=503),
            FakeResponse(payload=OK_JSON),
        ])
        payload, endpoint = fetch_first_json(session, ENDPOINTS, "课程列表读取")
        self.assertEqual(payload, OK_JSON)
        self.assertEqual(endpoint, "/api/courses?role=student&per_page=1000")
        self.assertEqual(len(session.calls), 2)

    def test_401_raises_session_expired_immediately_without_more_probes(self):
        session = FakeSession([FakeResponse(status_code=401)])
        with self.assertRaises(SessionExpiredError) as ctx:
            fetch_first_json(session, ENDPOINTS, "课程列表读取")
        self.assertEqual(str(ctx.exception), "课程列表读取失败：登录已过期，请重新登录")
        self.assertEqual(len(session.calls), 1)

    def test_403_is_recorded_and_probe_continues_to_final_composite_error(self):
        # 与 _get_json 的直接上抛不同：探测场景下 403 落入 except Exception 记账后继续，
        # 最终以「接口探测失败」综合文案收场——本用例锁定该现状防误改。
        session = FakeSession([FakeResponse(status_code=403)] * len(ENDPOINTS))
        with self.assertRaises(RuntimeError) as ctx:
            fetch_first_json(session, ENDPOINTS, "课程列表读取")
        message = str(ctx.exception)
        self.assertIn("课程列表读取 接口探测失败：", message)
        self.assertIn("登录态已失效或当前账号无权访问", message)
        self.assertEqual(len(session.calls), len(ENDPOINTS))

    def test_identity_host_redirect_is_session_expired(self):
        # 会话过期 302 → requests 自动跟随，最终 URL 落统一身份域（requests 已经 200）
        response = FakeResponse(
            status_code=200,
            url="https://ids.xmu.edu.cn/authserver/login",
            content_type="text/html",
            body_text="<html>login</html>",
        )
        session = FakeSession([response])
        with self.assertRaises(SessionExpiredError) as ctx:
            fetch_first_json(session, ENDPOINTS, "签到情况读取")
        self.assertEqual(str(ctx.exception), "签到情况读取失败：登录已过期，请重新登录")

    def test_redirect_history_with_login_markers_is_session_expired(self):
        response = FakeResponse(
            status_code=200,
            url="https://lnt.xmu.edu.cn/login",
            history=[object()],
            content_type="text/html",
            body_text='<form id="pwdencryptsalt">authserver/login</form>',
        )
        session = FakeSession([response])
        with self.assertRaises(SessionExpiredError):
            fetch_first_json(session, ENDPOINTS, "签到情况读取")

    def test_non_json_200_is_fallback_failure_not_session_expired(self):
        class NonJson(FakeResponse):
            def json(self):
                raise ValueError("Expecting value: line 1 column 1 (char 0)")

        session = FakeSession([NonJson(payload=None)] * len(ENDPOINTS))
        with self.assertRaises(RuntimeError) as ctx:
            fetch_first_json(session, ENDPOINTS, "课程列表读取")
        self.assertIn("接口探测失败", str(ctx.exception))
        self.assertIn("Expecting value", str(ctx.exception))


class GetJsonTests(unittest.TestCase):
    """courseware._get_json 行为锚定（批次B 去重前立此存照）。"""

    def test_200_json_passthrough(self):
        session = FakeSession([FakeResponse(payload={"modules": []})])
        self.assertEqual(_get_json(session, "/api/x/modules", "模块读取"), {"modules": []})

    def test_401_raises_session_expired_immediately(self):
        session = FakeSession([FakeResponse(status_code=401)])
        with self.assertRaises(SessionExpiredError) as ctx:
            _get_json(session, "/api/x", "课件读取")
        self.assertEqual(str(ctx.exception), "课件读取失败：登录已过期，请重新登录")

    def test_403_propagates_runtime_error_directly(self):
        # 单端点语义：403 直接上抛给调用方重试/提示链路，不吞不续探
        session = FakeSession([FakeResponse(status_code=403)])
        with self.assertRaises(RuntimeError) as ctx:
            _get_json(session, "/api/x", "课件读取")
        self.assertEqual(str(ctx.exception), "课件读取失败：登录态已失效或当前账号无权访问")

    def test_other_non_200_follows_raise_for_status(self):
        session = FakeSession([FakeResponse(status_code=500)])
        with self.assertRaises(requests.exceptions.HTTPError):
            _get_json(session, "/api/x", "课件读取")

    def test_redirect_to_login_page_is_session_expired(self):
        response = FakeResponse(
            status_code=200,
            url="https://lnt.xmu.edu.cn/login",
            history=[object()],
            content_type="text/html",
            body_text="window.location=/auth/realms/xmu/protocol/openid-connect/auth",
        )
        session = FakeSession([response])
        with self.assertRaises(SessionExpiredError):
            _get_json(session, "/api/x", "课件读取")

    def test_200_non_json_raises_platform_not_json_error(self):
        class Broken(FakeResponse):
            def json(self):
                raise ValueError("Expecting value: line 1 column 1 (char 0)")

        session = FakeSession([Broken(payload=None)])
        with self.assertRaises(RuntimeError) as ctx:
            _get_json(session, "/api/x", "课件读取")
        self.assertEqual(str(ctx.exception), "课件读取失败：平台未返回 JSON 数据")


class SharedHelperParityTests(unittest.TestCase):
    """unwrap_list / first_value 双实现的等价性锚定（批次B 收敛前）。"""

    NESTED_PAYLOAD = {
        "data": {"items": None},
        "extra": {"list": [{"k": 1}]},
        "other": [{"deep": True}],
    }

    def test_unwrap_list_two_implementations_agree(self):
        keys = ("courses", "data", "items", "list", "results")
        self.assertEqual(unwrap_list(self.NESTED_PAYLOAD, keys), _unwrap_list(self.NESTED_PAYLOAD, keys))
        self.assertEqual(unwrap_list(["plain"], keys), _unwrap_list(["plain"], keys))
        self.assertEqual(unwrap_list("scalar", keys), _unwrap_list("scalar", keys))

    def test_first_value_two_implementations_agree(self):
        data = {"a": None, "b": "", "c": 0, "d": "hit"}
        self.assertEqual(first_value(data, ("a", "b", "c")), _first_value(data, ("a", "b", "c")))
        self.assertEqual(first_value(data, ("a", "b"), default="x"), _first_value(data, ("a", "b"), default="x"))
        self.assertEqual(first_value({}, ("a",)), _first_value({}, ("a",)))


if __name__ == "__main__":
    unittest.main()
