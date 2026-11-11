"""docs/module-status.json must match modules.toml and the tree (tools/module_status.py).

The rule: every live registry entry counts; a module with no code is NOT_STARTED and still counts; retired
entries (status = "dead") and containers (children, no code of their own) are listed apart and not counted.

A test that only ever passes proves nothing, so most tests below build a small synthetic world in a temp folder
(a container, a module with code, a module with no code, a retired one), show that the file is clean, and then
plant one mutant at a time: each must make check() report a problem that names it.
"""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
_spec = importlib.util.spec_from_file_location("module_status", REPO_ROOT / "tools" / "module_status.py")
module_status = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(module_status)


def _row(name: str, path: str, state: str = "DONE") -> dict:
    return {"name": name, "path": path, "items": [{"item": name, "status": state, "evidence": "1 code file"}]}


def make_world(root: Path) -> tuple[dict, dict]:
    """A registry, a tree and the status file for it. Returns (status, registry)."""
    for rel, text in {
        "grp/README.md": "a container holds no source file\n",
        "grp/child/a.kt": "class A\n",
        "grp/other/b.py": "x = 1\n",
        "solo/README.md": "no code yet\n",
        "old/legacy.kt": "class Legacy\n",
    }.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(text)
    registry = {
        "grp": {"path": "grp"},
        "grp_child": {"path": "grp/child"},
        "grp_other": {"path": "grp/other"},
        "solo": {"path": "solo"},
        "old": {"path": "old", "status": "dead"},
    }
    rows = [_row("grp_child", "grp/child"), _row("grp_other", "grp/other"), _row("solo", "solo", "NOT_STARTED")]
    status = {
        "modules": rows,
        "containers": [{"name": "grp", "path": "grp", "reason": "children only"}],
        "retired": [{"name": "old", "path": "old", "reason": "dead"}],
        "summary": {},
    }
    status["summary"] = module_status.counts(status)
    return status, registry


class SyntheticWorldTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.status, self.registry = make_world(self.root)

    def tearDown(self):
        self._tmp.cleanup()

    def problems(self, status=None, registry=None):
        return module_status.check(status if status is not None else self.status,
                                   registry if registry is not None else self.registry, self.root)

    def assertRed(self, text, status=None, registry=None):
        found = self.problems(status, registry)
        self.assertTrue(any(text in p for p in found), f"expected a problem naming {text!r}, got {found}")

    def test_the_clean_world_is_green(self):
        self.assertEqual(self.problems(), [])
        self.assertEqual(self.status["summary"],
                         {"live": 3, "done": 2, "partial": 0, "not_started": 1, "containers": 1, "retired": 1})

    def test_a_no_code_module_without_children_still_counts_as_not_started(self):
        self.assertEqual(module_status.derive_containers(self.root, self.registry), {"grp"})
        self.assertEqual(module_status.counts(self.status)["not_started"], 1)

    def test_mutant_remove_a_row_is_red(self):
        status = copy.deepcopy(self.status)
        status["modules"] = [r for r in status["modules"] if r["name"] != "grp_child"]
        status["summary"] = module_status.counts(status)
        self.assertRed("missing from the status file: grp_child", status)

    def test_mutant_add_a_stray_row_is_red(self):
        status = copy.deepcopy(self.status)
        status["modules"].append(_row("stray", "nowhere"))
        status["summary"] = module_status.counts(status)
        self.assertRed("stray status row", status)

    def test_mutant_flip_a_retired_flag_is_red(self):
        registry = copy.deepcopy(self.registry)
        del registry["old"]["status"]  # now live in the registry, still in the retired list, no row
        self.assertRed("missing from the status file: old", registry=registry)
        self.assertRed("in the retired list but is not status = dead", registry=registry)
        registry = copy.deepcopy(self.registry)
        registry["solo"]["status"] = "dead"  # dead in the registry, still a row
        self.assertRed("retired entry solo appears as a row", registry=registry)

    def test_mutant_a_container_that_gets_code_is_red(self):
        (self.root / "grp" / "oops.kt").write_text("class Oops\n")
        self.assertRed("grp is listed as a container but it is not one")

    def test_mutant_hiding_a_no_code_module_as_a_container_is_red(self):
        status = copy.deepcopy(self.status)
        status["containers"].append({"name": "solo", "path": "solo", "reason": "x"})
        status["modules"] = [r for r in status["modules"] if r["name"] != "solo"]
        status["summary"] = module_status.counts(status)
        self.assertRed("solo is listed as a container but it is not one", status)

    def test_mutant_a_container_counted_as_a_row_is_red(self):
        status = copy.deepcopy(self.status)
        status["containers"] = []
        status["modules"].append(_row("grp", "grp", "NOT_STARTED"))
        status["summary"] = module_status.counts(status)
        self.assertRed("grp is a container (children, no code of its own) but is not in the containers list", status)

    def test_mutant_empty_evidence_is_red(self):
        status = copy.deepcopy(self.status)
        status["modules"][0]["items"][0]["evidence"] = "  "
        self.assertRed("empty evidence", status)

    def test_mutant_a_status_word_that_is_not_allowed_is_red(self):
        status = copy.deepcopy(self.status)
        status["modules"][0]["items"][0]["status"] = "done"
        self.assertRed("is not one of", status)

    def test_mutant_a_typed_number_that_differs_from_the_rows_is_red(self):
        status = copy.deepcopy(self.status)
        status["summary"]["done"] += 1
        self.assertRed("summary.done=3 but the rows give 2", status)

    def test_mutant_a_wrong_path_is_red(self):
        status = copy.deepcopy(self.status)
        status["modules"][0]["path"] = "grp/elsewhere"
        self.assertRed("path mismatch for grp_child", status)


    # ---- the second round: one test per way the tool could be wrong without any test noticing ----

    def test_row_status_of_mixed_and_pure_rows(self):
        def row(*states):
            return {"name": "r", "path": "r", "items": [{"item": "i", "status": s, "evidence": "e"} for s in states]}
        self.assertEqual(module_status.row_status(row("DONE", "DONE")), "DONE")
        self.assertEqual(module_status.row_status(row("NOT_STARTED", "NOT_STARTED")), "NOT_STARTED")
        self.assertEqual(module_status.row_status(row("PARTIAL", "PARTIAL")), "PARTIAL")
        self.assertEqual(module_status.row_status(row("DONE", "NOT_STARTED")), "PARTIAL")
        self.assertEqual(module_status.row_status(row("DONE", "PARTIAL")), "PARTIAL")
        self.assertEqual(module_status.row_status(row("NOT_STARTED", "PARTIAL")), "PARTIAL")

    def test_a_dead_entry_with_children_and_no_code_is_not_a_container(self):
        registry = copy.deepcopy(self.registry)
        (self.root / "gone").mkdir()
        (self.root / "gone" / "README.md").write_text("retired group\n")
        (self.root / "gone" / "kid").mkdir()
        (self.root / "gone" / "kid" / "k.kt").write_text("class K\n")
        registry["gone"] = {"path": "gone", "status": "dead"}
        registry["gone_kid"] = {"path": "gone/kid"}
        self.assertNotIn("gone", module_status.derive_containers(self.root, registry))

    def test_a_dead_registry_entry_missing_from_the_retired_list_is_red(self):
        status = copy.deepcopy(self.status)
        status["retired"] = []
        status["summary"] = module_status.counts(status)
        self.assertEqual(self.problems(status), ["retired entry missing from the retired list: old"])

    def test_a_name_in_the_retired_list_that_is_not_dead_is_red(self):
        status = copy.deepcopy(self.status)
        status["retired"].append({"name": "solo", "path": "solo", "reason": "x"})
        status["summary"] = module_status.counts(status)
        self.assertEqual(self.problems(status), ["solo is in the retired list but is not status = dead in the registry"])

    def test_a_name_that_is_both_a_row_and_a_container_is_red_by_that_check_alone(self):
        status = copy.deepcopy(self.status)
        status["modules"].append(_row("grp", "grp", "NOT_STARTED"))
        status["summary"] = module_status.counts(status)
        self.assertEqual(self.problems(status), ["grp is both a row and a container"])

    def test_a_row_with_no_items_is_red(self):
        status = copy.deepcopy(self.status)
        status["modules"][0]["items"] = []
        status["summary"] = module_status.counts(status)
        self.assertEqual(self.problems(status), ["grp_child has no items"])

    def test_each_code_suffix_alone_stops_a_container_from_being_one(self):
        for suffix in sorted(module_status.CODE_SUFFIXES):
            with self.subTest(suffix=suffix):
                path = self.root / "grp" / f"own{suffix}"
                path.write_text("x\n")
                self.assertRed("grp is listed as a container but it is not one")
                path.unlink()
                self.assertEqual(self.problems(), [])

    def test_a_markdown_or_json_file_alone_is_not_code(self):
        (self.root / "grp" / "notes.md").write_text("notes\n")
        (self.root / "grp" / "data.json").write_text("{}\n")
        self.assertEqual(self.problems(), [])

    def test_not_started_with_only_docs_allowed_on_temp_tree(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        registry = {"mod": {"path": "mod"}}
        (root / "mod").mkdir()
        (root / "mod" / "AGENTS.md").write_text("card\n")
        (root / "mod" / "README.md").write_text("readme\n")
        (root / "mod" / "build.gradle.kts").write_text("plugins {}\n")
        self.assertEqual(module_status.check(
            json.loads((root / "docs" / "module-status.json").read_text()), registry, root), [])

    def test_not_started_refused_on_kt(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        registry = {"mod": {"path": "mod"}}
        (root / "mod").mkdir()
        (root / "mod" / "thing.kt").write_text("class T\n")
        problems = module_status.check(status, registry, root)
        self.assertTrue(any("mod is NOT_STARTED but its folder holds code or test files: mod/thing.kt" in p for p in problems),
                        f"expected RED on .kt, got {problems}")

    def test_not_started_refused_on_py(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        registry = {"mod": {"path": "mod"}}
        (root / "mod").mkdir()
        (root / "mod" / "thing.py").write_text("x = 1\n")
        problems = module_status.check(status, registry, root)
        self.assertTrue(any("mod is NOT_STARTED but its folder holds code or test files: mod/thing.py" in p for p in problems),
                        f"expected RED on .py, got {problems}")

    def test_not_started_refused_on_sh(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        registry = {"mod": {"path": "mod"}}
        (root / "mod").mkdir()
        (root / "mod" / "thing.sh").write_text("#!/bin/sh\n")
        problems = module_status.check(status, registry, root)
        self.assertTrue(any("mod is NOT_STARTED but its folder holds code or test files: mod/thing.sh" in p for p in problems),
                        f"expected RED on .sh, got {problems}")

    def test_not_started_refused_on_test_file(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        registry = {"mod": {"path": "mod"}}
        (root / "mod").mkdir()
        (root / "mod" / "thing_test.py").write_text("def test_x():\n    pass\n")
        problems = module_status.check(status, registry, root)
        self.assertTrue(any("mod is NOT_STARTED but its folder holds code or test files: mod/thing_test.py" in p for p in problems),
                        f"expected RED on test file, got {problems}")

    def test_partial_row_with_code_untouched(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "PARTIAL")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        registry = {"mod": {"path": "mod"}}
        (root / "mod").mkdir()
        (root / "mod" / "thing.kt").write_text("class T\n")
        self.assertEqual(module_status.check(status, registry, root), [])

    def test_not_started_rule_does_not_count_a_childs_files_but_counts_the_entrys_own(self):
        root = Path(self._tmp.name)
        registry = {"mod": {"path": "mod"}, "mod_kid": {"path": "mod/kid"}}
        (root / "mod" / "kid").mkdir(parents=True)
        (root / "mod" / "kid" / "thing.kt").write_text("class T\n")
        suffixes = module_status.NOT_STARTED_SUFFIXES
        self.assertEqual(module_status.own_code_files(root, "mod", registry, suffixes), [])
        (root / "mod" / "own.sh").write_text("echo hi\n")
        self.assertEqual(module_status.own_code_files(root, "mod", registry, suffixes), ["mod/own.sh"])
        self.assertEqual(module_status.own_code_files(root, "mod", registry), [], "container rule keeps CODE_SUFFIXES")

    def test_not_started_refused_on_a_test_file_in_a_nested_test_folder(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        (root / "mod" / "src" / "test" / "kotlin").mkdir(parents=True)
        (root / "mod" / "src" / "test" / "kotlin" / "FooTest.kt").write_text("class FooTest\n")
        problems = module_status.check(status, {"mod": {"path": "mod"}}, root)
        self.assertTrue(any("mod is NOT_STARTED but its folder holds code or test files: mod/src/test/kotlin/FooTest.kt" in p
                            for p in problems), problems)

    def test_not_started_message_names_the_first_file_and_counts_the_rest(self):
        root = Path(self._tmp.name)
        (root / "modules.toml").write_text('[module.mod]\npath = "mod"\n')
        (root / "docs").mkdir()
        status = {"modules": [_row("mod", "mod", "NOT_STARTED")], "containers": [], "retired": [], "summary": {}}
        status["summary"] = module_status.counts(status)
        (root / "docs" / "module-status.json").write_text(json.dumps(status))
        (root / "mod").mkdir()
        (root / "mod" / "a.py").write_text("x = 1\n")
        (root / "mod" / "b.sh").write_text("echo b\n")
        problems = module_status.check(status, {"mod": {"path": "mod"}}, root)
        self.assertEqual(problems, ["mod is NOT_STARTED but its folder holds code or test files: mod/a.py (and 1 more)"])

    def test_a_sibling_folder_whose_name_starts_with_the_same_text_is_not_a_child(self):
        root = self.root / "sib"
        (root / "a" / "mod").mkdir(parents=True)
        (root / "a" / "mod_x").mkdir(parents=True)
        (root / "a" / "mod" / "README.md").write_text("x\n")
        (root / "a" / "mod_x" / "README.md").write_text("x\n")
        registry = {"mod": {"path": "a/mod"}, "mod_x": {"path": "a/mod_x"}}
        self.assertEqual(module_status.derive_containers(root, registry), set())

    def test_code_under_build_node_modules_and_gradle_folders_is_not_the_container_own_code(self):
        for folder in ("build", "node_modules", ".gradle"):
            (self.root / "grp" / folder).mkdir()
            (self.root / "grp" / folder / "gen.kt").write_text("class Gen\n")
        self.assertEqual(self.problems(), [])

    def test_main_exits_one_with_the_drift_message_on_a_drifted_tree_and_zero_on_a_clean_one(self):
        (self.root / "modules.toml").write_text("".join(
            f'[module.{n}]\npath = "{e["path"]}"\n' + ('status = "dead"\n' if e.get("status") == "dead" else "") + "\n"
            for n, e in self.registry.items()))
        (self.root / "docs").mkdir()
        (self.root / "docs" / "module-status.json").write_text(json.dumps(self.status))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(module_status.main(["--root", str(self.root)]), 0)
        self.assertIn("DONE=2 PARTIAL=0 NOT_STARTED=1 total=3 containers=1 retired=1", out.getvalue())
        self.assertEqual(err.getvalue(), "")
        drifted = copy.deepcopy(self.status)
        drifted["modules"].append(_row("stray", "nowhere"))
        (self.root / "docs" / "module-status.json").write_text(json.dumps(drifted))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(module_status.main(["--root", str(self.root)]), 1)
        self.assertIn("DRIFT DETECTED", err.getvalue())
        self.assertIn("stray status row", err.getvalue())


class RealTreeTest(unittest.TestCase):
    def test_the_status_file_matches_the_registry_and_the_tree(self):
        status = module_status.read_status(REPO_ROOT)
        registry = module_status.read_registry(REPO_ROOT)
        self.assertEqual(module_status.check(status, registry, REPO_ROOT), [])

    def test_the_tool_prints_the_numbers_counted_from_the_file(self):
        out = subprocess.run([sys.executable, str(REPO_ROOT / "tools" / "module_status.py")],
                             capture_output=True, text=True, cwd=REPO_ROOT)
        self.assertEqual(out.returncode, 0, out.stderr)
        c = module_status.counts(module_status.read_status(REPO_ROOT))
        self.assertIn(
            f"DONE={c['done']} PARTIAL={c['partial']} NOT_STARTED={c['not_started']} total={c['live']} "
            f"containers={c['containers']} retired={c['retired']}", out.stdout)

    def test_every_live_entry_is_a_row_or_a_container(self):
        status = module_status.read_status(REPO_ROOT)
        registry = module_status.read_registry(REPO_ROOT)
        live = {n for n, e in registry.items() if e.get("status") != "dead"}
        counted = {r["name"] for r in status["modules"]} | {c["name"] for c in status["containers"]}
        self.assertEqual(live, counted)


if __name__ == "__main__":
    unittest.main()
