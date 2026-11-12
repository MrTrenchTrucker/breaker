"""Tests for the shared crypto vector table, checked by an independent oracle.

Standard library only. The JSON table in examples/crypto_vectors.json and the Kotlin literal
copy in CryptoVectorData.kt must agree, the HKDF split and the X25519 public keys are
recomputed here, and the table keeps its shape and sizes.
"""
import hashlib
import hmac
import json
import os
import re
import unicodedata
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

JSON_PATH = os.path.join(
    ROOT, "shared", "modules", "api-contracts", "examples", "crypto_vectors.json",
)
KOTLIN_PATH = os.path.join(
    ROOT, "android", "modules", "crypto", "src", "test", "kotlin",
    "dev", "breaker", "dictation", "crypto", "CryptoVectorData.kt",
)

KDF_KEYS = {
    "name", "password_utf8", "salt_hex", "memory_kib", "iterations", "parallelism",
    "output_length", "kdf_version", "argon2id_hex", "kek_hex", "verifier_hex",
}
WRAP_KEYS = {"name", "wrapper_key_hex", "nonce_hex", "aad_hex", "plain_hex", "wrapped_hex"}
REFUSAL_KEYS = {"name", "wrapper_key_hex", "wrapped_hex", "aad_hex"}
X25519_KEYS = {"name", "private_hex", "public_hex"}
HEX_BYTES = {
    "salt_hex": 16, "argon2id_hex": 32, "kek_hex": 32, "verifier_hex": 32,
    "wrapper_key_hex": 32, "nonce_hex": 12, "plain_hex": 32, "wrapped_hex": 60,
    "private_hex": 32, "public_hex": 32,
}
BOX_AAD = b"breaker-box-privkey-v1"
PREV_AAD = b"breaker-prev-dek-v1"


def load_json():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def decode_kotlin_literal(lit):
    # Kotlin literal form is JSON-compatible: only \uXXXX, \\ and \" appear.
    return json.loads(lit)


def kotlin_value(raw):
    """A Kotlin value: a whole number, or one string literal or several joined with a plus."""
    raw = raw.strip()
    if re.fullmatch(r"\d+", raw):
        return int(raw)
    parts = re.findall(r'"(?:[^"\\]|\\.)*"', raw)
    return "".join(decode_kotlin_literal(p) for p in parts)


def load_kotlin_blocks(class_name):
    with open(KOTLIN_PATH, "r", encoding="utf-8") as f:
        text = f.read()
    blocks = re.findall(r"(?<!class )%s\(\n(.*?)\n {8}\)" % class_name, text, re.S)
    rows = []
    for body in blocks:
        row = {}
        fields = re.findall(
            r'^ {12}(\w+) = ((?:"(?:[^"\\]|\\.)*"(?: \+\n {16})?)+|\d+),$', body, re.M,
        )
        for key, raw in fields:
            row[key] = kotlin_value(raw.replace("\n", ""))
        rows.append(row)
    return rows


def hkdf_sha256(ikm, info, length=32):
    prk = hmac.new(b"", ikm, hashlib.sha256).digest()
    out = b""
    block = b""
    counter = 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


P = 2 ** 255 - 19
A24 = 121665


def x25519(private, u_bytes):
    k = bytearray(private)
    k[0] &= 248
    k[31] &= 127
    k[31] |= 64
    k = int.from_bytes(k, "little")
    x1 = int.from_bytes(u_bytes, "little") & ((1 << 255) - 1)
    x2, z2, x3, z3, swap = 1, 0, x1, 1, 0
    for t in range(254, -1, -1):
        k_t = (k >> t) & 1
        swap ^= k_t
        if swap:
            x2, x3 = x3, x2
            z2, z3 = z3, z2
        swap = k_t
        a = (x2 + z2) % P
        aa = a * a % P
        b = (x2 - z2) % P
        bb = b * b % P
        e = (aa - bb) % P
        c = (x3 + z3) % P
        d = (x3 - z3) % P
        da = d * a % P
        cb = c * b % P
        x3 = (da + cb) ** 2 % P
        z3 = x1 * ((da - cb) ** 2) % P
        x2 = aa * bb % P
        z2 = e * (aa + A24 * e) % P
    if swap:
        x2, x3 = x3, x2
        z2, z3 = z3, z2
    return (x2 * pow(z2, P - 2, P) % P).to_bytes(32, "little")


BASE_POINT = (9).to_bytes(32, "little")


