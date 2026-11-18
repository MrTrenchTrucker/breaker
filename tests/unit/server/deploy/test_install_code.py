"""Unit tests for the first-account install code step of scripts/install.sh.

The server writes a one-time install code to the file `install-code` next to
its database while no account exists. install.sh prints it ONCE, at the end of
a first install, and only to a terminal with shell tracing off. These tests
drive the REAL install.sh through the same temp PATH of stubs test_install.py
uses (the stubs and helpers below are copied from it, never imported), with a
fake data volume that is a temp directory holding REAL files with REAL modes.
A pty stands in for the terminal; plain pipes stand in for a redirect.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_install_code.py'
"""
import os
import re
import select
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import pty

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = REPO_ROOT / "server" / "modules" / "deploy" / "scripts"
INSTALL = Path(os.environ.get("INSTALL_SRC", str(SCRIPTS / "install.sh")))
COMPOSE = REPO_ROOT / "server" / "modules" / "deploy" / "docker-compose.yml"
ENV_EXAMPLE = REPO_ROOT / "server" / "modules" / "deploy" / ".env.example"

FAKE_KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"
PLACEHOLDER = "test-value"
BIND = "10.8.0.5"
FORWARDER_URL = "http://10.20.30.12:9000"
REMOTE = "git@example.invalid/breaker.git"

# The install code the fake server wrote: 43 URL-safe characters, different
# from the service key above so a leak of either is told apart.
CODE = "Zk3_mN8-pQ7rS2tU5vW9xY1zA4bC6dE0fG_hI-jLk8m"
assert len(CODE) == 43 and re.fullmatch(r"[A-Za-z0-9_-]{43}", CODE)
SENTENCE = ("First account: enter this install code on the Create account "
            "screen: " + CODE)
NOTE_NONE = "install: no install code needed"
NOTE_UNUSABLE = "install: install code not shown: the file is not usable"
NOTE_HIDDEN = ("install: install code not shown: output is not a terminal or "
               "tracing is on; read the install-code file in the data volume")
STEP_LINE = "install: install code"

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


STAT_STUB_UID = "4242"

NO_STAT_STUB_REASON = ("the shell's own stat applet wins over the PATH stub, so "
                       "the owner check cannot be driven in this shell")


def shell_honours_stat_stub(shell, env):
    """Probe one shell, once: does a `stat` stub on PATH win over the shell's
    own `stat`? Runs `stat -c %u /` in the shell with the environment the
    traced run uses (the stub answers STAT_STUB_UID). Returns (True, "") when
    it does, else (False, reason) with a non-empty sentence."""
    if os.path.basename(shell) == "busybox":
        argv = [shell, "sh", "-c", "stat -c %u /"]
    else:
        argv = [shell, "-c", "stat -c %u /"]
    probe_env = dict(env)
    probe_env["STUB_STAT_UID"] = STAT_STUB_UID
    try:
        done = subprocess.run(argv, capture_output=True, text=True,
                              env=probe_env, timeout=20,
                              stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError) as exc:
        return False, ("the shell could not be run for the stat probe (%s)"
                       % type(exc).__name__)
    if done.stdout.strip() == STAT_STUB_UID:
        return True, ""
    return False, NO_STAT_STUB_REASON


def select_traced_shells(shells, probe):
    """[(shell, skip_reason_or_None)]. bash is never probed and never
    skipped, so the traced-shell check cannot go blind on a host that has
    only bash; every other shell is probed once with `probe(shell)`."""
    chosen = []
    for shell in shells:
        if os.path.basename(shell) == "bash":
            chosen.append((shell, None))
            continue
        applies, reason = probe(shell)
        chosen.append((shell, None if applies else reason))
    return chosen


def run_traced_shells(case, selected, body):
    """Run body(shell) in its own subTest per shell; a shell with a skip
    reason skips only its own subTest."""
    for shell, reason in selected:
        with case.subTest(shell=shell):
            if reason:
                case.skipTest(reason)
            body(shell)


