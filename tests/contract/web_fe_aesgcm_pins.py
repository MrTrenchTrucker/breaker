"""Source pins for the web FE AES-GCM layer and the vendored noble-ciphers.

Not a `test_*.py` file on purpose: the contract-file bijection counts every
`tests/contract/test_*.py` as one module's contract test. The class below is
collected through `test_web_fe_contract.py`, which imports it.

The noble-ciphers files MOVED from
server/modules/web-fe/src/main/resources/web/js/vendor/noble-ciphers/ to
shared/modules/web-libs/noble-ciphers/ (git mv — same sha256). web-fe's
copyWebLibs Copy task copies them into build/web/js/vendor/noble-ciphers/ at
assemble time, so aesgcm.js's import path (`./vendor/noble-ciphers/aes.js`)
still resolves against the build output.

The pin table keeps the aesgcm.js import-path and forbidden-token checks from
the old pins file as checks on the real file, plus one row per vendored file
in its new home.
"""

import hashlib
import os
import re
import unittest

import contract_support

ROOT = contract_support.ROOT
NOBLE_CIPHERS = os.path.join(ROOT, "shared", "modules", "web-libs", "noble-ciphers")
AESGCM = os.path.join(
    ROOT, "server", "modules", "web-fe", "src", "main", "resources", "web", "js", "aesgcm.js"
)

IMPORT_LINE_RE = re.compile(r"^import\s+.*from\s+['\"](.+)['\"]", re.MULTILINE)
FORBIDDEN_TOKENS = ["Math.random", "Date.now", "eval", "new Function", "fetch", "XMLHttpRequest", "import("]

# One row per vendored noble-ciphers file: new home -> sha256 from the tarball.
NOBLE_CIPHERS_FILES = {
    "LICENSE": "f36671a5487c9c5050efacb58011c37c24c55a889803cb036cf9d9a6347c1e2d",
    "_arx.js": "ead142ba410404c0698bb80e0d6f77780799bb77a9e3b098cf0f9fca1c486e37",
    "_poly1305.js": "5d2afd73b40dbafb7b6740c6e6388e7123c79a4e57306861b28c5734925fa84d",
    "_polyval.js": "735b078133e0c5a97dd984a21389f152dc7958b283f52504f6f0906ded243fa5",
    "aes.js": "941ebefe14c48cd1401ffe52f329d4e6ff14f3f596681b211a33cde21de3a90c",
    "salsa.js": "c5a3d72861d5793ab756476696b540aeffefbaebe6a219ba4fba19c0c5fd5b95",
    "utils.js": "a443ec1f60d1aaa25d1b7d8c102d33c0e8e6d683ada1ad17be9c32e69170ff95",
}


class WebFeAesGcmPinsTest(unittest.TestCase):

    def _read_bytes(self, rel):
        path = os.path.join(ROOT, rel)
        self.assertTrue(os.path.isfile(path), f"missing: {rel}")
        with open(path, "rb") as fh:
            return fh.read()

    # ── moved files: byte-equal to tarball, new home ──────────────────

    def test_noble_ciphers_folder_holds_exactly_seven_files(self):
        names = sorted(os.listdir(NOBLE_CIPHERS))
        expected = sorted(list(NOBLE_CIPHERS_FILES.keys()))
        # Metadata files (card, readme, version, build) live beside the
        # vendored files but are not part of the vendored file set.
        metadata = {"AGENTS.md", "README.md", "VERSION", "build.gradle.kts"}
        names = [n for n in names if n not in metadata]
        self.assertEqual(names, expected, f"noble-ciphers folder has unexpected files: {names}")

    def test_sha256_matches_moved_noble_ciphers_files(self):
        for name, expected_hash in NOBLE_CIPHERS_FILES.items():
            path = os.path.join(NOBLE_CIPHERS, name)
            actual = hashlib.sha256(self._read_bytes(
                os.path.join("shared", "modules", "web-libs", "noble-ciphers", name)
            )).hexdigest()
            self.assertEqual(actual, expected_hash, f"{name} sha256 mismatch")

    # ── aesgcm.js still imports vendored noble-ciphers (now build output) ─

    def test_aesgcm_js_imports_only_vendored_files(self):
        self.assertTrue(os.path.isfile(AESGCM), f"missing: {AESGCM}")
        content = self._read_bytes(
            os.path.join(
                "server", "modules", "web-fe", "src", "main", "resources", "web", "js", "aesgcm.js"
            )
        ).decode("utf-8")
        for m in IMPORT_LINE_RE.finditer(content):
            imp = m.group(1)
            self.assertTrue(
                imp.startswith("./vendor/noble-ciphers/"),
                f"import path not under ./vendor/noble-ciphers/: {imp}",
            )

    def test_aesgcm_js_contains_no_forbidden_tokens(self):
        self.assertTrue(os.path.isfile(AESGCM), f"missing: {AESGCM}")
        content = self._read_bytes(
            os.path.join(
                "server", "modules", "web-fe", "src", "main", "resources", "web", "js", "aesgcm.js"
            )
        ).decode("utf-8")
        for token in FORBIDDEN_TOKENS:
            self.assertNotIn(token, content, f"aesgcm.js contains forbidden token: {token}")

    def test_notice_names_library_and_mit(self):
        notice_path = os.path.join(ROOT, "NOTICE")
        self.assertTrue(os.path.isfile(notice_path), f"missing: {notice_path}")
        notice_content = self._read_bytes("NOTICE").decode("utf-8")
        self.assertIn("@noble/ciphers 2.4.0", notice_content, "NOTICE missing @noble/ciphers 2.4.0")
        self.assertIn("@noble/hashes 2.4.0", notice_content, "NOTICE missing @noble/hashes 2.4.0")
        self.assertIn("@noble/curves 2.4.0", notice_content, "NOTICE missing @noble/curves 2.4.0")
        self.assertIn("libsodium 0.8.4", notice_content, "NOTICE missing libsodium 0.8.4")
        self.assertIn("MIT", notice_content, "NOTICE missing MIT")

    def test_notice_says_unmodified_copies(self):
        notice_path = os.path.join(ROOT, "NOTICE")
        self.assertTrue(os.path.isfile(notice_path), f"missing: {notice_path}")
        notice_content = self._read_bytes("NOTICE").decode("utf-8")
        self.assertIn("unmodified copies", notice_content, "NOTICE missing sentence about unmodified copies")
