"""tools/sherpa_asr_verify.py catches a build that is not what it claims to be.

Each test builds a tiny fake AAR (a zip with fake `.so` bytes, a fake
`classes.jar` holding empty `.class` entries, a `NOTICE.md` and a `licenses/`
directory) in a temp dir, runs the real `sherpa_asr_verify.py` on it as a
subprocess, and reads its PASS/FAIL lines and exit code. One test proves a
clean AAR passes every check (GREEN); one test per check (a)-(e) plants the
specific fault that check exists to catch and watches it fail for the stated
reason (RED), plus the control case for check (a): a control AAR with no
markers in it makes the scan itself fail, because a scan that cannot see the
thing it looks for proves nothing. A check that only ever passes proves
nothing, so every RED case here is mandatory.
"""
import io
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
VERIFY_PY = os.path.join(REPO_ROOT, "tools", "sherpa_asr_verify.py")

GOOD_SO_BYTES = b"not a real shared library, just filler bytes for the test \x00\x01\x02"

GOOD_CLASSES = (
    "com/k2fsa/sherpa/onnx/OnlineRecognizer.class",
    "com/k2fsa/sherpa/onnx/OfflineRecognizer.class",
)

GOOD_NOTICE = """# NOTICE — third-party components in the ASR-only sherpa-onnx test AAR

| Component | Version / commit | Licence | Source fetched from | Vendored as |
|---|---|---|---|---|
| sherpa-onnx | tag `v1.13.8` | Apache-2.0 | `https://example.invalid/sherpa-onnx` | `licenses/sherpa-onnx.LICENSE.txt` |
| onnxruntime | `v1.28.2` | MIT | `https://example.invalid/onnxruntime` | `licenses/onnxruntime.LICENSE.txt` |

Downloaded by the upstream build but NOT linked into any library in this package: asio, websocketpp and Eigen.
"""

GOOD_LICENSE_FILES = {
    "licenses/sherpa-onnx.LICENSE.txt": "Apache License 2.0 (test fixture text)\n",
    "licenses/onnxruntime.LICENSE.txt": "MIT License (test fixture text)\n",
}

GOOD_BUILD_LOG = (
    "-- Downloading sherpa-onnx from https://example.invalid/sherpa-onnx\n"
    "-- Downloading onnxruntime from https://example.invalid/onnxruntime\n"
)


def _make_classes_jar(class_names):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as jar:
        for name in class_names:
            jar.writestr(name, b"")  # empty .class entry, bytes never inspected
    return buf.getvalue()


def _build_aar(
    path,
    *,
    so_bytes_by_path=None,
    class_names=GOOD_CLASSES,
    notice_text=GOOD_NOTICE,
    license_files=GOOD_LICENSE_FILES,
    include_notice=True,
    include_licenses=True,
):
    """Write a fake AAR to `path`. Every knob defaults to a GREEN fixture;
    a test overrides exactly the one knob it needs to turn RED."""
    if so_bytes_by_path is None:
        so_bytes_by_path = {
            "jni/arm64-v8a/libsherpa-onnx-jni.so": GOOD_SO_BYTES,
            "jni/x86_64/libsherpa-onnx-jni.so": GOOD_SO_BYTES,
        }
    with zipfile.ZipFile(path, "w") as aar:
        for so_path, data in so_bytes_by_path.items():
            aar.writestr(so_path, data)
        aar.writestr("classes.jar", _make_classes_jar(class_names))
        if include_notice:
            aar.writestr("NOTICE.md", notice_text)
        if include_licenses:
            for lic_path, text in license_files.items():
                aar.writestr(lic_path, text)
    return path


def _run_verify(aar_path, build_log=None, control=None):
    cmd = [sys.executable, VERIFY_PY, aar_path]
    if build_log is not None:
        cmd += ["--build-log", build_log]
    if control is not None:
        cmd += ["--control", control]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


class SherpaAsrVerifyGreenTest(unittest.TestCase):
    """A clean AAR (and a clean build log) passes every check."""

    def test_good_aar_passes_every_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            aar = _build_aar(os.path.join(tmp, "good.aar"))
            log_path = os.path.join(tmp, "build.log")
            with open(log_path, "w", encoding="utf-8") as fh:
                fh.write(GOOD_BUILD_LOG)
            rc, out = _run_verify(aar, build_log=log_path)
            self.assertEqual(
                rc, 0,
                f"a clean AAR with a clean build log did not pass every check: {out}",
            )
            self.assertNotIn(
                "FAIL", out,
                f"a clean AAR produced a FAIL line, which should not happen: {out}",
            )


