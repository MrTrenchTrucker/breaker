"""Cross-check of the sign-in X25519 box vectors against the shared crypto vector table.

Standard library only. The x25519 rows of
shared/modules/api-contracts/examples/crypto_vectors.json and the X25519_BOX rows of
server/modules/web-fe/src/test/js/signin-vectors.mjs must agree in order, name by name,
private and public value by value, so a changed digit on either side fails by name.
Both files are read at run time; this file holds no hex value.
"""
import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))

VEC = os.path.join(ROOT, "shared", "modules", "api-contracts", "examples", "crypto_vectors.json")
FIXTURE = os.path.join(ROOT, "server", "modules", "web-fe", "src", "test", "js", "signin-vectors.mjs")

BLOCK_RE = re.compile(r"export const X25519_BOX = Object\.freeze\(\[(.*?)\n\]\);", re.S)
ROW_RE = re.compile(
    r"name: '([^']*)',\s*privateHex: '([0-9a-f]*)',\s*publicHex: '([0-9a-f]*)',")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def fixture_rows(text):
    """The X25519_BOX rows as (name, private, public) tuples, or None when the block is missing."""
    found = BLOCK_RE.search(text)
    if found is None:
        return None
    return ROW_RE.findall(found.group(1))


def shared_rows(text):
    """The x25519 rows of the shared table as (name, private, public) tuples."""
    return [(row["name"], row["private_hex"], row["public_hex"]) for row in json.loads(text)["x25519"]]


def compare(fixture, shared):
    """Problems, each named by vector, where the fixture and the shared table disagree."""
    if fixture is None:
        return ["X25519_BOX is not in the fixture"]
    if len(fixture) != len(shared):
        return ["X25519_BOX has %d rows, the shared table has %d" % (len(fixture), len(shared))]
    problems = []
    for mine, theirs in zip(fixture, shared):
        if mine[0] != theirs[0]:
            problems.append("name differs: %s and %s" % (mine[0], theirs[0]))
        elif mine[1] != theirs[1]:
            problems.append("private value differs for %s" % theirs[0])
        elif mine[2] != theirs[2]:
            problems.append("public value differs for %s" % theirs[0])
    return problems


def _other_digit(value):
    return "1" if value[-1] == "0" else "0"


class SharedX25519VectorsTest(unittest.TestCase):

    def setUp(self):
        self.vec = _read(VEC)
        self.fixture = _read(FIXTURE)

    def test_real_tree_agrees(self):
        self.assertEqual(compare(fixture_rows(self.fixture), shared_rows(self.vec)), [])

    def test_fixture_names_the_two_rfc7748_rows(self):
        rows = fixture_rows(self.fixture)
        self.assertIsNotNone(rows, "X25519_BOX is not in the fixture")
        self.assertEqual([row[0] for row in rows], ["rfc7748-6.1-alice", "rfc7748-6.1-bob"])

    def test_control_fixture_digit_changed_fails(self):
        old = shared_rows(self.vec)[0][1]
        block = BLOCK_RE.search(self.fixture).group(0)
        self.assertEqual(block.count(old), 1, "control edit did not land once")
        bad = self.fixture.replace(block, block.replace(old, old[:-1] + _other_digit(old), 1), 1)
        self.assertEqual(compare(fixture_rows(self.fixture), shared_rows(self.vec)), [])
        self.assertTrue(compare(fixture_rows(bad), shared_rows(self.vec)), "a changed fixture digit passed")

    def test_control_shared_digit_changed_fails(self):
        bad = change_digit_after(self.vec, '"name": "rfc7748-6.1-bob",', '"public_hex": "', '"')
        self.assertNotEqual(bad, self.vec, "control edit did not land")
        self.assertEqual(compare(fixture_rows(self.fixture), shared_rows(self.vec)), [])
        self.assertTrue(compare(fixture_rows(self.fixture), shared_rows(bad)), "a changed shared digit passed")

    def test_control_dropped_entry_fails(self):
        rows = fixture_rows(self.fixture)
        self.assertTrue(compare(rows[:-1], shared_rows(self.vec)), "a dropped entry passed")

    def test_control_swapped_names_fail(self):
        rows = fixture_rows(self.fixture)
        self.assertTrue(compare([rows[1], rows[0]], shared_rows(self.vec)), "swapped names passed")


# The wrap entries and refusals of the sign-in fixture, checked against the shared table.
WRAP_RE = re.compile(r"export const WRAP_VECTORS = Object\.freeze\(\[(.*?)\n\]\);", re.S)
REFUSAL_RE = re.compile(r"export const WRAP_REFUSALS = Object\.freeze\(\[(.*?)\n\]\);", re.S)
WRAP_ROW_RE = re.compile(
    r"name: '([^']*)',\s*wrapperKeyHex: '([0-9a-f]*)',\s*nonceHex: '([0-9a-f]*)',"
    r"\s*aadHex: '([0-9a-f]*)',\s*plainHex: '([0-9a-f]*)',\s*wrappedHex: '([0-9a-f]*)',")
