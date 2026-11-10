#!/usr/bin/env python3
"""Verify vendored noble web-libs files against the upstream npm tarball.

For each noble module under shared/modules/web-libs/ (a folder whose VERSION
has `name` starting `shared_web_libs_noble_` — libsodium is skipped by that
rule, not by a hard-coded name):

  1. read VERSION: source_tarball (a file name only) and source_tarball_sha256;
  2. require source_tarball == "<name>-<version>.tgz" where <name> equals the
     module folder name minus "noble-" (noble-ciphers -> ciphers), so a VERSION
     pointing at another package is a problem;
  3. build the URL https://registry.npmjs.org/@noble/<name>/-/<name>-<version>.tgz
     from the module folder and VERSION `version`; refuse any other scheme/host;
  4. download it with a 30 s timeout, cap at MAX_DOWNLOAD_BYTES (larger = download
     failure, exit 2), sha256 the WHOLE download before opening it, refuse on
     mismatch (exit 1);
  5. read members with tarfile: refuse absolute paths, '..', links, devices and
     non-regular members — each refused member produces a problem line
     ('unsafe tarball member <name>') and exit 1;
  6. EVERY file under the module folder (recursive) except the four metadata
     names below must exist at package/<same relative path> in the tarball and
     be byte-identical (LICENSE, .js, .d.ts, and any stray file included, because
     the web build serves all of them); a file with no tarball member is an
     error; tarball members not present in the folder are fine;
  7. compare bytes in memory (no extraction to disk).

NOT compared (and why): README.md, AGENTS.md, VERSION, build.gradle.kts — the
repo's own metadata, which the web build does not serve (copyWebLibs excludes
exactly these four names).

Usage:
    python3 tools/verify_vendored_libs.py

Exit codes: 0 clean, 1 a mismatch (one line per problem: module, file, what),
2 download failed (network/HTTP/too large). Exit 2 is a flake to re-run, not a
defect; a mismatch (exit 1) is the defect signal.

Download is injected via a `download` parameter (default urlopen with a 30 s
timeout); tests inject a bytes-returning callable — never touches the network.
"""
import hashlib
import io
import os
import re
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEBLIBS = os.path.join(ROOT, "shared", "modules", "web-libs")

MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024   # 50 MiB per tarball
MAX_MEMBER_BYTES = 10 * 1024 * 1024     # 10 MiB per member
DOWNLOAD_TIMEOUT = 30                    # seconds
NAME_RE = re.compile(r"^shared_web_libs_noble_(.+)$")  # ciphers, hashes, curves


def _default_download(url):
    """Real network download with a 30 s timeout (used by main)."""
    return urllib.request.urlopen(url, timeout=DOWNLOAD_TIMEOUT)


def _noble_modules():
    """Folders under web-libs whose VERSION name starts shared_web_libs_noble_.

    libsodium (shared_web_libs_libsodium) is skipped by this rule, not by a
    hard-coded name.
    """
    modules = []
    if not os.path.isdir(WEBLIBS):
        return modules
    for entry in sorted(os.listdir(WEBLIBS)):
        version_path = os.path.join(WEBLIBS, entry, "VERSION")
        if not os.path.isfile(version_path):
            continue
        with open(version_path, encoding="utf-8") as fh:
            text = fh.read()
        fields = {}
        for line in text.splitlines():
            if ": " in line:
                key, _, value = line.partition(": ")
                fields[key.strip()] = value.strip()
        name = fields.get("name", "")
        if NAME_RE.match(name):
            modules.append(entry)
    return modules


def _tarball_name_and_version(source_tarball, version):
    """Return (tarball_name, tarball_version, error) from source_tarball."""
    m = re.match(r"^(.+)-(\d+\.\d+\.\d+)\.tgz$", source_tarball)
    if not m:
        return None, None, f"source_tarball {source_tarball!r} is not <name>-<version>.tgz"
    tarball_name, tarball_version = m.group(1), m.group(2)
    if version != tarball_version:
        return None, None, (
            f"VERSION version {version!r} != tarball {tarball_version!r}"
        )
    return tarball_name, tarball_version, None


def _module_expected_tarball_name(module):
    """Module folder noble-ciphers -> expected tarball name ciphers."""
    if module.startswith("noble-"):
        return module[len("noble-"):]
    return module


def _download(url, download=_default_download):
    """Download url to bytes, one retry on network error.

    `download(url)` must return bytes or a file-like with .read() (the real
    default reads from urllib.request.urlopen with a 30 s timeout; tests inject
    a bytes-returning callable). Reads at most MAX_DOWNLOAD_BYTES + 1 bytes so
    an oversized answer is a download failure.
    """
    last_error = None
    for attempt in (1, 2):
        try:
            response = download(url)
            if hasattr(response, "read"):
                data = response.read(MAX_DOWNLOAD_BYTES + 1)
            else:
                data = response
            if not isinstance(data, (bytes, bytearray, memoryview)):
                data = bytes(data)
            if len(data) > MAX_DOWNLOAD_BYTES:
                return None, f"download exceeded {MAX_DOWNLOAD_BYTES} bytes"
            return bytes(data), None
        except Exception as exc:  # network/HTTP -> retry once
            last_error = exc
    return None, f"download failed after 2 attempts: {last_error}"


