"""The card heading scanners do not read a `## ` line inside a fenced code
block as a section heading.

A card may SHOW a section layout in a fenced example; that example is not a
declaration. All three heading scanners — the shared reader in
`tests/contract/contract_support.py`, the base-class per-section count, and
the independent reader in
`tests/contract/test_format_prompts_contract.py` — must agree on the same
rule, so this test drives all three against one synthetic card that carries
a fenced `## ` example plus the real section, and checks that the fenced line
is not counted and not read.
"""
import importlib.util
import os
import sys
import unittest

REPO_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)

CARD = (
    "## Owns\n"
    "The layout a card carries, shown here as an example:\n"
    "```\n"
    "## Does Not Own\n"
    "## Public Interface\n"
    "```\n"
    "## Does Not Own\n"
    "- business logic\n"
)


def _load_module(name, path):
    """Load a module from a file path (no __init__.py, no path pollution)."""
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class FencedHeadingScannersTest(unittest.TestCase):
    """A `## ` line inside a ``` fence is an example, not a heading."""

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

    def test_a_fenced_heading_is_an_example_not_a_section(self):
        # The shared reader: the fenced "## Does Not Own" must not count as a
        # second section (a pre-fix reader raises "opens 2 sections").
        self.assertEqual(
            self.contract_support._card_section(CARD, "## Does Not Own"),
            "## Does Not Own\n- business logic",
        )
        # The base-class count: the real section is counted once, the fenced
        # example not at all, and the fenced "## Public Interface" not at all.
        self.assertEqual(
            self.contract_support._section_heading_count(CARD, "## Does Not Own"),
            1,
        )
        self.assertEqual(
            self.contract_support._section_heading_count(
                CARD, "## Public Interface"
            ),
            0,
        )
        # The independent reader agrees with the shared one.
        self.assertEqual(
            self.tfdc._card_section(CARD, "## Does Not Own"),
            "## Does Not Own\n- business logic",
        )


if __name__ == "__main__":
    unittest.main()
