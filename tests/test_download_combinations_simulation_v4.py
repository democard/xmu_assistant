"""Concurrent file ownership and redirect/encoding/recovery combinations, offline."""
import gzip
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

import pytest
import requests
import urllib3.response

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "xmu-rollcall-cli"))
from xmu_rollcall import courseware


@contextmanager
def local_server(responder, host="127.0.0.1"):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from socketserver import TCPServer
    requests_seen, lock = [], threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            headers = dict(self.headers)
            with lock:
                requests_seen.append((self.path, headers))
                index = len(requests_seen)
            status, body, extra = responder(self.path, headers, index)
            self.send_response(status)
            size = len(body) if isinstance(body, bytes) else 0
            for key, value in {"Content-Type": "application/pdf", "Content-Length": str(size), **extra}.items():
                self.send_header(key, value)
            self.send_header("Connection", "close")
            self.end_headers()
            for chunk in ([body] if isinstance(body, bytes) else body):
                self.wfile.write(chunk)

        def log_message(self, *_args):
            pass

    class LoopbackServer(ThreadingHTTPServer):
        def server_bind(self):
            # HTTPServer normally reverse-resolves its bound address. Tests
            # need neither DNS nor the system resolver's timeout for 127.0.0.2.
            TCPServer.server_bind(self)
            self.server_name, self.server_port = "localhost", self.server_address[1]

    server = LoopbackServer((host, 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    thread.start()
    try:
        yield f"http://{host}:{server.server_port}/file.pdf", requests_seen
    finally:
        server.shutdown()
        server.server_close()
        thread.join(5)
        assert not thread.is_alive()


def download(url, directory, filename="file.pdf"):
    with requests.Session() as session:
        session.trust_env = False
        return courseware._download_url(session, url, directory, filename)


@pytest.mark.parametrize("has_partial", [False, True])
def test_simultaneous_same_name_downloads_never_share_output_or_resume_bytes(tmp_path, has_partial):
    ready = threading.Barrier(2)
    content = {"/first.pdf": b"shared-first-" + b"a" * 65536,
               "/second.pdf": b"shared-second-" + b"b" * 65536}
    if has_partial:
        (tmp_path / "file.pdf.part").write_bytes(b"shared-")

    def respond(path, headers, _index):
        # Both downloads have selected their output before either receives data.
        ready.wait(5)
        body = content[path]
        offset = int(headers["Range"][6:-1]) if "Range" in headers else 0
        return (206, body[offset:], {"Content-Range": f"bytes {offset}-{len(body)-1}/{len(body)}"}) if offset else (200, body, {})

    with local_server(respond) as (url, received), ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(download, url.replace("/file.pdf", path), tmp_path) for path in content]
        outcomes = []
        for future in futures:
            try:
                outcomes.append(future.result(timeout=8))
            except Exception as exc:
                outcomes.append(exc)
        assert all(isinstance(value, Path) for value in outcomes), outcomes
        assert len(set(outcomes)) == 2
        assert [path.read_bytes() for path in outcomes] == list(content.values())
        assert sorted(headers.get("Range", "") for _, headers in received) == (["", "bytes=7-"] if has_partial else ["", ""])
        assert set(tmp_path.iterdir()) == set(outcomes)


def test_numbered_partial_is_resumed_after_a_short_stream(tmp_path):
    (tmp_path / "file.pdf").write_bytes(b"previous-file")
    prefix, tail = b"a" * (512 * 1024), b"next-data"
    complete = prefix + tail

    def respond(_path, headers, index):
        if index == 1:
            return 200, prefix, {"Content-Length": str(len(complete))}
        offset = int(headers["Range"][6:-1]) if "Range" in headers else 0
        return (206, complete[offset:], {"Content-Range": f"bytes {offset}-{len(complete)-1}/{len(complete)}"}) if offset else (200, complete, {})

    with local_server(respond) as (url, received):
        with pytest.raises(requests.exceptions.ChunkedEncodingError):
            download(url, tmp_path)
        partial = tmp_path / "file (2).pdf.part"
        assert partial.read_bytes() == prefix
        result = download(url, tmp_path)
        assert result.name == "file (2).pdf"
        assert received[-1][1].get("Range") == f"bytes={len(prefix)}-"
        assert result.read_bytes() == complete
        assert (tmp_path / "file.pdf").read_bytes() == b"previous-file"
        assert not list(tmp_path.glob("*.part"))


def test_one_download_target_cannot_replace_another_active_partial(tmp_path):
    first_requested, second_requested = threading.Event(), threading.Event()
    start_first_body, finish_first_body, first_opened = threading.Event(), threading.Event(), threading.Event()
    first_bytes, second_bytes = b"a" * (256 * 1024) + b"tail", b"second-resource"
    original_open = Path.open

    def tracked_open(path, mode="r", *args, **kwargs):
        file = original_open(path, mode, *args, **kwargs)
        if path == tmp_path / "file.pdf.part" and mode == "wb":
            first_opened.set()
        return file

    def first_chunks():
        yield first_bytes[:-4]
        assert finish_first_body.wait(5)
        yield first_bytes[-4:]

    def respond(path, _headers, _index):
        if path == "/first.pdf":
            first_requested.set()
            assert start_first_body.wait(5)
            return 200, first_chunks(), {"Content-Length": str(len(first_bytes))}
        second_requested.set()
        assert first_opened.wait(5)
        return 200, second_bytes, {}

    with local_server(respond) as (url, _), patch.object(Path, "open", tracked_open), \
            ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(download, url.replace("file.pdf", "first.pdf"), tmp_path, "file.pdf")
        try:
            assert first_requested.wait(3)
            # Both names are selected before the first .part is created.
            second = executor.submit(download, url.replace("file.pdf", "second.pdf"), tmp_path, "file.pdf.part")
            assert second_requested.wait(3)
            start_first_body.set()
            try:
                second_result = second.result(timeout=4)
            except Exception as exc:
                second_result = exc
        finally:
            start_first_body.set()
            finish_first_body.set()
        first_result = first.result(timeout=4)
    assert isinstance(second_result, Path), second_result
    assert first_result != second_result
    assert first_result.read_bytes() == first_bytes
    assert second_result.read_bytes() == second_bytes
    assert set(tmp_path.iterdir()) == {first_result, second_result}


def test_concurrent_shortcuts_do_not_overwrite_another_resource(tmp_path):
    ready = threading.Barrier(2)
    original_write = Path.write_text

    def delayed_write(path, *args, **kwargs):
        if path.parent == tmp_path and path.suffix == ".url":
            ready.wait(5)
        return original_write(path, *args, **kwargs)

    def write_link(number):
        item = courseware.CoursewareItem(
            course_id="simulation", activity_id=str(number), activity_title="same-name", activity_type="web_link",
            module_name="", syllabus_name="", upload_id="", reference_id="", filename="same-name", size=0,
            media_type="", published_at="", upload_status="", allow_download=True,
            source_url=f"https://example.test/resource/{number}",
        )
        return courseware.download_courseware(None, item, tmp_path)

    with patch.object(Path, "write_text", delayed_write), ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(write_link, number) for number in (1, 2)]
        paths = [future.result(timeout=8) for future in futures]
    assert len(set(paths)) == 2
    for number, path in zip((1, 2), paths):
        assert f"URL=https://example.test/resource/{number}\n" in path.read_text(encoding="utf-8")


