#!/usr/bin/env python3
"""Breaker repo gate: one command that runs every test tier and fails loudly.

Run from anywhere in the repo:

    python3 tools/gate.py                 # full gate (needs JDK/Gradle in env)
    python3 tools/gate.py --skip-gradle   # local shortcut: never reads as a pass
    python3 tools/gate.py --max-workers 4 # pass --max-workers=N to Gradle

Steps, in order (each exit code captured directly, never through a pipe):
  1. check_repo      python3 tools/check_repo.py
  2. contract tier   python3 -m unittest discover -s tests/contract -t tests/contract
  3. unit tier       one discover run per folder under tests/unit that holds test_*.py
  4. gradle          ./gradlew --no-daemon --no-build-cache --rerun-tasks build
  5. junit xml       per-sub-project counts from build/test-results/**/TEST-*.xml
  6. nul check       tracked .kt/.kts/.py/.md/.toml/.yaml files, read as bytes
  7. orphan check    every test_*.py under tests/ must be loaded by exactly one step

RED when: any step exits non-zero; a contract or unit folder holding test_*.py
runs 0 tests; a test_*.py under tests/ is loaded by no step (orphan) or by more
than one (double reach); a sub-project with test sources (a test-only project
counts) produced no fresh JUnit XML (NO-SOURCE); a fresh JUnit XML records
failures or errors; java is missing; an include() line has no parseable path;
git ls-files fails; a tracked file of the checked types contains a NUL byte.

Exit 0 only on GATE: GREEN. --skip-gradle prints GATE: PARTIAL (gradle skipped)
and exits non-zero, so it can never read as a pass; but a RED reason always
wins over PARTIAL, so --skip-gradle with a real RED prints the RED verdict.

Stdlib only; runs on Python 3.12.
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INCLUDE_ARG_RE = re.compile(r'"([^"]+)"')


# -- discovery helpers (pure, testable) ---------------------------------------

def _test_files_in(folder):
    """test_*.py files directly inside folder (not in subfolders)."""
    if not os.path.isdir(folder):
        return []
    out = []
    for name in sorted(os.listdir(folder)):
        p = os.path.join(folder, name)
        if name.startswith("test_") and name.endswith(".py") and os.path.isfile(p):
            out.append(p)
    return out


def _is_package(folder):
    return os.path.isfile(os.path.join(folder, "__init__.py"))


def loaded_files(start):
    """The test files a `discover -s <start> -t <start>` run would load.

    discover enters a subfolder only while the package chain holds: every
    folder it descends into must hold __init__.py. A subfolder without one is
    NOT loaded, even though it sits under the start dir.
    """
    loaded = list(_test_files_in(start))
    stack = [start]
    while stack:
        cur = stack.pop()
        if not os.path.isdir(cur):
            continue
        for name in sorted(os.listdir(cur)):
            sub = os.path.join(cur, name)
            if os.path.isdir(sub) and _is_package(sub):
                loaded.extend(_test_files_in(sub))
                stack.append(sub)
    return loaded


def test_folders_under(root_tests):
    """Every folder under root_tests that directly holds test_*.py files."""
    folders = []
    if not os.path.isdir(root_tests):
        return folders
    for dirpath, dirnames, filenames in os.walk(root_tests):
        dirnames.sort()
        if any(f.startswith("test_") and f.endswith(".py") for f in filenames):
            folders.append(dirpath)
    return folders


def all_test_files_under(root_tests):
    """Every test_*.py anywhere under root_tests."""
    out = []
    if not os.path.isdir(root_tests):
        return out
    for dirpath, _dirnames, filenames in os.walk(root_tests):
        for f in sorted(filenames):
            if f.startswith("test_") and f.endswith(".py"):
                out.append(os.path.join(dirpath, f))
    return out


def _rel(root, path):
    return os.path.relpath(path, root).replace(os.sep, "/")


# -- step runners --------------------------------------------------------------

def _run(cmd, cwd, env=None):
    """Run a command, capture its exit code directly (no pipes).

    A missing executable becomes exit 127 with a message, never a traceback.
    """
    try:
        proc = subprocess.run(cmd, cwd=cwd, env=env,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True)
    except FileNotFoundError:
        return 127, cmd[0] + ": not found"
    return proc.returncode, proc.stdout or ""


def step_check_repo(root, out):
    code, text = _run([sys.executable, "tools/check_repo.py"], cwd=root)
    tail = [l for l in text.strip().splitlines() if l.strip()][-1:]
    out.append("step 1 check_repo: exit %d%s" % (
        code, (" - " + tail[0]) if tail else ""))
    return code


def _discover_run(folder, root, out, label):
    code, text = _run(
        [sys.executable, "-m", "unittest", "discover",
         "-s", folder, "-t", folder], cwd=root)
    m = re.search(r"Ran (\d+) tests?", text)
    count = int(m.group(1)) if m else 0
    out.append("step %s: exit %d, ran %d" % (label, code, count))
    return code, count


def step_contract(root, out):
    folder = os.path.join(root, "tests", "contract")
    if not os.path.isdir(folder):
        out.append("step 2 contract: exit 1 - tests/contract missing")
        return 1
    code, count = _discover_run(folder, root, out, "2 contract")
    bad = 1 if code != 0 else 0
    if all_test_files_under(folder) and count == 0:
        # discover exits 0 on "Ran 0 tests" on some Pythons; the rule must not
        # depend on the exit code (the same rule the unit tier already uses)
        out.append("  RED: tests/contract holds test_*.py but ran 0 tests")
        bad = 1
    return bad


def step_unit(root, out):
    folders = test_folders_under(os.path.join(root, "tests", "unit"))
    if not folders:
        out.append("step 3 unit: exit 1 - no folder under tests/unit holds test_*.py")
        return 1
    bad = 0
    for i, folder in enumerate(folders, 1):
        code, count = _discover_run(folder, root, out, "3 unit[%d %s]" % (
            i, _rel(root, folder)))
        if code != 0:
            bad = 1
        if _test_files_in(folder) and count == 0:
            # discover exits non-zero on "no tests were run" (1 on 3.12,
            # 5 on 3.14); the rule must not depend on which code it gives
            out.append("  RED: %s holds test_*.py but ran 0 tests"
                       % _rel(root, folder))
            bad = 1
    return bad


def _included_projects(root):
    """Project paths from settings.gradle.kts include() lines.

    Returns (projects, reasons). include() takes any number of ":path" args,
    so every quoted arg on an include line is collected. A line that has
    include( but no parseable path is a RED reason, never skipped silently.
    """
    settings = os.path.join(root, "settings.gradle.kts")
    if not os.path.isfile(settings):
        return [], []
    projects = []
    reasons = []
    with open(settings) as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line or line.startswith("//") or "include(" not in line:
                continue
            args = INCLUDE_ARG_RE.findall(line)
            if not args:
                reasons.append(
                    "settings.gradle.kts:%d include() has no parseable path"
                    % lineno)
                continue
            for a in args:
                # ":a:b" -> a/b (drop the leading ':' that marks a project path)
                projects.append(a.lstrip(":").replace(":", os.sep))
    return projects, reasons


def _dead_modules(root):
    """Dead-module paths from modules.toml (status == "dead")."""
    reg = os.path.join(root, "modules.toml")
    if not os.path.isfile(reg):
        return set()
    import tomllib
    with open(reg, "rb") as fh:
        modules = tomllib.load(fh).get("module", {})
    return {m["path"] for m in modules.values() if m.get("status") == "dead"}


def _has_source(folder, exts):
    if not os.path.isdir(folder):
        return False
    for dirpath, _d, files in os.walk(folder):
        for f in files:
            if f.endswith(exts):
                return True
    return False


def _snapshot_xml(root, projects):
    """Map each existing TEST-*.xml (path -> mtime) before Gradle runs.

    After the run, a file is fresh if it is new (absent from this snapshot) or
    its mtime changed. Nothing is deleted; this is a read-only before-image.
    """
    before = {}
    for proj in projects:
        results = os.path.join(root, proj, "build", "test-results")
        if not os.path.isdir(results):
            continue
        for dirpath, _d, files in os.walk(results):
            for f in files:
                if f.startswith("TEST-") and f.endswith(".xml"):
                    p = os.path.join(dirpath, f)
                    try:
                        before[p] = os.path.getmtime(p)
                    except OSError:
                        pass
    return before


def step_gradle(root, out, max_workers=None):
    """Run Gradle. Returns (exit_code, reason_snippet).

    A missing or broken java is a RED reason on its own (the gradlew call is
    never reached, so the gate cannot crash on a host without a JDK).
    """
    env = dict(os.environ)
    jcode, java = _run(["java", "-version"], cwd=root, env=env)
    first = (java.strip().splitlines() or ["java: not found"])[0]
    out.append("step 4 gradle: java %s" % first)
    if jcode != 0:
        out.append("step 4 gradle: exit %d (java missing or broken)" % jcode)
        return jcode, first
    cmd = ["./gradlew", "--no-daemon", "--no-build-cache", "--rerun-tasks", "build"]
    if max_workers:
        cmd += ["--max-workers=%d" % max_workers]
    code, _text = _run(cmd, cwd=root, env=env)
    out.append("step 4 gradle: exit %d" % code)
    return code, "gradle exited %d" % code


def step_junit(root, out, before, projects):
    """Per-sub-project JUnit counts; NO-SOURCE when test sources made no XML.

    `before` maps each pre-run TEST-*.xml path to its mtime; a file is fresh
    if it is new or its mtime changed. A test-only project (test sources, no
    main sources) is gated like any other. Any recorded failure or error in a
    fresh XML is a RED reason: a passing Gradle exit does not hide a failing
    suite.
    """
    reasons = []
    rows = []
    for proj in projects:
        proj_dir = os.path.join(root, proj)
        test_src = _has_source(os.path.join(proj_dir, "src", "test"),
                              (".kt", ".java"))
        fresh = []
        results = os.path.join(proj_dir, "build", "test-results")
        if os.path.isdir(results):
            for dirpath, _d, files in os.walk(results):
                for f in files:
                    if f.startswith("TEST-") and f.endswith(".xml"):
                        p = os.path.join(dirpath, f)
                        try:
                            m = os.path.getmtime(p)
                        except OSError:
                            continue
                        if p not in before or m != before[p]:
                            fresh.append(p)
        tests = failures = errors = skipped = 0
        for p in fresh:
            try:
                suite = ET.parse(p).getroot()
            except ET.ParseError:
                reasons.append("junit: unparseable XML %s" % _rel(root, p))
                continue
            tests += int(suite.get("tests", 0) or 0)
            failures += int(suite.get("failures", 0) or 0)
            errors += int(suite.get("errors", 0) or 0)
            skipped += int(suite.get("skipped", 0) or 0)
        if failures or errors:
            reasons.append(
                "junit: %s has fail=%d err=%d in fresh JUnit XML"
                % (proj, failures, errors))
        if test_src and not fresh:
            reasons.append(
                "NO-SOURCE: %s has test sources but no fresh JUnit XML" % proj)
        marker = "" if test_src else " (no test sources)"
        rows.append((proj, tests, failures, errors, skipped, marker))
    if rows:
        out.append("per-sub-module JUnit (fresh since before the run):")
        out.append("  %-34s %6s %8s %7s %7s" %
                   ("project", "tests", "fail", "err", "skip"))
        for proj, t, f, e, s, marker in rows:
            out.append("  %-34s %6d %8d %7d %7s%s" % (proj, t, f, e, s, marker))
    return (1 if reasons else 0), reasons


def step_nul(root, out):
    """Returns (exit_code, reason). Four outcomes stay distinct reasons:

    - a git failure -> "git ls-files failed" (the listing never ran);
    - a tracked path that is a directory or otherwise unreadable ->
      "unreadable tracked file(s)" (open failed for a reason other than the
      file being gone);
    - a tracked file that holds a NUL byte -> "NUL byte found in tracked
      file(s)";
    - a tracked path that no longer exists (deleted, not yet staged) is
      skipped: it has no bytes to check, and a working tree may hold an
      unstaged deletion.

    The listing is read NUL-delimited (`git ls-files -z`) so a name is never
    C-quoted or line-split: a non-ASCII name reaches open() as the real path,
    and a name with a newline cannot masquerade as two paths.
    """
    code, text = _run(["git", "ls-files", "-z",
                       "*.kt", "*.kts", "*.py", "*.md", "*.toml", "*.yaml"],
                      cwd=root)
    if code != 0:
        out.append("step 6 nul: exit %d - git ls-files failed" % code)
        return 1, "git ls-files failed"
    bad = []
    unreadable = []
    for line in text.split("\0"):
        if not line:
            continue
        p = os.path.join(root, line)
        if not os.path.exists(p):
            continue  # deleted, not yet staged: nothing to read
        try:
            with open(p, "rb") as fh:
                if b"\x00" in fh.read():
                    bad.append(line)
        except FileNotFoundError:
            continue  # gone between the listing and the open: same as above
        except OSError:
            unreadable.append(line)
    for b in bad:
        out.append("step 6 nul: RED - NUL byte in %s" % b)
    for u in unreadable:
        out.append("step 6 nul: RED - unreadable tracked file %s" % u)
    if not bad and not unreadable:
        out.append("step 6 nul: clean (0 NUL bytes in tracked files)")
    if bad:
        return 1, "NUL byte found in tracked file(s)"
    if unreadable:
        return 1, "unreadable tracked file(s)"
    return 0, None


def step_orphans(root, out, contract_loaded, unit_loaded):
    """Every test_*.py under tests/ must be loaded by exactly one step."""
    coverage = {}
    for f in contract_loaded:
        coverage.setdefault(f, []).append("contract")
    for folder, files in unit_loaded.items():
        for f in files:
            coverage.setdefault(f, []).append("unit:" + _rel(root, folder))
    orphans, doubles = [], []
    for f in all_test_files_under(os.path.join(root, "tests")):
        steps = coverage.get(f, [])
        if not steps:
            orphans.append(f)
        elif len(steps) > 1:
            doubles.append((f, steps))
    for f in orphans:
        out.append("orphan: RED - %s is loaded by no step" % _rel(root, f))
    for f, steps in doubles:
        out.append("orphan: RED - %s is loaded by %d steps (%s)" % (
            _rel(root, f), len(steps), ", ".join(steps)))
    return (1 if (orphans or doubles) else 0), orphans, doubles


def test_file_report(root, contract_loaded, unit_loaded):
    """Return the per-file coverage lines (printed before the verdict)."""
    lines = ["test files under tests/ and the step that runs them:"]
    coverage = {}
    for f in contract_loaded:
        coverage.setdefault(f, []).append("contract")
    for folder, files in unit_loaded.items():
        for f in files:
            coverage.setdefault(f, []).append("unit:" + _rel(root, folder))
    for f in all_test_files_under(os.path.join(root, "tests")):
        steps = coverage.get(f, [])
        lines.append("  %-60s %s" % (_rel(root, f),
                                     " + ".join(steps) if steps else "ORPHAN"))
    return lines


# -- the gate ------------------------------------------------------------------

def run_gate(root, skip_gradle=False, max_workers=None):
    """Run all steps. Returns (exit_code, output_lines)."""
    out = []
    reasons = []
    root = os.path.abspath(root)

    contract_dir = os.path.join(root, "tests", "contract")
    contract_loaded = loaded_files(contract_dir) if os.path.isdir(contract_dir) else []
    unit_loaded = {}
    for folder in test_folders_under(os.path.join(root, "tests", "unit")):
        unit_loaded[folder] = loaded_files(folder)

    code = step_check_repo(root, out)
    if code != 0:
        reasons.append("check_repo exited %d" % code)

    code = step_contract(root, out)
    if code != 0:
        reasons.append("contract tier exited %d" % code)

    code = step_unit(root, out)
    if code != 0:
        reasons.append("unit tier exited %d" % code)

    if skip_gradle:
        out.append("step 4 gradle: SKIPPED")
        out.append("step 5 junit: SKIPPED")
    else:
        projects, inc_reasons = _included_projects(root)
        reasons.extend(inc_reasons)
        # a dead module is never built or counted, even if include()d by mistake (that mistake is a check_repo/contract RED)
        projects = [p for p in projects if p not in _dead_modules(root)]
        before = _snapshot_xml(root, projects)
        code, gradle_note = step_gradle(root, out, max_workers)
        if code != 0:
            reasons.append(gradle_note)
        code, j_reasons = step_junit(root, out, before, projects)
        reasons.extend(j_reasons)

    code, nul_reason = step_nul(root, out)
    if code != 0:
        reasons.append(nul_reason)

    code, orphans, doubles = step_orphans(root, out, contract_loaded, unit_loaded)
    if code != 0:
        if orphans:
            reasons.append("%d orphan test file(s)" % len(orphans))
        if doubles:
            reasons.append("%d double-reached test file(s)" % len(doubles))

    out.extend(test_file_report(root, contract_loaded, unit_loaded))

    # A RED reason always wins over PARTIAL: --skip-gradle with a real RED
    # prints the RED verdict (and its reasons), never PARTIAL.
    if reasons:
        out.append("GATE: RED — " + "; ".join(reasons))
        return 1, out
    if skip_gradle:
        out.append("GATE: PARTIAL (gradle skipped)")
        return 1, out
    out.append("GATE: GREEN")
    return 0, out


def main(argv=None):
    ap = argparse.ArgumentParser(description="Breaker repo gate")
    ap.add_argument("--skip-gradle", action="store_true",
                    help="local shortcut: skip the Gradle step; never reads as a pass")
    ap.add_argument("--max-workers", type=int, default=None,
                    help="pass --max-workers=N to Gradle")
    ap.add_argument("--root", default=ROOT,
                    help="repo root (default: derived from this file)")
    args = ap.parse_args(argv)
    code, lines = run_gate(args.root, skip_gradle=args.skip_gradle,
                           max_workers=args.max_workers)
    for line in lines:
        print(line)
    return code


if __name__ == "__main__":
    sys.exit(main())
