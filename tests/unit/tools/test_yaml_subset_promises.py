"""This file pins the reader's documented promises so a reviewer can re-run them.

Each test holds one sentence of the module docstring of tools/yaml_subset.py
to the reader: the types it reads, the shapes it accepts, the refusals it
makes, and the line numbers it reports. A green run here means the reader
does what its documentation says.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import unittest

from yaml_support import YamlCase, YamlSubsetError, emit, load, loads, write_file


class TypesTest(YamlCase):
    def test_every_plain_scalar_is_a_string(self):
        got = loads("a: true\nb: 12\nc: 0.0\nd: null\ne: ~\nf: no\n")
        self.assertEqual(got, {"a": "true", "b": "12", "c": "0.0", "d": "null", "e": "~", "f": "no"})
        self.assertTrue(all(type(value) is str for value in got.values()))

    def test_a_quoted_scalar_is_a_string(self):
        got = loads('a: "12"\nb: \'true\'\n')
        self.assertEqual(got, {"a": "12", "b": "true"})
        self.assertTrue(all(type(value) is str for value in got.values()))

    def test_the_only_none_is_an_empty_value(self):
        got = loads("a:\nb: \nc: {k: }\n")
        self.assertEqual(got, {"a": None, "b": None, "c": {"k": None}})
        nones = [key for key, value in got.items() if value is None]
        self.assertEqual(nones, ["a", "b"])

    def test_output_purity_recursive(self):
        text = (
            "a: 1\n"
            "b: true\n"
            "c: null\n"
            "d: ~\n"
            "e: no\n"
            "f: |\n"
            "  block\n"
            "  text\n"
            "g: >\n"
            "  folded\n"
            "  text\n"
            "h: |-\n"
            "  strip\n"
            "i: >-\n"
            "  strip\n"
            "j: {k: v, n: [x, y]}\n"
            "k: [a, b, {c: d}]\n"
            "l:\n"
            "  - item\n"
            "  - key: value\n"
        )
        result = loads(text)
        self.assertIs(type(result), dict)

        def check(node):
            if node is None:
                return
            if isinstance(node, str):
                return
            if isinstance(node, list):
                for item in node:
                    check(item)
                return
            if isinstance(node, dict):
                for key, value in node.items():
                    self.assertIs(type(key), str)
                    check(value)
                return
            self.fail(f"unexpected type {type(node).__name__} in output")

        check(result)


class TrimmingTest(YamlCase):
    def test_only_ascii_space_at_either_end_is_trimmed(self):
        got = loads("a: x  \nb:  x  \nc: x y\n")
        self.assertEqual(got, {"a": "x", "b": "x", "c": "x y"})


class NonAsciiWhitespaceTest(YamlCase):
    def test_non_ascii_whitespace_at_an_edge_is_text(self):
        for char in ("\u200b", "\u2060", "\u180e", "\ufeff"):
            with self.subTest(char=char):
                got = loads(f"a: {char}x\n")
                self.assertEqual(got, {"a": f"{char}x"})

    def test_a_real_isspace_char_at_an_edge_is_refused(self):
        self.refuses_doc("a: \u00a0x\n", 1, "a plain value cannot start or end with the whitespace character U+00A0")


class BomTest(YamlCase):
    def test_a_leading_bom_is_refused_on_line_one(self):
        self.refuses_doc("\ufeffa: x\n", 1, "a byte-order mark is not supported")

    def test_bom_inside_a_value_is_text(self):
        got = loads("a: x\ufeffy\n")
        self.assertEqual(got, {"a": "x\ufeffy"})

    def test_bom_inside_a_quoted_scalar_is_text(self):
        got = loads('a: "x\ufeffy"\n')
        self.assertEqual(got, {"a": "x\ufeffy"})

    def test_bom_inside_a_block_scalar_is_text(self):
        got = loads("a: |\n  x\ufeffy\n")
        self.assertEqual(got, {"a": "x\ufeffy\n"})


class NestingTest(YamlCase):
    def test_flow_nesting_to_any_depth(self):
        for depth in (1, 10, 100, 5000):
            with self.subTest(depth=depth):
                text = "a: " + "[" * depth + "]" * depth + "\n"
                result = loads(text)
                node = result["a"]
                actual = 0
                while isinstance(node, list):
                    actual += 1
                    node = node[0] if node else None
                self.assertEqual(actual, depth)

    def test_block_nesting_limit_is_the_named_refusal(self):
        depth = 600
        text = ""
        for i in range(depth):
            text += "  " * i + f"k{i}:\n"
        text += "  " * depth + "leaf: x\n"
        with self.assertRaises(YamlSubsetError) as caught:
            loads(text)
        self.assertIn("the document nests too deeply for this reader", caught.exception.reason)


class CrlfLineNumbersTest(YamlCase):
    def test_control_character_on_crlf(self):
        self.refuses_doc("a: x\nb: \x01y\n".replace("\n", "\r\n"), 2, "the control character U+0001")

    def test_duplicate_key_on_crlf(self):
        self.refuses_doc("a: 1\nb: 2\na: 3\n".replace("\n", "\r\n"), 3, "duplicate key")

    def test_tab_in_content_line_on_crlf(self):
        self.refuses_doc("a: x\nb:\ty\n".replace("\n", "\r\n"), 2, "a tab is not supported in a content line")

    def test_bad_escape_on_crlf(self):
        self.refuses_doc('a: "x\\q"\n'.replace("\n", "\r\n"), 1, "unsupported escape")

    def test_unterminated_flow_on_crlf(self):
        self.refuses_doc("a: [x\n".replace("\n", "\r\n"), 1, "unterminated flow sequence")


class DuplicateKeysTest(YamlCase):
    def test_duplicate_key_in_block_mapping(self):
        self.refuses_doc("a: 1\na: 2\n", 2, "duplicate key")

    def test_duplicate_key_in_flow_mapping(self):
        self.refuses_doc("a: {k: 1, k: 2}\n", 1, "duplicate key")

    def test_same_key_in_different_scopes_is_fine(self):
        got = loads("a:\n  k: 1\nb:\n  k: 2\n")
        self.assertEqual(got, {"a": {"k": "1"}, "b": {"k": "2"}})

    def test_same_key_in_sequence_items_is_fine(self):
        got = loads("a:\n  - k: 1\n  - k: 2\n")
        self.assertEqual(got, {"a": [{"k": "1"}, {"k": "2"}]})


class TabsTest(YamlCase):
    def test_tab_in_content_line_is_refused(self):
        self.refuses_doc("a: x\nb:\ty\n", 2, "a tab is not supported in a content line")

    def test_tab_on_block_scalar_line_is_refused(self):
        self.refuses_doc("a: |\n  x\n  \ty\n", 3, "a tab is not supported in a content line")

    def test_tab_on_trailing_blank_line_of_block_scalar_is_refused(self):
        self.refuses_doc("a: |\n  x\n  \t\n", 3, "a tab is not supported in a block scalar")

    def test_tab_only_line_between_entries_is_blank(self):
        got = loads("a: 1\n\t\nb: 2\n")
        self.assertEqual(got, {"a": "1", "b": "2"})

    def test_tab_before_whole_line_comment_is_read(self):
        got = loads("a: 1\n\t# comment\nb: 2\n")
        self.assertEqual(got, {"a": "1", "b": "2"})


class TrailingCommentsTest(YamlCase):
    def test_hash_after_plain_value_is_refused(self):
        self.refuses_doc("a: x # comment\n", 1, "trailing comments")

    def test_hash_after_quoted_value_is_refused(self):
        self.refuses_doc('a: "x" # comment\n', 1, "trailing comments")

    def test_hash_after_flow_collection_is_refused(self):
        self.refuses_doc("a: [x] # comment\n", 1, "trailing comments")

    def test_hash_inside_quoted_scalar_is_text(self):
        got = loads('a: "x#y"\n')
        self.assertEqual(got, {"a": "x#y"})

    def test_whole_line_comment_is_fine(self):
        got = loads("# comment\na: x\n")
        self.assertEqual(got, {"a": "x"})


class ColonsInValuesTest(YamlCase):
    def test_colon_space_inside_value_is_refused(self):
        self.refuses_doc("a: b: c\n", 1, "a plain value may not contain ': '")

    def test_colon_at_end_of_value_is_refused(self):
        self.refuses_doc("a: b:\n", 1, "a plain value cannot end with ':'")

    def test_colon_without_space_in_flow_scalar_is_text(self):
        got = loads("a: [a:b]\n")
        self.assertEqual(got, {"a": ["a:b"]})

    def test_colon_without_space_in_flow_mapping_value_is_text(self):
        got = loads("a: {k: a:b}\n")
        self.assertEqual(got, {"a": {"k": "a:b"}})


class AnchorsAliasesTagsTest(YamlCase):
    def test_anchor_on_key_is_refused(self):
        self.refuses_doc("a: &x 1\n", 1, "anchors, aliases and tags")

    def test_alias_on_key_is_refused(self):
        self.refuses_doc("a: *x 1\n", 1, "anchors, aliases and tags")

    def test_tag_on_key_is_refused(self):
        self.refuses_doc("a: !!str 1\n", 1, "anchors, aliases and tags")

    def test_anchor_as_plain_value_is_refused(self):
        self.refuses_doc("a: &x\n", 1, "anchors, aliases and tags")

    def test_alias_as_plain_value_is_refused(self):
        self.refuses_doc("a: *x\n", 1, "anchors, aliases and tags")

    def test_tag_as_plain_value_is_refused(self):
        self.refuses_doc("a: !!str\n", 1, "anchors, aliases and tags")

    def test_question_mark_on_key_is_refused(self):
        self.refuses_doc("?x: y\n", 1, "anchors, aliases, tags and '?'")

    def test_question_mark_as_plain_value_is_refused(self):
        self.refuses_doc("a: ?\n", 1, "a plain value cannot be '?'")

    def test_question_mark_space_as_plain_value_is_refused(self):
        self.refuses_doc("a: ? x\n", 1, "a plain value cannot be '?' or start with '? '")

    def test_merge_key_is_refused(self):
        self.refuses_doc("<<: x\n", 1, "the merge key '<<'")

    def test_quoted_merge_key_is_read(self):
        got = loads("'<<': x\n")
        self.assertEqual(got, {"<<": "x"})


class MultiDocumentTest(YamlCase):
    def test_document_starting_with_marker_is_refused(self):
        self.refuses_doc("--- x\n", 1, "multi-document YAML")

    def test_document_starting_with_marker_no_space_is_refused(self):
        self.refuses_doc("---x: v\n", 1, "multi-document YAML")

    def test_marker_alone_on_line_is_refused(self):
        self.refuses_doc("---\na: x\n", 1, "document markers")

    def test_document_that_is_not_a_mapping_is_refused(self):
        self.refuses_doc("- a\n- b\n", 1, "the document is not a mapping")


class OddIndentsTest(YamlCase):
    def test_indentation_matching_no_enclosing_level_is_refused(self):
        self.refuses_doc("a:\n  b:\n    c: 1\n   d: 2\n", 4, "the indentation does not match any enclosing level")

    def test_value_continuing_on_next_line_is_refused(self):
        self.refuses_doc("a: x\n  y\n", 2, "a value cannot continue on the next line")

    def test_line_that_is_not_key_value_is_refused(self):
        self.refuses_doc("a: 1\nb\n", 2, "expected 'key: value'")

    def test_empty_plain_key_is_refused(self):
        self.refuses_doc(": x\n", 1, "a mapping key is empty")

    def test_quoted_empty_key_is_read(self):
        got = loads('"": x\n')
        self.assertEqual(got, {"": "x"})


class EmptyValuesTest(YamlCase):
    def test_empty_value_reads_as_none(self):
        got = loads("a:\nb: \nc: {k: }\n")
        self.assertEqual(got, {"a": None, "b": None, "c": {"k": None}})


class BlockScalarsTest(YamlCase):
    def test_literal_keeps_line_breaks(self):
        got = loads("a: |\n  x\n  y\n")
        self.assertEqual(got, {"a": "x\ny\n"})

    def test_folded_joins_lines(self):
        got = loads("a: >\n  x\n  y\n")
        self.assertEqual(got, {"a": "x y\n"})

    def test_literal_strip_drops_last_break(self):
        got = loads("a: |-\n  x\n  y\n")
        self.assertEqual(got, {"a": "x\ny"})

    def test_folded_strip_drops_last_break(self):
        got = loads("a: >-\n  x\n  y\n")
        self.assertEqual(got, {"a": "x y"})

    def test_last_break_kept_when_line_end_follows(self):
        got = loads("a: |\n  x\nb: y\n")
        self.assertEqual(got, {"a": "x\n", "b": "y"})

    def test_header_other_than_four_is_refused(self):
        self.refuses_doc("a: |+\n  x\n", 1, "is not a supported block scalar header")

    def test_block_scalar_with_no_content_is_refused(self):
        self.refuses_doc("a: |\n", 1, "a block scalar has no content")


class FlowDashFormsTest(YamlCase):
    def test_lone_dash_in_flow_is_refused(self):
        self.refuses_doc("a: [-]\n", 1, "a plain value cannot start with '-'")

    def test_dash_space_in_flow_is_refused(self):
        self.refuses_doc("a: [- x]\n", 1, "a plain value cannot start with '-'")

    def test_dash_space_after_entry_in_flow_is_refused(self):
        self.refuses_doc("a: [a, - x]\n", 1, "a plain value cannot start with '-'")

    def test_lone_dash_as_flow_mapping_value_is_refused(self):
        self.refuses_doc("a: {k: -}\n", 1, "a plain value cannot start with '-'")

    def test_dash_space_as_flow_mapping_value_is_refused(self):
        self.refuses_doc("a: {k: - x}\n", 1, "a plain value cannot start with '-'")


class StrictUtf8Test(YamlCase):
    def test_invalid_utf8_names_the_right_line(self):
        data = b"a: x\nb: \xffy\n"
        path = write_file(self, data)
        with self.assertRaises(YamlSubsetError) as caught:
            load(path)
        self.assertEqual(caught.exception.line, 2)
        self.assertIn("not valid UTF-8", caught.exception.reason)


class KeyNeverLostTest(YamlCase):
    def test_every_key_written_appears_in_result(self):
        document = {
            "alpha": "1",
            "beta": {"gamma": "2", "delta": ["x", "y"]},
            "epsilon": {"zeta": {"eta": "3"}},
            "theta": [{"iota": "4"}, {"kappa": "5"}],
            "lambda": None,
            "mu": {"nu": None, "xi": "6"},
        }
        text = emit(document)
        result = loads(text)

        def collect_keys(node, keys):
            if isinstance(node, dict):
                for key, value in node.items():
                    keys.append(key)
                    collect_keys(value, keys)
            elif isinstance(node, list):
                for item in node:
                    collect_keys(item, keys)

        written_keys = []
        collect_keys(document, written_keys)
        read_keys = []
        collect_keys(result, read_keys)
        for key in written_keys:
            self.assertIn(key, read_keys, f"key '{key}' was lost")


if __name__ == "__main__":
    unittest.main()
