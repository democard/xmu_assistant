"""A rejected resume range must recover once without losing a usable partial."""

import sys
import tempfile
import unittest
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import _download_url  # noqa: E402
from xmu_rollcall.utils import SessionExpiredError  # noqa: E402


class DownloadResponse:
    def __init__(self, status, body=b"", **headers):
        self.status_code = status
        self.headers = {"Content-Type": "application/pdf", **headers}
        self.body = body
        self.closed = False

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}")

    def iter_content(self, chunk_size):
        yield self.body

    def close(self):
        self.closed = True


class DownloadSession:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.ranges = []

    def get(self, url, **kwargs):
        self.ranges.append(kwargs["headers"].get("Range"))
        result = self.responses.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class ResumeRecoveryTests(unittest.TestCase):
    def test_rejected_range_retries_full_download_once(self):
        rejected = DownloadResponse(416)
        full = DownloadResponse(200, b"new", **{"Content-Length": "3"})
        session = DownloadSession(rejected, full)
        with tempfile.TemporaryDirectory() as directory:
            partial = Path(directory) / "file.pdf.part"
            partial.write_bytes(b"stale-file")
            target = _download_url(session, "https://example.test/file.pdf", Path(directory), "file.pdf")
            self.assertEqual(target.read_bytes(), b"new")
            self.assertFalse(partial.exists())
        self.assertEqual(session.ranges, ["bytes=10-", None])
        self.assertTrue(rejected.closed)
        self.assertTrue(full.closed)

    def test_failed_restart_keeps_partial_and_does_not_retry_again(self):
        for result, error in (
            (DownloadResponse(416), requests.HTTPError),
            (DownloadResponse(401), SessionExpiredError),
            (DownloadResponse(403), PermissionError),
            (DownloadResponse(200, b"error", **{"Content-Type": "application/json"}), RuntimeError),
            (DownloadResponse(200, b"short", **{"Content-Length": "20"}), RuntimeError),
            (requests.Timeout("offline"), requests.Timeout),
        ):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as directory:
                partial = Path(directory) / "file.pdf.part"
                partial.write_bytes(b"stale-file")
                rejected = DownloadResponse(416)
                session = DownloadSession(rejected, result)
                with self.assertRaises(error):
                    _download_url(session, "https://example.test/file.pdf", Path(directory), "file.pdf")
                self.assertEqual(session.ranges, ["bytes=10-", None])
                self.assertEqual(partial.read_bytes(), b"stale-file")
                self.assertFalse((Path(directory) / "file.pdf").exists())
                self.assertEqual(list(Path(directory).iterdir()), [partial])
                self.assertTrue(rejected.closed)
                if isinstance(result, DownloadResponse):
                    self.assertTrue(result.closed)

    def test_416_without_a_partial_is_not_retried(self):
        response = DownloadResponse(416)
        session = DownloadSession(response)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(requests.HTTPError):
                _download_url(session, "https://example.test/file.pdf", Path(directory), "file.pdf")
            self.assertEqual(list(Path(directory).iterdir()), [])
        self.assertEqual(session.ranges, [None])
        self.assertTrue(response.closed)

    def test_restart_accepts_complete_206_from_zero(self):
        session = DownloadSession(
            DownloadResponse(416),
            DownloadResponse(206, b"new", **{"Content-Range": "bytes 0-2/3"}),
        )
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "file.pdf.part").write_bytes(b"stale-file")
            target = _download_url(session, "https://example.test/file.pdf", Path(directory), "file.pdf")
            self.assertEqual(target.read_bytes(), b"new")
            self.assertEqual(list(Path(directory).iterdir()), [target])
        self.assertEqual(session.ranges, ["bytes=10-", None])


if __name__ == "__main__":
    unittest.main()
