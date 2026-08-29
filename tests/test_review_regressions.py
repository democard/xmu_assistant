"""发布前审查修复的回归测试（2026-08-20 审查轮新增）。

覆盖本轮修复：
1. verify.send_radar 网络层失败按 bool 契约返回 False 而不抛异常（契约对齐 send_code）；
2. secrets.unprotect 对「dpapi: 前缀 + 非合法 base64」回退明文，不误清凭据；
3. 主会话 cookiejar 跨线程读(clone)/写(merge) 由 SESSION_COOKIE_LOCK 串行，不再抛
   RuntimeError: dictionary changed size during iteration。
"""
from __future__ import annotations

import sys
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

import requests  # noqa: E402

from xmu_rollcall import secrets  # noqa: E402
from xmu_rollcall.utils import (  # noqa: E402
    SESSION_COOKIE_LOCK,
    SessionExpiredError,
    clone_session,
    merge_cookies,
)
from xmu_rollcall.verify import send_code, send_radar  # noqa: E402


class _FakePutSession:
    """最小 fake：headers 存在；put 按行为返回响应或抛网络异常。"""

    def __init__(self, behavior="ok", exc=None):
        self.headers = {}
        self.behavior = behavior
        self.exc = exc
        self.calls = 0

    def put(self, url, json=None, headers=None, timeout=None):
        self.calls += 1
        if self.behavior == "network_error":
            raise self.exc

        if self.behavior == "html":
            class R:
                status_code = 500

                def json(self):
                    raise ValueError("not json")

            return R()

        class R:
            status_code = 200

            def json(self):
                return {}

        return R()


class SendRadarNetworkContractTests(unittest.TestCase):
    def test_network_error_returns_false_not_raise(self):
        # 审查 #2：send_radar 对网络层失败应返回 False（与 send_code 一致），不得向外抛
        sess = _FakePutSession(behavior="network_error", exc=requests.ConnectionError("boom"))
        try:
            result = send_radar(sess, "rc-1")
        finally:
            pass
        self.assertFalse(result)

    def test_non_json_response_returns_false(self):
        sess = _FakePutSession(behavior="html")
        self.assertFalse(send_radar(sess, "rc-2"))


class SecretsUnprotectGuardTests(unittest.TestCase):
    def test_dpapi_prefix_invalid_base64_treated_as_plaintext(self):
        # 审查 #4：真实密码恰以 "dpapi:" 开头且非合法 base64，不得被误判为密文清空
        stored = "dpapi:这是一个普通明文密码"
        self.assertEqual(secrets.unprotect(stored), stored)

    def test_plain_without_prefix_untouched(self):
        self.assertEqual(secrets.unprotect("普通密码"), "普通密码")


class CookieJarLockSmokeTests(unittest.TestCase):
    def test_concurrent_clone_and_merge_do_not_crash(self):
        # 审查 #7：锁定后 clone(读) 与 merge(写) 并发不再抛
        # RuntimeError: dictionary changed size during iteration
        main = requests.Session()
        for i in range(5):
            main.cookies.set(f"k{i}", f"v{i}")
        errors: list[BaseException] = []

        def cloner():
            for _ in range(40):
                try:
                    clone_session(main)
                except BaseException as exc:  # noqa: BLE001
                    errors.append(exc)

        def merger():
            for i in range(40):
                worker = requests.Session()
                worker.cookies.set("extra", str(i))
                try:
                    merge_cookies(main, worker)
                except BaseException as exc:  # noqa: BLE001
                    errors.append(exc)

        threads = [
            threading.Thread(target=cloner),
            threading.Thread(target=merger),
            threading.Thread(target=cloner),
            threading.Thread(target=merger),
        ]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)
        for t in threads:
            self.assertFalse(t.is_alive(), "线程未在 10s 内退出")
        self.assertEqual(errors, [])


