"""Login disk failures must not commit half an account switch."""
import copy
import tempfile
import types
import unittest
import requests
from contextlib import ExitStack
from pathlib import Path
from unittest import mock

from xmu_rollcall import config, login_persistence, utils
from xmu_rollcall.desktop_qt import app


class LoginTransactionTests(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.stack.enter_context(mock.patch.object(config, "CONFIG_DIR", self.directory))
        self.stack.enter_context(mock.patch.object(config, "CONFIG_FILE", self.directory / "config.json"))
        config.save_config({
            "accounts": [{"id": 1, "username": "old-user", "password": "old-password", "name": "Old"}],
            "current_account_id": 1,
        })
        self.session = types.SimpleNamespace(close=mock.Mock())
        self.host = types.SimpleNamespace(events=[])
        self.host._emit = self.host.events.append

    def test_cookie_save_failure_rolls_back_new_account_and_current_account(self):
        before = copy.deepcopy(config.load_config())
        with mock.patch.object(login_persistence, "save_session", side_effect=OSError("simulated disk failure")):
            app.DashboardWindow._persist_login_worker(self.host, self.session, "new-user", "new-password", "New", 7)
        self.assertIn("simulated disk failure", self.host.events[-1][4])
        self.assertEqual(before, config.load_config())
        self.assertFalse((self.directory / "2.json").exists())

    def test_cookie_save_failure_restores_existing_credentials_and_cookie(self):
        cookie = self.directory / "1.json"
        cookie.write_bytes(b"previous-cookie")
        before = copy.deepcopy(config.load_config())
        with mock.patch.object(login_persistence, "save_session", side_effect=OSError("simulated disk failure")):
            app.DashboardWindow._persist_login_worker(self.host, self.session, "old-user", "new-password", "New", 7)
        self.assertIn("simulated disk failure", self.host.events[-1][4])
        self.assertEqual(before, config.load_config())
        self.assertEqual(b"previous-cookie", cookie.read_bytes())

    def test_initial_config_failure_does_not_attempt_cookie_save(self):
        before = (self.directory / "config.json").read_bytes()
        with mock.patch.object(login_persistence, "save_config", side_effect=OSError("config locked")), \
             mock.patch.object(login_persistence, "save_session") as saved:
            app.DashboardWindow._persist_login_worker(self.host, self.session, "new-user", "new-password", "New", 7)
        saved.assert_not_called()
        self.assertIn("config locked", self.host.events[-1][4])
        self.assertEqual(before, (self.directory / "config.json").read_bytes())

    def test_real_cookie_writer_failure_rolls_back_instead_of_reporting_success(self):
        before = copy.deepcopy(config.load_config())
        real_replace = utils.os.replace

        def fail_cookie_replace(source, destination):
            if Path(destination) == self.directory / "2.json":
                raise PermissionError("synthetic cookie file lock")
            return real_replace(source, destination)

        with requests.Session() as session, \
             mock.patch.object(utils.os, "replace", side_effect=fail_cookie_replace):
            session.cookies.set("fixture", "synthetic-session", domain="fixture.invalid")
            app.DashboardWindow._persist_login_worker(self.host, session, "new-user", "new-password", "New", 7)

        self.assertIn("synthetic cookie file lock", self.host.events[-1][4])
        self.assertEqual(before, config.load_config())
        self.assertFalse((self.directory / "2.json").exists())
        self.assertFalse((self.directory / "2.json.tmp").exists())

    def test_rollback_failure_keeps_both_original_error_and_recovery_error_visible(self):
        real_save = config.save_config
        calls = 0

        def fail_rollback(state):
            nonlocal calls
            calls += 1
            if calls > 1:
                raise OSError("synthetic rollback lock")
            real_save(state)

        with mock.patch.object(login_persistence, "save_config", side_effect=fail_rollback), \
             mock.patch.object(login_persistence, "save_session", side_effect=OSError("synthetic cookie lock")):
            app.DashboardWindow._persist_login_worker(self.host, self.session, "new-user", "new-password", "New", 7)

        error = self.host.events[-1][4]
        self.assertIn("synthetic cookie lock", error)
        self.assertIn("synthetic rollback lock", error)


class RestoreSessionOwnershipTests(unittest.TestCase):
    def test_failed_restore_closes_its_own_session(self):
        for loaded, verified in [(False, None), (True, None), (True, {}), (True, RuntimeError("network error"))]:
            with self.subTest(loaded=loaded, verified=verified):
                session = types.SimpleNamespace(close=mock.Mock())
                host = types.SimpleNamespace(events=[])
                host._emit = host.events.append
                with mock.patch.object(app, "load_config", return_value={}), \
                     mock.patch.object(app, "get_current_account", return_value={"id": 1}), \
                     mock.patch.object(app, "get_cookies_path", return_value="unused-fixture"), \
                     mock.patch.object(app.requests, "Session", return_value=session), \
                     mock.patch.object(app, "load_session", return_value=loaded), \
                     mock.patch.object(app, "verify_session", **(
                         {"side_effect": verified} if isinstance(verified, Exception) else {"return_value": verified}
                     )):
                    app.DashboardWindow._restore_worker(host, True, 4)
                self.assertEqual("restore_failed", host.events[-1][0])
                session.close.assert_called_once()

    def test_successful_restore_transfers_session_to_event_handler(self):
        session = types.SimpleNamespace(close=mock.Mock())
        host = types.SimpleNamespace(events=[])
        host._emit = host.events.append
        with mock.patch.object(app, "load_config", return_value={}), \
             mock.patch.object(app, "get_current_account", return_value={"id": 1}), \
             mock.patch.object(app, "get_cookies_path", return_value="unused-fixture"), \
             mock.patch.object(app.requests, "Session", return_value=session), \
             mock.patch.object(app, "load_session", return_value=True), \
             mock.patch.object(app, "verify_session", return_value={"name": "Fixture"}):
            app.DashboardWindow._restore_worker(host, True, 4)
        self.assertEqual("login_success", host.events[-1][0])
        self.assertIs(session, host.events[-1][1])
        session.close.assert_not_called()
