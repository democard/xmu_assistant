"""换号竞态：账号归属快照必须早于会话克隆读取。

`clone_session` 会在锁内复制 cookiejar（毫秒级窗口）。若 worker 先克隆再读账号 id，
GUI 线程的换号恰好插在这两步之间，就会出现「旧账号会话 + 新账号 id」：逐项守卫因为
两者相等而放行，下载/应答用旧账号会话执行，并把旧 cookie merge 进新账号主会话
（跨账号污染）。先读 id 则失败朝安全侧——守卫判账号已变即取消。

本用例用「克隆期间切号」的 stub 精确复现该交错。
"""

from __future__ import annotations

import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import CoursewareItem  # noqa: E402
from xmu_rollcall.courseware import _get_modules_cached, reset_modules_cache  # noqa: E402
from xmu_rollcall.desktop_qt.app import DashboardWindow  # noqa: E402


def _item() -> CoursewareItem:
    return CoursewareItem(
        course_id="c1",
        activity_id="a1",
        activity_title="第一章",
        activity_type="file",
        module_name="m",
        syllabus_name="s",
        upload_id="u1",
        reference_id="",
        filename="讲义.pdf",
        size=1,
        media_type="",
        published_at="",
        upload_status="ready",
        allow_download=True,
        source_url="",
    )


class DownloadWorkerSnapshotOrderTest(unittest.TestCase):
    def test_clone_failure_still_emits_batch_done(self):
        from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin

        events = []
        host = object.__new__(CoursewarePageMixin)
        host.session = object()
        host.account = {"id": "A"}
        host._emit = events.append
        host._courseware_key = lambda item: "k1"
        with patch(
            "xmu_rollcall.desktop_qt.courseware_page.clone_session",
            side_effect=RuntimeError("clone failed"),
        ):
            host._courseware_download_worker([_item()], Path("D:/tmp"), object(), "A", host.session)
        done = next(event for event in events if event[0] == "courseware_download_done")
        self.assertTrue(done[5], "clone 失败必须进入失败列表")
        self.assertIn("clone failed", done[7][0])

    def test_courseware_worker_keeps_modules_cache_on_gui_session_scope(self):
        from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin

        class Response:
            status_code = 200
            headers = {"Content-Type": "application/json"}
            url = "https://lnt.xmu.edu.cn/api/courses/c1/modules"
            history = []
            text = "{}"

            def raise_for_status(self):
                return None

            def json(self):
                return {"modules": []}

        class Source:
            def __init__(self):
                self.module_calls = 0

            def get(self, *args, **kwargs):
                self.module_calls += 1
                return Response()

        source_a = Source()
        source_b = Source()
        host = object.__new__(CoursewarePageMixin)
        host.session = source_a
        host.account = {"id": "A"}
        host._emit = lambda event: None
        reset_modules_cache()

        def fake_fetch(session, _course_id):
            _get_modules_cached(session, "c1")
            return []

        with patch("xmu_rollcall.desktop_qt.courseware_page.clone_session", side_effect=lambda session: session), \
             patch("xmu_rollcall.desktop_qt.courseware_page.fetch_courseware", side_effect=fake_fetch):
            host._courseware_worker(types.SimpleNamespace(course_id="c1"), True)
            host._courseware_worker(types.SimpleNamespace(course_id="c1"), True)
            host.session = source_b
            host.account = {"id": "B"}
            host._courseware_worker(types.SimpleNamespace(course_id="c1"), True)

        self.assertEqual(source_a.module_calls, 1)
        self.assertEqual(source_b.module_calls, 1)

    def test_courseware_worker_snapshots_source_before_clone_race(self):
        from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin

        old_source = object()
        new_source = object()
        cloned = types.SimpleNamespace()
        events = []
        host = object.__new__(CoursewarePageMixin)
        host.session = old_source
        host.account = {"id": "A"}
        host._emit = events.append

        def racing_clone(source):
            self.assertIs(source, old_source)
            host.session = new_source
            host.account = {"id": "B"}
            return cloned

        def fake_fetch(session, _course_id):
            self.assertIs(session, cloned)
            self.assertIs(session._xmu_modules_cache_scope, old_source)
            return []

        with patch("xmu_rollcall.desktop_qt.courseware_page.clone_session", side_effect=racing_clone), \
             patch("xmu_rollcall.desktop_qt.courseware_page.fetch_courseware", side_effect=fake_fetch):
            host._courseware_worker(types.SimpleNamespace(course_id="c1"), True)

        self.assertEqual(events[0][0], "courseware")
        self.assertEqual(events[0][3], "A")

    def test_account_id_is_read_before_the_session_is_cloned(self):
        from xmu_rollcall.desktop_qt.courseware_page import CoursewarePageMixin

        events: list[tuple] = []
        host = object.__new__(CoursewarePageMixin)
        host.session = object()  # 旧账号的主会话
        host.account = {"id": "A"}
        host._emit = events.append
        host._courseware_key = lambda item: "k1"

        def racing_clone(session):
            # 模拟 clone 期间 GUI 线程完成换号：主会话与当前账号都变成 B
            host.account = {"id": "B"}
            host.session = object()
            return "OLD_ACCOUNT_SESSION_COPY"

        with patch(
            "xmu_rollcall.desktop_qt.courseware_page.clone_session",
            side_effect=racing_clone,
        ), patch("xmu_rollcall.desktop_qt.courseware_page.download_courseware") as download:
            host._courseware_download_worker([_item()], Path("D:/tmp"))

        download.assert_not_called()
        self.assertTrue(
            any(event[0] == "courseware_download_done" for event in events),
            "批次必须以完成事件收尾（旗标复位）",
        )

    def test_answer_worker_reads_the_account_before_cloning_too(self):
        # 同一范式：_answer_worker 也必须在 clone 之前取账号快照（源码顺序守护，
        # 其行为链路含网络提交，改动顺序是这里唯一需要锁定的不变量）。
        import inspect

        source = inspect.getsource(DashboardWindow._answer_worker)
        id_pos = source.find('worker_account_id = str((self.account or {}).get("id") or "")')
        clone_pos = source.find("clone_session(self.session)")
        # 用 find(-1) 而非 index()：锚被重命名/改写时给出可读信息，而不是抛 ValueError
        self.assertGreaterEqual(id_pos, 0, "锚丢失：_answer_worker 的账号快照语句未找到")
        self.assertGreaterEqual(clone_pos, 0, "锚丢失：_answer_worker 的会话克隆语句未找到")
        self.assertLess(id_pos, clone_pos, "_answer_worker 必须先读账号 id 再克隆会话")


if __name__ == "__main__":
    unittest.main()