class DualEndParityTests(unittest.TestCase):
    """双端常量 parity 守护（B11）：同一平台交互常量在 Kotlin/Python 多处手工维护，
    漂移即行为分叉（本次审查 A3/A10 的根源）。用正则从源码抽取并断言相等。"""

    def _read(self, rel: str) -> str:
        return (ROOT / rel).read_text(encoding="utf-8")

    def test_course_endpoints_identical_across_three_implementations(self):
        import re

        kt = self._read("android/app/src/main/java/com/xmu/assistant/CoursewareClient.kt")
        py_courseware = self._read("xmu-rollcall-cli/xmu_rollcall/courseware.py")
        py_core = self._read("xmu-rollcall-cli/xmu_rollcall/desktop_qt/core.py")

        kt_block = kt.split("val endpoints = listOf(", 1)[1].split(")", 1)[0]
        kt_endpoints = re.findall(r'"(/api[^"]*)"', kt_block)
        cw_block = py_courseware.split("COURSE_ENDPOINTS = (", 1)[1].split(")", 1)[0]
        cw_endpoints = re.findall(r'"(/api[^"]*)"', cw_block)

        # core.py 已单点化（2026-08-28 C3）：从 courseware 导入同一常量，禁止再落
        # 逐字副本（否则漂移面回到"事后拦截"）；结构性断言替代原文本抽取。
        self.assertIn(
            "from ..courseware import COURSE_ENDPOINTS",
            py_core,
            "core.py 必须从 courseware 导入 COURSE_ENDPOINTS（单一来源），不得重定义",
        )
        self.assertNotIn(
            "COURSE_ENDPOINTS = (",
            py_core,
            "core.py 出现了 COURSE_ENDPOINTS 字面定义：请回到导入单一来源",
        )
        self.assertTrue(kt_endpoints, "Kotlin 端点列表抽取为空，抽取逻辑可能已失效")
        self.assertEqual(kt_endpoints, cw_endpoints, "课程端点漂移：Android vs courseware.py")

    def test_direct_url_extensions_match_python_superset(self):
        import re

        kt = self._read("android/app/src/main/java/com/xmu/assistant/CoursewareClient.kt")
        py = self._read("xmu-rollcall-cli/xmu_rollcall/courseware.py")

        # 清单已收敛为顶层单一来源 DIRECT_COURSEWARE_EXTENSIONS（下载分流与
        # 页内计数共用），抽取锚随迁；防护语义不变：双端扩展名表必须一致
        kt_block = kt.split("DIRECT_COURSEWARE_EXTENSIONS", 1)[1].split(")", 1)[0]
        kt_exts = set(re.findall(r'"(\.[a-z0-9]+)"', kt_block))
        py_block = py.split("DIRECT_URL_EXTENSIONS = (", 1)[1].split(")", 1)[0]
        py_exts = set(re.findall(r'"(\.[a-z0-9]+)"', py_block))

        self.assertTrue(py_exts, "Python 扩展名表抽取为空")
        self.assertEqual(kt_exts, py_exts, "直链扩展名表漂移：双端下载产物会不一致")

    def test_identity_hosts_match_android_session_health(self):
        import re

        kt = self._read("android/app/src/main/java/com/xmu/assistant/SessionHealth.kt")
        py = self._read("xmu-rollcall-cli/xmu_rollcall/utils.py")

        kt_block = kt.split("KNOWN_IDENTITY_HOSTS = setOf(", 1)[1].split(")", 1)[0]
        kt_hosts = set(re.findall(r'"([a-z0-9.\-]+\.xmu\.edu\.cn)"', kt_block))
        py_block = py.split("IDENTITY_HOSTS = (", 1)[1].split(")", 1)[0]
        py_hosts = set(re.findall(r'"([a-z0-9.\-]+\.xmu\.edu\.cn)"', py_block))

        self.assertTrue(kt_hosts)
        self.assertEqual(kt_hosts, py_hosts, "身份域列表漂移：会话过期判定两端不一致")

    def test_rollcall_history_endpoint_templates_match_desktop(self):
        """最近十次签到端点模板与桌面 ROLLCALL_ENDPOINT_TEMPLATES 同源（本轮新增）。"""
        import re

        kt = self._read("android/app/src/main/java/com/xmu/assistant/RollcallHistoryClient.kt")
        py = self._read("xmu-rollcall-cli/xmu_rollcall/desktop_qt/core.py")

        py_block = py.split("ROLLCALL_ENDPOINT_TEMPLATES = (", 1)[1].split(")", 1)[0]
        py_paths = re.findall(r'"(/api/course/\{course_id\}/student/\{student_id\}/rollcalls[^"]*)"', py_block)

        # Android 侧按同一形状内插（${course.id}/$studentId），长短两模板缺一不可
        kt_long = '/api/course/${course.id}/student/$studentId/rollcalls?page=1&page_size=100'
        kt_short = '/api/course/${course.id}/student/$studentId/rollcalls"'
        self.assertEqual(len(py_paths), 2, "桌面端点模板数量漂移")
        self.assertTrue(any(p.endswith("rollcalls?page=1&page_size=100") for p in py_paths), py_paths)
        self.assertTrue(any(p.endswith("/rollcalls") for p in py_paths), py_paths)
        self.assertIn(kt_long, kt, "Android 长模板与桌面不一致")
        self.assertIn(kt_short, kt, "Android 短模板与桌面不一致")



