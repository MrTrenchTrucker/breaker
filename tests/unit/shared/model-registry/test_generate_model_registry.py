"""kotlin_str escapes a double quote and a backslash in a rendered registry string.

Kotlin reads a bare '"' as the end of a string literal and a bare '\' as the
start of an escape, so a registry value holding either must reach the compiler
escaped: the quote as `\"`, the backslash as `\\`. Each test plants one
character in a real entry's licence name (the committed `small` entry, with its
SPDX id replaced by the name+link pair the generator accepts) and proves the
rendered literal carries the escaped form and reads back to the source value.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts

LICENCE = re.compile(r'^\s*licence = (".*"),$', re.MULTILINE)
LICENSE_LINK = "https://example.invalid/license"


def read_kotlin_string(literal):
    """The characters a Kotlin double-quoted literal denotes, per the escape
    rules the generator relies on (`\\"`, `\\\\`, `\\$`, `\\n`). Written out so
    a test can prove the round trip without a Kotlin compiler."""
    if not (literal.startswith('"') and literal.endswith('"')):
        raise AssertionError(
            f"model_registry tests: not a Kotlin string literal: {literal}")
    body = literal[1:-1]
    out = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        nxt = body[i + 1]
        if nxt in ('"', "\\", "$"):
            out.append(nxt)
            i += 2
        elif nxt == "n":
            out.append("\n")
            i += 2
        else:
            raise AssertionError(
                f"model_registry tests: the generated literal carries an "
                f"unhandled escape: \\{nxt}")
    return "".join(out)


def planted_entry(licence_name):
    """The committed `small` entry as a one-entry models.yaml whose licence
    name holds `licence_name` (the SPDX id replaced by the name+link pair the
    generator accepts in its place)."""
    text = ts.single_entry_yaml("small")
    marker = "    license: Apache-2.0\n"
    if text.count(marker) != 1:
        raise AssertionError(
            "model_registry tests: the small entry's license line was not "
            f"found exactly once:\n{text}")
    text = text.replace(
        marker,
        f"    license_name: {licence_name}\n"
        f"    license_link: {LICENSE_LINK}\n")
    return ts.check_models(text)[0]


def assert_no_bare_quote(testcase, literal):
    """Every embedded quote in `literal` is written `\\"`; none is bare (a bare
    quote would end the Kotlin literal early)."""
    body = literal[1:-1]
    i = 0
    while i < len(body):
        if body[i] == "\\":
            i += 2
            continue
        testcase.assertNotEqual(
            body[i], '"',
            "model_registry tests: a bare '\"' would end the Kotlin literal "
            f"early: {literal}")
        i += 1


def assert_no_bare_backslash(testcase, literal):
    """Every backslash in `literal` starts one of the escapes Kotlin defines
    (`\\"`, `\\\\`, `\\$`, `\\n`); a bare one would begin an undefined escape."""
    body = literal[1:-1]
    i = 0
    while i < len(body):
        if body[i] == "\\":
            testcase.assertIn(
                body[i + 1:i + 2], ('"', "\\", "$", "n"),
                "model_registry tests: a bare '\\' would begin an undefined "
                f"Kotlin escape: {literal}")
            i += 2
            continue
        i += 1


class QuoteIsEscapedInGeneratedKotlinTest(unittest.TestCase):
    """A licence name holding a double quote reaches the compiler as `\\"`.

    Kills mutant R02 (model_registry_render.py: kotlin_str loses
    `.replace('"', '\\"')`): the quote then survives bare and the literal no
    longer carries `\\"`."""

    LICENCE_NAME = 'Custom "licence" (2024)'

    def setUp(self):
        self.entry = planted_entry(self.LICENCE_NAME)
        # the plant itself must hold what the test claims
        self.assertEqual(self.entry["licence"], self.LICENCE_NAME)
        self.text = ts.gen_model_registry.render_kotlin([self.entry])
        literals = LICENCE.findall(self.text)
        self.assertEqual(len(literals), 1, self.text)
        self.literal = literals[0]

    def test_the_embedded_quote_is_escaped_and_reads_back(self):
        assert_no_bare_quote(self, self.literal)
        self.assertIn(r'\"', self.literal)
        self.assertEqual(
            read_kotlin_string(self.literal), self.LICENCE_NAME)


class BackslashIsEscapedInGeneratedKotlinTest(unittest.TestCase):
    """A licence name holding a backslash reaches the compiler as `\\\\`.

    Kills mutant R03 (model_registry_render.py: kotlin_str loses
    `.replace("\\", "\\\\")`): the backslash then survives bare and the literal
    no longer carries `\\\\`."""

    LICENCE_NAME = "Custom \\licence (2024)"

    def setUp(self):
        self.entry = planted_entry(self.LICENCE_NAME)
        # the plant itself must hold what the test claims
        self.assertEqual(self.entry["licence"], self.LICENCE_NAME)
        self.text = ts.gen_model_registry.render_kotlin([self.entry])
        literals = LICENCE.findall(self.text)
        self.assertEqual(len(literals), 1, self.text)
        self.literal = literals[0]

    def test_the_embedded_backslash_is_escaped_and_reads_back(self):
        self.assertIn("\\\\", self.literal)
        assert_no_bare_backslash(self, self.literal)
        self.assertEqual(
            read_kotlin_string(self.literal), self.LICENCE_NAME)


if __name__ == "__main__":
    unittest.main()
