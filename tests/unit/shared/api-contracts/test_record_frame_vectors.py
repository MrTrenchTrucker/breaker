"""Tests for the record frame vector file (the frame of an encrypted record and its associated data).

Standard library only (base64, binascii, re, json, os, unittest). The oracle below is written here from
the rules of the frame: it never reads or calls any Kotlin code. It builds each frame by hand, and
re-derives every refusal by its own rules: a strict alphabet by regular expression, padding by length,
re-encode and compare for the canonical form, a floor of 28 bytes, a nonce of 12 and a tag of 16 bytes,
a lowercase canonical UUID, a key version of at least 1.
"""
import base64
import binascii
import json
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

API = os.path.join(ROOT, "shared", "modules", "api-contracts")
VECTOR_PATH = os.path.join(API, "examples", "record_frame_vectors.json")
SYNC_DIR = os.path.join("dev", "breaker", "shared", "api", "sync")
MAIN_DIR = os.path.join(API, "src", "main", "kotlin", SYNC_DIR)
TEST_DIR = os.path.join(API, "src", "test", "kotlin", SYNC_DIR)
FRAME_KT = os.path.join(MAIN_DIR, "RecordFrame.kt")
CODEC_KT = os.path.join(MAIN_DIR, "SyncBodyCodec.kt")
FAMILY_KT = os.path.join(MAIN_DIR, "SyncBodyException.kt")
DATA_KT = os.path.join(TEST_DIR, "RecordFrameVectorData.kt")

ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
TEXT_RE = re.compile(r"[A-Za-z0-9+/]+={0,2}")
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
NAME_RE = re.compile(r"[a-z0-9]+(-[a-z0-9]+)*")
SECTIONS = ["frames", "join_refusals", "split_refusals", "aad", "aad_refusals"]
KEYS = {
    "frames": {"name", "nonce_hex", "ciphertext_hex", "tag_hex", "encrypted"},
    "join_refusals": {"name", "nonce_hex", "ciphertext_hex", "tag_hex", "family"},
    "split_refusals": {"name", "input", "family"},
    "aad": {"name", "client_id", "key_version", "hex"},
    "aad_refusals": {"name", "client_id", "key_version", "family"},
}


def read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def load_doc():
    return json.loads(read(VECTOR_PATH))


# ---- the oracle -----------------------------------------------------------------------------

def oracle_join(nonce, ciphertext, tag):
    """The frame text, or the refusal family."""
    if len(nonce) != 12 or len(tag) != 16:
        return ("refused", "frame")
    return ("ok", base64.b64encode(nonce + ciphertext + tag).decode("ascii"))


def oracle_split(text):
    """The three parts of a frame text, or the refusal family."""
    if text == "" or TEXT_RE.fullmatch(text) is None:
        return ("refused", "base64")
    if len(text) % 4 != 0:
        return ("refused", "base64")
    try:
        raw = base64.b64decode(text, validate=True)
    except binascii.Error:
        return ("refused", "base64")
    if base64.b64encode(raw).decode("ascii") != text:
        return ("refused", "base64")
    if len(raw) < 28:
        return ("refused", "frame")
    return ("ok", (raw[:12], raw[12:len(raw) - 16], raw[len(raw) - 16:]))


def oracle_aad(client_id, key_version):
    """The associated data bytes, or the refusal family."""
    if UUID_RE.fullmatch(client_id) is None:
        return ("refused", "client_id")
    if key_version < 1:
        return ("refused", "key_version")
    return ("ok", client_id.encode("ascii") + b"\n" + str(key_version).encode("ascii"))


def kotlin_text(value):
    """A Kotlin string literal body: printable ASCII as is except backslash and quote, else \\uXXXX."""
    out = []
    for ch in value:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif 0x20 <= ord(ch) < 0x7F and ch != "$":
            out.append(ch)
        else:
            out.append("\\u%04x" % ord(ch))
    return "".join(out)


