"""PC 判定面门控矩阵补测（E2）。

⚠️ 红线：本文件只**锚定现行为**，不得据此改动判定条件。登录认证 / Cookie /
会话判定属风控敏感面，方向未拍板前任何对齐动作都不做（双端 403 语义与页面
判定门控的差异见 8-28 记忆「A3 双端 403 语义方案」，维持分域现状）。

覆盖此前为零的门控分支：
- request_probe.classify_status 的 401 / 403 / 其它 三态直接单测（此前只经
  fetch_first_json、_get_json 间接覆盖）；
- request_probe.detect_expired_page 的转发语义（与 utils.response_session_expired
  同输入同输出，判定条件只有一个来源）；
- utils.response_session_expired 的门控矩阵补齐：4096 字节窥探边界、
  无跳转（history 空）时不得仅凭 HTML 标记判定、headers/text 取用异常降级、
  peek_body=False 的非 HTML 分支。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.request_probe import (  # noqa: E402
    classify_status,
    detect_expired_page,
    forbidden_error,
    session_expired_error,
)
from xmu_rollcall.utils import (  # noqa: E402
    IDENTITY_HOSTS,
    LOGIN_PAGE_MARKERS,
    SessionExpiredError,
    response_session_expired,
)


class _Raising:
    """取用即抛的替身：模拟流式响应读 text、或 headers 结构异常。"""

    def __init__(self, message="boom"):
        self._message = message

    def __getitem__(self, item):
        raise RuntimeError(self._message)

    def __iter__(self):
        raise RuntimeError(self._message)


class FakeResponse:
    def __init__(self, url="", history=None, content_type="text/html", body="",
                 raise_on_text=False, raise_on_headers=False):
        self.url = url
        self.history = history if history is not None else []
        self._content_type = content_type
        self._body = body
        self._raise_on_text = raise_on_text
        self._raise_on_headers = raise_on_headers

    @property
    def headers(self):
        if self._raise_on_headers:
            return _Raising("headers unavailable")
        return {"Content-Type": self._content_type}

    @property
    def text(self):
        if self._raise_on_text:
            raise RuntimeError("streaming body already consumed")
        return self._body


def expired(response, peek_body=True) -> bool:
    return response_session_expired(response, peek_body=peek_body)


class ClassifyStatusTests(unittest.TestCase):
    """classify_status 三态直接单测（此前仅经调用方间接覆盖）。"""

    def test_401_is_typed_session_expired(self):
        error = classify_status(401, "课件读取")
        self.assertIsInstance(error, SessionExpiredError)
        self.assertEqual(str(error), "课件读取失败：登录已过期，请重新登录")

    def test_403_is_plain_runtime_error_not_session_expired(self):
        # 资源级无权限：刻意不走续登，避免单端点权限问题触发 CAS 重登
        error = classify_status(403, "课件读取")
        self.assertIsInstance(error, RuntimeError)
        self.assertNotIsInstance(error, SessionExpiredError)
        self.assertEqual(str(error), "课件读取失败：登录态已失效或当前账号无权访问")
        self.assertEqual(str(error), str(forbidden_error("课件读取")))

    def test_other_statuses_return_none(self):
        for code in (200, 204, 301, 400, 404, 500, 502, 503):
            self.assertIsNone(classify_status(code, "签到情况读取"), f"code={code} 不应命中分级")

    def test_session_expired_error_helper_matches_classify(self):
        self.assertEqual(str(classify_status(401, "x")), str(session_expired_error("x")))


class DetectExpiredPageForwardingTests(unittest.TestCase):
    """detect_expired_page 只是探测语境的转发入口：与 response_session_expired 逐例同结果。"""

    CASES = [
        ("身份域最终 URL", dict(url="https://ids.xmu.edu.cn/authserver/login")),
        ("普通 API 无跳转", dict(url="https://lnt.xmu.edu.cn/api/profile")),
        (
            "跳转 + HTML + 标记词",
            dict(
                url="https://lnt.xmu.edu.cn/login",
                history=[object()],
                body='<form id="pwdencryptsalt">authserver/login</form>',
            ),
        ),
        (
            "跳转 + HTML 无标记",
            dict(url="https://lnt.xmu.edu.cn/error", history=[object()], body="<html>error</html>"),
        ),
        (
            "跳转 + 非 HTML",
            dict(url="https://lnt.xmu.edu.cn/x", history=[object()], content_type="application/json"),
        ),
    ]

    def test_forwarding_matches_shared_predicate_for_every_case(self):
        for label, kwargs in self.CASES:
            with self.subTest(label):
                response = FakeResponse(**kwargs)
                self.assertEqual(
                    detect_expired_page(response),
                    expired(response),
                )

    def test_forwarding_matches_for_streaming_peek_body_false(self):
        # 流式上下文由调用方传 peek_body，转发层本身只走默认 peek_body=True
        self.assertEqual(
            detect_expired_page(
                FakeResponse(url="https://lnt.xmu.edu.cn/x", history=[object()], body="x"),
            ),
            expired(FakeResponse(url="https://lnt.xmu.edu.cn/x", history=[object()], body="x")),
        )


class ResponseSessionExpiredGateMatrixTests(unittest.TestCase):
    """response_session_expired 门控矩阵（补此前未覆盖的边界与降级分支）。"""

    def test_marker_within_4096_byte_peek_is_detected(self):
        # 标记尾端正好落在窥探窗口内（起始 4081 + 14 字符 = 4095 < 4096）
        body = "a" * 4081 + "pwdencryptsalt"
        self.assertTrue(
            expired(FakeResponse(url="https://lnt.xmu.edu.cn/login", history=[object()], body=body)),
            "窗口内的标记必须命中",
        )

    def test_marker_beyond_4096_byte_peek_is_not_detected(self):
        # 窥探上限是有意的性能/风控折中：超长页不整页扫
        body = "a" * 4096 + "pwdencryptsalt"
        self.assertFalse(
            expired(FakeResponse(url="https://lnt.xmu.edu.cn/login", history=[object()], body=body)),
            "4096 字节之外的标记不得命中（门控上限）",
        )

    def test_identity_host_in_body_beyond_peek_window_is_not_detected(self):
        body = "b" * 4096 + "ids.xmu.edu.cn"
        self.assertFalse(
            expired(FakeResponse(url="https://lnt.xmu.edu.cn/login", history=[object()], body=body)),
        )

    def test_html_markers_without_redirect_history_are_not_expired(self):
        # history 空 = 没发生过跳转：普通取到的 HTML 页（哪怕含标记词）不算会话过期，
        # 否则平台自身的 HTML 错误页/维护页会被误判成登录页触发续登。
        self.assertFalse(
            expired(
                FakeResponse(
                    url="https://lnt.xmu.edu.cn/some-page",
                    history=[],
                    body='<form id="pwdencryptsalt">authserver/login</form>',
                )
            ),
            "无跳转时不得仅凭 HTML 标记判定（history 门控）",
        )

    def test_content_type_gate_rejects_non_html_after_redirect(self):
        self.assertFalse(
            expired(
                FakeResponse(
                    url="https://lnt.xmu.edu.cn/x",
                    history=[object()],
                    content_type="application/json; charset=utf-8",
                    body="pwdencryptsalt",
                )
            ),
            "非 HTML 响应不得进入标记扫描",
        )

    def test_peek_body_false_requires_html_after_redirect(self):
        # 流式：跳转过但 Content-Type 非 HTML → 不按登录页处理（正常 API 不会是 HTML）
        self.assertFalse(
            expired(
                FakeResponse(
                    url="https://lnt.xmu.edu.cn/download",
                    history=[object()],
                    content_type="application/octet-stream",
                ),
                peek_body=False,
            )
        )

    def test_peek_body_false_without_history_is_not_expired(self):
        self.assertFalse(
            expired(
                FakeResponse(url="https://lnt.xmu.edu.cn/download", history=[], content_type="text/html"),
                peek_body=False,
            )
        )

    def test_headers_access_failure_degrades_to_not_expired(self):
        self.assertFalse(
            expired(
                FakeResponse(
                    url="https://lnt.xmu.edu.cn/x",
                    history=[object()],
                    raise_on_headers=True,
                )
            ),
            "headers 取用异常必须降级为「未过期」，不得把异常漏给调用方",
        )

    def test_body_access_failure_degrades_to_not_expired(self):
        self.assertFalse(
            expired(
                FakeResponse(
                    url="https://lnt.xmu.edu.cn/x",
                    history=[object()],
                    body="ignored",
                    raise_on_text=True,
                )
            ),
            "流式 body 已消费时 text 抛错必须降级为「未过期」",
        )

    def test_identity_url_wins_even_when_body_unreadable(self):
        # 最终 URL 命中身份域是第一顺位判定，与 body 是否可读无关
        self.assertTrue(
            expired(
                FakeResponse(
                    url="https://c-identity.xmu.edu.cn/auth/realms/xmu/login",
                    history=[],
                    raise_on_text=True,
                )
            )
        )

    def test_marker_and_host_constants_are_the_judged_vocabulary(self):
        # 判定词表变更属风控面，用断言把现状钉住（改词必须显式改这里）
        self.assertEqual(IDENTITY_HOSTS, ("c-identity.xmu.edu.cn", "ids.xmu.edu.cn"))
        self.assertEqual(
            LOGIN_PAGE_MARKERS,
            ("pwdencryptsalt", "authserver/login", "/auth/realms/xmu/"),
        )


if __name__ == "__main__":
    unittest.main()
