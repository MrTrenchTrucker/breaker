"""Unit tests for scripts/install.sh.

Drives the REAL script through a temp PATH of stubs (git, gradlew, docker,
curl, chown, stat, python3, ss) and a temp checkout, so no real docker, git
remote, host path or network is touched. Every stub records its argv; the
tests assert the step order, the exit codes, the conditional backup arm and
that the service key value appears in no argv, no stdout/stderr line and no
log line.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_install.py'
"""
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = REPO_ROOT / "server" / "modules" / "deploy" / "scripts"
INSTALL = Path(os.environ.get("INSTALL_SRC", str(SCRIPTS / "install.sh")))
COMPOSE = REPO_ROOT / "server" / "modules" / "deploy" / "docker-compose.yml"
ENV_EXAMPLE = REPO_ROOT / "server" / "modules" / "deploy" / ".env.example"
# A file the web-fe image COPYs out of the checkout (Dockerfile:
# `COPY server/modules/deploy/images/web-fe/nginx.conf.template ...`), used by
# the D5 umask-scope test.
NGINX_TEMPLATE = (REPO_ROOT / "server" / "modules" / "deploy" / "images"
                  / "web-fe" / "nginx.conf.template")

# 43 URL-safe characters; the fake python3 generator writes exactly these 43
# bytes (no newline) into the temp file the script validates.
FAKE_KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"
PLACEHOLDER = "test-value"
BIND = "10.8.0.5"
# ruling 2(b): a URL can carry credentials, so it is never printed either.
FORWARDER_URL = "http://10.20.30.12:9000"
REMOTE = "git@example.invalid/breaker.git"

# backup-data.py's EXPECTED_TABLES["sync-api"]: the real tool marks a copy
# FAILED (exit 1) when a table is missing, so the fixture databases must
# really carry them.
SYNC_TABLES = (
    "accounts", "tokens", "transcriptions", "server_settings",
    "signin_attempts", "signin_handoff", "reset_codes", "pending_sealed",
)
BACKUP_DATA = SCRIPTS / "backup-data.py"

STUB_GIT = """#!/bin/sh
printf 'git %s\\n' "$*" >> "$STUB_LOG"
case " $* " in
    *" ls-remote "*)
        cat "$STUB_TAGS_FILE"
        exit "${STUB_GIT_LS_RC:-0}"
        ;;
    *" clone "*)
        [ "${STUB_GIT_CLONE_RC:-0}" = 0 ] || exit "$STUB_GIT_CLONE_RC"
        dest=""
        for a in "$@"; do dest=$a; done
        mkdir -p "$dest"
        cp -R "$STUB_CLONE_SRC/." "$dest/"
        exit 0
        ;;
    *" checkout "*)
        exit "${STUB_GIT_CHECKOUT_RC:-0}"
        ;;
esac
exit 0
"""

STUB_DOCKER = """#!/bin/sh
printf 'docker %s\\n' "$*" >> "$STUB_LOG"
case " $* " in
    *" compose version "*)
        # `-` not `:-`: an explicitly EMPTY version must reach the script as
        # empty, which is a case the compose guard has to refuse.
        printf '%s\n' "${STUB_COMPOSE_VERSION-Docker Compose version v2.20.0}"
        exit "${STUB_COMPOSE_RC:-0}"
        ;;
    *" buildx version "*)
        exit "${STUB_BUILDX_RC:-0}"
        ;;
    *" volume ls --format {{.Name}} "*)
        awk '{ print $1 }' "$STUB_VOLUMES_FILE" 2>/dev/null
        exit "${STUB_VOLUME_LS_RC:-0}"
        ;;
    *" volume inspect -f {{.Mountpoint}} "*)
        shift 4
        _sd_name=$1
        _sd_mount=$(awk -v n="$_sd_name" '$1 == n { $1 = ""; sub(/^ /, ""); print; exit }' \
            "$STUB_MOUNTS_FILE" 2>/dev/null)
        [ -n "$_sd_mount" ] || exit 1
        printf '%s\n' "$_sd_mount"
        exit 0
        ;;
    *" up -d "*)
        printf 'env BREAKER_IMAGE_TAG=%s\\n' "${BREAKER_IMAGE_TAG:-}" >> "$STUB_LOG"
        exit "${STUB_DOCKER_UP_RC:-0}"
        ;;
    *" build "*)
        printf 'env BREAKER_IMAGE_TAG=%s\\n' "${BREAKER_IMAGE_TAG:-}" >> "$STUB_LOG"
        exit "${STUB_DOCKER_BUILD_RC:-0}"
        ;;
esac
exit 0
"""

STUB_CURL = """#!/bin/sh
printf 'curl %s\\n' "$*" >> "$STUB_LOG"
printf '{"status":"ok"} 200\\n'
exit "${STUB_CURL_RC:-0}"
"""

STUB_SS = """#!/bin/sh
printf 'ss %s\\n' "$*" >> "$STUB_LOG"
cat "$STUB_LISTENERS_FILE"
"""

STUB_CHOWN = """#!/bin/sh
printf 'chown %s\\n' "$*" >> "$STUB_LOG"
exit "${STUB_CHOWN_RC:-0}"
"""

STUB_STAT = """#!/bin/sh
printf 'stat %s\\n' "$*" >> "$STUB_LOG"
case " $* " in
    *" -c %u "*)
        printf '%s\\n' "${STUB_STAT_UID:-10001}"
        exit "${STUB_STAT_UID_RC:-0}"
        ;;
esac
exec "@REAL_STAT@" "$@"
"""

