"""Contract test for the `android/ui` module.

Generated as a stub: it pins the structural contract this module has with the
rest of the repository (registry entry, card, Gradle wiring, declared
dependencies) and leaves the behavioural contract to the tests added with the
module's own code.
"""
import unittest

import contract_support


class AndroidUiContractTest(contract_support.ModuleContractTest):
    MODULE = "android_ui"


import os
import re

AUTH_POLICY = os.path.join(
    "android", "ui", "src", "main", "kotlin", "dev", "breaker", "dictation", "ui",
    "screen", "auth", "AuthPolicy.kt",
)
ACCOUNT_RULES = os.path.join(
    "android", "modules", "auth-client", "src", "main", "kotlin", "dev", "breaker",
    "dictation", "auth", "client", "AccountRules.kt",
)
POLICY_LIMIT = re.compile(r"USERNAME_MAX_CHARS: Int = (\d+)")
RULES_LIMIT = re.compile(r"MAX_USERNAME_CHARS = (\d+)")


def read_limit(text, pattern, name):
    """The int the pattern finds in text; raises AssertionError naming the file when there is none."""
    found = pattern.search(text)
    if found is None:
        raise AssertionError(name + " holds no match for " + pattern.pattern)
    return int(found.group(1))


class AuthUsernameLimitPin(unittest.TestCase):
    """The ui module's user name limit is a copy of the auth client's; the two must stay equal."""

    def test_the_two_limits_are_equal(self):
        with open(os.path.join(contract_support.ROOT, AUTH_POLICY), encoding="utf-8") as fh:
            ui_limit = read_limit(fh.read(), POLICY_LIMIT, AUTH_POLICY)
        with open(os.path.join(contract_support.ROOT, ACCOUNT_RULES), encoding="utf-8") as fh:
            client_limit = read_limit(fh.read(), RULES_LIMIT, ACCOUNT_RULES)
        self.assertEqual(
            ui_limit,
            client_limit,
            f"user name limit differs: {ui_limit} in {AUTH_POLICY}, {client_limit} in {ACCOUNT_RULES}",
        )

    def test_the_reader_finds_a_number_in_a_sample(self):
        self.assertEqual(64, read_limit("internal const val USERNAME_MAX_CHARS: Int = 64", POLICY_LIMIT, "sample"))
        self.assertEqual(64, read_limit("const val MAX_USERNAME_CHARS = 64", RULES_LIMIT, "sample"))
        with self.assertRaises(AssertionError):
            read_limit("nothing to find here", POLICY_LIMIT, "sample")

    def test_a_sample_with_a_different_number_is_not_equal(self):
        changed = read_limit("internal const val USERNAME_MAX_CHARS: Int = 65", POLICY_LIMIT, "sample")
        same = read_limit("const val MAX_USERNAME_CHARS = 64", RULES_LIMIT, "sample")
        self.assertEqual(65, changed)
        self.assertNotEqual(changed, same)


if __name__ == "__main__":
    unittest.main()
