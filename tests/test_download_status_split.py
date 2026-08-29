"""桌面下载 401/403 分流回归测试（A11 双端对齐）。

背景：_download_url 原对 401/403 一律抛 PermissionError("平台拒绝下载该课件")，
会话过期（401）得不到"请重新登录"指引，与 Android CoursewareClient.downloadUrl
的 401→MainSessionExpired / 403→权限失败分流不一致。
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

import requests  # noqa: E402

from xmu_rollcall.courseware import _download_url  # noqa: E402
from xmu_rollcall.utils import SessionExpiredError  # noqa: E402


class _FakeResponse:
    def __init__(self, status_code, content_type="application/octet-stream"):
        self.status_code = status_code
        self.headers = {"Content-Type": content_type}
        self.url = "https://lnt.xmu.edu.cn/api/uploads/reference/x/url"
        self.history = []

    def iter_content(self, chunk_size=1):
        return iter(())

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}")

    def close(self):
        pass


class _FakeDownloadSession:
    """最小 fake：get 返回预设响应，记录调用。"""

    def __init__(self, response):
        self.response = response
        self.calls = []

    def get(self, url, headers=None, timeout=None, stream=False):
        self.calls.append(url)
        return self.response


class DownloadStatusSplitTests(unittest.TestCase):
    def test_401_raises_session_expired_not_permission_error(self):
        session = _FakeDownloadSession(_FakeResponse(401))
        with self.assertRaises(SessionExpiredError):
            _download_url(session, "https://lnt.xmu.edu.cn/f/x.pdf", Path("d") / "dl", "x.pdf")

    def test_403_stays_permission_error(self):
        session = _FakeDownloadSession(_FakeResponse(403))
        with self.assertRaises(PermissionError):
            _download_url(session, "https://lnt.xmu.edu.cn/f/x.pdf", Path("d") / "dl", "x.pdf")

    def test_200_ok_writes_file(self):
        directory = Path(_make_tmp_dir())
        try:
            session = _FakeDownloadSession(_FakeResponse(200))
            target = _download_url(session, "https://lnt.xmu.edu.cn/f/x.pdf", directory, "x.pdf")
            self.assertTrue(target.exists())
        finally:
            _rmtree(directory)


def _make_tmp_dir() -> str:
    import tempfile

    return tempfile.mkdtemp(prefix="xmu_dl_test_")


def _rmtree(path: Path) -> None:
    import shutil

    shutil.rmtree(path, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
