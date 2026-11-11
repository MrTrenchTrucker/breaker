#!/usr/bin/env python3
"""backup-data.py - consistent online backup of Breaker's SQLite databases.

Usage:
    backup-data.py --out DIR [--sync-db PATH] [--whisper-db PATH]
    backup-data.py --verify BACKUP_DIR

The database paths default to the settings the servers read: BREAKER_SYNC_API_DB
(sync-api) and BREAKER_WHISPER_DB (whisper-server). A backup uses sqlite3's online
backup API, so a live database in WAL mode is copied as one consistent snapshot
(a plain file copy of a live database can be torn). Each copy is written to
OUT/<UTC stamp>/<label>.bak with mode 0600 (directories 0700), then checked: an
integrity check and the presence of the tables the server needs. A copy that fails
its check is not published. The stamp comes from BREAKER_BACKUP_NOW
(YYYYMMDDTHHMMSSZ) when set, else the clock. An existing stamp directory is never
overwritten. The script prints names and OK or FAILED only, never row data.
Exit codes: 0 ok, 1 a check failed, 2 usage error or refusal (nothing written).
"""

import argparse
import contextlib
import datetime
import os
import sqlite3
import sys
from pathlib import Path

# Tables each database must contain (the schema on main).
EXPECTED_TABLES = {
    "sync-api": {
        "accounts", "tokens", "transcriptions", "server_settings",
        "signin_attempts", "signin_handoff", "reset_codes", "pending_sealed",
    },
    "whisper-server": {"jobs"},
}


def restore_check(backup_path, expected_tables):
    """Open a copy read-only: integrity check and expected tables. Returns problems."""
    try:
        with contextlib.closing(sqlite3.connect("file:%s?mode=ro" % backup_path, uri=True)) as check:
            verdict = [row[0] for row in check.execute("PRAGMA integrity_check")]
            found = {row[0] for row in check.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    except sqlite3.Error as exc:
        return ["restore check failed to open: %s" % exc]
    problems = []
    if verdict != ["ok"]:
        problems.append("integrity check failed")
    missing = set(expected_tables) - found
    if missing:
        problems.append("missing tables: %s" % sorted(missing))
    return problems


def backup_database(db_path, label, stamp_dir):
    """Online-consistent copy of one database into stamp_dir. Returns problems."""
    source = Path(db_path)
    if not source.is_file():
        return ["%s: database file does not exist" % label]
    final = stamp_dir / (label + ".bak")
    partial = stamp_dir / (label + ".bak.partial")
    try:
        with contextlib.closing(sqlite3.connect(str(source))) as src:
            with contextlib.closing(sqlite3.connect(str(partial))) as dst:
                src.backup(dst)
    except sqlite3.Error as exc:
        _forget(partial)
        return ["%s: backup failed: %s" % (label, exc)]
    os.chmod(str(partial), 0o600)
    problems = restore_check(partial, EXPECTED_TABLES.get(label, set()))
    if problems:
        failed = stamp_dir / (label + ".bak.FAILED")
        partial.replace(failed)
        return ["%s: %s" % (label, problem) for problem in problems]
    partial.replace(final)
    return []


def _forget(path):
    try:
        path.unlink()
    except OSError:
        pass


def _stamp():
    forced = os.environ.get("BREAKER_BACKUP_NOW")
    if forced:
        return forced
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def _inside(child, parent):
    child, parent = child.resolve(), parent.resolve()
    return child == parent or parent in child.parents


def run_backup(args):
    sources = []
    for label, option, setting in (
        ("sync-api", args.sync_db, "BREAKER_SYNC_API_DB"),
        ("whisper-server", args.whisper_db, "BREAKER_WHISPER_DB"),
    ):
        path = option or os.environ.get(setting)
        if path:
            sources.append((label, path))
    if not sources:
        print("backup-data: no database to back up (set --sync-db or --whisper-db)", file=sys.stderr)
        return 2
    out_root = Path(args.out)
    for _label, path in sources:
        if _inside(out_root, Path(path).resolve().parent):
            print("backup-data: --out must not be inside a data directory", file=sys.stderr)
            return 2
    stamp_dir = out_root / _stamp()
    try:
        out_root.mkdir(mode=0o700, parents=True, exist_ok=True)
        stamp_dir.mkdir(mode=0o700)
    except FileExistsError:
        print("backup-data: this backup stamp already exists, nothing was written", file=sys.stderr)
        return 2
    except OSError as exc:
        print("backup-data: cannot create the output directory: %s" % exc.strerror, file=sys.stderr)
        return 2
    failed = False
    for label, path in sources:
        problems = backup_database(path, label, stamp_dir)
        if problems:
            failed = True
            for problem in problems:
                print("backup-data: FAILED %s" % problem, file=sys.stderr)
        else:
            print("%s OK" % label)
    return 1 if failed else 0


def run_verify(args):
    directory = Path(args.verify)
    copies = sorted(directory.glob("*.bak")) if directory.is_dir() else []
    if not copies:
        print("backup-data: no .bak file to verify", file=sys.stderr)
        return 2
    failed = False
    for copy in copies:
        label = copy.name[: -len(".bak")]
        problems = restore_check(copy, EXPECTED_TABLES.get(label, set()))
        if problems:
            failed = True
            print("%s FAILED %s" % (label, "; ".join(problems)))
        else:
            print("%s OK" % label)
    return 1 if failed else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Consistent online backup of Breaker's SQLite databases.")
    parser.add_argument("--out", help="output root directory")
    parser.add_argument("--sync-db", help="sync-api database (default: $BREAKER_SYNC_API_DB)")
    parser.add_argument("--whisper-db", help="whisper-server database (default: $BREAKER_WHISPER_DB)")
    parser.add_argument("--verify", help="check every .bak file in this backup directory")
    args = parser.parse_args(argv)
    if args.verify:
        return run_verify(args)
    if not args.out:
        print("backup-data: --out is required", file=sys.stderr)
        return 2
    return run_backup(args)


if __name__ == "__main__":
    sys.exit(main())
