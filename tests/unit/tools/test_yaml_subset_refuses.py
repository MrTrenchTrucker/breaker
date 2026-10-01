"""What tools/yaml_subset.py refuses in the document as a whole and in ordinary constructs.

Covers the whole document and its content lines, scalars, quoting, flow collections, keys, block scalars, continuation
lines, input hygiene, nesting, and the copying and pickling of a refusal. Every refusal test asserts the reason and the
line. A refusal for a neighbour's reason, or on the wrong line, is a failure: a reader that refuses the right thing
for the wrong reason sends a developer to the wrong line of the wrong file. Each edited document is built from an
accepted one (the control), with the edit checked to have landed.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import copy
import pickle
import unittest

from yaml_support import YamlCase, YamlSubsetError, load, loads, write_file


class WholeDocumentTest(YamlCase):
    def test_an_empty_document(self):
        for text in ("", "\n\n", "   \n", "# only a comment\n\n# and another\n"):
            with self.subTest(text=text):
                self.refuses_doc(text, 1, "the document is empty")

    def test_a_sequence_at_the_root_names_its_line(self):
        self.refuses_doc("- a\n- b\n", 1, "not a mapping")
        self.refuses_doc("# c\n\n- a\n", 3, "not a mapping")

    def test_a_scalar_at_the_root_is_not_key_value(self):
        self.refuses_doc("hello\n", 1, "expected 'key: value', was 'hello'")

    def test_a_flow_mapping_at_the_root_is_refused(self):
        self.refuses_doc("{a: 1}\n", 1, "a mapping key cannot start with '{'")

    def test_a_document_marker_line(self):
        good = "a: 1\nb: 2\n"
        self.refuses(good, "b: 2\n", "---\nb: 2\n", 2, "document markers are not supported")
        self.refuses(good, "b: 2\n", "...\nb: 2\n", 2, "document markers are not supported")
        self.refuses_doc("---\na: 1\n", 1, "document markers are not supported")
        self.refuses_doc("a: 1\n  ---  \n", 2, "document markers are not supported")

    def test_a_document_that_begins_with_dashes(self):
        self.refuses_doc("--- a\nb: 1\n", 1, "multi-document YAML is not supported")

    def test_text_left_over_at_a_shallower_indent_than_the_first_line(self):
        self.refuses("  a: 1\n  b: 2\n", "  b: 2\n", "b: 2\n", 2, "does not match any enclosing level")


class ContentLineTest(YamlCase):
    def test_a_tab_as_indentation(self):
        self.refuses("a:\n  b: 1\n", "  b: 1", "\tb: 1", 2, "a tab is not supported in a content line")

    def test_a_tab_inside_a_content_line(self):
        self.refuses("a: 1\nb: 2\n", "a: 1", "a:\t1", 1, "a tab is not supported in a content line")

    def test_a_tab_inside_a_block_scalar_line(self):
        self.refuses("a: |\n  one\n  two\n", "  two", "  t\two", 3, "a tab is not supported in a content line")

    def test_a_tab_on_a_hash_line_inside_a_block_scalar(self):
        self.refuses("a: |\n  one\n  # two\nb: 1\n", "  # two", "  # t\two", 3, "a tab is not supported in a block scalar")

    def test_a_tab_line_number_counts_comments_and_blank_lines(self):
        self.refuses("# c\n\na: 1\n# d\nb: 2\n", "b: 2", "b:\t2", 5, "a tab is not supported in a content line")


class ScalarRefusalTest(YamlCase):
    def test_an_anchor(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: &x 2", 2, "anchors, aliases and tags are not supported")

    def test_an_alias(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: *x", 2, "anchors, aliases and tags are not supported")

    def test_a_tag(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: !!str 2", 2, "anchors, aliases and tags are not supported")

    def test_an_anchor_on_a_sequence_item_and_in_flow(self):
        self.refuses("l:\n  - a\n  - b\n", "  - b", "  - &x b", 3, "anchors, aliases and tags are not supported")
        self.refuses("l: [a, b]\n", "[a, b]", "[a, *b]", 1, "anchors, aliases and tags are not supported")

    def test_a_trailing_comment_after_a_plain_value(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: 2 # two", 2, "trailing comments are not supported")

    def test_a_hash_in_a_plain_value(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: http://x/y#frag", 2, "trailing comments are not supported")

    def test_a_trailing_comment_after_a_quoted_value_flow_and_item(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", 'b: "two" # two', 2, "trailing comments are not supported")
        self.refuses("l: [a, b]\n", "[a, b]", "[a, b] # c", 1, "trailing comments are not supported")
        self.refuses("l: [a, b]\n", "[a, b]", "[a, b # c]", 1, "trailing comments are not supported")
        self.refuses("l:\n  - a\n  - b\n", "  - b", "  - b # c", 3, "trailing comments are not supported")

    def test_a_colon_and_space_in_a_plain_value(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: x: y", 2, "a plain value may not contain ': '")

    def test_a_colon_and_space_in_a_flow_sequence_item(self):
        self.refuses("l: [a, b]\n", "[a, b]", "[a, b: c]", 1, "a plain value may not contain ': '")

    def test_a_colon_and_space_in_a_flow_mapping_value(self):
        self.refuses("l: {a: b}\n", "{a: b}", "{a: b: c}", 1, "a plain value may not contain ': '")

    def test_a_block_sequence_item_written_inline(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "b: - x", 2, "a plain value cannot start with '-' followed by a space")

    def test_a_sequence_item_that_starts_a_sequence(self):
        self.refuses("a:\n  - x\n  - y\n", "  - y", "  - - y", 3, "a sequence item cannot start with another sequence item")

    def test_a_block_scalar_as_a_sequence_item(self):
        self.refuses("a:\n  - x\n  - y\n", "  - y", "  - |\n    y", 3, "a block scalar cannot be a sequence item")
        self.refuses("a:\n  - x\n  - y\n", "  - y", "  - >-\n    y", 3, "a block scalar cannot be a sequence item")

    def test_an_empty_sequence_item(self):
        self.refuses("a:\n  - x\n  - y\n", "  - y", "  -", 3, "an empty sequence item is not supported")


class QuotingRefusalTest(YamlCase):
    def test_an_unterminated_double_quoted_scalar(self):
        self.refuses("a: 1\nb: \"x\"\n", 'b: "x"', 'b: "x', 2, "unterminated quoted scalar")

    def test_an_unterminated_single_quoted_scalar(self):
        self.refuses("a: 1\nb: 'x'\n", "b: 'x'", "b: 'x", 2, "unterminated quoted scalar")

    def test_a_quoted_scalar_that_runs_onto_the_next_line(self):
        self.refuses_doc('a: "one\n  two"\n', 1, "unterminated quoted scalar")

    def test_an_unterminated_quoted_key_and_flow_scalar(self):
        self.refuses("a: 1\n\"b\": 2\n", '"b": 2', '"b: 2', 2, "unterminated quoted scalar")
        self.refuses("l: [\"a\", b]\n", '["a", b]', '["a, b]', 1, "unterminated quoted scalar")

    def test_a_backslash_at_the_very_end_of_a_quoted_scalar(self):
        self.refuses_doc('a: "x\\"\n', 1, "unterminated quoted scalar")

    def test_an_unsupported_escape(self):
        for escape in ("\\q", "\\u0041", "\\x41", "\\'", "\\/", "\\0", "\\ "):
            with self.subTest(escape=escape):
                err = self.refuses('a: "x"\n', '"x"', f'"x{escape}y"', 1, "unsupported escape")
                self.assertIn(f"'{escape[:2]}'", err.reason)

    def test_an_unescaped_double_quote_inside_a_double_quoted_scalar(self):
        self.refuses("a: 1\nb: \"x\"\n", 'b: "x"', 'b: "x" "y"', 2, "unexpected text after the closing quote")
        self.refuses("a: 1\nb: \"x\"\n", 'b: "x"', 'b: "x"y', 2, "unexpected text after the closing quote")

    def test_an_unescaped_single_quote_inside_a_single_quoted_scalar(self):
        self.refuses("a: 1\nb: 'x'\n", "b: 'x'", "b: 'x' 'y'", 2, "unexpected text after the closing quote")

    def test_an_unescaped_inner_quote_in_a_flow_scalar_and_a_flow_key(self):
        self.refuses("l: [\"a\", b]\n", '["a", b]', '["a" "b", c]', 1, "unexpected text after the closing quote")
        self.refuses("l: {\"a\": b}\n", '{"a": b}', '{"a" "b": c}', 1, "unexpected text after the closing quote")

    def test_an_unescaped_inner_quote_in_a_quoted_key(self):
        self.refuses("a: 1\n\"b\": 2\n", '"b": 2', '"b" "c": 2', 2, "expected 'key: value'")

    def test_an_escaped_quote_inside_a_flow_collection(self):
        self.refuses("l: [\"a\", b]\n", '["a", b]', '["a\\"b", c]', 1, "escaped quote inside a quoted scalar in a flow collection")
        self.refuses("l: {k: \"a\"}\n", '{k: "a"}', '{k: "a\\"b"}', 1, "escaped quote inside a quoted scalar in a flow collection")
        self.refuses("l: {\"k\": a}\n", '{"k": a}', '{"k\\"j": a}', 1, "escaped quote inside a quoted scalar in a flow collection")

    def test_an_escaped_quote_outside_a_flow_collection_is_still_read(self):
        self.assertEqual(loads('a: "x\\"y"\n'), {"a": 'x"y'})


class FlowRefusalTest(YamlCase):
    def test_an_unterminated_flow_sequence(self):
        self.refuses("a: 1\nl: [x, y]\n", "[x, y]", "[x, y", 2, "unterminated flow sequence")

    def test_an_unterminated_flow_mapping(self):
        self.refuses("a: 1\nl: {k: v}\n", "{k: v}", "{k: v", 2, "unterminated flow mapping")

    def test_a_flow_collection_that_runs_onto_the_next_line(self):
        self.refuses_doc("l: [x,\n  y]\n", 1, "unterminated flow sequence")

    def test_an_unterminated_inner_flow_collection(self):
        self.refuses("l: [[x], y]\n", "[[x], y]", "[[x], y", 1, "unterminated flow sequence")
        self.refuses("l: {k: [x]}\n", "{k: [x]}", "{k: [x}", 1, "unbalanced brackets")

    def test_unbalanced_brackets(self):
        self.refuses("l: [x, y]\n", "[x, y]", "[x, y}", 1, "unbalanced brackets")
        self.refuses("l: [x, y]\n", "[x, y]", "[x, y]]", 1, "unbalanced brackets")
        self.refuses("l: {k: v}\n", "{k: v}", "{k: v]", 1, "unbalanced brackets")
        self.refuses("l: {k: v}\n", "{k: v}", "{k: ]", 1, "unbalanced brackets")
        self.refuses("l: [x, y]\n", "[x, y]", "[x, }", 1, "unbalanced brackets")
        self.refuses("l: {k: v}\n", "{k: v}", "{k: v}}", 1, "unbalanced brackets")

    def test_text_after_a_flow_collection(self):
        self.refuses("l: [x, y]\n", "[x, y]", "[x, y] z", 1, "unexpected text after the flow collection")

    def test_a_missing_separator_between_flow_entries(self):
        self.refuses("l: [x, y]\n", "[x, y]", "[[x] [y]]", 1, "expected ',' or ']' after a flow entry")

    def test_a_flow_mapping_entry_that_is_not_key_value(self):
        self.refuses("l: {k: v}\n", "{k: v}", "{k, j: v}", 1, "is not a key/value pair")
        self.refuses("l: {k: v}\n", "{k: v}", '{"k", j: v}', 1, "is not a key/value pair")

    def test_an_unquoted_comma_in_a_flow_mapping_value_is_refused_not_truncated(self):
        err = self.refuses("l: { d: Rows stored }\n", "Rows stored", "Rows stored, not stored again.", 1, "is not a key/value pair")
        self.assertIn("not stored again.", err.reason)

    def test_a_flow_bracket_inside_a_plain_flow_scalar(self):
        self.refuses("l: [x, y]\n", "[x, y]", "[x, y[1]]", 1, "is not allowed inside a plain scalar in a flow collection")
        self.refuses("l: {k: v}\n", "{k: v}", "{k: v{1}}", 1, "is not allowed inside a plain scalar in a flow collection")

    def test_a_flow_bracket_inside_a_plain_flow_key(self):
        self.refuses("l: {k: v}\n", "{k: v}", "{k[1]: v}", 1, "is not allowed in a plain key in a flow mapping")

    def test_empty_entries_in_a_flow_sequence(self):
        for bad in ("[a,,b]", "[,a]", "[a,]", "[a, , b]", "[,]"):
            with self.subTest(flow=bad):
                self.refuses("l: [a, b]\n", "[a, b]", bad, 1, "an empty entry in a flow collection")

    def test_empty_entries_in_a_flow_mapping(self):
        for bad in ("{k: v,}", "{,k: v}", "{k: v,,j: w}", "{k: v, }"):
            with self.subTest(flow=bad):
                self.refuses("l: {k: v}\n", "{k: v}", bad, 1, "an empty entry in a flow collection")

    def test_empty_entries_in_a_nested_flow_collection(self):
        self.refuses("l: [[a], b]\n", "[[a], b]", "[[a,], b]", 1, "an empty entry in a flow collection")

    def test_a_flow_line_number_counts_comments_and_blank_lines(self):
        self.refuses("# c\n\nl: [a, b]\n", "[a, b]", "[a,,b]", 3, "an empty entry in a flow collection")

    def test_a_duplicate_key_in_a_flow_mapping(self):
        self.refuses("l: {k: 1, j: 2}\n", "j: 2", "k: 2", 1, "duplicate key 'k' in a flow mapping")
        self.refuses("l: {a: {k: 1, j: 2}}\n", "j: 2", "k: 2", 1, "duplicate key 'k' in a flow mapping")


class KeyRefusalTest(YamlCase):
    def test_an_empty_key(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", ": 2", 2, "a mapping key is empty")
        self.refuses("l: {a: 1}\n", "{a: 1}", "{: 1}", 1, "a mapping key is empty")

    def test_a_line_that_is_not_key_value(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "just text", 2, "expected 'key: value', was 'just text'")
        self.refuses("a: 1\nb: 2\n", "b: 2", "b:2", 2, "expected 'key: value', was 'b:2'")

    def test_a_sequence_item_where_a_key_is_expected(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "- b", 2, "a sequence item cannot sit where a mapping key is expected")
        self.refuses("a:\n  b: 1\n  c: 2\n", "  c: 2", "  - c", 3, "a sequence item cannot sit where a mapping key is expected")

    def test_an_anchor_alias_tag_or_question_mark_on_a_key(self):
        for key in ("&a k", "*a k", "!!str k", "? k"):
            with self.subTest(key=key):
                err = self.refuses("a: 1\nk: 2\n", "k: 2", f"{key}: 2", 2, "are not supported on a key")
                self.assertIn(f"'{key}'", err.reason)

    def test_an_anchor_on_a_key_in_a_sequence_item_and_in_flow(self):
        self.refuses("l:\n  - k: 1\n    j: 2\n", "    j: 2", "    &a j: 2", 3, "are not supported on a key")
        self.refuses("l:\n  - k: 1\n", "  - k: 1", "  - &a k: 1", 2, "are not supported on a key")
        self.refuses("l: {k: 1}\n", "{k: 1}", "{&a k: 1}", 1, "are not supported on a key")

    def test_a_flow_collection_as_a_key(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", "[b]: 2", 2, "a mapping key cannot start with '['")

    def test_a_duplicate_key(self):
        self.refuses("a: 1\nb: 2\nc: 3\n", "c: 3", "a: 3", 3, "duplicate key 'a' (first on line 1)")

    def test_a_duplicate_key_names_the_first_line_through_comments(self):
        self.refuses("# c\na: 1\n\n# d\nb: 2\nc: 3\n", "c: 3", "a: 3", 6, "duplicate key 'a' (first on line 2)")

    def test_a_duplicate_key_in_a_nested_mapping(self):
        self.refuses("a:\n  b: 1\n  c: 2\n", "  c: 2", "  b: 2", 3, "duplicate key 'b' (first on line 2)")

    def test_a_duplicate_key_in_a_sequence_item_mapping(self):
        self.refuses("l:\n  - id: a\n    n: 1\n", "    n: 1", "    id: 1", 3, "duplicate key 'id' (first on line 2)")

    def test_a_duplicate_key_spelled_quoted_or_numeric(self):
        self.refuses("a: 1\nb: 2\n", "b: 2", '"a": 2', 2, "duplicate key 'a'")
        self.refuses("1: x\nb: 2\n", "b: 2", '"1": 2', 2, "duplicate key '1'")

    def test_a_duplicate_key_with_an_empty_first_value(self):
        self.refuses("a:\nb: 2\n", "b: 2", "a: 2", 2, "duplicate key 'a' (first on line 1)")


class BlockScalarRefusalTest(YamlCase):
    def test_a_block_scalar_with_no_content(self):
        self.refuses("a: |\n  x\nb: 1\n", "  x\n", "", 1, "a block scalar has no content")
        self.refuses_doc("a: |\n", 1, "a block scalar has no content")
        self.refuses_doc("a: >-\n\n\nb: 1\n", 1, "a block scalar has no content")

    def test_a_block_scalar_whose_only_content_is_at_the_keys_indent(self):
        self.refuses("a:\n  b: |\n    x\n", "    x", "  x", 2, "a block scalar has no content")

    def test_unsupported_block_headers_are_refused_by_name(self):
        for header in ("|+", ">+", "|2", ">2", "|-1", "|1-", ">-1", "|+2", "| # c", ">=1.0", "|x"):
            with self.subTest(header=header):
                err = self.refuses("a: |\n  x\nb: 1\n", "a: |", f"a: {header}", 1, "is not a supported block scalar header")
                self.assertIn(f"'{header}'", err.reason)

    def test_a_folded_scalar_with_a_more_indented_line(self):
        self.refuses("a: >\n  x\n  y\n", "  y", "    y", 3, "a more-indented line inside a folded block scalar")

    def test_a_folded_more_indented_line_is_located_past_blank_lines_and_before_trailing_ones(self):
        self.refuses("a: >\n  x\n\n  y\nb: 1\n", "  y\n", "    y\n", 4, "a more-indented line inside a folded block scalar")
        self.refuses("a: >-\n  x\n  y\n\n\nb: 1\n", "  y\n", "    y\n", 3, "a more-indented line inside a folded block scalar")

    def test_a_block_scalar_line_indented_less_than_its_first_line(self):
        self.refuses("a: |\n    four\n    four\nb: 1\n", "    four\n    four", "    four\n  two", 3,
                     "a block scalar line is indented less than the block's first line")


class ContinuationRefusalTest(YamlCase):
    def test_a_multi_line_plain_scalar(self):
        self.refuses("a: one\nb: 2\n", "a: one\n", "a: one\n  two\n", 2, "a value cannot continue on the next line")

    def test_a_continuation_after_a_flow_value_and_after_a_sequence_item(self):
        self.refuses("a: [x]\nb: 2\n", "a: [x]\n", "a: [x]\n  y\n", 2, "a value cannot continue on the next line")
        self.refuses("l:\n  - a\n  - b\n", "  - a\n", "  - a\n     more\n", 3, "a value cannot continue on the next line")

    def test_a_dedent_to_a_level_that_matches_nothing(self):
        self.refuses("a:\n    b: 1\n    c: 2\n", "    c: 2", "  c: 2", 3, "the indentation does not match any enclosing level")

    def test_a_misaligned_key_in_a_sequence_item(self):
        self.refuses("l:\n- a: 1\n  b: 2\n", "  b: 2", " b: 2", 3, "the indentation does not match any enclosing level")

    def test_a_key_at_the_indent_of_a_sequence(self):
        self.refuses("a:\n  - x\n  - y\nb: 1\n", "  - y\n", "  - y\n  c: 2\n", 4, "the indentation does not match any enclosing level")

    def test_a_line_deeper_than_the_mapping_after_a_block_of_children(self):
        self.refuses("a:\n    b: 1\nc: 2\n", "c: 2", " c: 2", 3, "the indentation does not match any enclosing level")


class InputHygieneTest(YamlCase):
    def test_a_byte_order_mark(self):
        self.refuses_doc("\ufeffa: 1\n", 1, "a byte-order mark is not supported")

    def test_a_nul_and_other_control_characters(self):
        for char in ("\x00", "\x01", "\x07", "\x08", "\x0b", "\x0c", "\x1b", "\x1f"):
            with self.subTest(char=hex(ord(char))):
                err = self.refuses("a: 1\nb: 2\nc: 3\n", "b: 2", f"b: x{char}y", 2, "control character")
                self.assertIn(f"U+{ord(char):04X}", err.reason)

    def test_a_control_character_in_a_comment_or_a_block_scalar(self):
        self.refuses("a: 1\n# note\nb: 2\n", "# note", "# no\x00te", 2, "control character")
        self.refuses("a: |\n  one\n  two\n", "  two", "  t\x00wo", 3, "control character")

    def test_a_lone_carriage_return(self):
        self.refuses("a: 1\nb: 2\n", "a: 1\n", "a: 1\rb", 1, "a carriage return outside a CRLF line end")
        self.refuses("a: 1\nb: 2\nc: 3\n", "c: 3\n", "c: 3\r", 3, "a carriage return outside a CRLF line end")

    def test_a_lone_carriage_return_in_a_crlf_file_names_its_line(self):
        self.refuses_doc("a: 1\r\nb: 2\r\nc: 3\r\rd: 4\r\n", 3, "a carriage return outside a CRLF line end")

    def test_a_text_that_is_not_text(self):
        with self.assertRaisesRegex(TypeError, "use load"):
            loads(b"a: 1\n")


class NestingTest(YamlCase):
    def test_a_document_nested_too_deeply_is_refused_not_crashed(self):
        text = "".join(" " * level + "k:\n" for level in range(3000)) + " " * 3000 + "v: 1\n"
        with self.assertRaises(YamlSubsetError) as caught:
            loads(text, "deep.yaml")
        self.assertIn("nests too deeply", caught.exception.reason)
        self.assertGreater(caught.exception.line, 50, "the line should be where the reader was, not a default")
        self.assertEqual(caught.exception.name, "deep.yaml")


ROUND_TRIPS = tuple(
    [("copy.copy", copy.copy), ("copy.deepcopy", copy.deepcopy)]
    + [(f"pickle protocol {protocol}", lambda err, protocol=protocol: pickle.loads(pickle.dumps(err, protocol)))
       for protocol in range(pickle.HIGHEST_PROTOCOL + 1)]
)


class NarrowRefusal(YamlSubsetError):
    """A refusal of a narrower kind with the constructor of the base class; at module level so that pickle finds it."""


class ErrorRoundTripTest(YamlCase):
    """A refusal can be copied and pickled, so code that hands one across a process boundary gets the same error back."""

    def refusals(self):
        """Three real refusals, each built from a document that reads: in text, in a file, and of an empty text."""
        loads("a: 1\nb: 2\n", "doc.yaml")
        with self.assertRaises(YamlSubsetError) as in_text:
            loads("a: 1\njust text\n", "doc.yaml")
        load(write_file(self, b"a: 1\nb: 2\n"))
        path = write_file(self, b"a: 1\nb: \xff\n")
        with self.assertRaises(YamlSubsetError) as in_file:
            load(path)
        loads("a: 1\n")
        with self.assertRaises(YamlSubsetError) as empty:
            loads("")
        return in_text.exception, in_file.exception, empty.exception

    def test_a_refusal_survives_copying_and_pickling_at_every_protocol(self):
        first, second, third = self.refusals()
        self.assertEqual(((first.name, first.line), second.line, (third.name, third.line)), (("doc.yaml", 2), 2, ("<string>", 1)))
        for err in (first, second, third):
            for how, trip in ROUND_TRIPS:
                with self.subTest(refusal=str(err), how=how):
                    back = trip(err)
                    self.assertIs(type(back), YamlSubsetError)
                    self.assertIsInstance(back, ValueError)
                    self.assertEqual((back.name, back.line, back.reason), (err.name, err.line, err.reason))
                    self.assertEqual(str(back), str(err))
                    self.assertEqual(back.args, err.args)

    def test_a_refusal_of_a_subclass_comes_back_as_that_subclass(self):
        err = NarrowRefusal("doc.yaml", 7, "a reason")
        for how, trip in ROUND_TRIPS:
            with self.subTest(how=how):
                back = trip(err)
                self.assertIs(type(back), NarrowRefusal)
                self.assertEqual((back.name, back.line, back.reason), ("doc.yaml", 7, "a reason"))
                self.assertEqual(str(back), "doc.yaml:7: a reason")
                self.assertEqual(back.args, err.args)


if __name__ == "__main__":
    unittest.main()