def _read_tar_members(data):
    """Return (files, unsafe) from a tarball.

    files: dict of member name -> bytes for safe, regular members.
    unsafe: list of refused member names (absolute, '..', link, device,
    non-regular, oversized).
    """
    files = {}
    unsafe = []
    try:
        tar = tarfile.open(fileobj=io.BytesIO(data), mode="r:gz")
    except tarfile.TarError as exc:
        return None, [f"cannot open tarball: {exc}"]
    with tar:
        for member in tar.getmembers():
            name = member.name
            if member.issym() or member.islnk() or member.isdev():
                unsafe.append(name)
                continue
            if not member.isfile():
                unsafe.append(name)
                continue
            if name.startswith("/") or ".." in name.split("/"):
                unsafe.append(name)
                continue
            if member.size > MAX_MEMBER_BYTES:
                unsafe.append(name)
                continue
            f = tar.extractfile(member)
            if f is None:
                unsafe.append(name)
                continue
            files[name] = f.read()
    return files, unsafe


def _check_module(module, download=_default_download):
    """Return (problems, download_failed) for one noble module.

    problems: list of mismatch/unsafe lines (module is NOT prefixed — caller
    prints it once). download_failed: True if the tarball could not be fetched.
    """
    module_dir = os.path.join(WEBLIBS, module)
    version_path = os.path.join(module_dir, "VERSION")
    fields = {}
    with open(version_path, encoding="utf-8") as fh:
        text = fh.read()
    for line in text.splitlines():
        if ": " in line:
            key, _, value = line.partition(": ")
            fields[key.strip()] = value.strip()

    source_tarball = fields.get("source_tarball", "")
    expected_sha = fields.get("source_tarball_sha256", "")
    version = fields.get("version", "")

    tarball_name, tarball_version, err = _tarball_name_and_version(
        source_tarball, version
    )
    if err:
        return [f"{err}"], False

    expected_tarball_name = _module_expected_tarball_name(module)
    if tarball_name != expected_tarball_name:
        return [
            f"tarball {tarball_name!r} does not match module {module!r} "
            f"(expected {expected_tarball_name!r})"
        ], False

    url = (
        f"https://registry.npmjs.org/@noble/{tarball_name}/-/{source_tarball}"
    )

    data, dl_err = _download(url, download)
    if dl_err:
        return [], True  # download failure — no mismatch problems to report

    actual_sha = hashlib.sha256(data).hexdigest()
    if actual_sha != expected_sha:
        return [
            f"tarball sha256 mismatch: got {actual_sha}, expected {expected_sha}"
        ], False

    problems = []
    tarball_files, unsafe = _read_tar_members(data)
    if tarball_files is None:
        return [f"{u}" for u in unsafe], False

    for name in unsafe:
        problems.append(f"unsafe tarball member {name}")

    # EVERY file under the module folder (recursive) except the four metadata
    # names: the web build serves all of them, so all must match the tarball.
    metadata = {"AGENTS.md", "README.md", "VERSION", "build.gradle.kts"}
    vendored = {}
    for dirpath, _dirnames, filenames in os.walk(module_dir):
        for entry in sorted(filenames):
            if entry in metadata:
                continue
            full = os.path.join(dirpath, entry)
            rel = os.path.relpath(full, module_dir)
            with open(full, "rb") as fh:
                vendored[rel] = fh.read()

    # Every vendored file must exist in the tarball at package/<same path>
    # and be byte-identical.
    for rel, vendored_bytes in vendored.items():
        tarball_path = "package/" + rel
        if tarball_path not in tarball_files:
            problems.append(
                f"{rel} missing from tarball ({tarball_path})"
            )
            continue
        if tarball_files[tarball_path] != vendored_bytes:
            problems.append(
                f"{rel} differs from tarball ({tarball_path})"
            )

    return problems, False


def main(download=_default_download):
    modules = _noble_modules()
    if not modules:
        print("FAIL: no noble modules found under shared/modules/web-libs/")
        return 2

    any_mismatch = False
    any_download_fail = False
    for module in modules:
        problems, dl_fail = _check_module(module, download)
        if dl_fail:
            any_download_fail = True
            print(f"FAIL: {module} — download failed")
            continue
        if problems:
            any_mismatch = True
            for problem in problems:
                print(f"FAIL: {module} — {problem}")
        else:
            print(f"PASS: {module} — all vendored files byte-identical to tarball")

    if any_mismatch:
        return 1
    if any_download_fail:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
