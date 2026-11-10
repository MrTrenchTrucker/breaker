#!/usr/bin/env python3
"""Promise tests for tools/verify_vendored_libs.py.

Each fault test is written to FAIL while the defect it pins is present, against
a promise quoted by function name and wording; the documented-behaviour tests
pass and back the current behavior, so a reviewer can re-run them.

Fakes only: every fixture is a temp-dir tree with a hand-built .tgz tarball,
the download is injected (the `download` parameter), so tests never touch the
network. No Gradle, no network, no load.

Run: python3 -m unittest tests.unit.tools.test_verify_vendored_libs -v
"""
import hashlib
import io
import os
import shutil
import sys
import tarfile
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
TOOL_PATH = os.path.join(ROOT, "tools", "verify_vendored_libs.py")

import importlib.util
_spec = importlib.util.spec_from_file_location(
    "verify_vendored_libs_under_test", TOOL_PATH
)
verify = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(verify)

# Minimal noble module template: VERSION + one vendored file.
VERSION_TEXT = """name: shared_web_libs_noble_ciphers
version: 2.4.0
source_tarball: ciphers-2.4.0.tgz
source_tarball_sha256: PLACEHOLDER_SHA
licence: MIT
"""

FILE_TEXT = "export const x = 1;\n"


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def _build_tarball(files):
    """files: dict of member name -> bytes. Return (tarball bytes, sha256 hex)."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name=name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buf.getvalue(), _sha256(buf.getvalue())


def _write(path, text_or_bytes):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(text_or_bytes if isinstance(text_or_bytes, bytes)
                else text_or_bytes.encode("utf-8"))


def _scratch_tree(root, tarball_bytes, tarball_sha, vendored_rel="aes.js",
                  module_name="noble-ciphers"):
    """Build a scratch tree with one noble module; return the web-libs dir."""
    module_dir = os.path.join(root, "shared", "modules", "web-libs", module_name)
    _write(os.path.join(module_dir, "VERSION"),
           VERSION_TEXT.replace("PLACEHOLDER_SHA", tarball_sha))
    _write(os.path.join(module_dir, vendored_rel), FILE_TEXT)
    return os.path.join(root, "shared", "modules", "web-libs")


def _check_with(module_dir, tarball_bytes, download=None):
    """Run _check_module against a scratch web-libs dir, monkeypatching WEBLIBS."""
    orig = verify.WEBLIBS
    verify.WEBLIBS = module_dir
    try:
        return verify._check_module("noble-ciphers", download=download)
    finally:
        verify.WEBLIBS = orig


def _run_main_with(download=None):
    """Call verify.main(download=download) and return the exit code it
    returns."""
    return verify.main(download=download)


# ---------------------------------------------------------------------------
# RED: wrong tarball sha256 -> exit 1
# ---------------------------------------------------------------------------
class WrongTarballShaIsExit1(unittest.TestCase):
    """_check_module: tarball sha256 must equal VERSION source_tarball_sha256;
    main() exits 1 on a mismatch."""

    def test_wrong_sha_is_exit1(self):
        root = tempfile.mkdtemp(prefix="wrongsha")
        try:
            tarball, real_sha = _build_tarball({"package/aes.js": FILE_TEXT.encode()})
            weblibs = _scratch_tree(root, tarball, "0" * 64)
            problems, dl_fail = verify._check_module("noble-ciphers",
                                                     download=lambda u: tarball,
                                                     )
            # monkeypatch WEBLIBS so _check_module reads the scratch dir
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                problems, dl_fail = verify._check_module("noble-ciphers",
                                                         download=lambda u: tarball)
                self.assertFalse(dl_fail)
                self.assertTrue(any("sha256 mismatch" in p for p in problems),
                                f"wrong sha was not caught: {problems!r}")
                code = _run_main_with(download=lambda u: tarball)
                self.assertEqual(code, 1,
                                 f"main() should exit 1 on mismatch, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: one byte changed in a vendored file -> exit 1
# ---------------------------------------------------------------------------
class OneByteChangedIsExit1(unittest.TestCase):
    """_check_module: every vendored .js must be byte-identical to the tarball."""

    def test_one_byte_changed_is_exit1(self):
        root = tempfile.mkdtemp(prefix="bytechange")
        try:
            real = FILE_TEXT.encode()
            corrupted = real[:-1] + bytes([real[-1] ^ 0xFF])
            tarball, real_sha = _build_tarball({"package/aes.js": real})
            weblibs = _scratch_tree(root, tarball, real_sha)
            with open(os.path.join(weblibs, "noble-ciphers", "aes.js"), "wb") as fh:
                fh.write(corrupted)
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                problems, dl_fail = verify._check_module("noble-ciphers",
                                                         download=lambda u: tarball)
                self.assertTrue(any("differs from tarball" in p for p in problems),
                                f"byte change was not caught: {problems!r}")
                code = _run_main_with(download=lambda u: tarball)
                self.assertEqual(code, 1,
                                 f"main() should exit 1 on mismatch, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: vendored file missing from tarball -> exit 1
# ---------------------------------------------------------------------------
class MissingVendoredFileIsExit1(unittest.TestCase):
    """_check_module: a vendored file with no tarball member is an error."""

    def test_missing_vendored_file_is_exit1(self):
        root = tempfile.mkdtemp(prefix="missingfile")
        try:
            tarball, real_sha = _build_tarball({})  # empty tarball
            weblibs = _scratch_tree(root, tarball, real_sha)
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                problems, dl_fail = verify._check_module("noble-ciphers",
                                                         download=lambda u: tarball)
                self.assertTrue(any("missing from tarball" in p for p in problems),
                                f"missing vendored file was not caught: {problems!r}")
                code = _run_main_with(download=lambda u: tarball)
                self.assertEqual(code, 1,
                                 f"main() should exit 1 on missing member, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: path-traversal member -> problem line + exit 1
# ---------------------------------------------------------------------------
class PathTraversalIsRefused(unittest.TestCase):
    """_check_module: tar members with '..' or absolute paths produce an
    'unsafe tarball member' problem line and exit 1."""

    def test_path_traversal_member_is_unsafe(self):
        root = tempfile.mkdtemp(prefix="traversal")
        try:
            traversal_data = b"evil\n"
            files = {"package/aes.js": FILE_TEXT.encode(),
                     "../escape.js": traversal_data}
            tarball, real_sha = _build_tarball(files)
            weblibs = _scratch_tree(root, tarball, real_sha)
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                problems, dl_fail = verify._check_module("noble-ciphers",
                                                         download=lambda u: tarball)
                self.assertTrue(any("unsafe tarball member" in p
                                      and "../escape.js" in p for p in problems),
                                f"traversal member not reported: {problems!r}")
                code = _run_main_with(download=lambda u: tarball)
                self.assertEqual(code, 1,
                                 f"main() should exit 1 on unsafe member, got {code}")
                # no false mismatch on the clean aes.js
                self.assertFalse(any("differs" in p for p in problems),
                                 f"clean file flagged as differing: {problems!r}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# GREEN: clean case -> exit 0
# ---------------------------------------------------------------------------
class CleanIsGreen(unittest.TestCase):
    """_check_module: a clean module tree passes; main() exits 0."""

    def test_clean_module_is_exit0(self):
        root = tempfile.mkdtemp(prefix="clean")
        try:
            tarball, real_sha = _build_tarball({"package/aes.js": FILE_TEXT.encode()})
            weblibs = _scratch_tree(root, tarball, real_sha)
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                problems, dl_fail = verify._check_module("noble-ciphers",
                                                         download=lambda u: tarball)
                self.assertEqual(problems, [],
                                 f"clean tree reported problems: {problems!r}")
                code = _run_main_with(download=lambda u: tarball)
                self.assertEqual(code, 0,
                                 f"main() should exit 0 when clean, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: download failure -> exit 2
# ---------------------------------------------------------------------------
class DownloadFailureIsExit2(unittest.TestCase):
    """_download: a network/HTTP failure returns exit 2 after one retry.
    main() exits 2 when any download fails."""

    def test_download_failure_is_exit2(self):
        root = tempfile.mkdtemp(prefix="downloadfail")
        try:
            tarball, real_sha = _build_tarball({"package/aes.js": FILE_TEXT.encode()})
            weblibs = _scratch_tree(root, tarball, real_sha)
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                def failing_download(url):
                    raise OSError("network down")
                # _download returns an error string
                data, err = verify._download("https://example.com/x.tgz",
                                             download=failing_download)
                self.assertIsNone(data)
                self.assertIsNotNone(err)
                self.assertIn("download failed", err)
                # main() exits 2 on download failure
                code = _run_main_with(download=failing_download)
                self.assertEqual(code, 2,
                                 f"main() should exit 2 on download failure, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: oversized download -> exit 2 (size cap enforced)
# ---------------------------------------------------------------------------
class OversizedDownloadIsExit2(unittest.TestCase):
    """_download: an answer larger than MAX_DOWNLOAD_BYTES is a download
    failure (exit 2); urlopen is called with a 30 s timeout."""

    def test_oversized_download_is_exit2(self):
        root = tempfile.mkdtemp(prefix="oversize")
        try:
            tarball, real_sha = _build_tarball({"package/aes.js": FILE_TEXT.encode()})
            weblibs = _scratch_tree(root, tarball, real_sha)
            orig = verify.WEBLIBS
            verify.WEBLIBS = weblibs
            try:
                big = b"x" * (verify.MAX_DOWNLOAD_BYTES + 1)

                def oversized_download(url):
                    return io.BytesIO(big)

                data, err = verify._download("https://example.com/x.tgz",
                                             download=oversized_download)
                self.assertIsNone(data)
                self.assertIsNotNone(err)
                self.assertIn("exceeded", err)
                code = _run_main_with(download=oversized_download)
                self.assertEqual(code, 2,
                                 f"main() should exit 2 on oversized download, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: module-name check -> exit 1
# ---------------------------------------------------------------------------
class ModuleNameCheckIsExit1(unittest.TestCase):
    """_tarball_name_and_version + _check_module: the tarball name from
    source_tarball must equal the module folder name minus 'noble-'; a VERSION
    pointing at another package is a problem (exit 1)."""

    def test_module_name_mismatch_is_exit1(self):
        root = tempfile.mkdtemp(prefix="modulename")
        try:
            tarball, real_sha = _build_tarball({"package/aes.js": FILE_TEXT.encode()})
            # VERSION says ciphers-2.4.0.tgz but the folder is noble-hashes
            # -> expected tarball name 'hashes', got 'ciphers'
            version_text = ("name: shared_web_libs_noble_hashes\n"
                            "version: 2.4.0\n"
                            "source_tarball: ciphers-2.4.0.tgz\n"
                            f"source_tarball_sha256: {real_sha}\n"
                            "licence: MIT\n")
            module_dir = os.path.join(root, "shared", "modules", "web-libs",
                                      "noble-hashes")
            _write(os.path.join(module_dir, "VERSION"), version_text)
            _write(os.path.join(module_dir, "aes.js"), FILE_TEXT)
            orig = verify.WEBLIBS
            verify.WEBLIBS = os.path.join(root, "shared", "modules", "web-libs")
            try:
                problems, dl_fail = verify._check_module("noble-hashes",
                                                         download=lambda u: tarball)
                self.assertFalse(dl_fail)
                self.assertTrue(any("does not match module" in p
                                      and "ciphers" in p and "hashes" in p
                                      for p in problems),
                                f"module name mismatch not caught: {problems!r}")
                code = _run_main_with(download=lambda u: tarball)
                self.assertEqual(code, 1,
                                 f"main() should exit 1 on module name mismatch, got {code}")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# RED: non-noble module folder skipped
# ---------------------------------------------------------------------------
class NonNobleModuleSkipped(unittest.TestCase):
    """_noble_modules: libsodium (shared_web_libs_libsodium) is skipped by rule."""

    def test_libsodium_is_not_a_noble_module(self):
        root = tempfile.mkdtemp(prefix="nonnoble")
        try:
            tarball, real_sha = _build_tarball({"package/aes.js": FILE_TEXT.encode()})
            cipher_dir = os.path.join(root, "shared", "modules", "web-libs", "noble-ciphers")
            _write(os.path.join(cipher_dir, "VERSION"),
                   VERSION_TEXT.replace("PLACEHOLDER_SHA", real_sha))
            _write(os.path.join(cipher_dir, "aes.js"), FILE_TEXT)
            sodium_dir = os.path.join(root, "shared", "modules", "web-libs", "libsodium")
            _write(os.path.join(sodium_dir, "VERSION"),
                   ("name: shared_web_libs_libsodium\nversion: 0.8.4\n"
                    "source_tarball: libsodium-sumo-0.8.4.tgz\n"
                    "source_tarball_sha256: deadbeef\nlicence: ISC\n").encode())
            orig = verify.WEBLIBS
            verify.WEBLIBS = os.path.join(root, "shared", "modules", "web-libs")
            try:
                names = verify._noble_modules()
                self.assertIn("noble-ciphers", names)
                self.assertNotIn("libsodium", names,
                                 "libsodium was not skipped by the noble rule")
            finally:
                verify.WEBLIBS = orig
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# Whole-folder rule: every file except the four metadata names is compared
# ---------------------------------------------------------------------------
import contextlib


def _module_tree(root, files, tarball_members, version_text=None, make_sha=True):
    """Build a scratch noble-ciphers module holding `files` (rel -> bytes) and a
    tarball with `tarball_members` (member name -> bytes). Return (weblibs, tarball)."""
    tarball, sha = _build_tarball(tarball_members)
    module_dir = os.path.join(root, "shared", "modules", "web-libs", "noble-ciphers")
    version = version_text or VERSION_TEXT
    _write(os.path.join(module_dir, "VERSION"), version.replace("PLACEHOLDER_SHA", sha))
    for rel, data in files.items():
        _write(os.path.join(module_dir, rel), data)
    return os.path.join(root, "shared", "modules", "web-libs"), tarball


class _Scratch:
    """Context: a scratch web-libs dir that verify.WEBLIBS points at."""

    def __init__(self, files, members, version_text=None):
        self.files, self.members, self.version_text = files, members, version_text

    def __enter__(self):
        self.root = tempfile.mkdtemp(prefix="wholefolder")
        self.weblibs, self.tarball = _module_tree(
            self.root, self.files, self.members, self.version_text)
        self.orig = verify.WEBLIBS
        verify.WEBLIBS = self.weblibs
        return self

    def __exit__(self, *exc):
        verify.WEBLIBS = self.orig
        shutil.rmtree(self.root, ignore_errors=True)


def _run_main_capturing(download):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = verify.main(download=download)
    return code, out.getvalue()


class WholeFolderIsCompared(unittest.TestCase):
    """Promise (tool header, rule 6): every file under the module folder except
    AGENTS.md, README.md, VERSION and build.gradle.kts must exist in the tarball
    and be byte-identical, whatever its extension."""

    LIC = b"MIT licence text\n"

    def _members(self, **extra):
        members = {"package/aes.js": b"a\n", "package/LICENSE": self.LIC,
                   "package/aes.d.ts": b"d\n"}
        members.update(extra)
        return members

    def _files(self, **over):
        files = {"aes.js": b"a\n", "LICENSE": self.LIC, "aes.d.ts": b"d\n"}
        files.update(over)
        return files

    def test_all_identical_with_metadata_differing_is_green(self):
        files = self._files(**{"README.md": b"anything", "AGENTS.md": b"x",
                               "build.gradle.kts": b"y"})
        with _Scratch(files, self._members()) as sc:
            code, out = _run_main_capturing(lambda u: sc.tarball)
        self.assertEqual(code, 0, out)

    def test_changed_license_is_caught(self):
        with _Scratch(self._files(LICENSE=b"changed\n"), self._members()) as sc:
            code, out = _run_main_capturing(lambda u: sc.tarball)
        self.assertEqual(code, 1, out)
        self.assertIn("LICENSE differs from tarball", out)

    def test_changed_d_ts_is_caught(self):
        with _Scratch(self._files(**{"aes.d.ts": b"changed\n"}), self._members()) as sc:
            code, out = _run_main_capturing(lambda u: sc.tarball)
        self.assertEqual(code, 1, out)
        self.assertIn("aes.d.ts differs from tarball", out)

    def test_stray_file_of_any_kind_is_caught(self):
        for name in ("extra.json", "page.html", "x.mjs", "sub/dir/thing"):
            with self.subTest(name=name):
                with _Scratch(self._files(**{name: b"z"}), self._members()) as sc:
                    code, out = _run_main_capturing(lambda u: sc.tarball)
                self.assertEqual(code, 1, out)
                self.assertIn(name.replace("/", os.sep) + " missing from tarball", out)

    def test_mismatch_line_names_the_module_once(self):
        with _Scratch(self._files(LICENSE=b"changed\n"), self._members()) as sc:
            code, out = _run_main_capturing(lambda u: sc.tarball)
        line = [l for l in out.splitlines() if "LICENSE differs" in l][0]
        self.assertEqual(line.count("noble-ciphers"), 1, line)


class RetryVersionAndMemberSizeAreKept(unittest.TestCase):
    """Promises: one retry on a network error; VERSION version must equal the
    version in source_tarball; a member above MAX_MEMBER_BYTES is unsafe."""

    def _simple(self):
        return {"aes.js": b"a\n"}, {"package/aes.js": b"a\n"}

    def test_one_failure_then_success_is_clean_two_failures_is_exit_2(self):
        files, members = self._simple()
        with _Scratch(files, members) as sc:
            calls = {"n": 0}

            def flaky(url):
                calls["n"] += 1
                if calls["n"] == 1:
                    raise OSError("blip")
                return sc.tarball

            code, out = _run_main_capturing(flaky)
            self.assertEqual(code, 0, out)
            self.assertEqual(calls["n"], 2)

            def dead(url):
                raise OSError("down")

            code, out = _run_main_capturing(dead)
            self.assertEqual(code, 2, out)

    def test_version_differing_from_source_tarball_is_exit_1(self):
        files, members = self._simple()
        bad_version = VERSION_TEXT.replace("ciphers-2.4.0.tgz", "ciphers-2.5.0.tgz")
        with _Scratch(files, members, version_text=bad_version) as sc:
            code, out = _run_main_capturing(lambda u: sc.tarball)
        self.assertEqual(code, 1, out)
        self.assertIn("VERSION version", out)

    def test_member_above_the_size_cap_is_unsafe(self):
        files, members = self._simple()
        members["package/big.bin"] = b"x" * 50
        with _Scratch(files, members) as sc:
            orig = verify.MAX_MEMBER_BYTES
            verify.MAX_MEMBER_BYTES = 10
            try:
                code, out = _run_main_capturing(lambda u: sc.tarball)
            finally:
                verify.MAX_MEMBER_BYTES = orig
        self.assertEqual(code, 1, out)
        self.assertIn("unsafe tarball member package/big.bin", out)


if __name__ == "__main__":
    unittest.main()
