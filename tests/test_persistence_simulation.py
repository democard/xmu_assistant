"""Isolated disk failure, damaged profiles, and concurrent settings transactions."""
import copy
import json
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'xmu-rollcall-cli'))
from xmu_rollcall import config


class PersistenceSimulationTests(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.path = self.directory / 'config.json'
        self.stack.enter_context(patch.object(config, 'CONFIG_DIR', self.directory))
        self.stack.enter_context(patch.object(config, 'CONFIG_FILE', self.path))

    def profile(self, value):
        return {'accounts': [{'id': 1, 'username': 'simulation', 'password': value}],
                'current_account_id': 1,
                'notification_settings': {
                    'pushplus': {'enabled': False, 'token': value},
                    'qq_mail': {'enabled': False, **{key: value for key in
                               ('sender', 'password', 'recipient', 'smtp_host')}}}}

    def assert_empty_credentials(self, loaded):
        self.assertEqual(loaded['accounts'][0]['password'], '')
        notifications = loaded['notification_settings']
        self.assertEqual(notifications['pushplus']['token'], '')
        for field in ('sender', 'password', 'recipient'):
            self.assertEqual(notifications['qq_mail'][field], '')
        self.assertEqual(notifications['qq_mail']['smtp_host'], 'smtp.qq.com')

    def test_damaged_credentials_remain_empty_through_two_restart_cycles(self):
        for value in (None, False, 0, [], {}):
            with self.subTest(value=value):
                self.path.write_text(json.dumps(self.profile(value)), encoding='utf-8')
                for _ in range(2):
                    loaded = config.load_config()
                    self.assert_empty_credentials(loaded)
                    config.save_config(loaded)

    def test_direct_save_sanitizes_null_secrets_without_mutating_the_caller(self):
        original = self.profile(None)
        before = copy.deepcopy(original)
        config.save_config(original)
        self.assertEqual(original, before)
        self.assert_empty_credentials(config.load_config())
        # A literal string password, including "None", is valid user data and must survive.
        config.save_config(self.profile('None'))
        self.assertEqual(config.load_config()['accounts'][0]['password'], 'None')

    def test_failed_atomic_replace_preserves_previous_profile_and_next_save_recovers(self):
        config.save_config(self.profile('synthetic-before'))
        previous = self.path.read_bytes()
        with patch.object(config.os, 'replace', side_effect=PermissionError('simulated file lock')):
            with self.assertRaises(PermissionError):
                config.save_config(self.profile('synthetic-after'))
        self.assertEqual(self.path.read_bytes(), previous)
        self.assertFalse(self.path.with_name('config.json.tmp').exists())
        self.assertEqual(config.load_config()['accounts'][0]['password'], 'synthetic-before')
        config.save_config(self.profile('synthetic-after'))
        self.assertEqual(config.load_config()['accounts'][0]['password'], 'synthetic-after')

    def test_four_parallel_settings_transactions_keep_all_eighty_updates(self):
        config.save_config({'accounts': [], 'simulation_counters': {str(n): 0 for n in range(4)}})
        ready = threading.Barrier(4)

        def update(worker):
            ready.wait(timeout=5)
            for _ in range(20):
                with config.CONFIG_LOCK:
                    state = config.load_config()
                    state['simulation_counters'][str(worker)] += 1
                    config.save_config(state)

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(update, worker) for worker in range(4)]
            for future in futures:
                future.result(timeout=15)
        self.assertEqual(config.load_config()['simulation_counters'], {str(n): 20 for n in range(4)})
        self.assertFalse(self.path.with_name('config.json.tmp').exists())