STUB_PYTHON3 = """#!/bin/sh
printf 'python3 %s\\n' "$*" >> "$STUB_LOG"
case " $* " in
    *" - "*)
        # the service-key generator: python3 - TMP <<'PY'
        printf '%s' "${STUB_KEYGEN_VALUE:-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq}" > "$2"
        exit "${STUB_KEYGEN_RC:-0}"
        ;;
esac
case "${1:-}" in
    */backup-data.py)
        # The --out call IS the backup: with STUB_BACKUP_REAL=1 it execs the REAL
        # backup-data.py, so install.sh's own call shape is what the tool judges
        # (a wrong shape is the tool's exit 2, not a stub's 0). --has-data and
        # --record-schema are judged the same way, and their argv is captured
        # one argument per line either way: the $* log above joins arguments
        # with spaces, so it cannot tell a space INSIDE a host path from an
        # argument separator.
        _sba_mode=""
        case " $* " in
            *" --has-data "*) _sba_mode=--has-data ;;
            *" --record-schema "*) _sba_mode=--record-schema ;;
        esac
        if [ -n "$_sba_mode" ] && [ -n "${STUB_TOOL_ARGV_FILE:-}" ]; then
            for _sba_arg in "$@"; do printf '%s\\n' "$_sba_arg" >> "$STUB_TOOL_ARGV_FILE"; done
        fi
        if [ -n "$_sba_mode" ] && [ "${STUB_BACKUP_REAL:-0}" = 1 ]; then
            if [ "$_sba_mode" = --record-schema ] && [ -n "${STUB_SCHEMA_SNAPSHOT:-}" ]; then
                # Snapshot the record file mid-run: install.sh's EXIT trap
                # removes it before the test can read it (round 3 defect 6).
                _sba_out=""
                _sba_prev=""
                for _sba_arg in "$@"; do
                    if [ "$_sba_prev" = "--record-schema" ]; then _sba_out=$_sba_arg; fi
                    _sba_prev=$_sba_arg
                done
                "@REAL_PYTHON3@" "$@" || exit $?
                cp "$_sba_out" "$STUB_SCHEMA_SNAPSHOT" || exit 1
                exit 0
            fi
            exec "@REAL_PYTHON3@" "$@"
        fi
        case " $* " in
            *" --out "*)
                if [ "${STUB_BACKUP_REAL:-0}" = 1 ]; then
                    # Record the argv one argument per line. The $*
                    # log above joins arguments with spaces, so it cannot tell a
                    # space INSIDE a host path from an argument separator; this
                    # file can, and the real tool below judges the same argv.
                    if [ -n "${STUB_BACKUP_ARGV_FILE:-}" ]; then
                        : > "$STUB_BACKUP_ARGV_FILE"
                        for _sba_arg in "$@"; do
                            printf '%s\n' "$_sba_arg" >> "$STUB_BACKUP_ARGV_FILE"
                        done
                    fi
                    exec "@REAL_PYTHON3@" "$@"
                fi
                ;;
        esac
        ;;
esac
case " $* " in
    *" --has-data "*)
        exit "${STUB_HAS_DATA_RC:-0}"
        ;;
    *" --out "*)
        exit "${STUB_BACKUP_RC:-0}"
        ;;
    *" --record-schema "*)
        exit "${STUB_RECORD_SCHEMA_RC:-0}"
        ;;
esac
exit "${STUB_PYTHON3_RC:-0}"
"""

STUB_TOOL = """#!/bin/sh
printf '%s\\n' "$0 $*" >> "$STUB_LOG"
exit 0
"""

STUB_GRADLEW = """#!/bin/sh
printf 'gradlew %s\\n' "$*" >> "$STUB_LOG"
exit "${STUB_GRADLEW_RC:-0}"
"""


def env_example_names():
    """The NAME= names of the real .env.example, in file order."""
    names = []
    for raw in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        names.append(line.split("=", 1)[0].strip())
    return names


def env_value(path, name):
    """The last NAME= value in an env file (names only are printed elsewhere)."""
    value = ""
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        if raw.startswith(name + "="):
            value = raw.split("=", 1)[1]
    return value


