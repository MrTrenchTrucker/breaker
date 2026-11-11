"""tools/affected_checks.py: which Gradle tasks a change needs.

Most tests call the pure plan() function on a synthetic registry, so they need no git and no
disk. The command-line tests build a small throwaway git repository and run main() on it.

Every table of cases carries control assertions: a deliberately wrong registry or wrong input
must give a different plan, so the expected value cannot be satisfied by a planner that
ignores the input under test. A test that only ever passes proves nothing.
"""

import contextlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
TOOL = os.path.join(REPO_ROOT, "tools", "affected_checks.py")
_spec = importlib.util.spec_from_file_location("affected_checks", TOOL)
ac = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ac)


def _gradle(path):
    return ":" + path.replace("/", ":")


# A small world shaped like the real registry: a container, a chain, a dead module, a
# folder read by a Gradle build, a file read by a Gradle build, and a sub-module of a group.
REG = {
    "android": {"path": "android", "depends_on": ["shared"]},
    "core": {"path": "android/modules/core", "depends_on": ["android"]},
    "commit": {"path": "android/modules/commit", "depends_on": ["core"]},
    "accessibility": {"path": "android/modules/commit/accessibility", "depends_on": ["commit"]},
    "ime": {"path": "android/modules/commit/ime", "depends_on": ["commit"], "status": "dead"},
    "gesture": {"path": "android/modules/gesture", "depends_on": ["core"]},
    "history": {"path": "android/modules/history", "depends_on": ["shared"]},
    "ui": {"path": "android/ui", "depends_on": ["core"]},
    "app": {"path": "android/app", "depends_on": ["ui", "accessibility"]},
    "shared": {"path": "shared", "depends_on": []},
    "contracts": {"path": "shared/modules/api-contracts", "depends_on": ["shared"]},
    "webgroup": {"path": "shared/modules/web-libs", "depends_on": ["shared"]},
    "noble": {"path": "shared/modules/web-libs/noble-ciphers", "depends_on": ["webgroup"]},
    "web": {"path": "server/modules/web-fe", "depends_on": ["shared", "webgroup"]},
}
# Gradle reads, as the build files declare them: ui and app read folders, history reads a
# card file and a repo-root ADR, web reads the web-libs folder.
READS = {
    "ui": ["android/app"],
    "app": ["docs/brand/icon.svg"],
    "history": ["android/modules/core/AGENTS.md", "decisions/ADR-010-retention.md"],
    "web": ["shared/modules/web-libs"],
}
ROOT_READS = ["android/modules/stt-ondevice/sherpa-onnx-aar.properties"]
GRADLE = {_gradle(m["path"]) for m in REG.values() if m.get("status") != "dead"}
# Modules with Kotlin test sources. The containers, the web-libs group and web-fe have none.
TESTED = set(REG) - {"android", "shared", "webgroup", "noble", "web"}


def _plan(changed, reg=None, reads=None, root_reads=None, gradle=None, tested=None, max_touched=ac.MAX_TOUCHED):
    return ac.plan(
        changed,
        REG if reg is None else reg,
        READS if reads is None else reads,
        ROOT_READS if root_reads is None else root_reads,
        GRADLE if gradle is None else gradle,
        TESTED if tested is None else tested,
        max_touched=max_touched,
    )


def _tasks(*modules):
    return sorted(_gradle(REG[m]["path"]) + ":check" for m in modules)


def _proj(*modules):
    return sorted(_gradle(REG[m]["path"]) for m in modules)