class InstallCodeTest(unittest.TestCase):
    """Drives the real install.sh through stubs on a temp PATH."""

    def _base_setup(self):
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

    # -- harness additions ---------------------------------------------------

    def setUp(self):
        self._base_setup()
        # The fake data volume: a temp directory per volume, real files in it.
        self.vol_sync = self.tmp / "vol-sync"
        self.vol_whisper = self.tmp / "vol-whisper"
        self.vol_sync.mkdir()
        self.vol_whisper.mkdir()
        self.write_mounts(pairs=(
            ("breaker_breaker_sync_api_data", str(self.vol_sync)),
            ("breaker_breaker_whisper_data", str(self.vol_whisper)),
        ))
        self.code_file = self.vol_sync / "install-code"
        # git and docker also record their exported environment, so a code
        # that was exported to a child process is visible.
        self.envlog = self.tmp / "stub-env.log"
        for name, text in (("git", STUB_GIT), ("docker", STUB_DOCKER)):
            lines = text.split("\n", 1)
            self._write(self.bin / name,
                        lines[0] + '\nexport -p >> "$STUB_ENVLOG"\n' + lines[1])
        self.env["STUB_ENVLOG"] = str(self.envlog)
        self.baseline_tree = None

    def put_code(self, text=CODE + "\n", mode=0o600):
        self.code_file.write_bytes(text if isinstance(text, bytes)
                                   else text.encode("utf-8"))
        os.chmod(str(self.code_file), mode)

    def _args(self):
        return ["--remote", REMOTE, "--env-file", str(self.env_file),
                "--staging", str(self.staging)]

    def _reset(self):
        if self.staging.exists():
            shutil.rmtree(str(self.staging), ignore_errors=True)
        self.log.write_text("", encoding="utf-8")
        self.envlog.write_text("", encoding="utf-8")

    def run_pipe(self, extra=None, flags=("-eu",)):
        """stdout is a pipe (not a terminal)."""
        env = dict(self.env)
        env.update(extra or {})
        self._reset()
        return subprocess.run(["sh", *flags, str(self.install)] + self._args(),
                              capture_output=True, text=True, env=env,
                              cwd=str(self.tmp), timeout=60,
                              stdin=subprocess.DEVNULL)

    def run_pty(self, extra=None, flags=("-eu",), shell="sh"):
        """stdout is a real pty slave (a terminal); stderr goes to a file so
        a long xtrace cannot block. Returns (returncode, stdout, stderr)."""
        env = dict(self.env)
        env.update(extra or {})
        self._reset()
        err_path = self.tmp / "pty-stderr.txt"
        master, slave = pty.openpty()
        try:
            with open(str(err_path), "wb") as err:
                proc = subprocess.Popen(
                    [shell, *flags, str(self.install)] + self._args(),
                    stdin=subprocess.DEVNULL, stdout=slave, stderr=err,
                    env=env, cwd=str(self.tmp))
                os.close(slave)
                slave = -1
                chunks = []
                deadline = time.time() + 60
                while True:
                    ready, _w, _x = select.select([master], [], [], 0.2)
                    if ready:
                        try:
                            data = os.read(master, 65536)
                        except OSError:
                            break
                        if not data:
                            break
                        chunks.append(data)
                    elif proc.poll() is not None:
                        break
                    if time.time() > deadline:
                        proc.kill()
                        self.fail("install.sh did not finish in 60 s")
                proc.wait(timeout=30)
        finally:
            if slave != -1:
                os.close(slave)
            os.close(master)
        out = b"".join(chunks).decode("utf-8", "replace").replace("\r\n", "\n")
        return proc.returncode, out, err_path.read_text(encoding="utf-8",
                                                         errors="replace")

    def stdout_lines(self, out):
        return [line for line in out.split("\n") if line]

    def tree_files(self):
        """Every regular file under the temp tree except the fake volume's
        install-code file: (relative path -> bytes). Symlinks are skipped."""
        found = {}
        for root, _dirs, files in os.walk(str(self.tmp)):
            for name in files:
                path = Path(root) / name
                if path == self.code_file or path.is_symlink():
                    continue
                found[str(path.relative_to(self.tmp))] = path.read_bytes()
        return found

    def assert_refused(self, label):
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, "%s: exit %s %s" % (label, rc, err))
        self.assertNotIn(CODE, out, label)
        self.assertNotIn(CODE, err, label)
        self.assertIn(NOTE_UNUSABLE, out, label)
        self.assertNotIn("First account", out, label)
        self.assertTrue(out.rstrip().endswith("install: ok"), label)

    # -- the print rules ------------------------------------------------------

    def test_the_code_is_printed_once_on_a_terminal_in_the_exact_sentence(self):
        self.put_code()
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertEqual(out.count(CODE), 1, out)
        self.assertEqual(self.stdout_lines(out).count(SENTENCE), 1, out)
        self.assertEqual(len([l for l in self.stdout_lines(out)
                              if l.startswith("First account")]), 1, out)
        self.assertNotIn(CODE, err)

    def test_the_code_is_in_no_argv_log_no_env_no_stderr_and_no_file_but_the_volume_file(self):
        self.put_code()
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertEqual(out.count(CODE), 1)
        needle = CODE.encode("utf-8")
        self.assertNotIn(CODE, err)
        self.assertNotIn(CODE, self.log_text())
        self.assertNotIn(CODE, self.envlog.read_text(encoding="utf-8"))
        self.assertTrue(self.log_text(), "stub log is empty: the run did not happen")
        self.assertTrue(self.envlog.read_text(encoding="utf-8"),
                        "stub env log is empty: the env check is blind")
        self.assertIn("volume inspect", self.log_text())
        files = self.tree_files()
        self.assertTrue(files)
        for rel, data in files.items():
            self.assertNotIn(needle, data, rel)
        # the one allowed place still holds it
        self.assertIn(needle, self.code_file.read_bytes())

    def test_the_code_is_not_printed_when_stdout_is_a_pipe(self):
        self.put_code()
        r = self.run_pipe()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(NOTE_HIDDEN, r.stdout.splitlines())
        self.assertNotIn(CODE, r.stdout)
        self.assertNotIn(CODE, r.stderr)
        self.assertNotIn("First account", r.stdout)
        self.assertNotIn(CODE, self.log_text())

    def test_the_code_is_not_printed_under_sh_x_even_on_a_terminal(self):
        self.put_code()
        rc, out, err = self.run_pty(flags=("-x", "-eu"))
        self.assertEqual(rc, 0, err[-2000:])
        self.assertIn("+ ", err, "xtrace was not on: this check would be blind")
        self.assertNotIn(CODE, out)
        self.assertNotIn(CODE, err)
        self.assertIn(NOTE_HIDDEN, self.stdout_lines(out))
        self.assertNotIn("First account", out)

    def test_the_code_is_not_in_the_trace_of_other_posix_shells_either(self):
        # dash does not trace the word of a `case`; bash does. The script is
        # POSIX sh, so a shell that traces more must not leak the code.
        shells = [p for p in (shutil.which("bash"), shutil.which("ksh"),
                              shutil.which("busybox")) if p]
        if not shells:
            self.skipTest("no second POSIX shell on this host")
        selected = select_traced_shells(
            shells, lambda shell: shell_honours_stat_stub(shell, self.env))

        def check(shell):
            flags = ("sh", "-x", "-eu") if shell.endswith("busybox") else ("-x", "-eu")
            self.put_code()
            rc, out, err = self.run_pty(flags=flags, shell=shell)
            self.assertEqual(rc, 0, err[-2000:])
            self.assertIn("+ ", err, "xtrace was not on: this check would be blind")
            self.assertNotIn(CODE, out)
            self.assertNotIn(CODE, err)
            self.assertIn(NOTE_HIDDEN, self.stdout_lines(out))

        run_traced_shells(self, selected, check)

    def test_an_absent_file_prints_no_install_code_needed_and_exits_0(self):
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertIn(NOTE_NONE, self.stdout_lines(out))
        self.assertNotIn("First account", out)
        self.assertNotIn("not shown", out)
        self.assertTrue(out.rstrip().endswith("install: ok"))

    def test_an_unresolvable_data_folder_prints_no_install_code_needed(self):
        # the sync-api volume is not listed at all: deploy_db_paths answers an
        # empty first line, so there is no folder to look in
        self.write_mounts(pairs=(("breaker_breaker_whisper_data",
                                  str(self.vol_whisper)),))
        self.put_code()
        # a valid code file in the working directory must not be picked up
        # as "the file next to the database" when there is no database path
        (self.tmp / "install-code").write_text(CODE + "\n", encoding="utf-8")
        os.chmod(str(self.tmp / "install-code"), 0o600)
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertIn(NOTE_NONE, self.stdout_lines(out))
        self.assertNotIn(CODE, out)

    # -- the file checks ---------------------------------------------------------

    def test_a_file_with_mode_0644_is_refused(self):
        self.put_code(mode=0o644)
        self.assert_refused("0644")

    def test_a_file_with_mode_0400_is_refused(self):
        self.put_code(mode=0o400)
        self.assert_refused("0400")

    def test_any_mode_other_than_exactly_0600_is_refused(self):
        for mode in (0o660, 0o640, 0o700, 0o666, 0o604, 0o200, 0o601):
            with self.subTest(mode=oct(mode)):
                self.put_code()
                os.chmod(str(self.code_file), mode)
                self.assert_refused(oct(mode))
                os.chmod(str(self.code_file), 0o600)

    def test_a_symlink_is_refused_even_when_the_target_is_a_valid_0600_file(self):
        target = self.tmp / "elsewhere-code"
        target.write_text(CODE + "\n", encoding="utf-8")
        os.chmod(str(target), 0o600)
        os.symlink(str(target), str(self.code_file))
        self.assertTrue(self.code_file.is_symlink())
        self.assert_refused("symlink")

    def test_a_dangling_symlink_is_refused_not_treated_as_absent(self):
        os.symlink(str(self.tmp / "nowhere"), str(self.code_file))
        self.assert_refused("dangling symlink")

    def test_a_directory_named_install_code_is_refused(self):
        self.code_file.mkdir()
        self.assert_refused("directory")

    def test_a_wrong_length_is_refused(self):
        for text in (CODE[:42], CODE + "A", CODE[:42] + "\n", CODE + "A\n",
                     CODE * 2, "A", CODE[:20]):
            with self.subTest(length=len(text.strip())):
                self.put_code(text)
                self.assert_refused(repr(len(text)))

    def test_a_bad_character_is_refused_at_every_position(self):
        for bad in (" ", "+", "/", "=", ".", "\t", "!", "\u00e9", "$", "'"):
            for pos in (0, 20, 42):
                text = CODE[:pos] + bad + CODE[pos + 1:]
                with self.subTest(bad=repr(bad), pos=pos):
                    self.put_code(text + "\n")
                    self.assert_refused(repr(text))

    def test_an_empty_file_is_refused(self):
        self.put_code("")
        self.assert_refused("empty")
        self.put_code("\n")
        self.assert_refused("one newline only")

    def test_more_than_one_trailing_newline_or_a_second_line_is_refused(self):
        for text in (CODE + "\n\n", CODE + "\r\n", CODE + "\nX\n", CODE + "\nX",
                     CODE + "\n\n\n"):
            with self.subTest(text=repr(text[43:])):
                self.put_code(text)
                self.assert_refused(repr(text[43:]))

    def test_one_trailing_newline_is_accepted(self):
        self.put_code(CODE + "\n")
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.stdout_lines(out).count(SENTENCE), 1, out)

    def test_no_trailing_newline_is_accepted(self):
        self.put_code(CODE)
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.stdout_lines(out).count(SENTENCE), 1, out)

    def test_every_url_safe_character_class_is_accepted(self):
        for text in ("A" * 43, "z" * 43, "0" * 43, "_" * 43, "-" * 43,
                     "-_" * 21 + "a"):
            with self.subTest(text=text[:6]):
                self.put_code(text + "\n")
                rc, out, err = self.run_pty()
                self.assertEqual(rc, 0, err)
                self.assertIn("screen: " + text, self.stdout_lines(out)[-2])

    # -- order and gating ----------------------------------------------------------

    def test_the_step_comes_after_vpn_bind_and_before_install_ok(self):
        self.put_code()
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        lines = self.stdout_lines(out)
        vpn = lines.index("install: vpn-bind")
        step = lines.index(STEP_LINE)
        sent = lines.index(SENTENCE)
        ok = lines.index("install: ok")
        self.assertTrue(vpn < step < sent < ok, lines)
        self.assertEqual(ok, len(lines) - 1)
        self.assertEqual(lines.count("install: ok"), 1)

    def test_the_second_volume_lookup_happens_only_after_the_stack_is_up(self):
        self.put_code()
        r = self.run_pipe()
        self.assertEqual(r.returncode, 0, r.stderr)
        log = self.log_lines()
        inspects = [i for i, line in enumerate(log) if "volume inspect" in line]
        up = [i for i, line in enumerate(log) if " up -d" in line]
        self.assertEqual(len(inspects), 4, log)  # two volumes, called twice
        self.assertEqual(len(up), 1)
        self.assertEqual(len([i for i in inspects if i > up[0]]), 2, log)

    def test_a_failed_health_check_means_the_code_is_never_looked_up_or_shown(self):
        self.put_code()
        rc, out, err = self.run_pty(extra={"STUB_CURL_RC": "1"})
        self.assertEqual(rc, 1, err)
        self.assertEqual(self.log_text().count("volume inspect"), 2,
                         self.log_text())
        self.assertNotIn(CODE, out)
        self.assertNotIn(CODE, err)
        self.assertNotIn(STEP_LINE, self.stdout_lines(out))

    def test_an_exposed_vpn_bind_means_the_code_is_never_looked_up_or_shown(self):
        self.put_code()
        self.write_listeners("8.8.8.8")
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 1, err)
        self.assertEqual(self.log_text().count("volume inspect"), 2,
                         self.log_text())
        self.assertNotIn(CODE, out)
        self.assertNotIn(CODE, err)
        self.assertNotIn(STEP_LINE, self.stdout_lines(out))
        self.assertNotIn("install: ok", out)

    def test_a_failed_swap_means_the_code_is_never_looked_up_or_shown(self):
        self.put_code()
        rc, out, err = self.run_pty(extra={"STUB_DOCKER_UP_RC": "1"})
        self.assertEqual(rc, 1, err)
        self.assertEqual(self.log_text().count("volume inspect"), 2)
        self.assertNotIn(CODE, out + err)

    # -- exit code and leftovers -------------------------------------------------------

    def test_the_exit_code_stays_0_when_the_docker_volume_lookup_fails_late(self):
        # a failing second lookup is "no install code needed", not a failure
        self.put_code()
        marker = self.tmp / "second-lookup"
        stub = self.bin / "docker"
        text = stub.read_text(encoding="utf-8")
        text = text.replace(
            '    *" volume inspect -f {{.Mountpoint}} "*)\n',
            '    *" volume inspect -f {{.Mountpoint}} "*)\n'
            '        if [ -f "%s" ]; then exit 1; fi\n' % marker, 1)
        text = text.replace(
            '    *" up -d "*)\n',
            '    *" up -d "*)\n        : > "%s"\n' % marker, 1)
        self._write(stub, text)
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertIn(NOTE_NONE, self.stdout_lines(out))
        self.assertNotIn(CODE, out + err)

    def test_the_step_leaves_no_file_behind_and_does_not_touch_the_code_file(self):
        # control: the same install with no code file at all
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        control = sorted(self.tree_files())
        control_vol = sorted(os.listdir(str(self.vol_sync)))
        self.assertTrue(control)
        self.put_code()
        before = (self.code_file.read_bytes(), self.code_file.stat().st_mode,
                  self.code_file.stat().st_mtime_ns)
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertEqual(sorted(self.tree_files()), control)
        self.assertEqual(sorted(os.listdir(str(self.vol_sync))),
                         sorted(control_vol + ["install-code"]))
        after = (self.code_file.read_bytes(), self.code_file.stat().st_mode,
                 self.code_file.stat().st_mtime_ns)
        self.assertEqual(before, after)

    def test_the_step_leaves_no_file_behind_when_the_code_is_refused(self):
        rc, out, err = self.run_pty()
        control = sorted(self.tree_files())
        self.put_code(mode=0o644)
        rc, out, err = self.run_pty()
        self.assertEqual(rc, 0, err)
        self.assertEqual(sorted(self.tree_files()), control)

    # -- the other scripts never print it ------------------------------------------------

    def test_update_and_rollback_never_mention_the_install_code(self):
        for name in ("update.sh", "rollback.sh"):
            with self.subTest(script=name):
                text = (SCRIPTS / name).read_text(encoding="utf-8")
                self.assertNotIn("install-code", text)
                self.assertNotIn("First account", text)
                self.assertNotIn("install code", text)

    def test_the_install_code_is_not_in_the_env_example_or_the_compose_file(self):
        for path in (ENV_EXAMPLE, COMPOSE):
            self.assertNotIn(CODE, path.read_text(encoding="utf-8"))


