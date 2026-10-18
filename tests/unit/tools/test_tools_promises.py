#!/usr/bin/env python3
"""Promise tests for tools/check_repo.py and tools/gate.py.

Each test holds one quotable promise of the two tools to the code: a
failure-mode test is written to FAIL while the defect it pins is present,
against a promise quoted by function name and wording; the
documented-behaviour tests pass and back the current behavior, so a
reviewer can re-run them.

Fakes only: every fixture is a temp-dir tree or a copied check_repo.py, the
same discipline as test_gate.py / test_check_repo.py. No Gradle, no network,
no load.

Run: python3 -m unittest tests.unit.tools.test_tools_promises -v
"""

import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
GATE_PATH = os.path.join(ROOT, "tools", "gate.py")
CHECK_REPO_PATH = os.path.join(ROOT, "tools", "check_repo.py")

_spec = importlib.util.spec_from_file_location("gate_under_test_promises", GATE_PATH)
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)


def _git(root, *args):
    subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=t"] + list(args),
        cwd=root, check=True, stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def _write(path, text, mode=0o644):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)
    os.chmod(path, mode)


def _copy_check_repo(dst):
    """Copy the real check_repo.py into a scratch tree so ROOT resolves there."""
    os.makedirs(os.path.join(dst, "tools"), exist_ok=True)
    shutil.copy(CHECK_REPO_PATH, os.path.join(dst, "tools", "check_repo.py"))


def _run_check(dst):
    proc = subprocess.run(
        [sys.executable, os.path.join(dst, "tools", "check_repo.py")],
        capture_output=True, text=True,
    )
    return proc.returncode, proc.stdout, proc.stderr


# a card carrying every REQUIRED_CARD_SECTIONS heading, so a scratch tree is
# consistent except for the one fault under test
CARD = (
    "## Purpose\np\n## Owns\no\n## Does Not Own\nd\n"
    "## Public Interface\ni\n## Depends On\nnone\n## Invariants\ni\n"
    "## Test Locations\nt\n## Test Requirement\nr\n## Known Gotchas\ng\n"
)


class _CheckRepoTree:
    """A minimal consistent tree: one module 'x' with card + README."""

    def __init__(self):
        self.root = tempfile.mkdtemp(prefix="crtree")
        _copy_check_repo(self.root)
        _write(os.path.join(self.root, "modules.toml"),
               '[module.x]\npath = "x"\n')
        _write(os.path.join(self.root, "x", "AGENTS.md"), CARD)
        _write(os.path.join(self.root, "x", "README.md"), "x\n")
        _write(os.path.join(self.root, "README.md"), "root\n")

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


# ---------------------------------------------------------------------------
# gate.step_nul — a tracked NON-ASCII filename is mislabelled a NUL byte
# ---------------------------------------------------------------------------

class NonAsciiFilenameIsNotANulByte(unittest.TestCase):
    """gate.step_nul + the gate module docstring + step_nul's docstring.

    git ls-files C-quotes a non-ASCII path (e.g. "caf\\303\\251.md"); open()
    on the quoted line raises OSError; the except branch appends the file to
    `bad`, so a clean tree is reported as holding a NUL byte it does not have.
    Promise: the gate module docstring — the RED condition is "a tracked file
    of the checked types contains a NUL byte"; step_nul's docstring keeps a
    git failure and a found NUL byte as distinct reasons.
    """

    def test_clean_non_ascii_tracked_file_is_not_a_nul_byte(self):
        root = tempfile.mkdtemp(prefix="nulname")
        try:
            _git(root, "init", "-q")
            _write(os.path.join(root, "caf\u00e9.md"), "hello\n")  # café.md
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "base")
            out = []
            code, reason = gate.step_nul(root, out)
            text = "\n".join(out)
            self.assertEqual(
                code, 0,
                "a tracked non-ASCII-named file with NO NUL byte was reported "
                f"as a NUL byte (git ls-files C-quotes the name; open() raises "
                f"OSError; the except branch labels it NUL). reason={reason!r}\n{text}")
            self.assertIsNone(reason)
            # "NUL byte" alone would also match the clean-tree line
            # "step 6 nul: clean (0 NUL bytes in tracked files)"; anchor on the
            # RED prefix so the assertion is false on a clean tree and true only
            # when a NUL byte is actually reported.
            self.assertNotIn("RED - NUL byte", text)
            # Positive case: the same non-ASCII-named file that now HOLDS a
            # NUL byte must still be reported. A fix that drops non-ASCII
            # names from the check entirely would pass the first half and
            # fail here.
            with open(os.path.join(root, "caf\u00e9.md"), "ab") as fh:
                fh.write(b"\x00")
            out2 = []
            code2, reason2 = gate.step_nul(root, out2)
            text2 = "\n".join(out2)
            self.assertEqual(
                code2, 1,
                "a non-ASCII-named file that holds a NUL byte was not "
                f"reported. reason={reason2!r}\n{text2}")
            self.assertIn("NUL byte in caf\u00e9.md", text2)
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# gate.step_nul — a DELETED tracked file is mislabelled a NUL byte
# ---------------------------------------------------------------------------

