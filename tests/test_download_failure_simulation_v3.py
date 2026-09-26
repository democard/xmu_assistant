"""Synthetic streamed responses and real temporary files; no outside network."""
import errno
import io
import sys
from pathlib import Path
from unittest.mock import Mock, patch

import pytest
import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall import courseware


def streamed_response(status, body=b"", **headers):
    response = requests.Response()
    response.status_code = status
    response.url = "https://files.example.test/lecture.pdf"
    response.headers.update({"Content-Type": "application/pdf", **headers})
    response.raw = io.BytesIO(body)
    response.close = Mock(wraps=response.close)
    return response


class OfflineDownloadSession:
    def __init__(self, *responses):
        self.responses = iter(responses)
        self.ranges = []

    def get(self, _url, **kwargs):
        self.ranges.append(kwargs["headers"].get("Range"))
        return next(self.responses)


def download(session, directory, filename="lecture.pdf"):
    return courseware._download_url(
        session, "https://files.example.test/lecture.pdf", directory, filename,
    )


@pytest.mark.parametrize("status,body", [
    (202, b"queued"), (204, b""), (205, b""), (207, b"multi-status"), (304, b""),
])
@pytest.mark.parametrize("has_partial", [False, True])
def test_non_file_status_never_promotes_or_replaces_a_download(tmp_path, status, body, has_partial):
    partial = tmp_path / "lecture.pdf.part"
    if has_partial:
        partial.write_bytes(b"verified-prefix")
    response = streamed_response(status, body)
    session = OfflineDownloadSession(response)
    with pytest.raises(RuntimeError):
        download(session, tmp_path)
    assert session.ranges == (["bytes=15-"] if has_partial else [None])
    assert not (tmp_path / "lecture.pdf").exists()
    assert list(tmp_path.iterdir()) == ([partial] if has_partial else [])
    if has_partial:
        assert partial.read_bytes() == b"verified-prefix"
    response.close.assert_called_once()
    assert response.raw.closed


@pytest.mark.parametrize("status", [202, 304])
def test_non_file_restart_after_416_preserves_the_original_partial(tmp_path, status):
    partial = tmp_path / "lecture.pdf.part"
    partial.write_bytes(b"old-prefix")
    rejected, restart = streamed_response(416), streamed_response(status)
    session = OfflineDownloadSession(rejected, restart)
    with pytest.raises(RuntimeError):
        download(session, tmp_path)
    assert session.ranges == ["bytes=10-", None]
    assert partial.read_bytes() == b"old-prefix"
    assert list(tmp_path.iterdir()) == [partial]
    rejected.close.assert_called_once()
    restart.close.assert_called_once()


def test_empty_200_is_a_valid_empty_file(tmp_path):
    response = streamed_response(200, **{"Content-Length": "0"})
    result = download(OfflineDownloadSession(response), tmp_path)
    assert result.read_bytes() == b""
    assert list(tmp_path.iterdir()) == [result]
    response.close.assert_called_once()


def test_failed_final_replace_keeps_complete_bytes_and_next_attempt_recovers(tmp_path):
    response = streamed_response(200, b"complete-file", **{"Content-Length": "13"})
    with patch.object(courseware.os, "replace", side_effect=PermissionError("simulated sharing violation")):
        with pytest.raises(PermissionError):
            download(OfflineDownloadSession(response), tmp_path)
    partial = tmp_path / "lecture.pdf.part"
    assert partial.read_bytes() == b"complete-file"
    assert list(tmp_path.iterdir()) == [partial]
    response.close.assert_called_once()
    retry = OfflineDownloadSession(streamed_response(200, b"complete-file"))
    result = download(retry, tmp_path)
    assert retry.ranges == ["bytes=13-"]
    assert result.read_bytes() == b"complete-file"
    assert list(tmp_path.iterdir()) == [result]


def test_disk_full_during_validated_append_keeps_a_resumable_prefix(tmp_path):
    partial = tmp_path / "lecture.pdf.part"
    partial.write_bytes(b"abc")
    response = streamed_response(206, b"defghi", **{"Content-Range": "bytes 3-8/9"})
    original_open = Path.open

    class DiskFullFile:
        def __init__(self, file):
            self.file = file

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return self.file.__exit__(*args)

        def write(self, data):
            self.file.write(data[:2])
            self.file.flush()
            raise OSError(errno.ENOSPC, "simulated disk full")

    def failing_open(path, mode="r", *args, **kwargs):
        file = original_open(path, mode, *args, **kwargs)
        return DiskFullFile(file) if path == partial and mode == "ab" else file

    with patch.object(Path, "open", failing_open):
        with pytest.raises(OSError, match="disk full"):
            download(OfflineDownloadSession(response), tmp_path)
    assert partial.read_bytes() == b"abcde"
    assert list(tmp_path.iterdir()) == [partial]
    response.close.assert_called_once()
    retry = OfflineDownloadSession(streamed_response(206, b"fghi", **{"Content-Range": "bytes 5-8/9"}))
    result = download(retry, tmp_path)
    assert retry.ranges == ["bytes=5-"]
    assert result.read_bytes() == b"abcdefghi"
    assert list(tmp_path.iterdir()) == [result]


@pytest.mark.parametrize("filename", ["../../outside.pdf", "C:\\outside.pdf", "CON.pdf", ".."])
def test_platform_filename_stays_in_the_chosen_directory(tmp_path, filename):
    destination = tmp_path / "downloads"
    destination.mkdir()
    sentinel = tmp_path / "outside.pdf"
    sentinel.write_bytes(b"untouched")
    result = download(OfflineDownloadSession(streamed_response(200, b"downloaded")), destination, filename)
    assert result.resolve().parent == destination.resolve()
    assert result.read_bytes() == b"downloaded"
    assert sentinel.read_bytes() == b"untouched"
    assert list(destination.iterdir()) == [result]