class VectorFileShapeTest(unittest.TestCase):
    def test_top_level_keys_and_version(self):
        doc = load_doc()
        self.assertEqual(list(doc), ["version", "note"] + SECTIONS)
        self.assertEqual(doc["version"], 1)
        for section in SECTIONS:
            self.assertIsInstance(doc[section], list)
            self.assertGreater(len(doc[section]), 0, section)

    def test_every_entry_has_exactly_its_keys(self):
        doc = load_doc()
        for section in SECTIONS:
            for v in doc[section]:
                self.assertEqual(set(v), KEYS[section], section + " " + str(v.get("name")))

    def test_names_are_unique_and_plain_in_every_section(self):
        doc = load_doc()
        for section in SECTIONS:
            names = [v["name"] for v in doc[section]]
            self.assertEqual(len(names), len(set(names)), section)
            for n in names:
                self.assertIsNotNone(NAME_RE.fullmatch(n), n)

    def test_json_file_is_ascii(self):
        with open(VECTOR_PATH, "rb") as fh:
            data = fh.read()
        data.decode("ascii")

    def test_no_dollar_sign_in_any_value(self):
        self.assertNotIn("$", read(VECTOR_PATH))


class FrameOracleTest(unittest.TestCase):
    def test_every_frame_is_built_by_hand_and_equals_the_file(self):
        for v in load_doc()["frames"]:
            nonce = bytes.fromhex(v["nonce_hex"])
            ciphertext = bytes.fromhex(v["ciphertext_hex"])
            tag = bytes.fromhex(v["tag_hex"])
            self.assertEqual(oracle_join(nonce, ciphertext, tag), ("ok", v["encrypted"]), v["name"])

    def test_every_frame_splits_back_to_its_parts(self):
        for v in load_doc()["frames"]:
            parts = (bytes.fromhex(v["nonce_hex"]), bytes.fromhex(v["ciphertext_hex"]),
                     bytes.fromhex(v["tag_hex"]))
            self.assertEqual(oracle_split(v["encrypted"]), ("ok", parts), v["name"])

    def test_every_accepted_frame_re_encodes_to_exactly_its_text(self):
        for v in load_doc()["frames"]:
            kind, parts = oracle_split(v["encrypted"])
            self.assertEqual(kind, "ok", v["name"])
            self.assertEqual(oracle_join(*parts), ("ok", v["encrypted"]), v["name"])
            raw = parts[0] + parts[1] + parts[2]
            self.assertEqual(base64.b64encode(raw).decode("ascii"), v["encrypted"], v["name"])

    def test_part_sizes_and_the_frame_floor(self):
        for v in load_doc()["frames"]:
            self.assertEqual(len(v["nonce_hex"]), 24, v["name"])
            self.assertEqual(len(v["tag_hex"]), 32, v["name"])
            total = (len(v["nonce_hex"]) + len(v["ciphertext_hex"]) + len(v["tag_hex"])) // 2
            self.assertGreaterEqual(total, 28, v["name"])

    def test_coverage_named_in_the_ruling_is_present(self):
        frames = {v["name"]: v for v in load_doc()["frames"]}
        self.assertEqual(len(frames["empty-ciphertext"]["ciphertext_hex"]), 0)
        self.assertEqual(len(base64.b64decode(frames["empty-ciphertext"]["encrypted"])), 28)
        self.assertEqual(len(frames["one-byte-ciphertext"]["ciphertext_hex"]), 2)
        self.assertEqual(len(frames["ciphertext-17-bytes"]["ciphertext_hex"]), 34)
        self.assertEqual(len(base64.b64decode(frames["ciphertext-17-bytes"]["encrypted"])), 45)
        self.assertEqual(len(frames["ciphertext-100-bytes"]["ciphertext_hex"]), 200)
        every_ff = frames["all-ff-bytes"]
        self.assertEqual(set(every_ff["nonce_hex"] + every_ff["ciphertext_hex"] + every_ff["tag_hex"]), {"f"})
        mixed = frames["plus-and-slash"]["encrypted"]
        self.assertIn("+", mixed)
        self.assertIn("/", mixed)
        paddings = {v["encrypted"].count("=") for v in load_doc()["frames"]}
        self.assertEqual(paddings, {0, 1, 2})


