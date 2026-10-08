#!/usr/bin/env python3
"""Tests for tools/gate.py -- fakes only.

This file runs inside the gate's own unit tier, so it must never call real
Gradle or the real check_repo: every fixture is a temp-dir fake repo with its
own tools/check_repo.py stub and its own ./gradlew stub.
"""

import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
GATE_PATH = os.path.join(ROOT, "tools", "gate.py")

spec = importlib.util.spec_from_file_location("gate_under_test", GATE_PATH)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

JUNIT_XML = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<testsuite name="FakeSuite" tests="2" failures="0" errors="0" skipped="0">'
    '<testcase name="t1" classname="FakeSuite" time="0.01"/>'
    '<testcase name="t2" classname="FakeSuite" time="0.01"/>'
    "</testsuite>\n"
)

CONTRACT_TEST = (
    "import unittest\n\n"
    "class T(unittest.TestCase):\n"
    "    def test_a(self):\n"
    "        self.assertTrue(True)\n"
)
UNIT_TEST = (
    "import unittest\n\n"
    "class U(unittest.TestCase):\n"
    "    def test_b(self):\n"
    "        self.assertEqual(1, 1)\n"
)
EMPTY_TEST = "x = 1\n"  # a test_*.py module with zero test methods


def _write(path, text, mode=0o644):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)
    os.chmod(path, mode)


def _git(root, *args):
    subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t"] + list(args),
                   cwd=root, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)


GRADLEW_FRESH = (
    "#!/bin/sh\n"
    "for d in $(find . -type d -path '*/src/test'); do\n"
    "  out=\"${d#./}/../../build/test-results/test\"\n"
    "  mkdir -p \"$out\"\n"
    "  cat .xmltpl > \"$out/TEST-fake.xml\"\n"
    "done\n"
    "exit 0\n"
)
GRADLEW_NOOP = "#!/bin/sh\nexit 0\n"
# writes a FRESH XML that records one failure, but exits 0 (a passing build
# whose suite actually failed -- e.g. ignoreFailures / a retry plugin)
GRADLEW_FAIL = (
    "#!/bin/sh\n"
    "for d in $(find . -type d -path '*/src/test'); do\n"
    "  out=\"${d#./}/../../build/test-results/test\"\n"
    "  mkdir -p \"$out\"\n"
    "  sed 's/failures=\"0\"/failures=\"1\"/' .xmltpl > \"$out/TEST-fake.xml\"\n"
    "done\n"
    "exit 0\n"
)
# leaves a marker, so a test can prove ./gradlew was never called
GRADLEW_MARK = "#!/bin/sh\n: > .gradlew_called\nexit 0\n"
# records the arguments it was called with
GRADLEW_ARGS = "#!/bin/sh\necho \"$@\" > .gradlew_args\nexit 0\n"
# step_gradle probes `java -version` before ./gradlew; the tests put this stub
# first on PATH so they never depend on a JDK being installed on the host
JAVA_STUB = "#!/bin/sh\necho 'openjdk version \"17\" (test stub)'\nexit 0\n"


class FakeRepo:
    """A temp-dir repo that satisfies every gate step with fakes.

    Layout: project a/b has src/test sources (must produce fresh XML);
    project x has src/main only (table row marked "no test sources").
    """

    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="gatefake")
        r = self.root
        _write(os.path.join(r, "settings.gradle.kts"),
               'rootProject.name = "fake"\ninclude(":a:b")\ninclude(":x")\n')
        _write(os.path.join(r, "gradlew"), GRADLEW_FRESH, mode=0o755)
        _write(os.path.join(r, ".xmltpl"), JUNIT_XML)
        _write(os.path.join(r, "tools", "check_repo.py"),
               "import sys\nprint('REPO CONSISTENT: fake')\nsys.exit(0)\n")
        _write(os.path.join(r, "tests", "contract", "test_ok.py"), CONTRACT_TEST)
        _write(os.path.join(r, "tests", "unit", "shared", "ui-tokens", "test_ok.py"),
               UNIT_TEST)
        _write(os.path.join(r, "a", "b", "src", "main", "Main.kt"), "class Main\n")
        _write(os.path.join(r, "a", "b", "src", "test", "MainTest.kt"), "class MainTest\n")
        _write(os.path.join(r, "x", "src", "main", "X.kt"), "class X\n")
        _git(r, "init", "-q")
        _git(r, "add", "-A")
        _git(r, "commit", "-qm", "fake base")

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


