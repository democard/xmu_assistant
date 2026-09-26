"""Exercise real streamed HTTP downloads against loopback servers only."""

import sys
import tempfile
import threading
import unittest
from collections import deque
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import (
    CoursewareItem,
    _download_url,
    download_courseware,
)
from xmu_rollcall.utils import clone_session


@contextmanager
def loopback_server(responses, host="127.0.0.1"):
    pending = deque(responses)
    recorded = []
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def do_GET(self):
            with lock:
                recorded.append((self.path, dict(self.headers)))
                status, body, headers = pending.popleft() if pending else (500, b"unexpected request", {})
            self.send_response(status)
            headers = {"Content-Type": "application/pdf", "Content-Length": str(len(body)), **headers}
            for key, value in headers.items():
                self.send_header(key, value)
            self.send_header("Connection", "close")
            self.end_headers()
            # Deliberately send many writes; requests must assemble a streamed body itself.
            for start in range(0, len(body), 8192):
                self.wfile.write(body[start:start + 8192])

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer((host, 0), Handler)
    server.daemon_threads = False
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05})
    thread.start()
    try:
        yield f"http://{host}:{server.server_port}/file.pdf", recorded
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        if thread.is_alive():
            raise AssertionError("loopback server thread did not stop")


@contextmanager
def local_session():
    with requests.Session() as session:
        session.trust_env = False
        responses = []
        session.hooks["response"].append(lambda response, **_kwargs: responses.append(response))
        yield session, responses


def direct_item(url):
    return CoursewareItem(
        course_id="fixture-course", activity_id="fixture-activity", activity_title="Fixture",
        activity_type="material", module_name="", syllabus_name="", upload_id="", reference_id="",
        filename="file.pdf", size=0, media_type="document", published_at="", upload_status="ready",
        allow_download=True, source_url=url,
    )


class DownloadHttpIntegrationTests(unittest.TestCase):
    def test_real_206_stream_appends_multiple_chunks_and_promotes_original_name(self):
        complete = bytes(range(256)) * 2600  # More than two 256 KiB download chunks.
        prefix_length = 65537
        tail = complete[prefix_length:]
        response = (206, tail, {"Content-Range": f"bytes {prefix_length}-{len(complete) - 1}/{len(complete)}"})
        with tempfile.TemporaryDirectory() as directory, loopback_server([response]) as (url, recorded):
            destination = Path(directory)
            partial = destination / "file.pdf.part"
            partial.write_bytes(complete[:prefix_length])
            with local_session() as (session, responses):
                target = download_courseware(session, direct_item(url), destination)
                self.assertEqual(target.read_bytes(), complete)
                self.assertEqual([request[1].get("Range") for request in recorded], [f"bytes={prefix_length}-"])
                self.assertEqual(len(responses), 1)
                self.assertTrue(responses[0].raw.closed)
            self.assertEqual(list(destination.iterdir()), [target])

    def test_real_416_restarts_once_without_range_and_streams_full_replacement(self):
        complete = bytes(range(256)) * 1200
        responses = [(416, b"", {"Content-Range": "bytes */3"}), (200, complete, {})]
        with tempfile.TemporaryDirectory() as directory, loopback_server(responses) as (url, recorded):
            destination = Path(directory)
            (destination / "file.pdf.part").write_bytes(b"stale-prefix")
            with local_session() as (session, received):
                target = _download_url(session, url, destination, "file.pdf")
                self.assertEqual(target.read_bytes(), complete)
                self.assertEqual([request[1].get("Range") for request in recorded], ["bytes=12-", None])
                self.assertEqual([response.status_code for response in received], [416, 200])
                self.assertTrue(all(response.raw.closed for response in received))
            self.assertEqual(list(destination.iterdir()), [target])

    def test_real_failed_restart_keeps_partial_and_closes_error_or_truncated_stream(self):
        failures = (
            ((500, b"server error", {}), requests.HTTPError),
            ((200, b"short", {"Content-Length": "40"}), requests.exceptions.ChunkedEncodingError),
        )
        for response, expected_error in failures:
            with self.subTest(status=response[0]), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory)
                partial = destination / "file.pdf.part"
                partial.write_bytes(b"stale-prefix")
                with loopback_server([(416, b"", {}), response]) as (url, recorded):
                    with local_session() as (session, received):
                        with self.assertRaises(expected_error):
                            _download_url(session, url, destination, "file.pdf")
                        self.assertEqual([request[1].get("Range") for request in recorded], ["bytes=12-", None])
                        self.assertEqual(len(received), 2)
                        self.assertTrue(all(item.raw.closed for item in received))
                    self.assertEqual(partial.read_bytes(), b"stale-prefix")
                    self.assertEqual(list(destination.iterdir()), [partial])

    def test_real_cross_host_redirect_keeps_cloned_cookie_at_its_original_host(self):
        # Both distinct hosts are numeric loopback addresses; no DNS or outside network is used.
        with (
            loopback_server([(200, b"downloaded", {})], host="127.0.0.2") as (cdn_url, cdn_requests),
            loopback_server([(302, b"", {"Location": cdn_url})]) as (url, origin_requests),
            tempfile.TemporaryDirectory() as directory,
            requests.Session() as source,
        ):
            source.trust_env = False
            source.cookies.set("SESSION", "synthetic-origin", domain="127.0.0.1", path="/")
            with clone_session(source) as worker:
                target = _download_url(worker, url, Path(directory), "file.pdf")
            self.assertEqual(target.read_bytes(), b"downloaded")
            self.assertEqual(len(origin_requests), 1)
            self.assertEqual(origin_requests[0][1].get("Cookie"), "SESSION=synthetic-origin")
            self.assertEqual(len(cdn_requests), 1)
            self.assertIsNone(cdn_requests[0][1].get("Cookie"))


if __name__ == "__main__":
    unittest.main()
