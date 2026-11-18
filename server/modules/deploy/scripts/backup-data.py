#!/usr/bin/env python3
"""backup-data.py - consistent online backup of Breaker's SQLite databases.

Usage:
    backup-data.py --out DIR [--sync-db PATH] [--whisper-db PATH] [--keep N]
    backup-data.py --verify BACKUP_DIR
    backup-data.py --record-schema FILE --db PATH [--db PATH...]
    backup-data.py --has-data --db PATH [--db PATH...]

The database paths default to the settings the servers read: BREAKER_SYNC_API_DB
(sync-api) and BREAKER_WHISPER_DB (whisper-server). A backup uses sqlite3's online
backup API, so a live database in WAL mode is copied as one consistent snapshot
(a plain file copy of a live database can be torn). Each copy is written to
OUT/<UTC stamp>/<label>.bak with mode 0600 (directories 0700), then checked: an
integrity check and the presence of the tables the server needs. A copy that fails
its check is not published. The stamp comes from BREAKER_BACKUP_NOW
(YYYYMMDDTHHMMSSZ) when set, else the clock. An existing stamp directory is never
overwritten. The script prints names and OK or FAILED only, never row data.
--keep N (default 5) leaves the N most recent stamp directories in total directly
inside the backup root, this run's own stamp included (--keep 1 leaves only the
stamp just made); the run's own stamp is never deleted, non-directory entries are
never deleted, and nothing outside the root is ever touched. --keep 0 or negative
is refused. The WAL/SHM sidecars sqlite leaves beside this run's own .bak.partial
copy are removed AFTER the integrity check, whether the copy succeeds or fails:
the check reopens the partial and can recreate them, so removing them earlier
would leave the recreated ones behind to survive the rename.
Exit codes: 0 ok, 1 a check failed, 2 usage error or refusal (nothing written,
including a refused --keep).

--has-data is a separate probe: it exits 0 when AT LEAST ONE --db exists as a
regular file, 3 when none does (a directory or a dangling symlink at a path is
NOT data). It prints nothing and refuses --out, --verify and --record-schema in
the same call (exit 2, naming the conflict).

--record-schema FILE is a separate mode: it opens every --db database READ-ONLY
(sqlite3 mode=ro) and reads PRAGMA user_version, then writes one line
"PATH<TAB>VERSION" per database to FILE (mode 0600, written through a temporary
directory beside FILE). It refuses --out (or --verify) in the same call. If any
database cannot be opened or read (missing, not a database, locked, permission)
it exits 2, writes NOTHING and never guesses a version.
"""

import argparse
import contextlib
import datetime
import os
import re
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

# Tables each database must contain (the schema on main).
EXPECTED_TABLES = {
    "sync-api": {
        "accounts", "tokens", "transcriptions", "server_settings",
        "signin_attempts", "signin_handoff", "reset_codes", "pending_sealed",
    },
    "whisper-server": {"jobs"},
}

# A UTC stamp directory name: YYYYMMDDTHHMMSSZ. Lexicographic order = chronology.
STAMP_RE = re.compile(r"^\d{8}T\d{6}Z$")


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
        _remove_own_sidecars(label, stamp_dir)
        return ["%s: backup failed: %s" % (label, exc)]
    os.chmod(str(partial), 0o600)
    problems = restore_check(partial, EXPECTED_TABLES.get(label, set()))
    if problems:
        failed = stamp_dir / (label + ".bak.FAILED")
        partial.replace(failed)
        _remove_own_sidecars(label, stamp_dir)
        return ["%s: %s" % (label, problem) for problem in problems]
    _remove_own_sidecars(label, stamp_dir)
    partial.replace(final)
    return []


def _forget(path):
    try:
        path.unlink()
    except OSError:
        pass


def _forget_sidecars(paths):
    """Remove each of PATHS; a missing one is not an error."""
    for path in paths:
        _forget(Path(path))


def _stamp():
    forced = os.environ.get("BREAKER_BACKUP_NOW")
    if forced:
        return forced
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


STAMP_RE = re.compile(r"^\d{8}T\d{6}Z$")


def _stamp_sort_key(name):
    """UTC stamps (YYYYMMDDTHHMMSSZ) sort lexicographically = chronologically."""
    return name


def _prune_stamps(out_root, keep, stamp_dir):
    """Leave the `keep` most recent stamp directories inside out_root, this run's included.

    Boundaries: only entries directly inside the RESOLVED backup root, only
    directories, only names that are UTC stamps, never the stamp directory this
    run just created, and nothing outside the root is ever touched.
    """
    root = out_root.resolve()
    current = stamp_dir.resolve()
    candidates = []
    for entry in os.listdir(str(root)):
        path = root / entry
        if not _inside(path, root) or path.resolve() == root:
            continue
        if not path.is_dir():
            continue
        if not STAMP_RE.match(entry):
            continue
        candidates.append((entry, path))
    candidates.sort(key=lambda item: _stamp_sort_key(item[0]))
    excess = len(candidates) - keep
    for _entry, path in candidates:
        if excess <= 0:
            break
        if path.resolve() == current:
            # Never delete this run's own stamp, even when the clock makes it
            # sort old: skip it and take the next oldest instead.
            continue
        shutil.rmtree(str(path), ignore_errors=True)
        excess -= 1


def _own_sidecar_paths(label, stamp_dir):
    """The WAL/SHM sidecars sqlite leaves beside this run's own <label>.bak.partial."""
    return [stamp_dir / (label + ".bak.partial" + suffix) for suffix in ("-wal", "-shm")]