class GateTestBase(unittest.TestCase):
    def setUp(self):
        self.repo = FakeRepo()
        # A stub java first on PATH: no test may depend on the host's JDK.
        # TestMissingJava replaces PATH with one that has no java at all.
        self.bindir = tempfile.mkdtemp(prefix="gatebin")
        _write(os.path.join(self.bindir, "java"), JAVA_STUB, mode=0o755)
        self.old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = self.bindir + os.pathsep + self.old_path

    def tearDown(self):
        os.environ["PATH"] = self.old_path
        shutil.rmtree(self.bindir, ignore_errors=True)
        self.repo.close()

    def run_gate(self, *args, **kw):
        return gate.run_gate(self.repo.root, *args, **kw)


class TestGreen(GateTestBase):
    def test_full_gate_green(self):
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 0, "expected GREEN, got:\n" + text)
        self.assertEqual(lines[-1], "GATE: GREEN")
        # per-sub-module table lists both projects, x marked no test sources
        self.assertIn("a/b", text)
        self.assertIn("(no test sources)", text)
        # every test file is reported with its step
        self.assertIn("tests/contract/test_ok.py", text)
        self.assertIn("tests/unit/shared/ui-tokens/test_ok.py", text)


class TestPlantedNul(GateTestBase):
    def test_planted_nul_red(self):
        target = os.path.join(self.repo.root, "a", "b", "src", "main", "Main.kt")
        with open(target, "ab") as fh:
            fh.write(b"\x00")
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1)
        self.assertIn("NUL byte in a/b/src/main/Main.kt", text)
        self.assertIn("GATE: RED", text)
        # restore -> clean again
        _write(target, "class Main\n")
        code2, lines2 = self.run_gate()
        self.assertEqual(code2, 0, "\n".join(lines2))


class TestPlantedOrphan(GateTestBase):
    def test_planted_orphan_red(self):
        _write(os.path.join(self.repo.root, "tests", "integration", "test_orphan.py"),
               CONTRACT_TEST)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1)
        self.assertIn("tests/integration/test_orphan.py is loaded by no step", text)
        self.assertIn("GATE: RED", text)


class TestContractSubpackageOrphan(GateTestBase):
    def test_contract_subpackage_orphan(self):
        # a test under tests/contract/sub with NO __init__.py is not loaded
        # by `discover -s tests/contract` -> it must be reported as an orphan
        _write(os.path.join(self.repo.root, "tests", "contract", "sub", "test_hidden.py"),
               CONTRACT_TEST)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("tests/contract/sub/test_hidden.py is loaded by no step", text)


class TestDoubleReach(GateTestBase):
    def test_double_reach_red(self):
        # parent folder with its own test + a PACKAGE child with a test:
        # the child's file is loaded by both discover runs -> RED naming it
        base = os.path.join(self.repo.root, "tests", "unit", "parent")
        _write(os.path.join(base, "test_parent.py"), UNIT_TEST)
        _write(os.path.join(base, "child", "__init__.py"), "")
        _write(os.path.join(base, "child", "test_child.py"), UNIT_TEST)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("tests/unit/parent/child/test_child.py is loaded by 2 steps", text)
        self.assertIn("GATE: RED", text)


class TestZeroTestFolder(GateTestBase):
    def test_zero_test_folder_red(self):
        _write(os.path.join(self.repo.root, "tests", "unit", "empty", "test_empty.py"),
               EMPTY_TEST)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        # the VERDICT is the last line, and the reason names the folder
        self.assertTrue(lines[-1].startswith("GATE: RED"), lines[-1])
        self.assertIn("tests/unit/empty holds test_*.py but ran 0 tests", text)