def encoded_body(payload, encoding):
    if encoding in ("gzip", "x-gzip"):
        return gzip.compress(payload)
    codec = getattr(urllib3.response, "zstd", None)
    if "zstd" not in urllib3.response.HTTPResponse.CONTENT_DECODERS or codec is None:
        pytest.skip("this urllib3 runtime does not decode zstd")
    return codec.compress(payload) if hasattr(codec, "compress") else codec.ZstdCompressor().compress(payload)


@pytest.mark.parametrize("encoding,restart", [("x-gzip", False), ("x-gzip", True), ("zstd", False), ("zstd", True), ("gzip", True)])
def test_cross_host_redirect_and_compressed_response_preserve_decoded_bytes(tmp_path, encoding, restart):
    payload = b"synthetic-file-data" * 4096
    encoded = encoded_body(payload, encoding)
    if restart:
        (tmp_path / "file.pdf.part").write_bytes(b"outdated-prefix")

    def cdn(_path, _headers, index):
        if restart and index == 1:
            return 416, b"", {}
        return 200, encoded, {"Content-Encoding": encoding}

    with local_server(cdn, "127.0.0.2") as (cdn_url, cdn_requests), \
            local_server(lambda *_: (302, b"", {"Location": cdn_url})) as (url, origin_requests), \
            requests.Session() as session:
        session.trust_env = False
        session.cookies.set("SESSION", "synthetic-origin", domain="127.0.0.1", path="/")
        result = courseware._download_url(session, url, tmp_path, "file.pdf")
        assert result.read_bytes() == payload
        expected_ranges = ["bytes=15-", None] if restart else [None]
        assert [headers.get("Range") for _, headers in cdn_requests] == expected_ranges
        assert [headers.get("Range") for _, headers in origin_requests] == expected_ranges
        assert all(headers.get("Cookie") == "SESSION=synthetic-origin" for _, headers in origin_requests)
        assert all("Cookie" not in headers for _, headers in cdn_requests)
        assert list(tmp_path.iterdir()) == [result]


