"""Tests that the unseal form of AccountKeyServiceImpl.kt keeps its caps, its order and its zeroing.

Standard library only. The Kotlin source is read as text, so these checks are about the text of the
code: the two cap constants, the order of the steps in the function body, and the registry edge.
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

CRYPTO_DIR = os.path.join(ROOT, "android", "modules", "crypto")
CRYPTO_MAIN = os.path.join(
    CRYPTO_DIR, "src", "main", "kotlin", "dev", "breaker", "dictation", "crypto",
)
SERVICE_PATH = os.path.join(CRYPTO_MAIN, "AccountKeyServiceImpl.kt")
OLD_SERVICE_PATH = os.path.join(CRYPTO_MAIN, "CryptoServiceImpl.kt")
BUILD_PATH = os.path.join(CRYPTO_DIR, "build.gradle.kts")
REGISTRY_PATH = os.path.join(ROOT, "modules.toml")

FUNCTION_START = "override fun unsealAndReencrypt"
# The body of the function runs from its first line to the next line that starts with four spaces
# and then `override fun`, `private fun`, `private inline fun` or `/**`, or to the closing brace of
# the class at the start of a line, whichever comes first.
FUNCTION_END = re.compile(
    r"^(?:    (?:override fun|private fun|private inline fun|/\*\*)|\})", re.M,
)


def read_text(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def function_body(text):
    start = text.index(FUNCTION_START)
    end = FUNCTION_END.search(text, start + len(FUNCTION_START))
    if end is None:
        raise AssertionError("the end of unsealAndReencrypt was not found")
    return text[start:end.start()]


class UnsealLimitsTest(unittest.TestCase):
    def test_the_two_caps_are_defined_once_on_one_line(self):
        text = read_text(SERVICE_PATH)
        plain = "internal const val UNSEAL_PLAINTEXT_MAX_BYTES = 524288"
        box = "internal const val UNSEAL_BOX_MAX_BYTES = UNSEAL_PLAINTEXT_MAX_BYTES + 48"
        self.assertEqual(1, len(re.findall(r"^%s$" % re.escape(plain), text, re.M)))
        self.assertEqual(1, len(re.findall(r"^%s$" % re.escape(box), text, re.M)))

    def test_the_steps_run_in_the_binding_order(self):
        body = function_body(read_text(SERVICE_PATH))
        steps = (
            "sealed.size > UNSEAL_BOX_MAX_BYTES",
            "sealed.copyBytes()",
            "opener.open",
            "> UNSEAL_PLAINTEXT_MAX_BYTES",
            "decodeStrictServerBody",
            "nonces.next()",
            "sealBytes(",
        )
        positions = []
        for step in steps:
            self.assertIn(step, body, step)
            positions.append(body.index(step))
        self.assertEqual(sorted(positions), positions, "steps out of order: %r" % (positions,))
        self.assertEqual(len(set(positions)), len(positions))

    def test_the_nonce_source_is_asked_once_in_the_function(self):
        body = function_body(read_text(SERVICE_PATH))
        self.assertEqual(1, body.count("nonces.next()"))

    def test_the_strict_reader_is_called_once_in_the_file(self):
        self.assertEqual(1, read_text(SERVICE_PATH).count("decodeStrictServerBody"))

    def test_the_function_zeroes_its_arrays(self):
        body = function_body(read_text(SERVICE_PATH))
        self.assertGreaterEqual(body.count("fill(0)"), 3)

    def test_the_registry_and_the_build_file_carry_the_edge(self):
        text = read_text(REGISTRY_PATH)
        start = text.index("[module.android_crypto]")
        end = text.find("\n[module.", start + 1)
        section = text[start:] if end == -1 else text[start:end]
        lines = [line for line in section.split("\n") if line.startswith("depends_on")]
        self.assertEqual(1, len(lines))
        self.assertIn('"shared_api_contracts"', lines[0])
        line = 'implementation(project(":shared:modules:api-contracts"))'
        self.assertEqual(1, read_text(BUILD_PATH).count(line))

    def test_the_old_service_is_untouched(self):
        self.assertNotIn("unsealAndReencrypt", read_text(OLD_SERVICE_PATH))


if __name__ == "__main__":
    unittest.main()