class TestTouchedAndCallers(unittest.TestCase):
    def test_leaf_change_runs_itself_and_its_callers(self):
        p = _plan(["android/modules/core/src/main/A.kt"])
        self.assertEqual(p.mode, "targeted")
        self.assertEqual(p.touched, ["core"])
        # core is used by commit, gesture, ui and history; commit by accessibility;
        # accessibility by app. The dead ime module is not a caller.
        # history depends on shared here and reads only a card of core, so a .kt change in core
        # does not reach it.
        self.assertEqual(sorted(p.dependents), sorted(["commit", "gesture", "ui", "accessibility", "app"]))
        self.assertEqual(p.tasks, _tasks("accessibility", "app", "commit", "core", "gesture", "ui"))
        self.assertNotIn("ime", p.dependents)

    def test_control_removing_the_chain_edge_drops_the_transitive_callers(self):
        reg = dict(REG)
        reg["commit"] = dict(REG["commit"], depends_on=[])
        p = _plan(["android/modules/core/src/main/A.kt"], reg=reg)
        self.assertNotIn("commit", p.dependents)
        self.assertNotIn("accessibility", p.dependents)
        self.assertIn("gesture", p.dependents)
        # app still comes in, through ui, which depends on core directly.
        self.assertIn("app", p.dependents)

    def test_module_with_no_callers_runs_only_itself(self):
        p = _plan(["android/modules/gesture/src/main/G.kt"])
        self.assertEqual(p.touched, ["gesture"])
        self.assertEqual(p.dependents, [])
        self.assertEqual(p.tasks, _tasks("gesture"))

    def test_control_a_caller_added_to_the_registry_appears(self):
        reg = dict(REG)
        reg["app"] = dict(REG["app"], depends_on=["ui", "accessibility", "gesture"])
        p = _plan(["android/modules/gesture/src/main/G.kt"], reg=reg)
        self.assertEqual(p.dependents, ["app"])

    def test_deepest_module_wins_over_its_parent(self):
        p = _plan(["android/modules/commit/accessibility/src/main/X.kt"])
        self.assertEqual(p.touched, ["accessibility"])
        self.assertNotIn("commit", p.touched)

    def test_control_without_the_sub_module_the_parent_is_touched(self):
        reg = {k: v for k, v in REG.items() if k != "accessibility"}
        p = _plan(["android/modules/commit/accessibility/src/main/X.kt"], reg=reg)
        self.assertEqual(p.touched, ["commit"])

    def test_a_folder_read_by_a_build_makes_that_build_a_caller(self):
        p = _plan(["android/app/src/main/kotlin/A.kt"])
        self.assertEqual(p.touched, ["app"])
        self.assertEqual(p.dependents, ["ui"])
        self.assertIn("reads changed file", p.why["ui"])

    def test_control_without_the_read_the_build_is_not_a_caller(self):
        reads = {k: v for k, v in READS.items() if k != "ui"}
        p = _plan(["android/app/src/main/kotlin/A.kt"], reads=reads)
        self.assertEqual(p.touched, ["app"])
        self.assertNotIn("ui", p.dependents)

    def test_a_read_is_file_level_not_module_level(self):
        # history reads one card file of core and does not depend on core.
        p_read = _plan(["android/modules/core/AGENTS.md"])
        p_other = _plan(["android/modules/core/src/main/A.kt"])
        self.assertIn("history", p_read.dependents)
        self.assertNotIn("history", p_other.dependents)

    def test_a_group_read_covers_its_sub_modules(self):
        p = _plan(["shared/modules/web-libs/noble-ciphers/aes.js"])
        self.assertEqual(p.touched, ["noble"])
        self.assertEqual(p.dependents, ["web"])
        self.assertEqual(p.tasks, sorted([_gradle("shared/modules/web-libs/noble-ciphers") + ":check",
                                          _gradle("server/modules/web-fe") + ":check"]))

    def test_a_folder_read_of_registered_modules_is_not_an_outside_read(self):
        # app reads the whole android/modules folder, as the real android/app build does.
        reads = dict(READS, app=["android/modules"])
        p = _plan(["android/modules/gesture/src/main/G.kt"], reads=reads)
        self.assertEqual(p.mode, "targeted")
        self.assertIn("app", p.dependents)

    def test_control_a_folder_read_outside_the_modules_runs_everything(self):
        reads = dict(READS, app=["docs"])
        p = _plan(["docs/brand/x.md"], reads=reads)
        self.assertEqual(p.mode, "full")

    def test_control_a_contract_change_does_not_reach_the_web_build(self):
        p = _plan(["shared/modules/api-contracts/openapi.yaml"])
        self.assertEqual(p.touched, ["contracts"])
        self.assertNotIn("web", p.dependents)

    def test_a_dead_module_change_runs_nothing(self):
        p = _plan(["android/modules/commit/ime/src/main/I.kt"])
        self.assertEqual(p.touched, ["ime"])
        self.assertEqual(p.mode, "none")
        self.assertEqual(p.tasks, [])

    def test_control_the_same_dead_module_gets_a_task_if_it_were_live(self):
        reg = dict(REG)
        reg["ime"] = {k: v for k, v in REG["ime"].items() if k != "status"}
        gradle = GRADLE | {_gradle(REG["ime"]["path"])}
        p = _plan(["android/modules/commit/ime/src/main/I.kt"], reg=reg, gradle=gradle)
        self.assertEqual(p.mode, "targeted")
        self.assertEqual(p.tasks, [_gradle(REG["ime"]["path"]) + ":check"])

    def test_a_sibling_whose_name_starts_the_same_is_not_inside_the_module(self):
        # android/uiextra is not under android/ui: it belongs to the android container.
        p = _plan(["android/uiextra/A.kt"])
        self.assertEqual(p.touched, ["android"])
        self.assertEqual(ac.deepest_module("android/uiextra/A.kt", {k: v["path"] for k, v in REG.items()}), "android")

    def test_control_the_real_module_path_is_its_own_module(self):
        p = _plan(["android/ui/A.kt"])
        self.assertEqual(p.touched, ["ui"])

    def test_a_container_change_reaches_everything_that_uses_it(self):
        p = _plan(["android/AGENTS.md"])
        self.assertEqual(p.touched, ["android"])
        self.assertIn("core", p.dependents)
        self.assertIn("app", p.dependents)

    def test_control_a_container_nobody_uses_has_no_callers(self):
        reg = {k: (dict(v, depends_on=[]) if k != "android" else v) for k, v in REG.items()}
        reg["core"] = dict(REG["core"], depends_on=[])
        p = _plan(["android/AGENTS.md"], reg=reg)
        self.assertEqual(p.touched, ["android"])
        self.assertEqual(p.dependents, [])

    def test_a_cycle_terminates(self):
        reg = {
            "x": {"path": "lib/x", "depends_on": ["y"]},
            "y": {"path": "lib/y", "depends_on": ["x"]},
        }
        gradle = {":lib:x", ":lib:y"}
        p = ac.plan(["lib/x/A.kt"], reg, {}, [], gradle, {"x", "y"})
        self.assertEqual(p.touched, ["x"])
        self.assertEqual(p.dependents, ["y"])
        self.assertEqual(p.tasks, [":lib:x:check", ":lib:y:check"])