@pytest.mark.parametrize("encoding", ["gzip", "x-gzip", "zstd"])
def test_compressed_range_never_changes_the_existing_prefix(tmp_path, encoding):
    payload = b"range-tail" * 100
    encoded = encoded_body(payload, encoding)
    partial = tmp_path / "file.pdf.part"
    partial.write_bytes(b"prefix")
    def response(*_):
        return 206, encoded, {"Content-Encoding": encoding, "Content-Range": f"bytes 6-{5+len(encoded)}/{6+len(encoded)}"}
    with local_server(response) as (url, received), pytest.raises(RuntimeError):
        download(url, tmp_path)
    assert len(received) == 1
    assert partial.read_bytes() == b"prefix"
    assert list(tmp_path.iterdir()) == [partial]


def test_invalid_gzip_after_416_keeps_old_partial_and_releases_name(tmp_path):
    partial = tmp_path / "file.pdf.part"
    partial.write_bytes(b"old-prefix")
    def respond(_path, _headers, index):
        if index == 1:
            return 416, b"", {}
        if index == 2:
            return 200, b"not-a-gzip-stream", {"Content-Encoding": "gzip"}
        return 200, b"recovered-file", {}
    with local_server(respond) as (url, received):
        with pytest.raises(requests.exceptions.ContentDecodingError):
            download(url, tmp_path)
        assert partial.read_bytes() == b"old-prefix"
        assert list(tmp_path.iterdir()) == [partial]
        result = download(url, tmp_path)
        assert result.name == "file.pdf"
        assert result.read_bytes() == b"recovered-file"
        assert len(received) == 3


def test_request_exception_before_a_response_releases_the_filename(tmp_path):
    class OfflineSession:
        def get(self, *_args, **_kwargs):
            raise requests.Timeout("simulated connection timeout")
    with pytest.raises(requests.Timeout):
        courseware._download_url(OfflineSession(), "https://example.test/file.pdf", tmp_path, "file.pdf")
    assert list(tmp_path.iterdir()) == []
    with local_server(lambda *_: (200, b"recovered", {})) as (url, _):
        result = download(url, tmp_path)
    assert result.name == "file.pdf"
    assert result.read_bytes() == b"recovered"
