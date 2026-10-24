"""Repair-ledger regressions using production functions and isolated transports."""
from __future__ import annotations

import smtplib
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock, patch

import pytest

from xmu_rollcall import config, courseware, notifications


def item(**values):
    fields = dict(course_id="course", activity_id="activity", activity_title="Fixture", activity_type="material",
                  module_name="", syllabus_name="", upload_id="", reference_id="", filename="lesson.pdf",
                  size=0, media_type="document", published_at="", upload_status="ready", allow_download=True,
                  source_url="https://fixture.invalid/lesson.pdf")
    fields.update(values)
    return courseware.CoursewareItem(**fields)


class Response:
    def __init__(self, code=200, chunks=(b"AAAA", b"AAAA"), headers=None, payload=None):
        self.status_code, self.chunks = code, chunks
        self.headers = {"Content-Type": "application/pdf", **(headers or {})}
        self.url, self.history = "https://fixture.invalid/file.pdf", []
        self.payload, self.closed = payload, False

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self.payload

    def iter_content(self, chunk_size):
        yield from self.chunks

    def close(self):
        self.closed = True


def cancelled_prefix(tmp_path, resource=None, *, etag='"v1"'):
    resource = resource or item()
    cancelled = False

    def progress(done, total):
        nonlocal cancelled
        cancelled = done >= 4

    response = Response(headers={"Content-Length": "8", **({"ETag": etag} if etag else {})})
    session = Mock(get=Mock(return_value=response))
    # For a signed resource, the API response precedes the streamed body.
    if resource.upload_id:
        session.get.side_effect = [Response(headers={"Content-Type": "application/json"}, payload={"url": "https://cdn.fixture.invalid/first.pdf?signature=old"}), response]
    with pytest.raises(courseware.DownloadCancelledError):
        courseware.download_courseware(session, resource, tmp_path, progress_callback=progress, should_cancel=lambda: cancelled)
    assert (tmp_path / "lesson.pdf.part").read_bytes() == b"AAAA"
    return resource


def test_different_resource_with_same_name_never_uses_old_partial(tmp_path):
    cancelled_prefix(tmp_path)
    response = Response(chunks=(b"BBBBBBBB",), headers={"Content-Length": "8", "ETag": '"v1"'})
    session = Mock(get=Mock(return_value=response))
    target = courseware.download_courseware(session, item(activity_id="other", source_url="https://fixture.invalid/B.pdf"), tmp_path)
    assert target.read_bytes() == b"BBBBBBBB"
    assert target.name == "lesson (2).pdf"
    assert "Range" not in session.get.call_args.kwargs["headers"]
    assert (tmp_path / "lesson.pdf.part").read_bytes() == b"AAAA"


def test_same_resource_uses_etag_if_range_and_cleans_metadata(tmp_path):
    resource = cancelled_prefix(tmp_path)
    response = Response(206, (b"BBBB",), {"Content-Range": "bytes 4-7/8", "ETag": '"v1"'})
    session = Mock(get=Mock(return_value=response))
    target = courseware.download_courseware(session, resource, tmp_path)
    assert target.read_bytes() == b"AAAABBBB"
    assert session.get.call_args.kwargs["headers"]["If-Range"] == '"v1"'
    assert session.get.call_args.kwargs["headers"]["Range"] == "bytes=4-"
    assert set(tmp_path.iterdir()) == {target}


def test_signed_url_refresh_keeps_business_identity(tmp_path):
    resource = cancelled_prefix(tmp_path, item(upload_id="upload", reference_id="reference"))
    session = Mock()
    session.get.side_effect = [Response(headers={"Content-Type": "application/json"}, payload={"url": "https://cdn.fixture.invalid/second.pdf?signature=new"}),
                               Response(206, (b"BBBB",), {"Content-Range": "bytes 4-7/8", "ETag": '"v1"'})]
    assert courseware.download_courseware(session, resource, tmp_path).read_bytes() == b"AAAABBBB"
    assert session.get.call_args.kwargs["headers"]["Range"] == "bytes=4-"


@pytest.mark.parametrize("etag", [None, 'W/"v1"'])
def test_without_strong_validator_retries_full_instead_of_appending(tmp_path, etag):
    resource = cancelled_prefix(tmp_path, etag=etag)
    response = Response(chunks=(b"BBBBBBBB",), headers={"Content-Length": "8"})
    session = Mock(get=Mock(return_value=response))
    assert courseware.download_courseware(session, resource, tmp_path).read_bytes() == b"BBBBBBBB"
    assert "Range" not in session.get.call_args.kwargs["headers"]


@pytest.mark.parametrize("bad_etag", ['"changed"', None])
def test_changed_or_unconfirmed_206_validator_is_never_merged(tmp_path, bad_etag):
    resource = cancelled_prefix(tmp_path)
    bad = Response(206, (b"BBBB",), {"Content-Range": "bytes 4-7/8", **({"ETag": bad_etag} if bad_etag else {})})
    full = Response(chunks=(b"CCCCCCCC",), headers={"Content-Length": "8", "ETag": '"changed"'})
    session = Mock(get=Mock(side_effect=[bad, full]))
    assert courseware.download_courseware(session, resource, tmp_path).read_bytes() == b"CCCCCCCC"
    assert bad.closed and full.closed
    assert "Range" not in session.get.call_args.kwargs["headers"]