class SherpaAsrVerifyRedTests(unittest.TestCase):
    """One planted fault per check (a)-(e), each watched to fail for the
    right reason. A check that cannot be made to fail is not a check."""

    def test_check_a_marker_in_so_fails(self):
        """(a) An espeak-ng/mbrola marker string inside a .so fails the scan."""
        with tempfile.TemporaryDirectory() as tmp:
            bad_so = {
                "jni/arm64-v8a/libsherpa-onnx-jni.so": GOOD_SO_BYTES + b"espeak-ng-data",
                "jni/x86_64/libsherpa-onnx-jni.so": GOOD_SO_BYTES,
            }
            aar = _build_aar(os.path.join(tmp, "bad.aar"), so_bytes_by_path=bad_so)
            rc, out = _run_verify(aar)
            self.assertNotEqual(
                rc, 0,
                f"a .so file containing the 'espeak-ng-data' marker passed the scan: {out}",
            )
            self.assertIn(
                "espeak-ng-data", out,
                f"the scan failed but did not name the marker it found: {out}",
            )

    def test_check_a_control_without_markers_fails(self):
        """(a) control case: if the control AAR has no markers either, the
        scan cannot prove it can see them, so this is a FAIL, not a silent
        PASS on the target."""
        with tempfile.TemporaryDirectory() as tmp:
            target = _build_aar(os.path.join(tmp, "target.aar"))
            control_without_markers = _build_aar(os.path.join(tmp, "control.aar"))
            rc, out = _run_verify(target, control=control_without_markers)
            self.assertNotEqual(
                rc, 0,
                f"a control AAR with no markers in it was accepted as proof the scan works: {out}",
            )
            self.assertIn(
                "the check cannot see the markers", out,
                f"the control case failed but not with the documented reason: {out}",
            )

    def test_check_b_missing_abi_fails(self):
        """(b) Dropping the x86_64 .so entirely is an error."""
        with tempfile.TemporaryDirectory() as tmp:
            one_abi_only = {"jni/arm64-v8a/libsherpa-onnx-jni.so": GOOD_SO_BYTES}
            aar = _build_aar(os.path.join(tmp, "bad.aar"), so_bytes_by_path=one_abi_only)
            rc, out = _run_verify(aar)
            self.assertNotEqual(
                rc, 0,
                f"an AAR missing the x86_64 .so passed the ABI check: {out}",
            )
            self.assertIn(
                "jni/x86_64/", out,
                f"the ABI check failed but did not name the missing ABI: {out}",
            )

    def test_check_c_missing_recognizer_class_fails(self):
        """(c) classes.jar with no OnlineRecognizer class at all is an error."""
        with tempfile.TemporaryDirectory() as tmp:
            classes = ("com/k2fsa/sherpa/onnx/OfflineRecognizer.class",)
            aar = _build_aar(os.path.join(tmp, "bad.aar"), class_names=classes)
            rc, out = _run_verify(aar)
            self.assertNotEqual(
                rc, 0,
                f"classes.jar with no OnlineRecognizer class passed the class check: {out}",
            )
            self.assertIn(
                "OnlineRecognizer", out,
                f"the class check failed but did not name the missing class: {out}",
            )

    def test_check_c_present_tts_class_fails(self):
        """(c) A single OfflineTts* class sneaking into classes.jar is an error,
        even when both recognizer classes are present."""
        with tempfile.TemporaryDirectory() as tmp:
            classes = GOOD_CLASSES + ("com/k2fsa/sherpa/onnx/OfflineTts.class",)
            aar = _build_aar(os.path.join(tmp, "bad.aar"), class_names=classes)
            rc, out = _run_verify(aar)
            self.assertNotEqual(
                rc, 0,
                f"classes.jar with an OfflineTts class passed the class check: {out}",
            )
            self.assertIn(
                "OfflineTts", out,
                f"the class check failed but did not name the disallowed class: {out}",
            )

    def test_check_d_license_file_not_in_notice_fails(self):
        """(d) A licence file that NOTICE.md never names is an error."""
        with tempfile.TemporaryDirectory() as tmp:
            license_files = dict(GOOD_LICENSE_FILES)
            license_files["licenses/kaldi-decoder.LICENSE.txt"] = "Apache-2.0 (test fixture)\n"
            aar = _build_aar(os.path.join(tmp, "bad.aar"), license_files=license_files)
            rc, out = _run_verify(aar)
            self.assertNotEqual(
                rc, 0,
                f"a licence file not named in NOTICE.md passed the check: {out}",
            )
            self.assertIn(
                "kaldi-decoder.LICENSE.txt", out,
                f"the check failed but did not name the unnamed licence file: {out}",
            )

    def test_check_e_downloaded_component_not_in_notice_fails(self):
        """(e) A build-log download that NOTICE.md does not account for is an error."""
        with tempfile.TemporaryDirectory() as tmp:
            aar = _build_aar(os.path.join(tmp, "good.aar"))
            log_path = os.path.join(tmp, "build.log")
            with open(log_path, "w", encoding="utf-8") as fh:
                fh.write(GOOD_BUILD_LOG)
                fh.write("-- Downloading mystery-lib from https://example.invalid/mystery-lib\n")
            rc, out = _run_verify(aar, build_log=log_path)
            self.assertNotEqual(
                rc, 0,
                f"a build-log download not named anywhere in NOTICE.md passed the check: {out}",
            )
            self.assertIn(
                "mystery-lib", out,
                f"the check failed but did not name the unaccounted-for download: {out}",
            )


    def test_check_e_ignores_downloads_by_build_tools(self):
        """(e) Only CMake '-- Downloading <name>' lines count; Gradle or SDK tool downloads in the same log do not."""
        with tempfile.TemporaryDirectory() as tmp:
            aar = _build_aar(os.path.join(tmp, "good.aar"))
            log_path = os.path.join(tmp, "build.log")
            with open(log_path, "w", encoding="utf-8") as fh:
                fh.write(GOOD_BUILD_LOG)
                fh.write("Downloading https://services.gradle.org/distributions/gradle-8.6-bin.zip\n")
                fh.write("Downloading Android CLI...\n")
            rc, out = _run_verify(aar, build_log=log_path)
            self.assertEqual(
                rc, 0,
                f"a build-tool download (not a CMake component line) was counted as a component: {out}",
            )

if __name__ == "__main__":
    unittest.main()