REFUSAL_ROW_RE = re.compile(
    r"name: '([^']*)',\s*wrapperKeyHex: '([0-9a-f]*)',\s*wrappedHex: '([0-9a-f]*)',\s*aadHex: '([0-9a-f]*)',")


def _block(text, pattern):
    found = pattern.search(text)
    return None if found is None else found.group(1)


def wrap_rows(text):
    """The WRAP_VECTORS rows as (name, wrapper, nonce, aad, plain, wrapped) tuples, or None when missing."""
    block = _block(text, WRAP_RE)
    return None if block is None else WRAP_ROW_RE.findall(block)


def refusal_rows(text):
    """The WRAP_REFUSALS rows as (name, wrapper, wrapped, aad) tuples, or None when missing."""
    block = _block(text, REFUSAL_RE)
    return None if block is None else REFUSAL_ROW_RE.findall(block)


def shared_wrap(text):
    """The wrap rows of the shared table in the same tuple order as wrap_rows."""
    return [(row["name"], row["wrapper_key_hex"], row["nonce_hex"], row["aad_hex"], row["plain_hex"],
             row["wrapped_hex"]) for row in json.loads(text)["wrap"]]


def shared_refusals(text):
    """The refusal rows of the shared table in the same tuple order as refusal_rows."""
    return [(row["name"], row["wrapper_key_hex"], row["wrapped_hex"], row["aad_hex"])
            for row in json.loads(text)["wrap_refusals"]]


def compare_rows(fixture, shared, what):
    """Problems, each named by entry, where the fixture rows and the shared rows disagree."""
    if fixture is None:
        return [what + " is not in the fixture"]
    if len(fixture) != len(shared):
        return [what + " has %d rows, the shared table has %d" % (len(fixture), len(shared))]
    problems = []
    for mine, theirs in zip(fixture, shared):
        if mine[0] != theirs[0]:
            problems.append("name differs in %s: %s and %s" % (what, mine[0], theirs[0]))
        elif tuple(mine) != tuple(theirs):
            problems.append("a value differs for %s in %s" % (theirs[0], what))
    return problems


def change_digit_after(text, anchor, key, quote):
    """A copy of the text with the last digit of the value of key changed, after the first anchor."""
    start = text.index(anchor)
    at = text.index(key, start) + len(key)
    end = text.index(quote, at)
    value = text[at:end]
    return text[:at] + value[:-1] + _other_digit(value) + text[end:]


class SharedWrapVectorsTest(unittest.TestCase):

    def setUp(self):
        self.vec = _read(VEC)
        self.fixture = _read(FIXTURE)

    def test_real_tree_wrap_entries_agree(self):
        self.assertEqual(compare_rows(wrap_rows(self.fixture), shared_wrap(self.vec), "WRAP_VECTORS"), [])

    def test_real_tree_refusals_agree(self):
        self.assertEqual(compare_rows(refusal_rows(self.fixture), shared_refusals(self.vec), "WRAP_REFUSALS"), [])

    def test_control_fixture_wrap_digit_changed_fails(self):
        bad = change_digit_after(self.fixture, "name: 'box-private-key-under-dek',", "wrappedHex: '", "'")
        self.assertNotEqual(bad, self.fixture, "control edit did not land")
        self.assertTrue(compare_rows(wrap_rows(bad), shared_wrap(self.vec), "WRAP_VECTORS"),
                        "a changed fixture wrap digit passed")

    def test_control_shared_wrap_digit_changed_fails(self):
        bad = change_digit_after(self.vec, '"name": "previous-dek-under-new-dek",', '"wrapped_hex": "', '"')
        self.assertNotEqual(bad, self.vec, "control edit did not land")
        self.assertTrue(compare_rows(wrap_rows(self.fixture), shared_wrap(bad), "WRAP_VECTORS"),
                        "a changed shared wrap digit passed")

    def test_control_refusal_digit_changed_fails(self):
        bad = change_digit_after(self.fixture, "name: 'prev-dek-blob-with-box-aad',", "aadHex: '", "'")
        self.assertNotEqual(bad, self.fixture, "control edit did not land")
        self.assertTrue(compare_rows(refusal_rows(bad), shared_refusals(self.vec), "WRAP_REFUSALS"),
                        "a changed refusal digit passed")

    def test_control_wrap_entry_dropped_fails(self):
        rows = wrap_rows(self.fixture)
        self.assertTrue(compare_rows(rows[:-1], shared_wrap(self.vec), "WRAP_VECTORS"),
                        "a dropped wrap entry passed")

    def test_control_refusal_entry_dropped_fails(self):
        rows = refusal_rows(self.fixture)
        self.assertTrue(compare_rows(rows[:-1], shared_refusals(self.vec), "WRAP_REFUSALS"),
                        "a dropped refusal entry passed")


