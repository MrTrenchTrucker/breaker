"""Tests that the purpose strings in KeyWrap.kt are the ones in the shared crypto vector table.

Standard library only. KeyWrap.kt is read as text; the table is examples/crypto_vectors.json.
The two purpose strings, the size of a wrapped key, and the untouched old service are checked.
"""
import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

JSON_PATH = os.path.join(
    ROOT, "shared", "modules", "api-contracts", "examples", "crypto_vectors.json",
)
CRYPTO_MAIN = os.path.join(
    ROOT, "android", "modules", "crypto", "src", "main", "kotlin",
    "dev", "breaker", "dictation", "crypto",
)
KEY_WRAP_PATH = os.path.join(CRYPTO_MAIN, "KeyWrap.kt")
SERVICE_PATH = os.path.join(CRYPTO_MAIN, "AccountKeyServiceImpl.kt")
OLD_SERVICE_PATH = os.path.join(CRYPTO_MAIN, "CryptoServiceImpl.kt")
NONCE_PATH = os.path.join(CRYPTO_MAIN, "NonceSource.kt")

BOX_NAME = "BOX_PRIVATE_KEY_AAD_TEXT"
PREV_NAME = "PREVIOUS_DEK_AAD_TEXT"
CONSTANT_LINE = r'^internal const val %s = "([^"\\\n]*)"$'


def read_text(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def wrap_rows():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        return {row["name"]: row for row in json.load(f)["wrap"]}


def constant_value(text, name):
    found = re.findall(CONSTANT_LINE % name, text, re.M)
    if len(found) != 1:
        raise AssertionError("%s must be defined once, on one line, found %d" % (name, len(found)))
    return found[0]


def int_constant(text, name):
    found = re.findall(r"^(?:private |internal )?const val %s = (\d+)$" % name, text, re.M)
    if len(found) != 1:
        raise AssertionError("%s must be one whole number, found %d" % (name, len(found)))
    return int(found[0])


class CryptoAadConstantsTest(unittest.TestCase):
    def test_constants_are_ascii_and_equal_the_vector_aad(self):
        text = read_text(KEY_WRAP_PATH)
        rows = wrap_rows()
        pairs = (
            (BOX_NAME, "box-private-key-under-dek"),
            (PREV_NAME, "previous-dek-under-new-dek"),
        )
        for name, entry in pairs:
            value = constant_value(text, name)
            self.assertEqual(value, value.encode("ascii").decode("ascii"), name)
            self.assertEqual(value, bytes.fromhex(rows[entry]["aad_hex"]).decode("ascii"), name)

    def test_the_dek_under_kek_entry_has_empty_aad(self):
        self.assertEqual("", wrap_rows()["dek-under-kek"]["aad_hex"])

    def test_the_two_constants_differ(self):
        text = read_text(KEY_WRAP_PATH)
        self.assertNotEqual(constant_value(text, BOX_NAME), constant_value(text, PREV_NAME))

    def test_the_wrapped_size_is_60_from_12_32_and_16(self):
        text = read_text(KEY_WRAP_PATH)
        line = "private const val KEY_WRAP_TOTAL_BYTES = "
        line += "GCM_NONCE_BYTES + KEY_WRAP_KEY_BYTES + KEY_WRAP_TAG_BYTES"
        self.assertIn(line, text)
        nonce = int_constant(read_text(NONCE_PATH), "GCM_NONCE_BYTES")
        key = int_constant(text, "KEY_WRAP_KEY_BYTES")
        tag = int_constant(text, "KEY_WRAP_TAG_BYTES")
        self.assertEqual((12, 32, 16), (nonce, key, tag))
        self.assertEqual(60, nonce + key + tag)

    def test_no_other_breaker_purpose_text_is_in_the_two_sources(self):
        text = read_text(KEY_WRAP_PATH)
        expected = sorted([constant_value(text, BOX_NAME), constant_value(text, PREV_NAME)])
        found = []
        for path in (KEY_WRAP_PATH, SERVICE_PATH):
            found += re.findall(r"breaker-[A-Za-z0-9-]+", read_text(path))
        self.assertEqual(expected, sorted(found))

    def test_the_old_service_holds_neither_constant(self):
        text = read_text(OLD_SERVICE_PATH)
        keywrap = read_text(KEY_WRAP_PATH)
        for name in (BOX_NAME, PREV_NAME):
            self.assertNotIn(name, text)
            self.assertNotIn(constant_value(keywrap, name), text)


if __name__ == "__main__":
    unittest.main()