class TestFullTriggers(unittest.TestCase):
    def test_every_build_input_and_gate_file_runs_everything(self):
        for changed in [
            "settings.gradle.kts",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "buildSrc/src/main/Foo.kt",
            ".github/workflows/checks.yml",
            "modules.toml",
            "tools/affected_checks.py",
            "android/modules/core/build.gradle.kts",
        ]:
            with self.subTest(path=changed):
                p = _plan([changed])
                self.assertEqual(p.mode, "full")
                self.assertEqual(p.tasks, ["build"])

    def test_a_path_outside_every_module_that_is_not_safe_runs_everything(self):
        for changed in ["ARCHITECTURE.md", "unknown/new.kt", ".gitignore"]:
            with self.subTest(path=changed):
                p = _plan([changed])
                self.assertEqual(p.mode, "full")
                self.assertTrue(any("not a known safe path" in r for r in p.full_reasons))

    def test_a_file_the_root_gradle_build_reads_runs_everything(self):
        p = _plan(["android/modules/stt-ondevice/sherpa-onnx-aar.properties"])
        self.assertEqual(p.mode, "full")
        self.assertTrue(any("root Gradle build" in r for r in p.full_reasons))

    def test_control_the_same_file_without_the_root_read_is_targeted(self):
        full = _plan(["android/app/version.properties"], root_reads=["android/app/version.properties"])
        self.assertEqual(full.mode, "full")
        plain = _plan(["android/app/version.properties"], root_reads=[])
        self.assertEqual(plain.mode, "targeted")
        self.assertEqual(plain.touched, ["app"])

    def test_a_file_a_module_build_reads_outside_the_modules_runs_everything(self):
        p = _plan(["decisions/ADR-010-retention.md"])
        self.assertEqual(p.mode, "full")
        self.assertTrue(any("read by a module build" in r for r in p.full_reasons))

    def test_control_the_same_adr_not_read_by_any_build_is_safe(self):
        reads = {k: [x for x in v if not x.startswith("decisions/")] for k, v in READS.items()}
        p = _plan(["decisions/ADR-010-retention.md"], reads=reads)
        self.assertEqual(p.mode, "none")

    def test_more_than_twelve_touched_modules_runs_everything(self):
        reg = {f"m{i}": {"path": f"lib/m{i}", "depends_on": []} for i in range(13)}
        gradle = {_gradle(v["path"]) for v in reg.values()}
        changed = [f"lib/m{i}/A.kt" for i in range(13)]
        p = ac.plan(changed, reg, {}, [], gradle)
        self.assertEqual(p.mode, "full")
        self.assertIn("13 modules touched (limit 12)", p.full_reasons)

    def test_control_twelve_touched_modules_stay_targeted(self):
        reg = {f"m{i}": {"path": f"lib/m{i}", "depends_on": []} for i in range(12)}
        gradle = {_gradle(v["path"]) for v in reg.values()}
        changed = [f"lib/m{i}/A.kt" for i in range(12)]
        p = ac.plan(changed, reg, {}, [], gradle)
        self.assertEqual(p.mode, "targeted")
        self.assertEqual(len(p.tasks), 12)


