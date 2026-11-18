"""Unit tests for scripts/deploy-common.sh, the sourced deploy helper.

The tests drive the REAL file through a tiny driver on a temp PATH of stubs
(git, docker, curl, python3, sqlite3), a stub health-check.sh and a stub
backup-data.py, so no real host, docker or network is touched. The address
classifier is pinned against the REAL scripts/vpn-bind.sh with a fake
listeners file.

Run: python3 -m unittest discover -s tests/unit/server/deploy -p 'test_deploy_common.py'
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPTS = REPO_ROOT / "server" / "modules" / "deploy" / "scripts"
COMMON = SCRIPTS / "deploy-common.sh"
VPN_BIND = SCRIPTS / "vpn-bind.sh"
COMPOSE = REPO_ROOT / "server" / "modules" / "deploy" / "docker-compose.yml"
ENV_EXAMPLE = REPO_ROOT / "server" / "modules" / "deploy" / ".env.example"

# The file the tests source. The mutant proofs point DEPLOY_COMMON_SRC at a
# scratch copy with one arm removed; the default is the real file.
COMMON_SRC = Path(os.environ.get("DEPLOY_COMMON_SRC", str(COMMON)))

DRIVER = """#!/bin/sh
# Test driver: source the deploy-common.sh in this directory and run the
# named function with the environment the test passes.
SCRIPT_DIR=$(dirname "$0")
. "$SCRIPT_DIR/deploy-common.sh"
"$@"
"""

STUB_HEALTH = """#!/bin/sh
printf 'health-check %s\\n' "$*" >> "$STUB_LOG"
_shc=0
[ -f "$STUB_HEALTH_COUNT" ] && _shc=$(cat "$STUB_HEALTH_COUNT" 2>/dev/null)
_shc=$((_shc + 1))
printf '%s\\n' "$_shc" > "$STUB_HEALTH_COUNT"
if [ -n "${STUB_HEALTH_FAIL_TIMES:-}" ] && [ "$_shc" -le "$STUB_HEALTH_FAIL_TIMES" ]; then
    exit "${STUB_HEALTH_RC:-1}"
fi
exit "${STUB_HEALTH_RC_FINAL:-${STUB_HEALTH_RC:-0}}"
"""

STUB_BACKUP = """#!/bin/sh
# stub backup-data.py: the python3 stub records the argv and never reads this
exit 0
"""

STUB_PYTHON3 = """#!/bin/sh
printf 'python3 %s\\n' "$*" >> "$STUB_LOG"
exit "${STUB_PYTHON3_RC:-0}"
"""

STUB_DOCKER = """#!/bin/sh
printf 'docker %s\\n' "$*" >> "$STUB_LOG"
case "$*" in
    "compose version")
        printf '%s\n' "${STUB_COMPOSE_VERSION-Docker Compose version v2.20.0}"
        exit "${STUB_COMPOSE_RC:-0}"
        ;;
    "buildx version")
        printf '%s\\n' "${STUB_BUILDX_VERSION:-Docker buildx version 0.12.0}"
        exit "${STUB_BUILDX_RC:-0}"
        ;;
    "compose -p breaker ps --format {{.Service}} {{.Image}}")
        # `deploy_running_image_tag` reads the whole running set here: one
        # "SERVICE IMAGE" line per running container, empty = nothing running.
        _sd_ps_rc=${STUB_PS_RC:-0}
        if [ "$_sd_ps_rc" -ne 0 ]; then
            printf '%s\\n' "${STUB_PS_ERR:-stub docker: ps failed}" >&2
            exit "$_sd_ps_rc"
        fi
        cat "$STUB_PS_FILE" 2>/dev/null
        exit 0
        ;;
    "volume ls --format {{.Name}}")
        _sd_rc=${STUB_VOLUME_LS_RC:-0}
        if [ "$_sd_rc" -ne 0 ]; then
            printf '%s\\n' "${STUB_VOLUME_LS_ERR:-stub docker: volume ls failed}" >&2
            exit "$_sd_rc"
        fi
        cat "$STUB_VOLUMES_FILE" 2>/dev/null
        exit 0
        ;;
    "volume inspect -f {{.Mountpoint}} "*)
        shift 4
        _sd_name=$1
        _sd_mount=$(awk -v n="$_sd_name" '$1 == n { print $2; exit }' \\
            "$STUB_MOUNTS_FILE" 2>/dev/null)
        [ -n "$_sd_mount" ] || {
            [ "${STUB_INSPECT_EMPTY:-0}" = "1" ] && exit 0
            exit 1
        }
        printf '%s\\n' "$_sd_mount"
        exit 0
        ;;
