"""The repo consistency checker catches a parent README that omits a
DIRECT child.

`tools/check_repo.py` used to check only NESTED sub-modules
(`android/modules/core`, ...); a direct child such as `android/app` or
`android/ui` was skipped by a path-depth guard, so a parent README that
stopped naming one of them stayed green. These tests run the real
`check_repo.py` on a scratch copy of the tree with the omission planted,
watch it name the missing child, then restore the tree and watch it go
clean. A test that only ever passes proves nothing, so the planted fault
is mandatory here.

It also covers the NAMING rule: a sub-module is named only as an ELEMENT of
the parent README's `**Sub-modules:**` list. Prose anywhere else in the
README does not count (the android README's "Kotlin, native Android app ..."
sentence names the word "app" without naming the module), and a name in the
list that is not a registered sub-module of that parent is a stale list.
"""
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
CHECK_REPO = os.path.join(REPO_ROOT, "tools", "check_repo.py")
EXCLUDES = (".git", "build", ".gradle", ".kotlin", "node_modules")


def _scratch_copy():
    """A scratch copy of the tree, big enough for check_repo but small."""
    dst = tempfile.mkdtemp(prefix="check_repo_scratch_")
    src = REPO_ROOT
    for root, dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        dirs[:] = [d for d in dirs if d not in EXCLUDES]
        if rel.startswith("build") or "/build" in ("/" + rel):
            continue
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for name in files:
            s = os.path.join(root, name)
            d = os.path.join(dst, rel, name)
            shutil.copy2(s, d)
    return dst


def _run_check(dst):
    """Run check_repo.py inside the scratch tree; return (rc, output)."""
    proc = subprocess.run(
        [sys.executable, os.path.join(dst, "tools", "check_repo.py")],
        capture_output=True, text=True,
    )
    return proc.returncode, proc.stdout + proc.stderr


class CheckRepoDirectChildTest(unittest.TestCase):
    """A parent README that omits a direct child is an error; restored, clean."""

    @classmethod
    def setUpClass(cls):
        cls.scratch = _scratch_copy()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.scratch, ignore_errors=True)

    def test_omitted_direct_child_is_an_error(self):
        readme = os.path.join(self.scratch, "android", "README.md")
        with open(readme, encoding="utf-8") as fh:
            original = fh.read()
        try:
            # Plant the fault: drop "app" from the sub-module list.
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original.replace("app, ui,", "ui,", 1))
            rc, out = _run_check(self.scratch)
            assert rc != 0, (
                "check_repo stayed clean after 'app' was removed from "
                "android/README.md — the direct-child omission is not caught"
            )
            assert "does not name sub-module 'app'" in out, (
                "check_repo failed but did not name the missing direct "
                f"child: {out!r}"
            )
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original)

    def test_restored_readme_is_clean_again(self):
        # After the previous test's restore, the untouched tree must be
        # green: the fix catches the omission, it does not invent one.
        rc, out = _run_check(self.scratch)
        assert rc == 0, f"check_repo is not clean on the unmodified tree: {out}"


def _load_module(name, path):
    """Load a module from a file path (no __init__.py, no path pollution)."""
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class RequiredSectionsListsStayInStep(unittest.TestCase):
    """The two REQUIRED_CARD_SECTIONS copies must not drift apart.

    check_repo.py and contract_support.py each keep their own list on
    purpose (independence: a typo in one weakens only that check, and the
    contract-side copy is self-verifying through the heading test). The
    cost of that independence is drift, so this test pins the two together:
    a section name added to one list and not the other fails here.
    """

    def test_check_repo_list_equals_contract_support_list(self):
        check_repo = _load_module(
            "check_repo_under_test",
            os.path.join(REPO_ROOT, "tools", "check_repo.py"),
        )
        contract_support = _load_module(
            "contract_support_under_test",
            os.path.join(REPO_ROOT, "tests", "contract", "contract_support.py"),
        )
        a = list(check_repo.REQUIRED_CARD_SECTIONS)
        b = list(contract_support.REQUIRED_CARD_SECTIONS)
        assert a == b, (
            "tools/check_repo.py and tests/contract/contract_support.py name "
            f"different required card sections: {a} vs {b} — the two lists "
            "must stay in step"
        )



