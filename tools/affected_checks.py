#!/usr/bin/env python3
"""Decide which Gradle tasks a change needs: the modules it touches and the modules that use them.

    python3 tools/affected_checks.py                      # the Gradle tasks (default)
    python3 tools/affected_checks.py --explain            # why each module is in the plan
    python3 tools/affected_checks.py --json               # the whole plan as JSON
    python3 tools/affected_checks.py --mode               # full, targeted or none
    python3 tools/affected_checks.py --base A --head B    # any commit range

Without --base, the base is the merge-base of origin/main and --head.

How a change becomes a plan:
  1. Each changed file belongs to the DEEPEST module in modules.toml whose path contains it.
  2. The callers of a module are the modules that list it in depends_on, plus the modules
     whose Gradle build reads files under it (test inputs and copy tasks; the repo paths are
     read from each module's build.gradle.kts). Callers are followed transitively.
  3. Every planned module runs its own `check` task: its unit tests and its lint, for Android,
     JVM and base-only projects alike, so no per-plugin test task name is needed. `check` is
     `build` without the assemble step. The full run (main, nightly, manual) still runs `build`.
  4. The whole build (mode full, task `build`) runs when a change touches a build input or a
     gate file, a file that the root Gradle build reads, a path outside every module that is
     not a known safe path, more than 12 modules, or when the diff cannot be read. Failing
     safe means running everything, never nothing.

Exit codes: 0 plan printed. 2 the registry or the settings file could not be read.
Stdlib only. Python 3.12 (tomllib).
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tomllib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FULL_TASK = "build"      # the whole build: main, nightly and manual runs
PROJECT_TASK = "check"   # a planned project: its tests and lint, not its assemble step
MAX_TOUCHED = 12

BUILD_INPUT_NAMES = {"settings.gradle.kts", "gradle.properties", "libs.versions.toml"}
BUILD_INPUT_SUFFIXES = (".gradle.kts", ".gradle")
FULL_PREFIXES = ("gradle/", "buildSrc/", ".github/workflows/")
FULL_EXACT = {"modules.toml", "tools/affected_checks.py"}
SAFE_EXACT = {"README.md", ".github/CONTRIBUTING.md"}
SAFE_PREFIXES = ("docs/", "decisions/", "tests/", "tools/")

_SUFFIXES = (
    ".md", ".svg", ".png", ".yaml", ".yml", ".toml", ".json", ".properties", ".kt",
    ".kts", ".java", ".xml", ".txt", ".html", ".js", ".mjs", ".css", ".sha256", ".py",
    ".onnx", ".pem", ".sh",
)
_STRING = re.compile(r'"([^"\n]*)"')
_INCLUDE = re.compile(r'include\("(:[^"]+)"\)')


class GitError(RuntimeError):
    pass


class Plan:
    def __init__(self):
        self.mode = "none"
        self.reason = ""
        self.full_reasons = []
        self.touched = []
        self.dependents = []
        self.why = {}
        self.tasks = []
        self.rows = []
        self.safe_files = []
        self.skipped = []
        self.test_projects = []
        self.changed = 0

    def as_dict(self):
        return {
            "mode": self.mode,
            "reason": self.reason,
            "changed": self.changed,
            "touched": self.touched,
            "dependents": self.dependents,
            "tasks": self.tasks,
            "full_reasons": sorted(set(self.full_reasons)),
            "safe_files": self.safe_files,
            "skipped": self.skipped,
            "test_projects": self.test_projects,
        }


# --- pure layer: no git, no disk ----------------------------------------------

def gradle_project(path):
    """The Gradle project path of a module path: android/modules/core -> :android:modules:core."""
    return ":" + path.replace("/", ":")


def _under(path, base):
    return path == base or path.startswith(base + "/")


def deepest_module(path, paths):
    """The module key whose path is the longest one that contains `path`, or None."""
    best = None
    for key, mpath in paths.items():
        if _under(path, mpath) and (best is None or len(mpath) > len(paths[best])):
            best = key
    return best


def in_registry(literal, paths):
    """True when a Gradle-read repo path sits inside the module tree (a module, a folder
    holding modules, or a file inside a module). False for a path outside every module."""
    return any(_under(p, literal) or _under(literal, p) for p in paths.values())


def _is_build_input(path):
    name = path.rsplit("/", 1)[-1]
    return (
        name in BUILD_INPUT_NAMES
        or name.startswith("gradlew")
        or name.endswith(BUILD_INPUT_SUFFIXES)
        or path.startswith(FULL_PREFIXES)
        or path in FULL_EXACT
    )


def plan(changed, registry, reads, root_reads, gradle_projects, tested=frozenset(), max_touched=MAX_TOUCHED):
    """The plan for a list of changed repo paths. Pure: no git, no disk.

    registry      {key: {"path": str, "depends_on": [keys], "status": optional str}}
    reads         {key: [repo path or folder]} read by each module's build file
    root_reads    [repo path or folder] read by the root Gradle files
    gradle_projects  set of Gradle project paths, e.g. {":android:modules:core"}
    tested        keys of the modules that have Kotlin or Java test sources
    """
    paths = {k: v["path"] for k, v in registry.items()}
    out = Plan()
    files = sorted(set(changed))
    out.changed = len(files)
    if not files:
        out.reason = "no changed files"
        return out

    outside_reads = sorted({lit for lits in reads.values() for lit in lits if not in_registry(lit, paths)})
    direct = {}
    for f in files:
        reasons = []
        if _is_build_input(f):
            reasons.append("a build input or a gate file")
        if any(_under(f, lit) for lit in root_reads):
            reasons.append("read by the root Gradle build")
        owner = deepest_module(f, paths)
        if owner is not None:
            direct.setdefault(owner, []).append(f)
            out.rows.append((f, owner))
        else:
            if any(_under(f, lit) for lit in outside_reads):
                reasons.append("read by a module build, outside every module")
            elif f in SAFE_EXACT or f.startswith(SAFE_PREFIXES):
                out.safe_files.append(f)
                out.rows.append((f, None))
            else:
                reasons.append("outside every module and not a known safe path")
        for why in reasons:
            out.full_reasons.append(f"{f}: {why}")

    touched = sorted(direct)
    if len(touched) > max_touched:
        out.full_reasons.append(f"{len(touched)} modules touched (limit {max_touched})")

    # A dead module is kept out of the build on purpose, so nothing is its caller.
    live = {k for k, v in registry.items() if v.get("status") != "dead"}
    depends_callers = {}
    for key, spec in registry.items():
        if key not in live:
            continue
        for dep in spec.get("depends_on", []):
            if dep in live and dep != key:
                depends_callers.setdefault(dep, set()).add(key)

    # A module whose Gradle build reads a changed file (a test input or a copy source)
    # is a caller of that change, even when it does not list the module in depends_on.
    seen = set(touched)
    queue = list(touched)
    dependents = []
    why = {}
    for key in sorted(reads):
        if key not in live or key in seen:
            continue
        hit = next((f for f in files if any(_under(f, lit) for lit in reads[key])), None)
        if hit is not None:
            seen.add(key)
            dependents.append(key)
            queue.append(key)
            why[key] = f"reads changed file {hit}"
    while queue:
        mod = queue.pop(0)
        for caller in sorted(depends_callers.get(mod, ())):
            if caller in seen:
                continue
            seen.add(caller)
            dependents.append(caller)
            queue.append(caller)
            why[caller] = f"depends on {mod}"

    out.touched = touched
    out.dependents = dependents
    out.why = why
    tasks = []
    tests = []
    for key in sorted(seen):
        proj = gradle_project(paths[key])
        if registry[key].get("status") == "dead" or proj not in gradle_projects:
            out.skipped.append(key)
            continue
        tasks.append(proj + ":" + PROJECT_TASK)
        if key in tested:
            tests.append(proj)
    out.tasks = sorted(tasks)
    out.test_projects = sorted(tests)

    if out.full_reasons:
        out.mode = "full"
        out.tasks = [FULL_TASK]
        out.reason = "; ".join(sorted(set(out.full_reasons)))
        # A full run builds every live project, so every tested project is planned.
        out.test_projects = sorted(
            gradle_project(paths[k]) for k in registry
            if k in tested and registry[k].get("status") != "dead" and gradle_project(paths[k]) in gradle_projects
        )
    elif not out.tasks:
        out.mode = "none"
        if touched:
            out.reason = "touched modules have no Gradle project"
        else:
            out.reason = "only files that no Gradle build reads"
    else:
        out.mode = "targeted"
        out.reason = f"{len(touched)} touched module(s), {len(dependents)} caller(s)"
    return out


# --- repo layer: registry, settings and build files from disk -------------------

def repo_path_literal(text):
    """A quoted string in a build file that names a repo path, or None.

    Glob patterns, placeholders, URLs, variables and absolute paths are not repo paths.
    """
    s = text.strip()
    if not s or any(x in s for x in ("://", "$", "{", "\\", "*", "<", ">")) or s.startswith("/"):
        return None
    if s.startswith("./"):
        s = s[2:]
    s = s.rstrip("/")
    if not s or ".." in s.split("/"):
        return None
    if "/" in s or s.endswith(_SUFFIXES):
        return s
    return None


def _literals(path):
    """Repo paths named in one Gradle script. Full-line comments are skipped."""
    with open(path, encoding="utf-8") as fh:
        lines = [ln for ln in fh.read().splitlines() if not ln.lstrip().startswith("//")]
    found = set()
    for m in _STRING.finditer("\n".join(lines)):
        lit = repo_path_literal(m.group(1))
        if lit:
            found.add(lit)
    return sorted(found)


def _settings_projects(root):
    projects = set()
    with open(os.path.join(root, "settings.gradle.kts"), encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if not s.startswith("include("):
                continue
            m = _INCLUDE.match(s)
            if m is None:
                raise ValueError("settings.gradle.kts: an include() line has no parseable path")
            projects.add(m.group(1))
    return projects


def _has_kotlin_tests(folder):
    """True when a folder holds at least one Kotlin or Java source file."""
    for _, _, files in os.walk(folder):
        if any(f.endswith((".kt", ".java")) for f in files):
            return True
    return False


def load_repo(root):
    with open(os.path.join(root, "modules.toml"), "rb") as fh:
        registry = tomllib.load(fh).get("module", {})
    for key, spec in registry.items():
        if "path" not in spec:
            raise ValueError(f"modules.toml: [module.{key}] has no path")
    reads = {}
    for key, spec in registry.items():
        build = os.path.join(root, spec["path"], "build.gradle.kts")
        if os.path.isfile(build):
            # A bare file name in a module build is module-relative or an exclude pattern
            # (web-fe excludes AGENTS.md and README.md); the root build inputs are covered
            # by name in plan(), so only paths with a folder part count here.
            reads[key] = [lit for lit in _literals(build) if "/" in lit]
    root_files = [os.path.join(root, "settings.gradle.kts"), os.path.join(root, "build.gradle.kts")]
    root_files += sorted(glob.glob(os.path.join(root, "gradle", "*.kts")))
    root_reads = set()
    for f in root_files:
        if os.path.isfile(f):
            root_reads.update(_literals(f))
    tested = {k for k, s in registry.items() if _has_kotlin_tests(os.path.join(root, s["path"], "src", "test"))}
    return registry, _settings_projects(root), reads, sorted(root_reads), tested


# --- git layer -----------------------------------------------------------------

def _git(root, *args):
    proc = subprocess.run(["git", "-C", root, *args], capture_output=True)
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip() or "git failed"
        raise GitError(detail)
    return proc.stdout.decode("utf-8", "surrogateescape")


def _rev(text):
    if not text or text.startswith("-"):
        raise GitError(f"not a revision: {text!r}")
    return text


def changed_files(root, base, head):
    out = _git(root, "diff", "-z", "--name-only", "--no-renames", f"{_rev(base)}...{_rev(head)}")
    return [p for p in out.split("\0") if p]


def build_plan(root, base, head):
    registry, gradle_projects, reads, root_reads, tested = load_repo(root)
    try:
        if base is None:
            base = _git(root, "merge-base", "origin/main", _rev(head)).strip()
        files = changed_files(root, base, head)
    except GitError as err:
        out = Plan()
        out.mode = "full"
        out.tasks = [FULL_TASK]
        out.full_reasons = [f"the diff could not be read: {err}"]
        out.reason = out.full_reasons[0]
        return out, base, head
    return plan(files, registry, reads, root_reads, gradle_projects, tested), base, head


def explain_lines(p, base, head):
    lines = [f"affected checks: mode={p.mode}"]
    lines.append(f"range: {base or '?'}...{head}")
    lines.append(f"changed files: {p.changed}")
    for f, owner in p.rows:
        lines.append(f"file {f} -> module {owner}" if owner else f"file {f} -> safe (no Gradle build reads it)")
    if p.touched:
        lines.append("touched: " + " ".join(p.touched))
    for caller in p.dependents:
        lines.append(f"caller {caller}: {p.why.get(caller, '')}")
    for key in p.skipped:
        lines.append(f"skipped {key}: dead or no Gradle project")
    lines.append(f"reason: {p.reason}")
    lines.append("tests: " + (" ".join(p.test_projects) if p.test_projects else "(none: no planned project has Kotlin tests)"))
    lines.append("tasks: " + (" ".join(p.tasks) if p.tasks else "(none)"))
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description="Gradle tasks for a change: touched modules and their callers.")
    ap.add_argument("--base", help="base commit (default: merge-base of origin/main and --head)")
    ap.add_argument("--head", default="HEAD", help="head commit (default: HEAD)")
    ap.add_argument("--root", default=ROOT, help=argparse.SUPPRESS)
    fmt = ap.add_mutually_exclusive_group()
    fmt.add_argument("--gradle", dest="fmt", action="store_const", const="gradle",
                     help="print the Gradle tasks, space separated (default)")
    fmt.add_argument("--json", dest="fmt", action="store_const", const="json", help="print the plan as JSON")
    fmt.add_argument("--explain", dest="fmt", action="store_const", const="explain",
                     help="print why each module is in the plan")
    fmt.add_argument("--mode", dest="fmt", action="store_const", const="mode",
                     help="print full, targeted or none")
    fmt.add_argument("--test-projects", dest="fmt", action="store_const", const="tests",
                     help="print the planned Gradle projects that have Kotlin or Java tests")
    ap.set_defaults(fmt="gradle")
    args = ap.parse_args(argv)
    try:
        p, base, head = build_plan(args.root, args.base, args.head)
    except (OSError, ValueError) as err:
        print(f"affected_checks: {err}", file=sys.stderr)
        return 2
    if args.fmt == "gradle":
        print(" ".join(p.tasks))
    elif args.fmt == "mode":
        print(p.mode)
    elif args.fmt == "tests":
        print(" ".join(p.test_projects))
    elif args.fmt == "json":
        print(json.dumps(p.as_dict(), indent=2, sort_keys=True))
    else:
        print("\n".join(explain_lines(p, base, head)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