class TestNoSourceGradle(GateTestBase):
    def test_no_source_red_when_xml_missing(self):
        # a/b has src/test sources; make the fake gradlew produce no XML
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_NOOP, mode=0o755)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("NO-SOURCE: a/b has test sources but no fresh JUnit XML", text)

    def test_stale_xml_red(self):
        # a passing XML written before the run, and a gradlew that writes
        # nothing: the XML is unchanged since before the run -> stale -> NO-SOURCE
        results = os.path.join(self.repo.root, "a", "b", "build",
                               "test-results", "test")
        os.makedirs(results, exist_ok=True)
        _write(os.path.join(results, "TEST-fake.xml"), JUNIT_XML)
        time.sleep(0.3)  # the XML is 0.3s old when the run starts
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_NOOP, mode=0o755)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("NO-SOURCE: a/b has test sources but no fresh JUnit XML", text)

    def test_rewritten_xml_is_fresh(self):
        # an XML that existed before the run and was REWRITTEN by the build
        # (its mtime changed) is fresh -> not NO-SOURCE -> GREEN
        results = os.path.join(self.repo.root, "a", "b", "build",
                               "test-results", "test")
        os.makedirs(results, exist_ok=True)
        _write(os.path.join(results, "TEST-fake.xml"), JUNIT_XML)
        time.sleep(0.05)
        # GRADLEW_FRESH rewrites the XML for every src/test dir (mtime changes)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 0, text)
        self.assertNotIn("NO-SOURCE: a/b", text)


class TestFailingStepNotMasked(GateTestBase):
    def test_check_repo_failure_reaches_verdict(self):
        _write(os.path.join(self.repo.root, "tools", "check_repo.py"),
               "import sys\nprint('REPO INCONSISTENT - 1 problem(s):')\n"
               "print('  - planted')\nsys.exit(1)\n")
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1)
        self.assertIn("check_repo exited 1", text)
        # the verdict is the LAST line, and it is a RED verdict
        self.assertTrue(lines[-1].startswith("GATE: RED"), lines[-1])


class TestPartialSkip(GateTestBase):
    def test_skip_gradle_partial(self):
        code, lines = self.run_gate(skip_gradle=True)
        text = "\n".join(lines)
        self.assertNotEqual(code, 0, "partial must never read as a pass")
        self.assertEqual(lines[-1], "GATE: PARTIAL (gradle skipped)")
        self.assertIn("step 4 gradle: SKIPPED", text)


# -- round 2: one test per Z-item (each watched RED for the right reason) ----

class TestSkipGradleRedWins(GateTestBase):
    """Z1: a RED reason must win over PARTIAL when --skip-gradle is set."""

    def test_skip_gradle_with_planted_red_reasons(self):
        # --skip-gradle plus a real RED (check_repo failed): the verdict is
        # RED with the reason, never PARTIAL
        _write(os.path.join(self.repo.root, "tools", "check_repo.py"),
               "import sys\nprint('REPO INCONSISTENT - 1 problem(s):')\n"
               "print('  - planted')\nsys.exit(1)\n")
        code, lines = self.run_gate(skip_gradle=True)
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertTrue(lines[-1].startswith("GATE: RED"), lines[-1])
        self.assertIn("check_repo exited 1", text)
        self.assertNotIn("GATE: PARTIAL", text)


class TestTestOnlyProject(GateTestBase):
    """Z2: a project with test sources but no main sources must be gated."""

    def test_test_only_project_no_source_red(self):
        # a project with ONLY src/test (no src/main) and no fresh XML -> NO-SOURCE
        _write(os.path.join(self.repo.root, "settings.gradle.kts"),
               'rootProject.name = "fake"\ninclude(":t")\n')
        _write(os.path.join(self.repo.root, "t", "src", "test", "TTest.kt"),
               "class TTest\n")
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_NOOP, mode=0o755)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("NO-SOURCE: t has test sources but no fresh JUnit XML", text)


class TestRecordedFailures(GateTestBase):
    """Z3: recorded failures in a fresh XML must be a RED reason."""

    def test_recorded_failures_red(self):
        # a fresh XML with failures=1 and a passing gradle exit (0) -> RED
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_FAIL, mode=0o755)
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("a/b has fail=1", text)
        self.assertIn("GATE: RED", text)


class TestMissingJava(GateTestBase):
    """Z4: a missing java is a RED reason, never a traceback."""

    def test_missing_java_red_not_traceback(self):
        # a PATH without java: the gate reports RED (naming java), not a traceback
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_MARK, mode=0o755)
        bindir = tempfile.mkdtemp(prefix="gatejava")
        old = None
        try:
            gitp = shutil.which("git")
            if gitp:
                os.symlink(gitp, os.path.join(bindir, "git"))
            old = os.environ.get("PATH")
            os.environ["PATH"] = bindir
            code, lines = self.run_gate()
            text = "\n".join(lines)
            self.assertEqual(code, 1, text)
            self.assertIn("GATE: RED", text)
            self.assertIn("java", text.lower())
            # the java probe stops the step: ./gradlew is never called
            self.assertFalse(
                os.path.exists(os.path.join(self.repo.root, ".gradlew_called")),
                "./gradlew ran although java is missing")
        finally:
            if old is not None:
                os.environ["PATH"] = old
            shutil.rmtree(bindir, ignore_errors=True)