class StatStubProbeTest(unittest.TestCase):
    """The probe and the shell selection, driven with fake shells (no real
    busybox needed)."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-probe-test-"))
        self.addCleanup(shutil.rmtree, str(self.tmp), True)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.log = self.tmp / "stub.log"
        self.real_stat = shutil.which("stat")
        self.assertTrue(self.real_stat, "no stat on this host")
        self._script(self.bin / "stat",
                     STUB_STAT.replace("@REAL_STAT@", str(self.real_stat)))
        self.env = dict(os.environ)
        self.env.update({"PATH": str(self.bin), "STUB_LOG": str(self.log)})

    def _script(self, path, text):
        path.write_text(text, encoding="utf-8")
        os.chmod(str(path), 0o755)
        return str(path)

    def honouring_shell(self, name="fake-sh"):
        # ignores its argv; resolves stat through PATH, so the stub wins
        return self._script(self.tmp / name,
                            "#!/bin/sh\nexec stat -c %u /\n")

    def builtin_stat_shell(self, name="fake-builtin-sh"):
        # its own stat, found by an absolute path, ignoring PATH
        return self._script(self.tmp / name,
                            "#!/bin/sh\nexec %s -c %%u /\n" % self.real_stat)

    def busybox_standin(self):
        d = self.tmp / "bb"
        d.mkdir()
        return self._script(
            d / "busybox",
            '#!/bin/sh\n[ "$1" = sh ] || exit 1\nshift\nexec stat -c %u /\n')

    def test_a_shell_that_honours_path_makes_the_probe_say_applies(self):
        self.assertEqual(shell_honours_stat_stub(self.honouring_shell(),
                                                 self.env), (True, ""))

    def test_a_shell_with_its_own_stat_makes_the_probe_say_does_not_apply(self):
        applies, reason = shell_honours_stat_stub(self.builtin_stat_shell(),
                                                  self.env)
        self.assertFalse(applies)
        self.assertTrue(reason.strip())
        self.assertIn("stat applet", reason)

    def test_a_shell_that_does_not_run_makes_the_probe_say_does_not_apply(self):
        applies, reason = shell_honours_stat_stub(
            str(self.tmp / "no-such-shell"), self.env)
        self.assertFalse(applies)
        self.assertTrue(reason.strip())

    def test_the_busybox_form_is_used_for_a_shell_named_busybox(self):
        # the stand-in exits 1 unless its first argument is `sh`
        self.assertEqual(shell_honours_stat_stub(self.busybox_standin(),
                                                 self.env), (True, ""))

    def test_a_probe_answer_other_than_the_stub_number_is_not_accepted(self):
        # the real uid of / (not 4242) must read as does-not-apply
        self.assertNotEqual(
            subprocess.run([self.real_stat, "-c", "%u", "/"],
                           capture_output=True, text=True).stdout.strip(),
            STAT_STUB_UID)
        self.assertFalse(shell_honours_stat_stub(
            self.builtin_stat_shell(), self.env)[0])

    def test_bash_is_selected_without_being_probed(self):
        bash = shutil.which("bash")
        if not bash:
            bash = self._script(self.tmp / "bash", "#!/bin/sh\nexit 0\n")
        calls = []

        def spy(shell):
            calls.append(shell)
            return False, "spy says no"

        other = self.builtin_stat_shell()
        chosen = select_traced_shells([bash, other], spy)
        self.assertEqual(calls, [other])
        self.assertEqual(chosen, [(bash, None), (other, "spy says no")])

    def test_a_shell_that_applies_is_selected_with_no_skip_reason(self):
        other = self.honouring_shell()
        chosen = select_traced_shells(
            [other], lambda shell: shell_honours_stat_stub(shell, self.env))
        self.assertEqual(chosen, [(other, None)])

    def test_a_shell_that_does_not_apply_is_selected_with_the_stat_applet_reason(self):
        other = self.builtin_stat_shell()
        chosen = select_traced_shells(
            [other], lambda shell: shell_honours_stat_stub(shell, self.env))
        self.assertEqual(len(chosen), 1)
        shell, reason = chosen[0]
        self.assertEqual(shell, other)
        self.assertTrue(reason and reason.strip())
        self.assertIn("stat applet", reason)

    def test_a_skipped_shell_skips_only_its_own_subtest(self):
        ran = []
        selected = [("skipped-shell", "the stat applet wins"),
                    ("other-shell", None)]

        class Inner(unittest.TestCase):
            def runTest(inner):
                run_traced_shells(inner, selected, ran.append)

        result = unittest.TestResult()
        Inner().run(result)
        self.assertEqual(ran, ["other-shell"])
        self.assertEqual(len(result.skipped), 1)
        self.assertIn("stat applet", result.skipped[0][1])
        self.assertTrue(result.wasSuccessful())
        self.assertEqual(result.testsRun, 1)


if __name__ == "__main__":
    unittest.main()
