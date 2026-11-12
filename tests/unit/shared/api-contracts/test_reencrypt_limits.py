"""Tests that the reencrypt form of AccountKeyServiceImpl.kt keeps its order, its cap and its zeroing.

Standard library only. The Kotlin source is read as text, so these checks are about the text of the
code: the order of the steps in the function body, the single nonce request, the zeroing and the
absence of any String path.
"""
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

CRYPTO_MAIN = os.path.join(
    ROOT, "android", "modules", "crypto", "src", "main", "kotlin", "dev", "breaker", "dictation", "crypto",
)
CORE_PORT = os.path.join(
    ROOT, "android", "modules", "core", "src", "main", "kotlin",
    "dev", "breaker", "dictation", "core", "port",
)
SERVICE_PATH = os.path.join(CRYPTO_MAIN, "AccountKeyServiceImpl.kt")
KEY_WRAP_PATH = os.path.join(CRYPTO_MAIN, "KeyWrap.kt")
OLD_SERVICE_PATH = os.path.join(CRYPTO_MAIN, "CryptoServiceImpl.kt")
PORT_PATH = os.path.join(CORE_PORT, "AccountKeyService.kt")

FUNCTION_START = "override fun reencrypt"
# The body of the function runs from its first line to the next line that starts with four spaces
# and then `override fun`, `private fun`, `private inline fun` or `/**`, or to the closing brace of
# the class at the start of a line, whichever comes first.
FUNCTION_END = re.compile(
    r"^(?:    (?:override fun|private fun|private inline fun|/\*\*)|\})", re.M,
)
# The body of openBytes runs from its first line to the closing brace at the start of a line.
OPEN_BYTES_START = "internal fun openBytes("
OPEN_BYTES_END = re.compile(r"^\}", re.M)


def read_text(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def function_body(text):
    start = text.index(FUNCTION_START)
    end = FUNCTION_END.search(text, start + len(FUNCTION_START))
    if end is None:
        raise AssertionError("the end of reencrypt was not found")
    return text[start:end.start()]


def open_bytes_body(text):
    start = text.index(OPEN_BYTES_START)
    end = OPEN_BYTES_END.search(text, start)
    if end is None:
        raise AssertionError("the end of openBytes was not found")
    return text[start:end.start()]


class ReencryptLimitsTest(unittest.TestCase):
    def test_the_steps_run_in_the_binding_order(self):
        body = function_body(read_text(SERVICE_PATH))
        steps = (
            "cipherText.ciphertext.size > UNSEAL_PLAINTEXT_MAX_BYTES",
            "oldDek.bytes.size",
            "cipherText.nonce.size",
            "openBytes(",
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

    def test_the_plaintext_is_zeroed_in_a_finally(self):
        body = function_body(read_text(SERVICE_PATH))
        self.assertGreaterEqual(body.count("plain.fill(0)"), 1)
        self.assertIn("finally", body)
        self.assertGreater(body.index("plain.fill(0)"), body.index("finally"))

    def test_there_is_no_string_path_and_no_body_reader(self):
        body = function_body(read_text(SERVICE_PATH))
        for forbidden in ("String(", "toString(", "SyncBodyCodec", "decodeStrictServerBody"):
            self.assertNotIn(forbidden, body, forbidden)

    def test_open_bytes_is_defined_once_and_only_opens(self):
        text = read_text(KEY_WRAP_PATH)
        self.assertEqual(1, text.count("internal fun openBytes("))
        body = open_bytes_body(text)
        self.assertNotIn("Cipher.ENCRYPT_MODE", body)
        self.assertIn("Cipher.DECRYPT_MODE", body)

    def test_the_cap_constant_is_defined_once_and_reused(self):
        text = read_text(SERVICE_PATH)
        line = "internal const val UNSEAL_PLAINTEXT_MAX_BYTES = 524288"
        self.assertEqual(1, len(re.findall(r"^%s$" % re.escape(line), text, re.M)))
        self.assertEqual(1, text.count("const val UNSEAL_PLAINTEXT_MAX_BYTES"))

    def test_the_old_service_is_untouched(self):
        self.assertNotIn("reencrypt", read_text(OLD_SERVICE_PATH))

    def test_the_port_has_one_reencrypt_member(self):
        self.assertEqual(1, read_text(PORT_PATH).count("fun reencrypt("))


if __name__ == "__main__":
    unittest.main()