class TestMaxWorkersPassthrough(GateTestBase):
    """--max-workers reaches ./gradlew; accepting it and dropping it is a bug."""

    def test_max_workers_reaches_gradlew(self):
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_ARGS, mode=0o755)
        self.run_gate(max_workers=3)
        with open(os.path.join(self.repo.root, ".gradlew_args")) as fh:
            args = fh.read().split()
        self.assertIn("--max-workers=3", args)


class TestRunMissingExecutable(unittest.TestCase):
    """Z4 (unit level): _run turns a missing executable into 127 + a message."""

    def test_run_missing_executable_returns_127(self):
        root = tempfile.mkdtemp(prefix="gatecmd")
        try:
            code, msg = gate._run(["definitely-not-a-real-cmd-xyz"], cwd=root)
            self.assertEqual(code, 127)
            self.assertIn("definitely-not-a-real-cmd-xyz: not found", msg)
        finally:
            shutil.rmtree(root, ignore_errors=True)


class TestContractZeroTests(GateTestBase):
    """Z6: the contract tier re-checks count == 0, like the unit tier."""

    def test_contract_zero_tests_red(self):
        # a tests/contract holding a test_*.py with NO TestCase must be RED
        # (discover exits 0 on "Ran 0 tests" on some Pythons)
        _write(os.path.join(self.repo.root, "tests", "contract", "test_ok.py"),
               EMPTY_TEST)
        code, lines = self.run_gate(skip_gradle=True)
        text = "\n".join(lines)
        self.assertEqual(code, 1, text)
        self.assertIn("tests/contract holds test_*.py but ran 0 tests", text)
        self.assertIn("GATE: RED", text)


class TestGitLsFilesFailure(unittest.TestCase):
    """Z7: a git ls-files failure has its own reason, not 'NUL byte found'."""

    def test_git_lsfiles_failure_reason(self):
        # a root that is NOT a git repo: git ls-files fails -> its own reason,
        # not the mislabel "NUL byte found". (full gate, so the verdict is RED
        # and the reason is printed; --skip-gradle would hide it as PARTIAL)
        root = tempfile.mkdtemp(prefix="gategit")
        try:
            _write(os.path.join(root, "tools", "check_repo.py"),
                   "import sys\nprint('ok')\nsys.exit(0)\n")
            _write(os.path.join(root, "tests", "contract", "test_ok.py"),
                   CONTRACT_TEST)
            _write(os.path.join(root, "tests", "unit", "shared", "test_ok.py"),
                   UNIT_TEST)
            _write(os.path.join(root, "settings.gradle.kts"),
                   'rootProject.name = "fake"\n')
            _write(os.path.join(root, "gradlew"), GRADLEW_NOOP, mode=0o755)
            code, lines = gate.run_gate(root)
            text = "\n".join(lines)
            self.assertEqual(code, 1, text)
            self.assertIn("git ls-files failed", text)
            self.assertNotIn("NUL byte found", text)
        finally:
            shutil.rmtree(root, ignore_errors=True)


class TestMultiArgInclude(unittest.TestCase):
    """Z8: include() takes any number of args; an unparsed include is RED.

    Works against both the round-1 shape (a list) and the round-2 shape
    ((projects, reasons)), so the RED reason against round 1 is the dropped
    multi-arg include, not an unpacking error.
    """

    @staticmethod
    def _split(root):
        r = gate._included_projects(root)
        if isinstance(r, tuple):
            return r[0], r[1]
        return r, []

    def test_multi_arg_include_parses_all_args(self):
        root = tempfile.mkdtemp(prefix="gateinc")
        try:
            _write(os.path.join(root, "settings.gradle.kts"),
                   'rootProject.name = "fake"\ninclude(":a", ":b")\n')
            projects, reasons = self._split(root)
            self.assertEqual(sorted(projects), sorted(["a", "b"]))
            self.assertEqual(reasons, [])
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_include_with_no_parseable_path_is_red_reason(self):
        root = tempfile.mkdtemp(prefix="gateinc")
        try:
            _write(os.path.join(root, "settings.gradle.kts"),
                   'rootProject.name = "fake"\ninclude(variable)\n')
            projects, reasons = self._split(root)
            self.assertEqual(projects, [])
            self.assertEqual(len(reasons), 1)
            self.assertIn("include()", reasons[0])
        finally:
            shutil.rmtree(root, ignore_errors=True)


