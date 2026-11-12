"""Tests for the sealed body vector file (the strict server R1 reader), checked by the oracle module.

Standard library only. The strict function below is written here from the oracle pieces: the
oracle body() reader, then the key order of the oracle Scanner document, then source server.
"""
import json
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_body_oracle import (  # noqa: E402
    API, FAMILIES, KOTLIN_PATH, Refusal, Scanner, body, kotlin_literal, read)

VECTOR_PATH = os.path.join(API, "examples", "sealed_body_vectors.json")
KOTLIN_DIR = os.path.dirname(KOTLIN_PATH)
SEALED_KT = os.path.join(KOTLIN_DIR, "SealedBodyVectorData.kt")
MAIN_DIR = os.path.join(API, "src", "main", "kotlin", "dev", "breaker", "shared", "api", "sync")
STRICT_ORDER = ["v", "text", "source", "model", "duration_ms", "created_at"]
EXTRA_FAMILY = "key_order"


def load_doc():
    return json.loads(read(VECTOR_PATH))


def input_bytes(v):
    return bytes.fromhex(v["input_hex"]) if "input_hex" in v else v["input"].encode("utf-8")


def strict(raw):
    """The strict server reader: oracle body(), then key order, then source server."""
    result = body(raw)
    members = Scanner(raw.decode("utf-8")).document()
    if list(members) != STRICT_ORDER:
        raise Refusal(EXTRA_FAMILY)
    if result["source"] != "server":
        raise Refusal("source")
    return result


def outcome(raw):
    try:
        return ("ok", strict(raw))
    except Refusal as e:
        return ("refused", str(e))


class VectorFileShapeTest(unittest.TestCase):
    def test_top_level_keys_and_version(self):
        doc = load_doc()
        self.assertEqual(list(doc), ["version", "note", "vectors"])
        self.assertEqual(doc["version"], 1)
        self.assertIsInstance(doc["vectors"], list)

    def test_names_are_unique_and_plain(self):
        names = [v["name"] for v in load_doc()["vectors"]]
        self.assertEqual(len(names), len(set(names)))
        for n in names:
            self.assertIsNotNone(re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", n), n)

    def test_every_entry_has_the_fields_its_expectation_needs(self):
        for v in load_doc()["vectors"]:
            name = v["name"]
            self.assertIn(v["expect"], ("ok", "refused"), name)
            keys = set(v)
            if v["expect"] == "ok":
                self.assertEqual(
                    keys, {"name", "input", "expect", "text", "model", "duration_ms", "created_at_ms"},
                    name)
            else:
                self.assertEqual(("input" in v) + ("input_hex" in v), 1, name)
                self.assertEqual(
                    keys - {"input", "input_hex"}, {"name", "expect", "family"}, name)

    def test_json_file_is_ascii(self):
        with open(VECTOR_PATH, "rb") as fh:
            data = fh.read()
        data.decode("ascii")


class OracleStrictTest(unittest.TestCase):
    def test_every_entry_is_re_derived_by_the_strict_function(self):
        for v in load_doc()["vectors"]:
            kind, got = outcome(input_bytes(v))
            self.assertEqual(kind, v["expect"], v["name"])
            if kind == "ok":
                self.assertEqual(
                    (got["text"], got["model"], got["duration"], got["created"]),
                    (v["text"], v["model"], v["duration_ms"], v["created_at_ms"]), v["name"])
            else:
                self.assertEqual(got, v["family"], v["name"])

    def test_every_family_is_known(self):
        for v in load_doc()["vectors"]:
            if v["expect"] == "refused":
                self.assertTrue(v["family"] in FAMILIES or v["family"] == EXTRA_FAMILY, v["name"])

    def test_coverage_named_in_the_ruling_is_present(self):
        names = [v["name"] for v in load_doc()["vectors"]]
        for part in ("source-local", "version-2", "missing-", "order-", "extra-key",
                     "duplicate-key", "trailing", "bom", "non-utf8"):
            self.assertTrue(any(part in n for n in names), part)


class KotlinCopyTest(unittest.TestCase):
    def expected_lines(self):
        ok, texts, raws = [], [], []
        for v in load_doc()["vectors"]:
            lit = kotlin_literal
            if v["expect"] == "ok":
                ok.append('        OkBody("%s", "%s", "%s", "%s", %dL, %dL),' % (
                    v["name"], lit(v["input"]), lit(v["text"]), lit(v["model"]),
                    v["duration_ms"], v["created_at_ms"]))
            elif "input" in v:
                texts.append('        RefuseText("%s", "%s", "%s"),' % (
                    v["name"], lit(v["input"]), v["family"]))
            else:
                raws.append('        RefuseBytes("%s", "%s", "%s"),' % (
                    v["name"], v["input_hex"], v["family"]))
        return ok, texts, raws

    def test_kotlin_copy_agrees_entry_by_entry(self):
        kt = read(SEALED_KT).splitlines()
        ok, texts, raws = self.expected_lines()
        self.assertEqual([l for l in kt if l.startswith('        OkBody("')], ok)
        self.assertEqual([l for l in kt if l.startswith('        RefuseText("')], texts)
        self.assertEqual([l for l in kt if l.startswith('        RefuseBytes("')], raws)
        self.assertEqual(len(ok) + len(texts) + len(raws), len(load_doc()["vectors"]))


class SourceDefinitionsTest(unittest.TestCase):
    def test_key_order_constant_is_defined_once(self):
        text = read(os.path.join(MAIN_DIR, "SyncBodyException.kt"))
        self.assertEqual(len(re.findall(r'^    const val KEY_ORDER = "key_order"$', text, re.M)), 1)

    def test_strict_key_order_list_and_function_are_defined_once(self):
        text = read(os.path.join(MAIN_DIR, "SyncBodyCodec.kt"))
        found = re.findall(r"^\s*private val STRICT_KEY_ORDER = listOf\((.*)\)$", text, re.M)
        self.assertEqual(len(found), 1)
        self.assertEqual(re.findall(r'"([^"]*)"', found[0]), STRICT_ORDER)
        self.assertEqual(text.count("STRICT_KEY_ORDER ="), 1)
        self.assertEqual(len(re.findall(r"fun decodeStrictServerBody\(", text)), 1)


if __name__ == "__main__":
    unittest.main()
