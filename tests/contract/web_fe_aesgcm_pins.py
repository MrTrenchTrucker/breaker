"""Source pins for the web FE AES-GCM layer.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_web_fe_contract.py`, which imports it.
"""

import hashlib
import os
import re
import unittest

import contract_support

IMPORT_LINE_RE = re.compile(r"^import\s+.*from\s+['\"](.+)['\"]", re.MULTILINE)
FORBIDDEN_TOKENS = ["Math.random", "Date.now", "eval", "new Function", "fetch", "XMLHttpRequest", "import("]


class WebFeAesGcmPinsTest(unittest.TestCase):

    def _read(self, rel):
        path = os.path.join(contract_support.ROOT, rel)
        self.assertTrue(os.path.isfile(path), f"missing: {rel}")
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()

    def test_vendor_folder_holds_exactly_four_files(self):
        vendor_dir = os.path.join(contract_support.ROOT, "server/modules/web-fe/src/main/resources/web/js/vendor/noble-ciphers")
        names = sorted(os.listdir(vendor_dir))
        expected = sorted(["LICENSE", "aes.js", "_polyval.js", "utils.js"])
        self.assertEqual(names, expected, f"vendor folder has unexpected files: {names}")

    def test_sha256_matches_vendor_files(self):
        vendor_dir = os.path.join(contract_support.ROOT, "server/modules/web-fe/src/main/resources/web/js/vendor/noble-ciphers")
        expected = {
            "aes.js": "941ebefe14c48cd1401ffe52f329d4e6ff14f3f596681b211a33cde21de3a90c",
            "utils.js": "a443ec1f60d1aaa25d1b7d8c102d33c0e8e6d683ada1ad17be9c32e69170ff95",
            "_polyval.js": "735b078133e0c5a97dd984a21389f152dc7958b283f52504f6f0906ded243fa5",
            "LICENSE": "f36671a5487c9c5050efacb58011c37c24c55a889803cb036cf9d9a6347c1e2d",
        }
        for name, expected_hash in expected.items():
            path = os.path.join(vendor_dir, name)
            with open(path, "rb") as fh:
                actual = hashlib.sha256(fh.read()).hexdigest()
            self.assertEqual(actual, expected_hash, f"{name} sha256 mismatch")

    def test_aesgcm_js_imports_only_vendored_files(self):
        aesgcm_path = os.path.join(contract_support.ROOT, "server/modules/web-fe/src/main/resources/web/js/aesgcm.js")
        self.assertTrue(os.path.isfile(aesgcm_path), f"missing: {aesgcm_path}")
        content = self._read("server/modules/web-fe/src/main/resources/web/js/aesgcm.js")
        for m in IMPORT_LINE_RE.finditer(content):
            imp = m.group(1)
            self.assertTrue(
                imp.startswith("./vendor/noble-ciphers/"),
                f"import path not under ./vendor/noble-ciphers/: {imp}",
            )

    def test_aesgcm_js_contains_no_forbidden_tokens(self):
        aesgcm_path = os.path.join(contract_support.ROOT, "server/modules/web-fe/src/main/resources/web/js/aesgcm.js")
        self.assertTrue(os.path.isfile(aesgcm_path), f"missing: {aesgcm_path}")
        content = self._read("server/modules/web-fe/src/main/resources/web/js/aesgcm.js")
        for token in FORBIDDEN_TOKENS:
            self.assertNotIn(token, content, f"aesgcm.js contains forbidden token: {token}")

    def test_notice_names_library_and_mit(self):
        notice_path = os.path.join(contract_support.ROOT, "NOTICE")
        self.assertTrue(os.path.isfile(notice_path), f"missing: {notice_path}")
        notice_content = self._read("NOTICE")
        self.assertIn("@noble/ciphers 2.4.0", notice_content, "NOTICE missing @noble/ciphers 2.4.0")
        self.assertIn("MIT", notice_content, "NOTICE missing MIT")

    def test_notice_says_unmodified_copies(self):
        notice_path = os.path.join(contract_support.ROOT, "NOTICE")
        self.assertTrue(os.path.isfile(notice_path), f"missing: {notice_path}")
        notice_content = self._read("NOTICE")
        self.assertIn("unmodified copies", notice_content, "NOTICE missing sentence about unmodified copies")
