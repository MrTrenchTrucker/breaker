"""Unit tests for scripts/rollback.sh.

Drives the REAL rollback.sh through a temp PATH of stubs (git, docker, curl,
ss, stat, chown, python3, gradlew) and the REAL sibling scripts (git-pull.sh,
health-check.sh, vpn-bind.sh), so no host, docker or network is touched. The
stub log records the argv of every call; the tests assert the step order and
every guard. Each guard is proven red first (the mutant table is in the plan
file PLAN_DEP2_S2_ROLLBACK.md).

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_rollback.py'
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
SCRIPT = SCRIPTS / "rollback.sh"
COMPOSE = REPO_ROOT / "server" / "modules" / "deploy" / "docker-compose.yml"
ENV_EXAMPLE = REPO_ROOT / "server" / "modules" / "deploy" / ".env.example"

# The file the tests run. The mutant proofs point ROLLBACK_SRC at a scratch
# copy with one branch removed; the default is the real file.
ROLLBACK_SRC = Path(os.environ.get("ROLLBACK_SRC", str(SCRIPT)))
# The backup script is a sibling of the file under test.
BACKUP_PY = ROLLBACK_SRC.parent / "backup-data.py"

REMOTE = "https://example.invalid/breaker.git"
PLACEHOLDER = "placeholder-value-42"
# 43 characters of the URL-safe alphabet, the ServiceKeyFile.kt rule.
KEY = "rollback-key-0123456789ABCDEFGHIJKLMNOPQRS"
# ruling 2(b): a URL can carry credentials, so it is never printed either.
FORWARDER_URL = "http://10.20.30.12:9000"

# The two named volumes the compose file declares (project `breaker`). The
# stub docker answers `volume inspect` with the per-test HOST mountpoints
# built in setUp, and deploy_db_paths turns them into the HOST database paths
# the backup must receive.
SYNC_VOLUME = "breaker_breaker_sync_api_data"
WHISPER_VOLUME = "breaker_breaker_whisper_data"
# The CONTAINER paths the compose `environment:` sets as literals. They must
# never reach the host-side backup as a --sync-db/--whisper-db value.
SYNC_CONTAINER_DB = "/var/lib/breaker/sync-api/sync-api.db"
WHISPER_CONTAINER_DB = "/var/lib/breaker/whisper-server/whisper-server.db"
# The REAL backup tool, and the tables its restore check requires. The stub
# execs this file (not the scratch copy's sibling) so the backup path is
# exercised end to end; the tables are the tool's EXPECTED_TABLES contract.
REAL_SCRIPTS = REPO_ROOT / "server" / "modules" / "deploy" / "scripts"
REAL_BACKUP_PY = REAL_SCRIPTS / "backup-data.py"
SYNC_TABLES = (
    "accounts", "tokens", "transcriptions", "server_settings",
    "signin_attempts", "signin_handoff", "reset_codes", "pending_sealed",
)

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
    if [ "${STUB_GRADLEW_EXEC:-1}" = 1 ]; then chmod +x "$dest/gradlew"; else chmod 644 "$dest/gradlew"; fi
    exit 0
fi
exit 0
"""

STUB_DOCKER = r"""#!/bin/sh
printf 'docker %s\n' "$*" >> "$STUB_LOG"
printf 'docker-env BREAKER_IMAGE_TAG=%s\n' "${BREAKER_IMAGE_TAG:+set}" >> "$STUB_LOG"
case "$*" in
    "compose version")
        # `${VAR-default}` (no colon) keeps an EXPLICITLY EMPTY value: with
        # `:-` an empty STUB_COMPOSE_VERSION would silently become the
        # default, and the "empty answer is refused" test could not fail.
        printf '%s\n' "${STUB_COMPOSE_VERSION-Docker Compose version v2.20.0}"
        exit "${STUB_COMPOSE_RC:-0}"
        ;;
    "buildx version")
        printf '%s\n' "${STUB_BUILDX_VERSION:-Docker buildx version 0.12.0}"
        exit "${STUB_BUILDX_RC:-0}"
        ;;
    "volume ls --format {{.Name}}")
        _sd_rc=${STUB_VOLUME_LS_RC:-0}
        if [ "$_sd_rc" -ne 0 ]; then
            printf '%s\n' "${STUB_VOLUME_LS_ERR:-stub docker: volume ls failed}" >&2
            exit "$_sd_rc"
        fi
        cat "${STUB_VOLUMES_FILE:-$STUB_BIN/default-volumes}" 2>/dev/null
        exit 0
        ;;
    "volume inspect -f {{.Mountpoint}} "*)
        shift 4
        _sd_name=$1
        # The mountpoint is the REST OF THE LINE: a host path may contain a
        # space (the backup call-shape fix), and awk's $2 would truncate it.
        _sd_mount=$(awk -v n="$_sd_name" '$1 == n { sub(/^[^ \t]+[ \t]/, ""); print; exit }' \
            "${STUB_MOUNTS_FILE:-$STUB_BIN/default-mounts}" 2>/dev/null)
        [ -n "$_sd_mount" ] || {
            [ "${STUB_INSPECT_EMPTY:-0}" = "1" ] && exit 0
            exit 1
        }
        printf '%s\n' "$_sd_mount"
        exit 0
        ;;
esac
case "$*" in
    *" up -d"*) exit "${STUB_UP_RC:-0}" ;;
    *" build"*) exit "${STUB_BUILD_RC:-0}" ;;
    *" stop"*) exit "${STUB_STOP_RC:-0}" ;;
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
cat "${STUB_LISTENERS_FILE:-$STUB_BIN/default-listeners}"
exit 0
"""

