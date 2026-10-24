"""复核新增回归：直接运行持久化与流式下载生产路径。"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))

from xmu_rollcall import config, secrets
from xmu_rollcall.courseware import DownloadCancelledError, download_courseware, _write_resume_metadata, _courseware_resource_key
from xmu_rollcall.radar_coordinates import RadarCoordinateStore


@pytest.mark.parametrize("operation", ["add", "set_preferred", "delete", "clear"])
def test_coordinate_write_failure_preserves_memory_and_disk(tmp_path, operation):
    path = tmp_path / "radar.json"
    store = RadarCoordinateStore(path)
    first = store.add("c1", "数学", "A", 24.6, 118.3)
    second = store.add("c1", "数学", "B", 24.7, 118.4)
    before = store.all()
    saved = path.read_text(encoding="utf-8")
    with patch.object(Path, "replace", side_effect=OSError("disk unavailable")):
        with pytest.raises(OSError, match="disk unavailable"):
            if operation == "add":
                store.add("c1", "数学", "C", 24.8, 118.5)
            elif operation == "set_preferred":
                store.set_preferred(first.id)
            elif operation == "delete":
                store.delete(second.id)
            else:
                store.clear()
    assert store.all() == before
    assert path.read_text(encoding="utf-8") == saved
    assert store.preferred_for("c1", "数学").id == second.id
    assert not path.with_suffix(".tmp").exists()


def test_invalid_coordinate_timestamp_does_not_prevent_startup(tmp_path):
    path = tmp_path / "radar.json"
    path.write_text('[{"latitude":24.6,"longitude":118.3,"created_at_ms":1e999}]', encoding="utf-8")
    assert RadarCoordinateStore(path).all() == []


def test_null_current_account_uses_default_cookie_filename(tmp_path):
    with patch.object(config, "CONFIG_DIR", tmp_path), patch.object(
        config, "load_config", return_value={"current_account_id": None}
    ):
        assert Path(config.get_cookies_path()).name == "1.json"


@pytest.mark.skipif(not secrets.is_supported(), reason="Windows DPAPI")
@pytest.mark.parametrize("password", ["dpapi:c2VjcmV0", "dpapi:普通密码", "normal-password"])
def test_config_encrypts_known_plaintext_even_with_dpapi_prefix(tmp_path, password):
    with patch.object(config, "CONFIG_DIR", tmp_path), patch.object(config, "CONFIG_FILE", tmp_path / "config.json"):
        config.save_config({"accounts": [{"id": 1, "password": password}]})
        raw = json.loads(config.CONFIG_FILE.read_text(encoding="utf-8"))
        assert raw["accounts"][0]["password"] != password
        assert config.load_config()["accounts"][0]["password"] == password


class StreamResponse:
    def __init__(self, status=200, headers=None, chunks=(b"abc", b"def")):
        self.status_code = status
        self.headers = {"Content-Type": "application/pdf", "ETag": '"fixture-v1"', **(headers or {})}
        self.chunks = chunks
        self.closed = False

    def raise_for_status(self):
        pass

    def iter_content(self, chunk_size):
        yield from self.chunks

    def close(self):
        self.closed = True


def download_item():
    return SimpleNamespace(upload_id="", source_url="https://fixture.invalid/a.pdf", filename="a.pdf", activity_title="A")


def download(session, directory, **kwargs):
    return download_courseware(session, download_item(), directory, **kwargs)


@pytest.mark.parametrize("resume", [False, True])
def test_download_progress_counts_full_file_including_resume(tmp_path, resume):
    if resume:
        (tmp_path / "a.pdf.part").write_bytes(b"abc")
        _write_resume_metadata(tmp_path / "a.pdf.part", _courseware_resource_key(download_item()), '"fixture-v1"')
        response = StreamResponse(206, {"Content-Range": "bytes 3-5/6", "Content-Length": "3"}, (b"def",))
    else:
        response = StreamResponse(headers={"Content-Length": "6"})
    progress = []
    target = download(Mock(get=Mock(return_value=response)), tmp_path, progress_callback=lambda done, total: progress.append((done, total)))
    assert target.read_bytes() == b"abcdef"
    assert progress[0] == (3 if resume else 0, 6)
    assert progress[-1] == (6, 6)
    assert response.closed


@pytest.mark.parametrize("resume", [False, True])
def test_cancel_stream_preserves_safe_partial_and_releases_reservation(tmp_path, resume):
    if resume:
        (tmp_path / "a.pdf.part").write_bytes(b"abc")
        _write_resume_metadata(tmp_path / "a.pdf.part", _courseware_resource_key(download_item()), '"fixture-v1"')
        response = StreamResponse(206, {"Content-Range": "bytes 3-8/9"}, (b"def", b"ghi"))
    else:
        response = StreamResponse(headers={"Content-Length": "6"})
    cancelled = False

    def progress(done, total):
        nonlocal cancelled
        if done > (3 if resume else 0):
            cancelled = True

    session = Mock(get=Mock(return_value=response))
    with pytest.raises(DownloadCancelledError):
        download(session, tmp_path, progress_callback=progress, should_cancel=lambda: cancelled)
    assert response.closed
    assert not (tmp_path / "a.pdf").exists()
    assert (tmp_path / "a.pdf.part").read_bytes() == b"abc"
    assert not list(tmp_path.glob(".xmu-download-*"))
    session.get.return_value = StreamResponse(206, {"Content-Range": "bytes 3-5/6"}, (b"def",))
    assert download(session, tmp_path).read_bytes() == b"abcdef"


def test_cancel_before_start_makes_no_request_or_directory(tmp_path):
    session = Mock()
    destination = tmp_path / "not-created"
    with pytest.raises(DownloadCancelledError):
        download(session, destination, should_cancel=lambda: True)
    session.get.assert_not_called()
    assert not destination.exists()


def test_compressed_progress_does_not_report_wire_length_as_file_length(tmp_path):
    response = StreamResponse(headers={"Content-Length": "30", "Content-Encoding": "gzip"})
    progress = []
    download(Mock(get=Mock(return_value=response)), tmp_path, progress_callback=lambda done, total: progress.append((done, total)))
    assert progress[-1] == (6, None)
