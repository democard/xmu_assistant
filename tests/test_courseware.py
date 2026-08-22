from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.courseware import (  # noqa: E402
    CoursewareItem,
    _get_modules_cached,
    available_path,
    download_courseware,
    fetch_courses,
    fetch_courseware,
    reset_modules_cache,
    sanitize_filename,
)


def make_item(**overrides) -> CoursewareItem:
    values = {
        "course_id": "1",
        "activity_id": "2",
        "activity_title": "Lecture",
        "activity_type": "material",
        "module_name": "PPT",
        "syllabus_name": "",
        "upload_id": "3",
        "reference_id": "4",
        "filename": "lecture.pdf",
        "size": 2048,
        "media_type": "document",
        "published_at": "2026-01-01T00:00:00Z",
        "upload_status": "ready",
        "allow_download": True,
    }
    values.update(overrides)
    return CoursewareItem(**values)


class FakeResponse:
    status_code = 200
    headers = {"Content-Type": "application/pdf"}

    def raise_for_status(self):
        return None

    def iter_content(self, chunk_size):
        yield b"part-one"
        yield b"part-two"

    def close(self):
        return None


class FakeSignedUrlResponse:
    status_code = 200
    headers = {"Content-Type": "application/json"}

    def raise_for_status(self):
        return None

    def json(self):
        return {"url": "https://storage.example.test/signed-file"}


class FakeSession:
    def __init__(self):
        self.urls = []

    def get(self, url, **kwargs):
        self.urls.append(url)
        if url.endswith("/api/uploads/reference/4/url"):
            return FakeSignedUrlResponse()
        if url == "https://storage.example.test/signed-file":
            return FakeResponse()
        raise AssertionError(f"Unexpected request: {url}")


