"""Unit tests for scripts/update.sh.

Drives the REAL update.sh through a temp PATH of stubs (git, docker, curl, ss,
chown, stat, sqlite3, python3) and the REAL sibling scripts (git-pull.sh,
release-tags.sh, health-check.sh, vpn-bind.sh, deploy-common.sh). The python3
stub records every argv and, only when the invoked script is backup-data.py,
EXECUTES the REAL sibling backup-data.py with the same argv, so the backup
call shape is exercised against the real tool; rollback.sh is a stub that
records its argv. No host, docker, network or real key is touched. The stub log
records every argv; the tests assert the step order, the exit codes and every
guard.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_update.py'
"""
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ZERO_SHA = "0" * 40  # a fake tag target; built, not typed, so no hash-like literal sits in the tree

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = REPO_ROOT / "server" / "modules" / "deploy" / "scripts"
SCRIPT = SCRIPTS / "update.sh"
COMPOSE = REPO_ROOT / "server" / "modules" / "deploy" / "docker-compose.yml"
ENV_EXAMPLE = REPO_ROOT / "server" / "modules" / "deploy" / ".env.example"

# The file the tests run. The mutant proofs point UPDATE_SRC at a scratch copy
# with one branch removed; the default is the real file.
UPDATE_SRC = Path(os.environ.get("UPDATE_SRC", str(SCRIPT)))

REMOTE = "https://example.invalid/breaker.git"
PLACEHOLDER = "placeholder-value-42"
# 43 characters of the URL-safe alphabet, the ServiceKeyFile.kt rule.
KEY = "update-key-0123456789ABCDEFGHIJKLMNOPQRS"
FORWARDER_URL = "http://10.20.30.12:9000"
BIND = "10.8.0.5"
SYNC_VERSION = 7
WHISPER_VERSION = 3
MIGRATED_VERSION = 99

# backup-data.py's EXPECTED_TABLES (the schema on main): the restore check
# marks a copy FAILED when a database lacks them, so the fixture databases
# must carry exactly these tables for a real backup to exit 0.
SYNC_TABLES = (
    "accounts", "tokens", "transcriptions", "server_settings",
    "signin_attempts", "signin_handoff", "reset_codes", "pending_sealed",
)
WHISPER_TABLES = ("jobs",)

# The stamp the real backup writes when BREAKER_BACKUP_NOW is pinned, so the
# .bak files and the --verify directory are known in advance.
BACKUP_STAMP = "20261117T010203Z"

STUB_GIT = r"""#!/bin/sh
printf 'git %s\n' "$*" >> "$STUB_LOG"
is_ls=0
is_clone=0
for a in "$@"; do
    [ "$a" = "ls-remote" ] && is_ls=1
    [ "$a" = "clone" ] && is_clone=1
done
if [ "$is_ls" = 1 ]; then
    cat "${STUB_TAGS_FILE:-$STUB_BIN/default-tags}"
    exit 0
fi
if [ "$is_clone" = 1 ]; then
    for a in "$@"; do dest="$a"; done
    mkdir -p "$dest/server/modules/deploy"
    cp "$STUB_BIN/default-compose.yml" "$dest/server/modules/deploy/docker-compose.yml"
    cp "$STUB_BIN/default-env-example" "$dest/server/modules/deploy/.env.example"
    cp "$STUB_BIN/gradlew" "$dest/gradlew"
    chmod +x "$dest/gradlew"
    exit 0
fi
exit 0
"""

# The docker stub answers `volume inspect` from the two mount env vars (an
# unset/empty one is a volume that does not exist yet), and on `up -d` it can
# migrate or delete the sync database to drive the option-C arms.
STUB_DOCKER = r"""#!/bin/sh
printf 'docker %s\n' "$*" >> "$STUB_LOG"
printf 'docker-env BREAKER_IMAGE_TAG=%s\n' "${BREAKER_IMAGE_TAG:+set}" >> "$STUB_LOG"
case "$*" in
    "compose version")
        printf '%s\n' "${STUB_COMPOSE_VERSION-Docker Compose version v2.20.0}"
        exit "${STUB_COMPOSE_RC:-0}"
        ;;
    "buildx version")
        printf '%s\n' "${STUB_BUILDX_VERSION:-Docker buildx version 0.12.0}"
        exit "${STUB_BUILDX_RC:-0}"
        ;;
    "compose -p breaker ps --format {{.Service}} {{.Image}}")
        # deploy_running_image_tag reads the whole running set here: one
        # "SERVICE IMAGE" line per running container, empty = nothing running.
        # STUB_PS_FAIL_FILE makes the read fail (the file's existence is the
        # signal), which is how a daemon that cannot be reached is modelled.
        if [ -f "$STUB_PS_FAIL_FILE" ]; then
            printf '%s\n' "${STUB_PS_ERR:-stub docker: ps failed}" >&2
            exit 1
        fi
        cat "${STUB_PS_FILE:-$STUB_BIN/default-ps}" 2>/dev/null
        exit 0
        ;;
    *"volume ls --format {{.Name}}"*)
        if [ -n "${STUB_VOLUME_LIST:-}" ]; then
            # shellcheck disable=SC2086
            printf '%s\n' $STUB_VOLUME_LIST
        else
            [ -n "${STUB_SYNC_MOUNT:-}" ] && printf '%s\n' breaker_breaker_sync_api_data
            [ -n "${STUB_WHISPER_MOUNT:-}" ] && printf '%s\n' breaker_breaker_whisper_data
        fi
        exit "${STUB_VOLUME_LS_RC:-0}"
        ;;
esac
case "$*" in
    *"volume inspect "*sync_api_data*)
        [ -n "${STUB_SYNC_MOUNT:-}" ] || exit 1
        printf '%s\n' "$STUB_SYNC_MOUNT"
        exit 0
        ;;
    *"volume inspect "*whisper_data*)
        [ -n "${STUB_WHISPER_MOUNT:-}" ] || exit 1
        printf '%s\n' "$STUB_WHISPER_MOUNT"
        exit 0
        ;;
    *"volume inspect "*)
        exit 1
        ;;
    *" up -d"*)
        if [ "${STUB_UP_MIGRATE:-0}" = 1 ]; then
            "$STUB_REAL_PYTHON3" -c 'import sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
conn.execute("PRAGMA user_version = " + sys.argv[2])
conn.commit()
conn.close()' "$STUB_SYNC_MOUNT/sync-api.db" "$STUB_MIGRATED_VERSION" 2>/dev/null || true
        fi
        if [ "${STUB_UP_DELETE:-0}" = 1 ]; then
            rm -f "$STUB_SYNC_MOUNT/sync-api.db" "$STUB_WHISPER_MOUNT/whisper-server.db"
        fi
        exit "${STUB_UP_RC:-0}"
        ;;
    *" build"*)
        exit "${STUB_BUILD_RC:-0}"
        ;;
    *" stop"*)
        exit "${STUB_STOP_RC:-0}"
        ;;
esac
exit 0
"""

STUB_CURL = r"""#!/bin/sh
printf 'curl %s\n' "$*" >> "$STUB_LOG"
if [ "${STUB_CURL_RC:-0}" != 0 ]; then
    exit "$STUB_CURL_RC"
fi
printf '{"status":"ok"} %s\n' "${STUB_CURL_CODE:-200}"
exit 0
"""

STUB_SS = r"""#!/bin/sh
printf 'ss %s\n' "$*" >> "$STUB_LOG"
if [ "${STUB_SS_RC:-0}" != 0 ]; then
    exit "$STUB_SS_RC"
fi
cat "${STUB_LISTENERS_FILE:-$STUB_BIN/default-listeners}"
exit 0
"""

STUB_CHOWN = r"""#!/bin/sh
printf 'chown %s\n' "$*" >> "$STUB_LOG"
exit 0
"""

