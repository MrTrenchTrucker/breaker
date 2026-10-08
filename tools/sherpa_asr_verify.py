#!/usr/bin/env python3
"""Verify an ASR-only sherpa-onnx AAR built from tools/sherpa-asr/Dockerfile.

Checks that the artifact actually is what the recipe claims: no GPL text-to-
speech data compiled in, both ABIs present, the right recognizer classes (and
none of the excluded ones) in classes.jar, and licence bookkeeping consistent
with what shipped. None of these checks trust the Dockerfile's intent; they
inspect the built .aar directly.

Usage:
    python3 tools/sherpa_asr_verify.py AAR [--build-log LOG] [--control UPSTREAM_AAR]

Each check prints one PASS/FAIL line with a reason. Exit code is 1 if any
check fails (or is itself broken — see check (a)'s control case), 0 if every
check that ran passes. A check that did not run because its optional input
was not given prints a SKIP line and never affects the exit code.
"""
import argparse
import io
import os
import re
import sys
import tempfile
import zipfile

# Strings that only occur in sherpa-onnx's text-to-speech data/engine path
# (espeak-ng's phoneme tables and the mbrola voice format). None of these may
# appear in a native library built with SHERPA_ONNX_ENABLE_TTS=OFF.
TTS_MARKERS = (b"phontab", b"phondata", b"phonindex", b"intonations", b"mbrola", b"espeak-ng-data")

REQUIRED_ABIS = ("jni/arm64-v8a/", "jni/x86_64/")


def _so_files(zf):
    return [n for n in zf.namelist() if n.endswith(".so") and any(n.startswith(abi) for abi in REQUIRED_ABIS)]


def _scan_for_markers(aar_path):
    """Return the set of TTS_MARKERS (as str) found in any jni/*/*.so file."""
    found = set()
    with zipfile.ZipFile(aar_path) as zf:
        for name in _so_files(zf):
            data = zf.read(name)
            for marker in TTS_MARKERS:
                if marker in data:
                    found.add(marker.decode())
    return found


def check_no_tts_markers(aar_path, control_path):
    """(a) No espeak-ng/mbrola marker strings in any native library.

    With --control, the same scan must FIND the markers in the control AAR —
    otherwise the scan itself cannot see them, and a silent PASS on the target
    would prove nothing.
    """
    found = _scan_for_markers(aar_path)
    if found:
        return False, f"found TTS/espeak-ng marker string(s) in jni/*/*.so: {sorted(found)}"
    if control_path:
        control_found = _scan_for_markers(control_path)
        if not control_found:
            return False, "the check cannot see the markers"
        return True, f"no markers in the target; control AAR shows the markers ({sorted(control_found)}), so the scan can see them"
    return True, "no espeak-ng/mbrola marker strings found in jni/*/*.so"


def check_both_abis_present(aar_path):
    """(b) jni/arm64-v8a/ and jni/x86_64/ each hold at least one .so."""
    with zipfile.ZipFile(aar_path) as zf:
        names = zf.namelist()
    missing = [abi for abi in REQUIRED_ABIS if not any(n.startswith(abi) and n.endswith(".so") for n in names)]
    if missing:
        return False, f"no .so file found under: {missing}"
    return True, "jni/arm64-v8a/ and jni/x86_64/ each hold at least one .so"


def _classes_jar_simple_names(aar_path):
    with zipfile.ZipFile(aar_path) as zf:
        if "classes.jar" not in zf.namelist():
            return None
        jar_bytes = zf.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        class_entries = [n for n in jar.namelist() if n.endswith(".class")]
    return [n.rsplit("/", 1)[-1][: -len(".class")] for n in class_entries]


def check_recognizer_classes(aar_path):
    """(c) classes.jar has OnlineRecognizer and OfflineRecognizer, no TTS/diarization classes."""
    names = _classes_jar_simple_names(aar_path)
    if names is None:
        return False, "classes.jar not found in the AAR"
    has_online = any("OnlineRecognizer" in n for n in names)
    has_offline = any("OfflineRecognizer" in n for n in names)
    disallowed = sorted({n for n in names if n.startswith("OfflineTts") or n.startswith("OfflineSpeakerDiarization")})
    if not has_online:
        return False, "no class name containing 'OnlineRecognizer' found in classes.jar"
    if not has_offline:
        return False, "no class name containing 'OfflineRecognizer' found in classes.jar"
    if disallowed:
        return False, f"found disallowed TTS/diarization class(es) in classes.jar: {disallowed}"
    return True, "OnlineRecognizer and OfflineRecognizer present; no OfflineTts/OfflineSpeakerDiarization classes"


