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

if __name__ == "__main__":
    unittest.main()