def test_unowned_legacy_partial_is_preserved_without_range(tmp_path):
    (tmp_path / "lesson.pdf.part").write_bytes(b"old-unowned")
    session = Mock(get=Mock(return_value=Response(chunks=(b"BBBBBBBB",), headers={"Content-Length": "8"})))
    target = courseware.download_courseware(session, item(), tmp_path)
    assert target.name == "lesson (2).pdf"
    assert target.read_bytes() == b"BBBBBBBB"
    assert (tmp_path / "lesson.pdf.part").read_bytes() == b"old-unowned"
    assert "Range" not in session.get.call_args.kwargs["headers"]


def test_failed_full_replacement_retains_old_validator_and_prefix(tmp_path):
    resource = cancelled_prefix(tmp_path)
    session = Mock(get=Mock(return_value=Response(chunks=(b"bad",), headers={"Content-Length": "8", "ETag": '"v2"'})))
    with pytest.raises(RuntimeError, match="下载不完整"):
        courseware.download_courseware(session, resource, tmp_path)
    partial = tmp_path / "lesson.pdf.part"
    assert partial.read_bytes() == b"AAAA"
    assert courseware._read_resume_metadata(partial)["etag"] == '"v1"'


def test_long_attachment_shortcut_and_numbered_names_keep_extensions(tmp_path):
    long = "课😀" * 130 + ".pdf"
    clean = courseware.sanitize_filename(long)
    assert clean.endswith(".pdf")
    assert len((clean + ".part.resume.json.tmp").encode("utf-16-le")) // 2 <= 255
    resource = item(filename=long, source_url="https://fixture.invalid/platform", upload_status="")
    first = courseware.download_courseware(None, resource, tmp_path)
    second = courseware.download_courseware(None, resource, tmp_path)
    assert first.suffix == second.suffix == ".url"
    assert first != second and second.name.endswith(" (2).url")
    assert first.read_text(encoding="utf-8").startswith("[InternetShortcut]")


def test_extreme_extension_still_respects_numbered_filename_budget(tmp_path):
    name = "a." + "x" * 227
    first = courseware.available_path(tmp_path, name)
    first.write_bytes(b"existing")
    second = courseware.available_path(tmp_path, name)
    assert first != second
    assert len((second.name + ".part.resume.json.tmp").encode("utf-16-le")) // 2 <= 255


def test_directory_probes_are_unique_and_do_not_remove_an_existing_probe(tmp_path):
    home = tmp_path / "home"
    directory = home / ".xmu_rollcall"
    directory.mkdir(parents=True)
    existing = directory / ".test_write"
    existing.write_text("not ours", encoding="utf-8")
    real_factory = config.tempfile.NamedTemporaryFile
    barrier = threading.Barrier(2)
    names = []

    def factory(*args, **kwargs):
        file = real_factory(*args, **kwargs)
        names.append(file.name)
        barrier.wait(timeout=5)
        return file

    with patch.dict(config.os.environ, {"LOCALAPPDATA": str(tmp_path / "local")}, clear=True), \
         patch.object(config.Path, "home", return_value=home), patch.object(config.tempfile, "NamedTemporaryFile", side_effect=factory):
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: config.get_config_dir(), range(2)))
    assert results == [directory, directory]
    assert len(set(names)) == 2 and all(not Path(name).exists() for name in names)
    assert existing.read_text(encoding="utf-8") == "not ours"


@pytest.mark.parametrize("stage", ["data", "quit"])
def test_smtp_submission_or_quit_failure_never_sends_on_second_port(stage):
    class SMTP(smtplib.SMTP):
        ports = []

        def __init__(self, host, port, **kwargs):
            self.port, self.sock, self.file = port, None, None

        def login(self, *_args):
            pass

        def send_message(self, message):
            self.ports.append(self.port)
            if stage == "data":
                raise smtplib.SMTPServerDisconnected("lost DATA acknowledgement")

        def docmd(self, command, args=""):
            return (451, b"QUIT failed") if stage == "quit" else (221, b"bye")

    with patch.object(notifications.smtplib, "SMTP_SSL", SMTP), patch.object(notifications.smtplib, "SMTP", SMTP):
        sender = notifications.QQMailNotifier("sender", "synthetic", "recipient")
        if stage == "data":
            with pytest.raises(notifications.MailDeliveryUncertain):
                sender.send("test", "test")
        else:
            sender.send("test", "test")
    assert SMTP.ports == [465]


def test_mail_unknown_result_has_actionable_feedback_not_password_blame():
    text = notifications.friendly_error_message(notifications.MailDeliveryUncertain("unknown"), "qq_mail")
    assert "结果未知" in text and "收件箱" in text
    assert "授权码" not in text
