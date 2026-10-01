"""ADR-016 decision item 4 says what tools/yaml_subset.py does; each sentence of it is held here.

The item is the project's recorded decision, so its text is pinned word for word,
and every claim in it is checked against the reader instead of trusted: the
reader is standard-library only, it supports what the item lists, it reads every
plain scalar as a string with an empty value as the only null, and it refuses what
the item says it refuses, with the line number.

Not checkable from this tree: that "the same reader serves the api-contracts spec
checks". That module's checks are not here to read. What is checked is that the
reader reads a spec-shaped document and hands back the strings those checks need.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import ast
import os
import re
import sys
import unittest

from yaml_support import ROOT, TOOLS, YamlCase, loads, yaml_subset

ADR = os.path.join(ROOT, "decisions", "ADR-016-model-registry-codegen.md")
YAML_LIBRARIES = {"yaml", "ruamel", "strictyaml", "oyaml", "pyyaml"}

# The decision item exactly as recorded (line wrapping is free, the words are not).
ITEM_4 = (
    "4. The Python tier reads YAML through one small standard-library-only reader "
    "in `tools/`. It supports block and flow mappings and sequences (a flow "
    "collection may nest inside another), plain and quoted scalars, block scalars "
    "and whole-line comments. Every plain scalar is read as a string, with no "
    "implicit numbers, booleans or dates; the only null is an empty value, and "
    "callers convert types explicitly. Duplicate keys, anchors, aliases, tags and "
    "anything else outside that subset are refused with the line number. The same "
    "reader serves the api-contracts spec checks. No third-party YAML library is "
    "added."
)


def decision_items():
    with open(ADR, encoding="utf-8") as handle:
        text = handle.read()
    section = text.split("\n## Decision\n", 1)[1].split("\n## ", 1)[0]
    items = re.split(r"\n(?=\d+\. )", section.strip("\n"))
    return {int(item.split(".", 1)[0]): " ".join(item.split()) for item in items}


def imported_modules(path):
    with open(path, encoding="utf-8") as handle:
        tree = ast.parse(handle.read(), path)
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0:
            names.add(node.module.split(".")[0])
    return names


class ItemFourTextTest(YamlCase):
    def test_the_decision_lists_items_one_to_five_in_order(self):
        self.assertEqual(list(decision_items()), [1, 2, 3, 4, 5])

    def test_item_four_reads_exactly_as_ruled(self):
        self.assertEqual(decision_items()[4], ITEM_4)

    def test_the_module_docstring_cites_the_decision_and_it_exists(self):
        self.assertIn("decisions/ADR-016-model-registry-codegen.md", yaml_subset.__doc__)
        self.assertTrue(os.path.isfile(ADR))


class ItemFourClaimsTest(YamlCase):
    def test_one_small_standard_library_only_reader_in_tools(self):
        # The reader is two files in tools/: yaml_subset.py and the private helper beside it.
        reader = os.path.join(TOOLS, "yaml_subset.py")
        helper = os.path.join(TOOLS, "_yaml_subset_scan.py")
        stdlib = set(sys.stdlib_module_names)
        helper_module = "_yaml_subset_scan"
        max_raw_lines = 500
        for path in (reader, helper):
            self.assertTrue(os.path.isfile(path), f"{path} must exist (rule: the reader is these two files in tools/)")
        outside = imported_modules(helper) - stdlib
        self.assertEqual(outside, set(), "_yaml_subset_scan.py imports something that is not in the standard "
                                         "library (rule: the helper imports standard-library modules only)")
        outside = imported_modules(reader) - stdlib - {helper_module}
        self.assertEqual(outside, set(), "yaml_subset.py imports something that is not in the standard library "
                                         "and is not _yaml_subset_scan (rule: no third-party module and no third "
                                         "file in tools/)")
        for name in sorted(os.listdir(TOOLS)):
            other = os.path.join(TOOLS, name)
            if name.endswith(".py") and other not in (reader, helper):
                with open(other, encoding="utf-8") as handle:
                    tree = ast.parse(handle.read(), other)
                for node in ast.walk(tree):
                    # `import helper`, `import pkg.helper`, `from helper import x`, `from pkg.helper import x`,
                    # `from pkg import helper`, `from . import helper`
                    if isinstance(node, ast.Import):
                        parts = [part for alias in node.names for part in alias.name.split(".")]
                    elif isinstance(node, ast.ImportFrom):
                        parts = (node.module or "").split(".") + [alias.name for alias in node.names]
                    else:
                        continue
                    self.assertNotIn(helper_module, parts, f"tools/{name} line {node.lineno} imports "
                                     "_yaml_subset_scan (rule: only yaml_subset.py imports the helper)")
        for path in (reader, helper):
            with open(path, "rb") as handle:
                raw_lines = len(handle.read().split(b"\n")) - 1  # one per line feed byte
            self.assertLessEqual(raw_lines, max_raw_lines, f"{os.path.basename(path)} has {raw_lines} raw lines "
                                                           f"(rule: at most {max_raw_lines} per file)")

    def test_no_third_party_yaml_library_is_imported_anywhere_in_tools_or_tests(self):
        scanned = 0
        for top in ("tools", "tests"):
            for folder, _, files in os.walk(os.path.join(ROOT, top)):
                for name in files:
                    if name.endswith(".py"):
                        scanned += 1
                        found = imported_modules(os.path.join(folder, name)) & YAML_LIBRARIES
                        self.assertEqual(found, set(), f"{os.path.join(folder, name)} imports a YAML library")
        self.assertGreater(scanned, 10, "the scan should have seen the tools and tests trees")

    def test_it_supports_block_and_flow_mappings_and_sequences_a_flow_nested_in_a_flow_scalars_and_comments(self):
        text = (
            "# a whole-line comment\n"
            "block_map:\n"
            "  plain: text\n"
            "  quoted: \"q\\n\"\n"
            "  single: 'it''s'\n"
            "block_seq:\n"
            "  - a\n"
            "  - b: c\n"
            "flow_map: {k: v, n: {m: [x, y]}}\n"
            "flow_seq: [1, [2, 3], {k: v}]\n"
            "text: |\n"
            "  literal\n"
            "  block\n"
        )
        self.assertEqual(loads(text), {
            "block_map": {"plain": "text", "quoted": "q\n", "single": "it's"},
            "block_seq": ["a", {"b": "c"}],
            "flow_map": {"k": "v", "n": {"m": ["x", "y"]}},
            "flow_seq": ["1", ["2", "3"], {"k": "v"}],
            "text": "literal\nblock\n",
        })

    def test_every_plain_scalar_is_a_string_with_no_implicit_numbers_booleans_or_dates(self):
        got = loads("n: 12\nf: 0.5\nb: true\nc: No\nd: 2026-09-30\nt: 12:30:00\nh: 0x10\ne: 1e3\nz: 007\n")
        self.assertEqual(got, {"n": "12", "f": "0.5", "b": "true", "c": "No", "d": "2026-09-30", "t": "12:30:00",
                               "h": "0x10", "e": "1e3", "z": "007"})
        self.assertTrue(all(type(value) is str for value in got.values()))

    def test_the_only_null_is_an_empty_value(self):
        got = loads("empty:\nword: null\ntilde: ~\nquoted: \"\"\nflow: {k: }\nlist: [null, ~]\n")
        self.assertEqual(got, {"empty": None, "word": "null", "tilde": "~", "quoted": "", "flow": {"k": None},
                               "list": ["null", "~"]})
        nones = [key for key, value in got.items() if value is None]
        self.assertEqual(nones, ["empty"])

    def test_duplicate_keys_anchors_aliases_and_tags_are_refused_with_the_line_number(self):
        self.refuses_doc("a: 1\nb: 2\na: 3\n", 3, "duplicate key")
        self.refuses_doc("a: 1\nb: &x 2\n", 2, "anchors, aliases and tags")
        self.refuses_doc("a: 1\nb: *x\n", 2, "anchors, aliases and tags")
        self.refuses_doc("a: 1\nb: !!str 2\n", 2, "anchors, aliases and tags")

    def test_anything_else_outside_the_subset_is_refused_with_the_line_number(self):
        self.refuses_doc("a: 1\nb: 2 # trailing\n", 2, "trailing comments")
        self.refuses_doc("a: 1\nb: |+\n  x\n", 2, "block scalar header")
        self.refuses_doc("a: 1\nb: one\n  two\n", 3, "cannot continue")

    def test_it_reads_a_spec_shaped_document_and_hands_back_the_strings_such_checks_need(self):
        spec = 'info:\n  version: "1.0.0"\npaths:\n  /health:\n    get:\n      responses:\n        "200":\n          description: Up.\n'
        got = loads(spec)
        self.assertIs(type(got["info"]["version"]), str)
        codes = got["paths"]["/health"]["get"]["responses"]
        self.assertEqual([int(code) for code in codes], [200])


if __name__ == "__main__":
    unittest.main()
