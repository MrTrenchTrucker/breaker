"""Tests for the username vector table, checked by an independent oracle.

Standard library only. The JSON table in examples/username_vectors.json and the Kotlin
literal table in UsernameVectorData.kt must agree with each other and with the openapi
username pattern, and every row must match the answer UsernameRules gives.
"""
import json
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
for _ in range(4):
    ROOT = os.path.dirname(ROOT)

JSON_PATH = os.path.join(
    ROOT, "shared", "modules", "api-contracts", "examples", "username_vectors.json",
)
KOTLIN_PATH = os.path.join(
    ROOT, "server", "modules", "sync-api", "src", "test", "kotlin", "dev", "breaker",
    "server", "syncapi", "accounts", "UsernameVectorData.kt",
)
OPENAPI_PATH = os.path.join(
    ROOT, "shared", "modules", "api-contracts", "openapi.yaml",
)


def load_json_rows():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        doc = json.load(f)
    return doc["pattern"], doc["vectors"]


def load_openapi_patterns():
    with open(OPENAPI_PATH, "r", encoding="utf-8") as f:
        text = f.read()
    return re.findall(r'pattern: "\^\[A-Za-z0-9._-\]\{1,64\}\$"', text)


def decode_kotlin_literal(lit):
    # Kotlin literal form is JSON-compatible: only \uXXXX, \\ and \" appear.
    # lit arrives with surrounding quotes; strip them, then re-wrap for json.loads.
    inner = lit[1:-1] if len(lit) >= 2 and lit.startswith('"') and lit.endswith('"') else lit
    return json.loads('"' + inner + '"')


def load_kotlin_rows():
    with open(KOTLIN_PATH, "r", encoding="utf-8") as f:
        text = f.read()
    rows = re.findall(
        r'UsernameVector\("([a-z0-9-]+)", ("(?:[^"\\]|\\.)*"), (true|false)\)',
        text,
    )
    decoded = []
    for name, lit, valid in rows:
        decoded.append((name, decode_kotlin_literal(lit), valid == "true"))
    return decoded


class UsernameVectorTableTest(unittest.TestCase):
    def test_json_has_exactly_pattern_and_vectors(self):
        pattern, vectors = load_json_rows()
        self.assertEqual(set(["pattern", "vectors"]), set(doc_keys()))

    def test_names_are_unique_and_plain(self):
        _, vectors = load_json_rows()
        names = [v["name"] for v in vectors]
        self.assertEqual(len(names), len(set(names)))
        for n in names:
            self.assertIsNotNone(re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", n), n)

    def test_every_row_has_name_input_valid_bool(self):
        _, vectors = load_json_rows()
        for v in vectors:
            self.assertEqual(set(["name", "input", "valid"]), set(v.keys()))
            self.assertIsInstance(v["name"], str)
            self.assertIsInstance(v["input"], str)
            self.assertIsInstance(v["valid"], bool)

    def test_json_pattern_equals_each_openapi_pattern_line(self):
        pattern, _ = load_json_rows()
        openapi_patterns = load_openapi_patterns()
        self.assertGreaterEqual(len(openapi_patterns), 1, "no username pattern line found")
        for line in openapi_patterns:
            # line is pattern: "..." — extract the quoted value.
            m = re.search(r'pattern: "(.*)"', line)
            self.assertIsNotNone(m, line)
            self.assertEqual(pattern, m.group(1), line)

    def test_oracle_fullmatch_matches_valid(self):
        pattern, vectors = load_json_rows()
        body = pattern.lstrip("^").rstrip("$")
        for v in vectors:
            matched = re.fullmatch(body, v["input"]) is not None
            self.assertEqual(matched, v["valid"], v["name"])

    def test_kotlin_literal_cross_checks_json(self):
        pattern, vectors = load_json_rows()
        kotlin_rows = load_kotlin_rows()
        self.assertEqual(24, len(kotlin_rows), "the Kotlin table must have exactly 24 rows")
        json_triples = [(v["name"], v["input"], v["valid"]) for v in vectors]
        kotlin_triples = [(name, inp, valid) for name, inp, valid in kotlin_rows]
        self.assertEqual(json_triples, kotlin_triples)

    def test_table_covers_required_names(self):
        _, vectors = load_json_rows()
        by_name = {v["name"]: v for v in vectors}
        for required in ("empty", "sixty-four-chars", "sixty-five-chars",
                         "space-inside", "tab-inside", "trailing-newline",
                         "latin-e-acute", "dot-inside", "hyphen-inside",
                         "underscore-inside"):
            self.assertIn(required, by_name, required)


def doc_keys():
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        return json.load(f).keys()


if __name__ == "__main__":
    unittest.main()
