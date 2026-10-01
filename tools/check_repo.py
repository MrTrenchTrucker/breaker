#!/usr/bin/env python3
"""Breaker repo consistency checker.

Fails if the tree drifts from modules.toml. Run after any structural change:

    python3 tools/check_repo.py

Exit code 0 = consistent. Exit code 1 = problems listed.
"""
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
    adj = {k: v.get("depends_on", []) for k, v in modules.items()}
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

    # parent READMEs name their sub-modules
    for parent in ("android", "server", "shared"):
        readme = os.path.join(ROOT, parent, "README.md")
        if os.path.isfile(readme):
            with open(readme) as fh:
                rt = fh.read()
            for m in modules.values():
                p = m["path"]
                if p.startswith(parent + "/") and "/" in p[len(parent) + 1:]:
                    child = p.split("/")[-1]
                    if child not in rt:
                        errors.append(f"{parent}/README.md does not name sub-module '{child}'")

    # README doc references resolve
    with open(os.path.join(ROOT, "README.md")) as fh:
        readme_text = fh.read()
    for ref in re.findall(r"`(docs/\d+-[^`]+)`", readme_text):
        if not os.path.isfile(os.path.join(ROOT, ref)):
            errors.append(f"README references missing doc: {ref}")

    if errors:
        print("REPO INCONSISTENT — %d problem(s):" % len(errors))
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print("REPO CONSISTENT: %d modules, all cards + READMEs present, no cycles." % len(modules))
    sys.exit(0)

if __name__ == "__main__":
    main()
