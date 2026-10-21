"""Tests for the sync body vector file, checked by the independent oracle module.

Standard library only. The oracle (sync_body_oracle.py) re-derives every vector without the
codec; these tests compare its results with the expected values in the vector file.
"""
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_body_oracle import (  # noqa: E402
    FAMILIES, KINDS, KOTLIN_PATH, b64, body, client_id, created_at_epoch, input_bytes,
    kotlin_literal, load_doc, plain, r1_text, read, refusal, vectors)


class VectorFileShapeTest(unittest.TestCase):
    def test_top_level_is_an_object_with_a_vectors_array(self):
        doc = load_doc()
        self.assertEqual(list(doc), ["vectors"])
        self.assertIsInstance(doc["vectors"], list)

    def test_names_are_unique_and_plain(self):
        names = [v["name"] for v in vectors()]
        self.assertEqual(len(names), len(set(names)))
        for n in names:
            self.assertIsNotNone(re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", n), n)

    def test_every_entry_has_a_known_kind_and_expect(self):
        for v in vectors():
            name, ok = v["name"], v["expect"] == "ok"
            self.assertIn(v["kind"], KINDS, name)
            self.assertIn(v["expect"], ("ok", "refuse"), name)
            self.assertTrue(("input" in v) != ("input_hex" in v), name)
            self.assertTrue("input_hex" not in v or v["kind"] == "body", name)
            if ok and v["kind"] in ("body", "plain"):
                self.assertTrue({"r1", "base64"} <= set(v), name)
            if ok and v["kind"] == "created_at":
                self.assertIn("epoch_ms", v, name)
            if not ok:
                self.assertIn("family", v, name)

    def test_every_refusal_names_a_known_family(self):
        for v in vectors(expect="refuse"):
            self.assertIn(v["family"], FAMILIES, v["name"])


class OracleBodyTest(unittest.TestCase):
    def test_every_positive_body_is_re_derived_from_its_input(self):
        for v in vectors("body", "ok"):
            r1 = r1_text(body(input_bytes(v)))
            self.assertEqual((r1, b64(r1)), (v["r1"], v["base64"]), v["name"])

    def test_every_negative_body_is_refused_with_its_family(self):
        for v in vectors("body", "refuse"):
            self.assertEqual(refusal(body, input_bytes(v)), v["family"], v["name"])


class OraclePlainTest(unittest.TestCase):
    def test_every_positive_plain_decodes_to_its_r1_text(self):
        for v in vectors("plain", "ok"):
            r1 = r1_text(plain(v["input"]))
            self.assertEqual((r1, b64(r1), v["base64"]), (v["r1"], v["input"], v["input"]), v["name"])

    def test_every_negative_plain_is_refused_with_its_family(self):
        for v in vectors("plain", "refuse"):
            self.assertEqual(refusal(plain, v["input"]), v["family"], v["name"])


class OracleClientIdAndTimeTest(unittest.TestCase):
    def test_every_client_id_vector_matches_the_oracle(self):
        for v in vectors("client_id"):
            self.assertEqual(refusal(client_id, v["input"]), v.get("family", "ok"), v["name"])

    def test_every_created_at_vector_matches_the_oracle(self):
        for v in vectors("created_at"):
            if v["expect"] == "ok":
                self.assertEqual(created_at_epoch(v["input"]), v["epoch_ms"], v["name"])
            else:
                self.assertEqual(refusal(created_at_epoch, v["input"]), v["family"], v["name"])


class OracleVerbatimTest(unittest.TestCase):
    def test_every_vector_string_appears_verbatim_in_the_kotlin_data_file(self):
        kt = read(KOTLIN_PATH)
        for v in load_doc()["vectors"]:
            for k in ("input", "input_hex", "r1", "base64"):
                if k in v:
                    self.assertIn('"' + kotlin_literal(v[k]) + '"', kt, v["name"])

    def test_every_positive_body_base64_appears_once_in_the_kotlin_data_file(self):
        # Once for each vector field holding the value: the base64 of P01 is also the input
        # of PL01, and the base64 of P23 is also the input of PL02.
        kt = read(KOTLIN_PATH)
        every = [v[k] for v in load_doc()["vectors"] for k in ("input", "input_hex", "r1", "base64")
                 if k in v]
        for v in vectors("body", "ok"):
            lit = '"' + kotlin_literal(v["base64"]) + '"'
            self.assertEqual(kt.count(lit), every.count(v["base64"]), v["name"])


if __name__ == "__main__":
    unittest.main()
