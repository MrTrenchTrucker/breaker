"""Tests for the sealed box vectors of the shared crypto vector table, checked by an independent oracle.

Standard library only. XSalsa20, HSalsa20 and Poly1305 are written out here from their published
definitions and checked against published test vectors first, so a bug in this file cannot hide.
Every key, nonce, tag and ciphertext of the seal and seal_refusals sections is then recomputed, and the
Kotlin literal copy in CryptoVectorData.kt must agree with the JSON.
"""
import hashlib
import json
import re
import unittest

from test_crypto_vectors import BASE_POINT, load_json, load_kotlin_blocks, x25519

MASK = 0xFFFFFFFF
SEAL_KEYS = {
    "name", "recipient_private_hex", "recipient_public_hex", "ephemeral_private_hex",
    "ephemeral_public_hex", "nonce_hex", "plain_hex", "box_hex",
}
REFUSAL_KEYS = {"name", "recipient_private_hex", "box_hex"}
KEY_SIZES = {
    "recipient_private_hex": 32, "recipient_public_hex": 32, "ephemeral_private_hex": 32,
    "ephemeral_public_hex": 32, "nonce_hex": 24,
}
R1_KEYS = ["v", "text", "source", "model", "duration_ms", "created_at"]
SEAL_MAP = {
    "name": "name", "recipientPrivateHex": "recipient_private_hex",
    "recipientPublicHex": "recipient_public_hex", "ephemeralPrivateHex": "ephemeral_private_hex",
    "ephemeralPublicHex": "ephemeral_public_hex", "nonceHex": "nonce_hex",
    "plainHex": "plain_hex", "boxHex": "box_hex",
}
REFUSAL_MAP = {"name": "name", "recipientPrivateHex": "recipient_private_hex", "boxHex": "box_hex"}


def rotl(x, n):
    return ((x << n) & MASK) | (x >> (32 - n))


def quarter(y, a, b, c, d):
    y[b] ^= rotl((y[a] + y[d]) & MASK, 7)
    y[c] ^= rotl((y[b] + y[a]) & MASK, 9)
    y[d] ^= rotl((y[c] + y[b]) & MASK, 13)
    y[a] ^= rotl((y[d] + y[c]) & MASK, 18)


def twenty_rounds(x):
    for _ in range(10):
        quarter(x, 0, 4, 8, 12)
        quarter(x, 5, 9, 13, 1)
        quarter(x, 10, 14, 2, 6)
        quarter(x, 15, 3, 7, 11)
        quarter(x, 0, 1, 2, 3)
        quarter(x, 5, 6, 7, 4)
        quarter(x, 10, 11, 8, 9)
        quarter(x, 15, 12, 13, 14)


def initial_state(key, words6to9):
    sigma = [int.from_bytes(b"expand 32-byte k"[i:i + 4], "little") for i in (0, 4, 8, 12)]
    k = [int.from_bytes(key[i:i + 4], "little") for i in range(0, 32, 4)]
    state = [0] * 16
    state[0], state[5], state[10], state[15] = sigma
    state[1:5] = k[0:4]
    state[11:15] = k[4:8]
    state[6:10] = words6to9
    return state


def hsalsa20(key, input16):
    words = [int.from_bytes(input16[i:i + 4], "little") for i in range(0, 16, 4)]
    x = initial_state(key, words)
    twenty_rounds(x)
    out = [x[0], x[5], x[10], x[15], x[6], x[7], x[8], x[9]]
    return b"".join(w.to_bytes(4, "little") for w in out)


def salsa20_block(key, nonce8, counter):
    words = [
        int.from_bytes(nonce8[0:4], "little"), int.from_bytes(nonce8[4:8], "little"),
        counter & MASK, counter >> 32,
    ]
    start = initial_state(key, words)
    x = list(start)
    twenty_rounds(x)
    return b"".join(((x[i] + start[i]) & MASK).to_bytes(4, "little") for i in range(16))


def xsalsa20_stream(key, nonce24, length):
    subkey = hsalsa20(key, nonce24[:16])
    out = b""
    counter = 0
    while len(out) < length:
        out += salsa20_block(subkey, nonce24[16:], counter)
        counter += 1
    return out[:length]