class DeletedTrackedFileIsSkipped(unittest.TestCase):
    """gate.step_nul (the except-OSError branch) + its docstring.

    A file still in `git ls-files` but gone from the worktree has no bytes to
    check. On the unfixed tool, open() raises OSError and the except branch
    labels it "NUL byte in <file>"; a missing file contains no bytes. The fix
    SKIPS a missing path (a working tree may hold an unstaged deletion) and
    keeps the step clean when nothing else is wrong.
    Promise: step_nul's docstring — a git failure, an unreadable file and a
    found NUL byte are distinct reasons; a deleted file is none of them.
    """

    def test_deleted_tracked_file_is_skipped_not_a_nul_byte(self):
        root = tempfile.mkdtemp(prefix="nuldel")
        try:
            _git(root, "init", "-q")
            _write(os.path.join(root, "a.py"), "x = 1\n")
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "base")
            os.remove(os.path.join(root, "a.py"))  # tracked, deleted from worktree
            out = []
            code, reason = gate.step_nul(root, out)
            text = "\n".join(out)
            self.assertNotIn(
                "NUL byte in a.py", text,
                "a deleted tracked file was labelled 'NUL byte in a.py'; a "
                "missing file contains no bytes (OSError conflated with a NUL "
                f"byte). reason={reason!r}\n{text}")
            self.assertEqual(
                code, 0,
                "a deleted tracked file should be SKIPPED (no bytes to check) "
                "and leave the step clean; it was reported instead. "
                f"reason={reason!r}\n{text}")
        finally:
            shutil.rmtree(root, ignore_errors=True)


class UnreadableTrackedPathIsNotANulByte(unittest.TestCase):
    """gate.step_nul — a tracked path that is now a DIRECTORY is unreadable.

    New branch the fix adds: an open() that fails for a reason other than the
    file being gone (here IsADirectoryError, an OSError) is a distinct RED
    reason, "unreadable tracked file(s)", never labelled a NUL byte. On the
    unfixed tool the same path falls into the except-OSError branch and is
    labelled "NUL byte in <path>". A root chmod 000 would not deny the read,
    so a directory is used: it fails for everyone.
    Promise: step_nul's docstring — git failure / unreadable / NUL byte stay
    distinct reasons.
    """

    def test_tracked_path_replaced_by_a_directory_is_unreadable_not_nul(self):
        root = tempfile.mkdtemp(prefix="nuldird")
        try:
            _git(root, "init", "-q")
            _write(os.path.join(root, "d.py"), "x = 1\n")
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "base")
            # replace the tracked file with a directory: open() raises
            # IsADirectoryError (an OSError) for a path that still exists
            os.remove(os.path.join(root, "d.py"))
            os.mkdir(os.path.join(root, "d.py"))
            out = []
            code, reason = gate.step_nul(root, out)
            text = "\n".join(out)
            self.assertNotIn(
                "NUL byte in d.py", text,
                "a tracked path that is now a directory was labelled "
                f"'NUL byte in d.py'; it is unreadable, not a NUL byte. "
                f"reason={reason!r}\n{text}")
            self.assertEqual(
                code, 1,
                "an unreadable tracked path must be a RED reason (the file "
                f"cannot be read). reason={reason!r}\n{text}")
            self.assertIn("unreadable", text,
                          "the unreadable reason must be named, distinct from "
                          f"a NUL byte. reason={reason!r}\n{text}")
        finally:
            shutil.rmtree(root, ignore_errors=True)


# ---------------------------------------------------------------------------
# check_repo cycle check — unregistered depends_on crashes instead of listing
# ---------------------------------------------------------------------------

class UnregisteredDependsOnIsListed(unittest.TestCase):
    """check_repo's cycle check (the DFS over depends_on) + the module docstring.

    A module whose depends_on names an unregistered module is listed as an
    error in the module loop above, but the DFS reads color[v] for the
    unknown key and raises KeyError; the process exits 1 with EMPTY stdout,
    so no problems are listed.
    Promise: the check_repo module docstring — "Exit code 1 = problems
    listed."
    """

    def test_unregistered_dep_is_listed_not_a_traceback(self):
        tree = _CheckRepoTree()
        try:
            with open(os.path.join(tree.root, "modules.toml"), "w") as fh:
                fh.write('[module.x]\npath = "x"\ndepends_on = ["ghost"]\n')
            rc, out, err = _run_check(tree.root)
            self.assertNotEqual(rc, 0, "expected a non-zero exit for the fault")
            self.assertNotIn(
                "Traceback", out + err,
                "check_repo crashed with a traceback instead of listing the "
                f"problem. stderr tail: {err[-300:]!r}")
            self.assertNotIn("KeyError", out + err)
            self.assertIn(
                "ghost", out,
                "the unregistered dependency 'ghost' was not listed in stdout "
                "(the DFS raised before the error list was printed). "
                f"stdout={out!r}")
        finally:
            tree.close()


# ---------------------------------------------------------------------------
# check_repo doc-ref check — bare numbered refs are never resolved
# ---------------------------------------------------------------------------