# The key file's owner is a test fact, not a host fact: uid 10001 by default
# (STUB_FAKE_KEY_UID overrides it) for the key path, the real stat for every
# other call, so the owner check runs without chowning anything.
STUB_STAT = r"""#!/bin/sh
printf 'stat %s\n' "$*" >> "$STUB_LOG"
case "$*" in
    *"%u"*"$STUB_KEY_FILE"*)
        printf '%s\n' "${STUB_FAKE_KEY_UID:-10001}"
        exit 0
        ;;
esac
exec "$STUB_REAL_STAT" "$@"
"""

STUB_GRADLEW = r"""#!/bin/sh
printf 'gradlew %s\n' "$*" >> "$STUB_LOG"
printf 'gradlew-env BREAKER_IMAGE_TAG=%s\n' "${BREAKER_IMAGE_TAG:+set}" >> "$STUB_LOG"
# D5: record the umask the build runs under and the mode of a probe file it
# creates, so a test can pin both under a restrictive CALLER umask.
if [ -n "${STUB_UMASK_FILE:-}" ]; then
    printf '%s\n' "$(umask)" >> "$STUB_UMASK_FILE"
fi
if [ -n "${STUB_PROBE_FILE:-}" ]; then
    mkdir -p "$(dirname "$STUB_PROBE_FILE")"
    : > "$STUB_PROBE_FILE"
    if [ -n "${STUB_MODE_FILE:-}" ]; then
        printf '%s\n' "$(stat -c '%a' "$STUB_PROBE_FILE")" >> "$STUB_MODE_FILE"
    fi
fi
exit "${STUB_GRADLEW_RC:-0}"
"""

# A no-op sqlite3 CLI. No preflight tool loop lists it and update.sh never
# invokes it (every schema read goes through python3's sqlite3 module); the
# stub only keeps the run host-independent (this container has no sqlite3
# binary), and test_a_missing_sqlite3_cli_is_accepted removes it.
STUB_SQLITE3 = r"""#!/bin/sh
printf 'sqlite3 %s\n' "$*" >> "$STUB_LOG"
exit 0
"""

# The rollback sibling stub: it records its argv and the steps the real
# --recovery path performs (build, image build, up, health). It never backs
# up and never records a schema.
STUB_ROLLBACK = r"""#!/bin/sh
printf 'rollback %s\n' "$*" >> "$STUB_LOG"
printf 'rollback: host build\n' >> "$STUB_LOG"
printf 'rollback: image build\n' >> "$STUB_LOG"
printf 'rollback: swap\n' >> "$STUB_LOG"
printf 'rollback: health\n' >> "$STUB_LOG"
if [ "${STUB_ROLLBACK_RC:-0}" != 0 ]; then
    # A FAILED recovery leaves whatever docker says is up. The test states
    # that answer (STUB_PS_AFTER_FAIL) or makes the read fail
    # (STUB_PS_FAIL_FILE), so the report must come from docker, never a guess.
    if [ -n "${STUB_PS_FILE:-}" ] && [ -n "${STUB_PS_AFTER_FAIL:-}" ]; then
        printf '%s\n' "$STUB_PS_AFTER_FAIL" > "$STUB_PS_FILE"
    fi
    if [ -n "${STUB_PS_FAIL_FILE:-}" ]; then
        : > "$STUB_PS_FAIL_FILE"
    fi
fi
exit "${STUB_ROLLBACK_RC:-0}"
"""

# The python3 stub. It RECORDS every argv, and ONLY when the invoked script is
# backup-data.py it EXECUTES the real sibling scripts/backup-data.py with the
# same argv, so the deploy call shape is exercised against the real tool (a
# real `--out` write, a real `--has-data` probe, a real `--record-schema`
# read). Every other python3 call - update.sh's inline `update_read_version`
# heredoc, the docker stub's migration helper - is left to the real
# interpreter, so it is never intercepted.
STUB_PYTHON3 = r'''import os
import sys

log = os.environ.get("STUB_LOG")
argv = sys.argv[1:]
if log:
    with open(log, "a") as fh:
        fh.write("python3 " + " ".join(argv) + "\n")

# Only `python3 .../backup-data.py ...` is redirected to the real tool. A
# `python3 -c ...` / `python3 -` call (preflight's module import, update.sh's
# inline heredoc) has no script file and must reach the real interpreter.
if argv and not argv[0].startswith("-") and os.path.basename(argv[0]) == "backup-data.py":
    real = os.environ["STUB_REAL_BACKUP_DATA"]
    os.execv(sys.executable, [sys.executable, real] + argv[1:])

os.execv(sys.executable, [sys.executable] + argv)
'''

# The legacy no-op backup-data.py stub. Kept for the test that drives the OLD
# `--out ... --db ...` shape directly; the real-tool assertions never use it.
STUB_BACKUP_DATA = r'''import os
import sqlite3
import sys

args = sys.argv[1:]
with open(os.environ["STUB_LOG"], "a") as fh:
    fh.write("backup-data.py " + " ".join(args) + "\n")


def db_paths():
    out = []
    i = 0
    while i < len(args):
        if args[i] == "--db":
            out.append(args[i + 1])
            i += 2
        else:
            i += 1
    return out


if "--has-data" in args:
    sys.exit(0 if any(os.path.isfile(p) for p in db_paths()) else 3)

if "--record-schema" in args:
    out = args[args.index("--record-schema") + 1]
    records = []
    for db in db_paths():
        try:
            conn = sqlite3.connect("file:%s?mode=ro" % db, uri=True)
            try:
                row = conn.execute("PRAGMA user_version").fetchone()
            finally:
                conn.close()
        except sqlite3.Error:
            sys.exit(2)
        if row is None:
            sys.exit(2)
        records.append((db, row[0]))
    with open(out, "w") as fh:
        for db, ver in records:
            fh.write("%s\t%s\n" % (db, ver))
    sys.exit(0)

if "--out" in args:
    out = args[args.index("--out") + 1]
    os.makedirs(out, exist_ok=True)
    sys.exit(int(os.environ.get("STUB_BACKUP_RC", "0")))

sys.exit(0)
'''

CORE_TOOLS = ("sh", "dirname", "sed", "awk", "sort", "grep", "tail", "cat",
              "basename", "cut", "tr", "date", "pwd", "chmod", "cp", "mkdir",
              "rm", "mv", "wc", "sleep")


def env_example_names():
    """The NAME= names of the real .env.example, in file order."""
    names = []
    for raw in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        names.append(line.split("=", 1)[0].strip())
    return names


BASE_VALUES = {
    "BREAKER_IMAGE_TAG": "",
    "BREAKER_BIND_ADDR": BIND,
    "BREAKER_NETWORK_SUBNET": "10.20.30.0/24",
    "BREAKER_SYNC_API_ADDRESS": "10.20.30.11",
    "BREAKER_WHISPER_ADDRESS": "10.20.30.12",
    "BREAKER_WEB_FE_ADDRESS": "10.20.30.13",
    "BREAKER_TRAINING_ADDRESS": "10.20.30.14",
    "BREAKER_SYNC_API_PORT": "8081",
    "BREAKER_SYNC_API_INTERNAL_PORT": "8082",
    "BREAKER_WHISPER_PORT": "8083",
    "BREAKER_WEB_FE_PORT": "8084",
    "BREAKER_WHISPER_FORWARDER_URL": FORWARDER_URL,
    "BREAKER_WHISPER_SYNC_API_SERVICE_KEY": KEY,
}