class _ExpiredLoginResponse:
    """会话过期假响应：PUT 被 302 重放降级 GET，最终落在身份域登录页（200 HTML）。

    仅凭 status_code==200 会被旧实现判为提交成功（假成功）；本组用例锁定
    send_* 必须经 response_session_expired 识别并抛 SessionExpiredError。
    """

    status_code = 200
    url = "https://ids.xmu.edu.cn/cas/login?service=lnt"
    history = ("redirected",)
    headers = {"Content-Type": "text/html; charset=utf-8"}
    text = "<html>请先登录</html>"

    def json(self):
        raise ValueError("not json")


class _OkJsonResponse:
    """正常 API JSON 响应（200，教务域，无跳转）。"""

    status_code = 200
    url = "https://lnt.xmu.edu.cn/api/rollcall/rc/student_rollcalls"
    history = ()
    headers = {"Content-Type": "application/json"}

    def __init__(self, payload):
        self._payload = payload
        self.text = "{}"

    def json(self):
        return self._payload


class _FakeSubmitSession:
    """headers + get/put 按预设返回响应（send_code 与 send_radar 复用）。"""

    def __init__(self, get_response=None, put_response=None):
        self.headers = {}
        self._get_response = get_response
        self._put_response = put_response

    def get(self, url, headers=None, timeout=None):
        return self._get_response

    def put(self, url, json=None, headers=None, timeout=None):
        return self._put_response


class SendSubmitSessionExpiredTests(unittest.TestCase):
    """体检 P1：提交链路会话过期落 200 登录页必须上抛，不得假成功。"""

    def test_send_radar_expired_login_page_raises_not_true(self):
        # 首打点 PUT → 302 降级 GET → 200 登录页：旧实现 return True（假成功）
        sess = _FakeSubmitSession(put_response=_ExpiredLoginResponse())
        with self.assertRaises(SessionExpiredError):
            send_radar(sess, "rc-1")

    def test_send_code_expired_answer_put_raises_not_true(self):
        # 签到码 GET 正常、提交 PUT 过期：旧实现 return True（假成功）
        code_ok = _OkJsonResponse({"data": {"number_code": "1234"}})
        sess = _FakeSubmitSession(get_response=code_ok, put_response=_ExpiredLoginResponse())
        with self.assertRaises(SessionExpiredError):
            send_code(sess, "rc-2")

    def test_send_code_expired_code_get_raises_not_false(self):
        # 签到码 GET 即过期：旧实现吞成 return False（假失败），现按契约上抛
        sess = _FakeSubmitSession(get_response=_ExpiredLoginResponse())
        with self.assertRaises(SessionExpiredError):
            send_code(sess, "rc-3")


if __name__ == "__main__":
    unittest.main()