def poly1305(key, message):
    r = int.from_bytes(key[:16], "little") & 0x0FFFFFFC0FFFFFFC0FFFFFFC0FFFFFFF
    s = int.from_bytes(key[16:], "little")
    p = (1 << 130) - 5
    acc = 0
    for i in range(0, len(message), 16):
        block = message[i:i + 16]
        n = int.from_bytes(block + b"\x01", "little")
        acc = (acc + n) * r % p
    return ((acc + s) & ((1 << 128) - 1)).to_bytes(16, "little")


def xor(a, b):
    return bytes(x ^ y for x, y in zip(a, b))


def shared_key(private, other_public):
    return hsalsa20(x25519(private, other_public), bytes(16))


def seal_parts(box):
    return box[:32], box[32:48], box[48:]


def box_nonce(epk, rpk):
    return hashlib.blake2b(epk + rpk, digest_size=24).digest()


def tag_of(key, nonce, ciphertext):
    stream = xsalsa20_stream(key, nonce, 32)
    return poly1305(stream, ciphertext)


def bits_between(a, b):
    return sum(bin(x ^ y).count("1") for x, y in zip(a, b))


def seal_rows():
    return load_json()["seal"]


class OwnCodeKnownAnswerTest(unittest.TestCase):
    """The helpers above against published vectors, before anything in the table is trusted."""

    def test_poly1305_rfc8439_section_2_5_2(self):
        key = bytes.fromhex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        tag = poly1305(key, b"Cryptographic Forum Research Group")
        self.assertEqual("a8061dc1305136c6c22b8baf0c0127a9", tag.hex())

    def test_hsalsa20_nacl_core_vector(self):
        key = bytes.fromhex("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389")
        data = bytes.fromhex("69696ee955b62b73cd62bda875fc73d6")
        expected = "dc908dda0b9344a953629b733820778880f3ceb421bb61b91cbd4c3e66256ce4"
        self.assertEqual(expected, hsalsa20(key, data).hex())


