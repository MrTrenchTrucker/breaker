"""Tests that the bytes form of the account key service keeps its order, its cap and its zeroing.

Standard library only. The Kotlin sources are read as text, so these checks are about the text of
the code: the order of the steps in the two functions of RecordBytes.kt, the single nonce request,
the zeroing, the absence of any String path and where the two members live.
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
BYTES_PATH = os.path.join(CRYPTO_MAIN, "RecordBytes.kt")
SERVICE_PATH = os.path.join(CRYPTO_MAIN, "AccountKeyServiceImpl.kt")
OLD_SERVICE_PATH = os.path.join(CRYPTO_MAIN, "CryptoServiceImpl.kt")
PORT_PATH = os.path.join(CORE_PORT, "AccountKeyService.kt")

ENCRYPT_START = "internal fun encryptRecordBytes("
DECRYPT_START = "internal fun decryptRecordBytes("
# A top-level function body runs from its first line to the closing brace at the start of a line.
FUNCTION_END = re.compile(r"^\}", re.M)


def read_text(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def function_body(text, start_marker):
    start = text.index(start_marker)
    end = FUNCTION_END.search(text, start)
    if end is None:
        raise AssertionError("the end of %s was not found" % start_marker)
    return text[start:end.start()]


def member_statement(text, marker):
    """From the marker to the next blank line: one override with its body."""
    start = text.index(marker)
    end = text.find("\n\n", start)
    return text[start:] if end == -1 else text[start:end]


def assert_in_order(test, body, steps):
    positions = []
    for step in steps:
        test.assertIn(step, body, step)
        positions.append(body.index(step))
    test.assertEqual(sorted(positions), positions, "steps out of order: %r" % (positions,))
    test.assertEqual(len(set(positions)), len(positions))


class BytesLimitsTest(unittest.TestCase):
    def test_encrypt_steps_run_in_the_binding_order(self):
        body = function_body(read_text(BYTES_PATH), ENCRYPT_START)
        assert_in_order(self, body, (
            "plain.size > UNSEAL_PLAINTEXT_MAX_BYTES",
            "dek.bytes.size != ACCOUNT_KEY_BYTES",
            "nonces.next()",
            "nonce.size != GCM_NONCE_BYTES",
            "sealBytes(",
        ))

    def test_decrypt_steps_run_in_the_binding_order(self):
        body = function_body(read_text(BYTES_PATH), DECRYPT_START)
        assert_in_order(self, body, (
            "cipherText.ciphertext.size > UNSEAL_PLAINTEXT_MAX_BYTES",
            "dek.bytes.size != ACCOUNT_KEY_BYTES",
            "cipherText.nonce.size != GCM_NONCE_BYTES",
            "cipherText.tag.size != UNSEAL_TAG_BYTES",
            "cipherText.ciphertext + cipherText.tag",
            "openBytes(",
        ))

    def test_the_nonce_source_is_asked_once_in_the_file_and_never_in_decrypt(self):
        text = read_text(BYTES_PATH)
        self.assertEqual(1, text.count("nonces.next()"))
        self.assertEqual(0, function_body(text, DECRYPT_START).count("nonces"))
        self.assertEqual(1, function_body(text, ENCRYPT_START).count("nonces.next()"))

    def test_the_copies_are_zeroed_in_a_finally(self):
        text = read_text(BYTES_PATH)
        encrypt = function_body(text, ENCRYPT_START)
        decrypt = function_body(text, DECRYPT_START)
        self.assertIn("finally", encrypt)
        self.assertIn("sealedBytes.fill(0)", encrypt)
        self.assertGreater(encrypt.index("sealedBytes.fill(0)"), encrypt.index("finally"))
        self.assertIn("finally", decrypt)
        self.assertIn("sealedInput.fill(0)", decrypt)
        self.assertGreater(decrypt.index("sealedInput.fill(0)"), decrypt.index("finally"))

    def test_there_is_no_string_path(self):
        text = read_text(BYTES_PATH)
        for forbidden in ("String(", "toString(", "decodeToString", "Charsets"):
            self.assertNotIn(forbidden, text, forbidden)

    def test_the_cap_constant_is_reused_and_not_defined_here(self):
        text = read_text(BYTES_PATH)
        self.assertGreaterEqual(text.count("UNSEAL_PLAINTEXT_MAX_BYTES"), 2)
        self.assertNotIn("const val UNSEAL_PLAINTEXT_MAX_BYTES", text)

    def test_the_service_has_one_override_of_each_inside_the_refusing_wrapper(self):
        text = read_text(SERVICE_PATH)
        for name in ("encryptBytes", "decryptBytes"):
            marker = "override fun %s" % name
            self.assertEqual(1, text.count(marker), marker)
            self.assertIn("refusingAnyFailure {", member_statement(text, marker), marker)
        self.assertIn("encryptRecordBytes(plain, dek, aad, nonces)", text)
        self.assertIn("decryptRecordBytes(cipherText, dek, aad)", text)

    def test_the_port_has_one_member_of_each(self):
        text = read_text(PORT_PATH)
        self.assertEqual(1, text.count("fun encryptBytes("))
        self.assertEqual(1, text.count("fun decryptBytes("))

    def test_the_old_service_is_untouched(self):
        text = read_text(OLD_SERVICE_PATH)
        self.assertNotIn("encryptBytes", text)
        self.assertNotIn("decryptBytes", text)


if __name__ == "__main__":
    unittest.main()
