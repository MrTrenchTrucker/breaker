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


def main():
    with open(os.path.join(ROOT, "modules.toml"), "rb") as f:
        reg = tomllib.load(f)

    # [module.*] tables nest under the top-level "module" table
    modules = reg.get("module", {})
    if not modules:
        errors.append("modules.toml: no [module.*] entries found")
        sys.exit(1)

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

    # parent READMEs name their sub-modules.
    #
    # A child is named only if it is an ELEMENT of the parent README's
    # `**Sub-modules:**` list (the three parent READMEs all carry that one
    # line: comma-separated, before the " — each with ..." tail). Prose
    # anywhere else does not count: the android README's "Kotlin, native
    # Android app ..." sentence names "app" without naming the module, and a
    # substring test let an omitted list entry pass because of it. Direct
    # children (android/app, android/ui) are elements like any other.
    for parent in ("android", "server", "shared"):
        readme = os.path.join(ROOT, parent, "README.md")
        if os.path.isfile(readme):
            with open(readme) as fh:
                rt = fh.read()
            expected = {
                m["path"].split("/")[-1]
                for m in modules.values()
                if m["path"].startswith(parent + "/")
            }
            named = _sub_modules_named_in(rt)
            if named is None:
                errors.append(
                    f"{parent}/README.md has no '**Sub-modules:**' line "
                    f"naming its sub-modules"
                )
                continue
            named_set = set(named)
            for child in sorted(expected - named_set):
                errors.append(
                    f"{parent}/README.md does not name sub-module '{child}' "
                    f"in its **Sub-modules:** list"
                )
            for stale in sorted(named_set - expected):
                errors.append(
                    f"{parent}/README.md names '{stale}' in its "
                    f"**Sub-modules:** list, which is not a registered "
                    f"sub-module of {parent}"
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
