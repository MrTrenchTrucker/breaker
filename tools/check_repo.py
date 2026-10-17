#!/usr/bin/env python3
"""Breaker repo consistency checker.

Fails if the tree drifts from modules.toml. Run after any structural change:

    python3 tools/check_repo.py

Exit code 0 = consistent. Exit code 1 = problems listed.
"""
import glob
import os
import re
import sys
import tomllib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REQUIRED_CARD_SECTIONS = [
    "## Purpose", "## Owns", "## Does Not Own", "## Public Interface",
    "## Depends On", "## Invariants", "## Test Locations",
    "## Test Requirement", "## Known Gotchas",
]

errors = []

def _sub_modules_named_in(readme_text):
    """The elements of a parent README's `**Sub-modules:**` line, in order.

    None when the line is absent. The line is the single line that starts
    with the marker; its body is the comma-separated list up to (not
    including) the " — " tail, e.g. " — each with its own AGENTS.md +
    README.md".
    """
    for line in readme_text.splitlines():
        line = line.strip()
        if line.startswith("**Sub-modules:**"):
            body = line[len("**Sub-modules:**"):].split(" — ")[0]
            return [name.strip() for name in body.split(",") if name.strip()]
    return None


# --- Gradle / build-file helpers -----------------------------------------

def _gradle_path(path):
    # Convert a filesystem module path (":a:b") via "/".replace(":").
    return ":" + path.replace("/", ":")


def _settings_includes():
    """Set of project paths declared with include(":a:b") in settings.gradle.kts.

    Empty set when the file is absent (never crash on a tree without Gradle).
    """
    try:
        text = open(os.path.join(ROOT, "settings.gradle.kts"), encoding="utf-8").read()
    except FileNotFoundError:
        return set()
    return set(re.findall(r'include\("(:[^"]+)"\)', text))


def _project_deps(rel):
    """Set of project(":a:b") dependencies declared in one build.gradle.kts.

    Empty set when the file is absent.
    """
    try:
        text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
    except FileNotFoundError:
        return set()
    return set(re.findall(r'project\("(:[^"]+)"\)', text))


def _all_build_files():
    """Every build.gradle.kts reachable from the repo root, as a relative path."""
    found = []
    for _, dirs, files in os.walk(ROOT):
        if "build.gradle.kts" in files:
            rel = os.path.relpath(os.path.join(_, "build.gradle.kts"), ROOT)
            found.append(rel)
    return sorted(found)


def _naming_home(path):
    # nearest ancestor folder (from dirname(P) up to ROOT) with a README that has a Sub-modules line
    d = os.path.dirname(path)
    while True:
        readme = os.path.join(ROOT, d, "README.md") if d != "." else os.path.join(ROOT, "README.md")
        if os.path.isfile(readme):
            text = open(readme, encoding="utf-8").read()
            if _sub_modules_named_in(text) is not None:   # has a **Sub-modules:** line
                return d
        if d == "." or d == "":
            return None   # no ancestor carries a Sub-modules list
        d = os.path.dirname(d)


