"""A repeated card heading must fail loudly in each reader, and a contract or
registry file planted outside its module folder must be named by the walk.

Two surviving mutants of rounds 1-3 are killed here:

1. READER FIRST-MATCH — either card-section reader could be reverted to
   "silently return the first body" and the real-card exactly-once tests
   would stay green, because no real card repeats a heading. Each reader is
   therefore driven on a synthetic card that DOES repeat one, and each must
   raise. (The readers are the shared `_card_section` in
   `tests/contract/contract_support.py` and the independent `_card_section`
   in `tests/contract/test_format_prompts_contract.py`.)

2. STRAY-FILE WALK — the shared module's stray-file rule (an OpenAPI spec
   outside `shared/modules/api-contracts/`, or a `models.yaml` outside
   `shared/modules/model-registry/`) could be deleted and the suite would
   stay green, because the rule only scans the real tree, which has no
   strays. The rule is `contract_strays.find_contract_strays(root)`; the
   contract method calls it on the real root and the tests below call it on
   planted temp trees.
"""
import importlib.util
import os
import sys
import tempfile
import unittest

REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

CARD = (
    "## Owns\n"
    "- first body\n"
    "## Owns\n"
    "- second body\n"
)


def _load_module(name, path):
    """Load a module from a file path (no __init__.py, no path pollution)."""
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class RepeatedHeadingReadersTest(unittest.TestCase):
    """A heading that opens two `## ` lines is a broken card, not a first-wins read."""

    def setUp(self):
        self.contract_support = _load_module(
            "contract_support_under_test",
            os.path.join(REPO_ROOT, "tests", "contract", "contract_support.py"),
        )
        # The format-prompts reader imports `contract_support` at module
        # level, so its package dir must be importable while it loads.
        cdir = os.path.join(REPO_ROOT, "tests", "contract")
        inserted = cdir not in sys.path
        if inserted:
            sys.path.insert(0, cdir)
        try:
            self.tfdc = _load_module(
                "tfdc_under_test",
                os.path.join(
                    REPO_ROOT, "tests", "contract",
                    "test_format_prompts_contract.py",
                ),
            )
        finally:
            if inserted:
                sys.path.remove(cdir)

    def test_the_shared_reader_rejects_a_repeated_heading(self):
        # A first-match revert of the shared reader returns the first body
        # and keeps the suite green; this must not pass.
        with self.assertRaises(AssertionError):
            self.contract_support._card_section(CARD, "## Owns")

    def test_the_independent_reader_rejects_a_repeated_heading(self):
        # The independent reader has the same rule; a first-match revert of
        # only it would also keep the suite green; this must not pass.
        with self.assertRaises(AssertionError):
            self.tfdc._card_section(CARD, "## Owns")


class ContractStrayWalkTest(unittest.TestCase):
    """find_contract_strays names each planted stray and nothing else."""

    def setUp(self):
        self.cs = _load_module(
            "contract_strays_under_test",
            os.path.join(REPO_ROOT, "tests", "contract", "contract_strays.py"),
        )

    def _plant(self, tmp, *relative_paths):
        for rel in relative_paths:
            path = os.path.join(tmp, *rel.split("/"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("planted\n")

    def test_it_names_an_openapi_spec_planted_outside_api_contracts(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._plant(tmp, "shared/openapi.yaml")
            offenders = self.cs.find_contract_strays(tmp)
        self.assertEqual(
            offenders,
            ["shared/openapi.yaml — an OpenAPI spec belongs in "
             "shared/modules/api-contracts"],
        )

    def test_it_names_a_models_yaml_planted_outside_model_registry(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._plant(tmp, "docs/models.yaml")
            offenders = self.cs.find_contract_strays(tmp)
        self.assertEqual(
            offenders,
            ["docs/models.yaml — the model registry data file belongs in "
             "shared/modules/model-registry"],
        )

    def test_files_in_their_own_module_folders_are_not_strays(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._plant(
                tmp,
                "shared/modules/api-contracts/openapi.yaml",
                "shared/modules/model-registry/models.yaml",
            )
            offenders = self.cs.find_contract_strays(tmp)
        self.assertEqual(offenders, [])

    def test_it_names_both_strays_at_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._plant(
                tmp,
                "shared/openapi.yaml",
                "docs/models.yaml",
                "shared/modules/api-contracts/openapi.yaml",
                "shared/modules/model-registry/models.yaml",
            )
            offenders = self.cs.find_contract_strays(tmp)
        self.assertEqual(len(offenders), 2)
        self.assertIn(
            "shared/openapi.yaml — an OpenAPI spec belongs in "
            "shared/modules/api-contracts",
            offenders,
        )
        self.assertIn(
            "docs/models.yaml — the model registry data file belongs in "
            "shared/modules/model-registry",
            offenders,
        )


if __name__ == "__main__":
    unittest.main()