class BareNumberedDocRefIsResolved(unittest.TestCase):
    """check_repo's README doc-reference check + its section comment + the
    module docstring.

    The root README references the numbered specs in BARE form (docs/00–
    docs/07, no slug). The doc-ref regex requires a dash after the digits, so
    a bare numbered ref is never resolved: deleting the referenced spec file
    changes nothing and the check stays green.
    Promise: the doc-ref section comment — "README doc references resolve";
    the module docstring — "Fails if the tree drifts."
    """

    def _tree_with_spec(self, readme_ref):
        tree = _CheckRepoTree()
        os.makedirs(os.path.join(tree.root, "docs"), exist_ok=True)
        _write(os.path.join(tree.root, "docs", "07-ui-ux-look-and-feel.md"),
               "spec 07\n")
        _write(os.path.join(tree.root, "README.md"),
               f"See {readme_ref} for the UI spec.\n")
        return tree

    def test_bare_numbered_ref_to_a_deleted_spec_is_caught(self):
        tree = self._tree_with_spec("`docs/07`")
        try:
            os.remove(os.path.join(tree.root, "docs", "07-ui-ux-look-and-feel.md"))
            rc, out, err = _run_check(tree.root)
            self.assertIn(
                "missing doc", out,
                "deleting the numbered spec the README references (bare "
                "`docs/07`) was not reported; the doc-ref regex "
                r"docs/\d+-[^\`] only matches dash+slug refs, so the bare "
                f"numbered ref passes silently. stdout={out!r}")
        finally:
            tree.close()

    def test_dash_slug_ref_to_a_deleted_spec_is_caught(self):
        # control: the same deletion IS caught when the ref carries the slug
        tree = self._tree_with_spec("`docs/07-ui-ux-look-and-feel.md`")
        try:
            os.remove(os.path.join(tree.root, "docs", "07-ui-ux-look-and-feel.md"))
            rc, out, err = _run_check(tree.root)
            self.assertIn(
                "missing doc", out,
                "the dash+slug doc ref to a deleted spec should be reported "
                f"(control for the bare-ref gap). stdout={out!r}")
        finally:
            tree.close()


# ---------------------------------------------------------------------------
# documented behaviour (tests that pass; back the documented behavior)
# ---------------------------------------------------------------------------

class NulStepDocumentedBehavior(unittest.TestCase):
    """The NUL step catches what its docstring names and skips what it does not."""

    def _gitroot(self):
        root = tempfile.mkdtemp(prefix="nulnb")
        _git(root, "init", "-q")
        return root

    def test_nul_byte_in_kt_is_caught(self):
        root = self._gitroot()
        try:
            _write(os.path.join(root, "Main.kt"), "class Main\n")
            with open(os.path.join(root, "Main.kt"), "ab") as fh:
                fh.write(b"\x00")
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "b")
            out = []
            code, reason = gate.step_nul(root, out)
            self.assertEqual(code, 1)
            self.assertIn("NUL byte in Main.kt", "\n".join(out))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_nul_byte_in_yml_is_not_checked(self):
        # the docstring names .yaml, not .yml; a NUL in a .yml file is outside
        # the documented type set and is not reported (behavior matches docs)
        root = self._gitroot()
        try:
            _write(os.path.join(root, "flow.yml"), "a: 1\n")
            with open(os.path.join(root, "flow.yml"), "ab") as fh:
                fh.write(b"\x00")
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "b")
            out = []
            code, reason = gate.step_nul(root, out)
            self.assertEqual(code, 0,
                             "a NUL in a .yml file is outside the documented "
                             "checked types (.kt/.kts/.py/.md/.toml/.yaml)")
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_empty_tracked_file_passes(self):
        root = self._gitroot()
        try:
            _write(os.path.join(root, "empty.md"), "")
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "b")
            out = []
            code, reason = gate.step_nul(root, out)
            self.assertEqual(code, 0)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def test_path_with_spaces_passes(self):
        root = self._gitroot()
        try:
            _write(os.path.join(root, "notes file.md"), "ok\n")
            _git(root, "add", "-A")
            _git(root, "commit", "-qm", "b")
            out = []
            code, reason = gate.step_nul(root, out)
            self.assertEqual(code, 0,
                             "a tracked file whose name has a space (no NUL) "
                             f"must pass. reason={reason!r}")
        finally:
            shutil.rmtree(root, ignore_errors=True)


class CheckRepoDocumentedBehavior(unittest.TestCase):
    """check_repo names the faults its docstring and rules promise."""

    def test_missing_readme_is_named(self):
        tree = _CheckRepoTree()
        try:
            os.remove(os.path.join(tree.root, "x", "README.md"))
            rc, out, err = _run_check(tree.root)
            self.assertNotEqual(rc, 0)
            self.assertIn("README.md missing", out)
        finally:
            tree.close()

    def test_consistent_tree_is_green(self):
        tree = _CheckRepoTree()
        try:
            rc, out, err = _run_check(tree.root)
            self.assertEqual(rc, 0, f"expected green, got: {out}{err}")
            self.assertIn("REPO CONSISTENT", out)
        finally:
            tree.close()


if __name__ == "__main__":
    unittest.main()
