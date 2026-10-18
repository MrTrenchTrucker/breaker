"""Temp-tree tests for the dead-module contract rules.

The per-module methods in contract_support read the module global ROOT. These
tests build a minimal temp tree with one dead module (android/modules/commit/ime)
and one live sibling (android/modules/commit/accessibility), point
contract_support.ROOT at it, and call the methods directly. No pytest: run with
`python3 -m unittest tests.unit.tools.test_contract_dead -v`.
"""
import os
import shutil
import sys
import tempfile
import unittest

_REPO_ROOT = os.path.abspath(
     os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(__file__))))
)
sys.path.insert(0, os.path.join(_REPO_ROOT, "tests", "contract"))
import contract_support  # noqa: E402

DEAD_KEY = "android_commit_ime"
DEAD_PATH = "android/modules/commit/ime"
LIVE_KEY = "android_commit_accessibility"
LIVE_PATH = "android/modules/commit/accessibility"

CARD = (
    "## Purpose\nDemo module.\n\n"
    "## Owns\nIts behaviour.\n\n"
    "## Does Not Own\nNothing else.\n\n"
    "## Public Interface\nPublicThing\n\n"
    "## Depends On\n- android\n- android_core\n\n"
    "## Invariants\n- nothing bad happens.\n\n"
    "## Test Locations\n{test_locations}\n\n"
    "## Test Requirement\nBreak protected behaviour, confirm red, restore.\n\n"
    "## Known Gotchas\n- none\n"
)

TOML = (
    "[module.android_commit_ime]\n"
    'path = "android/modules/commit/ime"\n'
    'status = "dead"\n'
    'depends_on = ["android", "android_core"]\n'
    'public = "X"\n'
    'card = "android/modules/commit/ime/AGENTS.md"\n'
    "\n"
    "[module.android_commit_accessibility]\n"
    'path = "android/modules/commit/accessibility"\n'
    'depends_on = ["android", "android_core"]\n'
    'public = "X"\n'
    'card = "android/modules/commit/accessibility/AGENTS.md"\n'
)


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def _card_for(test_locations):
    return CARD.format(test_locations=test_locations)


def _build_tree(root, include_dead=False):
    """Minimal tree: commit/ime (dead) + commit/accessibility (live). Two modules only."""
    _write(os.path.join(root, "modules.toml"), TOML)
    inc = 'include(":android:modules:commit:accessibility")\n'
    if include_dead:
        inc += 'include(":android:modules:commit:ime")\n'
    _write(os.path.join(root, "settings.gradle.kts"), inc)
    # dead module ime: card (Test Locations says None) + README + DEAD_CODE.md, NO build file
    _write(
        os.path.join(root, DEAD_PATH, "AGENTS.md"),
        _card_for("None. No contract test is registered for this module."),
    )
    _write(os.path.join(root, DEAD_PATH, "README.md"), "# ime\n")
    _write(
        os.path.join(root, DEAD_PATH, "DEAD_CODE.md"),
        "Dead: ADR-022 superseded the IME path. Revive by removing the status line\n"
        "and adding the include() line.\n",
    )
    # live sibling accessibility: card naming its contract test + README + build file
    _write(
        os.path.join(root, LIVE_PATH, "AGENTS.md"),
        _card_for(
            "- Contract: `tests/contract/test_commit_accessibility_contract.py`."
        ),
    )
    _write(os.path.join(root, LIVE_PATH, "README.md"), "# accessibility\n")
    _write(
        os.path.join(root, LIVE_PATH, "build.gradle.kts"),
        'plugins { id("com.android.library") }\n',
    )
    # the one contract test file (the live sibling's)
    _write(
        os.path.join(root, "tests", "contract", "test_commit_accessibility_contract.py"),
        'import unittest\n\n'
        "import contract_support\n\n\n"
        "class Test(contract_support.ModuleContractTest):\n"
        '    MODULE = "android_commit_accessibility"\n\n\n'
        'if __name__ == "__main__":\n'
        "    unittest.main()\n",
    )


def _dead_ime_test_case():
    # Built inside a function so it is NOT a module-level TestCase subclass:
    # unittest's loader would otherwise collect its ~14 inherited test_* methods
    # and run them against the real tree (ROOT unset) where they fail.
    class _DeadImeTest(contract_support.ModuleContractTest):
        MODULE = DEAD_KEY
    return _DeadImeTest


class ContractDeadModuleTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.mkdtemp(prefix="contract_dead_")
        self._old_root = contract_support.ROOT
        self.test = _dead_ime_test_case()()

    def tearDown(self):
        self.test = None
        contract_support.ROOT = self._old_root
        shutil.rmtree(self._tmp, ignore_errors=True)

    def _plant(self, include_dead=False):
        _build_tree(self._tmp, include_dead=include_dead)
        contract_support.ROOT = self._tmp
        self.test.setUp()

    # -- include flip -----------------------------------------------------
    def test_dead_module_not_included_passes_include_flip(self):
        self._plant(include_dead=False)
        self.test.test_module_is_included_in_the_gradle_build()

    def test_dead_module_still_included_fails_include_flip(self):
        self._plant(include_dead=True)
        with self.assertRaises(AssertionError):
            self.test.test_module_is_included_in_the_gradle_build()

    # -- build-file settlement (both cases) --------------------------------
    def test_dead_module_without_build_file_passes(self):
        self._plant(include_dead=False)
        self.test.test_module_has_a_build_file()

    def test_dead_module_with_build_file_passes(self):
        self._plant(include_dead=False)
        _write(
            os.path.join(self._tmp, DEAD_PATH, "build.gradle.kts"),
            'plugins { id("com.android.library") }\n',
        )
        self.test.test_module_has_a_build_file()

    # -- include-list bijection (live-only registered side) ----------------
    def test_dead_module_excluded_from_include_list_bijection(self):
        self._plant(include_dead=False)
        self.test.test_include_list_is_exactly_the_registered_modules()

    def test_dead_module_included_fails_include_list_bijection(self):
        self._plant(include_dead=True)
        with self.assertRaises(AssertionError) as ctx:
            self.test.test_include_list_is_exactly_the_registered_modules()
        self.assertIn("NOT registered", str(ctx.exception))

    # -- dead exempt from the contract-test bijection -----------------------
    def test_dead_module_exempt_from_contract_bijection(self):
        self._plant(include_dead=False)
        self.test.test_contract_tests_are_in_bijection_with_the_registry()

    def test_control_live_module_without_contract_test_fails_bijection(self):
        self._plant(include_dead=False)
        # strip the Test Locations name from the live card -> bijection must fail
        card = os.path.join(self._tmp, LIVE_PATH, "AGENTS.md")
        with open(card, encoding="utf-8") as fh:
            text = fh.read()
        text = text.replace(
            "- Contract: `tests/contract/test_commit_accessibility_contract.py`.",
            "- none.",
        )
        _write(card, text)
        self.test.setUp()
        with self.assertRaises(AssertionError):
            self.test.test_contract_tests_are_in_bijection_with_the_registry()


if __name__ == "__main__":
    unittest.main()
