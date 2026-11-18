"""Executable-mode pins for the Breaker deploy entry scripts.

install.sh, update.sh and rollback.sh are the operator entry points and must each
be a regular file carrying the owner-execute bit (mode 100755). deploy-common.sh
is SOURCED by its siblings, so only its regularity is pinned; an execute bit is
not required. Every other pre-existing sibling .sh is pinned executable too, so
a future addition cannot land non-executable unnoticed.

The real tree (server/modules/deploy/scripts) is located relative to the
repository root derived from __file__; ~/.hermes is never consulted. Mode bits
come from os.stat, never from git, so the pin measures what an operator's
filesystem actually enforces.

Run: python3 -m unittest discover -s tests/unit/server/deploy
"""
import os
import shutil
import stat
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
SCRIPTS_DIR = REPO_ROOT / "server" / "modules" / "deploy" / "scripts"

# Operator entry points: the card tells the operator to RUN them.
ENTRY_SCRIPTS = ("install.sh", "update.sh", "rollback.sh")

# Sourced helpers: never executed, so no execute bit is required of them.
SOURCED_SCRIPTS = ("deploy-common.sh",)


def require_regular_file(path):
    """Return os.stat(PATH); raise AssertionError unless it is a regular file."""
    st = os.stat(path)
    if not stat.S_ISREG(st.st_mode):
        raise AssertionError("%s is not a regular file" % path)
    return st


def require_owner_executable(path):
    """Require PATH to be a regular file with the owner-execute bit set.

    Raises AssertionError naming PATH otherwise. Explicit raises (not bare
    `assert`) keep the pin alive under `python -O`.
    """
    st = require_regular_file(path)
    if not (st.st_mode & stat.S_IXUSR):
        raise AssertionError(
            "%s has no owner-execute bit (mode %o)"
            % (path, stat.S_IMODE(st.st_mode))
        )
    return st


class EntryScriptModePinTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(
            SCRIPTS_DIR.is_dir(), "scripts directory missing: %s" % SCRIPTS_DIR
        )

    def test_repo_root_is_derived_from_this_file_not_hermes_home(self):
        self.assertEqual(
            str(SCRIPTS_DIR),
            os.path.join(str(REPO_ROOT), "server", "modules", "deploy", "scripts"),
        )
        self.assertNotIn(".hermes", str(REPO_ROOT))

    def test_entry_scripts_are_regular_and_owner_executable(self):
        for name in ENTRY_SCRIPTS:
            with self.subTest(script=name):
                st = require_owner_executable(SCRIPTS_DIR / name)
                self.assertTrue(stat.S_ISREG(st.st_mode))

    def test_sourced_common_is_regular_and_needs_no_execute_bit(self):
        for name in SOURCED_SCRIPTS:
            with self.subTest(script=name):
                st = require_regular_file(SCRIPTS_DIR / name)
                self.assertTrue(stat.S_ISREG(st.st_mode))

    def test_every_other_preexisting_script_is_owner_executable(self):
        pinned = set(ENTRY_SCRIPTS) | set(SOURCED_SCRIPTS)
        others = sorted(
            entry.name for entry in SCRIPTS_DIR.glob("*.sh")
            if entry.name not in pinned
        )
        self.assertTrue(others, "no other sibling .sh found to pin")
        for name in others:
            with self.subTest(script=name):
                require_owner_executable(SCRIPTS_DIR / name)

    def test_control_copy_chmod_755_satisfies_the_pin(self):
        # CONTROL: a copy made 0755 must satisfy the very same assertion, so a
        # RED result on the tree cannot be a broken checker.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        dst = os.path.join(tmp, "install.sh")
        shutil.copyfile(SCRIPTS_DIR / "install.sh", dst)
        os.chmod(dst, 0o755)
        st = require_owner_executable(dst)
        self.assertTrue(stat.S_ISREG(st.st_mode))
        self.assertTrue(st.st_mode & stat.S_IXUSR)

    def test_pin_rejects_a_copy_with_the_execute_bit_cleared(self):
        # RED-FIRST PROOF: the same assertion must FAIL, naming the file, once
        # the owner-execute bit is gone. copyfile carries no mode bits, and the
        # chmod makes the cleared state explicit.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        dst = os.path.join(tmp, "install.sh")
        shutil.copyfile(SCRIPTS_DIR / "install.sh", dst)
        os.chmod(dst, 0o644)
        with self.assertRaises(AssertionError) as ctx:
            require_owner_executable(dst)
        self.assertIn("install.sh", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