def check_notice_and_licenses(aar_path):
    """(d) NOTICE.md and a non-empty licenses/ exist; every licence file is named in NOTICE.md."""
    with zipfile.ZipFile(aar_path) as zf:
        names = zf.namelist()
        if "NOTICE.md" not in names:
            return False, "NOTICE.md not found in the AAR"
        license_files = [n for n in names if n.startswith("licenses/") and not n.endswith("/")]
        if not license_files:
            return False, "licenses/ is missing or empty in the AAR"
        notice_text = zf.read("NOTICE.md").decode("utf-8", errors="replace")
    missing = sorted(lf.split("/")[-1] for lf in license_files if lf.split("/")[-1] not in notice_text)
    if missing:
        return False, f"licence file(s) not named in NOTICE.md: {missing}"
    return True, f"NOTICE.md present, all {len(license_files)} licence file(s) named in it"


def _normalize(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


def _notice_known_component_names(notice_text):
    """Component names NOTICE.md accounts for: its table's first column, plus
    the names in its 'downloaded but not linked' paragraph."""
    names = []
    in_table = False
    for line in notice_text.splitlines():
        stripped = line.strip()
        if stripped.startswith("| Component"):
            in_table = True
            continue
        if not in_table:
            continue
        if not stripped.startswith("|"):
            in_table = False
            continue
        if stripped.startswith("|---"):
            continue
        cell = stripped.split("|")[1]
        cell = re.sub(r"\(.*?\)", "", cell)
        cell = cell.strip().strip("`").strip()
        if cell:
            names.append(cell)

    not_linked = re.search(r"NOT linked into any library[^:]*:(.*?)(?:\n\n|$)", notice_text, re.S)
    if not_linked:
        segment = not_linked.group(1)
        segment = segment.split(". ")[0]
        for token in re.split(r",| and ", segment):
            token = re.sub(r"\(.*?\)", "", token).strip().rstrip(".").strip()
            if token:
                names.append(token)
    return names


def check_build_log_downloads_are_named(build_log_path, notice_path):
    """(e) Every 'Downloading <name> ' build-log line names a component NOTICE.md accounts for."""
    with open(build_log_path, encoding="utf-8", errors="replace") as fh:
        log_text = fh.read()
    # Only CMake's own "-- Downloading <name> ..." lines name a third-party component;
    # other tools in the same log ("Downloading https://...", "Downloading Android CLI")
    # are build tooling, not code that ends up in the package.
    downloaded = sorted(set(re.findall(r"^-- Downloading\s+(\S+)\s", log_text, re.M)))
    if not downloaded:
        return True, "no 'Downloading <name> ' lines found in the build log"

    with open(notice_path, encoding="utf-8") as fh:
        notice_text = fh.read()
    known = [_normalize(n) for n in _notice_known_component_names(notice_text)]
    known = [n for n in known if n]

    missing = []
    for name in downloaded:
        norm = _normalize(name)
        if not any(norm in k or k in norm for k in known):
            missing.append(name)
    if missing:
        return False, f"downloaded component(s) not named in NOTICE.md: {missing}"
    return True, f"all {len(downloaded)} downloaded component(s) named in NOTICE.md: {downloaded}"


def _run(label, result):
    ok, reason = result
    status = "PASS" if ok else "FAIL"
    print(f"{status}: {label} — {reason}")
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("aar", help="path to the AAR to verify")
    parser.add_argument("--build-log", help="path to a build log, to check CMake download lines against NOTICE.md (check e)")
    parser.add_argument("--control", help="path to the upstream (TTS-enabled) AAR, to prove check (a)'s scan can see the markers it is looking for")
    args = parser.parse_args()

    all_ok = True

    all_ok &= _run("no TTS/espeak-ng markers in native libraries", check_no_tts_markers(args.aar, args.control))
    all_ok &= _run("both ABIs present", check_both_abis_present(args.aar))
    all_ok &= _run("recognizer classes correct", check_recognizer_classes(args.aar))
    all_ok &= _run("NOTICE.md / licenses/ consistent", check_notice_and_licenses(args.aar))

    if args.build_log:
        with zipfile.ZipFile(args.aar) as zf:
            if "NOTICE.md" not in zf.namelist():
                all_ok &= _run("build-log downloads named in NOTICE.md", (False, "NOTICE.md not found in the AAR, cannot cross-check"))
            else:
                with tempfile.TemporaryDirectory() as tmp:
                    notice_path = os.path.join(tmp, "NOTICE.md")
                    with open(notice_path, "wb") as out:
                        out.write(zf.read("NOTICE.md"))
                    all_ok &= _run(
                        "build-log downloads named in NOTICE.md",
                        check_build_log_downloads_are_named(args.build_log, notice_path),
                    )
    else:
        print("SKIP: build-log downloads named in NOTICE.md — no --build-log given")

    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
