"""Contract test for the server/modules/web-fe module.

Runs the node test suite via subprocess and asserts the results.
Also checks browser-safety of the derivation.js source and
verifies parity with an independent Python HKDF implementation.
"""
import hashlib
import hmac
import os
import re
import subprocess
import unittest

import contract_support

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DERIVATION_JS = os.path.join(ROOT, "server/modules/web-fe/src/main/resources/static/derivation.js")
DERIVATION_TEST_JS = os.path.join(ROOT, "server/modules/web-fe/src/test/js/derivation.test.js")

NODE_TEST_NAMES = [
    "split of the counting input gives the expected key-encryption key",
    "split of the counting input gives the expected verifier",
    "memory at the floor and at the ceiling is accepted",
    "iterations at the floor and at the ceiling are accepted",
    "parallelism at the floor and at the ceiling is accepted",
    "output length 32 is accepted",
    "salt of 16 bytes is accepted",
    "version 1 is accepted",
    "memory below the floor is refused",
    "memory above the ceiling is refused",
    "iterations below the floor are refused",
    "iterations above the ceiling are refused",
    "parallelism below the floor is refused",
    "parallelism above the ceiling is refused",
    "test_nfc_normalization",
    "test_js_module_exists",
    "test_js_test_exists",
    "test_hkdf_rfc5869_a1_vector",
    "test_hkdf_rfc5869_a3_vector",
    "test_contract_reports_nonzero_tests",
    "output length 31 is refused",
    "output length 33 is refused",
    "salt of 15 bytes is refused",
    "salt of 17 bytes is refused",
    "version 0 is refused",
    "version 2 is refused",
    "string memoryKib is refused",
    "string iterations is refused",
    "string parallelism is refused",
    "null params is refused",
    "undefined params is refused",
    "non-object params is refused",
    "memory NaN is refused",
    "memory 65536.5 is refused",
    "memory Infinity is refused",
    "iterations 3.5 is refused",
    "iterations NaN is refused",
    "parallelism 1.5 is refused",
    "parallelism NaN is refused",
    "memory 65535 is refused",
    "memory 262145 is refused",
]


def _hkdf(ikm, salt, info, length):
    """Independent HKDF-SHA256 using stdlib hmac + hashlib."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    t = b""
    okm = b""
    i = 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        okm += t
        i += 1
    return okm[:length]


class ServerModulesWebFeContractTest(contract_support.ModuleContractTest):
    MODULE = "server_web_fe"


class WebFeContractTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        # Check node exists and major >= 20
        try:
            result = subprocess.run(["node", "--version"], capture_output=True, text=True)
            if result.returncode != 0:
                raise AssertionError("node is not available")
            version_str = result.stdout.strip()
            major = int(version_str.lstrip("v").split(".")[0])
            if major < 20:
                raise AssertionError(f"node major version must be >= 20, got {version_str}")
        except FileNotFoundError:
            raise AssertionError("node is not available")

        result = subprocess.run(
            ["node", "--test", "--test-reporter=tap", DERIVATION_TEST_JS],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )
        cls.node_stdout = result.stdout
        cls.node_stderr = result.stderr
        cls.node_exit_code = result.returncode
        assert cls.node_exit_code == 0, f"node tests failed with exit code {cls.node_exit_code}"
        assert "not ok" not in cls.node_stdout
        cls.test_results = {}
        for line in result.stdout.splitlines():
            m = re.match(r"^(ok|not ok)\s+\d+\s+-\s+(.+)$", line)
            if m:
                status, name = m.groups()
                cls.test_results[name] = (status == "ok")
        cls.test_count = 0
        for line in result.stdout.splitlines():
            m = re.match(r"^# tests\s+(\d+)", line)
            if m:
                cls.test_count = int(m.group(1))
        assert cls.test_count == len(NODE_TEST_NAMES), \
            f"expected {len(NODE_TEST_NAMES)} node tests, got {cls.test_count}"

    def test_derivation_js_exists(self):
        self.assertTrue(os.path.isfile(DERIVATION_JS), "derivation.js missing")

    def test_derivation_test_js_exists(self):
        self.assertTrue(os.path.isfile(DERIVATION_TEST_JS), "derivation.test.js missing")

    def test_contract_reports_nonzero_tests(self):
        self.assertGreater(self.test_count, 0, "node run reported 0 tests")

    def test_node_test_results(self):
        for name in NODE_TEST_NAMES:
            with self.subTest(node_test=name):
                self.assertIn(name, self.test_results, f"node test not found: {name}")
                self.assertTrue(self.test_results[name], f"node test failed: {name}")

    def test_derivation_js_is_browser_safe(self):
        with open(DERIVATION_JS) as f:
            src = f.read()
        for forbidden in ["require(", "process.", "Buffer", "node:"]:
            self.assertNotIn(forbidden, src, f"derivation.js contains forbidden browser pattern: {forbidden}")

    def test_parity_hkdf_kek(self):
        ikm = bytes(range(32))
        salt = bytes(32)
        # Label parity: KeySplit.kt:8 — KEK_LABEL = "breaker-kek-v1"
        kek = _hkdf(ikm, salt, b"breaker-kek-v1", 32)
        self.assertEqual(kek.hex(), "64ab835ac3c4c27e630723be2516fd9fca90ec58ade981222aa0219812915995")

    def test_parity_hkdf_verifier(self):
        ikm = bytes(range(32))
        salt = bytes(32)
        # Label parity: KeySplit.kt:11 — AUTH_VERIFIER_LABEL = "breaker-auth-verifier-v1"
        verifier = _hkdf(ikm, salt, b"breaker-auth-verifier-v1", 32)
        self.assertEqual(verifier.hex(), "69503b3190a0704fca87cd50ee5819dc592fe5ab476416aff2e9408cff83a4e8")

    def test_parity_kek_differs_from_verifier(self):
        ikm = bytes(range(32))
        salt = bytes(32)
        # Label parity: KeySplit.kt:8,11 — KEK_LABEL = "breaker-kek-v1", AUTH_VERIFIER_LABEL = "breaker-auth-verifier-v1"
        kek = _hkdf(ikm, salt, b"breaker-kek-v1", 32)
        verifier = _hkdf(ikm, salt, b"breaker-auth-verifier-v1", 32)
        self.assertNotEqual(kek, verifier)


if __name__ == "__main__":
    unittest.main()