class TestSafeFiles(unittest.TestCase):
    def test_files_no_gradle_build_reads_run_no_gradle_task(self):
        for changed in [
            "README.md",
            "docs/product/intro.md",
            "decisions/ADR-005-something.md",
            "tests/unit/tools/test_x.py",
            "tools/gen_model_registry.py",
            ".github/CONTRIBUTING.md",
        ]:
            with self.subTest(path=changed):
                p = _plan([changed])
                self.assertEqual(p.mode, "none")
                self.assertEqual(p.tasks, [])
                self.assertEqual(p.safe_files, [changed])

    def test_an_empty_diff_is_none(self):
        p = _plan([])
        self.assertEqual(p.mode, "none")
        self.assertEqual(p.tasks, [])
        self.assertEqual(p.reason, "no changed files")

    def test_control_a_safe_looking_path_read_by_a_build_is_not_safe(self):
        reads = dict(READS, app=["docs/brand/icon.svg"])
        p = _plan(["docs/brand/icon.svg"], reads=reads)
        self.assertEqual(p.mode, "full")
        self.assertNotEqual(p.mode, "none")


class TestPlanIsDeterministic(unittest.TestCase):
    def test_same_input_same_plan_and_sorted_output(self):
        changed = ["android/modules/core/src/main/A.kt", "android/app/src/main/B.kt"]
        a = _plan(changed).as_dict()
        b = _plan(list(reversed(changed))).as_dict()
        self.assertEqual(a, b)
        self.assertEqual(a["tasks"], sorted(a["tasks"]))
        self.assertEqual(a["touched"], sorted(a["touched"]))