class RefusalOracleTest(unittest.TestCase):
    def test_every_join_refusal_is_re_derived(self):
        for v in load_doc()["join_refusals"]:
            got = oracle_join(bytes.fromhex(v["nonce_hex"]), bytes.fromhex(v["ciphertext_hex"]),
                              bytes.fromhex(v["tag_hex"]))
            self.assertEqual(got, ("refused", v["family"]), v["name"])

    def test_join_refusals_cover_both_sides_of_both_sizes(self):
        sizes = {(len(v["nonce_hex"]) // 2, len(v["tag_hex"]) // 2) for v in load_doc()["join_refusals"]}
        self.assertEqual(sizes, {(11, 16), (13, 16), (12, 15), (12, 17)})

    def test_every_split_refusal_is_re_derived(self):
        for v in load_doc()["split_refusals"]:
            self.assertEqual(oracle_split(v["input"]), ("refused", v["family"]), v["name"])

    def test_split_refusals_name_both_families(self):
        families = {v["family"] for v in load_doc()["split_refusals"]}
        self.assertEqual(families, {"base64", "frame"})

    def test_the_two_frame_refusals_are_27_and_12_decoded_bytes(self):
        by_name = {v["name"]: v for v in load_doc()["split_refusals"]}
        self.assertEqual(len(base64.b64decode(by_name["27-decoded-bytes"]["input"])), 27)
        self.assertEqual(len(base64.b64decode(by_name["12-decoded-bytes"]["input"])), 12)

    def test_non_canonical_vectors_have_nonzero_padding_bits_and_a_canonical_control(self):
        by_name = {v["name"]: v for v in load_doc()["split_refusals"]}
        frames = [v["encrypted"] for v in load_doc()["frames"]]
        cases = (("non-canonical-28-bytes", "==", 0x0F, 28), ("non-canonical-29-bytes", "=", 0x03, 29))
        for name, pad, mask, size in cases:
            text = by_name[name]["input"]
            self.assertTrue(text.endswith(pad) and not text.endswith("=" + pad), name)
            last = ALPHABET.index(text[-len(pad) - 1])
            self.assertNotEqual(last & mask, 0, name)
            lenient = base64.b64decode(text)
            self.assertEqual(len(lenient), size, name)
            self.assertNotEqual(base64.b64encode(lenient).decode("ascii"), text, name)
            controls = [f for f in frames if base64.b64encode(lenient).decode("ascii") == f]
            self.assertEqual(len(controls), 1, name)

    def test_every_aad_vector_is_built_by_hand(self):
        for v in load_doc()["aad"]:
            got = oracle_aad(v["client_id"], v["key_version"])
            self.assertEqual(got[0], "ok", v["name"])
            self.assertEqual(got[1].hex(), v["hex"], v["name"])

    def test_aad_versions_differ_and_version_10_has_two_digits(self):
        by_name = {v["name"]: v["hex"] for v in load_doc()["aad"]}
        one = by_name["client-a-version-1"]
        two = by_name["client-a-version-2"]
        ten = by_name["client-a-version-10"]
        self.assertEqual(len({one, two, ten}), 3)
        self.assertTrue(ten.endswith("0a3130"))
        self.assertEqual(len(ten), len(one) + 2)
        self.assertNotEqual(by_name["client-b-version-1"], one)

    def test_every_aad_refusal_is_re_derived(self):
        for v in load_doc()["aad_refusals"]:
            got = oracle_aad(v["client_id"], v["key_version"])
            self.assertEqual(got, ("refused", v["family"]), v["name"])

    def test_aad_refusals_cover_the_ruling(self):
        names = [v["name"] for v in load_doc()["aad_refusals"]]
        for part in ("key-version-0", "minus-1", "uppercase", "braces", "35-", "37-", "empty"):
            self.assertTrue(any(part in n for n in names), part)
        lengths = {len(v["client_id"]) for v in load_doc()["aad_refusals"]}
        self.assertTrue({35, 37, 0} <= lengths)


class KotlinCopyTest(unittest.TestCase):
    def expected_lines(self):
        doc = load_doc()
        lit = kotlin_text
        frames = ['        FrameVector("%s", "%s", "%s", "%s", "%s"),' % (
            v["name"], v["nonce_hex"], v["ciphertext_hex"], v["tag_hex"], lit(v["encrypted"]))
            for v in doc["frames"]]
        joins = ['        JoinRefusal("%s", "%s", "%s", "%s", "%s"),' % (
            v["name"], v["nonce_hex"], v["ciphertext_hex"], v["tag_hex"], v["family"])
            for v in doc["join_refusals"]]
        splits = ['        SplitRefusal("%s", "%s", "%s"),' % (v["name"], lit(v["input"]), v["family"])
                  for v in doc["split_refusals"]]
        aads = ['        AadVector("%s", "%s", %d, "%s"),' % (
            v["name"], v["client_id"], v["key_version"], v["hex"]) for v in doc["aad"]]
        refusals = ['        AadRefusal("%s", "%s", %d, "%s"),' % (
            v["name"], lit(v["client_id"]), v["key_version"], v["family"]) for v in doc["aad_refusals"]]
        return frames, joins, splits, aads, refusals

    def test_kotlin_copy_agrees_entry_by_entry(self):
        kt = read(DATA_KT).splitlines()
        frames, joins, splits, aads, refusals = self.expected_lines()
        prefixes = ("FrameVector", "JoinRefusal", "SplitRefusal", "AadVector", "AadRefusal")
        expected = (frames, joins, splits, aads, refusals)
        for prefix, want in zip(prefixes, expected):
            got = [line for line in kt if line.startswith('        ' + prefix + '("')]
            self.assertEqual(got, want, prefix)
            self.assertGreater(len(want), 0, prefix)

    def test_kotlin_copy_holds_no_other_entry_lines(self):
        kt = read(DATA_KT).splitlines()
        entry = [line for line in kt if line.startswith("        ") and line.endswith("),")]
        frames, joins, splits, aads, refusals = self.expected_lines()
        self.assertEqual(len(entry), len(frames) + len(joins) + len(splits) + len(aads) + len(refusals))


class SourceDefinitionsTest(unittest.TestCase):
    def count(self, pattern, text):
        return len(re.findall(pattern, text, re.M))

    def test_the_object_the_class_and_the_three_constants_are_defined_once(self):
        text = read(FRAME_KT)
        self.assertEqual(self.count(r"^object RecordFrame \{$", text), 1)
        self.assertEqual(self.count(r"^class RecordFrameParts\(", text), 1)
        self.assertEqual(self.count(r"^    const val NONCE_BYTES = 12$", text), 1)
        self.assertEqual(self.count(r"^    const val TAG_BYTES = 16$", text), 1)
        self.assertEqual(self.count(r"^    const val MIN_FRAME_BYTES = 28$", text), 1)
        self.assertEqual(self.count(r"^    fun join\(", text), 1)
        self.assertEqual(self.count(r"^    fun split\(", text), 1)
        self.assertEqual(self.count(r"^    fun aad\(", text), 1)

    def test_the_two_families_are_defined_once_after_key_order(self):
        text = read(FAMILY_KT)
        self.assertEqual(self.count(r'^    const val FRAME = "frame"$', text), 1)
        self.assertEqual(self.count(r'^    const val KEY_VERSION = "key_version"$', text), 1)
        lines = text.splitlines()
        i = lines.index('    const val KEY_ORDER = "key_order"')
        self.assertEqual(lines[i + 1], '    const val FRAME = "frame"')
        self.assertEqual(lines[i + 2], '    const val KEY_VERSION = "key_version"')

    def test_the_strict_reader_is_internal_and_defined_once(self):
        text = read(CODEC_KT)
        self.assertEqual(
            self.count(r"^    internal fun decodeBase64Strict\(text: String\): ByteArray \{$", text), 1)
        self.assertEqual(text.count("fun decodeBase64Strict("), 1)
        self.assertEqual(text.count("private fun decodeBase64Strict"), 0)

    def test_split_calls_the_strict_reader_of_the_codec(self):
        self.assertEqual(read(FRAME_KT).count("SyncBodyCodec.decodeBase64Strict(encrypted)"), 1)

    def test_the_frame_source_holds_no_cryptography(self):
        text = read(FRAME_KT)
        self.assertEqual(text.count("import javax"), 0)
        self.assertEqual(text.count("java.security"), 0)
        self.assertEqual(text.count("Cipher"), 0)


if __name__ == "__main__":
    unittest.main()