class CryptoVectorTableTest(unittest.TestCase):
    def test_shape_and_sizes(self):
        doc = load_json()
        self.assertEqual(
            {"version", "kdf", "wrap", "wrap_refusals", "x25519", "seal", "seal_refusals"},
            set(doc.keys()),
        )
        self.assertEqual(1, doc["version"])
        for section, keys in (
            ("kdf", KDF_KEYS), ("wrap", WRAP_KEYS),
            ("wrap_refusals", REFUSAL_KEYS), ("x25519", X25519_KEYS),
        ):
            names = [row["name"] for row in doc[section]]
            self.assertEqual(len(names), len(set(names)), section + " names must be unique")
            for row in doc[section]:
                self.assertIn(set(row.keys()), (keys, keys | {"note"}), row["name"])
                for key, size in HEX_BYTES.items():
                    if key in row:
                        self.assertIsNotNone(
                            re.fullmatch(r"[0-9a-f]{%d}" % (size * 2), row[key]),
                            row["name"] + " " + key,
                        )
                if "aad_hex" in row:
                    self.assertIsNotNone(
                        re.fullmatch(r"(?:[0-9a-f]{2})*", row["aad_hex"]), row["name"] + " aad_hex",
                    )

    def test_kotlin_copy_agrees_with_json(self):
        doc = load_json()
        kdf_map = {
            "name": "name", "password": "password_utf8", "saltHex": "salt_hex",
            "memoryKib": "memory_kib", "iterations": "iterations",
            "parallelism": "parallelism", "outputLength": "output_length",
            "kdfVersion": "kdf_version", "argon2idHex": "argon2id_hex",
            "kekHex": "kek_hex", "verifierHex": "verifier_hex",
        }
        wrap_map = {
            "name": "name", "wrapperKeyHex": "wrapper_key_hex", "nonceHex": "nonce_hex",
            "aadHex": "aad_hex", "plainHex": "plain_hex", "wrappedHex": "wrapped_hex",
        }
        refusal_map = {
            "name": "name", "wrapperKeyHex": "wrapper_key_hex", "wrappedHex": "wrapped_hex",
            "aadHex": "aad_hex",
        }
        x_map = {"name": "name", "privateHex": "private_hex", "publicHex": "public_hex"}
        for class_name, section, mapping in (
            ("KdfVector", "kdf", kdf_map),
            ("WrapVector", "wrap", wrap_map),
            ("WrapRefusal", "wrap_refusals", refusal_map),
            ("X25519Vector", "x25519", x_map),
        ):
            kotlin_rows = load_kotlin_blocks(class_name)
            self.assertEqual(len(doc[section]), len(kotlin_rows), section + " row count")
            for json_row, kotlin_row in zip(doc[section], kotlin_rows):
                self.assertEqual(set(mapping.keys()), set(kotlin_row.keys()), json_row["name"])
                expected = {k: json_row[v] for k, v in mapping.items()}
                self.assertEqual(expected, kotlin_row, json_row["name"])

    def test_hkdf_split_is_recomputed(self):
        for row in load_json()["kdf"]:
            ikm = bytes.fromhex(row["argon2id_hex"])
            kek = hkdf_sha256(ikm, b"breaker-kek-v1").hex()
            verifier = hkdf_sha256(ikm, b"breaker-auth-verifier-v1").hex()
            self.assertEqual(row["kek_hex"], kek, row["name"])
            self.assertEqual(row["verifier_hex"], verifier, row["name"])
            self.assertNotEqual(kek, verifier, row["name"])

    def test_v2_spellings_differ_but_normalise_to_the_same_bytes(self):
        rows = {row["name"]: row for row in load_json()["kdf"]}
        composed = rows["v2-composed"]
        decomposed = rows["v2-decomposed"]
        self.assertNotEqual(composed["password_utf8"], decomposed["password_utf8"])
        self.assertEqual(
            unicodedata.normalize("NFC", composed["password_utf8"]).encode("utf-8"),
            unicodedata.normalize("NFC", decomposed["password_utf8"]).encode("utf-8"),
        )
        for key in ("argon2id_hex", "kek_hex", "verifier_hex"):
            self.assertEqual(composed[key], decomposed[key], key)

    def test_wrapped_form_starts_with_the_nonce(self):
        for row in load_json()["wrap"]:
            self.assertTrue(row["wrapped_hex"].startswith(row["nonce_hex"]), row["name"])
            self.assertEqual(60, len(bytes.fromhex(row["wrapped_hex"])), row["name"])

    def test_wrap_aad_is_the_purpose_string(self):
        rows = {row["name"]: row for row in load_json()["wrap"]}
        self.assertEqual(
            {"dek-under-kek", "box-private-key-under-dek", "previous-dek-under-new-dek"}, set(rows),
        )
        self.assertEqual("", rows["dek-under-kek"]["aad_hex"])
        self.assertEqual(BOX_AAD, bytes.fromhex(rows["box-private-key-under-dek"]["aad_hex"]))
        self.assertEqual(PREV_AAD, bytes.fromhex(rows["previous-dek-under-new-dek"]["aad_hex"]))

    def test_each_refusal_belongs_to_one_wrap_entry_with_another_aad(self):
        doc = load_json()
        self.assertGreaterEqual(len(doc["wrap_refusals"]), 1)
        for refusal in doc["wrap_refusals"]:
            owners = [w for w in doc["wrap"] if w["wrapped_hex"] == refusal["wrapped_hex"]]
            self.assertEqual(1, len(owners), refusal["name"])
            self.assertNotEqual(owners[0]["aad_hex"], refusal["aad_hex"], refusal["name"])
            self.assertEqual(owners[0]["wrapper_key_hex"], refusal["wrapper_key_hex"], refusal["name"])

    def test_x25519_public_keys_are_recomputed(self):
        rows = {row["name"]: row for row in load_json()["x25519"]}
        for row in rows.values():
            computed = x25519(bytes.fromhex(row["private_hex"]), BASE_POINT).hex()
            self.assertEqual(row["public_hex"], computed, row["name"])
        alice = rows["rfc7748-6.1-alice"]
        bob = rows["rfc7748-6.1-bob"]
        shared_a = x25519(bytes.fromhex(alice["private_hex"]), bytes.fromhex(bob["public_hex"]))
        shared_b = x25519(bytes.fromhex(bob["private_hex"]), bytes.fromhex(alice["public_hex"]))
        self.assertEqual(shared_a, shared_b)

    def test_json_file_is_ascii(self):
        with open(JSON_PATH, "rb") as f:
            data = f.read()
        data.decode("ascii")


if __name__ == "__main__":
    unittest.main()
