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
SCRIPT = REPO_ROOT / "server" / "modules" / "deploy" / "scripts" / "backup-data.py"

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


if __name__ == "__main__":
    unittest.main()