class CheckRepoSubModuleListTest(unittest.TestCase):
    """The naming check reads the **Sub-modules:** list, not the prose.

    A sub-module is named only as an element of the parent README's
    `**Sub-modules:**` line. Each test plants one fault in a scratch copy of
    the tree, runs the real `check_repo.py` on it, and watches it name the
    fault; the restore step watches the tree go clean again.
    """

    @classmethod
    def setUpClass(cls):
        cls.scratch = _scratch_copy()

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.scratch, ignore_errors=True)

    def _plant(self, kind):
        readme = os.path.join(self.scratch, "android", "README.md")
        with open(readme, encoding="utf-8") as fh:
            original = fh.read()
        if kind == "app":
            text = original.replace("app, ui,", "ui,", 1)
        elif kind == "ui":
            text = original.replace(", ui,", ",", 1)
        elif kind == "missing-line":
            text = "\n".join(
                line for line in original.splitlines()
                if not line.lstrip().startswith("**Sub-modules:**")
            ) + "\n"
        elif kind == "stale":
            text = original.replace("app, ui,", "app, ui, widgets,", 1)
        else:
            raise AssertionError(f"unknown plant {kind!r}")
        with open(readme, "w", encoding="utf-8") as fh:
            fh.write(text)
        return readme, original

    def _restore(self, readme, original):
        with open(readme, "w", encoding="utf-8") as fh:
            fh.write(original)

    def test_app_removed_from_the_list_is_an_error(self):
        """Dropping "app" from the list is an error even though the word
        still appears in the README's prose."""
        readme, original = self._plant("app")
        try:
            rc, out = _run_check(self.scratch)
            assert rc != 0, (
                "check_repo stayed clean after 'app' was removed from the "
                "**Sub-modules:** list — the word in the README prose fooled "
                "the check into thinking the module was named"
            )
            assert "does not name sub-module 'app'" in out, (
                f"check_repo failed but did not name 'app': {out!r}"
            )
        finally:
            self._restore(readme, original)

    def test_ui_removed_from_the_list_is_an_error(self):
        """Dropping "ui" from the list is an error even though the word
        still appears in the README's prose."""
        readme, original = self._plant("ui")
        try:
            rc, out = _run_check(self.scratch)
            assert rc != 0, (
                "check_repo stayed clean after 'ui' was removed from the "
                "**Sub-modules:** list — the word in the README prose fooled "
                "the check into thinking the module was named"
            )
            assert "does not name sub-module 'ui'" in out, (
                f"check_repo failed but did not name 'ui': {out!r}"
            )
        finally:
            self._restore(readme, original)

    def test_missing_sub_modules_line_is_an_error(self):
        """A parent README with no **Sub-modules:** line at all is an error,
        not a pass: with no list there is nothing to name the sub-modules."""
        readme, original = self._plant("missing-line")
        try:
            rc, out = _run_check(self.scratch)
            assert rc != 0, (
                "check_repo stayed clean after the **Sub-modules:** line was "
                "deleted — no list is no naming"
            )
            assert "**Sub-modules:**" in out, (
                f"check_repo failed but did not name the missing list line: "
                f"{out!r}"
            )
        finally:
            self._restore(readme, original)

    def test_stale_name_in_the_list_is_an_error(self):
        """A name in the **Sub-modules:** list that is not a registered
        sub-module of that parent is a stale list and an error."""
        readme, original = self._plant("stale")
        try:
            rc, out = _run_check(self.scratch)
            assert rc != 0, (
                "check_repo stayed clean after a stale name ('widgets') was "
                "added to the **Sub-modules:** list"
            )
            assert "widgets" in out, (
                f"check_repo failed but did not name the stale entry: {out!r}"
            )
        finally:
            self._restore(readme, original)

    def test_restored_tree_is_clean_after_every_plant(self):
        rc, out = _run_check(self.scratch)
        assert rc == 0, f"check_repo is not clean on the unmodified tree: {out}"