STUB_PYTHON3 = r"""#!/bin/sh
printf 'python3 %s\n' "$*" >> "$STUB_LOG"
# Only the real backup tool runs for real: the stub records the argv, then
# execs the REAL backup-data.py under the real interpreter, so the backup
# path is exercised end to end. Every other python3 call (the preflight
# `python3 -c import sqlite3` probe) keeps the old stub verdict.
if [ "$#" -gt 0 ]; then
    case "$1" in
        */backup-data.py|backup-data.py)
            shift
            # STUB_ARGV_LOG (optional) records one argv entry per line, so a
            # test can observe argument BOUNDARIES: `$*` joins them with
            # spaces and cannot show that a path with a space stayed one arg.
            if [ -n "${STUB_ARGV_LOG:-}" ]; then
                _sp_i=0
                for _sp_a in "$@"; do
                    printf 'backup-argv[%s]=%s\n' "$_sp_i" "$_sp_a" >> "$STUB_ARGV_LOG"
                    _sp_i=$((_sp_i + 1))
                done
            fi
            exec "${STUB_REAL_PYTHON3:-python3}" "${STUB_REAL_BACKUP_PY:-backup-data.py}" "$@"
            ;;
    esac
fi
exit "${STUB_PYTHON3_RC:-0}"
"""

STUB_STAT = r"""#!/bin/sh
printf 'stat %s\n' "$*" >> "$STUB_LOG"
# %u is intercepted so the owner check is testable without a privilege:
# STUB_STAT_UID defaults to the required 10001. %a (mode) stays real.
case "$*" in
    *"%u"*) printf '%s\n' "${STUB_STAT_UID:-10001}"; exit 0 ;;
esac
exec "$STUB_REAL_STAT" "$@"
"""

STUB_CHOWN = r"""#!/bin/sh
printf 'chown %s\n' "$*" >> "$STUB_LOG"
exit 0
"""