class UpdateTest(unittest.TestCase):
    """Drives the real update.sh through the stubs."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-update-test-"))
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.run_dir = self.tmp / "run"
        self.run_dir.mkdir()
        shutil.copyfile(UPDATE_SRC, self.run_dir / "update.sh")
        os.chmod(str(self.run_dir / "update.sh"), 0o755)
        for name in ("deploy-common.sh", "git-pull.sh", "release-tags.sh",
                     "health-check.sh", "vpn-bind.sh"):
            os.symlink(str(SCRIPTS / name), str(self.run_dir / name))
        self._write(self.run_dir / "backup-data.py", STUB_BACKUP_DATA)
        self._write(self.run_dir / "rollback.sh", STUB_ROLLBACK)
        for name, text in (("git", STUB_GIT), ("docker", STUB_DOCKER),
                           ("curl", STUB_CURL), ("ss", STUB_SS),
                           ("chown", STUB_CHOWN), ("gradlew", STUB_GRADLEW)):
            self._write(self.bin / name, text)
        for tool in CORE_TOOLS:
            source = shutil.which(tool)
            if source:
                os.symlink(source, str(self.bin / tool))
        # python3 is a stub that records argv and execs the REAL backup-data.py
        # for the backup calls (see STUB_PYTHON3); everything else goes to the
        # real interpreter. The shebang must be the ABSOLUTE real interpreter:
        # `env python3` would resolve through the stub's own PATH and recurse.
        self._write(self.bin / "python3",
                    "#!%s\n%s" % (shutil.which("python3") or "/usr/bin/python3",
                                  STUB_PYTHON3))
        self._write(self.bin / "stat", STUB_STAT)
        self._write(self.bin / "sqlite3", STUB_SQLITE3)

        self._write(self.bin / "default-tags",
                    ZERO_SHA + "\trefs/tags/v1.0.0\n" +
                    ZERO_SHA + "\trefs/tags/v1.2.0\n")
        # `docker compose ps` answer: the three running services at the tag
        # that is RUNNING before the swap (the previous release, v1.0.0).
        self._write(self.bin / "default-ps",
                    "sync-api breaker/sync-api:v1.0.0\n"
                    "whisper-server breaker/whisper-server:v1.0.0\n"
                    "web-fe breaker/web-fe:v1.0.0\n")
        self.ps_file = self.bin / "ps"
        shutil.copyfile(str(self.bin / "default-ps"), str(self.ps_file))
        shutil.copyfile(COMPOSE, str(self.bin / "default-compose.yml"))
        shutil.copyfile(ENV_EXAMPLE, str(self.bin / "default-env-example"))
        self._write(self.bin / "gradlew", STUB_GRADLEW)
        self._write(self.bin / "default-listeners",
                    "LISTEN 0 128 %s:8081 *:*\n"
                    "LISTEN 0 128 %s:8083 *:*\n"
                    "LISTEN 0 128 %s:8084 *:*\n" % (BIND, BIND, BIND))

        self.env_file = self.tmp / "stack.env"
        self.key_file = self.tmp / "service-key"
        self.write_key()
        self.write_env()
        self.staging = self.tmp / "staging"
        self.backup_out = self.tmp / "backups"
        self.log = self.tmp / "stub.log"

        # The two named-volume mountpoints with REAL sqlite3 databases that
        # carry the tables backup-data.py's restore check expects
        # (EXPECTED_TABLES): sync-api's eight tables and whisper-server's jobs.
        self.sync_mount = self.tmp / "vol-sync"
        self.whisper_mount = self.tmp / "vol-whisper"
        self.sync_mount.mkdir()
        self.whisper_mount.mkdir()
        self.sync_db = self.sync_mount / "sync-api.db"
        self.whisper_db = self.whisper_mount / "whisper-server.db"
        self.write_database(self.sync_db, SYNC_VERSION, tables=SYNC_TABLES)
        self.write_database(self.whisper_db, WHISPER_VERSION, tables=("jobs",))

        self.env = dict(os.environ)
        for name in list(self.env):
            if name.startswith("BREAKER_"):
                del self.env[name]
        self.env.update({
            "PATH": str(self.bin),
            "STUB_LOG": str(self.log),
            "STUB_BIN": str(self.bin),
            "STUB_TAGS_FILE": str(self.bin / "default-tags"),
            "STUB_LISTENERS_FILE": str(self.bin / "default-listeners"),
            "STUB_REAL_STAT": shutil.which("stat") or "/usr/bin/stat",
            "STUB_KEY_FILE": str(self.key_file),
            "STUB_REAL_PYTHON3": shutil.which("python3") or "/usr/bin/python3",
            "STUB_REAL_BACKUP_DATA": str(SCRIPTS / "backup-data.py"),
            "STUB_PS_FILE": str(self.ps_file),
            "STUB_SYNC_MOUNT": str(self.sync_mount),
            "STUB_WHISPER_MOUNT": str(self.whisper_mount),
            "STUB_MIGRATED_VERSION": str(MIGRATED_VERSION),
            "BREAKER_BACKUP_NOW": BACKUP_STAMP,
            "HEALTH_STEP": "0",
            "HEALTH_WAIT": "2",
        })

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def _write(self, path, text):
        path.write_text(text, encoding="utf-8")
        os.chmod(str(path), 0o755)

    def write_key(self, content=KEY, mode=0o600):
        self.key_file.write_text(content + "\n", encoding="utf-8")
        os.chmod(str(self.key_file), mode)

    def write_env(self, overrides=None, mode=0o600):
        values = dict(BASE_VALUES)
        values["BREAKER_SYNC_API_SERVICE_KEY_HOST"] = str(self.key_file)
        values.update(overrides or {})
        lines = ["%s=%s" % (name, values.get(name, PLACEHOLDER))
                 for name in env_example_names()]
        self.env_file.write_text("".join(line + "\n" for line in lines),
                                 encoding="utf-8")
        os.chmod(str(self.env_file), mode)

    def write_database(self, path, version, tables=()):
        conn = sqlite3.connect(str(path))
        try:
            for table in tables:
                conn.execute(
                    "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, note TEXT)"
                    % table)
            conn.execute("PRAGMA user_version = %d" % version)
            conn.commit()
        finally:
            conn.close()

    def base_args(self, staging=None, use_compose=True):
        args = ["--remote", REMOTE,
                "--env-file", str(self.env_file),
                "--staging", str(staging or self.staging),
                "--backup-out", str(self.backup_out)]
        if use_compose:
            args.extend(["--compose", str(COMPOSE)])
        return args

    def invoke(self, args, env_extra=None):
        self.log.write_text("", encoding="utf-8")
        env = dict(self.env)
        env.update(env_extra or {})
        return subprocess.run(["sh", str(self.run_dir / "update.sh")] + list(args),
                              capture_output=True, text=True, env=env, timeout=60)

    def run_ok(self, extra=(), env_extra=None, **kwargs):
        return self.invoke(self.base_args(**kwargs) + list(extra), env_extra)

    def log_text(self):
        return self.log.read_text(encoding="utf-8") if self.log.exists() else ""

    def log_lines(self):
        return [line for line in self.log_text().splitlines() if line]

    def python3_calls(self, needle):
        """The recorded `python3 ...` argv lines that contain NEEDLE.

        The python3 stub records every argv it is invoked with, so this reads
        the deploy call shape without parsing the shell script's source.
        """
        return [line for line in self.log_text().splitlines()
                if line.startswith("python3 ") and needle in line]

    def backup_stamp_dirs(self):
        """The stamp directories the real backup wrote under --backup-out."""
        if not self.backup_out.is_dir():
            return []
        return sorted(path for path in self.backup_out.iterdir() if path.is_dir())

    def run_real_backup_tool(self, *args):
        """Run the REAL backup-data.py directly (no stub) with a fixed stamp."""
        env = dict(self.env)
        env["BREAKER_BACKUP_NOW"] = BACKUP_STAMP
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "backup-data.py")] + list(args),
            capture_output=True, text=True, env=env, timeout=60)

    # -- usage ---------------------------------------------------------------

    def test_usage_unknown_option_exits_2(self):
        r = self.run_ok(extra=["--bogus"])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--bogus", r.stderr)
        self.assertEqual(r.stdout, "")

    def test_usage_missing_value_exits_2(self):
        for args, name in ((["--remote"], "--remote"),
                           (["--remote", REMOTE, "--tag"], "--tag"),
                           (["--remote", REMOTE, "--env-file"], "--env-file"),
                           (["--remote", REMOTE, "--staging"], "--staging"),
                           (["--remote", REMOTE, "--backup-out"], "--backup-out"),
                           (["--remote", REMOTE, "--keep"], "--keep"),
                           (["--remote", REMOTE, "--timeout"], "--timeout")):
            with self.subTest(args=args):
                r = self.invoke(args)
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertEqual(r.stdout, "")
                self.assertIn("%s requires a value" % name, r.stderr)

    def test_usage_missing_remote_exits_2(self):
        r = self.invoke([])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--remote is required", r.stderr)

    def test_usage_bad_timeout_exits_2(self):
        for value in ("abc", "0", "61"):
            with self.subTest(value=value):
                r = self.run_ok(extra=["--timeout", value])
                self.assertEqual(r.returncode, 2, r.stderr)

    # -- the happy path and the order ---------------------------------------

    def test_success_runs_every_step_in_order(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        order = [
            ("tag", "ls-remote"),
            ("pull", "clone"),
            ("host build", "gradlew"),
            ("image build", " build --no-cache\n"),
            ("backup", "backup-data.py --out"),
            ("schema", "--record-schema"),
            ("swap", "up -d"),
            ("health", "curl"),
            ("vpn-bind", "ss -ltnH"),
        ]
        positions = []
        for label, needle in order:
            index = log.find(needle)
            self.assertGreater(index, -1, "%s missing from log:\n%s" % (label, log))
            positions.append(index)
        self.assertEqual(positions, sorted(positions), log)

    def test_the_default_tag_is_resolved_and_exported(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("docker-env BREAKER_IMAGE_TAG=set", self.log_text())
        self.assertIn("refs/tags/v1.2.0", self.log_text())

    def test_a_named_tag_reaches_the_pull(self):
        r = self.run_ok(extra=["--tag", "v1.0.0"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("checkout -q --detach refs/tags/v1.0.0", self.log_text())

    def test_the_swap_is_detached(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("up -d", self.log_text())

    def test_the_three_host_health_urls_use_the_bind_address(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        for url in ("http://%s:8081/health" % BIND,
                    "http://%s:8083/health" % BIND,
                    "http://%s:8084/health" % BIND):
            self.assertIn(url, self.log_text())

    def test_an_ipv6_private_bind_builds_bracketed_health_urls(self):
        listeners = self.tmp / "listeners-ipv6"
        listeners.write_text(
            "LISTEN 0 128 [fd00::5]:8081 *:*\n"
            "LISTEN 0 128 [fd00::5]:8083 *:*\n"
            "LISTEN 0 128 [fd00::5]:8084 *:*\n", encoding="utf-8")
        self.write_env(overrides={"BREAKER_BIND_ADDR": "fd00::5"})
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout + self.log_text()
        for url in ("http://[fd00::5]:8081/health",
                    "http://[fd00::5]:8083/health",
                    "http://[fd00::5]:8084/health"):
            self.assertIn(url, out)
        self.assertNotIn("http://fd00::5:", out)

    # -- preflight part 2 ---------------------------------------------------

    def test_env_not_0600_exits_1_and_never_swaps(self):
        os.chmod(str(self.env_file), 0o644)
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("0600", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_required_name_empty_exits_1_and_never_swaps(self):
        self.write_env(overrides={"BREAKER_WHISPER_PORT": ""})
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_WHISPER_PORT", r.stderr)
        self.assertNotIn("8083", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_public_bind_is_refused_and_never_swaps(self):
        self.write_env(overrides={"BREAKER_BIND_ADDR": "8.8.8.8"})
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_BIND_ADDR", r.stderr)
        self.assertIn("public", r.stderr)
        self.assertNotIn("8.8.8.8", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    # -- preflight tools: no sqlite3 CLI; the module import is preflight's ---

    def test_a_missing_sqlite3_cli_is_accepted(self):
        (self.bin / "sqlite3").unlink()
        # Fail part 1 at the compose-version step, AFTER the tool loop: the
        # message proves the loop accepted a PATH with no sqlite3 CLI at all.
        r = self.run_ok(env_extra={"STUB_COMPOSE_RC": "1"})
        self.assertIn("compose v2 is required", r.stderr)
        self.assertNotIn("required tool missing: sqlite3", r.stderr)

    def test_a_python3_without_the_sqlite3_module_is_refused(self):
        # The module import is deploy_preflight's check, not a probe of
        # update.sh's own: the run passes part 1 and the pull, then preflight
        # part 2 refuses before any build, backup or swap.
        fake = self.bin / "python3"
        fake.unlink()
        self._write(fake, "#!/bin/sh\nexit 1\n")
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("python3", r.stderr)
        self.assertIn("sqlite3", r.stderr)
        self.assertIn("update: preflight part 2", r.stdout)
        log = self.log_text()
        self.assertNotIn("gradlew", log)
        self.assertNotIn("up -d", log)

    # -- the service key ----------------------------------------------------

    def test_a_missing_key_exits_1_and_is_never_created(self):
        self.key_file.unlink()
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_SYNC_API_SERVICE_KEY_HOST", r.stderr)
        self.assertIn("missing", r.stderr)
        self.assertFalse(self.key_file.exists())
        self.assertNotIn("up -d", self.log_text())

    def test_a_key_with_the_wrong_mode_exits_1_and_is_unchanged(self):
        os.chmod(str(self.key_file), 0o644)
        before = self.key_file.read_bytes()
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("0600", r.stderr)
        self.assertEqual(self.key_file.read_bytes(), before)
        self.assertNotIn("up -d", self.log_text())

    def test_a_key_with_the_wrong_owner_exits_1_and_is_unchanged(self):
        before = self.key_file.read_bytes()
        r = self.run_ok(env_extra={"STUB_FAKE_KEY_UID": "0"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("owner is not 10001", r.stderr)
        self.assertIn(str(self.key_file), r.stderr)
        self.assertNotIn("owner is 0", r.stderr)
        self.assertEqual(self.key_file.read_bytes(), before)
        self.assertNotIn("up -d", self.log_text())

    def test_a_key_that_does_not_match_the_env_value_exits_1_and_is_unchanged(self):
        self.write_key(content="a-different-key-value")
        before = self.key_file.read_bytes()
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_WHISPER_SYNC_API_SERVICE_KEY", r.stderr)
        self.assertEqual(self.key_file.read_bytes(), before)
        self.assertNotIn("up -d", self.log_text())

    # -- the backup and the schema record -----------------------------------

    def test_the_backup_runs_before_the_swap(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("backup-data.py --out", log)
        self.assertLess(log.find("backup-data.py --out"), log.find("up -d"), log)

    def test_the_backup_call_carries_sync_db_and_whisper_db(self):
        # D3: `--out` reads --sync-db/--whisper-db; `--db` belongs only to
        # --has-data and --record-schema. The python3 stub records the argv and
        # execs the REAL backup-data.py, so a wrong shape is a real refusal.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--out"), [
            "python3 %s --out %s --sync-db %s --whisper-db %s --keep 5"
            % (self.run_dir / "backup-data.py", self.backup_out,
               self.sync_db, self.whisper_db)], self.log_text())

    def test_the_schema_record_runs_before_the_swap(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("--record-schema", log)
        self.assertLess(log.find("--record-schema"), log.find("up -d"), log)

    def test_no_backup_skips_the_backup_call_and_still_records(self):
        r = self.run_ok(extra=["--no-backup"])
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertNotIn("backup-data.py --out", log)
        self.assertIn("--record-schema", log)

    def test_the_default_takes_the_backup_call(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("backup-data.py --out", self.log_text())

    def test_the_has_data_call_names_both_resolved_volumes(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--has-data"), [
            "python3 %s --has-data --db %s --db %s"
            % (self.run_dir / "backup-data.py", self.sync_db, self.whisper_db)],
            self.log_text())

    def test_no_data_skips_the_backup_and_the_schema_record(self):
        self.sync_db.unlink()
        self.whisper_db.unlink()
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("nothing to back up", r.stdout)
        log = self.log_text()
        self.assertNotIn("backup-data.py --out", log)
        self.assertNotIn("--record-schema", log)

    def test_both_volumes_absent_never_asks_has_data_or_backups(self):
        # D1: with BOTH volumes ABSENT there is no --db at all, so --has-data
        # (which exits 2 with no --db) must NOT be called and backup-data.py
        # --out must NOT be called. Absence is "no data", not a hard failure.
        env = {"STUB_SYNC_MOUNT": "", "STUB_WHISPER_MOUNT": ""}
        r = self.run_ok(env_extra=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("nothing to back up", r.stdout)
        log = self.log_text()
        self.assertNotIn("--has-data", log)
        self.assertNotIn("backup-data.py", log)
        self.assertNotIn("--record-schema", log)

    def test_one_volume_absent_passes_only_the_present_one(self):
        # D1: an absent volume yields an EMPTY line; the caller must drop it
        # and pass exactly one database - never an empty argument.
        env = {"STUB_SYNC_MOUNT": str(self.sync_mount),
               "STUB_WHISPER_MOUNT": ""}
        r = self.run_ok(env_extra=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertEqual(self.python3_calls("--has-data"), [
            "python3 %s --has-data --db %s"
            % (self.run_dir / "backup-data.py", self.sync_db)], log)
        self.assertEqual(self.python3_calls("--out"), [
            "python3 %s --out %s --sync-db %s --keep 5"
            % (self.run_dir / "backup-data.py", self.backup_out, self.sync_db)],
            log)
        # Exactly one --db per present path (has-data, record-schema).
        self.assertEqual(log.count("--db %s" % self.sync_db), 2, log)
        self.assertNotIn("--db  ", log)
        self.assertNotIn("--db --", log)
        self.assertNotIn("--whisper-db", log)

    def test_an_absent_first_volume_does_not_shift_the_second_into_its_slot(self):
        # The empty line must be DROPPED, not passed through: the present
        # whisper database is the only database, and no empty argument appears.
        env = {"STUB_SYNC_MOUNT": "", "STUB_WHISPER_MOUNT": str(self.whisper_mount)}
        r = self.run_ok(env_extra=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertEqual(self.python3_calls("--has-data"), [
            "python3 %s --has-data --db %s"
            % (self.run_dir / "backup-data.py", self.whisper_db)], log)
        self.assertEqual(self.python3_calls("--out"), [
            "python3 %s --out %s --whisper-db %s --keep 5"
            % (self.run_dir / "backup-data.py", self.backup_out, self.whisper_db)],
            log)
        self.assertNotIn("--db  ", log)
        self.assertNotIn("--db --", log)
        self.assertNotIn("--sync-db", log)

    # -- D3: the real-tool backup path --------------------------------------

    def test_the_backup_writes_both_bak_files_and_verify_passes(self):
        # The real backup-data.py runs against the two fixture databases: the
        # update exits 0, both .bak files exist under the pinned stamp and
        # --verify re-checks them. A wrong call shape cannot produce this.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        stamp = self.backup_out / BACKUP_STAMP
        self.assertTrue((stamp / "sync-api.bak").is_file(), r.stderr)
        self.assertTrue((stamp / "whisper-server.bak").is_file(), r.stderr)
        self.assertFalse(list(stamp.glob("*.bak.FAILED")),
                         "a copy was marked FAILED: %s" % r.stderr)
        verify = self.run_real_backup_tool("--verify", str(stamp))
        self.assertEqual(verify.returncode, 0, verify.stderr)
        self.assertEqual(verify.stdout.split(),
                         ["sync-api", "OK", "whisper-server", "OK"])

    def test_the_old_out_db_shape_is_refused_by_the_real_tool(self):
        # D3: `--out DIR --db A --db B` is the broken shape; the real tool
        # exits 2 naming --sync-db/--whisper-db and writes nothing.
        out = self.tmp / "old-shape-out"
        r = self.run_real_backup_tool("--out", str(out),
                                      "--db", str(self.sync_db),
                                      "--db", str(self.whisper_db))
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("--sync-db", r.stderr)
        self.assertIn("--whisper-db", r.stderr)
        self.assertFalse(out.exists())

    def test_a_host_path_with_a_space_stays_one_argument(self):
        # The DB list must be positional parameters, never an unquoted string:
        # a path containing a space survives as ONE --sync-db argument.
        spaced = self.tmp / "vol sync"
        spaced.mkdir()
        spaced_db = spaced / "sync-api.db"
        self.write_database(spaced_db, SYNC_VERSION, tables=SYNC_TABLES)
        env = {"STUB_SYNC_MOUNT": str(spaced),
               "STUB_WHISPER_MOUNT": str(self.whisper_mount)}
        r = self.run_ok(env_extra=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--out"), [
            "python3 %s --out %s --sync-db %s --whisper-db %s --keep 5"
            % (self.run_dir / "backup-data.py", self.backup_out,
               spaced_db, self.whisper_db)], self.log_text())
        stamp = self.backup_out / BACKUP_STAMP
        self.assertTrue((stamp / "sync-api.bak").is_file(), r.stderr)
        verify = self.run_real_backup_tool("--verify", str(stamp))
        self.assertEqual(verify.returncode, 0, verify.stderr)

    def test_both_volumes_absent_still_swaps(self):
        # Absence of the volumes is NOT a hard failure: the update proceeds
        # to the swap with no backup and no schema record.
        env = {"STUB_SYNC_MOUNT": "", "STUB_WHISPER_MOUNT": ""}
        r = self.run_ok(env_extra=env)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("up -d", self.log_text())

    # -- D15: the build is rebuilt from scratch ----------------------------

    def test_the_image_build_carries_no_cache(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(
            "docker compose --env-file %s -p breaker -f %s build --no-cache"
            % (self.env_file, COMPOSE),
            self.log_text())

    def test_the_host_build_names_all_three_modules(self):
        # D11: web-fe's Dockerfile COPYs build/web/js/vendor, which only
        # :server:modules:web-fe:assemble produces, so the host build command
        # must name all three modules - not just the two installDist trees.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        builds = [line for line in self.log_lines() if line.startswith("gradlew ")]
        self.assertEqual(builds, [
            "gradlew :server:modules:sync-api:installDist"
            " :server:modules:whisper-server:installDist"
            " :server:modules:web-fe:assemble"], self.log_text())

    def test_the_checkout_and_the_build_are_022_under_a_077_caller(self):
        # D5: a root shell with umask 077 is the real use. The checkout and the
        # host build must run under a FORCED 022, never the caller's 077: the
        # images COPY the checkout trees and the gradle outputs, and uid 10001
        # cannot read a root-owned 0600 file. The stub gradlew records the
        # umask and the probe mode.
        probe = self.staging / "build" / "umask-probe.txt"
        umask_file = self.tmp / "umask.txt"
        mode_file = self.tmp / "mode.txt"
        previous = os.umask(0o077)
        try:
            r = self.run_ok(env_extra={"STUB_UMASK_FILE": str(umask_file),
                                       "STUB_PROBE_FILE": str(probe),
                                       "STUB_MODE_FILE": str(mode_file)})
        finally:
            os.umask(previous)
        self.assertEqual(r.returncode, 0, r.stderr)
        compose = (self.staging / "server" / "modules" / "deploy"
                   / "docker-compose.yml")
        self.assertTrue(compose.is_file(), r.stdout + r.stderr)
        self.assertEqual(os.stat(str(compose)).st_mode & 0o777, 0o644,
                         "the checkout copied mode %o"
                         % (os.stat(str(compose)).st_mode & 0o777))
        self.assertTrue(probe.is_file(), r.stdout + r.stderr)
        self.assertEqual(os.stat(str(probe)).st_mode & 0o777, 0o644,
                         "the build created mode %o"
                         % (os.stat(str(probe)).st_mode & 0o777))
        self.assertEqual(umask_file.read_text(encoding="utf-8").split(),
                         ["0022"], "the build ran under the caller's umask")
        self.assertEqual(mode_file.read_text(encoding="utf-8").split(),
                         ["644"], "the stub recorded the probe mode")

    def test_the_recovery_call_pins_exact_argv_without_no_cache(self):
        # The recovery path invokes rollback.sh, which owns its own image
        # build and always builds with --no-cache itself; that build line is
        # pinned in tests/unit/server/deploy/test_rollback.py. update.sh must
        # NOT forward --no-cache: rollback.sh's option table has no such case,
        # so a forwarded --no-cache exits 2 and the recovery would always fail.
        # Pin the recovery call's EXACT argv.
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        expected = ("rollback --remote %s --env-file %s --timeout 5 "
                    "--recovery --tag v1.0.0" % (REMOTE, self.env_file))
        self.assertEqual(
            [line for line in log.splitlines()
             if line.startswith("rollback --remote")],
            [expected], log)
        # update.sh's OWN image build still carries --no-cache (D15); the
        # recovery call is the only build line without it.
        self.assertEqual(log.count("--no-cache"), 1, log)

    def test_an_unresolvable_volume_fails_naming_the_volume(self):
        # A volume that cannot be resolved is a HARD FAILURE, not "no data":
        # deploy_db_paths names the volume on stderr and update.sh exits 1
        # before any backup, schema record or swap.
        # Both volumes LISTED but neither inspectable: the hard failure.
        env = {"STUB_SYNC_MOUNT": "", "STUB_WHISPER_MOUNT": "",
               "STUB_VOLUME_LIST": "breaker_breaker_sync_api_data "
                                   "breaker_breaker_whisper_data"}
        r = self.run_ok(env_extra=env)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("breaker_breaker_sync_api_data", r.stderr)
        log = self.log_text()
        self.assertNotIn("backup-data.py --out", log)
        self.assertNotIn("--record-schema", log)
        self.assertNotIn("up -d", log)

    def test_a_schema_record_that_cannot_read_aborts_before_the_swap(self):
        self.sync_db.write_bytes(b"this is not a database\n")
        # The REAL backup-data.py refuses a source that is not a database (its
        # online backup raises "file is not a database", exit 1), so with the
        # backup enabled the backup arm fails first. Skip the backup to reach
        # the schema-record arm this test pins.
        r = self.run_ok(extra=["--no-backup"])
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema record failed", r.stderr)
        self.assertNotIn("up -d", self.log_text())
        self.assertNotIn("schema verdict", r.stdout)

    # -- option C: the health-failure arms ----------------------------------

    def test_health_failure_unchanged_redeploys_the_previous_tag_once(self):
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertEqual(
            log.count("rollback --remote %s --env-file %s --timeout 5 --recovery"
                      % (REMOTE, self.env_file)),
            1, log)
        self.assertEqual(log.count("backup-data.py --out"), 1, log)
        self.assertEqual(log.count("--record-schema"), 1, log)
        for needle in ("rollback: host build", "rollback: image build",
                       "rollback: swap", "rollback: health"):
            self.assertIn(needle, log)
        self.assertIn(str(self.backup_out), r.stdout)
        self.assertIn("schema verdict unchanged", r.stdout)

    def test_health_failure_changed_leaves_the_stack_and_reports_both_versions(self):
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_MIGRATE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertNotIn("rollback --remote", log)
        self.assertEqual(log.count("up -d"), 1, log)
        self.assertIn("schema verdict changed", r.stdout)
        self.assertIn(str(self.backup_out), r.stdout)
        self.assertIn(str(SYNC_VERSION), r.stdout)
        self.assertIn(str(MIGRATED_VERSION), r.stdout)

    def test_a_post_failure_read_failure_is_unknown_and_never_redeploys(self):
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_DELETE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertNotIn("rollback --remote", log)
        self.assertIn("schema verdict unknown", r.stdout)
        self.assertIn(str(self.backup_out), r.stdout)

    def test_no_data_health_failure_leaves_the_stack_and_never_redeploys(self):
        self.sync_db.unlink()
        self.whisper_db.unlink()
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("nothing to back up", r.stdout)
        log = self.log_text()
        self.assertNotIn("rollback --remote", log)
        self.assertNotIn("--record-schema", log)
        self.assertIn("schema verdict unknown", r.stdout)

    def test_a_failed_recovery_is_reported_without_a_second_attempt(self):
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertEqual(
            log.count("rollback --remote %s --env-file %s --timeout 5 --recovery"
                      % (REMOTE, self.env_file)),
            1, log)
        self.assertIn("recovery failed", r.stdout)
        self.assertIn(str(self.backup_out), r.stdout)

    def test_a_failed_recovery_is_attempted_exactly_once(self):
        # Option C's recovery redeploys the previous tag ONCE. A second attempt
        # is not part of the design and would take the running stack down twice.
        # Named for MUTANTS.json row U21-recovery-second-attempt: the mutant adds
        # the second attempt back in the recovery's else arm.
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        attempts = [line for line in log.splitlines() if line.startswith("rollback --remote")]
        self.assertEqual(len(attempts), 1, log)
        self.assertIn("recovery failed", r.stdout)

    def test_the_recovery_forwards_the_env_file_and_timeout_but_not_compose(self):
        r = self.run_ok(extra=["--timeout", "7"],
                        env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertIn(
            "rollback --remote %s --env-file %s --timeout 7 --recovery"
            % (REMOTE, self.env_file),
            log)
        self.assertNotIn("--compose", log)

    # -- the bind ------------------------------------------------------------

    def test_a_bad_bind_stops_the_stack_and_never_takes_it_down(self):
        listeners = self.tmp / "listeners-public"
        listeners.write_text(
            "LISTEN 0 128 %s:8081 *:*\n"
            "LISTEN 0 128 8.8.8.8:8083 *:*\n"
            "LISTEN 0 128 %s:8084 *:*\n" % (BIND, BIND), encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertEqual(
            [line for line in log.splitlines()
             if line.startswith("docker compose") and " stop" in line],
            ["docker compose --env-file %s -p breaker -f %s stop"
             % (self.env_file, COMPOSE)], log)
        self.assertNotIn("down", log)
        self.assertIn("8083", r.stderr)

    def test_a_vpn_bind_usage_error_leaves_the_running_version_alone(self):
        # vpn-bind exits 2 (ss failed) with no exposed line: report only.
        r = self.run_ok(env_extra={"STUB_SS_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertNotIn("docker compose -p breaker stop", log)
        self.assertNotIn("down", log)
        self.assertIn("vpn-bind failed", r.stderr)

    def test_a_not_listening_port_is_reported_without_stopping(self):
        # vpn-bind exits 3 (not-listening) with no exposed line: report only.
        listeners = self.tmp / "listeners-missing"
        listeners.write_text(
            "LISTEN 0 128 %s:8081 *:*\n"
            "LISTEN 0 128 %s:8084 *:*\n" % (BIND, BIND), encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertNotIn("docker compose -p breaker stop", log)
        self.assertNotIn("down", log)
        self.assertIn("vpn-bind failed", r.stderr)

    # -- round 3 defect 4/6: stop flags and the leftover schema record ------

    def test_the_stop_argv_carries_env_file_and_compose(self):
        # Defect 4: the fail-closed stop must name the same compose file and
        # env file as every other compose call, never a bare `-p breaker`.
        listeners = self.tmp / "listeners-public-flags"
        listeners.write_text(
            "LISTEN 0 128 %s:8081 *:*\n"
            "LISTEN 0 128 8.8.8.8:8083 *:*\n"
            "LISTEN 0 128 %s:8084 *:*\n" % (BIND, BIND), encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 1, r.stderr)
        stops = [line for line in self.log_text().splitlines()
                 if line.startswith("docker compose") and " stop" in line]
        self.assertEqual(stops, [
            "docker compose --env-file %s -p breaker -f %s stop"
            % (self.env_file, COMPOSE)], self.log_text())

    def test_no_schema_record_survives_a_successful_run(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(sorted(self.tmp.glob("update-schema-before.*")), [],
                         r.stdout + r.stderr)

    def test_no_schema_record_survives_a_failed_run(self):
        # The health check fails AFTER the record was written; the EXIT trap
        # must still remove it on this failure path.
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(sorted(self.tmp.glob("update-schema-before.*")), [],
                         r.stdout + r.stderr)

    # -- no value ever printed ----------------------------------------------

    def test_the_service_key_value_is_never_printed(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn(KEY, r.stdout + r.stderr + self.log_text())

    def test_no_environment_value_is_printed_on_the_happy_path(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout + r.stderr + self.log_text()
        self.assertNotIn(KEY, out)
        self.assertNotIn(FORWARDER_URL, out)
        for url in ("http://%s:8081/health" % BIND,
                    "http://%s:8083/health" % BIND,
                    "http://%s:8084/health" % BIND):
            self.assertIn(url, r.stdout)


    # -- D6/D7/D8/D9: scoped fixes batch B -------------------------------------------

    def test_the_recovery_call_carries_no_staging(self):
        # D6: rollback.sh OWNS its staging default (/var/tmp/breaker-deploy/
        # <stamp>-$$) and its own D6 fix CREATES STAGING_PARENT before it
        # checks it. update.sh's $STAGING is the directory it ALREADY pulled
        # into, so it is NON-EMPTY, and git-pull.sh refuses a non-empty --dest
        # (rc 2 "--dest exists and is not empty"). The recovery must therefore
        # pass NO --staging: forwarding "$STAGING" would guarantee the recovery
        # pull fails - the exact outcome D6 is about. Pin the exact argv.
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        expected = ("rollback --remote %s --env-file %s --timeout 5 "
                    "--recovery --tag v1.0.0" % (REMOTE, self.env_file))
        self.assertEqual(
            [line for line in log.splitlines()
             if line.startswith("rollback --remote")],
            [expected], log)
        self.assertNotIn("--staging", log)

    def test_the_backup_call_carries_the_default_keep_five(self):
        # D7: --keep defaults to 5 and reaches the backup call as exactly
        # `--keep 5`, after the database paths.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--out"), [
            "python3 %s --out %s --sync-db %s --whisper-db %s --keep 5"
            % (self.run_dir / "backup-data.py", self.backup_out,
               self.sync_db, self.whisper_db)], self.log_text())

    def test_an_explicit_keep_reaches_the_backup_call(self):
        r = self.run_ok(extra=["--keep", "3"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--out"), [
            "python3 %s --out %s --sync-db %s --whisper-db %s --keep 3"
            % (self.run_dir / "backup-data.py", self.backup_out,
               self.sync_db, self.whisper_db)], self.log_text())

    def test_a_bad_keep_is_refused_with_exit_2(self):
        # The message is the point: before D7 `--keep` was an unknown option,
        # so assert the specific refusal, not merely exit 2.
        for value in ("0", "-1", "abc", "1x", ""):
            with self.subTest(value=value):
                r = self.run_ok(extra=["--keep", value])
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertIn("--keep must be a positive integer", r.stderr)
                self.assertEqual(r.stdout, "")

    def test_compose_v5_is_accepted(self):
        # D8: the MAJOR is compared numerically, so v5.x passes.
        r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": "Docker Compose version v5.6.0"})
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_compose_v1_empty_and_garbage_are_refused(self):
        for value in ("Docker Compose version v1.29.2", "", "garbage"):
            with self.subTest(value=value):
                r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": value})
                self.assertEqual(r.returncode, 1, r.stderr)
                self.assertIn("not v2", r.stderr)

    def test_a_failing_docker_compose_is_still_hard(self):
        # D8: a docker that fails stays the caller's own "not available"
        # failure; only the version RULE moves to the shared helper.
        r = self.run_ok(env_extra={"STUB_COMPOSE_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("not available", r.stderr)

    def test_a_successful_recovery_reports_the_previous_tag(self):
        # Defect 5: after a successful recovery the tag RUNNING is the tag the
        # recovery redeployed - the tag recorded as running before the swap,
        # which in the plain case is the PREVIOUS release tag, never the
        # target that failed its health check.
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("update: running tag v1.0.0", r.stdout, r.stdout)
        self.assertNotIn("update: running tag v1.2.0", r.stdout)

    # -- defects 3 + 5: the running tag and the recovery target -------------

    def recovery_argv(self):
        return [line for line in self.log_text().splitlines()
                if line.startswith("rollback --remote")]

    def test_the_recovery_redeploys_the_recorded_running_tag(self):
        # Defect 5: `update --tag` LOWER than latest with a failing health. The
        # remote has v1.0.0, v1.1.0 and v1.2.0; the target is v1.1.0 and the tag
        # RUNNING before the swap is v1.0.0. The recovery must redeploy v1.0.0 -
        # the recorded running tag - not v1.1.0, the previous of the remote's
        # latest (which is the failing target).
        tags = self.tmp / "three-tags"
        tags.write_text(
            ZERO_SHA + "\trefs/tags/v1.0.0\n" +
            ZERO_SHA + "\trefs/tags/v1.1.0\n" +
            ZERO_SHA + "\trefs/tags/v1.2.0\n",
            encoding="utf-8")
        r = self.run_ok(extra=["--tag", "v1.1.0"],
                        env_extra={"STUB_CURL_RC": "1",
                                   "STUB_TAGS_FILE": str(tags)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(
            self.recovery_argv(),
            ["rollback --remote %s --env-file %s --timeout 5 "
             "--recovery --tag v1.0.0" % (REMOTE, self.env_file)],
            self.log_text())
        self.assertNotIn("--previous", self.log_text())
        self.assertIn("update: running tag v1.0.0", r.stdout, r.stdout)

    def test_the_plain_recovery_still_deploys_the_previous_tag(self):
        # The plain case (no --tag): the tag recorded as running before the
        # swap IS the previous release tag, so the recovery still lands on it.
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(
            self.recovery_argv(),
            ["rollback --remote %s --env-file %s --timeout 5 "
             "--recovery --tag v1.0.0" % (REMOTE, self.env_file)],
            self.log_text())
        self.assertIn("update: running tag v1.0.0", r.stdout, r.stdout)
        self.assertNotIn("update: running tag v1.2.0", r.stdout)

    def test_a_failed_recovery_reports_the_tag_docker_says_is_running(self):
        # Defect 3: after a FAILED recovery the report must be the tag docker
        # says is running - here v1.1.0, which is NOT the target v1.2.0.
        after = ("sync-api breaker/sync-api:v1.1.0\n"
                 "whisper-server breaker/whisper-server:v1.1.0\n"
                 "web-fe breaker/web-fe:v1.1.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1",
                                   "STUB_PS_AFTER_FAIL": after})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertIn("update: running tag v1.1.0", r.stdout, r.stdout)
        self.assertNotIn("update: running tag v1.2.0", r.stdout)
        # the report is backed by a docker read taken AFTER the recovery attempt
        self.assertGreater(log.rfind("compose -p breaker ps"),
                           log.find("rollback --remote"), log)

    def test_a_failed_recovery_with_an_unreadable_tag_reports_unknown(self):
        # Defect 3: when the running tag cannot be read after a failed
        # recovery, the report says unknown - never the target.
        fail = self.tmp / "ps-fail"
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1",
                                   "STUB_PS_FAIL_FILE": str(fail)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("update: running tag unknown", r.stdout, r.stdout)
        self.assertNotIn("update: running tag v1.2.0", r.stdout)

    # -- D2: a failed recovery says FAILED, never a clean status -------------

    def test_a_failed_recovery_prints_an_explicit_failed_line(self):
        # D2: the recovery redeploy was attempted and its health check ALSO
        # failed. The arm must say FAILED explicitly, naming the recorded tag it
        # tried, the health state and the running tag read from docker (v1.1.0 -
        # not the target v1.2.0 and not the recorded tag v1.0.0).
        after = ("sync-api breaker/sync-api:v1.1.0\n"
                 "whisper-server breaker/whisper-server:v1.1.0\n"
                 "web-fe breaker/web-fe:v1.1.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1",
                                   "STUB_PS_AFTER_FAIL": after})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn(
            "update: FAILED: recovery to v1.0.0 did not become healthy "
            "(health state: failed; running tag: v1.1.0)",
            r.stdout, r.stdout)
        self.assertNotIn("update: deployed tag", r.stdout)

    def test_a_failed_recovery_report_tag_line_is_not_a_clean_status(self):
        # D2: the report may still print the tag, but in this arm it must not
        # stand alone as a clean status for a tag that was NOT verified healthy.
        after = ("sync-api breaker/sync-api:v1.1.0\n"
                 "whisper-server breaker/whisper-server:v1.1.0\n"
                 "web-fe breaker/web-fe:v1.1.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1",
                                   "STUB_PS_AFTER_FAIL": after})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn(
            "update: running tag v1.1.0 (recovery failed; not verified healthy)",
            r.stdout, r.stdout)
        self.assertNotIn("update: running tag v1.1.0\n", r.stdout, r.stdout)
        self.assertNotIn("update: deployed tag", r.stdout)

    def test_a_failed_recovery_with_an_unreadable_tag_prints_unknown_in_the_failed_line(self):
        # D2: the FAILED line's running tag is READ from docker; when the read
        # fails it says unknown - never the recorded tag and never the target.
        fail = self.tmp / "ps-fail"
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_ROLLBACK_RC": "1",
                                   "STUB_PS_FAIL_FILE": str(fail)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn(
            "update: FAILED: recovery to v1.0.0 did not become healthy "
            "(health state: failed; running tag: unknown)",
            r.stdout, r.stdout)
        self.assertNotIn("running tag: v1.0.0)", r.stdout)

    def test_a_recorded_unknown_running_tag_skips_the_recovery_redeploy(self):
        # the running tag could not be read before the swap,
        # so there is no tag to redeploy. No rollback call is made, the stack is
        # left as it is, the message names the backup folder, exit non-zero.
        self.ps_file.write_text("", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertNotIn("rollback --remote", log)
        self.assertIn(
            "update: the release that was running could not be read; "
            "the stack is left as it is; the pre-update backup is in %s"
            % self.backup_out, r.stdout, r.stdout)


    # -- D1: the report names the tag docker says is RUNNING, never the target --
    # update_report is called with NO second argument in the no-schema-record
    # arm, the verdict-changed arm and the unknown-verdict arm. In all three
    # the tag must come from the REAL deploy_running_image_tag through the stub
    # docker seam at test_update.py:98 (`compose -p breaker ps --format
    # {{.Service}} {{.Image}}` answered from ${STUB_PS_FILE:-default-ps}), or be
    # `unknown`; the target is never a default.

    def write_ps(self, text):
        self.ps_file.write_text(text, encoding="utf-8")

    def assert_report_running_tag(self, r, tag):
        self.assertIn("update: running tag %s" % tag, r.stdout, r.stdout)
        self.assertNotIn("update: running tag v1.2.0", r.stdout)

    def test_no_schema_record_report_names_the_running_tag(self):
        # no-schema-record arm - no data was present, so nothing was recorded.
        # the one tag every running service agrees on (v1.3.0), not the target.
        self.write_ps(
            "sync-api breaker/sync-api:v1.3.0\n"
            "whisper-server breaker/whisper-server:v1.3.0\n"
            "web-fe breaker/web-fe:v1.3.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1",
                                   "STUB_SYNC_MOUNT": "",
                                   "STUB_WHISPER_MOUNT": ""})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assert_report_running_tag(r, "v1.3.0")

    def test_no_schema_record_report_unknown_on_mixed_tags(self):
        # no-schema-record arm - the services DISAGREE: unknown.
        self.write_ps(
            "sync-api breaker/sync-api:v1.3.0\n"
            "whisper-server breaker/whisper-server:v1.4.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1",
                                   "STUB_SYNC_MOUNT": "",
                                   "STUB_WHISPER_MOUNT": ""})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assert_report_running_tag(r, "unknown")

    def test_no_schema_record_report_unknown_when_nothing_is_running(self):
        # no-schema-record arm - nothing is running (an empty ps answer):
        # unknown, never the target.
        self.write_ps("")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1",
                                   "STUB_SYNC_MOUNT": "",
                                   "STUB_WHISPER_MOUNT": ""})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assert_report_running_tag(r, "unknown")

    def test_no_schema_record_report_unknown_when_the_read_fails(self):
        # no-schema-record arm - the ps read FAILS: unknown, never the target.
        fail = self.tmp / "ps-fail"
        fail.write_text("", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1",
                                   "STUB_SYNC_MOUNT": "",
                                   "STUB_WHISPER_MOUNT": "",
                                   "STUB_PS_FAIL_FILE": str(fail)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assert_report_running_tag(r, "unknown")

    def test_changed_verdict_report_names_the_running_tag(self):
        # verdict-changed arm - the new version migrated, so the stack is left
        # as it is. The report names the tag docker says is running, not the
        # target.
        self.write_ps(
            "sync-api breaker/sync-api:v1.3.0\n"
            "whisper-server breaker/whisper-server:v1.3.0\n"
            "web-fe breaker/web-fe:v1.3.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_MIGRATE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict changed", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "v1.3.0")

    def test_changed_verdict_report_unknown_on_mixed_tags(self):
        # verdict-changed arm - the services DISAGREE: unknown.
        self.write_ps(
            "sync-api breaker/sync-api:v1.3.0\n"
            "whisper-server breaker/whisper-server:v1.4.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_MIGRATE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict changed", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "unknown")

    def test_changed_verdict_report_unknown_when_nothing_is_running(self):
        # verdict-changed arm - nothing is running (an empty ps answer): unknown.
        self.write_ps("")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_MIGRATE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict changed", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "unknown")

    def test_changed_verdict_report_unknown_when_the_read_fails(self):
        # verdict-changed arm - the ps read FAILS: unknown.
        fail = self.tmp / "ps-fail"
        fail.write_text("", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_MIGRATE": "1",
                                   "STUB_PS_FAIL_FILE": str(fail)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict changed", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "unknown")

    def test_unknown_verdict_report_names_the_running_tag(self):
        # unknown-verdict arm - the post-swap read cannot be made. The report
        # still names the tag docker says is running.
        self.write_ps(
            "sync-api breaker/sync-api:v1.3.0\n"
            "whisper-server breaker/whisper-server:v1.3.0\n"
            "web-fe breaker/web-fe:v1.3.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_DELETE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict unknown", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "v1.3.0")

    def test_unknown_verdict_report_unknown_on_mixed_tags(self):
        # unknown-verdict arm - the services DISAGREE: unknown.
        self.write_ps(
            "sync-api breaker/sync-api:v1.3.0\n"
            "whisper-server breaker/whisper-server:v1.4.0\n")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_DELETE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict unknown", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "unknown")

    def test_unknown_verdict_report_unknown_when_nothing_is_running(self):
        # unknown-verdict arm - nothing is running (an empty ps answer): unknown.
        self.write_ps("")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_DELETE": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict unknown", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "unknown")

    def test_unknown_verdict_report_unknown_when_the_read_fails(self):
        # unknown-verdict arm - the ps read FAILS: unknown.
        fail = self.tmp / "ps-fail"
        fail.write_text("", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1", "STUB_UP_DELETE": "1",
                                   "STUB_PS_FAIL_FILE": str(fail)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema verdict unknown", r.stdout, r.stdout)
        self.assert_report_running_tag(r, "unknown")


if __name__ == "__main__":
    unittest.main()