SEAL_BLOCK_RE = re.compile(r"export const SEAL_VECTORS = Object\.freeze\(\[(.*?)\n\]\);", re.S)
SEAL_REFUSAL_BLOCK_RE = re.compile(r"export const SEAL_REFUSALS = Object\.freeze\(\[(.*?)\n\]\);", re.S)
SEAL_ROW_RE = re.compile(
    r"name: '([^']*)',\s*recipientPrivateHex: '([0-9a-f]*)',\s*recipientPublicHex: '([0-9a-f]*)',"
    r"\s*ephemeralPrivateHex: '([0-9a-f]*)',\s*ephemeralPublicHex: '([0-9a-f]*)',"
    r"\s*nonceHex: '([0-9a-f]*)',\s*plainHex: '([0-9a-f]*)',\s*boxHex: '([0-9a-f]*)',")
SEAL_REFUSAL_ROW_RE = re.compile(
    r"name: '([^']*)',\s*recipientPrivateHex: '([0-9a-f]*)',\s*boxHex: '([0-9a-f]*)',")


def seal_rows(text):
    """The SEAL_VECTORS rows as tuples in the shared table's field order, or None when missing."""
    block = _block(text, SEAL_BLOCK_RE)
    return None if block is None else SEAL_ROW_RE.findall(block)


def seal_refusal_rows(text):
    """The SEAL_REFUSALS rows as (name, recipient private, box) tuples, or None when missing."""
    block = _block(text, SEAL_REFUSAL_BLOCK_RE)
    return None if block is None else SEAL_REFUSAL_ROW_RE.findall(block)


def shared_seal(text):
    """The seal rows of the shared table in the same tuple order as seal_rows."""
    return [(row["name"], row["recipient_private_hex"], row["recipient_public_hex"],
             row["ephemeral_private_hex"], row["ephemeral_public_hex"], row["nonce_hex"],
             row["plain_hex"], row["box_hex"]) for row in json.loads(text)["seal"]]


def shared_seal_refusals(text):
    """The refusal rows of the shared table in the same tuple order as seal_refusal_rows."""
    return [(row["name"], row["recipient_private_hex"], row["box_hex"])
            for row in json.loads(text)["seal_refusals"]]


class SharedSealVectorsTest(unittest.TestCase):

    def setUp(self):
        self.vec = _read(VEC)
        self.fixture = _read(FIXTURE)

    def test_real_tree_seal_entries_agree(self):
        self.assertEqual(compare_rows(seal_rows(self.fixture), shared_seal(self.vec), "SEAL_VECTORS"), [])

    def test_real_tree_seal_refusals_agree(self):
        self.assertEqual(
            compare_rows(seal_refusal_rows(self.fixture), shared_seal_refusals(self.vec), "SEAL_REFUSALS"), [])

    def test_control_seal_fixture_digit_changed_fails(self):
        bad = change_digit_after(self.fixture, "name: 'signin-package-65',", "boxHex: '", "'")
        self.assertNotEqual(bad, self.fixture, "control edit did not land")
        self.assertTrue(compare_rows(seal_rows(bad), shared_seal(self.vec), "SEAL_VECTORS"),
                        "a changed fixture seal digit passed")

    def test_control_seal_shared_digit_changed_fails(self):
        bad = change_digit_after(self.vec, '"name": "signin-package-65",', '"box_hex": "', '"')
        self.assertNotEqual(bad, self.vec, "control edit did not land")
        self.assertTrue(compare_rows(seal_rows(self.fixture), shared_seal(bad), "SEAL_VECTORS"),
                        "a changed shared seal digit passed")

    def test_control_seal_entry_dropped_fails(self):
        rows = seal_rows(self.fixture)
        self.assertTrue(compare_rows(rows[:-1], shared_seal(self.vec), "SEAL_VECTORS"),
                        "a dropped seal entry passed")

    def test_control_seal_names_swapped_fail(self):
        rows = seal_rows(self.fixture)
        self.assertTrue(compare_rows([rows[1], rows[0]], shared_seal(self.vec), "SEAL_VECTORS"),
                        "swapped seal names passed")

    def test_control_seal_refusal_digit_changed_fails(self):
        bad = change_digit_after(self.fixture, "name: 'signin-package-flipped-bit',", "boxHex: '", "'")
        self.assertNotEqual(bad, self.fixture, "control edit did not land")
        self.assertTrue(compare_rows(seal_refusal_rows(bad), shared_seal_refusals(self.vec), "SEAL_REFUSALS"),
                        "a changed refusal digit passed")

    def test_control_seal_refusal_dropped_fails(self):
        rows = seal_refusal_rows(self.fixture)
        self.assertTrue(compare_rows(rows[:-1], shared_seal_refusals(self.vec), "SEAL_REFUSALS"),
                        "a dropped refusal entry passed")

    def test_control_seal_refusal_names_swapped_fail(self):
        rows = seal_refusal_rows(self.fixture)
        self.assertTrue(compare_rows([rows[1], rows[0]], shared_seal_refusals(self.vec), "SEAL_REFUSALS"),
                        "swapped refusal names passed")


if __name__ == "__main__":
    unittest.main()