class SealVectorTest(unittest.TestCase):
    def test_shape_and_sizes(self):
        doc = load_json()
        self.assertTrue({"seal", "seal_refusals"} <= set(doc.keys()))
        for section, keys in (("seal", SEAL_KEYS), ("seal_refusals", REFUSAL_KEYS)):
            names = [row["name"] for row in doc[section]]
            self.assertEqual(len(names), len(set(names)), section + " names must be unique")
            for row in doc[section]:
                self.assertIn(set(row.keys()), (keys, keys | {"note"}), row["name"])
                for key, value in row.items():
                    if key.endswith("_hex"):
                        self.assertIsNotNone(re.fullmatch(r"(?:[0-9a-f]{2})*", value), row["name"] + key)
                for key, size in KEY_SIZES.items():
                    if key in row:
                        self.assertEqual(size, len(bytes.fromhex(row[key])), row["name"] + " " + key)
        self.assertEqual(3, len(doc["seal"]))
        self.assertEqual(4, len(doc["seal_refusals"]))
        for row in doc["seal"]:
            plain = bytes.fromhex(row["plain_hex"])
            self.assertEqual(len(plain) + 48, len(bytes.fromhex(row["box_hex"])), row["name"])

    def test_public_keys_are_recomputed_and_the_box_starts_with_the_ephemeral_key(self):
        for row in seal_rows():
            for private, public in (
                ("recipient_private_hex", "recipient_public_hex"),
                ("ephemeral_private_hex", "ephemeral_public_hex"),
            ):
                computed = x25519(bytes.fromhex(row[private]), BASE_POINT).hex()
                self.assertEqual(row[public], computed, row["name"] + " " + public)
            self.assertTrue(row["box_hex"].startswith(row["ephemeral_public_hex"]), row["name"])

    def test_nonce_is_the_blake2b_of_the_two_public_keys(self):
        for row in seal_rows():
            epk = bytes.fromhex(row["ephemeral_public_hex"])
            rpk = bytes.fromhex(row["recipient_public_hex"])
            self.assertEqual(row["nonce_hex"], box_nonce(epk, rpk).hex(), row["name"])

    def test_tag_and_ciphertext_are_recomputed_and_the_recipient_opens_the_box(self):
        for row in seal_rows():
            name = row["name"]
            epk = bytes.fromhex(row["ephemeral_public_hex"])
            rpk = bytes.fromhex(row["recipient_public_hex"])
            plain = bytes.fromhex(row["plain_hex"])
            box = bytes.fromhex(row["box_hex"])
            nonce = bytes.fromhex(row["nonce_hex"])

            key = shared_key(bytes.fromhex(row["ephemeral_private_hex"]), rpk)
            stream = xsalsa20_stream(key, nonce, 32 + len(plain))
            ciphertext = xor(plain, stream[32:])
            tag = poly1305(stream[:32], ciphertext)
            self.assertEqual(box, epk + tag + ciphertext, name + " recomputed box")

            open_key = shared_key(bytes.fromhex(row["recipient_private_hex"]), epk)
            self.assertEqual(key, open_key, name + " both sides share one key")
            got_epk, got_tag, got_ct = seal_parts(box)
            self.assertEqual(got_tag, tag_of(open_key, nonce, got_ct), name + " tag on opening")
            opened = xor(got_ct, xsalsa20_stream(open_key, nonce, 32 + len(got_ct))[32:])
            self.assertEqual(plain, opened, name + " opened plain")

    def test_every_refusal_fails_the_tag_check_and_belongs_to_a_seal_box(self):
        doc = load_json()
        for row in doc["seal_refusals"]:
            name = row["name"]
            private = bytes.fromhex(row["recipient_private_hex"])
            box = bytes.fromhex(row["box_hex"])
            epk, tag, ciphertext = seal_parts(box)
            rpk = x25519(private, BASE_POINT)
            key = shared_key(private, epk)
            computed = tag_of(key, box_nonce(epk, rpk), ciphertext)
            self.assertNotEqual(tag, computed, name + " the tag must not verify")

            if name.endswith("-wrong-recipient"):
                owners = [s for s in doc["seal"] if s["box_hex"] == row["box_hex"]]
                self.assertEqual(1, len(owners), name)
                self.assertNotEqual(owners[0]["recipient_private_hex"], row["recipient_private_hex"], name)
            elif name.endswith("-flipped-bit"):
                owners = [
                    s for s in doc["seal"]
                    if len(s["box_hex"]) == len(row["box_hex"])
                    and bits_between(bytes.fromhex(s["box_hex"]), box) == 1
                ]
                self.assertEqual(1, len(owners), name)
                self.assertEqual(owners[0]["recipient_private_hex"], row["recipient_private_hex"], name)
                good = shared_key(private, epk)
                good_tag = tag_of(good, box_nonce(epk, rpk), bytes.fromhex(owners[0]["box_hex"])[48:])
                self.assertEqual(tag, good_tag, name + " the tag is the original tag, the body changed")
            else:
                self.fail(name + " must be a wrong-recipient or flipped-bit row")

    def test_r1_bodies_are_six_key_json_with_the_right_source(self):
        rows = {row["name"]: row for row in seal_rows()}
        for name, source in (("r1-server-body", "server"), ("r1-source-local-body", "local")):
            text = bytes.fromhex(rows[name]["plain_hex"]).decode("ascii")
            body = json.loads(text, object_pairs_hook=lambda pairs: pairs)
            self.assertEqual(R1_KEYS, [k for k, _ in body], name)
            self.assertEqual(source, dict(body)["source"], name)

    def test_signin_package_plain_has_the_pattern_and_the_key_roles(self):
        doc = load_json()
        rows = {row["name"]: row for row in doc["seal"]}
        row = rows["signin-package-65"]
        plain = bytes.fromhex(row["plain_hex"])
        self.assertEqual(65, len(plain))
        self.assertEqual(1, plain[0])
        self.assertEqual(bytes((0xA0 + i) % 256 for i in range(32)), plain[1:33])
        self.assertEqual(bytes((0x30 + i) % 256 for i in range(32)), plain[33:65])
        x = {r["name"]: r for r in doc["x25519"]}
        self.assertEqual(x["rfc7748-6.1-bob"]["private_hex"], row["ephemeral_private_hex"])
        self.assertEqual(x["rfc7748-6.1-alice"]["private_hex"], row["recipient_private_hex"])

    def test_kotlin_copy_agrees_with_json(self):
        doc = load_json()
        for class_name, section, mapping in (
            ("SealVector", "seal", SEAL_MAP),
            ("SealRefusal", "seal_refusals", REFUSAL_MAP),
        ):
            kotlin_rows = load_kotlin_blocks(class_name)
            self.assertEqual(len(doc[section]), len(kotlin_rows), section + " row count")
            for json_row, kotlin_row in zip(doc[section], kotlin_rows):
                self.assertEqual(set(mapping.keys()), set(kotlin_row.keys()), json_row["name"])
                expected = {k: json_row[v] for k, v in mapping.items()}
                self.assertEqual(expected, kotlin_row, json_row["name"])


if __name__ == "__main__":
    unittest.main()