class TestRepoLayer(unittest.TestCase):
    def test_repo_path_literals(self):
        self.assertEqual(ac.repo_path_literal("android/app"), "android/app")
        self.assertEqual(ac.repo_path_literal("docs/brand/x.svg"), "docs/brand/x.svg")
        self.assertEqual(ac.repo_path_literal("modules.toml"), "modules.toml")
        self.assertIsNone(ac.repo_path_literal("https://example.invalid/x"))
        self.assertIsNone(ac.repo_path_literal("**/build/**"))
        self.assertIsNone(ac.repo_path_literal("dev.breaker"))
        self.assertIsNone(ac.repo_path_literal("/etc/passwd"))
        self.assertIsNone(ac.repo_path_literal("../escape/x"))

    def test_a_commented_out_read_is_not_a_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "x.kts")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write('// listOf("android/modules/phrases")\nlistOf("android/app")\n')
            self.assertEqual(ac._literals(path), ["android/app"])


class TestCommandLine(unittest.TestCase):
    """Runs main() on a throwaway git repository: real diffs, real base and head commits."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="affected_checks_")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self._write("modules.toml", (
            '[module.lib]\npath = "lib"\ndepends_on = []\n\n'
            '[module.app]\npath = "app"\ndepends_on = ["lib"]\n'
        ))
        self._write("settings.gradle.kts", 'rootProject.name = "x"\ninclude(":lib")\ninclude(":app")\n')
        self._write("lib/build.gradle.kts", "plugins { base }\n")
        self._write("app/build.gradle.kts", "plugins { base }\n")
        self._write("lib/src/A.kt", "class A\n")
        self._write("app/src/B.kt", "class B\n")
        self._write("README.md", "readme\n")
        self._git("init", "-q")
        self._git("add", "-A")
        self._git("commit", "-q", "-m", "one")
        self.c1 = self._git("rev-parse", "HEAD")
        self._write("lib/src/A.kt", "class A { fun f() = 1 }\n")
        self._git("commit", "-qam", "two")
        self.c2 = self._git("rev-parse", "HEAD")

    def _write(self, rel, text):
        path = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)

    def _git(self, *args):
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
                   GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
        out = subprocess.run(["git", "-C", self.tmp, "-c", "commit.gpgsign=false", *args],
                             capture_output=True, text=True, env=env, check=True)
        return out.stdout.strip()

    def _run(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = ac.main(["--root", self.tmp, *argv])
        return code, out.getvalue(), err.getvalue()

    def test_a_real_diff_gives_the_touched_module_its_caller(self):
        code, out, _ = self._run("--base", self.c1, "--head", self.c2, "--json")
        self.assertEqual(code, 0)
        plan = json.loads(out)
        self.assertEqual(plan["mode"], "targeted")
        self.assertEqual(plan["touched"], ["lib"])
        self.assertEqual(plan["dependents"], ["app"])
        self.assertEqual(plan["tasks"], [":app:check", ":lib:check"])

    def test_control_the_same_range_with_no_change_is_none(self):
        code, out, _ = self._run("--base", self.c2, "--head", self.c2, "--mode")
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), "none")

    def test_control_a_docs_only_change_runs_no_gradle_task(self):
        self._write("README.md", "readme changed\n")
        self._git("commit", "-qam", "docs")
        c3 = self._git("rev-parse", "HEAD")
        code, out, _ = self._run("--base", self.c2, "--head", c3, "--gradle")
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), "")
        code, out, _ = self._run("--base", self.c2, "--head", c3, "--mode")
        self.assertEqual(out.strip(), "none")

    def test_an_unreadable_base_fails_safe_to_the_full_build(self):
        code, out, _ = self._run("--base", "no-such-commit-0000", "--head", self.c2, "--mode")
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), "full")
        code, out, _ = self._run("--base", "no-such-commit-0000", "--head", self.c2, "--gradle")
        self.assertEqual(out.strip(), "build")

    def test_a_base_that_looks_like_an_option_is_refused_to_full(self):
        code, out, _ = self._run("--base=--output=/tmp/x", "--head", self.c2, "--mode")
        self.assertEqual(out.strip(), "full")

    def test_explain_names_the_file_module_and_caller(self):
        code, out, _ = self._run("--base", self.c1, "--head", self.c2, "--explain")
        self.assertEqual(code, 0)
        self.assertIn("file lib/src/A.kt -> module lib", out)
        self.assertIn("touched: lib", out)
        self.assertIn("caller app: depends on lib", out)
        self.assertIn("tasks: :app:check :lib:check", out)

    def test_a_missing_registry_exits_two(self):
        os.remove(os.path.join(self.tmp, "modules.toml"))
        code, _, err = self._run("--base", self.c1, "--head", self.c2, "--mode")
        self.assertEqual(code, 2)
        self.assertIn("modules.toml", err)


class TestTestProjects(unittest.TestCase):
    """test_projects: the planned Gradle projects that have Kotlin or Java tests."""

    def test_planned_projects_with_tests_are_listed(self):
        p = _plan(["android/modules/core/src/main/A.kt"])
        self.assertEqual(p.test_projects, _proj("accessibility", "app", "commit", "core", "gesture", "ui"))

    def test_control_untested_callers_are_not_listed(self):
        p = _plan(["shared/modules/web-libs/noble-ciphers/aes.js"])
        self.assertEqual(p.tasks, sorted([_gradle("shared/modules/web-libs/noble-ciphers") + ":check",
                                          _gradle("server/modules/web-fe") + ":check"]))
        self.assertEqual(p.test_projects, [])
        control = _plan(["shared/modules/web-libs/noble-ciphers/aes.js"], tested=TESTED | {"web"})
        self.assertEqual(control.test_projects, [_gradle("server/modules/web-fe")])

    def test_a_mixed_plan_lists_only_the_tested_projects(self):
        p = _plan(["android/modules/core/src/main/A.kt", "shared/modules/web-libs/noble-ciphers/aes.js"])
        self.assertIn(_gradle("android/modules/core"), p.test_projects)
        self.assertNotIn(_gradle("server/modules/web-fe"), p.test_projects)
        self.assertNotIn(_gradle("shared/modules/web-libs/noble-ciphers"), p.test_projects)

    def test_full_mode_lists_every_tested_live_project(self):
        p = _plan(["settings.gradle.kts"])
        self.assertEqual(p.mode, "full")
        # ime is tested in the fixture but dead, so a full run does not plan it.
        self.assertEqual(p.test_projects, _proj(*sorted(TESTED - {"ime"})))
        self.assertNotIn(":android:modules:commit:ime", p.test_projects)
        self.assertIn(":android:app", p.test_projects)
        self.assertNotIn(":server:modules:web-fe", p.test_projects)
        control = _plan(["settings.gradle.kts"], tested=set())
        self.assertEqual(control.test_projects, [])

    def test_every_test_project_is_a_planned_task(self):
        for changed in (["android/modules/core/src/main/A.kt"],
                        ["android/app/src/main/kotlin/A.kt"],
                        ["shared/modules/api-contracts/openapi.yaml"]):
            with self.subTest(changed=changed):
                p = _plan(changed)
                for proj in p.test_projects:
                    self.assertIn(proj + ":check", p.tasks)

    def test_targeted_tasks_use_check_and_full_uses_build(self):
        p = _plan(["android/modules/gesture/src/main/G.kt"])
        self.assertTrue(p.tasks and all(t.endswith(":check") for t in p.tasks))
        full = _plan(["settings.gradle.kts"])
        self.assertEqual(full.tasks, ["build"])


class TestRepoTestDetection(unittest.TestCase):
    def test_a_module_has_kotlin_or_java_tests_only_with_a_source_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "src", "test")
            self.assertFalse(ac._has_kotlin_tests(src), "a missing folder has no tests")
            os.makedirs(src)
            with open(os.path.join(src, "README.md"), "w", encoding="utf-8") as fh:
                fh.write("docs only\n")
            self.assertFalse(ac._has_kotlin_tests(src), "a markdown file is not a test")
            with open(os.path.join(src, "ATest.kt"), "w", encoding="utf-8") as fh:
                fh.write("class ATest\n")
            self.assertTrue(ac._has_kotlin_tests(src))
            os.remove(os.path.join(src, "ATest.kt"))
            with open(os.path.join(src, "BTest.java"), "w", encoding="utf-8") as fh:
                fh.write("class BTest {}\n")
            self.assertTrue(ac._has_kotlin_tests(src), "a Java test counts too")


# --- the workflow's own steps, extracted from checks.yml and run -----------------

WORKFLOW = os.path.join(REPO_ROOT, ".github", "workflows", "checks.yml")


def _step_run(step_name):
    """The body of one named step's run: block in checks.yml, dedented by its indent.

    Read as text (stdlib only): the step header, then the first `run: |` after it, then the
    lines indented deeper than the step header.
    """
    with open(WORKFLOW, encoding="ascii") as fh:
        lines = fh.read().splitlines()
    start = lines.index(f"      - name: {step_name}")
    run = lines.index("        run: |", start)
    body = []
    for line in lines[run + 1:]:
        if line.strip() and len(line) - len(line.lstrip(" ")) < 10:
            break
        body.append(line[10:] if line.strip() else "")
    return "\n".join(body) + "\n"


def _step_block(step_name):
    """The whole text of one named step in checks.yml, env block included (text, not evaluated)."""
    with open(WORKFLOW, encoding="ascii") as fh:
        lines = fh.read().splitlines()
    start = lines.index(f"      - name: {step_name}")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("      - ")), len(lines))
    return "\n".join(lines[start:end])


def _kotlin_tests_script():
    """The python heredoc of the 'Kotlin tests ran and passed' step, without the shell wrapper."""
    body = _step_run("Kotlin tests ran and passed").splitlines()
    start = body.index("python3 - <<'EOF'")
    end = body.index("EOF", start)
    return "\n".join(body[start + 1:end]) + "\n"


class TestWorkflowTestStep(unittest.TestCase):
    """The zero-test rule as the workflow runs it: 0 tests fail only when a planned project has tests."""

    def setUp(self):
        self.scripts = tempfile.mkdtemp(prefix="affected_scripts_")
        self.work = tempfile.mkdtemp(prefix="affected_results_")
        self.addCleanup(shutil.rmtree, self.scripts, True)
        self.addCleanup(shutil.rmtree, self.work, True)
        self.script = os.path.join(self.scripts, "kotlin_tests_step.py")
        with open(self.script, "w", encoding="utf-8") as fh:
            fh.write(_kotlin_tests_script())

    def _results(self, tests, failures):
        folder = os.path.join(self.work, "app", "build", "test-results", "testDebugUnitTest")
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "TEST-app.xml"), "w", encoding="utf-8") as fh:
            fh.write(f'<testsuite name="app" tests="{tests}" failures="{failures}" errors="0"/>\n')

    def _step(self, mode, planned):
        env = dict(os.environ, MODE=mode, TEST_PROJECTS=planned)
        return subprocess.run([sys.executable, self.script], cwd=self.work, env=env,
                              capture_output=True, text=True)

    def test_zero_tests_pass_when_no_planned_project_has_tests(self):
        self.assertEqual(self._step("targeted", "").returncode, 0)

    def test_control_zero_tests_fail_when_a_planned_project_has_tests(self):
        r = self._step("targeted", ":android:app:check")
        self.assertEqual(r.returncode, 1)
        self.assertIn("ran 0 tests", r.stdout)

    def test_a_full_run_with_zero_tests_fails(self):
        self.assertEqual(self._step("full", "").returncode, 1)

    def test_a_none_plan_with_zero_tests_passes(self):
        self.assertEqual(self._step("none", "").returncode, 0)

    def test_passing_tests_pass_whether_or_not_projects_are_planned(self):
        self._results(tests=2, failures=0)
        self.assertEqual(self._step("targeted", "").returncode, 0)
        self.assertEqual(self._step("targeted", ":android:app:check").returncode, 0)

    def test_the_step_reads_the_plan_outputs(self):
        # Wiring check by text: GitHub expressions are not evaluated here. The plan step must write
        # test_projects, and this step must take it, next to the mode, into its environment.
        block = _step_block("Kotlin tests ran and passed")
        self.assertIn("MODE: ${{ steps.plan.outputs.mode }}", block)
        self.assertIn("TEST_PROJECTS: ${{ steps.plan.outputs.test_projects }}", block)
        plan_block = _step_block("Plan the Gradle tasks")
        self.assertIn('echo "test_projects=$TESTS" >> "$GITHUB_OUTPUT"', plan_block)
        self.assertIn("id: plan", plan_block)

    def test_a_failure_fails_whatever_the_plan(self):
        self._results(tests=2, failures=1)
        self.assertEqual(self._step("targeted", "").returncode, 1)
        self.assertEqual(self._step("targeted", ":android:app:check").returncode, 1)


class TestWorkflowPlanStep(unittest.TestCase):
    """The plan step runs as the workflow runs it, against a real two-commit range."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="affected_plan_")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self._write("modules.toml", '[module.lib]\npath = "lib"\ndepends_on = []\n')
        self._write("settings.gradle.kts", 'rootProject.name = "x"\ninclude(":lib")\n')
        self._write("lib/build.gradle.kts", "plugins { base }\n")
        self._write("lib/src/main/A.kt", "class A\n")
        self._git("init", "-q")
        self._git("add", "-A")
        self._git("commit", "-q", "-m", "one")
        self.c1 = self._git("rev-parse", "HEAD")
        self._write("lib/src/main/B.kt", "class B\n")
        self._git("add", "-A")
        self._git("commit", "-q", "-m", "two")
        self.c2 = self._git("rev-parse", "HEAD")
        self._write("lib/src/test/ATest.kt", "class ATest\n")
        self._git("add", "-A")
        self._git("commit", "-q", "-m", "three")
        self.c3 = self._git("rev-parse", "HEAD")
        with open(TOOL, encoding="utf-8") as fh:
            self._write("tools/affected_checks.py", fh.read())
        self.plan_script = os.path.join(self.tmp, "plan_step.sh")
        with open(self.plan_script, "w", encoding="utf-8") as fh:
            fh.write(_step_run("Plan the Gradle tasks"))

    def _write(self, rel, text):
        path = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)

    def _git(self, *args):
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
                   GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
        out = subprocess.run(["git", "-C", self.tmp, "-c", "commit.gpgsign=false", *args],
                             capture_output=True, text=True, env=env, check=True)
        return out.stdout.strip()

    def _plan(self, event, base, head):
        out_file = os.path.join(self.tmp, "github_output.txt")
        open(out_file, "w").close()
        env = dict(os.environ, EVENT_NAME=event, BASE_SHA=base, HEAD_SHA=head, GITHUB_OUTPUT=out_file)
        r = subprocess.run(["bash", "-eo", "pipefail", self.plan_script], cwd=self.tmp, env=env,
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        with open(out_file, encoding="utf-8") as fh:
            return dict(line.split("=", 1) for line in fh.read().splitlines() if "=" in line)

    def test_a_range_with_no_tested_project_plans_no_test_projects(self):
        self._git("checkout", "-q", self.c2)  # the tree has no lib tests yet
        out = self._plan("pull_request", self.c1, self.c2)
        self.assertEqual(out["mode"], "targeted")
        self.assertEqual(out["tasks"], ":lib:check")
        self.assertEqual(out["test_projects"], "")

    def test_control_the_same_range_with_tests_plans_the_project(self):
        self._git("checkout", "-q", self.c3)  # the tree now has lib tests
        out = self._plan("pull_request", self.c2, self.c3)
        self.assertEqual(out["mode"], "targeted")
        self.assertEqual(out["test_projects"], ":lib")

    def test_a_push_event_is_the_full_run(self):
        self._git("checkout", "-q", self.c3)
        out = self._plan("push", "", "")
        self.assertEqual(out["mode"], "full")
        self.assertEqual(out["tasks"], "build")


if __name__ == "__main__":
    unittest.main()
