"""Print the module-status rollup from docs/module-status.json and check it against modules.toml.

The rule (project decision, 2026-11-10): every LIVE registry entry counts, and a module with no code is
NOT_STARTED, never left out of the denominator. Two kinds of entry are listed apart and not counted:
  * retired: an entry with status = "dead";
  * containers: an entry that has other entries below its folder AND no source or test file of its own
    (android, server, shared, shared_web_libs): it only groups its children, which are rows of their own.
A row is DONE only when EVERYTHING its own Owns names is built, with tests (ruling 2026-11-10). A part named
in Owns that is missing, blocked or only decided makes it PARTIAL. Other modules, later phases, device-only
proofs and app wiring do not count against a row.
A row whose items are ALL NOT_STARTED must hold no code, test or script file of its own (child-module folders
excluded); a docs-only folder (AGENTS.md, README.md) stays allowed.
The containers list is DERIVED from the tree, so a container that gets code, or a no-code module that is
not a container (it has no children), cannot be moved in or out of the count by editing the file.

Exits 0 when the file matches; 1 on any drift. Prints
  DONE=<n> PARTIAL=<n> NOT_STARTED=<n> total=<n> containers=<n> retired=<n>
with every number counted from the rows of the file, never typed.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tomllib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATUSES = ("DONE", "PARTIAL", "NOT_STARTED")
CODE_SUFFIXES = {".kt", ".java", ".py", ".js", ".mjs", ".ts", ".css", ".html"}
# A NOT_STARTED row must hold no code or test file of its own; scripts (.sh) count there too.
# derive_containers() keeps the shorter CODE_SUFFIXES.
NOT_STARTED_SUFFIXES = CODE_SUFFIXES | {".sh"}
SKIPPED_DIRS = {".git", ".gradle", "build", "node_modules", "__pycache__"}


def read_registry(root: Path) -> dict:
    return tomllib.loads((root / "modules.toml").read_text())["module"]


def read_status(root: Path) -> dict:
    return json.loads((root / "docs" / "module-status.json").read_text())


def own_code_files(root: Path, name: str, registry: dict, suffixes: set[str] = CODE_SUFFIXES) -> list[str]:
    """Source and test files under the entry's folder that belong to no other entry's folder."""
    others = {e["path"].rstrip("/") for n, e in registry.items() if n != name}
    base = root / registry[name]["path"]
    found: list[str] = []
    for current, dirs, files in os.walk(base):
        rel = Path(current).relative_to(root).as_posix()
        dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS and f"{rel}/{d}" not in others]
        found += [f"{rel}/{f}" for f in files if Path(f).suffix in suffixes]
    return sorted(found)


def derive_containers(root: Path, registry: dict) -> set[str]:
    """Live entries with a child entry below their folder and no code of their own."""
    result = set()
    for name, entry in registry.items():
        if entry.get("status") == "dead":
            continue
        path = entry["path"].rstrip("/")
        has_child = any(n != name and e["path"].startswith(path + "/") for n, e in registry.items())
        if has_child and not own_code_files(root, name, registry):
            result.add(name)
    return result


def row_status(row: dict) -> str:
    states = {item["status"] for item in row["items"]}
    if states == {"DONE"}:
        return "DONE"
    if states == {"NOT_STARTED"}:
        return "NOT_STARTED"
    return "PARTIAL"


def counts(status: dict) -> dict:
    rows = status.get("modules", [])
    by = {s: 0 for s in STATUSES}
    for row in rows:
        by[row_status(row)] += 1
    return {
        "live": len(rows),
        "done": by["DONE"],
        "partial": by["PARTIAL"],
        "not_started": by["NOT_STARTED"],
        "containers": len(status.get("containers", [])),
        "retired": len(status.get("retired", [])),
    }


def check(status: dict, registry: dict, root: Path) -> list[str]:
    """Human-readable drift problems; empty means the file matches the registry and the tree."""
    problems: list[str] = []
    live = {n: e["path"] for n, e in registry.items() if e.get("status") != "dead"}
    dead = {n: e["path"] for n, e in registry.items() if e.get("status") == "dead"}
    rows = {r["name"]: r for r in status.get("modules", [])}
    containers = {c["name"]: c for c in status.get("containers", [])}
    retired = {r["name"] for r in status.get("retired", [])}
    expected = derive_containers(root, registry)

    for name in sorted(set(containers) - expected):
        problems.append(f"{name} is listed as a container but it is not one (it has no child entry, or it holds code of its own)")
    for name in sorted(expected - set(containers)):
        problems.append(f"{name} is a container (children, no code of its own) but is not in the containers list")
    for name in sorted(set(live) - set(rows) - set(containers)):
        problems.append(f"live registry entry missing from the status file: {name}")
    for name in sorted(set(rows) - set(live)):
        problems.append(f"stray status row that is not a live registry entry: {name}")
    for name in sorted(set(rows) & set(containers)):
        problems.append(f"{name} is both a row and a container")
    for name in sorted((set(rows) | set(containers)) & set(dead)):
        problems.append(f"retired entry {name} appears as a row or a container")
    for name in sorted(set(dead) - retired):
        problems.append(f"retired entry missing from the retired list: {name}")
    for name in sorted(retired - set(dead)):
        problems.append(f"{name} is in the retired list but is not status = dead in the registry")
    for name, row in rows.items():
        if name in live and row.get("path") != live[name]:
            problems.append(f"path mismatch for {name}: registry={live[name]} status={row.get('path')}")
        if not row.get("items"):
            problems.append(f"{name} has no items")
        if row_status(row) == "NOT_STARTED":
            owned = own_code_files(root, name, registry, NOT_STARTED_SUFFIXES)
            if owned:
                more = f" (and {len(owned) - 1} more)" if len(owned) > 1 else ""
                problems.append(f"{name} is NOT_STARTED but its folder holds code or test files: {owned[0]}{more}")
        for item in row.get("items", []):
            if item.get("status") not in STATUSES:
                problems.append(f"{name}: status {item.get('status')!r} is not one of {STATUSES}")
            if not str(item.get("evidence", "")).strip():
                problems.append(f"{name}: an item has empty evidence")
    summary, real = status.get("summary", {}), counts(status)
    for key, value in real.items():
        if summary.get(key) != value:
            problems.append(f"summary.{key}={summary.get(key)} but the rows give {value}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args(argv)
    status, registry = read_status(args.root), read_registry(args.root)
    problems = check(status, registry, args.root)
    c = counts(status)
    print(
        f"DONE={c['done']} PARTIAL={c['partial']} NOT_STARTED={c['not_started']} "
        f"total={c['live']} containers={c['containers']} retired={c['retired']}"
    )
    if problems:
        print("DRIFT DETECTED:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("module-status.json matches the registry and the tree.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
