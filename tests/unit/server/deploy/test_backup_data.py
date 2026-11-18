"""Unit tests for scripts/backup-data.py, the consistent online SQLite backup.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_backup_data.py'
"""
import datetime
import importlib.util
import os
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
# The mutant proofs point BACKUP_DATA_SRC at a scratch copy with one guard
# removed; the default is the real file.
SCRIPT = Path(os.environ.get(
    "BACKUP_DATA_SRC",
    str(REPO_ROOT / "server" / "modules" / "deploy" / "scripts" / "backup-data.py")))

SYNC_TABLES = (
    "accounts", "tokens", "transcriptions", "server_settings",
    "signin_attempts", "signin_handoff", "reset_codes", "pending_sealed",
)
STAMP = "20261111T120000Z"


def make_db(path, tables):
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    for table in tables:
        conn.execute("CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, note TEXT)" % table)
    conn.commit()
    conn.close()


def make_versioned_db(path, version):
    """A real SQLite database whose PRAGMA user_version is VERSION."""
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.execute("CREATE TABLE records (id INTEGER PRIMARY KEY)")
    conn.execute("PRAGMA user_version = %d" % version)
    conn.commit()
    conn.close()
    return path


class BackupDataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-backup-test-"))
        self.data = self.tmp / "data"
        self.out = self.tmp / "out"
        self.sync_db = self.data / "sync.db"
        self.whisper_db = self.data / "whisper.db"
        make_db(self.sync_db, SYNC_TABLES)
        make_db(self.whisper_db, ("jobs",))

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def run_script(self, *args, stamp=STAMP, extra_env=None):
        env = dict(os.environ)
        env.pop("BREAKER_SYNC_API_DB", None)
        env.pop("BREAKER_WHISPER_DB", None)
        env.pop("BREAKER_BACKUP_NOW", None)
        if stamp is not None:
            env["BREAKER_BACKUP_NOW"] = stamp
        env.update(extra_env or {})
        return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, env=env)

    def backup(self, *extra, stamp=STAMP):
        return self.run_script("--out", str(self.out), "--sync-db", str(self.sync_db),
                               "--whisper-db", str(self.whisper_db), *extra, stamp=stamp)

    def test_backs_up_both_databases_into_a_stamped_directory(self):
        result = self.backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.split(), ["sync-api", "OK", "whisper-server", "OK"])
        self.assertTrue((self.out / STAMP / "sync-api.bak").is_file())
        self.assertTrue((self.out / STAMP / "whisper-server.bak").is_file())

    def test_the_databases_default_to_the_server_settings(self):
        env = dict(os.environ, BREAKER_SYNC_API_DB=str(self.sync_db), BREAKER_BACKUP_NOW=STAMP)
        env.pop("BREAKER_WHISPER_DB", None)
        result = subprocess.run([sys.executable, str(SCRIPT), "--out", str(self.out)],
                                capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.out / STAMP / "sync-api.bak").is_file())
        self.assertFalse((self.out / STAMP / "whisper-server.bak").exists())

    def test_a_copy_holds_the_committed_rows_exactly(self):
        conn = sqlite3.connect(str(self.sync_db))
        conn.execute("INSERT INTO accounts (note) VALUES ('alpha')")
        conn.execute("INSERT INTO accounts (note) VALUES ('beta')")
        conn.commit()
        conn.close()
        self.assertEqual(self.backup().returncode, 0)
        copy = sqlite3.connect(str(self.out / STAMP / "sync-api.bak"))
        rows = [row[0] for row in copy.execute("SELECT note FROM accounts ORDER BY id")]
        copy.close()
        self.assertEqual(rows, ["alpha", "beta"])

    def test_a_live_wal_source_gives_a_consistent_copy(self):
        writer = sqlite3.connect(str(self.sync_db), isolation_level=None)
        writer.execute("PRAGMA journal_mode=WAL")
        writer.execute("INSERT INTO accounts (note) VALUES ('committed')")
        writer.execute("BEGIN")
        writer.execute("INSERT INTO accounts (note) VALUES ('pending')")
        try:
            result = self.backup()
            self.assertEqual(result.returncode, 0, result.stderr)
        finally:
            writer.close()
        copy = sqlite3.connect(str(self.out / STAMP / "sync-api.bak"))
        rows = [row[0] for row in copy.execute("SELECT note FROM accounts")]
        copy.close()
        self.assertEqual(rows, ["committed"])

    def test_files_are_private(self):
        self.assertEqual(self.backup().returncode, 0)
        directory = stat.S_IMODE((self.out / STAMP).stat().st_mode)
        copy = stat.S_IMODE((self.out / STAMP / "sync-api.bak").stat().st_mode)
        self.assertEqual(directory, 0o700)
        self.assertEqual(copy, 0o600)

    def test_an_existing_stamp_is_refused_and_left_alone(self):
        self.assertEqual(self.backup().returncode, 0)
        before = (self.out / STAMP / "sync-api.bak").read_bytes()
        result = self.backup()
        self.assertEqual(result.returncode, 2)
        self.assertEqual((self.out / STAMP / "sync-api.bak").read_bytes(), before)

    def test_an_output_directory_inside_the_data_directory_is_refused(self):
        result = self.run_script("--out", str(self.data / "backups"), "--sync-db", str(self.sync_db))
        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.data / "backups").exists())

    def test_usage_errors_exit_two(self):
        self.assertEqual(self.run_script("--sync-db", str(self.sync_db)).returncode, 2)
        self.assertEqual(self.run_script("--out", str(self.out)).returncode, 2)
        self.assertEqual(self.run_script("--out", str(self.out), "--bogus").returncode, 2)
        self.assertEqual(self.run_script("--verify", str(self.tmp / "nothing")).returncode, 2)

    def test_a_missing_database_fails_with_its_name_and_no_published_copy(self):
        result = self.run_script("--out", str(self.out), "--sync-db", str(self.data / "gone.db"))
        self.assertEqual(result.returncode, 1)
        self.assertIn("does not exist", result.stderr)
        self.assertFalse((self.out / STAMP / "sync-api.bak").exists())

    def test_a_database_without_the_expected_tables_is_not_published(self):
        bad = self.data / "bad.db"
        make_db(bad, ("accounts",))
        result = self.run_script("--out", str(self.out), "--sync-db", str(bad))
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing tables", result.stderr)
        self.assertFalse((self.out / STAMP / "sync-api.bak").exists())
        self.assertTrue((self.out / STAMP / "sync-api.bak.FAILED").exists())

    def test_verify_passes_a_good_backup_and_catches_a_corrupt_copy(self):
        self.assertEqual(self.backup().returncode, 0)
        good = self.run_script("--verify", str(self.out / STAMP))
        self.assertEqual(good.returncode, 0, good.stdout)
        self.assertEqual(good.stdout.split(), ["sync-api", "OK", "whisper-server", "OK"])
        copy = self.out / STAMP / "sync-api.bak"
        raw = bytearray(copy.read_bytes())
        for index in range(100, 400):
            raw[index] = raw[index] ^ 0xFF
        copy.write_bytes(bytes(raw))
        bad = self.run_script("--verify", str(self.out / STAMP))
        self.assertEqual(bad.returncode, 1)
        self.assertIn("sync-api FAILED", bad.stdout)

    def test_an_integrity_report_that_is_not_ok_is_a_problem(self):
        spec = importlib.util.spec_from_file_location("backup_data", str(SCRIPT))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        class FakeConnection:
            def execute(self, sql):
                if sql.startswith("PRAGMA integrity_check"):
                    return [("row 1 missing from index iv",)]
                return [("jobs",)]

            def close(self):
                pass

        module.sqlite3 = types.SimpleNamespace(connect=lambda *a, **k: FakeConnection(), Error=sqlite3.Error)
        problems = module.restore_check("unused", {"jobs"})
        self.assertEqual(problems, ["integrity check failed"])

    # -- gaps found by the manager's mutants -------------------------------

    def test_the_output_root_the_script_creates_is_private(self):
        self.assertEqual(self.backup().returncode, 0)
        self.assertEqual(stat.S_IMODE(self.out.stat().st_mode), 0o700)

    def test_an_output_directory_equal_to_the_data_directory_is_refused(self):
        before = sorted(os.listdir(str(self.data)))
        result = self.run_script("--out", str(self.data), "--sync-db", str(self.sync_db))
        self.assertEqual(result.returncode, 2)
        self.assertEqual(sorted(os.listdir(str(self.data))), before)

    def test_the_second_database_directory_is_checked_too(self):
        other = self.tmp / "other"
        other_db = other / "whisper.db"
        make_db(other_db, ("jobs",))
        result = self.run_script("--out", str(other / "backups"), "--sync-db", str(self.sync_db),
                                 "--whisper-db", str(other_db))
        self.assertEqual(result.returncode, 2)
        self.assertFalse((other / "backups").exists())

    def test_an_option_beats_the_environment(self):
        other = self.tmp / "elsewhere" / "other.db"
        make_db(other, SYNC_TABLES)
        conn = sqlite3.connect(str(other))
        conn.execute("INSERT INTO accounts (note) VALUES ('from-the-option')")
        conn.commit()
        conn.close()
        result = self.run_script("--out", str(self.out), "--sync-db", str(other),
                                 extra_env={"BREAKER_SYNC_API_DB": str(self.sync_db)})
        self.assertEqual(result.returncode, 0, result.stderr)
        copy = sqlite3.connect(str(self.out / STAMP / "sync-api.bak"))
        rows = [row[0] for row in copy.execute("SELECT note FROM accounts")]
        copy.close()
        self.assertEqual(rows, ["from-the-option"])

    def test_the_whisper_database_defaults_to_its_own_setting(self):
        result = self.run_script("--out", str(self.out), extra_env={"BREAKER_WHISPER_DB": str(self.whisper_db)})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.out / STAMP / "whisper-server.bak").is_file())
        self.assertFalse((self.out / STAMP / "sync-api.bak").exists())

    def test_every_expected_table_is_required(self):
        for missing in SYNC_TABLES:
            with self.subTest(label="sync-api", missing=missing):
                db = self.data / ("lacks-" + missing + ".db")
                make_db(db, tuple(t for t in SYNC_TABLES if t != missing) or ("other",))
                out = self.tmp / ("out-" + missing)
                result = self.run_script("--out", str(out), "--sync-db", str(db))
                self.assertEqual(result.returncode, 1)
                self.assertIn(missing, result.stderr)
                self.assertFalse((out / STAMP / "sync-api.bak").exists())
        db = self.data / "lacks-jobs.db"
        make_db(db, ("other",))
        result = self.run_script("--out", str(self.tmp / "out-jobs"), "--whisper-db", str(db))
        self.assertEqual(result.returncode, 1)
        self.assertIn("jobs", result.stderr)
        self.assertFalse((self.tmp / "out-jobs" / STAMP / "whisper-server.bak").exists())

    def test_the_clock_stamp_is_a_utc_second_stamp(self):
        before = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
        result = self.run_script("--out", str(self.out), "--sync-db", str(self.sync_db), stamp=None)
        after = datetime.datetime.now(datetime.timezone.utc)
        self.assertEqual(result.returncode, 0, result.stderr)
        names = os.listdir(str(self.out))
        self.assertEqual(len(names), 1)
        self.assertRegex(names[0], r"^\d{8}T\d{6}Z$")
        stamped = datetime.datetime.strptime(names[0], "%Y%m%dT%H%M%SZ").replace(tzinfo=datetime.timezone.utc)
        self.assertTrue(before <= stamped <= after, names[0])

    def test_the_restore_check_opens_the_copy_read_only(self):
        spec = importlib.util.spec_from_file_location("backup_data", str(SCRIPT))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        seen = []

        class FakeConnection:
            def execute(self, sql):
                return [("ok",)] if sql.startswith("PRAGMA integrity_check") else [("jobs",)]

            def close(self):
                pass

        def connect(*args, **kwargs):
            seen.append((args, kwargs))
            return FakeConnection()

        module.sqlite3 = types.SimpleNamespace(connect=connect, Error=sqlite3.Error)
        self.assertEqual(module.restore_check("/some/copy.bak", {"jobs"}), [])
        self.assertEqual(len(seen), 1)
        self.assertIn("mode=ro", seen[0][0][0])
        self.assertTrue(seen[0][1].get("uri"))

    def test_a_read_only_copy_verifies_and_is_left_untouched(self):
        self.assertEqual(self.backup().returncode, 0)
        copy = self.out / STAMP / "sync-api.bak"
        os.chmod(str(copy), 0o400)
        before = (copy.read_bytes(), copy.stat().st_mtime_ns)
        result = self.run_script("--verify", str(self.out / STAMP))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual((copy.read_bytes(), copy.stat().st_mtime_ns), before)

    def test_verify_looks_only_at_bak_files(self):
        folder = self.tmp / "mixed"
        folder.mkdir()
        make_db(folder / "good.bak", ("anything",))
        (folder / "x.bak.FAILED").write_bytes(b"not a database at all")
        (folder / "y.bak.partial").write_bytes(b"not a database either")
        result = self.run_script("--verify", str(folder))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(result.stdout.split(), ["good", "OK"])

    def test_a_failed_backup_leaves_no_partial_file(self):
        junk = self.data / "junk.db"
        junk.write_bytes(b"this is not a sqlite database" * 20)
        result = self.run_script("--out", str(self.out), "--sync-db", str(junk))
        self.assertEqual(result.returncode, 1)
        left = [name for name in os.listdir(str(self.out / STAMP)) if name.endswith(".partial")]
        self.assertEqual(left, [])
        self.assertFalse((self.out / STAMP / "sync-api.bak").exists())

    # -- D7: keep-last-N retention and own-sidecar cleanup ------------------

    def stamp_names(self, root=None):
        root = Path(root or self.out)
        return sorted(name for name in os.listdir(str(root)))

    def seed_stamps(self, names):
        """Pre-create stamp directories directly inside the backup root."""
        for name in names:
            (self.out / name).mkdir(parents=True, exist_ok=True)

    def test_keep_keeps_exactly_the_n_most_recent_stamps_inside_the_root(self):
        old = ["20260101T000000Z", "20260102T000000Z", "20260103T000000Z",
               "20260104T000000Z", "20260105T000000Z", "20260106T000000Z"]
        self.seed_stamps(old)
        result = self.backup("--keep", "3")
        self.assertEqual(result.returncode, 0, result.stderr)
        # --keep 3 leaves three stamp directories IN TOTAL: this run's stamp
        # plus the two newest seeds.
        self.assertEqual(self.stamp_names(), ["20260105T000000Z", "20260106T000000Z", STAMP])

    def test_the_default_keep_is_five(self):
        old = ["2026010%dT000000Z" % day for day in range(1, 9)]
        self.seed_stamps(old)
        result = self.backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.stamp_names()), 5)  # five in total, this run's included

    def test_a_decoy_directory_outside_the_root_that_sorts_newer_is_not_deleted(self):
        # Both decoys sort newer than every real stamp. The sibling one is never
        # reached by listing the root; the linked one IS listed inside the root
        # (under a valid stamp name) but resolves outside it, so only the
        # resolved-path containment check keeps it out of the deletion set.
        sibling = self.tmp / ("99999999T999999Z-sibling")
        sibling.mkdir()
        linked = self.data / ("99999999T999999Z-linked")
        linked.mkdir()
        self.out.mkdir(parents=True, exist_ok=True)
        os.symlink(str(linked), str(self.out / "99999999T999999Z"))
        old = ["20260101T000000Z", "20260102T000000Z"]
        self.seed_stamps(old)
        # shutil.rmtree refuses symlinks, so surviving on the filesystem does not
        # by itself prove the filter ran: the real contract is that a resolving-
        # outside entry is never handed to the deleter. Prove that in-process.
        # keep 0 hands EVERY stamp directory except this run's own (which does
        # not exist yet here), so the linked decoy is handed only if the
        # containment check is dropped.
        spec = importlib.util.spec_from_file_location("backup_data_prune", str(SCRIPT))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        handed = []
        module.shutil = types.SimpleNamespace(
            rmtree=lambda path, ignore_errors=False: handed.append(path))
        module._prune_stamps(self.out, 0, self.out / STAMP)
        self.assertEqual(handed, [str(self.out / "20260101T000000Z"),
                                  str(self.out / "20260102T000000Z")])
        result = self.backup("--keep", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(sibling.is_dir(), "sibling decoy outside the root was deleted")
        self.assertTrue(linked.is_dir(), "containment guard failed: directory resolving "
                                        "outside the root was deleted")
        self.assertTrue((self.out / "99999999T999999Z").is_symlink())
        # --keep 1 leaves exactly this run's stamp as the only real directory;
        # both seed stamps are gone and the surviving symlink is not a target.
        left = sorted(p.name for p in self.out.iterdir()
                      if p.is_dir() and not p.is_symlink())
        self.assertEqual(left, [STAMP])

    def test_the_just_created_stamp_is_never_deleted_even_with_keep_one(self):
        result = self.backup("--keep", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.stamp_names(), [STAMP])

    def test_the_just_created_stamp_survives_when_it_sorts_oldest(self):
        # BREAKER_BACKUP_NOW can name a stamp older than stamps already in the
        # root (a restored clock, a re-run of an old stamp). It is still this
        # run's own stamp: --keep 1 must leave it and delete the newer seeds.
        self.seed_stamps(["20261201T000000Z", "20261202T000000Z"])
        result = self.backup("--keep", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.stamp_names(), [STAMP])

    def test_a_non_directory_entry_in_the_root_is_never_deleted(self):
        self.out.mkdir(parents=True)
        (self.out / "README.txt").write_text("keep me", encoding="utf-8")
        (self.out / "20260101T000000Z.bak.partial-wal").write_bytes(b"stray sidecar")
        old = ["20260101T000000Z", "20260102T000000Z"]
        self.seed_stamps(old)
        result = self.backup("--keep", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.out / "README.txt").is_file())
        self.assertTrue((self.out / "20260101T000000Z.bak.partial-wal").is_file())
        self.assertEqual(sorted(p.name for p in self.out.iterdir()),
                         sorted(["README.txt", "20260101T000000Z.bak.partial-wal", STAMP]))

    def test_keep_zero_or_negative_is_refused_and_deletes_nothing(self):
        old = ["20260101T000000Z", "20260102T000000Z"]
        self.seed_stamps(old)
        before = self.stamp_names()
        for bad in ("0", "-1"):
            with self.subTest(keep=bad):
                result = self.backup("--keep", bad)
                self.assertEqual(result.returncode, 2)
                self.assertIn("--keep", result.stderr)
        self.assertEqual(self.stamp_names(), before)
        self.assertFalse(any(name == STAMP for name in self.stamp_names()))

    def test_own_sidecars_are_removed_after_a_successful_backup(self):
        result = self.backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        leftovers = [name for name in os.listdir(str(self.out / STAMP))
                     if ".bak.partial-wal" in name or ".bak.partial-shm" in name]
        self.assertEqual(leftovers, [])

    def test_the_stamp_directory_holds_only_the_bak_copies_after_a_real_backup(self):
        """Regression for the gate owner's defect 2 (FACTS_DEP4.md item 2).

        The integrity check (restore_check) reopens <label>.bak.partial and
        sqlite RECREATES the -wal/-shm sidecars beside it, which then survive
        the rename to .bak (24 stale sidecars over 6 stamps on the real stack).
        The sources are WAL-mode databases, as on the real stack. After a real
        backup of a real-schema database the stamp directory must hold ONLY the
        .bak copies: no -wal, no -shm, no .partial.
        """
        for db in (self.sync_db, self.whisper_db):
            conn = sqlite3.connect(str(db), isolation_level=None)
            conn.execute("PRAGMA journal_mode=WAL")
            conn.close()
        result = self.backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        listing = sorted(os.listdir(str(self.out / STAMP)))
        self.assertEqual(listing, ["sync-api.bak", "whisper-server.bak"],
                         "stamp directory holds: %r" % (listing,))

    def test_own_sidecars_are_removed_when_the_copy_fails(self):
        # The online backup writes <label>.bak.partial; when sqlite opens it beside
        # a WAL source, <label>.bak.partial-wal/-shm appear next to it. They are
        # this run's own sidecars and must be removed whether the copy passes or
        # fails. Seed them into the existing stamp directory (from the failed
        # first run) and re-run the same stamp.
        junk = self.data / "junk.db"
        junk.write_bytes(b"this is not a sqlite database" * 20)
        result = self.run_script("--out", str(self.out), "--sync-db", str(junk))
        self.assertEqual(result.returncode, 1)
        stamp_dir = self.out / STAMP
        for suffix in ("-wal", "-shm"):
            (stamp_dir / ("sync-api.bak.partial" + suffix)).write_bytes(b"sidecar")
        self.assertTrue((stamp_dir / "sync-api.bak.partial-wal").exists())
        result = self.backup()
        self.assertEqual(result.returncode, 2, result.stderr)  # stamp already exists
        leftovers = [name for name in os.listdir(str(stamp_dir))
                     if ".bak.partial-wal" in name or ".bak.partial-shm" in name]
        self.assertEqual(leftovers, [])

    def test_the_scripts_backup_argv_shape_runs_the_real_tool(self):
        """The argv the three deploy scripts build for the backup call, pinned against
        the REAL tool. install.sh builds `--out "$BACKUP_OUT" --db <host path>...` with
        `$@` carrying one `--db` per present volume (install.sh:460 `set -- $_idb_dbs`,
        then :481 `python3 backup-data.py --out "$BACKUP_OUT" "$@"`); update.sh:327 and
        rollback.sh:252 build the same `--out DIR --db PATH --db PATH` shape from
        deploy_db_paths. This runs the real backup-data.py with exactly that argv."""
        out = self.tmp / "real-tool-out"
        argv = ["--out", str(out), "--sync-db", str(self.sync_db),
                "--whisper-db", str(self.whisper_db)]
        env = dict(os.environ)
        for key in ("BREAKER_SYNC_API_DB", "BREAKER_WHISPER_DB"):
            env.pop(key, None)
        env["BREAKER_BACKUP_NOW"] = STAMP
        result = subprocess.run([sys.executable, str(SCRIPT), *argv],
                                capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        stamp = out / STAMP
        self.assertTrue((stamp / "sync-api.bak").is_file(), result.stdout + result.stderr)
        self.assertTrue((stamp / "whisper-server.bak").is_file(), result.stdout + result.stderr)
        verify = subprocess.run([sys.executable, str(SCRIPT), "--verify", str(stamp)],
                                capture_output=True, text=True, env=env)
        self.assertEqual(verify.returncode, 0, verify.stdout + verify.stderr)

    def test_the_deploy_scripts_do_not_pass_db_to_the_out_mode(self):
        """backup-data.py's --out mode reads --sync-db/--whisper-db only; `--db` is a
        --has-data/--record-schema flag, so `--out DIR --db A --db B` is refused with
        exit 2 and writes nothing. A deploy script still emitting `--db` to --out is
        therefore a red test here."""
        out = self.tmp / "old-shape-out"
        argv = ["--out", str(out), "--db", str(self.sync_db),
                "--db", str(self.whisper_db)]
        env = dict(os.environ)
        for key in ("BREAKER_SYNC_API_DB", "BREAKER_WHISPER_DB"):
            env.pop(key, None)
        env["BREAKER_BACKUP_NOW"] = STAMP
        result = subprocess.run([sys.executable, str(SCRIPT), *argv],
                                capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("no database to back up", result.stderr)
        self.assertFalse(out.exists())

    def test_an_existing_stamp_says_so(self):
        self.assertEqual(self.backup().returncode, 0)
        self.assertIn("already exists", self.backup().stderr)

    def test_the_output_never_holds_row_data(self):
        conn = sqlite3.connect(str(self.sync_db))
        conn.execute("INSERT INTO transcriptions (note) VALUES ('SECRET-MARKER-42')")
        conn.commit()
        conn.close()
        result = self.backup()
        verify = self.run_script("--verify", str(self.out / STAMP))
        for text in (result.stdout, result.stderr, verify.stdout, verify.stderr):
            self.assertNotIn("SECRET-MARKER-42", text)

    def test_the_script_never_copies_a_database_file_bytewise(self):
        text = SCRIPT.read_text()
        self.assertIn(".backup(", text)
        for banned in ("shutil.copy", "copyfile", "os.system", "subprocess"):
            self.assertNotIn(banned, text)


class RecordSchemaTest(unittest.TestCase):
    """The --record-schema mode, driven with the REAL sqlite3 module.

    Every database here is a real file opened by python3's sqlite3: a live
    WAL database with -wal/-shm present and a writer still open, a cleanly
    closed database with neither, and the inputs that must abort with exit 2
    and write nothing.
    """

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-schema-test-"))
        self.data = self.tmp / "data"
        self.data.mkdir()
        self.out = self.tmp / "schema.txt"
        self.db = make_versioned_db(self.data / "one.db", 7)

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def run_script(self, args):
        env = dict(os.environ)
        env.pop("BREAKER_BACKUP_NOW", None)
        return subprocess.run([sys.executable, str(SCRIPT), *args],
                              capture_output=True, text=True, env=env)

    def record(self, *dbs, extra=()):
        args = ["--record-schema", str(self.out)]
        for db in dbs:
            args += ["--db", str(db)]
        args += list(extra)
        return self.run_script(args)

    def test_a_live_wal_database_with_a_writer_open_is_recorded(self):
        writer = sqlite3.connect(str(self.db), isolation_level=None)
        writer.execute("PRAGMA journal_mode=WAL")
        writer.execute("INSERT INTO records (id) VALUES (1)")
        try:
            self.assertTrue(Path(str(self.db) + "-wal").exists())
            self.assertTrue(Path(str(self.db) + "-shm").exists())
            result = self.record(self.db)
            self.assertEqual(result.returncode, 0, result.stderr)
        finally:
            writer.close()
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")
        self.assertEqual(self.out.read_text(encoding="utf-8"), "%s\t7\n" % self.db)

    def test_a_cleanly_closed_database_is_recorded(self):
        self.assertFalse(Path(str(self.db) + "-wal").exists())
        self.assertFalse(Path(str(self.db) + "-shm").exists())
        result = self.record(self.db)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")
        self.assertEqual(self.out.read_text(encoding="utf-8"), "%s\t7\n" % self.db)

    def test_a_missing_database_exits_two_and_writes_nothing(self):
        missing = self.data / "gone.db"
        result = self.record(missing)
        self.assertEqual(result.returncode, 2)
        self.assertIn(str(missing), result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.out.exists())

    def test_a_file_that_is_not_a_database_exits_two_and_writes_nothing(self):
        junk = self.data / "junk.db"
        junk.write_bytes(b"this is not a sqlite database" * 20)
        result = self.record(junk)
        self.assertEqual(result.returncode, 2)
        self.assertIn(str(junk), result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.out.exists())

    def test_a_directory_at_the_database_path_exits_two_and_writes_nothing(self):
        # A directory where the database should be cannot be opened read-only,
        # so the same exit-2 guard fires before anything is written. This arm
        # needs no privilege drop: the path itself is the unreadable input.
        directory = self.data / "dir.db"
        directory.mkdir()
        result = self.record(directory)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn(str(directory), result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.out.exists())

    def test_record_schema_refuses_out_and_verify(self):
        for extra in (("--out", str(self.tmp / "o")), ("--verify", str(self.tmp / "v"))):
            with self.subTest(option=extra[0]):
                result = self.record(self.db, extra=extra)
                self.assertEqual(result.returncode, 2)
                self.assertIn("cannot be combined", result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertFalse(self.out.exists())
                self.assertFalse(Path(extra[1]).exists())

    def test_record_schema_requires_a_database(self):
        result = self.run_script(["--record-schema", str(self.out)])
        self.assertEqual(result.returncode, 2)
        self.assertIn("--db", result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.out.exists())

    def test_an_uncreatable_output_directory_exits_two(self):
        blocker = self.tmp / "blocker"
        blocker.write_text("not a directory", encoding="utf-8")
        result = self.run_script(["--record-schema", str(blocker / "schema.txt"),
                                  "--db", str(self.db)])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertFalse((blocker / "schema.txt").exists())

    def test_an_unwritable_record_target_exits_two(self):
        target = self.tmp / "target.dir"
        target.mkdir()
        result = self.run_script(["--record-schema", str(target), "--db", str(self.db)])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertEqual(list(target.iterdir()), [])

    def test_the_record_file_is_0600_and_holds_path_tab_version_per_database(self):
        other = make_versioned_db(self.data / "two.db", 42)
        result = self.record(self.db, other)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(stat.S_IMODE(self.out.stat().st_mode), 0o600)
        lines = self.out.read_text(encoding="utf-8").splitlines()
        self.assertEqual(lines, ["%s\t7" % self.db, "%s\t42" % other])
        for line, db, version in ((lines[0], self.db, "7"), (lines[1], other, "42")):
            self.assertEqual(line.split("\t"), [str(db), version])

    def test_a_database_that_yields_no_user_version_row_exits_two(self):
        # PRAGMA user_version returns one row on every real database, so this
        # guard is reached with a connection that yields no row. It must abort
        # with exit 2 before the output file is created.
        spec = importlib.util.spec_from_file_location("backup_data_schema", str(SCRIPT))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        class FakeResult:
            def __init__(self, rows):
                self._rows = rows

            def fetchone(self):
                return self._rows[0] if self._rows else None

        class FakeConnection:
            def execute(self, sql):
                return FakeResult([])

            def close(self):
                pass

        module.sqlite3 = types.SimpleNamespace(connect=lambda *a, **k: FakeConnection(),
                                                Error=sqlite3.Error)
        args = types.SimpleNamespace(record_schema=str(self.out), db=[str(self.db)])
        self.assertEqual(module.run_record_schema(args), 2)
        self.assertFalse(self.out.exists())


class HasDataTest(unittest.TestCase):
    """The --has-data mode: 0 when at least one --db is a regular file, else 3.

    A directory or a dangling symlink at the path is NOT data. --out,
    --verify and --record-schema cannot be combined with it.
    """

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-hasdata-test-"))
        self.data = self.tmp / "data"
        self.data.mkdir()
        self.sync_db = self.data / "sync.db"
        self.whisper_db = self.data / "whisper.db"
        make_db(self.sync_db, SYNC_TABLES)
        make_db(self.whisper_db, ("jobs",))

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def run_script(self, args):
        env = dict(os.environ)
        env.pop("BREAKER_SYNC_API_DB", None)
        env.pop("BREAKER_WHISPER_DB", None)
        env.pop("BREAKER_BACKUP_NOW", None)
        return subprocess.run([sys.executable, str(SCRIPT), *args],
                              capture_output=True, text=True, env=env)

    def has_data(self, *dbs, extra=()):
        args = ["--has-data"]
        for db in dbs:
            args += ["--db", str(db)]
        args += list(extra)
        return self.run_script(args)

    def test_one_present_database_exits_zero(self):
        result = self.has_data(self.sync_db)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")

    def test_no_present_database_exits_three(self):
        result = self.has_data(self.data / "gone.db")
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertEqual(result.stdout, "")

    def test_one_of_two_databases_present_exits_zero(self):
        result = self.has_data(self.sync_db, self.data / "gone.db")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_both_databases_missing_exits_three(self):
        result = self.has_data(self.data / "gone.db", self.data / "also-gone.db")
        self.assertEqual(result.returncode, 3, result.stderr)

    def test_a_directory_named_like_the_database_is_not_data(self):
        directory = self.data / "dir.db"
        directory.mkdir()
        result = self.has_data(directory)
        self.assertEqual(result.returncode, 3, result.stderr)

    def test_a_dangling_symlink_is_not_data(self):
        link = self.data / "dangling.db"
        os.symlink(str(self.data / "no-such-target.db"), str(link))
        result = self.has_data(link)
        self.assertEqual(result.returncode, 3, result.stderr)

    def test_a_symlink_to_a_regular_file_is_data(self):
        link = self.data / "link.db"
        os.symlink(str(self.sync_db), str(link))
        result = self.has_data(link)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_has_data_refuses_out_verify_and_record_schema(self):
        conflicts = (("--out", str(self.tmp / "out")),
                     ("--verify", str(self.tmp / "verify")),
                     ("--record-schema", str(self.tmp / "schema")))
        for option, value in conflicts:
            with self.subTest(option=option):
                result = self.has_data(self.sync_db, extra=(option, value))
                self.assertEqual(result.returncode, 2)
                self.assertIn(option, result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertFalse(Path(value).exists())

    def test_has_data_requires_a_database(self):
        result = self.run_script(["--has-data"])
        self.assertEqual(result.returncode, 2)
        self.assertIn("--db", result.stderr)


if __name__ == "__main__":
    unittest.main()