def _remove_own_sidecars(label, stamp_dir):
    """Remove the WAL/SHM sidecars of this run's own partial copy."""
    _forget_sidecars(_own_sidecar_paths(label, stamp_dir))


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
    if args.keep <= 0:
        print("backup-data: --keep must be a positive number of stamp directories to keep",
              file=sys.stderr)
        return 2
    for _label, path in sources:
        if _inside(out_root, Path(path).resolve().parent):
            print("backup-data: --out must not be inside a data directory", file=sys.stderr)
            return 2
    stamp_dir = out_root / _stamp()
    try:
        out_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError as exc:
        print("backup-data: cannot create the output directory: %s" % exc.strerror, file=sys.stderr)
        return 2
    try:
        stamp_dir.mkdir(mode=0o700)
    except FileExistsError:
        # This run owns this stamp name but wrote nothing into it yet; still take
        # its own sidecars (a previous attempt may have left them beside a partial).
        for label, _path in sources:
            _remove_own_sidecars(label, stamp_dir)
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
    _prune_stamps(out_root, args.keep, stamp_dir)
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


def run_record_schema(args):
    """Read PRAGMA user_version of every --db read-only and record it.

    Every database is opened with sqlite3 mode=ro and read before anything is
    written. A database that cannot be opened or read (missing, not a
    database, locked, permission) exits 2 with NOTHING written: no version is
    ever guessed. The output is written through a temporary directory beside
    FILE, so FILE appears atomically with mode 0600.
    """
    out_path = Path(args.record_schema)
    records = []
    for db in args.db:
        try:
            with contextlib.closing(sqlite3.connect("file:%s?mode=ro" % db, uri=True)) as conn:
                row = conn.execute("PRAGMA user_version").fetchone()
        except sqlite3.Error as exc:
            print("backup-data: cannot read %s: %s" % (db, exc), file=sys.stderr)
            return 2
        if row is None:
            print("backup-data: cannot read %s: no user_version row" % db, file=sys.stderr)
            return 2
        records.append((db, row[0]))
    out_dir = os.path.dirname(os.path.abspath(args.record_schema))
    try:
        tmp_dir = tempfile.mkdtemp(prefix=".breaker-schema-", dir=out_dir)
    except OSError as exc:
        print("backup-data: cannot create the schema output directory: %s" % exc.strerror,
              file=sys.stderr)
        return 2
    tmp_path = os.path.join(tmp_dir, "schema")
    try:
        with open(tmp_path, "w", encoding="utf-8") as handle:
            for db, version in records:
                handle.write("%s\t%s\n" % (db, version))
        os.chmod(tmp_path, 0o600)
        os.replace(tmp_path, str(out_path))
    except OSError as exc:
        print("backup-data: cannot write %s: %s" % (args.record_schema, exc.strerror),
              file=sys.stderr)
        return 2
    finally:
        _forget(Path(tmp_path))
        try:
            os.rmdir(tmp_dir)
        except OSError:
            pass
    return 0


def run_has_data(args):
    """Exit 0 when at least one --db exists as a regular file, else 3.

    Path.is_file() follows symlinks, so a dangling symlink is not data and a
    directory is not data; a symlink to a real file is.
    """
    for db in args.db:
        if Path(db).is_file():
            return 0
    return 3


def main(argv=None):
    parser = argparse.ArgumentParser(description="Consistent online backup of Breaker's SQLite databases.")
    parser.add_argument("--out", help="output root directory")
    parser.add_argument("--sync-db", help="sync-api database (default: $BREAKER_SYNC_API_DB)")
    parser.add_argument("--whisper-db", help="whisper-server database (default: $BREAKER_WHISPER_DB)")
    parser.add_argument("--keep", type=int, default=5, metavar="N",
                        help="leave the N most recent stamp directories in total, this "
                             "run's own included (default 5; deletes nothing outside the "
                             "root and never the stamp just created). N <= 0 is refused.")
    parser.add_argument("--verify", help="check every .bak file in this backup directory")
    parser.add_argument("--record-schema", metavar="FILE",
                        help="record PRAGMA user_version of each --db into FILE (read-only)")
    parser.add_argument("--has-data", action="store_true",
                        help="exit 0 when at least one --db is a regular file, 3 when none is")
    parser.add_argument("--db", action="append", default=[], metavar="PATH",
                        help="database to record (repeatable, with --record-schema or --has-data)")
    args = parser.parse_args(argv)
    if args.has_data:
        _conflict = [name for name, value in (("--out", args.out),
                                              ("--verify", args.verify),
                                              ("--record-schema", args.record_schema)) if value]
        if _conflict:
            print("backup-data: --has-data cannot be combined with %s" % ", ".join(_conflict),
                  file=sys.stderr)
            return 2
        if not args.db:
            print("backup-data: --has-data requires at least one --db", file=sys.stderr)
            return 2
        return run_has_data(args)
    if args.record_schema:
        if args.out or args.verify:
            print("backup-data: --record-schema cannot be combined with --out or --verify",
                  file=sys.stderr)
            return 2
        if not args.db:
            print("backup-data: --record-schema requires at least one --db", file=sys.stderr)
            return 2
        return run_record_schema(args)
    if args.verify:
        return run_verify(args)
    if not args.out:
        print("backup-data: --out is required", file=sys.stderr)
        return 2
    return run_backup(args)


if __name__ == "__main__":
    sys.exit(main())