class CoursewareTests(unittest.TestCase):
    def test_download_status_respects_platform_permission(self):
        self.assertEqual(make_item().download_status, "可下载")
        self.assertEqual(make_item(allow_download=False).download_status, "可下载（平台版权保护）")
        self.assertEqual(make_item(upload_id="").download_status, "已保存入口")

    def test_sanitize_filename_for_windows(self):
        self.assertEqual(sanitize_filename('a<b>:c?.pdf'), "a_b__c_.pdf")
        self.assertEqual(sanitize_filename("CON.txt"), "_CON.txt")

    def test_available_path_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            directory = Path(temp_dir)
            (directory / "lecture.pdf").write_bytes(b"existing")
            self.assertEqual(available_path(directory, "lecture.pdf").name, "lecture (2).pdf")

    def test_download_streams_to_final_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            session = FakeSession()
            target = download_courseware(session, make_item(), temp_dir)
            self.assertEqual(target.read_bytes(), b"part-onepart-two")
            self.assertFalse(target.with_name(target.name + ".part").exists())
            self.assertTrue(session.urls[0].endswith("/api/uploads/reference/4/url"))
            self.assertEqual(session.urls[1], "https://storage.example.test/signed-file")

    def test_download_allows_copyright_protected_item(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            session = FakeSession()
            target = download_courseware(session, make_item(allow_download=False), temp_dir)
            self.assertEqual(target.read_bytes(), b"part-onepart-two")
            self.assertTrue(session.urls[0].endswith("/api/uploads/reference/4/url"))

    def test_fetch_courses_keeps_platform_order_and_filter_metadata(self):
        class JsonResponse:
            status_code = 200

            def raise_for_status(self):
                return None

            def json(self):
                return {"courses": [
                    {
                        "id": "c2",
                        "name": "Second Course",
                        "academic_year": {"name": "2025-2026"},
                        "semester": {"code": "2025-2026-2"},
                    },
                    {
                        "id": "c1",
                        "name": "First Course",
                        "academic_year": {"name": "2025-2026"},
                        "semester": {"code": "2025-2026-1"},
                    },
                ]}

        class CourseListSession:
            def get(self, url, **kwargs):
                return JsonResponse()

        courses, _source = fetch_courses(CourseListSession())
        self.assertEqual([course.course_id for course in courses], ["c2", "c1"])
        self.assertEqual(courses[0].term, "2025-2026")
        self.assertEqual(courses[0].semester_code, "2025-2026-2")
        self.assertIn("2025-2026", courses[0].search_text)

    def test_homework_is_filtered_before_detail_request(self):
        class JsonResponse:
            status_code = 200

            def __init__(self, payload):
                self.payload = payload

            def raise_for_status(self):
                return None

            def json(self):
                return self.payload

        class TrackingSession:
            def __init__(self):
                self.urls = []

            def get(self, url, **kwargs):
                self.urls.append(url)
                if url.endswith("/courseware-activities"):
                    return JsonResponse([
                        {"id": 10, "type": "homework", "title": "Assignment"},
                        {"id": 11, "type": "material", "title": "Slides"},
                    ])
                if url.endswith("/modules"):
                    return JsonResponse({"modules": []})
                if url.endswith("/activities/11"):
                    return JsonResponse({
                        "id": 11,
                        "type": "material",
                        "title": "Slides",
                        "uploads": [],
                    })
                raise AssertionError(f"Unexpected request: {url}")

        session = TrackingSession()
        items = fetch_courseware(session, "1")
        self.assertEqual([item.activity_id for item in items], ["11"])
        self.assertFalse(any(url.endswith("/activities/10") for url in session.urls))

    def test_courseware_details_follow_module_order(self):
        class JsonResponse:
            status_code = 200

            def __init__(self, payload):
                self.payload = payload

            def raise_for_status(self):
                return None

            def json(self):
                return self.payload

        class MultiActivitySession:
            def __init__(self):
                self.urls = []

            def get(self, url, **kwargs):
                self.urls.append(url)
                if url.endswith("/courseware-activities"):
                    return JsonResponse([
                        {"id": 22, "type": "material", "title": "B", "module_id": "m2", "syllabus_id": 0},
                        {"id": 21, "type": "material", "title": "A", "module_id": "m1", "syllabus_id": 0},
                        {"id": 23, "type": "homework", "title": "Homework"},
                    ])
                if url.endswith("/modules"):
                    return JsonResponse({"modules": [
                        {"id": "m1", "name": "第一章", "syllabuses": []},
                        {"id": "m2", "name": "第二章", "syllabuses": []},
                    ]})
                if url.endswith("/activities/21"):
                    return JsonResponse({
                        "id": 21,
                        "type": "material",
                        "title": "A",
                        "module_id": "m1",
                        "syllabus_id": 0,
                        "uploads": [{
                            "id": "u21",
                            "reference_id": "r21",
                            "name": "a.pdf",
                            "size": 10,
                            "status": "ready",
                            "allow_download": True,
                        }],
                    })
                if url.endswith("/activities/22"):
                    return JsonResponse({
                        "id": 22,
                        "type": "material",
                        "title": "B",
                        "module_id": "m2",
                        "syllabus_id": 0,
                        "uploads": [{
                            "id": "u22",
                            "reference_id": "r22",
                            "name": "b.pdf",
                            "size": 20,
                            "status": "ready",
                            "allow_download": False,
                        }],
                    })
                raise AssertionError(f"Unexpected request: {url}")

        session = MultiActivitySession()
        items = fetch_courseware(session, "1")
        self.assertEqual([item.activity_id for item in items], ["21", "22"])
        self.assertEqual(items[1].download_status, "可下载（平台版权保护）")
        self.assertFalse(any(url.endswith("/activities/23") for url in session.urls))

    def test_exam_detail_404_does_not_block_courseware(self):
        class JsonResponse:
            def __init__(self, payload, status_code=200):
                self.payload = payload
                self.status_code = status_code

            def raise_for_status(self):
                if self.status_code >= 400:
                    import requests
                    raise requests.HTTPError(f"{self.status_code} error")

            def json(self):
                return self.payload

        class MixedActivitySession:
            def get(self, url, **kwargs):
                if url.endswith("/courseware-activities"):
                    return JsonResponse([
                        {"id": 33876, "type": "exam", "title": "Optional exam"},
                        {"id": 11, "type": "material", "title": "Slides"},
                    ])
                if url.endswith("/modules"):
                    return JsonResponse({"modules": []})
                if url.endswith("/activities/33876"):
                    return JsonResponse({}, 404)
                if url.endswith("/activities/11"):
                    return JsonResponse({
                        "id": 11,
                        "type": "material",
                        "title": "Slides",
                        "uploads": [],
                    })
                raise AssertionError(f"Unexpected request: {url}")

        items = fetch_courseware(MixedActivitySession(), "102746")
        self.assertEqual([item.activity_id for item in items], ["11"])

    def test_download_saves_entry_for_link_without_attachment(self):
        item = make_item(
            activity_id="99",
            activity_title="Course page",
            activity_type="web_link",
            upload_id="",
            reference_id="",
            filename="",
            upload_status="",
            source_url="https://example.test/course/page",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            target = download_courseware(FakeSession(), item, temp_dir)
            self.assertEqual(target.suffix, ".url")
            self.assertIn("https://example.test/course/page", target.read_text(encoding="utf-8"))

    def test_download_direct_video_url_without_attachment(self):
        class DirectVideoSession:
            def get(self, url, **kwargs):
                self.url = url
                return FakeResponse()

        item = make_item(
            activity_id="100",
            activity_title="Video",
            activity_type="online_video",
            upload_id="",
            reference_id="",
            filename="video.mp4",
            upload_status="",
            source_url="https://media.example.test/video.mp4",
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            session = DirectVideoSession()
            target = download_courseware(session, item, temp_dir)
            self.assertEqual(session.url, "https://media.example.test/video.mp4")
            self.assertEqual(target.read_bytes(), b"part-onepart-two")


class ModulesCacheTests(unittest.TestCase):
    """modules TTL 缓存：TTL 内同一课程只发一次章节请求，不同课程互不共享。"""

    def setUp(self):
        reset_modules_cache()

    def tearDown(self):
        reset_modules_cache()

    class _JsonResponse:
        status_code = 200

        def __init__(self, payload):
            self._payload = payload

        def raise_for_status(self):
            return None

        def json(self):
            return self._payload

    class _CountingSession:
        def __init__(self):
            self.module_calls = 0

        def get(self, url, **kwargs):
            if "/modules" in url:
                self.module_calls += 1
                return ModulesCacheTests._JsonResponse({"modules": []})
            return ModulesCacheTests._JsonResponse({})

    def test_same_course_hits_cache_within_ttl(self):
        sess = self._CountingSession()
        first = _get_modules_cached(sess, "course-1")
        second = _get_modules_cached(sess, "course-1")
        self.assertIs(first, second)
        self.assertEqual(1, sess.module_calls)

    def test_different_courses_have_independent_entries(self):
        sess = self._CountingSession()
        _get_modules_cached(sess, "course-1")
        _get_modules_cached(sess, "course-2")
        self.assertEqual(2, sess.module_calls)


class ResumeDownloadTests(unittest.TestCase):
    """断点续传：.part 已有字节时带 Range 续传（206 追加）；服务端忽略 Range 返回 200 时全量覆盖。"""

    class _BodyResponse:
        def __init__(self, status_code, body):
            self.status_code = status_code
            self.headers = {"Content-Type": "application/pdf"}
            self._body = body

        def raise_for_status(self):
            return None

        def iter_content(self, chunk_size):
            yield self._body

        def close(self):
            return None

    class _RangeSession:
        def __init__(self):
            self.range_header = None

        def get(self, url, **kwargs):
            self.range_header = (kwargs.get("headers") or {}).get("Range")
            if self.range_header:
                # 服务端支持 Range：返回剩余部分
                return ResumeDownloadTests._BodyResponse(206, b"+resumed")
            return ResumeDownloadTests._BodyResponse(200, b"part-onepart-two")

    class _RangeIgnoringSession:
        """服务端不支持 Range：忽略 Range 头返回 200 全量。"""

        def __init__(self):
            self.range_header = None

        def get(self, url, **kwargs):
            self.range_header = (kwargs.get("headers") or {}).get("Range")
            return ResumeDownloadTests._BodyResponse(200, b"part-onepart-two")

    @staticmethod
    def _direct_item():
        return make_item(upload_id="", source_url="https://storage.example.test/file.pdf", filename="file.pdf")

    def test_resume_appends_partial_with_206(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            partial = Path(temp_dir) / "file.pdf.part"
            partial.write_bytes(b"part-one")
            sess = self._RangeSession()
            target = download_courseware(sess, self._direct_item(), temp_dir)
            self.assertEqual(b"part-one+resumed", target.read_bytes())
            self.assertEqual("bytes=8-", sess.range_header)
            self.assertFalse(partial.exists())

    def test_server_ignoring_range_restarts_from_scratch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            partial = Path(temp_dir) / "file.pdf.part"
            partial.write_bytes(b"stale-bytes")
            sess = self._RangeIgnoringSession()
            target = download_courseware(sess, self._direct_item(), temp_dir)
            # 200 全量覆盖：不得把旧 .part 字节重复拼接
            self.assertEqual(b"part-onepart-two", target.read_bytes())
            self.assertIsNotNone(sess.range_header)
            self.assertFalse(partial.exists())


if __name__ == "__main__":
    unittest.main()
