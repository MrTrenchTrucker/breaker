"""The rendered Kotlin carries a '$' literally, not as a string template.

Kotlin reads `$` inside a string literal as the start of a template. The
generator escapes it, so a licence name or a url owner segment holding a
dollar sign reaches the compiler as that same character: the rendered line
carries the escaped form, and un-reading the rendered Kotlin literal returns
the value models.yaml held.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts

KOTLIN_FIELD = re.compile(r'^\s*(licence|url) = (".*"),$', re.MULTILINE)
LICENCE_NAME = "Custom $licence (2024)"
DOLLAR_OWNER_URL = (
    "https://api.github.com/repos/own$er/k2-fsa/sherpa-onnx/"
    "releases/assets/191972150"
)


def read_kotlin_string(literal):
    """The characters a Kotlin double-quoted literal denotes, per the Kotlin
    escape rules the generator relies on: `\\"`, `\\n`-style backslash pairs,
    and `\\$`. Written out so a test can prove the round trip without a
    Kotlin compiler."""
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


def planted_entry():
    """The committed entry, given a licence name and a url owner segment that
    both hold a '$' (the SPDX id is replaced by the name+link pair the
    generator accepts in its place)."""
    text = "\n".join(
        line for line in ts.models_text().splitlines()
        if not line.startswith("    license:"))
    text = text.replace(
        "    url: https://api.github.com/repos/k2-fsa/sherpa-onnx/"
        "releases/assets/191972150",
        f"    url: {DOLLAR_OWNER_URL}")
    text = text.replace(
        "    notes: >-",
        f"    license_name: {LICENCE_NAME}\n"
        "    license_link: https://example.invalid/license\n"
        "    notes: >-")
    return ts.check_models(text)[0]


class DollarInGeneratedKotlinTest(unittest.TestCase):
    def setUp(self):
        self.entry = planted_entry()
        # the plant itself must hold what the test claims
        self.assertEqual(self.entry["licence"], LICENCE_NAME)
        self.assertEqual(self.entry["url"], DOLLAR_OWNER_URL)
        self.text = ts.gen_model_registry.render_kotlin([self.entry])

    def test_generated_text_carries_the_escaped_dollar(self):
        self.assertIn(r'licence = "Custom \$licence (2024)"', self.text)
        self.assertIn(r"/repos/own\$er/", self.text)
        # no unescaped dollar survives anywhere in a rendered literal
        for _, literal in KOTLIN_FIELD.findall(self.text):
            body = literal[1:-1]
            i = 0
            while i < len(body):
                if body[i] == "\\":
                    i += 2
                    continue
                self.assertNotEqual(
                    body[i], "$",
                    "model_registry tests: an unescaped '$' would be read as "
                    f"a Kotlin string template: {literal}")
                i += 1

    def test_reparsed_generated_value_equals_the_source_value(self):
        rendered = dict(KOTLIN_FIELD.findall(self.text))
        self.assertEqual(
            read_kotlin_string(rendered["licence"]), self.entry["licence"])
        self.assertEqual(
            read_kotlin_string(rendered["url"]), self.entry["url"])


if __name__ == "__main__":
    unittest.main()
