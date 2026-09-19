"""换号竞态：账号归属快照必须早于会话克隆读取。

`clone_session` 会在锁内复制 cookiejar（毫秒级窗口）。若 worker 先克隆再读账号 id，
GUI 线程的换号恰好插在这两步之间，就会出现「旧账号会话 + 新账号 id」：逐项守卫因为
两者相等而放行，下载/应答用旧账号会话执行，并把旧 cookie merge 进新账号主会话
（跨账号污染）。先读 id 则失败朝安全侧——守卫判账号已变即取消。

本用例用「克隆期间切号」的 stub 精确复现该交错。
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import CoursewareItem  # noqa: E402
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
