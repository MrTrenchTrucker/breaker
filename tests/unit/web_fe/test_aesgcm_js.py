"""Node runner test for web FE AES-GCM JavaScript tests.

- Runs `node --version`, prints it, parses major AND minor
- FAILS (never skips) if node absent or older than 20.19.0
- Runs the web-fe node tests through tools/assemble_weblibs_test_tree.py, which assembles a temp tree with the
  build-output layout (the vendored libraries live in shared/modules/web-libs) and runs `node --test` on the
  named *.test.mjs files there
- Fails unless run reports exactly N tests passed and 0 failed (N pinned)
- Stub proofs: stub node in temp dir printing v20.18.0 -> runner fails;
  stub printing v18.0.0 -> runner fails; stub printing v20.19.0 -> passes;
  missing node (empty PATH folder) -> fails
"""

import glob
import os
import re
import subprocess
import sys
import tempfile
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

NODE_VERSION_RE = re.compile(r"^v(\d+)\.(\d+)")
NODE_MIN_MAJOR = 20
NODE_MIN_MINOR = 19
# The count of the whole src/test/js suite, pinned so a dropped test file is caught.
# 319 = 258 on the base tree + 6 devices-net + 33 devices flow + 22 devices view.
PASSED_COUNT = 319


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


def _test_files(test_dir):
    """Explicit test files: newer node versions run nothing when given a folder."""
    return sorted(glob.glob(os.path.join(test_dir, "*.test.mjs")))


HELPER = os.path.join(ROOT, "tools", "assemble_weblibs_test_tree.py")


def node_test_counts(test_dir):
    files = _test_files(test_dir)
    if not files:
        return None
    result = _run([sys.executable, HELPER])
    output = result.stdout + result.stderr
    return parse_node_test_counts(output)


def parse_node_test_counts(output):
    """Parse a `node --test` summary block into (passed, failed, cancelled, skipped).

    Accepts both reporter prefixes: the legacy `# ` and the Node v24 `ℹ `
    (U+2139 INFORMATION SOURCE) followed by a space. Returns None when the
    tests or fail counts are missing so the runner cannot silently pass.
    """
    passed = None
    failed = None
    cancelled = None
    skipped = None

    patterns = {
        "tests": r"^(?:# |ℹ )tests\s+(\d+)\s*$",
        "pass": r"^(?:# |ℹ )pass\s+(\d+)\s*$",
        "fail": r"^(?:# |ℹ )fail\s+(\d+)\s*$",
        "cancelled": r"^(?:# |ℹ )cancelled\s+(\d+)\s*$",
        "skipped": r"^(?:# |ℹ )skipped\s+(\d+)\s*$",
    }
    for label, pattern in patterns.items():
        m = re.search(pattern, output, re.MULTILINE)
        if not m:
            continue
        value = int(m.group(1))
        if label == "tests":
            passed = value
        elif label == "pass":
            passed = value
        elif label == "fail":
            failed = value
        elif label == "cancelled":
            cancelled = value
        elif label == "skipped":
            skipped = value

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
        """Run the web-fe node tests in the assembled tree; fail unless exactly N passed, 0 failed."""
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

    def test_parse_node_test_counts_old_format(self):
        """Legacy `# ` reporter prefix block parses to the pinned counts."""
        output = (
            "TAP version 13\n"
            "# tests 258\n"
            "# pass 258\n"
            "# fail 0\n"
            "# cancelled 0\n"
            "# skipped 0\n"
            "# 1..258\n"
        )
        self.assertEqual(parse_node_test_counts(output), (258, 0, 0, 0))

    def test_parse_node_test_counts_new_format(self):
        """Node v24 `ℹ ` reporter prefix block parses to the pinned counts."""
        output = (
            "TAP version 13\n"
            "ℹ tests 258\n"
            "ℹ pass 258\n"
            "ℹ fail 0\n"
            "ℹ cancelled 0\n"
            "ℹ skipped 0\n"
            "# 1..258\n"
        )
        self.assertEqual(parse_node_test_counts(output), (258, 0, 0, 0))

    def test_parse_node_test_counts_missing_fail_returns_none(self):
        """A summary block with no `fail` line returns None, never a silent pass."""
        output = (
            "TAP version 13\n"
            "# tests 258\n"
            "# pass 258\n"
            "# cancelled 0\n"
            "# skipped 0\n"
        )
        self.assertIsNone(parse_node_test_counts(output))

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
