"""End-to-end liveness check for the deploy module's health-check.sh.

Runs a local http.server on 127.0.0.1 port 0 (stdlib only, a thread, shut down
in tearDown) and drives health-check.sh against it.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_health_check.py'
"""
import http.server
import os
import socket
import subprocess
import sys
import threading
import time
import unittest

SCRIPT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', '..', '..', '..',
    'server', 'modules', 'deploy', 'scripts', 'health-check.sh',
))


def find_free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
    s.close()
    return port


class HealthHandler(http.server.BaseHTTPRequestHandler):
    """Handler that answers by path: / ok, /whisper ok-with-extra-fields,
    /bad-body 200-wrong-body, /500, /301, /slow (sleeps past timeout)."""
    auth_headers = []

    def do_GET(self):
        HealthHandler.auth_headers.append(self.headers.get('Authorization'))

        if self.path == '/slow':
            time.sleep(10)
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return

        if self.path == '/500':
            self.send_response(500)
            self.end_headers()
            return

        if self.path == '/301':
            self.send_response(301)
            self.send_header('Location', '/ok')
            self.end_headers()
            return

        if self.path == '/bad-body':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status":"error"}')
            return

        if self.path == '/whisper':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(
                b'{"status":"ok","forwarding_to":"http://whisper-x:9000","uptime":"0h0m0s"}')
            return

        if self.path == '/slow6':
            time.sleep(6)
            try:
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b'{"status":"ok"}')
            except (BrokenPipeError, ConnectionResetError):
                pass
            return

        if self.path == '/201':
            self.send_response(201)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')
            return

        if self.path == '/204':
            self.send_response(204)
            self.end_headers()
            return

        # default: plain ok
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def log_message(self, format, *args):
        pass  # suppress request logs


def start_server():
    port = find_free_port()
    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), HealthHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, port


class HealthCheckTest(unittest.TestCase):
    server = None
    port = None

    @classmethod
    def setUpClass(cls):
        cls.server, cls.port = start_server()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        HealthHandler.auth_headers = []

    def run_check(self, *extra_args):
        base_url = 'http://127.0.0.1:%d/' % self.port
        cmd = ['sh', SCRIPT, '--url', base_url, '--timeout', '2'] + list(extra_args)
        return subprocess.run(cmd, capture_output=True, text=True)

    # --- happy path -----------------------------------------------------

    def test_ok_200_right_body_prints_ok_and_exits_zero(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 0)
        self.assertIn(' ok', result.stdout)
        self.assertNotIn('bad', result.stdout)

    def test_ok_whisper_style_extra_field_body_prints_ok_and_exits_zero(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/whisper' % self.port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn(' ok', result.stdout)

    # --- failure paths --------------------------------------------------

    def test_bad_body_200_wrong_body_prints_bad_body_and_exits_one(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/bad-body' % self.port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('bad-body', result.stdout)

    def test_bad_status_500_prints_bad_status_and_exits_one(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/500' % self.port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('bad-status 500', result.stdout)

    def test_bad_status_301_prints_bad_status_and_exits_one(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/301' % self.port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('bad-status 301', result.stdout)

    def test_unreachable_closed_port_prints_unreachable_and_exits_one(self):
        dead_port = find_free_port()
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/' % dead_port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('unreachable', result.stdout)

    def test_unreachable_slow_answer_past_timeout_prints_unreachable(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/slow' % self.port, '--timeout', '1']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('unreachable', result.stdout)

    def test_two_urls_one_good_one_bad_exits_one(self):
        cmd = ['sh', SCRIPT,
               '--url', 'http://127.0.0.1:%d/' % self.port,
               '--url', 'http://127.0.0.1:%d/bad-body' % self.port,
               '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(' ok', result.stdout)
        self.assertIn('bad-body', result.stdout)

    # --- default timeout and only 200 is ok ------------------------------

    def test_default_timeout_is_five_seconds(self):
        url = 'http://127.0.0.1:%d/slow6' % self.port
        started = subprocess.run(['sh', SCRIPT, '--url', url],
                                 capture_output=True, text=True)
        self.assertEqual(started.returncode, 1)
        self.assertIn('unreachable', started.stdout)
        longer = subprocess.run(['sh', SCRIPT, '--url', url, '--timeout', '10'],
                                capture_output=True, text=True)
        self.assertEqual(longer.returncode, 0)
        self.assertIn(' ok', longer.stdout)

    def test_a_201_with_the_right_body_is_bad_status(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/201' % self.port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('bad-status 201', result.stdout)

    def test_a_204_is_bad_status(self):
        cmd = ['sh', SCRIPT, '--url',
               'http://127.0.0.1:%d/204' % self.port, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('bad-status 204', result.stdout)

    # --- usage errors ---------------------------------------------------

    def test_usage_no_url_exits_two(self):
        cmd = ['sh', SCRIPT, '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_usage_bad_scheme_exits_two(self):
        cmd = ['sh', SCRIPT, '--url', 'ftp://example.com/', '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_usage_unknown_option_exits_two(self):
        cmd = ['sh', SCRIPT, '--url', 'http://127.0.0.1:%d/' % self.port,
               '--bogus', '--timeout', '2']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_usage_bad_timeout_zero_exits_two(self):
        cmd = ['sh', SCRIPT, '--url', 'http://127.0.0.1:%d/' % self.port,
               '--timeout', '0']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_usage_bad_timeout_sixty_one_exits_two(self):
        cmd = ['sh', SCRIPT, '--url', 'http://127.0.0.1:%d/' % self.port,
               '--timeout', '61']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_usage_bad_timeout_not_a_number_exits_two(self):
        cmd = ['sh', SCRIPT, '--url', 'http://127.0.0.1:%d/' % self.port,
               '--timeout', 'abc']
        result = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    # --- script properties ----------------------------------------------

    def test_no_authorization_header_is_sent(self):
        self.run_check()
        for header in HealthHandler.auth_headers:
            self.assertIsNone(header)

    def test_script_text_has_no_write_command(self):
        with open(SCRIPT) as fh:
            text = fh.read()
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.startswith('>') or ' > ' in stripped or ' >> ' in stripped:
                self.fail('write redirect found: %s' % stripped)
        self.assertNotIn('sed -i', text)
        import re
        self.assertNotRegex(text, r'\brm\b')