STUB_SQLITE3 = r"""#!/bin/sh
printf 'sqlite3 %s\n' "$*" >> "$STUB_LOG"
exit 0
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

# The core utilities the real sibling scripts and the stubs need on PATH.
CORE_TOOLS = ("sh", "dirname", "sed", "awk", "sort", "grep", "tail", "cat",
              "basename", "cut", "tr", "date", "pwd", "chmod", "cp", "mkdir")


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
    "BREAKER_BIND_ADDR": "10.8.0.5",
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


class RollbackTest(unittest.TestCase):
    """Drives the real rollback.sh through the stubs."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-rollback-test-"))
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, text in (("git", STUB_GIT), ("docker", STUB_DOCKER),
                           ("curl", STUB_CURL), ("ss", STUB_SS),
                           ("python3", STUB_PYTHON3), ("stat", STUB_STAT),
                           ("chown", STUB_CHOWN), ("gradlew", STUB_GRADLEW),
                           ("sqlite3", STUB_SQLITE3)):
            self._write(self.bin / name, text)
        for tool in CORE_TOOLS:
            source = shutil.which(tool)
            if source:
                os.symlink(source, str(self.bin / tool))
        # two release tags: v1.0.0 is the previous one
        self._write(self.bin / "default-tags",
                    ZERO_SHA + "\trefs/tags/v1.0.0\n" +
                    ZERO_SHA + "\trefs/tags/v1.2.0\n")
        shutil.copyfile(COMPOSE, str(self.bin / "default-compose.yml"))
        shutil.copyfile(ENV_EXAMPLE, str(self.bin / "default-env-example"))
        self._write(self.bin / "default-listeners",
                    "LISTEN 0 128 10.8.0.5:8081 *:*\n"
                    "LISTEN 0 128 10.8.0.5:8083 *:*\n"
                    "LISTEN 0 128 10.8.0.5:8084 *:*\n")
        # The daemon's `docker volume ls --format '{{.Name}}'` answer (both
        # volumes exist) and the NAME SPACE MOUNT table for `volume inspect`.
        # The mountpoints are per-test temp dirs holding two REAL SQLite
        # databases: the python3 stub execs the REAL backup-data.py, so every
        # rollback run that reaches the backup step performs a real backup.
        self.data = self.tmp / "data"
        self.sync_host_db = self.data / "sync" / "sync-api.db"
        self.whisper_host_db = self.data / "whisper" / "whisper-server.db"
        self.make_db(self.sync_host_db, SYNC_TABLES)
        self.make_db(self.whisper_host_db, ("jobs",))
        self._write(self.bin / "default-volumes",
                    "%s\n%s\n" % (SYNC_VOLUME, WHISPER_VOLUME))
        self._write(self.bin / "default-mounts",
                    "%s %s\n%s %s\n"
                    % (SYNC_VOLUME, self.data / "sync",
                       WHISPER_VOLUME, self.data / "whisper"))
        self.env_file = self.tmp / "stack.env"
        self.key_file = self.tmp / "service-key"
        self.write_key()
        self.write_env()
        self.staging = self.tmp / "staging"
        self.backup_out = self.tmp / "backups"
        self.log = self.tmp / "stub.log"
        self.env = dict(os.environ)
        for name in list(self.env):
            if name.startswith("BREAKER_"):
                del self.env[name]
        self.env["PATH"] = str(self.bin)
        self.env["STUB_LOG"] = str(self.log)
        self.env["STUB_BIN"] = str(self.bin)
        self.env["STUB_REAL_STAT"] = shutil.which("stat") or "/usr/bin/stat"
        self.env["STUB_STAT_UID"] = "10001"
        # The stub python3 execs the REAL backup tool under the REAL
        # interpreter (never the stub PATH, which would recurse).
        self.env["STUB_REAL_PYTHON3"] = shutil.which("python3") or sys.executable
        self.env["STUB_REAL_BACKUP_PY"] = str(REAL_BACKUP_PY)
        self.env["STUB_TAGS_FILE"] = str(self.bin / "default-tags")
        self.env["STUB_LISTENERS_FILE"] = str(self.bin / "default-listeners")
        # The shared helper retries the single-GET health-check; spend the
        # bound without sleeping so the suite stays fast.
        self.env["HEALTH_STEP"] = "0"
        self.env["HEALTH_WAIT"] = "2"

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def _write(self, path, text):
        path.write_text(text, encoding="utf-8")
        os.chmod(str(path), 0o755)

    def make_db(self, path, tables):
        """A real SQLite database carrying the tables backup-data.py expects."""
        path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(path))
        for table in tables:
            conn.execute(
                "CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY, note TEXT)"
                % table)
        conn.commit()
        conn.close()

    def real_tool_env(self):
        """The environment for a direct backup-data.py call: no DB settings."""
        env = dict(os.environ)
        for name in ("BREAKER_SYNC_API_DB", "BREAKER_WHISPER_DB",
                     "BREAKER_BACKUP_NOW"):
            env.pop(name, None)
        return env

    def write_key(self, content=KEY, mode=0o600):
        self.key_file.write_text(content + "\n", encoding="utf-8")
        os.chmod(str(self.key_file), mode)

    def write_env(self, overrides=None, mode=0o600):
        values = dict(BASE_VALUES)
        values["BREAKER_SYNC_API_SERVICE_KEY_HOST"] = str(self.key_file)
        values.update(overrides or {})
        lines = ["%s=%s" % (name, values.get(name, PLACEHOLDER))
                 for name in env_example_names()]
        # The two database names are not in .env.example (the compose file's
        # `environment:` sets them as CONTAINER paths), so ENV_FILE normally
        # does not carry them. A test may override them to prove the backup
        # still receives the HOST paths deploy_db_paths resolves.
        for name in ("BREAKER_SYNC_API_DB", "BREAKER_WHISPER_DB"):
            if name in values:
                lines.append("%s=%s" % (name, values[name]))
        self.env_file.write_text("".join(line + "\n" for line in lines),
                                 encoding="utf-8")
        os.chmod(str(self.env_file), mode)

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
        return subprocess.run(["sh", str(ROLLBACK_SRC)] + list(args),
                              capture_output=True, text=True, env=env, timeout=60)

    def run_ok(self, extra=(), env_extra=None, **kwargs):
        return self.invoke(self.base_args(**kwargs) + list(extra), env_extra)

    def log_text(self):
        return self.log.read_text(encoding="utf-8") if self.log.exists() else ""

    # -- usage --------------------------------------------------------------

    def test_usage_errors_exit_two(self):
        cases = [
            [],
            ["--remote"],
            ["--remote", REMOTE, "--bogus"],
            ["--remote", REMOTE, "--env-file"],
            ["--remote", REMOTE, "--timeout", "abc"],
            ["--remote", REMOTE, "--timeout", "0"],
            ["--remote", REMOTE, "--timeout", "61"],
            ["--remote", REMOTE, "--tag"],
            ["--remote", REMOTE, "--previous", "--tag", "v1.2.0"],
            ["--remote", REMOTE, "positional"],
        ]
        for args in cases:
            with self.subTest(args=args):
                r = self.invoke(args)
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertEqual(self.log_text(), "")

    # -- the happy path and the order ---------------------------------------

    def test_the_step_order_is_pull_build_image_backup_swap_health_bind(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        order = [
            ("pull", "ls-remote"),
            ("clone", "clone"),
            ("host build", "gradlew"),
            ("image build", "build --no-cache"),
            ("backup", "backup-data.py --out"),
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

    def test_the_three_host_health_urls_use_the_bind_address(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        for url in ("http://10.8.0.5:8081/health",
                    "http://10.8.0.5:8083/health",
                    "http://10.8.0.5:8084/health"):
            self.assertIn(url, log)
        self.assertIn("docker-env BREAKER_IMAGE_TAG=set", log)

    def test_an_ipv6_private_bind_is_bracketed_in_the_health_urls(self):
        listeners = self.tmp / "listeners-ipv6"
        listeners.write_text(
            "LISTEN 0 128 [fd00::5]:8081 *:*\n"
            "LISTEN 0 128 [fd00::5]:8083 *:*\n"
            "LISTEN 0 128 [fd00::5]:8084 *:*\n", encoding="utf-8")
        self.write_env(overrides={"BREAKER_BIND_ADDR": "fd00::5"})
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 0, r.stderr)
        for url in ("http://[fd00::5]:8081/health",
                    "http://[fd00::5]:8083/health",
                    "http://[fd00::5]:8084/health"):
            self.assertIn(url, r.stdout)
        self.assertNotIn("http://fd00::5:", r.stdout)

    def test_previous_is_pulled_from_git_pull(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("ls-remote --tags", log)
        self.assertIn("clone", log)
        self.assertIn("checkout -q --detach refs/tags/v1.0.0", log)
        self.assertNotIn("refs/tags/v1.2.0", log)

    def test_the_compose_defaults_under_staging(self):
        r = self.invoke(self.base_args(use_compose=False))
        self.assertEqual(r.returncode, 0, r.stderr)
        expected = str(self.staging / "server" / "modules" / "deploy" /
                       "docker-compose.yml")
        self.assertIn(expected, self.log_text())

    # -- the pull failures --------------------------------------------------

    def test_no_previous_tag_exits_three_and_never_swaps(self):
        tags = self.tmp / "one-tag"
        tags.write_text(ZERO_SHA + "\trefs/tags/v1.0.0\n",
                        encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_TAGS_FILE": str(tags)})
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertIn("previous", r.stderr)
        log = self.log_text()
        self.assertNotIn("up -d", log)
        self.assertNotIn("gradlew", log)
        self.assertNotIn(" build\n", log)

    # -- preflight part 1 ---------------------------------------------------

    def test_a_missing_tool_fails_before_the_pull(self):
        os.remove(str(self.bin / "git"))
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("git", r.stderr)
        self.assertEqual(self.log_text(), "")

    def test_a_missing_sqlite3_cli_is_accepted(self):
        # Nothing invokes the sqlite3 CLI: every schema read goes through
        # python3's module, so its absence must not fail preflight.
        (self.bin / "sqlite3").unlink()
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("required tool missing: sqlite3", r.stderr)

    def test_a_python3_without_the_sqlite3_module_is_refused_at_preflight_before_the_build(self):
        # The redundant early probe is gone: the module check now lives only in
        # deploy_preflight, which runs after the pull and before the host build.
        # A broken python3 is still refused before any build, backup or swap.
        r = self.run_ok(env_extra={"STUB_PYTHON3_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("python3", r.stderr)
        self.assertIn("sqlite3", r.stderr)
        log = self.log_text()
        check = log.find("python3 -c import sqlite3")
        self.assertGreater(check, -1, log)
        # the check fired at preflight, i.e. AFTER the pull ...
        self.assertIn("clone", log)
        self.assertGreater(check, log.find("clone"), log)
        # ... and BEFORE any host build, image build, backup or swap.
        for banned in ("gradlew", "build\n", "up -d", "backup-data.py"):
            self.assertNotIn(banned, log)

    def test_a_checkout_without_an_executable_gradlew_is_refused_before_the_build(self):
        # gradlew is a property of the pulled tag, so the build step checks it
        # in the checkout, before any gradle invocation.
        r = self.run_ok(env_extra={"STUB_GRADLEW_EXEC": "0"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn(str(self.staging / "gradlew"), r.stderr)
        self.assertNotIn("gradlew", self.log_text())

    def test_an_unwritable_staging_parent_fails_before_the_pull(self):
        parent = self.tmp / "parent"
        parent.mkdir()
        os.chmod(str(parent), 0o555)
        try:
            r = self.invoke(self.base_args(staging=parent / "staging"))
            self.assertEqual(r.returncode, 1, r.stderr)
            self.assertIn("STAGING_PARENT", r.stderr)
            # preflight's own `stat` MAY be logged; no docker, git, gradlew or
            # curl call may happen before the failure.
            for line in self.log_text().splitlines():
                self.assertFalse(line.startswith(("docker", "git", "gradlew", "curl")),
                                 line)
        finally:
            os.chmod(str(parent), 0o700)

    def test_a_non_v2_compose_is_refused_before_the_pull(self):
        r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": "Docker Compose version v1.29.2"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("compose", r.stderr)
        self.assertNotIn("ls-remote", self.log_text())

    def test_a_missing_buildx_is_a_note_not_a_failure(self):
        r = self.run_ok(env_extra={"STUB_BUILDX_RC": "1"})
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("NOTE", r.stdout)

    # -- preflight part 2 ---------------------------------------------------

    def test_env_file_not_0600_exits_one_and_never_swaps(self):
        os.chmod(str(self.env_file), 0o644)
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("0600", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_required_name_empty_exits_one_and_never_swaps(self):
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

    # -- the service key ----------------------------------------------------

    def test_a_missing_key_file_exits_one_and_is_never_created(self):
        self.key_file.unlink()
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_SYNC_API_SERVICE_KEY_HOST", r.stderr)
        self.assertIn("missing", r.stderr)
        self.assertFalse(self.key_file.exists())
        self.assertNotIn("up -d", self.log_text())

    def test_a_key_file_with_the_wrong_mode_exits_one_and_is_unchanged(self):
        os.chmod(str(self.key_file), 0o644)
        before = self.key_file.read_bytes()
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("0600", r.stderr)
        self.assertEqual(self.key_file.read_bytes(), before)
        self.assertNotIn("up -d", self.log_text())

    def test_a_key_file_that_does_not_match_the_env_value_exits_one_and_is_unchanged(self):
        self.write_key(content="a-different-key-value")
        before = self.key_file.read_bytes()
        r = self.run_ok()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_WHISPER_SYNC_API_SERVICE_KEY", r.stderr)
        self.assertEqual(self.key_file.read_bytes(), before)
        self.assertNotIn("up -d", self.log_text())

    def test_the_key_file_owner_check_accepts_uid_10001(self):
        # control: the stub stat reports the required owner, so the check
        # passes and the run reaches the swap.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("stat -c %u", self.log_text())
        self.assertIn("up -d", self.log_text())

    def test_a_key_file_owned_by_another_uid_exits_one_and_never_swaps(self):
        r = self.run_ok(env_extra={"STUB_STAT_UID": "1001"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("10001", r.stderr)
        self.assertIn(str(self.key_file), r.stderr)
        self.assertNotIn(KEY, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    # -- the backup ---------------------------------------------------------

    def test_the_backup_runs_before_the_swap(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("backup-data.py --out", log)
        self.assertLess(log.find("backup-data.py --out"), log.find("up -d"), log)

    def test_no_backup_skips_the_backup_call(self):
        r = self.run_ok(["--no-backup"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("backup-data.py", self.log_text())

    def test_recovery_skips_the_backup_call(self):
        r = self.run_ok(["--recovery"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("backup-data.py", self.log_text())

    def test_recovery_runs_build_image_swap_health_and_no_backup_or_schema_record(self):
        r = self.run_ok(["--recovery"])
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        for needle in ("gradlew", "build --no-cache", "up -d", "curl"):
            self.assertIn(needle, log)
        self.assertNotIn("backup-data.py", log)
        self.assertNotIn("--record-schema", log)

    # -- defect 5: --tag and --previous are mutually exclusive --------------

    def test_a_named_tag_is_pulled_from_git_pull(self):
        # Defect 5: `--tag TAG` makes the recovery redeploy EXACTLY that tag,
        # so the pull argv names it and never --previous.
        r = self.run_ok(["--tag", "v1.2.0"])
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("checkout -q --detach refs/tags/v1.2.0", log)
        self.assertNotIn("--previous", log)

    def test_previous_is_still_the_default(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("checkout -q --detach refs/tags/v1.0.0", self.log_text())

    def test_an_explicit_previous_is_accepted(self):
        r = self.run_ok(["--previous"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("checkout -q --detach refs/tags/v1.0.0", self.log_text())

    def test_tag_and_previous_together_are_a_usage_error(self):
        r = self.run_ok(["--tag", "v1.2.0", "--previous"])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("mutually exclusive", r.stderr)
        self.assertEqual(self.log_text(), "")

    def test_tag_without_a_value_is_a_usage_error(self):
        r = self.run_ok(["--tag"])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--tag", r.stderr)
        self.assertEqual(self.log_text(), "")

    def test_a_tag_absent_from_the_remote_exits_three(self):
        r = self.run_ok(["--tag", "v9.9.9"])
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    # -- the external call shape (D2/D3/D15) --------------------------------

    def test_the_image_build_call_carries_env_file_and_no_cache(self):
        # D2: the build interpolates ${...:?...}, so it needs --env-file, in
        # the install.sh:424 position. D15: the owner's rule is "always
        # rebuild from scratch".
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
        builds = [line for line in self.log_text().splitlines()
                  if line.startswith("gradlew ")]
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

    def test_the_up_call_carries_env_file(self):
        # D2: up -d interpolates the same names as the build.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(
            "docker compose --env-file %s -p breaker -f %s up -d"
            % (self.env_file, COMPOSE),
            self.log_text())

    def test_the_stop_call_carries_env_file_and_compose(self):
        # Defect 4: the fail-closed stop names the SAME project, compose file
        # and env file as every other compose call, so it cannot silently miss
        # the stack on a host where a bare `-p breaker` is not enough.
        listeners = self.tmp / "listeners-public"
        listeners.write_text(
            "LISTEN 0 128 10.8.0.5:8081 *:*\n"
            "LISTEN 0 128 8.8.8.8:8083 *:*\n"
            "LISTEN 0 128 10.8.0.5:8084 *:*\n", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        stop_lines = [line for line in log.splitlines()
                      if line.startswith("docker compose") and " stop" in line]
        self.assertEqual(
            stop_lines,
            ["docker compose --env-file %s -p breaker -f %s stop"
             % (self.env_file, COMPOSE)], log)
        self.assertNotIn(" ps", log)
        self.assertNotIn("down", log)

    def backup_argv_lines(self):
        """The exact python3 backup-data.py argv lines from the stub log."""
        return [line for line in self.log_text().splitlines()
                if line.startswith("python3 ") and "backup-data.py" in line]

    def test_the_backup_call_carries_the_sync_and_whisper_host_paths(self):
        # D3: the backup runs on the HOST, so rollback passes the HOST paths
        # deploy_db_paths resolved from the named volumes, under the names
        # backup-data.py's --out mode reads: --sync-db then --whisper-db.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("--sync-db %s" % self.sync_host_db, log)
        self.assertIn("--whisper-db %s" % self.whisper_host_db, log)
        self.assertIn(
            "python3 %s --out %s --sync-db %s --whisper-db %s"
            % (BACKUP_PY, self.backup_out, self.sync_host_db, self.whisper_host_db),
            log)
        self.assertNotIn("--db", log)

    def test_the_backup_never_passes_the_container_path_from_env_file(self):
        # D3: even when ENV_FILE carries the container path, the backup gets
        # the HOST path deploy_db_paths resolved - never the container path.
        self.write_env(overrides={"BREAKER_SYNC_API_DB": SYNC_CONTAINER_DB,
                                  "BREAKER_WHISPER_DB": WHISPER_CONTAINER_DB})
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn("--sync-db %s" % self.sync_host_db, log)
        self.assertIn("--whisper-db %s" % self.whisper_host_db, log)
        self.assertNotIn("--sync-db %s" % SYNC_CONTAINER_DB, log)
        self.assertNotIn("--whisper-db %s" % WHISPER_CONTAINER_DB, log)
        self.assertNotIn("--db", log)

    def test_the_backup_is_skipped_when_no_database_volume_exists(self):
        # Both volumes absent (first install): deploy_db_paths prints two
        # empty lines, so there is no data to lose and backup-data.py --out is
        # never called; the swap still happens.
        empty = self.tmp / "no-volumes"
        empty.write_text("", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_VOLUMES_FILE": str(empty)})
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertNotIn("backup-data.py --out", log)
        self.assertEqual(self.backup_argv_lines(), [], log)
        self.assertIn("nothing to back up", r.stdout)
        self.assertIn("up -d", log)

    def test_one_absent_volume_passes_only_the_present_host_path(self):
        # One volume absent: only the present HOST path is passed, under its
        # own name; the argv carries no empty value and no --whisper-db.
        only_sync = self.tmp / "only-sync-volume"
        only_sync.write_text("%s\n" % SYNC_VOLUME, encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_VOLUMES_FILE": str(only_sync)})
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn(
            "python3 %s --out %s --sync-db %s"
            % (BACKUP_PY, self.backup_out, self.sync_host_db), log)
        self.assertNotIn("--whisper-db", log)
        argv = self.backup_argv_lines()
        self.assertEqual(len(argv), 1, log)
        self.assertEqual(argv[0].count("--sync-db"), 1, log)
        self.assertNotIn("--sync-db  ", argv[0], log)

    def backup_argv_entries(self, path):
        """The recorded backup-data.py argv entries (one per line, boundaries)."""
        entries = []
        for line in path.read_text(encoding="utf-8").splitlines():
            self.assertTrue(line.startswith("backup-argv["), line)
            entries.append(line.split("]=", 1)[1])
        return entries

    def test_a_host_path_with_a_space_reaches_the_backup_as_one_argument(self):
        # the backup argv used to be a concatenated STRING that
        # the call site word-split, so a host path containing a space was
        # broken into several arguments. Both mountpoints below contain a
        # space, and the stub records the real argv boundaries.
        sync_dir = self.data / "sync dir"
        whisper_dir = self.data / "whisper dir"
        self.sync_host_db = sync_dir / "sync-api.db"
        self.whisper_host_db = whisper_dir / "whisper-server.db"
        self.make_db(self.sync_host_db, SYNC_TABLES)
        self.make_db(self.whisper_host_db, ("jobs",))
        mounts = self.tmp / "mounts-with-space"
        mounts.write_text(
            "%s %s\n%s %s\n" % (SYNC_VOLUME, sync_dir, WHISPER_VOLUME, whisper_dir),
            encoding="utf-8")
        argv_log = self.tmp / "backup-argv.log"
        stamp = "20260112T120000Z"
        r = self.run_ok(env_extra={
            "STUB_MOUNTS_FILE": str(mounts),
            "STUB_ARGV_LOG": str(argv_log),
            "BREAKER_BACKUP_NOW": stamp,
        })
        argv = self.backup_argv_entries(argv_log)
        self.assertEqual(argv[:2], ["--out", str(self.backup_out)], argv)
        sync_at = [i for i, a in enumerate(argv) if a == "--sync-db"]
        whisper_at = [i for i, a in enumerate(argv) if a == "--whisper-db"]
        self.assertEqual(len(sync_at), 1, argv)
        self.assertEqual(argv[sync_at[0] + 1], str(self.sync_host_db), argv)
        self.assertEqual(len(whisper_at), 1, argv)
        self.assertEqual(argv[whisper_at[0] + 1], str(self.whisper_host_db), argv)
        self.assertEqual(r.returncode, 0, r.stderr)
        stamp_dir = self.backup_out / stamp
        self.assertTrue((stamp_dir / "sync-api.bak").is_file(), stamp_dir)
        self.assertTrue((stamp_dir / "whisper-server.bak").is_file(), stamp_dir)

    def test_a_volume_ls_failure_is_a_hard_failure_before_the_swap(self):
        # deploy_db_paths keeps its hard-failure arm: a failing `docker volume
        # ls` exits 1 and the stack is never swapped.
        r = self.run_ok(env_extra={"STUB_VOLUME_LS_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("volume ls", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_the_backup_path_runs_the_real_tool_on_two_real_databases(self):
        # D3: the python3 stub records the argv and execs the REAL
        # backup-data.py, so this drives the real backup against the two real
        # SQLite databases the default mountpoints resolve to.
        stamp = "20260111T120000Z"
        r = self.run_ok(env_extra={"BREAKER_BACKUP_NOW": stamp})
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertIn(
            "python3 %s --out %s --sync-db %s --whisper-db %s"
            % (BACKUP_PY, self.backup_out, self.sync_host_db, self.whisper_host_db),
            log)
        self.assertNotIn("--db", log)
        stamp_dir = self.backup_out / stamp
        self.assertTrue((stamp_dir / "sync-api.bak").is_file(), stamp_dir)
        self.assertTrue((stamp_dir / "whisper-server.bak").is_file(), stamp_dir)
        verify = subprocess.run(
            [sys.executable, str(REAL_BACKUP_PY), "--verify", str(stamp_dir)],
            capture_output=True, text=True, env=self.real_tool_env())
        self.assertEqual(verify.returncode, 0, verify.stderr)
        self.assertIn("sync-api OK", verify.stdout)
        self.assertIn("whisper-server OK", verify.stdout)

    def test_the_old_out_db_shape_is_refused_by_the_real_tool(self):
        # D3: `--db` belongs only to --record-schema/--has-data; the --out
        # mode reads --sync-db/--whisper-db, so the old shape is a usage error
        # and writes nothing.
        out = self.tmp / "old-shape-out"
        first = self.tmp / "old-first.db"
        second = self.tmp / "old-second.db"
        r = subprocess.run(
            [sys.executable, str(REAL_BACKUP_PY), "--out", str(out),
             "--db", str(first), "--db", str(second)],
            capture_output=True, text=True, env=self.real_tool_env())
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("no database to back up", r.stderr)
        self.assertFalse(out.exists())

    # -- the swap and after -------------------------------------------------

    def test_a_health_failure_leaves_the_previous_tag_running_and_never_redeploys(self):
        r = self.run_ok(env_extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertEqual(log.count("ls-remote"), 1, log)
        self.assertEqual(log.count("clone"), 1, log)
        self.assertEqual(log.count("up -d"), 1, log)
        self.assertNotIn(" stop", log)
        self.assertNotIn("ss -ltnH", log)

    def test_a_bad_bind_after_the_swap_stops_the_stack_and_never_takes_it_down(self):
        listeners = self.tmp / "listeners-public"
        listeners.write_text(
            "LISTEN 0 128 10.8.0.5:8081 *:*\n"
            "LISTEN 0 128 8.8.8.8:8083 *:*\n"
            "LISTEN 0 128 10.8.0.5:8084 *:*\n", encoding="utf-8")
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
        # exit 2 (unreadable listener file) carries no `exposed` line, so the
        # stack is reported and left running: no stop, no down.
        r = self.run_ok(env_extra={"VPN_BIND_LISTENERS_FILE":
                                   str(self.tmp / "no-such-listeners")})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("vpn-bind failed", r.stderr)
        log = self.log_text()
        self.assertIn("up -d", log)
        self.assertNotIn(" stop", log)
        self.assertNotIn("down", log)

    def test_a_not_listening_port_leaves_the_running_version_alone(self):
        # exit 3: a port with no listener row is `not-listening`, not
        # `exposed`; it must not stop the stack.
        listeners = self.tmp / "listeners-missing-port"
        listeners.write_text(
            "LISTEN 0 128 10.8.0.5:8081 *:*\n"
            "LISTEN 0 128 10.8.0.5:8083 *:*\n", encoding="utf-8")
        r = self.run_ok(env_extra={"STUB_LISTENERS_FILE": str(listeners)})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("vpn-bind failed", r.stderr)
        log = self.log_text()
        self.assertIn("up -d", log)
        self.assertNotIn(" stop", log)
        self.assertNotIn("down", log)

    def test_no_restore_or_down_migration_is_ever_run(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        for banned in ("restore", "down", "migrate", "--verify"):
            self.assertNotIn(banned, log)

    # -- no value ever printed ----------------------------------------------

    def test_the_service_key_value_is_never_printed(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn(KEY, r.stdout + r.stderr + self.log_text())

    def test_no_environment_value_is_printed_on_the_happy_path(self):
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout + r.stderr + self.log_text()
        # ruling 2(a): the service key value (the key file content is the same)
        # never reaches stdout, stderr or a recorded argv.
        self.assertNotIn(KEY, out)
        # ruling 2(b): the forwarder URL never prints (a URL can carry credentials).
        self.assertNotIn(FORWARDER_URL, out)
        # ruling 2(d): the bind address, the ports and the three health URLs
        # built from them MAY print.
        for url in ("http://10.8.0.5:8081/health",
                    "http://10.8.0.5:8083/health",
                    "http://10.8.0.5:8084/health"):
            self.assertIn(url, r.stdout)


    # -- D6: the staging parent is created when absent ----------------------

    def test_the_staging_parent_is_created_when_absent(self):
        # D6: rollback checked deploy_dir_writable(STAGING_PARENT) but never
        # CREATED it, so a first run into a fresh tree failed preflight. It is
        # created the way install.sh:187-192 and update.sh:202-207 do, and the
        # checkout lands inside it.
        parent = self.tmp / "fresh-parent"
        self.assertFalse(parent.exists())
        r = self.invoke(self.base_args(staging=parent / "staging"))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(parent.is_dir(), parent)
        self.assertIn("clone", self.log_text())

    # -- D7: --keep plumbing -----------------------------------------------

    def test_keep_reaches_the_backup_call_as_exactly_keep_n(self):
        # D7: --keep N is forwarded to backup-data.py as one argv pair, so a
        # value is never concatenated into the host-path arguments.
        r = self.run_ok(["--keep", "3"])
        self.assertEqual(r.returncode, 0, r.stderr)
        argv = self.backup_argv_lines()
        self.assertEqual(len(argv), 1, argv)
        line = argv[0]
        self.assertIn(" --keep 3", line, line)
        self.assertRegex(
            line,
            r"backup-data\.py --out \S+ --sync-db \S+ --whisper-db \S+ --keep 3$",
            line)

    def test_the_default_keep_is_five(self):
        # D7: with no --keep the backup call still carries the default, so the
        # retention contract is explicit rather than implicit.
        r = self.run_ok()
        self.assertEqual(r.returncode, 0, r.stderr)
        line = self.backup_argv_lines()[0]
        self.assertIn(" --keep 5", line, line)

    def test_a_non_integer_keep_is_refused_with_exit_two(self):
        # D7: a value backup-data.py would reject as a usage error must be
        # refused HERE, before any pull or build, with the usage code.
        for bad in ("abc", "1.5", "", " 3", "0x3"):
            with self.subTest(keep=bad):
                r = self.run_ok(["--keep", bad])
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertIn("--keep", r.stderr)
                self.assertEqual(self.log_text(), "")

    def test_a_non_positive_keep_is_refused_with_exit_two(self):
        # D7: 0 and negatives are refused with the usage code, before the pull.
        for bad in ("0", "-1"):
            with self.subTest(keep=bad):
                r = self.run_ok(["--keep", bad])
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertIn("--keep", r.stderr)
                self.assertEqual(self.log_text(), "")

    def test_keep_without_a_value_is_refused_with_exit_two(self):
        r = self.run_ok(["--keep"])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--keep", r.stderr)
        self.assertEqual(self.log_text(), "")

    # -- D8: the shared compose-version guard ------------------------------

    def test_a_major_five_compose_answer_is_accepted(self):
        # D8: the shared deploy_compose_version_ok compares the MAJOR
        # numerically, so v5 is accepted (the copied guard's v2*|2* only
        # accepted a literal leading 2).
        r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": "Docker Compose version v5.6.0"})
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("up -d", self.log_text())

    def test_a_major_one_compose_answer_is_refused_before_the_pull(self):
        r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": "Docker Compose version v1.29.2"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("compose", r.stderr)
        self.assertNotIn("ls-remote", self.log_text())

    def test_an_empty_compose_answer_is_refused_before_the_pull(self):
        r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": ""})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("compose", r.stderr)
        self.assertNotIn("ls-remote", self.log_text())

    def test_a_garbage_compose_answer_is_refused_before_the_pull(self):
        r = self.run_ok(env_extra={"STUB_COMPOSE_VERSION": "garbage"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("compose", r.stderr)
        self.assertNotIn("ls-remote", self.log_text())

    def test_a_failing_docker_compose_stays_a_hard_failure(self):
        # The caller keeps its own hard arm: a docker that fails is
        # "not available", not a version verdict, and the pull never runs.
        r = self.run_ok(env_extra={"STUB_COMPOSE_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("not available", r.stderr)
        self.assertNotIn("ls-remote", self.log_text())


if __name__ == "__main__":
    unittest.main()