def main():
    with open(os.path.join(ROOT, "modules.toml"), "rb") as f:
        reg = tomllib.load(f)

    # [module.*] tables nest under the top-level "module" table
    modules = reg.get("module", {})
    if not modules:
        errors.append("modules.toml: no [module.*] entries found")
        sys.exit(1)

    # status scan: register dead modules so their extra checks run below.
    # A status other than "dead" or None is an invalid registry value.
    dead = {}
    for key, m in modules.items():
        status = m.get("status")
        if status is None:
            continue
        if status == "dead":
            dead[key] = m
        else:
            errors.append(f"{key}: unknown status {status!r} (expected 'dead' or absent)")

    for key, m in modules.items():
        path = m["path"]
        folder = os.path.join(ROOT, path)
        if not os.path.isdir(folder):
            errors.append(f"{key}: folder missing -> {path}")
            continue
        card = os.path.join(folder, "AGENTS.md")
        if not os.path.isfile(card):
            errors.append(f"{key}: AGENTS.md missing -> {path}/AGENTS.md")
        else:
            with open(card) as fh:
                text = fh.read()
            for sec in REQUIRED_CARD_SECTIONS:
                if sec not in text:
                    errors.append(f"{key}: card missing section '{sec}'")
        if not os.path.isfile(os.path.join(folder, "README.md")):
            errors.append(f"{key}: README.md missing -> {path}/README.md")
        for dep in m.get("depends_on", []):
            if dep not in modules:
                errors.append(f"{key}: depends_on '{dep}' not a registered module")

        # Dead-module guards: only run for modules whose registry status == "dead".
        # A dead module is neither required to have a build file nor forbidden from
        # one; the only coupling allowed is that
        # nothing may include() it or carry a project() edge TO it.
        if key in dead:
            # (a) DEAD_CODE.md must exist and be non-empty.
            dc = os.path.join(folder, "DEAD_CODE.md")
            if not os.path.isfile(dc):
                errors.append(f"{key}: dead module has no DEAD_CODE.md -> {path}/DEAD_CODE.md")
            else:
                with open(dc, encoding="utf-8") as dfh:
                    dc_text = dfh.read()
                if not dc_text.strip():
                    errors.append(f"{key}: DEAD_CODE.md is empty -> {path}/DEAD_CODE.md")

            # (b) exact whole-match, never prefix/substring.
            if _gradle_path(m["path"]) in _settings_includes():
                errors.append(
                    f"{key}: dead module is include()d in settings.gradle.kts "
                    f"({_gradle_path(m['path'])}) — a dead module is not built"
                )

            # (c) nothing may depend_on a dead module via the registry.
            for ok, ov in modules.items():
                if key in ov.get("depends_on", []):
                    errors.append(
                        f"{ok}: depends_on names dead module {key} — nothing may depend on a dead module"
                    )

            # (d) exact whole-match, never prefix/substring.
            for bf in _all_build_files():
                if _gradle_path(m["path"]) in _project_deps(bf):
                    errors.append(
                        f"{bf}: project() edge to dead module {key} — nothing may depend on a dead module"
                    )

    # dependency cycle check (DFS)
    #
    # Only registered keys go into the adjacency: a depends_on that names an
    # unregistered module is already listed as an error above, and letting it
    # into the DFS would index `color` with a key that was never colored
    # (KeyError, no problems listed). Filtering keeps that error in the printed
    # list and the cycle check total.
    adj = {k: [d for d in v.get("depends_on", []) if d in modules]
           for k, v in modules.items()}
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {k: WHITE for k in adj}
    def dfs(u):
        color[u] = GRAY
        for v in adj[u]:
            if color[v] == GRAY:
                errors.append(f"dependency cycle: {u} -> {v}")
                return
            if color[v] == WHITE:
                dfs(v)
        color[u] = BLACK
    for u in adj:
        if color[u] == WHITE:
            dfs(u)

    # every folder with AGENTS.md is registered
    for root, dirs, files in os.walk(ROOT):
        if "AGENTS.md" in files:
            rel = os.path.relpath(root, ROOT)
            if rel.startswith(".") or rel.startswith("docs") or rel.startswith("decisions") or rel.startswith("tools"):
                continue
            if rel != "." and not any(m["path"] == rel for m in modules.values()):
                errors.append(f"unregistered module folder: {rel}")

    # Sub-module naming: each module is named in the README of its NEAREST
    # ANCESTOR folder that carries a `**Sub-modules:**` line, NOT necessarily
    # the android/server/shared top README. A sub-module (commit/ime) must be
    # named in commit/README.md; the top android/README is only the home when
    # no nearer ancestor carries the list. Dead sub-modules are registered too,
    # so they carry the same naming obligation (a dead module is still a module).
    for key, m in modules.items():
        if not (m["path"].startswith("android/") or
                m["path"].startswith("server/") or
                m["path"].startswith("shared/")):
            continue
        home = _naming_home(m["path"])
        if home is None:
            errors.append(
                f"{key}: no ancestor README carries a '**Sub-modules:**' list "
                f"to name '{m['path'].split('/')[-1]}'"
            )
            continue
        home_readme = os.path.join(ROOT, home, "README.md")
        try:
            with open(home_readme) as fh:
                rt = fh.read()
        except FileNotFoundError:
            errors.append(f"{key}: naming home {home} has no README.md")
            continue
        named = _sub_modules_named_in(rt)
        leaf = m["path"].split("/")[-1]
        if leaf not in (named or []):
            errors.append(
                f"{home}/README.md does not name sub-module '{leaf}' "
                f"in its **Sub-modules:** list"
            )

    # Stale-list check: every name in any home README's **Sub-modules:** list must be
    # the leaf of a registered module whose own naming home is that same home.
    # A home is any folder (under android/, server or shared/) whose README carries
    # the line — including nested homes like commit/ once they gain a list. This
    # replaces the old two-loop over top-3 parents only, which both emitted an error
    # per stale name and never checked nested homes.
    def _homes():
        hs = []
        for dirpath, _dirs, files in os.walk(ROOT):
            if "README.md" not in files:
                continue
            rel = os.path.relpath(dirpath, ROOT)
            if rel == ".":
                continue
            if not (rel.startswith("android") or rel.startswith("server") or rel.startswith("shared")):
                continue
            with open(os.path.join(dirpath, "README.md"), encoding="utf-8") as fh:
                if _sub_modules_named_in(fh.read()) is not None:
                    hs.append(rel)
        return sorted(hs)

    for home in _homes():
        with open(os.path.join(ROOT, home, "README.md"), encoding="utf-8") as fh:
            named = _sub_modules_named_in(fh.read()) or []
        for name in sorted(set(named)):
            leaf_modules = [m["path"] for m in modules.values() if m["path"].split("/")[-1] == name]
            if not any(_naming_home(p) == home for p in leaf_modules):
                errors.append(
                    f"{home}/README.md names '{name}' in its **Sub-modules:** list, "
                    f"which no registered sub-module of {home} carries"
                )

    # README doc references resolve.
    #
    # Two ref shapes occur in the README: a dashed slug (`docs/04-build-order.md`)
    # resolves to that exact file, and a bare number (`docs/07`, as in the
    # "`docs/00`-`docs/07`" range line) resolves to the spec file
    # `docs/07-*.md`. Both must be checked; a bare number used to fall through
    # the dashed-only regex and pass silently when its spec was deleted.
    with open(os.path.join(ROOT, "README.md")) as fh:
        readme_text = fh.read()
    for ref in re.findall(r"`(docs/\d+(?:-[^`]+)?)`", readme_text):
        if "-" in ref:
            if not os.path.isfile(os.path.join(ROOT, ref)):
                errors.append(f"README references missing doc: {ref}")
        else:
            if not glob.glob(os.path.join(ROOT, ref + "-*.md")):
                errors.append(
                    f"README references missing doc: {ref} (no {ref}-*.md)"
                )

    if errors:
        print("REPO INCONSISTENT — %d problem(s):" % len(errors))
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print("REPO CONSISTENT: %d modules, all cards + READMEs present, no cycles." % len(modules))
    sys.exit(0)

if __name__ == "__main__":
    main()
