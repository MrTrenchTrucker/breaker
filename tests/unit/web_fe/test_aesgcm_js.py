"""Node runner test for web FE AES-GCM JavaScript tests.

- Runs `node --version`, prints it, parses major AND minor
- FAILS (never skips) if node absent or older than 20.19.0
- Runs `node --test` on the *.test.mjs files of the src/test/js folder (named, never the folder) with subprocess
- Fails unless run reports exactly N tests passed and 0 failed (N pinned)
- Stub proofs: stub node in temp dir printing v20.18.0 -> runner fails;
  stub printing v18.0.0 -> runner fails; stub printing v20.19.0 -> passes;
  missing node (empty PATH folder) -> fails
"""

import glob
import os
import re
import subprocess
import tempfile
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

NODE_VERSION_RE = re.compile(r"^v(\d+)\.(\d+)")
NODE_MIN_MAJOR = 20
NODE_MIN_MINOR = 19
PASSED_COUNT = 258


def _run(args, env=None):
    return subprocess.run(args, capture_output=True, text=True, env=env)


def node_major_minor(env=None):
    try:
        result = _run(["node", "--version"], env=env)
    except FileNotFoundError:
        return None
    if result.returncode != 0:
        return None
    m = NODE_VERSION_RE.match(result.stdout.strip())
    if not m:
        return None
    return (int(m.group(1)), int(m.group(2)))


def meets_floor(major, minor):
    return (major > NODE_MIN_MAJOR) or (major == NODE_MIN_MAJOR and minor >= NODE_MIN_MINOR)


def test_files(test_dir):
    """Explicit test files: newer node versions run nothing when given a folder."""
    return sorted(glob.glob(os.path.join(test_dir, "*.test.mjs")))


def node_test_counts(test_dir):
    files = test_files(test_dir)
    if not files:
        return None
    result = _run(["node", "--test"] + files)
    output = result.stdout + result.stderr
    passed = None
    failed = None
    cancelled = None
    skipped = None
    m = re.search(r"^# tests\s+(\d+)\s*$", output, re.MULTILINE)
    if m:
        passed = int(m.group(1))
    m = re.search(r"^# pass\s+(\d+)\s*$", output, re.MULTILINE)
    if m:
        passed = int(m.group(1))
    m = re.search(r"^# fail\s+(\d+)\s*$", output, re.MULTILINE)
    if m:
        failed = int(m.group(1))
    m = re.search(r"^# cancelled\s+(\d+)\s*$", output, re.MULTILINE)
    if m:
        cancelled = int(m.group(1))
    m = re.search(r"^# skipped\s+(\d+)\s*$", output, re.MULTILINE)
    if m:
        skipped = int(m.group(1))
    if passed is None or failed is None:
        return None
    return (passed, failed, cancelled, skipped)


class TestNodeAesGcmJsRunner(unittest.TestCase):
    def test_node_version_meets_floor(self):
        """FAILS (never skips) if node absent or older than 20.19.0."""
        info = node_major_minor()
        self.assertIsNotNone(info, "node not found on PATH")
        major, minor = info
        print(f"node version: v{major}.{minor}")
        self.assertTrue(
            meets_floor(major, minor),
            f"node v{major}.{minor} is below required 20.19.0",
        )

    def test_node_test_runner_pins_count(self):
        """Run `node --test` on src/test/js; fail unless exactly N passed, 0 failed."""
        test_dir = os.path.join(ROOT, "server", "modules", "web-fe", "src", "test", "js")
        self.assertTrue(os.path.isdir(test_dir), f"test directory not found: {test_dir}")
        counts = node_test_counts(test_dir)
        self.assertIsNotNone(counts, "node --test did not report a test count")
        passed, failed, cancelled, skipped = counts
        print(f"node --test: {passed} passed, {failed} failed, {cancelled} cancelled, {skipped} skipped")
        self.assertEqual(passed, PASSED_COUNT, f"expected {PASSED_COUNT} tests, got {passed}")
        self.assertEqual(failed, 0, f"{failed} test(s) failed out of {passed + failed}")
        self.assertEqual(cancelled, 0, f"{cancelled} cancelled")
        self.assertEqual(skipped, 0, f"{skipped} skipped")

    def test_stub_proof_v20_18_0_fails(self):
        """Stub node printing v20.18.0 must make meets_floor return False."""
        with tempfile.TemporaryDirectory() as stub_dir:
            stub = os.path.join(stub_dir, "node")
            with open(stub, "w") as fh:
                fh.write("#!/bin/sh\necho v20.18.0\n")
            os.chmod(stub, 0o755)
            env = dict(os.environ, PATH=stub_dir)
            result = _run(["node", "--version"], env=env)
            self.assertEqual(result.stdout.strip(), "v20.18.0")
            major, minor = node_major_minor(env)
            self.assertFalse(meets_floor(major, minor), "v20.18.0 should be below the 20.19.0 floor")

    def test_stub_proof_v18_0_0_fails(self):
        """Stub node printing v18.0.0 must make meets_floor return False."""
        with tempfile.TemporaryDirectory() as stub_dir:
            stub = os.path.join(stub_dir, "node")
            with open(stub, "w") as fh:
                fh.write("#!/bin/sh\necho v18.0.0\n")
            os.chmod(stub, 0o755)
            env = dict(os.environ, PATH=stub_dir)
            result = _run(["node", "--version"], env=env)
            self.assertEqual(result.stdout.strip(), "v18.0.0")
            major, minor = node_major_minor(env)
            self.assertFalse(meets_floor(major, minor), "v18.0.0 should be below the 20.19.0 floor")

    def test_stub_proof_v20_19_0_passes(self):
        """Stub node printing v20.19.0 must make meets_floor return True."""
        with tempfile.TemporaryDirectory() as stub_dir:
            stub = os.path.join(stub_dir, "node")
            with open(stub, "w") as fh:
                fh.write("#!/bin/sh\necho v20.19.0\n")
            os.chmod(stub, 0o755)
            env = dict(os.environ, PATH=stub_dir)
            result = _run(["node", "--version"], env=env)
            self.assertEqual(result.stdout.strip(), "v20.19.0")
            major, minor = node_major_minor(env)
            self.assertTrue(meets_floor(major, minor), "v20.19.0 should meet the 20.19.0 floor")

    def test_stub_proof_missing_node_fails(self):
        """Stub folder with no node binary: node_major_minor returns None."""
        with tempfile.TemporaryDirectory() as stub_dir:
            env = dict(os.environ, PATH=stub_dir)
            self.assertIsNone(node_major_minor(env), "missing node should return None")


if __name__ == "__main__":
    unittest.main()