class InstallTest(unittest.TestCase):
    """Drives the real install.sh through stubs on a temp PATH."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-install-test-"))
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.run_dir = self.tmp / "run"
        self.run_dir.mkdir()
        shutil.copyfile(INSTALL, self.run_dir / "install.sh")
        os.chmod(str(self.run_dir / "install.sh"), 0o755)
        for name in ("deploy-common.sh", "git-pull.sh", "release-tags.sh",
                     "health-check.sh", "vpn-bind.sh", "backup-data.py"):
            os.symlink(str(SCRIPTS / name), str(self.run_dir / name))
        self.install = self.run_dir / "install.sh"
        self._write(self.bin / "git", STUB_GIT)
        self._write(self.bin / "docker", STUB_DOCKER)
        self._write(self.bin / "curl", STUB_CURL)
        self._write(self.bin / "ss", STUB_SS)
        self._write(self.bin / "chown", STUB_CHOWN)
        self._write(self.bin / "python3",
                    STUB_PYTHON3.replace("@REAL_PYTHON3@", str(shutil.which("python3"))))
        self._write(self.bin / "sqlite3", STUB_TOOL)
        for tool in ("sh", "dirname", "sed", "awk", "sort", "grep",
                     "tail", "cat", "basename", "cut", "tr", "wc", "cp",
                     "mv", "rm", "chmod", "mkdir", "date", "id"):
            source = shutil.which(tool)
            if source:
                os.symlink(source, str(self.bin / tool))
        # stat is stubbed for the owner read (`-c %u`); every other read is
        # delegated to the real binary so mode checks stay honest.
        self.real_stat = shutil.which("stat")
        self._write(self.bin / "stat",
                    STUB_STAT.replace("@REAL_STAT@", str(self.real_stat)))

        self.fixture = self.tmp / "clone-src"
        (self.fixture / "server" / "modules" / "deploy").mkdir(parents=True)
        shutil.copyfile(COMPOSE, self.fixture / "server" / "modules" / "deploy" / "docker-compose.yml")
        shutil.copyfile(ENV_EXAMPLE, self.fixture / "server" / "modules" / "deploy" / ".env.example")
        self._write(self.fixture / "gradlew", STUB_GRADLEW)

        self.tags = self.tmp / "tags.txt"
        self.tags.write_text("0" * 40 + "\trefs/tags/v1.2.0\n" +
                             "0" * 40 + "\trefs/tags/v1.10.0\n", encoding="utf-8")

        self.listeners = self.tmp / "listeners.txt"
        self.write_listeners(BIND)

        self.mounts = self.tmp / "mounts"
        self.volumes = self.tmp / "volumes"
        self.write_mounts()

        self.env_file = self.tmp / "stack.env"
        self.key_file = self.tmp / "keys" / "sync-api-service-key"
        self.default_key = self.tmp / "default-keys" / "sync-api-service-key"
        self.staging = self.tmp / "staging"
        self.log = self.tmp / "stub.log"

        self.key_file.parent.mkdir(parents=True, exist_ok=True)
        self.key_file.write_text(FAKE_KEY + "\n", encoding="utf-8")
        os.chmod(str(self.key_file), 0o600)
        self.write_env(whisper=FAKE_KEY)

        self.env = dict(os.environ)
        self.env.update({
            "PATH": str(self.bin),
            "STUB_LOG": str(self.log),
            "STUB_CLONE_SRC": str(self.fixture),
            "STUB_TAGS_FILE": str(self.tags),
            "STUB_LISTENERS_FILE": str(self.listeners),
            "STUB_MOUNTS_FILE": str(self.mounts),
            "STUB_VOLUMES_FILE": str(self.volumes),
            "HEALTH_STEP": "0",
            "HEALTH_WAIT": "2",
            "BREAKER_KEY_HOST_DEFAULT": str(self.default_key),
        })

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def _write(self, path, text):
        path.write_text(text, encoding="utf-8")
        os.chmod(str(path), 0o755)

    def write_listeners(self, addr):
        rows = ["LISTEN 0 128 %s:%s *:*" % (addr, port)
                for port in ("8081", "8083", "8084")]
        self.listeners.write_text("\n".join(rows) + "\n", encoding="utf-8")

    def write_mounts(self, pairs=None, names=None):
        """The docker volume-inspect table (name -> mountpoint) and, separately,
        the names `docker volume ls` answers with (wider, so a listed volume
        can be one whose inspect fails)."""
        if pairs is None:
            pairs = (("breaker_breaker_sync_api_data", "/srv/docker/sync"),
                     ("breaker_breaker_whisper_data", "/srv/docker/whisper"))
        self.mounts.write_text("".join("%s %s\n" % (name, mount)
                                       for name, mount in pairs),
                               encoding="utf-8")
        if names is None:
            names = [name for name, _mount in pairs]
        self.volumes.write_text("".join("%s\n" % name for name in names),
                                encoding="utf-8")

    def _current_value(self, path, name, default):
        if not path.exists():
            return default
        return env_value(path, name)

    def write_env(self, path=None, overrides=None, mode=0o600, host=None, whisper=None):
        """Write an env file; the two secret lines keep their current values
        unless they are passed explicitly (pass "" to blank one)."""
        path = self.env_file if path is None else path
        values = {name: PLACEHOLDER for name in env_example_names()}
        values.update({
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
        })
        if host is None:
            host = self._current_value(path, "BREAKER_SYNC_API_SERVICE_KEY_HOST",
                                       str(self.key_file))
        if whisper is None:
            whisper = self._current_value(path, "BREAKER_WHISPER_SYNC_API_SERVICE_KEY",
                                          FAKE_KEY)
        values["BREAKER_SYNC_API_SERVICE_KEY_HOST"] = host
        values["BREAKER_WHISPER_SYNC_API_SERVICE_KEY"] = whisper
        values.update(overrides or {})
        path.write_text("".join("%s=%s\n" % (name, values.get(name, PLACEHOLDER))
                                for name in env_example_names()), encoding="utf-8")
        os.chmod(str(path), mode)

    def run_install(self, args=None, extra=None):
        env = dict(self.env)
        env.update(extra or {})
        staging = self.staging
        listed = list(args or [])
        for index, arg in enumerate(listed):
            if arg == "--staging" and index + 1 < len(listed):
                staging = Path(listed[index + 1])
        if staging.exists():
            shutil.rmtree(str(staging), ignore_errors=True)
        self.log.write_text("", encoding="utf-8")
        return subprocess.run(["sh", "-eu", str(self.install)] + listed,
                              capture_output=True, text=True, env=env,
                              cwd=str(self.tmp), timeout=60)

    def run_default(self, extra=None, args=None):
        args = list(args) if args is not None else [
            "--remote", REMOTE,
            "--env-file", str(self.env_file),
            "--staging", str(self.staging),
        ]
        return self.run_install(args, extra=extra)

    def log_text(self):
        return self.log.read_text(encoding="utf-8") if self.log.exists() else ""

    def log_lines(self):
        return [line for line in self.log_text().splitlines() if line]

    def index_of(self, needle):
        for index, line in enumerate(self.log_lines()):
            if needle in line:
                return index
        return -1

    def python3_calls(self, needle):
        """The argv of every stub python3 call whose argv contains needle."""
        prefix = "python3 "
        return [line[len(prefix):] for line in self.log_lines()
                if line.startswith(prefix) and needle in line]

    def tool_argv_blocks(self, path):
        """Parse STUB_TOOL_ARGV_FILE: one block per captured backup-data.py
        call, starting at its script path, one argument per line (so a space
        inside a host path is visible as one line)."""
        blocks = []
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.endswith("backup-data.py"):
                blocks.append([line])
            elif blocks:
                blocks[-1].append(line)
        return blocks

    def make_db(self, path, tables):
        """A real SQLite database carrying TABLES (the tool checks them)."""
        path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(path))
        for table in tables:
            conn.execute("CREATE TABLE IF NOT EXISTS %s (id INTEGER PRIMARY KEY)" % table)
        conn.commit()
        conn.close()
        return path

    def run_backup_data(self, *args):
        """Run the REAL backup-data.py, never the stub on the temp PATH."""
        env = dict(os.environ)
        env.pop("BREAKER_SYNC_API_DB", None)
        env.pop("BREAKER_WHISPER_DB", None)
        env.pop("BREAKER_BACKUP_NOW", None)
        return subprocess.run([sys.executable, str(BACKUP_DATA), *args],
                              capture_output=True, text=True, env=env, timeout=60)

    # -- usage ---------------------------------------------------------------

    def test_usage_unknown_option_exits_2(self):
        r = self.run_default(args=["--remote", REMOTE, "--bogus"])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--bogus", r.stderr)
        self.assertEqual(r.stdout, "")

    def test_usage_missing_value_exits_2(self):
        for args in (["--remote", REMOTE, "--tag"],
                     ["--remote", REMOTE, "--env-file"],
                     ["--remote", REMOTE, "--staging"],
                     ["--remote", REMOTE, "--keep"]):
            with self.subTest(args=args):
                r = self.run_install(args)
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertEqual(r.stdout, "")

    def test_usage_missing_remote_exits_2(self):
        r = self.run_install([])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--remote", r.stderr)

    # -- the accepting control and the step order ----------------------------

    def test_success_runs_every_step_in_order(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        keys = []
        for line in self.log_lines():
            if line.startswith("git ") and "ls-remote" in line:
                key = "tag"
            elif line.startswith("git ") and "clone" in line:
                key = "pull"
            elif line.startswith("git ") and "checkout" in line:
                key = "checkout"
            elif line.startswith("gradlew"):
                key = "build"
            elif line.startswith("docker ") and line.split()[-1] in ("build", "--no-cache"):
                key = "image"
            elif line.startswith("docker ") and "up" in line.split() and "-d" in line.split():
                key = "swap"
            elif line.startswith("curl"):
                key = "health"
            elif line.startswith("ss"):
                key = "vpn-bind"
            else:
                continue
            if not keys or keys[-1] != key:
                keys.append(key)
        self.assertEqual(keys, ["tag", "pull", "checkout", "build", "image",
                                "swap", "health", "vpn-bind"])

    def test_health_runs_after_the_swap(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertGreater(self.index_of("curl"), self.index_of("up -d"))

    def test_default_tag_is_resolved_and_exported(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("env BREAKER_IMAGE_TAG=v1.10.0", self.log_text())

    def test_named_tag_reaches_the_pull(self):
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--tag", "v1.2.0"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("checkout -q --detach refs/tags/v1.2.0", self.log_text())

    def test_the_image_build_carries_no_cache(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        builds = [line for line in self.log_lines()
                  if line.startswith("docker ") and "build" in line.split()]
        self.assertEqual(builds, [
            "docker compose --env-file %s -p breaker -f %s build --no-cache"
            % (self.env_file,
               self.staging / "server" / "modules" / "deploy" / "docker-compose.yml")],
            self.log_text())

    def test_the_host_build_names_all_three_modules(self):
        # D11: web-fe's Dockerfile COPYs build/web/js/vendor, which only
        # :server:modules:web-fe:assemble produces, so the host build command
        # must name all three modules - not just the two installDist trees.
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        builds = [line for line in self.log_lines() if line.startswith("gradlew ")]
        self.assertEqual(builds, [
            "gradlew :server:modules:sync-api:installDist"
            " :server:modules:whisper-server:installDist"
            " :server:modules:web-fe:assemble"], self.log_text())

    def test_the_pull_checkout_keeps_a_file_the_image_copies_readable(self):
        # The web-fe image COPYs this template out of the checkout, so the
        # pull runs under the FORCED 022, never a global `umask 077`: a 077
        # checkout makes the copy 0600 and the image carries a file the
        # container's account cannot read.
        src = (self.fixture / "server" / "modules" / "deploy" / "images"
               / "web-fe" / "nginx.conf.template")
        src.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(NGINX_TEMPLATE, str(src))
        os.chmod(str(src), 0o644)
        previous = os.umask(0o022)
        try:
            r = self.run_default()
        finally:
            os.umask(previous)
        self.assertEqual(r.returncode, 0, r.stderr)
        staged = (self.staging / "server" / "modules" / "deploy" / "images"
                  / "web-fe" / "nginx.conf.template")
        self.assertTrue(staged.is_file(), r.stdout + r.stderr)
        self.assertEqual(os.stat(str(staged)).st_mode & 0o777, 0o644,
                         "the checkout copied mode %o"
                         % (os.stat(str(staged)).st_mode & 0o777))

    def test_the_host_build_runs_under_the_callers_umask(self):
        # Control for the 077-caller test below: with a 022 caller the env/key
        # section still tightens to 077 and the restore before the host build
        # puts the gradle outputs (the installDist trees the images COPY) back
        # at their normal modes. The stub records the umask it is invoked
        # under and creates one file, so the mode and the recorded value are
        # both checked.
        probe = self.staging / "build" / "umask-probe.txt"
        umask_file = self.tmp / "umask.txt"
        self._write(self.fixture / "gradlew", (
            "#!/bin/sh\n"
            "printf 'gradlew %s\\n' \"$*\" >> \"$STUB_LOG\"\n"
            "printf '%s\\n' \"$(umask)\" >> \"$STUB_UMASK_FILE\"\n"
            "mkdir -p \"$(dirname \"$STUB_PROBE_FILE\")\"\n"
            ": > \"$STUB_PROBE_FILE\"\n"
            "exit \"${STUB_GRADLEW_RC:-0}\"\n"))
        previous = os.umask(0o022)
        try:
            r = self.run_default(extra={"STUB_UMASK_FILE": str(umask_file),
                                        "STUB_PROBE_FILE": str(probe)})
        finally:
            os.umask(previous)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(probe.is_file(), r.stdout + r.stderr)
        mode = os.stat(str(probe)).st_mode & 0o777
        recorded = umask_file.read_text(encoding="utf-8").split()
        self.assertEqual(mode, 0o644,
                         "the build created mode %o under umask %s"
                         % (mode, recorded))
        self.assertEqual(recorded, ["0022"],
                         "the build ran under umask %s" % recorded)

    def _write_mode_gradlew(self, umask_file, mode_file):
        """A gradlew stub that records BOTH the umask it runs under and the
        mode of the probe file it creates, so a test can pin the checkout and
        build modes independently of the caller's umask."""
        self._write(self.fixture / "gradlew", (
            "#!/bin/sh\n"
            "printf 'gradlew %s\\n' \"$*\" >> \"$STUB_LOG\"\n"
            "printf '%s\\n' \"$(umask)\" >> \"$STUB_UMASK_FILE\"\n"
            "mkdir -p \"$(dirname \"$STUB_PROBE_FILE\")\"\n"
            ": > \"$STUB_PROBE_FILE\"\n"
            "printf '%s\\n' \"$(stat -c '%a' \"$STUB_PROBE_FILE\")\""
            " >> \"$STUB_MODE_FILE\"\n"
            "exit \"${STUB_GRADLEW_RC:-0}\"\n"))

    def test_the_checkout_and_the_build_are_022_under_a_077_caller(self):
        # D5: a root shell with umask 077 is the real use. The checkout and the
        # builds must run under a FORCED 022, never the caller's 077: the
        # web-fe image COPYs nginx.conf.template out of the checkout and the
        # gradle outputs out of the build tree, and uid 10001 cannot read a
        # root-owned 0600 file. The stub records the umask and the probe mode.
        src = (self.fixture / "server" / "modules" / "deploy" / "images"
               / "web-fe" / "nginx.conf.template")
        src.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(NGINX_TEMPLATE, str(src))
        os.chmod(str(src), 0o644)
        probe = self.staging / "build" / "umask-probe.txt"
        umask_file = self.tmp / "umask.txt"
        mode_file = self.tmp / "mode.txt"
        self._write_mode_gradlew(umask_file, mode_file)
        previous = os.umask(0o077)
        try:
            r = self.run_default(extra={"STUB_UMASK_FILE": str(umask_file),
                                        "STUB_PROBE_FILE": str(probe),
                                        "STUB_MODE_FILE": str(mode_file)})
        finally:
            os.umask(previous)
        self.assertEqual(r.returncode, 0, r.stderr)
        staged = (self.staging / "server" / "modules" / "deploy" / "images"
                  / "web-fe" / "nginx.conf.template")
        self.assertTrue(staged.is_file(), r.stdout + r.stderr)
        self.assertEqual(os.stat(str(staged)).st_mode & 0o777, 0o644,
                         "the checkout copied mode %o"
                         % (os.stat(str(staged)).st_mode & 0o777))
        self.assertTrue(probe.is_file(), r.stdout + r.stderr)
        self.assertEqual(os.stat(str(probe)).st_mode & 0o777, 0o644,
                         "the build created mode %o"
                         % (os.stat(str(probe)).st_mode & 0o777))
        self.assertEqual(umask_file.read_text(encoding="utf-8").split(),
                         ["0022"], "the build ran under the caller's umask")
        self.assertEqual(mode_file.read_text(encoding="utf-8").split(),
                         ["644"], "the stub recorded the probe mode")

    # -- the env file ---------------------------------------------------------

    def test_env_absent_is_created_0600_from_the_example(self):
        self.env_file.unlink()
        r = self.run_default()
        self.assertTrue(self.env_file.is_file(), r.stderr)
        self.assertEqual(os.stat(self.env_file).st_mode & 0o777, 0o600)
        example = [line for line in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines()
                   if not line.startswith("BREAKER_SYNC_API_SERVICE_KEY_HOST")
                   and not line.startswith("BREAKER_WHISPER_SYNC_API_SERVICE_KEY")]
        created = [line for line in self.env_file.read_text(encoding="utf-8").splitlines()
                   if not line.startswith("BREAKER_SYNC_API_SERVICE_KEY_HOST")
                   and not line.startswith("BREAKER_WHISPER_SYNC_API_SERVICE_KEY")]
        self.assertEqual(created, example)
        self.assertEqual(env_value(self.env_file, "BREAKER_SYNC_API_SERVICE_KEY_HOST"),
                         str(self.default_key))
        self.assertEqual(env_value(self.env_file, "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"),
                         FAKE_KEY)

    def test_env_present_but_not_0600_exits_1(self):
        os.chmod(str(self.env_file), 0o644)
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("0600", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_env_values_are_never_echoed(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout + r.stderr + self.log_text()
        # ruling 2(a): the key value (the key file content is the same) and the
        # other env values never reach stdout, stderr or a recorded argv.
        for value in (FAKE_KEY, PLACEHOLDER):
            self.assertNotIn(value, out)
        # ruling 2(b): the forwarder URL never prints (a URL can carry credentials).
        self.assertNotIn(FORWARDER_URL, out)
        # ruling 2(d): the bind address, the ports and the three health URLs
        # built from them MAY print.
        for url in ("http://%s:8081/health" % BIND,
                    "http://%s:8083/health" % BIND,
                    "http://%s:8084/health" % BIND):
            self.assertIn(url, r.stdout)

    # -- preflight part 2 -----------------------------------------------------

    def test_a_required_name_empty_exits_1(self):
        self.write_env(overrides={"BREAKER_WHISPER_PORT": ""})
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_WHISPER_PORT", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_bind_empty_wildcard_and_public_are_refused(self):
        for addr in ("", "0.0.0.0", "::", "[::]", "8.8.8.8", "2001:db8::1"):
            with self.subTest(addr=addr):
                self.write_env(overrides={"BREAKER_BIND_ADDR": addr})
                r = self.run_default()
                self.assertEqual(r.returncode, 1, r.stderr)
                self.assertIn("BREAKER_BIND_ADDR", r.stderr)
                self.assertNotIn("up -d", self.log_text())

    def test_an_existing_env_with_a_public_bind_is_refused_before_the_pull(self):
        # preflight part 1 (e): the early bind check fires before the pull, so
        # no git ls-remote/clone call is recorded.
        self.write_env(overrides={"BREAKER_BIND_ADDR": "8.8.8.8"})
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("BREAKER_BIND_ADDR", r.stderr)
        self.assertIn("public", r.stderr)
        self.assertNotIn("8.8.8.8", r.stderr)
        log = self.log_text()
        # the tag-resolution ls-remote may appear; the PULL (clone/checkout) may not
        self.assertNotIn("clone", log)
        self.assertNotIn("checkout", log)
        self.assertNotIn("up -d", log)

    def test_compose_v1_is_refused(self):
        r = self.run_default(extra={"STUB_COMPOSE_VERSION": "Docker Compose version v1.29.2"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_compose_v5_is_accepted(self):
        # deploy_compose_version_ok compares the MAJOR numerically, so v5 is as
        # valid as v2; install.sh's copied `v2*|2*` case refused it.
        r = self.run_default(extra={"STUB_COMPOSE_VERSION": "Docker Compose version v5.6.0"})
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("up -d", self.log_text())

    def test_an_empty_compose_version_is_refused(self):
        r = self.run_default(extra={"STUB_COMPOSE_VERSION": ""})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("not v2", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_garbage_compose_version_is_refused(self):
        r = self.run_default(extra={"STUB_COMPOSE_VERSION": "not a version"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("not v2", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_failing_docker_compose_version_is_a_hard_failure(self):
        # The caller runs `docker compose version` itself, so a failing docker
        # stays the "not available" hard failure, distinct from "not v2".
        r = self.run_default(extra={"STUB_COMPOSE_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("not available", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_missing_tool_exits_1(self):
        os.remove(str(self.bin / "docker"))
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("required tool missing: docker", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_missing_sqlite3_cli_is_accepted(self):
        # Nothing invokes the sqlite3 CLI: every schema read goes through
        # python3's module, so its absence must not fail preflight.
        os.remove(str(self.bin / "sqlite3"))
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("required tool missing: sqlite3", r.stderr)

    def test_a_python3_that_cannot_import_sqlite3_is_refused_at_preflight_before_any_build(self):
        # The redundant early probe is gone: the module check now lives only in
        # deploy_preflight, which runs after the pull/env/key steps and before
        # the host build. A broken python3 is still refused before any stack
        # change, so the pull is no longer blocked by it.
        r = self.run_default(extra={"STUB_PYTHON3_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("python3", r.stderr)
        self.assertIn("sqlite3", r.stderr)
        log = self.log_text()
        check = log.find("python3 -c import sqlite3")
        self.assertGreater(check, -1, log)
        # the check fired at preflight, i.e. AFTER the pull ...
        self.assertIn("clone", log)
        self.assertGreater(check, log.find("clone"), log)
        # ... and BEFORE any host build, image build or swap.
        for banned in ("gradlew", "build\n", "up -d"):
            self.assertNotIn(banned, log)

    def test_a_checkout_without_an_executable_gradlew_is_refused_before_the_build(self):
        # gradlew is a property of the pulled tag, so the build step checks it
        # in the checkout, before any gradle invocation.
        os.chmod(str(self.fixture / "gradlew"), 0o644)
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn(str(self.staging / "gradlew"), r.stderr)
        self.assertNotIn("gradlew", self.log_text())

    def test_an_unwritable_staging_parent_exits_1(self):
        parent = self.tmp / "ro"
        parent.mkdir()
        os.chmod(str(parent), 0o555)
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(parent / "staging")])
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("STAGING_PARENT", r.stderr)

    # -- the service key ------------------------------------------------------

    def test_key_absent_is_created_and_written_to_the_whisper_line(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        data = self.key_file.read_text(encoding="utf-8")
        self.assertEqual(data.strip(), FAKE_KEY)
        self.assertEqual(len(data.strip()), 43)
        self.assertEqual(os.stat(self.key_file).st_mode & 0o777, 0o600)
        self.assertIn("chown 10001 " + str(self.key_file), self.log_text())
        self.assertEqual(env_value(self.env_file, "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"),
                         FAKE_KEY)

    def test_key_present_valid_is_unchanged(self):
        before_key = self.key_file.read_text(encoding="utf-8")
        before_env = self.env_file.read_text(encoding="utf-8")
        before_stat = os.stat(self.key_file)
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.key_file.read_text(encoding="utf-8"), before_key)
        self.assertEqual(self.env_file.read_text(encoding="utf-8"), before_env)
        self.assertEqual(os.stat(self.key_file).st_mode, before_stat.st_mode)
        self.assertEqual(os.stat(self.key_file).st_mtime, before_stat.st_mtime)

    def test_key_wrong_mode_changes_nothing(self):
        os.chmod(str(self.key_file), 0o644)
        before_key = self.key_file.read_text(encoding="utf-8")
        before_env = self.env_file.read_text(encoding="utf-8")
        r = self.run_default()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.key_file.read_text(encoding="utf-8"), before_key)
        self.assertEqual(self.env_file.read_text(encoding="utf-8"), before_env)

    def test_key_wrong_length_changes_nothing(self):
        short = "A" * 42
        self.key_file.write_text(short + "\n", encoding="utf-8")
        os.chmod(str(self.key_file), 0o600)
        self.write_env(whisper=short)
        before_key = self.key_file.read_text(encoding="utf-8")
        before_env = self.env_file.read_text(encoding="utf-8")
        r = self.run_default()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.key_file.read_text(encoding="utf-8"), before_key)
        self.assertEqual(self.env_file.read_text(encoding="utf-8"), before_env)

    def test_key_mismatch_changes_nothing(self):
        self.write_env(whisper="B" * 43)
        before_key = self.key_file.read_text(encoding="utf-8")
        before_env = self.env_file.read_text(encoding="utf-8")
        r = self.run_default()
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.key_file.read_text(encoding="utf-8"), before_key)
        self.assertEqual(self.env_file.read_text(encoding="utf-8"), before_env)

    def test_empty_host_line_gets_the_default_path(self):
        self.write_env(host="", whisper="")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(env_value(self.env_file, "BREAKER_SYNC_API_SERVICE_KEY_HOST"),
                         str(self.default_key))
        self.assertTrue(self.default_key.is_file())
        self.assertEqual(self.default_key.read_text(encoding="utf-8").strip(), FAKE_KEY)

    def test_chown_failure_prints_the_manual_command_without_the_value(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default(extra={"STUB_CHOWN_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("chown 10001", r.stderr)
        self.assertNotIn(FAKE_KEY, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_the_key_text_is_in_no_argv_no_output_and_no_log(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.key_file.read_text(encoding="utf-8").strip(), FAKE_KEY)
        self.assertNotIn(FAKE_KEY, self.log_text())
        self.assertNotIn(FAKE_KEY, r.stdout)
        self.assertNotIn(FAKE_KEY, r.stderr)

    # -- key generation, owner and the env whisper line ---------------------

    def _key_temp_files(self):
        return [p for p in self.key_file.parent.iterdir()
                if p.name.startswith(".sync-api-service-key.")]

    def test_key_generation_failure_leaves_no_final_key_file(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default(extra={"STUB_KEYGEN_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("cannot generate the service key", r.stderr)
        self.assertFalse(self.key_file.exists())
        self.assertEqual(self._key_temp_files(), [])
        self.assertNotIn(FAKE_KEY, r.stderr)

    def test_a_wrong_length_generated_key_is_refused_and_leaves_no_final_file(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default(extra={"STUB_KEYGEN_VALUE": "A" * 42})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("cannot generate the service key", r.stderr)
        self.assertFalse(self.key_file.exists())
        self.assertEqual(self._key_temp_files(), [])

    def test_the_generated_key_is_moved_from_a_0600_temp_file(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(self.key_file.is_file())
        self.assertEqual(os.stat(self.key_file).st_mode & 0o777, 0o600)
        self.assertEqual(self._key_temp_files(), [])

    def test_empty_env_whisper_line_is_filled_from_the_key_file(self):
        self.write_env(whisper="")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(env_value(self.env_file, "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"),
                         FAKE_KEY)

    def test_a_differing_non_empty_env_whisper_still_refuses(self):
        self.write_env(whisper="B" * 43)
        before_env = self.env_file.read_text(encoding="utf-8")
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("does not match", r.stderr)
        self.assertEqual(self.env_file.read_text(encoding="utf-8"), before_env)

    def test_non_root_chown_failure_writes_the_env_line_before_exiting(self):
        self.key_file.unlink()
        self.write_env(whisper="")
        first = self.run_default(extra={"STUB_CHOWN_RC": "1"})
        self.assertEqual(first.returncode, 1, first.stderr)
        self.assertIn("chown 10001", first.stderr)
        self.assertNotIn(FAKE_KEY, first.stderr)
        self.assertEqual(env_value(self.env_file, "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"),
                         FAKE_KEY)
        # the operator runs the printed chown; the second run succeeds
        second = self.run_default(extra={"STUB_CHOWN_RC": "0"})
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(env_value(self.env_file, "BREAKER_WHISPER_SYNC_API_SERVICE_KEY"),
                         FAKE_KEY)
        self.assertEqual(self.key_file.read_text(encoding="utf-8").strip(), FAKE_KEY)

    def test_key_owner_mismatch_is_refused(self):
        r = self.run_default(extra={"STUB_STAT_UID": "0"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("uid 10001", r.stderr)
        self.assertIn(str(self.key_file), r.stderr)
        self.assertNotIn(FAKE_KEY, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    # -- the conditional backup arm ------------------------------------------

    def test_first_install_with_no_data_skips_the_backup_and_the_schema_record(self):
        r = self.run_default(extra={"STUB_HAS_DATA_RC": "3"})
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("install: first install, nothing to back up", r.stdout)
        log = self.log_text()
        self.assertIn("--has-data --db /srv/docker/sync/sync-api.db "
                      "--db /srv/docker/whisper/whisper-server.db", log)
        self.assertNotIn("--out", log)
        self.assertNotIn("--record-schema", log)
        self.assertIn("up -d", log)

    def test_the_backup_call_carries_one_flag_per_present_database_path(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--out"), [
            "%s --out /var/backups/breaker"
            " --sync-db /srv/docker/sync/sync-api.db"
            " --whisper-db /srv/docker/whisper/whisper-server.db"
            " --keep 5"
            % (self.run_dir / "backup-data.py")], self.log_text())

    def test_the_backup_call_shape_is_accepted_by_the_real_tool(self):
        # The stub execs the REAL backup-data.py for the --out call, so it is
        # install.sh's own argv the tool judges. Two real databases carrying the
        # tables the tool expects, in a temp dir, and a temp --backup-out.
        sync_dir = self.tmp / "data" / "sync"
        whisper_dir = self.tmp / "data" / "whisper"
        sync_db = self.make_db(sync_dir / "sync-api.db", SYNC_TABLES)
        whisper_db = self.make_db(whisper_dir / "whisper-server.db", ("jobs",))
        self.write_mounts([("breaker_breaker_sync_api_data", str(sync_dir)),
                           ("breaker_breaker_whisper_data", str(whisper_dir))])
        out = self.tmp / "backups"
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--backup-out", str(out)],
            extra={"STUB_BACKUP_REAL": "1"})
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.python3_calls("--out")
        self.assertEqual(calls, [
            "%s --out %s --sync-db %s --whisper-db %s --keep 5"
            % (self.run_dir / "backup-data.py", out, sync_db, whisper_db)],
            self.log_text())
        self.assertNotIn("--db", calls[0])
        stamps = [p for p in out.iterdir() if p.is_dir()]
        self.assertEqual(len(stamps), 1, r.stdout + r.stderr)
        stamp = stamps[0]
        self.assertTrue((stamp / "sync-api.bak").is_file(), stamp)
        self.assertTrue((stamp / "whisper-server.bak").is_file(), stamp)
        verified = self.run_backup_data("--verify", str(stamp))
        self.assertEqual(verified.returncode, 0, verified.stdout + verified.stderr)
        self.assertEqual(verified.stdout.split(),
                         ["sync-api", "OK", "whisper-server", "OK"])

    def test_a_host_path_with_a_space_is_one_argument_to_the_real_tool(self):
        # The backup argv must be positional parameters built from
        # individually quoted values. A concatenated string word-split at the
        # call site breaks "/tmp/.../my sync/sync-api.db" into two words; the
        # real tool then refuses the stray positional (exit 2) and the
        # per-argument capture below shows the split. STUB_BACKUP_REAL=1 keeps
        # the real tool as the judge.
        sync_dir = self.tmp / "data" / "my sync"
        whisper_dir = self.tmp / "data" / "my whisper"
        sync_db = self.make_db(sync_dir / "sync-api.db", SYNC_TABLES)
        whisper_db = self.make_db(whisper_dir / "whisper-server.db", ("jobs",))
        self.write_mounts([("breaker_breaker_sync_api_data", str(sync_dir)),
                           ("breaker_breaker_whisper_data", str(whisper_dir))])
        out = self.tmp / "backups"
        argv_file = self.tmp / "backup.argv"
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--backup-out", str(out)],
            extra={"STUB_BACKUP_REAL": "1",
                   "STUB_BACKUP_ARGV_FILE": str(argv_file)})
        self.assertEqual(
            argv_file.read_text(encoding="utf-8").splitlines(),
            [str(self.run_dir / "backup-data.py"), "--out", str(out),
             "--sync-db", str(sync_db), "--whisper-db", str(whisper_db),
             "--keep", "5"],
            r.stdout + r.stderr)
        self.assertEqual(r.returncode, 0, r.stderr)
        stamps = [p for p in out.iterdir() if p.is_dir()]
        self.assertEqual(len(stamps), 1, r.stdout + r.stderr)
        stamp = stamps[0]
        self.assertTrue((stamp / "sync-api.bak").is_file(), stamp)
        self.assertTrue((stamp / "whisper-server.bak").is_file(), stamp)

    def test_a_host_path_with_a_space_is_one_argument_to_has_data_and_the_schema_record(self):
        # The --has-data probe and deploy_record_schema must
        # receive a spaced host path as ONE argument. Both calls are judged by
        # the REAL tool under STUB_BACKUP_REAL=1 and their argv is captured one
        # argument per line: the old `set -- $_idb_dbs` / `"$_idb_paths"`
        # word-split turns ".../my sync/sync-api.db" into two arguments, the
        # real tool exits 2 (argparse), and install.sh hard-fails before the
        # backup. The schema file must carry the whole path as one record.
        sync_dir = self.tmp / "data" / "my sync"
        whisper_dir = self.tmp / "data" / "my whisper"
        sync_db = self.make_db(sync_dir / "sync-api.db", SYNC_TABLES)
        whisper_db = self.make_db(whisper_dir / "whisper-server.db", ("jobs",))
        self.write_mounts([("breaker_breaker_sync_api_data", str(sync_dir)),
                           ("breaker_breaker_whisper_data", str(whisper_dir))])
        out = self.tmp / "backups"
        argv_file = self.tmp / "tool.argv"
        snapshot = self.tmp / "schema.snapshot"
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--backup-out", str(out)],
            extra={"STUB_BACKUP_REAL": "1",
                   "STUB_TOOL_ARGV_FILE": str(argv_file),
                   "STUB_SCHEMA_SNAPSHOT": str(snapshot)})
        self.assertEqual(r.returncode, 0, r.stderr)
        # The EXIT trap removes the record when install.sh exits, so the file
        # itself is gone; the stub snapshotted its content mid-run instead.
        self.assertEqual(sorted(self.tmp.glob("update-schema-before.*")), [],
                         r.stdout + r.stderr)
        schema_file = snapshot
        tab = chr(9)
        nl = chr(10)
        expected_schema = "".join("%s%s0%s" % (db, tab, nl)
                                  for db in (sync_db, whisper_db))
        self.assertEqual(schema_file.read_text(encoding="utf-8"), expected_schema,
                         r.stdout + r.stderr)
        # The argv names the REAL record path (the trap removes it before the
        # test runs), so its boundaries are asserted here and its content
        # above comes from the mid-run snapshot.
        blocks = self.tool_argv_blocks(argv_file)
        self.assertEqual(len(blocks), 2, r.stdout + r.stderr)
        self.assertEqual(blocks[0], [
            str(self.run_dir / "backup-data.py"), "--has-data",
            "--db", str(sync_db), "--db", str(whisper_db)], r.stdout + r.stderr)
        self.assertEqual(blocks[1][:2],
                         [str(self.run_dir / "backup-data.py"), "--record-schema"],
                         r.stdout + r.stderr)
        self.assertRegex(blocks[1][2], r"update-schema-before\.\d+$",
                         r.stdout + r.stderr)
        self.assertEqual(blocks[1][3:],
                         ["--db", str(sync_db), "--db", str(whisper_db)],
                         r.stdout + r.stderr)

    # -- round 3 defect 4/6: stop flags and the leftover schema record ------

    def _schema_leftovers(self):
        return sorted(self.tmp.glob("update-schema-before.*"))

    def _real_data_mounts(self):
        """Two real databases carrying the tables the real tool checks."""
        sync_dir = self.tmp / "data" / "sync"
        whisper_dir = self.tmp / "data" / "whisper"
        self.make_db(sync_dir / "sync-api.db", SYNC_TABLES)
        self.make_db(whisper_dir / "whisper-server.db", ("jobs",))
        self.write_mounts([("breaker_breaker_sync_api_data", str(sync_dir)),
                           ("breaker_breaker_whisper_data", str(whisper_dir))])

    def test_the_stop_argv_carries_env_file_and_compose(self):
        # Defect 4: the fail-closed stop must name the same compose file and
        # env file as every other compose call, never a bare `-p breaker`.
        self.write_listeners("8.8.8.8")
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        stops = [line for line in self.log_text().splitlines()
                 if line.startswith("docker compose") and " stop" in line]
        self.assertEqual(stops, [
            "docker compose --env-file %s -p breaker -f %s stop"
            % (self.env_file,
               self.staging / "server" / "modules" / "deploy" / "docker-compose.yml")],
            self.log_text())

    def test_no_schema_record_survives_a_successful_run(self):
        self._real_data_mounts()
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging),
            "--backup-out", str(self.tmp / "backups")],
            extra={"STUB_BACKUP_REAL": "1"})
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self._schema_leftovers(), [], r.stdout + r.stderr)

    def test_no_schema_record_survives_a_failed_run(self):
        # The swap fails AFTER the record was written: the EXIT trap must
        # still remove it on the failure path.
        self._real_data_mounts()
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging),
            "--backup-out", str(self.tmp / "backups")],
            extra={"STUB_BACKUP_REAL": "1", "STUB_DOCKER_UP_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(self._schema_leftovers(), [], r.stdout + r.stderr)

    def test_the_old_out_db_shape_is_refused_by_the_real_tool(self):
        # The D3 fault's guard: --db belongs to --has-data and --record-schema
        # only; --out with --db is a usage error (exit 2) and writes nothing.
        out = self.tmp / "old-shape"
        result = self.run_backup_data("--out", str(out),
                                      "--db", str(self.tmp / "a.db"),
                                      "--db", str(self.tmp / "b.db"))
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("no database to back up", result.stderr)
        self.assertFalse(out.exists())

    def test_both_volumes_absent_never_calls_has_data_or_the_backup(self):
        self.write_mounts(pairs=(), names=[])
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("install: first install, nothing to back up", r.stdout)
        log = self.log_text()
        self.assertEqual(self.python3_calls("--has-data"), [], log)
        self.assertEqual(self.python3_calls("--out"), [], log)
        self.assertEqual(self.python3_calls("--record-schema"), [], log)
        self.assertIn("up -d", log)

    def test_one_absent_volume_passes_only_the_present_db(self):
        self.write_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync")],
                          names=["breaker_breaker_sync_api_data"])
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_text()
        self.assertEqual(self.python3_calls("--has-data"), [
            "%s --has-data --db /srv/docker/sync/sync-api.db"
            % (self.run_dir / "backup-data.py")], log)
        self.assertEqual(self.python3_calls("--out"), [
            "%s --out /var/backups/breaker --sync-db /srv/docker/sync/sync-api.db"
            " --keep 5"
            % (self.run_dir / "backup-data.py")], log)
        schema = self.python3_calls("--record-schema")
        self.assertEqual(len(schema), 1, log)
        self.assertRegex(
            schema[0],
            r"^%s --record-schema \S+ --db /srv/docker/sync/sync-api\.db$"
            % re.escape(str(self.run_dir / "backup-data.py")))

    def test_no_backup_skips_only_the_backup_and_still_records(self):
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--no-backup"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("install: backup skipped (--no-backup)", r.stdout)
        log = self.log_text()
        self.assertNotIn("--out", log)
        self.assertIn("--record-schema", log)
        self.assertIn("up -d", log)

    def test_the_backup_and_the_schema_record_run_after_the_image_build_and_before_the_swap(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("install: backup the data", r.stdout)
        self.assertIn("install: schema record", r.stdout)
        log = self.log_text()
        self.assertIn("--out /var/backups/breaker", log)
        self.assertIn("--record-schema", log)
        self.assertGreater(log.find("--has-data"), log.find("docker compose --env-file"), log)
        self.assertLess(log.find("--out /var/backups/breaker"), log.find("up -d"), log)
        self.assertLess(log.find("--record-schema"), log.find("up -d"), log)

    def test_backup_out_option_sets_the_backup_destination(self):
        out = self.tmp / "backups"
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--backup-out", str(out)])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("--out " + str(out), self.log_text())

    def test_backup_out_requires_a_value(self):
        r = self.run_default(args=["--remote", REMOTE, "--backup-out"])
        self.assertEqual(r.returncode, 2, r.stderr)
        self.assertIn("--backup-out", r.stderr)

    def test_keep_reaches_the_backup_call_as_an_exact_argument(self):
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--keep", "3"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.python3_calls("--out"), [
            "%s --out /var/backups/breaker"
            " --sync-db /srv/docker/sync/sync-api.db"
            " --whisper-db /srv/docker/whisper/whisper-server.db"
            " --keep 3"
            % (self.run_dir / "backup-data.py")], self.log_text())

    def test_the_default_keep_is_five(self):
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.python3_calls("--out")
        self.assertEqual(len(calls), 1, self.log_text())
        self.assertIn("--keep 5", calls[0], self.log_text())

    def test_a_bad_keep_value_is_refused_with_exit_2(self):
        # The message is asserted too: before the plumbing lands, `--keep` is
        # an unknown option and the refusal is a different message.
        for value in ("abc", "0", "-1", "3.5", ""):
            with self.subTest(value=value):
                r = self.run_default(args=[
                    "--remote", REMOTE, "--env-file", str(self.env_file),
                    "--staging", str(self.staging), "--keep", value])
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertIn("positive integer", r.stderr)
                self.assertNotIn("clone", self.log_text())
                self.assertNotIn("up -d", self.log_text())

    def test_a_backup_failure_exits_before_the_swap(self):
        r = self.run_default(extra={"STUB_BACKUP_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("backup failed", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_schema_record_failure_exits_before_the_swap(self):
        r = self.run_default(extra={"STUB_RECORD_SCHEMA_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("schema record failed", r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_a_has_data_probe_failure_is_a_hard_failure(self):
        r = self.run_default(extra={"STUB_HAS_DATA_RC": "2"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("cannot tell whether a database file exists", r.stderr)
        self.assertIn("2", r.stderr)
        self.assertNotIn("nothing to back up", r.stdout)
        self.assertNotIn("up -d", self.log_text())

    def test_an_unresolvable_volume_is_a_hard_failure(self):
        # whisper is LISTED (so it is not merely absent) but its inspect
        # fails: that stays a hard failure naming it.
        self.write_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync")],
                          names=["breaker_breaker_sync_api_data",
                                 "breaker_breaker_whisper_data"])
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("breaker_breaker_whisper_data", r.stderr)
        self.assertNotIn("nothing to back up", r.stdout)
        self.assertNotIn("up -d", self.log_text())

    def test_health_urls_come_from_the_shared_builder_for_an_ipv6_bind(self):
        self.write_env(overrides={"BREAKER_BIND_ADDR": "fd00::5"})
        self.write_listeners("[fd00::5]")
        r = self.run_default()
        self.assertEqual(r.returncode, 0, r.stderr)
        for port in ("8081", "8083", "8084"):
            self.assertIn("http://[fd00::5]:%s/health" % port, r.stdout)
        self.assertNotIn("http://fd00::5:8081/health", r.stdout)

    # -- failures -------------------------------------------------------------

    def test_no_release_tag_exits_3(self):
        self.tags.write_text("0" * 40 + "\trefs/tags/latest\n", encoding="utf-8")
        r = self.run_default()
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_named_tag_absent_from_the_remote_exits_3(self):
        r = self.run_default(args=[
            "--remote", REMOTE, "--env-file", str(self.env_file),
            "--staging", str(self.staging), "--tag", "v9.9.9"])
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertNotIn("up -d", self.log_text())

    def test_failures_before_the_swap_leave_the_stack_untouched(self):
        cases = ({"STUB_GIT_CLONE_RC": "1"}, {"STUB_GRADLEW_RC": "1"},
                 {"STUB_DOCKER_BUILD_RC": "1"})
        for extra in cases:
            with self.subTest(extra=extra):
                r = self.run_default(extra=extra)
                self.assertEqual(r.returncode, 1, r.stderr)
                self.assertNotIn("up -d", self.log_text())

    def test_a_swap_failure_exits_1(self):
        r = self.run_default(extra={"STUB_DOCKER_UP_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("up -d", self.log_text())

    def test_a_health_failure_exits_1(self):
        r = self.run_default(extra={"STUB_CURL_RC": "1"})
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("up -d", self.log_text())
        self.assertIn("curl", self.log_text())

    def test_a_bad_bind_stops_the_stack_and_never_downs_it(self):
        self.write_listeners("8.8.8.8")
        r = self.run_default()
        self.assertEqual(r.returncode, 1, r.stderr)
        log = self.log_text()
        self.assertEqual(
            [line for line in log.splitlines()
             if line.startswith("docker compose") and " stop" in line],
            ["docker compose --env-file %s -p breaker -f %s stop"
             % (self.env_file,
                self.staging / "server" / "modules" / "deploy" / "docker-compose.yml")],
            log)
        self.assertNotIn("down", log)
        self.assertTrue(any("8083" in line for line in r.stderr.splitlines()))


if __name__ == "__main__":
    unittest.main()
