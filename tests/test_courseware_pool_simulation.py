"""Real loopback HTTP and concurrent detail workers, with synthetic accounts."""
import json
import sys
import threading
import unittest
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'xmu-rollcall-cli'))
from xmu_rollcall import courseware
from xmu_rollcall.utils import SessionExpiredError


@contextmanager
def course_server(outcome='success', count=16):
    first_wave = threading.Barrier(8)
    detail_ids = []
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            status = 200
            if self.path.endswith('/courseware-activities'):
                payload = [{'id': n, 'type': 'material', 'title': f'Lecture {n}'}
                           for n in range(count, 0, -1)]
            elif self.path.endswith('/modules'):
                payload = {'modules': []}
            else:
                activity_id = int(self.path.rsplit('/', 1)[-1])
                with lock:
                    detail_ids.append(activity_id)
                # No timing guesses: all eight real workers must enter HTTP before proceeding.
                if activity_id <= 8:
                    first_wave.wait(timeout=10)
                payload = {'id': activity_id, 'title': f'Lecture {activity_id}', 'uploads': []}
                if activity_id == 1 and outcome == 'expired':
                    status = 401
            body = json.dumps(payload).encode()
            if self.path.endswith('/activities/1') and outcome == 'bad-json':
                body = b'{invalid'
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            if '/activities/' in self.path:
                self.send_header('Set-Cookie', 'worker-only=synthetic; Path=/')
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    class Server(ThreadingHTTPServer):
        request_queue_size = 16

    server = Server(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.02})
    thread.start()
    try:
        with patch.object(courseware, 'base_url', f'http://127.0.0.1:{server.server_port}'):
            yield detail_ids
    finally:
        first_wave.abort()
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive(), 'loopback server did not stop'


class CoursewarePoolSimulationTests(unittest.TestCase):
    def run_scenario(self, outcome, repeats=1, count=16):
        real_clone = courseware.clone_session
        workers = []
        lock = threading.Lock()

        def tracked_clone(source):
            worker = real_clone(source)
            worker.close = Mock(wraps=worker.close)
            with lock:
                workers.append(worker)
            return worker

        with requests.Session() as source, course_server(outcome, count) as received:
            source.trust_env = False
            source.cookies.set('account', 'synthetic', domain='127.0.0.1', path='/')
            source.close = Mock(wraps=source.close)
            try:
                with patch.object(courseware, 'clone_session', side_effect=tracked_clone):
                    for _ in range(repeats):
                        if outcome == 'expired':
                            with self.assertRaises(SessionExpiredError):
                                courseware.fetch_courseware(source, 'simulation')
                        else:
                            rows = courseware.fetch_courseware(source, 'simulation')
                            self.assertEqual([row.activity_id for row in rows],
                                             [str(n) for n in range(1, count + 1)])
                            if outcome == 'bad-json':
                                self.assertEqual(rows[0].upload_status, 'entry')
                                self.assertIn('JSON', rows[0].media_type)
                self.assertEqual(source.close.call_count, 0, 'caller owns the source session')
                self.assertIsNone(source.cookies.get('worker-only'))
                if count:
                    self.assertGreaterEqual(len(workers), 8)
                    self.assertTrue(received)
                else:
                    self.assertEqual(workers, [])
                self.assertEqual([worker.close.call_count for worker in workers], [1] * len(workers))
            finally:
                # Close on a failing red test too, so the test itself leaves no sockets behind.
                for worker in workers:
                    if not worker.close.called:
                        worker.close()

    def test_repeated_refresh_reuses_each_worker_then_releases_every_owned_session(self):
        self.run_scenario('success', repeats=3)

    def test_expired_detail_releases_sessions_after_all_workers_stop(self):
        self.run_scenario('expired')

    def test_bad_detail_falls_back_without_leaking_worker_pools(self):
        self.run_scenario('bad-json')

    def test_empty_course_creates_no_detail_sessions(self):
        self.run_scenario('success', count=0)