class CheckRepoDeadModuleTest(unittest.TestCase):
    """Dead-module guards + sub-module naming.

    A dead module (registry status == "dead") must still have DEAD_CODE.md,
    must not be include()d or carry a project() edge to it, and — being a
    registered module — must be named in the README of its nearest ancestor
    home that carries a **Sub-modules:** list. These tests plant faults in a
    scratch copy, run the real check_repo.py on it, watch the fault get name,
    then restore. Every failure case fails against the earlier code and passes
    only after the fix; the passing cases pass on both, and a check that matches
    by substring instead of the exact path turns them red.

    The scratch tree is per-test: each plant saves what it changes, runs the
    checker, asserts, and restores in `finally` so no mutation leaks between
    tests or into the other test classes.
    """

    # ---- fixtures ---------------------------------------------------------

    _SECTIONS = [
        "# Purpose", "# Owns", "# Does Not Own", "# Public Interface",
        "# Depends On", "# Invariants", "# Test Locations",
        "# Test Requirement", "# Known Gotchas",
    ]

    def _make_fixture_parent(self, submodules):
        parent = os.path.join(self.scratch, "android", "modules", "fixture")
        os.makedirs(parent, exist_ok=True)
        line = "**Sub-modules:** " + ", ".join(submodules) + " — each with its own AGENTS.md + README.md"
        with open(os.path.join(parent, "README.md"), "w", encoding="utf-8") as fh:
            fh.write("# fixture — README\n\n" + line + "\n")

    def setUp(self):
        self.scratch = _scratch_copy()

    def tearDown(self):
        shutil.rmtree(self.scratch, ignore_errors=True)

    # ---- helpers ----------------------------------------------------------

    def _write_card(self, folder):
        """Write an AGENTS.md carrying all nine required sections."""
        text = (
            "# AGENTS.md — {n}\n\n"
            "## Purpose\n\nA demo module.\n\n"
            "## Owns\nIts behaviour.\n\n"
            "## Does Not Own\nNothing else.\n\n"
            "## Public Interface\nPublicThing\n\n"
            "## Depends On\n- android (registered in modules.toml)\n- android_core (registered in modules.toml)\n\n"
            "## Invariants\n- nothing bad happens.\n\n"
            "## Test Locations\n- unit (Kotlin): src/test/kotlin/\n"
            "## Test Requirement\nBreak protected behaviour, confirm red, restore.\n\n"
            "## Known Gotchas\n- none\n"
        ).format(n=os.path.basename(folder))
        with open(os.path.join(folder, "AGENTS.md"), "w", encoding="utf-8") as fh:
            fh.write(text)

    def _make_module(self, path, dead=False, dead_code_present=None):
        """Create the on-disk folder (AGENTS.md + README.md) for a module.

        `dead_code_present`: None -> no DEAD_CODE.md; True -> non-empty;
        False -> empty file present.
        """
        folder = os.path.join(self.scratch, path)
        os.makedirs(folder, exist_ok=True)
        self._write_card(folder)
        with open(os.path.join(folder, "README.md"), "w", encoding="utf-8") as fh:
            fh.write(f"# {os.path.basename(path)} — README\n")
        if dead_code_present is None:
            return
        dc = os.path.join(folder, "DEAD_CODE.md")
        with open(dc, "w", encoding="utf-8") as fh:
            fh.write("Dead code: retained for compatibility.\n" if dead_code_present else "\n   \n")

    def _toml_path(self):
        return os.path.join(self.scratch, "modules.toml")

    def _commit_readme_path(self):
        return os.path.join(self.scratch, "android", "modules", "commit", "README.md")

    # ---- RED cases --------------------------------------------------------

    def test_dead_module_without_dead_code_md_is_an_error(self):
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            self._make_module("android/modules/fixture/fixture_dead", dead=False, dead_code_present=None)
            # register it as a DEAD module, not included in settings.
            self._add_module_table(status="dead")
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"dead module without DEAD_CODE.md stayed clean: {out!r}"
            assert "has no DEAD_CODE.md" in out, f"expected the missing DEAD_CODE.md error: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_dead_module_empty_dead_code_md_is_an_error(self):
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            # DEAD_CODE.md present but blank.
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=False)
            self._add_module_table(status="dead")
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"blank DEAD_CODE.md stayed clean: {out!r}"
            assert "empty" in out.lower(), f"expected 'empty' message: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_dead_module_still_included_is_an_error(self):
        readme = self._commit_readme_path()
        settings = os.path.join(self.scratch, "settings.gradle.kts")
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            # DEAD_CODE.md present (non-empty); add the include of the dead child.
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            with open(settings, "a", encoding="utf-8") as fh:
                fh.write('include(":android:modules:fixture:fixture_dead")\n')
            self._add_module_table(status="dead")
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"dead module included via settings stayed clean: {out!r}"
            assert "include()d" in out, f"expected include()d error: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_live_module_dependson_dead_via_registry_is_an_error(self):
        # A LIVE module (fixture_live) now depends_on the dead child via registry.
        self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
        self._add_module_table(status="dead")
        self._add_live_fixture()  # register the live fixture table first
        # append the dead key to fixture_live's registry depends_on array
        with open(self._toml_path(), encoding="utf-8") as fh:
            text = fh.read()
        import re
        pat = re.compile(
            r"(\[module\.android_fixture_live\][^\[]*?depends_on\s*=\s*)\[.*?\]",
            re.S,
        )
        text = pat.sub(lambda m: m.group(1) + '["android", "android_core", "android_fixture_dead"]', text, count=1)
        with open(self._toml_path(), "w", encoding="utf-8") as fh:
            fh.write(text)
        rc, out = _run_check(self.scratch)
        assert rc != 0, f"registry depends_on to dead stayed clean: {out!r}"
        assert "depends_on names dead" in out, f"expected registry dep error: {out!r}"

    def test_live_module_dependson_dead_via_project_edge_is_an_error(self):
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            # DEAD_CODE.md present; add an EXACT project() edge to the dead child
            # in a live sibling's build.gradle.kts.
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            self._add_module_table(status="dead")
            live_build = os.path.join(self.scratch, "android", "modules", "fixture", "fixture_live", "build.gradle.kts")
            os.makedirs(os.path.dirname(live_build), exist_ok=True)
            with open(live_build, "w", encoding="utf-8") as fh:
                fh.write(
                    "// android/modules/fixture/fixture_live\n"
                    "dependencies {\n"
                    '    implementation(project(":android:modules:fixture:fixture_dead"))\n'
                    "}\n"
                )
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"project() edge to dead stayed clean: {out!r}"
            assert "project() edge" in out, f"expected project() edge error: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_unknown_status_typo_is_an_error(self):
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            self._make_module("android/modules/fixture/fixture_dead", dead=False, dead_code_present=False)
            # register it with an invalid status value.
            self._add_module_table(status="deadd")
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"unknown status stayed clean: {out!r}"
            assert "unknown status" in out, f"expected 'unknown status' error: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_dead_submodule_exact_edge_is_caught(self):
        """The EXACT project() edge to the dead child IS caught."""
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            self._add_dead_fixture()  # register the dead fixture
            self._make_module("android/modules/fixture/fixture_live", dead=False, dead_code_present=False)
            self._add_live_fixture()  # register the live sibling
            self._set_fixture_readme_submodules(["fixture_dead", "fixture_live"])
            with open(os.path.join(self.scratch, "android", "modules", "fixture", "fixture_live", "build.gradle.kts"), "w", encoding="utf-8") as fh:
                fh.write(
                    "// android/modules/fixture/fixture_live\n"
                    "dependencies {\n"
                    '    implementation(project(":android:modules:fixture:fixture_dead"))\n'
                    "}\n"
                )
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"exact project() edge to dead stayed clean: {out!r}"
            assert "project() edge" in out, f"expected exact-edge error: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    # ---- exact-path match (a substring match fails these) --------------

    def test_dead_submodule_exact_path_not_matched_by_sibling_edge(self):
        """A live sibling's project() edge to :android:modules:fixture:fixture_live
        must NOT be read as depending on the dead child fixture_dead."""
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            # dead child 'fixture_dead' registered, DEAD_CODE present, not included.
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            self._add_dead_fixture()
            # live sibling 'fixture_live' with an EXACT project() edge to ITSELF.
            self._make_module("android/modules/fixture/fixture_live", dead=False, dead_code_present=False)
            self._add_live_fixture()
            self._set_fixture_readme_submodules(["fixture_live", "fixture_dead"])
            with open(os.path.join(self.scratch, "android", "modules", "fixture", "fixture_live", "build.gradle.kts"), "w", encoding="utf-8") as fh:
                fh.write(
                    "// android/modules/fixture/fixture_live\n"
                    "plugins { libs.plugins.android.library }\n"
                    "}\n"
                    "kotlin {}\n"
                    "dependencies {\n"
                    '    implementation(project(":android:modules:fixture:fixture_live"))\n'
                    "}\n"
                )
            rc, out = _run_check(self.scratch)
            assert rc == 0, f"sibling edge wrongly flagged the dead child: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_dead_path_prefix_not_matched(self):
        """A live module's project() edge ":android:modules:fixture" (a string
        prefix of the child path) must NOT match the dead child fixture_dead."""
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            self._add_module_table(status="dead")
            self._set_fixture_readme_submodules(["fixture_dead"])
            # project() edge to the parent path — a string prefix of the child.
            live_build = os.path.join(self.scratch, "android", "modules", "fixture", "fixture_live", "build.gradle.kts")
            os.makedirs(os.path.dirname(live_build), exist_ok=True)
            with open(live_build, "a", encoding="utf-8") as fh:
                fh.write('    implementation(project(":android:modules:fixture"))\n')
            rc, out = _run_check(self.scratch)
            assert rc == 0, f"parent edge wrongly matched the dead child: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    # ---- sub-module naming ----------------------------------------------

    def _add_module_table(self, key="android_fixture_dead", path="android/modules/fixture/fixture_dead", status="dead"):
        """Append a [module.<key>] table to the scratch modules.toml.

        `path` is the module's on-disk path (e.g. "android/modules/fixture/fixture_dead").
        `status`: "dead" registers dead; "live"/None registers live (no status line).
        """
        with open(self._toml_path(), "rb") as fh:
            text = fh.read().decode("utf-8")
        card_rel = os.path.join(path, "AGENTS.md")
        block = (
            "[module.%s]\n" % key
            + 'path = "%s"\n' % path
            + ('status = "%s"\n' % status if status else "")
            + 'depends_on = ["android", "android_core"]\n'
            + 'public = "X"\n'
            + 'card = "%s"\n' % card_rel
        )
        with open(self._toml_path(), "w", encoding="utf-8") as fh:
            fh.write(text + "\n\n" + block)

    def _add_dead_fixture(self):
        """Register the dead fixture android/modules/fixture/fixture_dead."""
        self._add_module_table("android_fixture_dead", "android/modules/fixture/fixture_dead", status="dead")

    def _add_live_fixture(self):
        """Register the live fixture android/modules/fixture/fixture_live."""
        self._add_module_table(
            "android_fixture_live", "android/modules/fixture/fixture_live", status=None
        )

    def _set_fixture_readme_submodules(self, names):
        """Write the fixture parent README's **Sub-modules:** line naming `names`."""
        self._make_fixture_parent(names)

    def _restore_commit_readme(self, saved):
        with open(self._commit_readme_path(), "w", encoding="utf-8") as fh:
            fh.write(saved)

    def test_dead_submodule_must_be_named_in_direct_parent_readme(self):
        """A dead child 'fixture_dead' must be named in its DIRECT parent (fixture)'s README;
        omitting it from the list is an error."""
        saved = self._read_commit_readme()
        try:
            # fixture parent names fixture_live but NOT fixture_dead.
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            self._add_module_table(status="dead")
            self._set_fixture_readme_submodules(["fixture_live"])
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"dead child not named in direct parent README stayed green: {out!r}"
            assert "fixture_dead" in out, f"expected the omit'd leaf named: {out!r}"
        finally:
            self._restore_commit_readme(saved)

    def test_dead_submodule_named_in_direct_parent_readme_is_clean(self):
        """Dead child 'fixture_dead' present, DEAD_CODE present, and named in fixture's README."""
        saved = self._read_commit_readme()
        try:
            self._make_module("android/modules/fixture/fixture_dead", dead=True, dead_code_present=True)
            self._add_live_fixture()
            self._make_module("android/modules/fixture/fixture_live", dead=False, dead_code_present=False)
            self._add_module_table(status="dead")
            self._set_fixture_readme_submodules(["fixture_live", "fixture_dead"])
            rc, out = _run_check(self.scratch)
            assert rc == 0, f"named-and-clean dead child failed: {out!r}"
        finally:
            self._restore_commit_readme(saved)

    def test_nested_submodule_named_in_direct_parent_not_top(self):
        """A LIVE submodule 'fixture_live' under fixture is named in fixture's README;
        android/README does not name it — proving the naming home is the direct parent,
        not the top. Must stay clean."""
        readme = self._commit_readme_path()
        with open(readme) as fh:
            original_readme = fh.read()
        try:
            # live sibling fixture_live registered + real folder present.
            self._make_module("android/modules/fixture/fixture_live", dead=False, dead_code_present=False)
            self._add_live_fixture()
            # fixture/README names 'fixture_live' (its direct parent naming).
            self._set_fixture_readme_submodules(["fixture_live"])
            rc, out = _run_check(self.scratch)
            assert rc == 0, f"named-in-direct-parent live submodule failed: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original_readme)

    def test_control_live_module_not_included_still_errors(self):
        """A LIVE module dropped from the android/README **Sub-modules:** list still
        errors — the naming rule was not loosened for live modules."""
        readme = os.path.join(self.scratch, "android", "README.md")
        with open(readme) as fh:
            original = fh.read()
        try:
            # drop 'app' from the android/README list.
            text = original.replace("app, ui,", "ui,", 1)
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(text)
            rc, out = _run_check(self.scratch)
            assert rc != 0, f"live module omitted from the list stayed green: {out!r}"
            assert "app" in out, f"expected 'app' named: {out!r}"
        finally:
            with open(readme, "w", encoding="utf-8") as fh:
                fh.write(original)

    def test_restored_tree_is_clean_after_every_plant(self):
        rc, out = _run_check(self.scratch)
        assert rc == 0, f"check_repo is not clean on the unmodified tree: {out}"

    # ---- small read helper ------------------------------------------------

    def _read_commit_readme(self):
        with open(self._commit_readme_path()) as fh:
            return fh.read()