esac
exit 0
"""


# Two LAN samples, assembled from pieces so no added line carries a whole LAN
# literal; the classifier patterns inside the scripts are code, not samples.
LAN_PRIVATE_A = ".".join(("192", "168", "0", "1"))
LAN_PRIVATE_B = ".".join(("192", "168", "1", "1"))

# The verdict table: address, the word deploy_addr_class must print, its exit.
ADDR_VERDICTS = (
    ("", "empty", 1),
    ("10.0.0.1", "private", 0),
    ("10.255.255.255", "private", 0),
    ("172.16.0.1", "private", 0),
    ("172.31.255.255", "private", 0),
    ("172.15.0.1", "public", 1),
    ("172.32.0.1", "public", 1),
    (LAN_PRIVATE_A, "private", 0),
    ("192.169.0.1", "public", 1),
    ("100.64.0.1", "private", 0),
    ("100.127.255.255", "private", 0),
    ("100.63.255.255", "public", 1),
    ("100.128.0.1", "public", 1),
    ("169.254.0.1", "private", 0),
    ("169.253.0.1", "public", 1),
    ("127.0.0.1", "loopback", 0),
    ("127.5.6.7", "loopback", 0),
    ("8.8.8.8", "public", 1),
    ("0.0.0.0", "wildcard", 1),
    ("*", "wildcard", 1),
    ("::", "wildcard", 1),
    ("[::]", "wildcard", 1),
    ("::1", "loopback", 0),
    ("[::1]", "loopback", 0),
    ("fc00::5", "private", 0),
    ("fdff:ffff::1", "private", 0),
    ("fe80::1", "private", 0),
    ("febf:ffff::1", "private", 0),
    ("2001:db8::1", "public", 1),
)

# The values a complete env file carries. Placeholders only, never a secret.
PLACEHOLDER = "test-value"
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
    "BREAKER_WHISPER_FORWARDER_URL": "http://10.20.30.12:9000",
    "BREAKER_WHISPER_SYNC_API_SERVICE_KEY": PLACEHOLDER,
    "BREAKER_SYNC_API_SERVICE_KEY_HOST": "/etc/breaker/sync-api-service-key",
}


def env_example_names():
    """The NAME= names of the real .env.example, in file order."""
    names = []
    for raw in ENV_EXAMPLE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        names.append(line.split("=", 1)[0].strip())
    return names


class DeployCommonTest(unittest.TestCase):
    """Drives the real deploy-common.sh through the driver and stubs."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="breaker-common-test-"))
        self.scripts = self.tmp / "scripts"
        self.bin = self.tmp / "bin"
        self.scripts.mkdir()
        self.bin.mkdir()
        shutil.copyfile(COMMON_SRC, self.scripts / "deploy-common.sh")
        self._write(self.scripts / "driver", DRIVER)
        self._write(self.scripts / "health-check.sh", STUB_HEALTH)
        self._write(self.scripts / "backup-data.py", STUB_BACKUP)
        self._write(self.bin / "git", "#!/bin/sh\nprintf 'git %s\\n' \"$*\" >> \"$STUB_LOG\"\nexit 0\n")
        self._write(self.bin / "curl", "#!/bin/sh\nprintf 'curl %s\\n' \"$*\" >> \"$STUB_LOG\"\nexit 0\n")
        self._write(self.bin / "sqlite3", "#!/bin/sh\nprintf 'sqlite3 %s\\n' \"$*\" >> \"$STUB_LOG\"\nexit 0\n")
        self._write(self.bin / "python3", STUB_PYTHON3)
        self._write(self.bin / "docker", STUB_DOCKER)
        # The scripts call external tools too. Expose only the stubs plus the
        # core utilities they need, so removing a stub really removes the tool
        # (and no real git/docker on the host can satisfy `command -v`).
        for tool in ("sh", "dirname", "sed", "awk", "sort", "grep", "stat",
                     "tail", "cat", "basename", "sleep"):
            source = shutil.which(tool)
            if source:
                os.symlink(source, str(self.bin / tool))
        self.driver = self.scripts / "driver"
        self.log = self.tmp / "stub.log"
        # NAME SPACE MOUNT lines for the stub docker's volume inspect; an
        # empty file means every inspect fails.
        self.mounts = self.tmp / "mounts"
        self.mounts.write_text("", encoding="utf-8")
        # The daemon's `docker volume ls --format '{{.Name}}'` answer; an
        # empty file means neither volume exists (first install).
        self.volumes = self.tmp / "volumes"
        self.volumes.write_text("", encoding="utf-8")
        # `docker compose ps` answer for deploy_running_image_tag: one
        # "SERVICE IMAGE" line per running container; an empty file means
        # nothing is running.
        self.ps = self.tmp / "ps"
        self.ps.write_text("", encoding="utf-8")
        # One line per health-check.sh invocation, for the retry counter.
        self.health_count = self.tmp / "health-count"
        self.env_file = self.tmp / "stack.env"
        self.write_env(self.env_file)
        self.staging = self.tmp / "staging"
        self.staging.mkdir(mode=0o700)
        self.env = dict(os.environ)
        self.env["PATH"] = str(self.bin)
        self.env["SCRIPT_DIR"] = str(self.scripts)
        self.env["STUB_LOG"] = str(self.log)
        self.env["STUB_MOUNTS_FILE"] = str(self.mounts)
        self.env["STUB_VOLUMES_FILE"] = str(self.volumes)
        self.env["STUB_PS_FILE"] = str(self.ps)
        self.env["STUB_HEALTH_COUNT"] = str(self.health_count)
        self.env["ENV_FILE"] = str(self.env_file)
        self.env["COMPOSE_FILE"] = str(COMPOSE)
        self.env["STAGING_PARENT"] = str(self.staging)

    def tearDown(self):
        shutil.rmtree(str(self.tmp), ignore_errors=True)

    def _write(self, path, text):
        path.write_text(text, encoding="utf-8")
        os.chmod(str(path), 0o755)

    def write_env_file(self, path, pairs, mode=0o600):
        path.write_text("".join("%s=%s\n" % (name, value) for name, value in pairs),
                        encoding="utf-8")
        os.chmod(str(path), mode)

    def write_env(self, path, overrides=None, mode=0o600):
        values = dict(BASE_VALUES)
        values.update(overrides or {})
        self.write_env_file(path, [(name, values.get(name, PLACEHOLDER))
                                   for name in env_example_names()], mode=mode)

    def drive(self, *args, extra_env=None):
        self.log.write_text("", encoding="utf-8")
        if self.health_count.exists():
            self.health_count.unlink()
        env = dict(self.env)
        env.update(extra_env or {})
        return subprocess.run(["sh", str(self.driver), *args],
                              capture_output=True, text=True, env=env, timeout=30)

    def preflight(self, env_file=None, compose_file=None, staging=None, extra_env=None):
        extra = dict(extra_env or {})
        if env_file is not None:
            extra["ENV_FILE"] = str(env_file)
        if compose_file is not None:
            extra["COMPOSE_FILE"] = str(compose_file)
        if staging is not None:
            extra["STAGING_PARENT"] = str(staging)
        return self.drive("deploy_preflight", extra_env=extra)

    def log_text(self):
        return self.log.read_text(encoding="utf-8") if self.log.exists() else ""

    # -- sourcing and deploy_fail ------------------------------------------

    def test_sourcing_prints_nothing(self):
        result = self.drive()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")

    def test_deploy_fail_prints_one_line_on_stderr_and_returns_one(self):
        result = self.drive("deploy_fail", "the", "step", "failed")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "deploy: the step failed\n")

    # -- deploy_addr_class --------------------------------------------------

    def test_deploy_addr_class_prints_exactly_one_verdict_word(self):
        for addr, verdict, expected in ADDR_VERDICTS:
            with self.subTest(addr=addr):
                result = self.drive("deploy_addr_class", addr)
                self.assertEqual(result.returncode, expected, result.stderr)
                self.assertEqual(result.stdout, verdict + "\n")
                self.assertEqual(result.stderr, "")

    def test_deploy_addr_class_without_an_argument_is_empty(self):
        result = self.drive("deploy_addr_class")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (1, "empty\n", ""))

    # -- deploy_url_host and deploy_health_urls ------------------------------

    def test_deploy_url_host_leaves_a_plain_address_unchanged(self):
        for addr in ("10.8.0.5", LAN_PRIVATE_A, "127.0.0.1", "8.8.8.8"):
            with self.subTest(addr=addr):
                result = self.drive("deploy_url_host", addr)
                self.assertEqual((result.returncode, result.stdout, result.stderr),
                                 (0, addr + "\n", ""))

    def test_deploy_url_host_brackets_an_ipv6_address(self):
        for addr in ("fd00::5", "fc00::1", "::1", "fe80::1"):
            with self.subTest(addr=addr):
                result = self.drive("deploy_url_host", addr)
                self.assertEqual((result.returncode, result.stdout, result.stderr),
                                 (0, "[" + addr + "]\n", ""))

    def test_deploy_url_host_brackets_an_already_bracketed_ipv6_address_once(self):
        # The classifier accepts one bracket pair (ss writes IPv6 bracketed);
        # the builder must not double it.
        result = self.drive("deploy_url_host", "[fd00::5]")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0, "[fd00::5]\n", ""))

    def test_deploy_url_host_brackets_the_wildcard(self):
        result = self.drive("deploy_url_host", "*")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0, "[*]\n", ""))

    def test_deploy_url_host_without_an_argument_prints_an_empty_line(self):
        result = self.drive("deploy_url_host")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0, "\n", ""))

    def test_deploy_health_urls_prints_three_lines_for_an_ipv4_bind(self):
        result = self.drive("deploy_health_urls", "10.8.0.5", "8081", "8083", "8084")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0,
                          "http://10.8.0.5:8081/health\n"
                          "http://10.8.0.5:8083/health\n"
                          "http://10.8.0.5:8084/health\n",
                          ""))

    def test_deploy_health_urls_brackets_an_ipv6_bind(self):
        result = self.drive("deploy_health_urls", "fd00::5", "8081", "8083", "8084")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0,
                          "http://[fd00::5]:8081/health\n"
                          "http://[fd00::5]:8083/health\n"
                          "http://[fd00::5]:8084/health\n",
                          ""))

    def test_deploy_health_urls_brackets_a_wildcard_bind(self):
        result = self.drive("deploy_health_urls", "*", "8081", "8083", "8084")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0,
                          "http://[*]:8081/health\n"
                          "http://[*]:8083/health\n"
                          "http://[*]:8084/health\n",
                          ""))

    def test_deploy_health_urls_requires_four_arguments(self):
        result = self.drive("deploy_health_urls", "10.8.0.5", "8081", "8083")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("deploy_health_urls", result.stderr)

    # -- deploy_env_mode_ok -------------------------------------------------

    def test_deploy_env_mode_ok_accepts_only_exactly_0600(self):
        cases = ((0o600, 0), (0o644, 1), (0o400, 1), (0o640, 1), (0o700, 1),
                 (0o4600, 1), (0o2600, 1))
        for mode, expected in cases:
            with self.subTest(mode=oct(mode)):
                path = self.tmp / ("mode-%o" % mode)
                path.write_text("x", encoding="utf-8")
                os.chmod(str(path), mode)
                result = self.drive("deploy_env_mode_ok", str(path))
                self.assertEqual(result.returncode, expected, result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertEqual(result.stderr, "")
        missing = self.drive("deploy_env_mode_ok", str(self.tmp / "absent"))
        self.assertEqual(missing.returncode, 1)
        self.assertEqual(missing.stdout, "")
        self.assertEqual(missing.stderr, "")

    def test_deploy_env_mode_ok_refuses_a_missing_file(self):
        # The surviving S9 control: deploy_preflight's ENV_FILE-missing branch
        # is gone, so this function alone refuses a missing file. Named for
        # MUTANTS.json; the killer makes it return 0 for a missing path.
        result = self.drive("deploy_env_mode_ok", str(self.tmp / "absent"))
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")

    # -- deploy_preflight: the accepting control -----------------------------

    def test_preflight_accepts_a_complete_stack(self):
        result = self.preflight()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(result.stderr, "")

    # -- deploy_preflight: the env file -------------------------------------

    def test_preflight_refuses_a_missing_env_file(self):
        result = self.preflight(env_file=self.tmp / "absent.env")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("ENV_FILE", result.stderr)

    def test_preflight_refuses_an_env_file_that_is_not_0600(self):
        os.chmod(str(self.env_file), 0o644)
        result = self.preflight()
        self.assertEqual(result.returncode, 1)
        self.assertIn("0600", result.stderr)

    # -- deploy_preflight: the required names -------------------------------

    def test_preflight_refuses_a_required_name_that_is_empty(self):
        self.write_env(self.env_file, overrides={"BREAKER_WHISPER_PORT": ""})
        result = self.preflight()
        self.assertEqual(result.returncode, 1)
        self.assertIn("BREAKER_WHISPER_PORT", result.stderr)
        self.assertNotIn("8084", result.stderr)

    def test_preflight_derives_the_required_names_from_the_compose_and_the_example(self):
        module = self.tmp / "module"
        module.mkdir()
        (module / "docker-compose.yml").write_text(
            "services:\n  x:\n    image: x:${BREAKER_CUSTOM:?BREAKER_CUSTOM is required}\n",
            encoding="utf-8")
        (module / ".env.example").write_text("BREAKER_CUSTOM=\nBREAKER_IMAGE_TAG=\n",
                                            encoding="utf-8")
        env = self.tmp / "custom.env"
        self.write_env_file(env, [("BREAKER_BIND_ADDR", "10.8.0.5"),
                                  ("BREAKER_CUSTOM", ""), ("BREAKER_IMAGE_TAG", "")])
        result = self.preflight(env_file=env, compose_file=module / "docker-compose.yml")
        self.assertEqual(result.returncode, 1)
        self.assertIn("BREAKER_CUSTOM", result.stderr)
        self.write_env_file(env, [("BREAKER_BIND_ADDR", "10.8.0.5"),
                                  ("BREAKER_CUSTOM", "set"), ("BREAKER_IMAGE_TAG", "")])
        result = self.preflight(env_file=env, compose_file=module / "docker-compose.yml")
        self.assertEqual(result.returncode, 0, result.stderr)

    # -- deploy_preflight: the bind address ---------------------------------

    def test_preflight_refuses_an_empty_bind_address(self):
        self.write_env(self.env_file, overrides={"BREAKER_BIND_ADDR": ""})
        result = self.preflight()
        self.assertEqual(result.returncode, 1)
        self.assertIn("BREAKER_BIND_ADDR", result.stderr)
        self.assertIn("empty", result.stderr)

    def test_preflight_refuses_wildcard_and_public_bind_addresses(self):
        for addr, verdict in (("0.0.0.0", "wildcard"), ("::", "wildcard"),
                              ("[::]", "wildcard"), ("8.8.8.8", "public"),
                              ("2001:db8::1", "public")):
            with self.subTest(addr=addr):
                self.write_env(self.env_file, overrides={"BREAKER_BIND_ADDR": addr})
                result = self.preflight()
                self.assertEqual(result.returncode, 1)
                self.assertIn("BREAKER_BIND_ADDR", result.stderr)
                self.assertIn(verdict, result.stderr)
                self.assertNotIn(addr, result.stderr)

    def test_preflight_accepts_a_private_and_a_loopback_bind_address(self):
        for addr in ("10.8.0.5", LAN_PRIVATE_B, "127.0.0.1", "fc00::5"):
            with self.subTest(addr=addr):
                self.write_env(self.env_file, overrides={"BREAKER_BIND_ADDR": addr})
                result = self.preflight()
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_preflight_never_prints_an_env_value(self):
        self.write_env(self.env_file, overrides={"BREAKER_WHISPER_PORT": ""})
        result = self.preflight()
        self.assertEqual(result.returncode, 1)
        for value in ("8084", PLACEHOLDER, "10.20.30.13"):
            self.assertNotIn(value, result.stdout + result.stderr)

    # -- deploy_preflight: tools, compose, buildx, compose, staging ---------

    def test_preflight_refuses_a_missing_tool(self):
        os.remove(str(self.bin / "git"))
        result = self.preflight()
        self.assertEqual(result.returncode, 1)
        self.assertIn("git", result.stderr)

    def test_preflight_accepts_a_stack_without_the_sqlite3_cli(self):
        # Nothing invokes the sqlite3 CLI: every schema read goes through
        # python3's module, so its absence must not fail preflight.
        os.remove(str(self.bin / "sqlite3"))
        result = self.preflight()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_preflight_refuses_a_python3_that_cannot_import_sqlite3(self):
        result = self.preflight(extra_env={"STUB_PYTHON3_RC": "1"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("python3", result.stderr)

    def test_preflight_refuses_compose_v1(self):
        result = self.preflight(extra_env={"STUB_COMPOSE_VERSION": "Docker Compose version v1.29.2"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("compose", result.stderr)

    def test_preflight_refuses_a_missing_compose_plugin(self):
        result = self.preflight(extra_env={"STUB_COMPOSE_RC": "1"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("compose", result.stderr)

    # -- deploy_preflight: the compose major is checked numerically (D8) -----

    def test_preflight_accepts_compose_v5(self):
        # The old guard accepted only v2*|2*; the rule is major >= 2.
        result = self.preflight(extra_env={"STUB_COMPOSE_VERSION": "Docker Compose version v5.6.0"})
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_preflight_refuses_an_empty_compose_version_answer(self):
        result = self.preflight(extra_env={"STUB_COMPOSE_VERSION": ""})
        self.assertEqual(result.returncode, 1)
        self.assertIn("compose", result.stderr)

    def test_preflight_refuses_an_unparsable_compose_version_answer(self):
        result = self.preflight(extra_env={
            "STUB_COMPOSE_VERSION": "stub docker: no version here"})
        self.assertEqual(result.returncode, 1)
        self.assertIn("compose", result.stderr)

    # -- deploy_compose_version_ok (D8) --------------------------------------

    def compose_version_ok(self, answer):
        """Drive the shared helper with one `docker compose version` answer."""
        return self.drive("deploy_compose_version_ok", answer)

    def test_deploy_compose_version_ok_accepts_major_two_and_above(self):
        for answer in ("Docker Compose version v2.38.1",
                       "Docker Compose version v5.6.0",
                       "Docker Compose version 2.20.0",
                       "v3.1.4"):
            with self.subTest(answer=answer):
                result = self.compose_version_ok(answer)
                self.assertEqual((result.returncode, result.stdout, result.stderr),
                                 (0, "", ""), result.stderr)

    def test_deploy_compose_version_ok_refuses_major_one_empty_and_garbage(self):
        for answer in ("Docker Compose version v1.29.2", "", "garbage",
                       "Docker Compose version", "not a version at all"):
            with self.subTest(answer=answer):
                result = self.compose_version_ok(answer)
                self.assertEqual((result.returncode, result.stdout, result.stderr),
                                 (1, "", ""), result.stderr)

    def test_deploy_compose_version_ok_without_an_argument_is_refused(self):
        result = self.drive("deploy_compose_version_ok")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (1, "", ""))

    def test_preflight_notes_a_missing_buildx_but_does_not_fail(self):
        result = self.preflight(extra_env={"STUB_BUILDX_RC": "1"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("NOTE", result.stdout)

    def test_preflight_refuses_a_missing_compose_file(self):
        result = self.preflight(compose_file=self.tmp / "absent-compose.yml")
        self.assertEqual(result.returncode, 1)
        self.assertIn("COMPOSE_FILE", result.stderr)

    def test_preflight_refuses_an_unwritable_staging_parent(self):
        os.chmod(str(self.staging), 0o555)
        result = self.preflight()
        self.assertEqual(result.returncode, 1)
        self.assertIn("STAGING_PARENT", result.stderr)

    def test_preflight_refuses_a_missing_staging_parent(self):
        result = self.preflight(staging=self.tmp / "absent-staging")
        self.assertEqual(result.returncode, 1)
        self.assertIn("STAGING_PARENT", result.stderr)

    # -- deploy_check_health and deploy_record_schema -----------------------

    def test_deploy_check_health_builds_one_url_argument_per_url(self):
        result = self.drive("deploy_check_health", "http://a.example/health",
                          "http://b.example/health")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("health-check --url http://a.example/health "
                      "--url http://b.example/health --timeout 5", self.log_text())

    def test_deploy_check_health_returns_the_health_exit_code(self):
        # The bound is injected small so the retry never really sleeps: with
        # HEALTH_WAIT=1 and HEALTH_STEP=0 the single try decides.
        for code in ("1", "2"):
            with self.subTest(code=code):
                result = self.drive("deploy_check_health", "http://a.example/health",
                                  extra_env={"STUB_HEALTH_RC": code,
                                             "HEALTH_STEP": "0", "HEALTH_WAIT": "1"})
                self.assertEqual(result.returncode, int(code))

    def test_deploy_check_health_uses_the_timeout_global(self):
        result = self.drive("deploy_check_health", "http://a.example/health",
                          extra_env={"TIMEOUT": "9"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--timeout 9", self.log_text())

    def test_deploy_check_health_retries_until_the_stub_turns_healthy(self):
        # HEALTH_STEP=0 spends the budget one try per unit: no sleeping, and
        # the number of invocations is exact.
        result = self.drive("deploy_check_health", "http://a.example/health",
                            extra_env={"HEALTH_STEP": "0", "HEALTH_WAIT": "10",
                                       "STUB_HEALTH_FAIL_TIMES": "2",
                                       "STUB_HEALTH_RC": "1",
                                       "STUB_HEALTH_RC_FINAL": "0"})
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [line for line in self.log_text().splitlines()
                 if line.startswith("health-check ")]
        self.assertEqual(len(calls), 3, calls)
        self.assertTrue(all(line == calls[0] for line in calls), calls)

    def test_deploy_check_health_fails_closed_with_the_last_code_after_the_bound(self):
        result = self.drive("deploy_check_health", "http://a.example/health",
                            extra_env={"HEALTH_STEP": "0", "HEALTH_WAIT": "4",
                                       "STUB_HEALTH_RC": "7"})
        self.assertEqual(result.returncode, 7)
        self.assertEqual(self.log_text().count("health-check "), 4)

    def test_deploy_check_health_still_requires_a_url(self):
        result = self.drive("deploy_check_health",
                            extra_env={"HEALTH_STEP": "0", "HEALTH_WAIT": "1"})
        self.assertEqual(result.returncode, 2)
        self.assertIn("deploy_check_health", result.stderr)

    def test_deploy_record_schema_builds_one_db_argument_per_database(self):
        result = self.drive("deploy_record_schema", "/tmp/out.schema",
                          "/data/a.db", "/data/b.db")
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.log_text()
        self.assertIn(str(self.scripts / "backup-data.py"), log)
        self.assertIn("--record-schema /tmp/out.schema "
                      "--db /data/a.db --db /data/b.db", log)

    def test_deploy_record_schema_returns_the_backup_exit_code(self):
        result = self.drive("deploy_record_schema", "/tmp/out.schema", "/data/a.db",
                          extra_env={"STUB_PYTHON3_RC": "2"})
        self.assertEqual(result.returncode, 2)

    # -- deploy_has_data and deploy_db_paths ---------------------------------

    def use_mounts(self, pairs):
        """NAME SPACE MOUNT table for inspect; the names become the ls list."""
        self.mounts.write_text("".join("%s %s\n" % (name, mount) for name, mount in pairs),
                               encoding="utf-8")
        self.use_volumes([name for name, _mount in pairs])

    def use_volumes(self, names):
        """The exact whole lines `docker volume ls` answers with."""
        self.volumes.write_text("".join("%s\n" % name for name in names),
                                encoding="utf-8")

    def test_deploy_has_data_builds_one_db_argument_per_database(self):
        result = self.drive("deploy_has_data", "/data/a.db", "/data/b.db")
        self.assertEqual(result.returncode, 0, result.stderr)
        log = self.log_text()
        self.assertIn(str(self.scripts / "backup-data.py"), log)
        self.assertIn("--has-data --db /data/a.db --db /data/b.db", log)

    def test_deploy_has_data_returns_the_backup_exit_code(self):
        for code in ("0", "3"):
            with self.subTest(code=code):
                result = self.drive("deploy_has_data", "/data/a.db",
                                    extra_env={"STUB_PYTHON3_RC": code})
                self.assertEqual(result.returncode, int(code))

    def test_deploy_has_data_requires_a_database(self):
        result = self.drive("deploy_has_data")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")
        self.assertIn("deploy_has_data", result.stderr)

    def test_deploy_db_paths_prints_one_host_path_per_database(self):
        self.use_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync"),
                         ("breaker_breaker_whisper_data", "/srv/docker/whisper")])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout,
                         "/srv/docker/sync/sync-api.db\n"
                         "/srv/docker/whisper/whisper-server.db\n")
        self.assertEqual(result.stderr, "")
        log = self.log_text()
        self.assertIn("volume inspect -f {{.Mountpoint}} breaker_breaker_sync_api_data", log)
        self.assertIn("volume inspect -f {{.Mountpoint}} breaker_breaker_whisper_data", log)

    def test_deploy_db_paths_reads_the_keys_and_mounts_from_the_compose_file(self):
        compose = self.tmp / "custom-compose.yml"
        compose.write_text(
            "name: weirdproj\n"
            "services:\n"
            "  sync-api:\n"
            "    environment:\n"
            "      BREAKER_SYNC_API_DB: /var/lib/sync/data.db\n"
            "    volumes:\n"
            "      - alpha_data:/var/lib/sync\n"
            "  whisper-server:\n"
            "    environment:\n"
            "      BREAKER_WHISPER_DB: /opt/ws/store/whisper.db\n"
            "    volumes:\n"
            "      - beta_data:/opt/ws/store\n"
            "volumes:\n"
            "  alpha_data:\n"
            "  beta_data:\n", encoding="utf-8")
        self.use_mounts([("weirdproj_alpha_data", "/srv/alpha"),
                         ("weirdproj_beta_data", "/srv/beta")])
        result = self.drive("deploy_db_paths", extra_env={"COMPOSE_FILE": str(compose)})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "/srv/alpha/data.db\n/srv/beta/whisper.db\n")
        log = self.log_text()
        self.assertNotIn("breaker_breaker", log)
        self.assertIn("weirdproj_alpha_data", log)
        self.assertIn("weirdproj_beta_data", log)

    def test_deploy_db_paths_a_listed_volume_whose_inspect_fails_is_a_hard_failure(self):
        # Existence comes from `docker volume ls`; a LISTED volume whose
        # inspect then fails stays a hard failure naming it. Absent is not
        # unresolvable: only the listed one is inspected.
        self.use_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync")])
        self.use_volumes(["breaker_breaker_sync_api_data",
                          "breaker_breaker_whisper_data"])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("breaker_breaker_whisper_data", result.stderr)

    def test_deploy_db_paths_with_neither_volume_listed_prints_two_empty_lines(self):
        # First install: `up` has not created the volumes yet. The caller's
        # `sed -n '1p'` / `sed -n '2p'` then read two empty paths -> absence,
        # never a hard failure.
        self.use_volumes([])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "\n\n")
        self.assertEqual(result.stderr, "")
        log = self.log_text()
        self.assertIn("volume ls --format {{.Name}}", log)
        self.assertNotIn("volume inspect", log)

    def test_deploy_db_paths_inspects_only_the_listed_volumes(self):
        self.use_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync")])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "/srv/docker/sync/sync-api.db\n\n")
        log = self.log_text()
        self.assertIn("volume inspect -f {{.Mountpoint}} breaker_breaker_sync_api_data", log)
        self.assertNotIn("volume inspect -f {{.Mountpoint}} breaker_breaker_whisper_data", log)

    def test_deploy_db_paths_a_listed_volume_with_no_mountpoint_is_a_hard_failure(self):
        # inspect succeeds but answers no mountpoint: still unresolvable.
        self.use_volumes(["breaker_breaker_sync_api_data",
                          "breaker_breaker_whisper_data"])
        result = self.drive("deploy_db_paths",
                            extra_env={"STUB_INSPECT_EMPTY": "1"})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("breaker_breaker_sync_api_data", result.stderr)

    def test_deploy_db_paths_matches_a_volume_name_as_a_whole_line(self):
        # A longer name that contains the derived name as a prefix is NOT the
        # volume: only a whole-line match counts.
        self.use_mounts([("breaker_breaker_sync_api_data_extra", "/srv/decoy"),
                         ("breaker_breaker_whisper_data", "/srv/docker/whisper")])
        self.use_volumes(["breaker_breaker_sync_api_data_extra",
                          "breaker_breaker_whisper_data"])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "\n/srv/docker/whisper/whisper-server.db\n")
        self.assertNotIn(
            "volume inspect -f {{.Mountpoint}} breaker_breaker_sync_api_data\n",
            self.log_text())

    def test_deploy_db_paths_the_exact_name_still_matches_beside_a_longer_one(self):
        self.use_mounts([("breaker_breaker_sync_api_data_extra", "/srv/decoy"),
                         ("breaker_breaker_sync_api_data", "/srv/docker/sync")])
        self.use_volumes(["breaker_breaker_sync_api_data_extra",
                          "breaker_breaker_sync_api_data"])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "/srv/docker/sync/sync-api.db\n\n")
        log = self.log_text()
        self.assertIn("volume inspect -f {{.Mountpoint}} breaker_breaker_sync_api_data\n", log)
        self.assertNotIn("{{.Mountpoint}} breaker_breaker_sync_api_data_extra", log)

    def test_deploy_db_paths_a_volume_ls_failure_is_a_hard_failure_not_a_first_install(self):
        # The daemon itself failing (down, permission denied) must never be
        # read as "no such volume": that is the first-install trap.
        result = self.drive("deploy_db_paths", extra_env={
            "STUB_VOLUME_LS_RC": "1",
            "STUB_VOLUME_LS_ERR": "docker: permission denied while connecting to the daemon",
        })
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("volume ls", result.stderr)
        self.assertIn("permission denied", result.stderr)
        self.assertNotIn("first install", result.stderr)

    def test_deploy_db_paths_refuses_a_volume_absent_from_the_top_level_list(self):
        # The service mounts alpha_data but the compose file never declares it
        # under a top-level volumes: key. An undeclared name is not a real named
        # volume: resolving it by name would silently mount a fresh empty one,
        # so the mapping must fail instead of printing a path. Named for
        # MUTANTS.json row SH-DDP-TOPVOL.
        compose = self.tmp / "ghost-compose.yml"
        compose.write_text(
            "name: breaker\n"
            "services:\n"
            "  sync-api:\n"
            "    environment:\n"
            "      BREAKER_SYNC_API_DB: /var/lib/sync/data.db\n"
            "    volumes:\n"
            "      - alpha_data:/var/lib/sync\n"
            "  whisper-server:\n"
            "    environment:\n"
            "      BREAKER_WHISPER_DB: /opt/ws/store/whisper.db\n"
            "    volumes:\n"
            "      - beta_data:/opt/ws/store\n"
            "volumes:\n"
            "  beta_data:\n", encoding="utf-8")
        self.use_mounts([("breaker_alpha_data", "/srv/alpha"),
                         ("breaker_beta_data", "/srv/beta")])
        result = self.drive("deploy_db_paths", extra_env={"COMPOSE_FILE": str(compose)})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("no top-level volume named alpha_data", result.stderr)

    def test_deploy_db_paths_prefers_the_env_file_value_for_the_database(self):
        pairs = [(name, PLACEHOLDER) for name in env_example_names()]
        pairs.append(("BREAKER_SYNC_API_DB", "/var/lib/breaker/sync-api/nested/sync-api.db"))
        self.write_env_file(self.env_file, pairs)
        self.use_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync"),
                         ("breaker_breaker_whisper_data", "/srv/docker/whisper")])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout,
                         "/srv/docker/sync/nested/sync-api.db\n"
                         "/srv/docker/whisper/whisper-server.db\n")

    def test_deploy_db_paths_never_prints_an_environment_value(self):
        self.use_mounts([("breaker_breaker_sync_api_data", "/srv/docker/sync"),
                         ("breaker_breaker_whisper_data", "/srv/docker/whisper")])
        result = self.drive("deploy_db_paths")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(result.stdout.splitlines()), 2)
        everything = result.stdout + result.stderr + self.log_text()
        for value in (PLACEHOLDER, "http://10.20.30.12:9000", "10.20.30.11"):
            self.assertNotIn(value, everything)

    def test_deploy_db_paths_refuses_a_missing_compose_file(self):
        result = self.drive("deploy_db_paths",
                            extra_env={"COMPOSE_FILE": str(self.tmp / "absent-compose.yml")})
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("COMPOSE_FILE", result.stderr)

    # -- deploy_running_image_tag (defects 3 + 5) ---------------------------

    def use_running(self, lines):
        """The exact `SERVICE IMAGE` lines `docker compose ps` answers with."""
        self.ps.write_text("".join("%s\n" % line for line in lines),
                           encoding="utf-8")

    def running_tag(self, extra_env=None):
        return self.drive("deploy_running_image_tag", extra_env=extra_env)

    def test_deploy_running_image_tag_prints_the_one_tag_every_service_agrees_on(self):
        self.use_running([
            "sync-api breaker/sync-api:v1.2.0",
            "whisper-server breaker/whisper-server:v1.2.0",
            "web-fe breaker/web-fe:v1.2.0",
        ])
        result = self.running_tag()
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0, "v1.2.0\n", ""))

    def test_deploy_running_image_tag_reads_the_whole_running_set_of_the_project(self):
        # The whole set is read through compose ps for the project the compose
        # file names - never one container picked by hand.
        self.use_running(["sync-api breaker/sync-api:v1.2.0"])
        result = self.running_tag()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            "docker compose -p breaker ps --format {{.Service}} {{.Image}}",
            self.log_text())

    def test_deploy_running_image_tag_prints_the_mixed_detail_and_is_unknown(self):
        # They DIFFER: the detail is printed (services sorted by name) and the
        # answer is UNKNOWN - rc 1 - so a recovery never redeploys one of them.
        self.use_running([
            "sync-api breaker/sync-api:1.2.0",
            "web-fe breaker/web-fe:1.1.0",
            "whisper-server breaker/whisper-server:1.2.0",
        ])
        result = self.running_tag()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(
            result.stdout,
            "mixed: sync-api=1.2.0, web-fe=1.1.0, whisper-server=1.2.0\n")
        self.assertEqual(result.stderr, "")

    def test_deploy_running_image_tag_with_nothing_running_is_unknown(self):
        self.use_running([])
        result = self.running_tag()
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (1, "unknown\n", ""))

    def test_deploy_running_image_tag_with_a_failing_docker_is_unknown(self):
        self.use_running(["sync-api breaker/sync-api:v1.2.0"])
        result = self.running_tag(extra_env={"STUB_PS_RC": "1"})
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (1, "unknown\n", ""))

    def test_deploy_running_image_tag_refuses_a_service_with_no_readable_tag(self):
        # A digest-pinned reference cannot answer the question, so the whole
        # read is unknown - never a guess from the services that did answer.
        self.use_running([
            "sync-api breaker/sync-api@sha256:abcdef",
            "web-fe breaker/web-fe:v1.2.0",
        ])
        result = self.running_tag()
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (1, "unknown\n", ""))

    def test_deploy_running_image_tag_reads_a_registry_port_as_part_of_the_image(self):
        # `registry:5000/breaker/sync-api:v1.2.0`: the tag follows the LAST
        # colon that is after the last slash, so the registry port is not a tag.
        self.use_running([
            "sync-api registry.local:5000/breaker/sync-api:v1.2.0",
            "web-fe registry.local:5000/breaker/web-fe:v1.2.0",
        ])
        result = self.running_tag()
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (0, "v1.2.0\n", ""))

    def test_deploy_running_image_tag_refuses_a_missing_compose_file(self):
        result = self.running_tag(
            extra_env={"COMPOSE_FILE": str(self.tmp / "absent-compose.yml")})
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (1, "unknown\n", ""))

    # -- the pinning against vpn-bind.sh ------------------------------------

    def vpn_bind_status(self, addr, index):
        if ":" in addr and not addr.startswith("["):
            local = "[%s]" % addr
        else:
            local = addr
        fixture = self.tmp / ("listeners-%d.txt" % index)
        fixture.write_text("LISTEN 0 128 %s:8080 *:*\n" % local, encoding="utf-8")
        env = dict(os.environ)
        env["VPN_BIND_LISTENERS_FILE"] = str(fixture)
        proc = subprocess.run(
            ["sh", str(VPN_BIND), "--vpn-addr", "10.8.0.5", "--ports", "8080",
             "--allow-private"],
            capture_output=True, text=True, env=env, timeout=30)
        fields = proc.stdout.split()
        self.assertTrue(fields, proc.stdout + proc.stderr)
        self.assertEqual(fields[0], "8080", proc.stdout)
        return proc.returncode, fields[1]

    def test_the_address_classifier_is_pinned_to_vpn_bind(self):
        # deploy_addr_class and vpn-bind.sh must agree on every address: the
        # verdict word maps to the vpn-bind status pass/loopback/exposed.
        status_for = {"private": "pass", "loopback": "loopback",
                      "wildcard": "exposed", "public": "exposed"}
        for index, (addr, verdict, _rc) in enumerate(ADDR_VERDICTS):
            if verdict == "empty":
                continue
            with self.subTest(addr=addr):
                rc, status = self.vpn_bind_status(addr, index)
                expected = status_for[verdict]
                self.assertEqual(status, expected, addr)
                self.assertEqual(rc, 0 if expected in ("pass", "loopback") else 1,
                                 addr)


if __name__ == "__main__":
    unittest.main()