class TestDeadModuleFilter(GateTestBase):
    """A dead module (status = 'dead' in modules.toml) is filtered out of the
    project set before both _snapshot_xml and step_junit, so it can never NO-SOURCE
    or contribute a test count, even if it was include()d by mistake."""

    def _plant_modules_toml_dead(self):
        _write(os.path.join(self.repo.root, "modules.toml"),
               '[module.a_b]\n'
               'card = "a/b/AGENTS.md"\n'
               'public = true\n'
               'depends_on = []\n'
               'path = "a/b"\n'
               'status = "dead"\n')

    def test_dead_module_included_by_mistake_is_not_no_source(self):
        # a/b IS include()d (the mistake), has src/test sources,
        # no fresh TEST-*.xml, and is registered dead. On the unmodified gate
        # the NO-SOURCE fires -> the assert below FAILS. After the filter it's
        # removed from projects -> no NO-SOURCE -> PASS.
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_NOOP, mode=0o755)
        self._plant_modules_toml_dead()
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertNotIn("NO-SOURCE: a/b", text)

    def test_dead_module_not_included_is_not_no_source(self):
        # Control (GREEN both ways): same project but NOT include()d; it is not
        # in projects anyway. Pins that the common case (not included) is fine.
        _write(os.path.join(self.repo.root, "settings.gradle.kts"),
               'rootProject.name = "fake"\n')
        self._plant_modules_toml_dead()
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertNotIn("NO-SOURCE: a/b", text)

    def test_live_module_included_still_no_sources_red(self):
        # Control (GREEN both ways): a LIVE project c/d (no status == live),
        # include()d, with src/test sources and no fresh XML -> NO-SOURCE must
        # STILL fire. Proves the live rule was not loosened.
        _write(os.path.join(self.repo.root, "gradlew"), GRADLEW_NOOP, mode=0o755)
        _write(os.path.join(self.repo.root, "settings.gradle.kts"),
               'rootProject.name = "fake"\ninclude(":c:d")\n')
        _write(os.path.join(self.repo.root, "c", "d", "src", "test", "CTest.kt"),
               "class CTest\n")
        code, lines = self.run_gate()
        text = "\n".join(lines)
        self.assertIn("NO-SOURCE: c/d", text)


class TestStepUnitRunsEveryFolder(GateTestBase):
    """Regression guard for the step_unit `return bad` bug.

    The loop tail must run EVERY folder and only return after the loop. If
    `return bad` is left inside the loop, the first folder's exit
    short-circuits discovery of every later folder, so a failing test in a
    second folder is never reached.

    Build tests/unit/<A>/test_a.py (passes) and tests/unit/<B>/test_b.py
    (a deliberately FAILING assertion). Folder order is what matters: A is
    discovered first (index 1), B second (index 2). The failing test lives in
    the SECOND folder, so a short-circuiting tail hides it.
    """

    def test_step_unit_runs_every_folder_and_reports_red(self):
        # A passes (index 1), B fails (index 2) -> sorted order: A then B.
        _write(os.path.join(self.repo.root, "tests", "unit", "A", "test_a.py"),
               UNIT_TEST)
        # the SECOND folder's test deliberately FAILS
        _write(os.path.join(self.repo.root, "tests", "unit", "B", "test_b.py"),
               "import unittest\n\nclass B(unittest.TestCase):\n    def test_boom(self):\n        self.assertFalse(True)  # boom\n")

        code, lines = self.run_gate()
        text = "\n".join(lines)
        # RED: the failing second folder makes bad == 1 (step_unit returns non-zero)
        self.assertEqual(code, 1, text)
        # BOTH folders ran: a `3 unit[1 ...]` and a `3 unit[2 ...]` line exist.
        self.assertRegex(text, r"3 unit\[1 ")
        self.assertRegex(text, r"3 unit\[2 ")
        self.assertIn("GATE: RED", text)

if __name__ == "__main__":
    unittest.main()
