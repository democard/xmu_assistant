"""Cookie 克隆、合并和加密缓存不得丢失作用域；仅准备请求，不访问网络。"""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))
from xmu_rollcall import utils


def cookie_header(session, url):
    return session.prepare_request(requests.Request("GET", url)).headers.get("Cookie")


def snapshot(session):
    fields = (
        "name", "value", "version", "port", "port_specified", "domain", "domain_specified",
        "domain_initial_dot", "path", "path_specified", "secure", "expires", "discard",
        "comment", "comment_url", "rfc2109", "_rest",
    )
    return sorted(
        [{field: getattr(cookie, field) for field in fields} for cookie in session.cookies],
        key=lambda value: (value["domain"], value["path"], value["name"]),
    )


def source_session():
    session = requests.Session()
    session.cookies.set("SESSION", "synthetic-lnt", domain="lnt.xmu.edu.cn", path="/api", secure=True,
                        expires=int(time.time()) + 86400, rest={"HttpOnly": None, "SameSite": "Lax"})
    session.cookies.set("SESSION", "synthetic-ids", domain="ids.xmu.edu.cn", path="/", secure=True)
    session.cookies.set("expired", "expired", domain="lnt.xmu.edu.cn", expires=1)
    return session


class CookieScopeTests(unittest.TestCase):
    def assert_scope(self, session):
        self.assertEqual(cookie_header(session, "https://lnt.xmu.edu.cn/api/profile"), "SESSION=synthetic-lnt")
        self.assertEqual(cookie_header(session, "https://ids.xmu.edu.cn/"), "SESSION=synthetic-ids")
        for url in (
            "https://lnt.xmu.edu.cn/other", "https://lnt.xmu.edu.cn/apix",
            "http://lnt.xmu.edu.cn/api/profile", "http://files.example.test/api/profile",
            "https://files.example.test/api/profile", "https://other.xmu.edu.cn/api/profile",
        ):
            with self.subTest(url=url):
                self.assertIsNone(cookie_header(session, url))

    def test_clone_preserves_metadata_and_scope(self):
        source = source_session()
        cloned = utils.clone_session(source)
        self.assertEqual(snapshot(cloned), snapshot(source))
        self.assert_scope(cloned)

    def test_clone_does_not_share_cookie_extensions(self):
        source = source_session()
        cloned = utils.clone_session(source)
        clone_cookie = next(iter(cloned.cookies))
        clone_cookie.value = "changed"
        clone_cookie.set_nonstandard_attr("SameSite", "None")
        source_cookie = next(iter(source.cookies))
        self.assertEqual(source_cookie.value, "synthetic-lnt")
        self.assertEqual(source_cookie.get_nonstandard_attr("SameSite"), "Lax")

    def test_same_name_different_paths_remain_distinct_after_clone_and_restore(self):
        source = requests.Session()
        source.cookies.set("SESSION", "root", domain="lnt.xmu.edu.cn", path="/", secure=True)
        source.cookies.set("SESSION", "api", domain="lnt.xmu.edu.cn", path="/api", secure=True)
        cloned = utils.clone_session(source)
        self.assertEqual(cookie_header(cloned, "https://lnt.xmu.edu.cn/api/profile"), "SESSION=api; SESSION=root")
        self.assertEqual(cookie_header(cloned, "https://lnt.xmu.edu.cn/other"), "SESSION=root")
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "session.json")
            utils.save_session(cloned, path)
            restored = requests.Session()
            self.assertTrue(utils.load_session(restored, path))
        self.assertEqual(snapshot(restored), snapshot(source))

    def test_cookie_domain_flags_and_encrypted_cache_roundtrip(self):
        source = source_session()
        host_cookie = requests.cookies.create_cookie("host", "one", domain="lnt.xmu.edu.cn", secure=True)
        host_cookie.domain_specified = False
        host_cookie.path_specified = False
        source.cookies.set_cookie(host_cookie)
        source.cookies.set("parent", "shared", domain=".xmu.edu.cn", path="/", secure=True)
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "session.json")
            utils.save_session(source, path)
            restored = requests.Session()
            self.assertTrue(utils.load_session(restored, path))
            self.assertEqual(snapshot(restored), snapshot(source))
            self.assertFalse(Path(path + ".tmp").exists())
        self.assertIn("parent=shared", cookie_header(restored, "https://ids.xmu.edu.cn/"))
        self.assertNotIn("host=one", cookie_header(restored, "https://ids.xmu.edu.cn/"))

    def test_merge_does_not_share_cookie_or_rest_object(self):
        source = source_session()
        target = requests.Session()
        utils.merge_cookies(target, source)
        self.assertEqual(snapshot(target), snapshot(source))
        self.assert_scope(target)
        cookie = next(iter(source.cookies))
        cookie.value = "changed"
        cookie.set_nonstandard_attr("SameSite", "None")
        self.assertNotEqual(snapshot(target), snapshot(source))
        target_cookie = next(iter(target.cookies))
        self.assertEqual(target_cookie.value, "synthetic-lnt")
        self.assertEqual(target_cookie.get_nonstandard_attr("SameSite"), "Lax")

    def test_merge_updates_only_matching_domain_path_name(self):
        target = source_session()
        worker = requests.Session()
        worker.cookies.set("SESSION", "renewed", domain="lnt.xmu.edu.cn", path="/api", secure=True)
        utils.merge_cookies(target, worker)
        self.assertEqual(cookie_header(target, "https://lnt.xmu.edu.cn/api/profile"), "SESSION=renewed")
        self.assertEqual(cookie_header(target, "https://ids.xmu.edu.cn/"), "SESSION=synthetic-ids")

    def test_save_restore_preserves_duplicate_names_and_metadata(self):
        source = source_session()
        with tempfile.TemporaryDirectory() as directory, patch.object(utils.secrets, "protect", side_effect=lambda text: text):
            path = str(Path(directory) / "session.json")
            utils.save_session(source, path)
            stored = json.loads(Path(path).read_text(encoding="utf-8"))
            self.assertEqual(stored.get("version"), 2)
            self.assertIsInstance(stored.get("cookies"), list)
            restored = requests.Session()
            self.assertTrue(utils.load_session(restored, path))
            self.assertEqual(snapshot(restored), snapshot(source))
            self.assert_scope(restored)

    def test_legacy_cache_scoped_to_secure_lnt_without_guessing_identity_domains(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.json"
            path.write_text('{"legacy": "synthetic"}', encoding="utf-8")
            restored = requests.Session()
            self.assertTrue(utils.load_session(restored, str(path)))
            self.assertEqual(restored.cookies.get("legacy"), "synthetic")
            self.assertEqual(cookie_header(restored, "https://lnt.xmu.edu.cn/api/profile"), "legacy=synthetic")
            for url in (
                "http://lnt.xmu.edu.cn/api/profile", "https://ids.xmu.edu.cn/",
                "https://other.xmu.edu.cn/", "https://files.example.test/",
            ):
                with self.subTest(url=url):
                    self.assertIsNone(cookie_header(restored, url))

    def test_malformed_or_unknown_version_never_replaces_active_cookiejar(self):
        for value in (
            {"version": 3, "cookies": []}, {"version": 2, "cookies": "invalid"},
            {"version": 2, "cookies": [{"name": "partial"}]}, {"legacy": ["invalid"]},
        ):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "session.json"
                path.write_text(json.dumps(value), encoding="utf-8")
                target = source_session()
                before = snapshot(target)
                with patch.object(utils, "_diag_log"):
                    self.assertFalse(utils.load_session(target, str(path)))
                self.assertEqual(snapshot(target), before)

    def test_invalid_cookie_metadata_does_not_partially_replace_current_session(self):
        source = source_session()
        with tempfile.TemporaryDirectory() as directory, patch.object(utils.secrets, "protect", side_effect=lambda text: text):
            path = Path(directory) / "session.json"
            utils.save_session(source, str(path))
            valid = json.loads(path.read_text(encoding="utf-8"))
            for field, value in (("secure", "false"), ("expires", "never"), ("rest", []), ("domain", None)):
                with self.subTest(field=field):
                    payload = json.loads(json.dumps(valid))
                    payload["cookies"][-1][field] = value
                    path.write_text(json.dumps(payload), encoding="utf-8")
                    target = source_session()
                    before = snapshot(target)
                    with patch.object(utils, "_diag_log"):
                        self.assertFalse(utils.load_session(target, str(path)))
                    self.assertEqual(snapshot(target), before)

    def test_legacy_cookie_names_can_match_schema_field_names(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "session.json"
            path.write_text('{"version":"token-one","cookies":"token-two"}', encoding="utf-8")
            restored = requests.Session()
            self.assertTrue(utils.load_session(restored, str(path)))
            self.assertEqual(restored.cookies.get("version"), "token-one")
            self.assertEqual(restored.cookies.get("cookies"), "token-two")


if __name__ == "__main__":
    unittest.main()
