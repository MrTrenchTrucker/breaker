"""What tools/yaml_subset.py accepts, one test per construct.

Covers block mappings and sequences, empty and typed values, quoting, comments and line ends, flow collections and block
scalars. Each test writes the document out and states the exact structure expected, so a construct that is refused,
mistyped, reordered or mis-indented fails here.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import unittest

from yaml_support import YamlCase, loads


class BlockStructureTest(YamlCase):
    def test_block_mapping_keeps_key_order(self):
        self.assertEqual(list(loads("zeta: 1\nalpha: 2\nmid: 3\n")), ["zeta", "alpha", "mid"])
        self.assertEqual(loads("zeta: 1\nalpha: 2\n"), {"zeta": "1", "alpha": "2"})

    def test_nested_mapping(self):
        self.assertEqual(loads("a:\n  b:\n    c: deep\n  d: shallow\ne: top\n"),
                         {"a": {"b": {"c": "deep"}, "d": "shallow"}, "e": "top"})

    def test_sequence_of_scalars(self):
        self.assertEqual(loads("tags:\n  - one\n  - two\nnext: x\n"), {"tags": ["one", "two"], "next": "x"})

    def test_sequence_of_mappings_with_aligned_keys(self):
        text = "models:\n  - id: a\n    size: 1\n  - id: b\n    size: 2\n"
        self.assertEqual(loads(text), {"models": [{"id": "a", "size": "1"}, {"id": "b", "size": "2"}]})

    def test_sequence_inside_a_sequence_item(self):
        text = "items:\n  - name: x\n    tags:\n      - a\n      - b\n    last: z\n  - name: y\n"
        self.assertEqual(loads(text), {"items": [{"name": "x", "tags": ["a", "b"], "last": "z"}, {"name": "y"}]})

    def test_sequence_at_the_keys_own_indent(self):
        self.assertEqual(loads("tags:\n- a\n- b\nnext: x\n"), {"tags": ["a", "b"], "next": "x"})

    def test_sequence_at_the_keys_own_indent_inside_an_item(self):
        text = "items:\n  - name: x\n    tags:\n    - a\n    - b\n    last: z\n"
        self.assertEqual(loads(text), {"items": [{"name": "x", "tags": ["a", "b"], "last": "z"}]})

    def test_any_number_of_spaces_after_the_dash(self):
        self.assertEqual(loads("a:\n  -   b: 1\n      c: 2\n  -  d\n"), {"a": [{"b": "1", "c": "2"}, "d"]})

    def test_a_key_starting_with_a_dash_is_a_key_not_a_sequence_item(self):
        self.assertEqual(loads("a:\n-x: 1\n"), {"a": None, "-x": "1"})

    def test_the_root_may_be_indented(self):
        self.assertEqual(loads("  a: 1\n  b:\n    c: 2\n"), {"a": "1", "b": {"c": "2"}})

    def test_any_indent_width_per_level(self):
        self.assertEqual(loads("a:\n    b:\n     c: 1\n    d: 2\n"), {"a": {"b": {"c": "1"}, "d": "2"}})

    def test_a_quoted_key_opens_a_sequence_item_mapping(self):
        self.assertEqual(loads('l:\n  - "a b": c\n    d: e\n'), {"l": [{"a b": "c", "d": "e"}]})


class EmptyAndTypedValuesTest(YamlCase):
    def test_an_empty_value_is_none_at_the_end_of_the_document(self):
        self.assertEqual(loads("a: 1\nb:\n"), {"a": "1", "b": None})
        self.assertEqual(loads("a: 1\nb:"), {"a": "1", "b": None})

    def test_an_empty_value_is_none_before_a_sibling_and_before_a_dedent(self):
        self.assertEqual(loads("a:\nb: 1\n"), {"a": None, "b": "1"})
        self.assertEqual(loads("a:\n  b:\n  c: 1\nd:\n"), {"a": {"b": None, "c": "1"}, "d": None})

    def test_a_quoted_empty_string_is_not_none(self):
        self.assertEqual(loads('a: ""\nb: \'\'\nc:\n'), {"a": "", "b": "", "c": None})

    def test_every_plain_scalar_is_a_string(self):
        text = ("a: true\nb: false\nc: 12\nd: 0.0\ne: null\nf: ~\ng: no\nh: 2026-09-30\ni: 0e12\n"
                "j: 0x1F\nk: 1_000\nl: .inf\nm: 007\nn: 1.10\no: Null\np: YES\n")
        got = loads(text)
        self.assertEqual(got, {"a": "true", "b": "false", "c": "12", "d": "0.0", "e": "null", "f": "~", "g": "no",
                               "h": "2026-09-30", "i": "0e12", "j": "0x1F", "k": "1_000", "l": ".inf",
                               "m": "007", "n": "1.10", "o": "Null", "p": "YES"})
        self.assertTrue(all(type(value) is str for value in got.values()))

    def test_only_dict_list_str_and_none_come_back(self):
        text = "a: 1\nb: [true, {c: 2.5, d: }]\ne:\n  - f: g\n  - h\n"

        def walk(node):
            self.assertIn(type(node), (dict, list, str, type(None)))
            if isinstance(node, dict):
                for key, value in node.items():
                    self.assertIs(type(key), str)
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)
        walk(loads(text))

    def test_a_numeric_key_stays_a_string(self):
        got = loads('"200":\n  d: ok\n404: nf\n')
        self.assertEqual(got, {"200": {"d": "ok"}, "404": "nf"})
        self.assertTrue(all(type(key) is str for key in got))


class QuotingTest(YamlCase):
    def test_double_quoted_escapes(self):
        self.assertEqual(loads('a: "x\\ny\\tz\\"q\\\\w"\n'), {"a": 'x\ny\tz"q\\w'})

    def test_single_quoted_doubles_the_quote(self):
        self.assertEqual(loads("a: 'it''s'\nb: ''''\n"), {"a": "it's", "b": "'"})

    def test_a_backslash_is_plain_text_inside_single_quotes(self):
        # only a double-quoted scalar has escapes: in single quotes a backslash is a character, even the last one
        cases = (
            ("a block value ending in a backslash", "k: 'C:\\dir\\'\n", {"k": "C:\\dir\\"}),
            ("a backslash before a letter", "k: 'a\\nb'\n", {"k": "a\\nb"}),
            ("two backslashes in a row", "k: 'a\\\\b'\n", {"k": "a\\\\b"}),
            ("a block key ending in a backslash", "'C:\\dir\\': 1\n", {"C:\\dir\\": "1"}),
            ("a sequence item ending in a backslash", "l:\n  - 'C:\\dir\\'\n", {"l": ["C:\\dir\\"]}),
            ("a flow sequence entry ending in a backslash", "l: ['C:\\dir\\', b]\n", {"l": ["C:\\dir\\", "b"]}),
            ("a flow mapping value ending in a backslash", "l: {k: 'C:\\dir\\'}\n", {"l": {"k": "C:\\dir\\"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_quoted_number_and_a_quoted_null_are_strings(self):
        self.assertEqual(loads('a: "12"\nb: \'null\'\nc: "true"\nd: \'12\'\ne: "0.5"\n'),
                         {"a": "12", "b": "null", "c": "true", "d": "12", "e": "0.5"})

    def test_quoted_scalars_may_hold_a_hash_a_colon_and_brackets(self):
        text = 'a: "x # y"\nb: "p: q"\nc: \'[not, flow]\'\nd: "{k: v}"\ne: "&x *y !z"\nf: "- a"\n'
        self.assertEqual(loads(text), {"a": "x # y", "b": "p: q", "c": "[not, flow]", "d": "{k: v}",
                                       "e": "&x *y !z", "f": "- a"})

    def test_quoted_keys(self):
        text = '"a: b": 1\n\'c # d\': 2\n"e\\"f": 3\n"": 4\n'
        self.assertEqual(loads(text), {"a: b": "1", "c # d": "2", 'e"f': "3", "": "4"})

    def test_a_quoted_key_may_have_spaces_before_its_colon(self):
        self.assertEqual(loads('"a" : b\n'), {"a": "b"})

    def test_a_plain_key_may_have_spaces_before_its_colon(self):
        # the spaces belong to neither the key nor the value: the key is read without them, in every position
        cases = (
            ("a block key", "k : v\n", {"k": "v"}),
            ("a block key with several spaces", "k   : v\n", {"k": "v"}),
            ("a block key with no value", "k :\n", {"k": None}),
            ("a sequence item key", "l:\n  - k : v\n", {"l": [{"k": "v"}]}),
            ("a sequence item key with no value", "l:\n  - k :\n", {"l": [{"k": None}]}),
            ("a flow mapping key", "l: {k : v}\n", {"l": {"k": "v"}}),
            ("a flow mapping key with no value", "l: {k :}\n", {"l": {"k": None}}),
            ("several flow mapping entries", "l: {k   :   v , j : w}\n", {"l": {"k": "v", "j": "w"}}),
            ("a flow sequence entry before its comma", "l: [a , b ]\n", {"l": ["a", "b"]}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_quoted_scalar_may_have_trailing_spaces_after_it(self):
        self.assertEqual(loads('a: "x"   \n'), {"a": "x"})

    def test_plain_scalar_keeps_an_apostrophe_and_a_quote_inside(self):
        self.assertEqual(loads("a: The client's id for this row.\nb: say \"hi\" now\n"),
                         {"a": "The client's id for this row.", "b": 'say "hi" now'})

    def test_a_url_as_a_sequence_item(self):
        self.assertEqual(loads("l:\n  - http://x/y\n  - https://z:80/w\n"), {"l": ["http://x/y", "https://z:80/w"]})

    def test_plain_scalar_may_hold_a_colon_not_followed_by_a_space(self):
        text = "url: http://host:8000/a/b\nt: 12:30\nr: a:b\n"
        self.assertEqual(loads(text), {"url": "http://host:8000/a/b", "t": "12:30", "r": "a:b"})

    def test_plain_scalar_may_hold_a_dash_a_bracket_and_words(self):
        self.assertEqual(loads("a: b - c\nd: see [1] and {2}\ne: -1\nf: -x\n"),
                         {"a": "b - c", "d": "see [1] and {2}", "e": "-1", "f": "-x"})

    def test_a_reference_key_and_keys_with_spaces(self):
        text = 'schema:\n  $ref: "#/components/schemas/X"\nmodel family: whisper\nit\'s: fine\n'
        self.assertEqual(loads(text), {"schema": {"$ref": "#/components/schemas/X"}, "model family": "whisper",
                                       "it's": "fine"})


class CommentsAndLineEndsTest(YamlCase):
    def test_whole_line_comments_and_blank_lines_anywhere(self):
        text = ("# top\n\na: 1\n# between keys\n\n  # indented comment\nb:\n  # inside a mapping\n  - x\n\n"
                "  # between items\n  - y\n# at the end\n\n")
        self.assertEqual(loads(text), {"a": "1", "b": ["x", "y"]})

    def test_a_comment_or_blank_line_may_hold_a_tab(self):
        self.assertEqual(loads("a: 1\n# a\ttab\n\t\nb: 2\n"), {"a": "1", "b": "2"})

    def test_crlf_reads_the_same_as_lf(self):
        text = "a:\n  - b: 1\n    c: |\n      x\n\n      y\n  - d\ne: [f, g]\n# c\n"
        self.assertEqual(loads(text.replace("\n", "\r\n")), loads(text))
        self.assertEqual(loads(text)["a"][0]["c"], "x\n\ny\n")

    def test_no_trailing_newline(self):
        self.assertEqual(loads("a: 1\nb: 2"), {"a": "1", "b": "2"})
        self.assertEqual(loads("a:\n  - x\n  - y"), {"a": ["x", "y"]})

    def test_trailing_spaces_are_ignored_after_a_value(self):
        self.assertEqual(loads("a: 1   \nb:   \n  c: 2  \n"), {"a": "1", "b": {"c": "2"}})

    def test_the_default_name_of_a_string_source(self):
        with self.assertRaises(ValueError) as caught:
            loads("")
        self.assertTrue(str(caught.exception).startswith("<string>:1: "), str(caught.exception))
        self.assertIn("the document is empty", str(caught.exception))


class FlowTest(YamlCase):
    def test_flow_sequence_and_mapping_of_scalars(self):
        self.assertEqual(loads("a: [x, y, z]\nb: {k: v, n: w}\n"), {"a": ["x", "y", "z"], "b": {"k": "v", "n": "w"}})

    def test_flow_spacing_does_not_matter(self):
        self.assertEqual(loads("a: [x,y ,  z ]\nb: {k: v,n:  w }\nc: { k: v }\nd: [ x ]\n"),
                         {"a": ["x", "y", "z"], "b": {"k": "v", "n": "w"}, "c": {"k": "v"}, "d": ["x"]})

    def test_empty_flow_collections(self):
        self.assertEqual(loads("a: []\nb: {}\nc: [ ]\nd: {  }\n"), {"a": [], "b": {}, "c": [], "d": {}})

    def test_a_flow_mapping_value_may_be_empty(self):
        self.assertEqual(loads("a: {k: , j: v}\nb: {k:}\n"), {"a": {"k": None, "j": "v"}, "b": {"k": None}})

    def test_a_quoted_flow_mapping_key_may_have_no_value(self):
        # a colon right before a comma or a closing brace ends a quoted key, as it does a plain one
        cases = (
            ("a double-quoted key before the closing brace", 'l: {"a":}\n', {"l": {"a": None}}),
            ("a single-quoted key before the closing brace", "l: {'a':}\n", {"l": {"a": None}}),
            ("a double-quoted key before a comma", 'l: {"a":, b: 1}\n', {"l": {"a": None, "b": "1"}}),
            ("a single-quoted key before a comma", "l: {'a':, b: 1}\n", {"l": {"a": None, "b": "1"}}),
            ("a later key before the closing brace", 'l: {b: 1, "a":}\n', {"l": {"b": "1", "a": None}}),
            ("a later key before a comma", 'l: {b: 1, "a":, c: 2}\n', {"l": {"b": "1", "a": None, "c": "2"}}),
            ("a key in a mapping inside a sequence", 'l: [{"a":}]\n', {"l": [{"a": None}]}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_flow_collections_as_sequence_items(self):
        self.assertEqual(loads("l:\n  - [a, b]\n  - {k: v}\n  - {}\n"), {"l": [["a", "b"], {"k": "v"}, {}]})

    def test_a_flow_mapping_item_is_not_read_as_a_block_key(self):
        self.assertEqual(loads("l:\n  - {a: 1}\n  - {b: 2, c: 3}\n"), {"l": [{"a": "1"}, {"b": "2", "c": "3"}]})

    def test_quoted_scalars_inside_flow_protect_commas_and_colons(self):
        text = 'a: {d: "Rows stored, not stored again.", e: \'x: y\'}\nb: ["a, b", \'c, d\']\n'
        self.assertEqual(loads(text), {"a": {"d": "Rows stored, not stored again.", "e": "x: y"},
                                       "b": ["a, b", "c, d"]})

    def test_quoted_keys_inside_flow(self):
        self.assertEqual(loads('a: {"k: 1": v, \'j, 2\': w}\n'), {"a": {"k: 1": "v", "j, 2": "w"}})

    def test_escapes_inside_a_double_quoted_scalar_in_flow_are_decoded(self):
        # \n, \t and \\ read the same in a flow collection as they do on a block line
        shapes = (
            ("a sequence entry", 'a: ["p%sq"]\n', lambda text: {"a": [text]}),
            ("a mapping value", 'a: {k: "p%sq"}\n', lambda text: {"a": {"k": text}}),
            ("a mapping key", 'a: {"p%sq": v}\n', lambda text: {"a": {text: "v"}}),
        )
        escapes = (("newline", "\\n", "\n"), ("tab", "\\t", "\t"), ("backslash", "\\\\", "\\"))
        for shape, template, structure in shapes:
            for name, written, decoded in escapes:
                with self.subTest(shape=shape, escape=name):
                    self.assertEqual(loads(template % written), structure("p" + decoded + "q"))

    def test_a_double_quoted_flow_scalar_may_end_in_an_escaped_backslash(self):
        self.assertEqual(loads('a: ["x\\\\", y]\nb: {k: "z\\\\", "w\\\\": v}\n'),
                         {"a": ["x\\", "y"], "b": {"k": "z\\", "w\\": "v"}})
        self.assertEqual(loads('a: [["x\\ny"], {k: "p\\tq"}]\n'), {"a": [["x\ny"], {"k": "p\tq"}]})

    def test_an_apostrophe_inside_a_plain_flow_scalar(self):
        text = "a: { description: The client's id for this row., b: c }\nl: [it's, don't]\n"
        self.assertEqual(loads(text), {"a": {"description": "The client's id for this row.", "b": "c"},
                                       "l": ["it's", "don't"]})

    def test_a_url_inside_flow(self):
        self.assertEqual(loads("a: [http://x/y, https://z:80/w]\nb: {u: http://x/y}\n"),
                         {"a": ["http://x/y", "https://z:80/w"], "b": {"u": "http://x/y"}})

    def test_a_reference_in_a_flow_mapping(self):
        self.assertEqual(loads('schema: { $ref: "#/components/schemas/X" }\n'),
                         {"schema": {"$ref": "#/components/schemas/X"}})

    def test_nested_flow_two_levels(self):
        text = "source: { type: string, enum: [local, server], description: Which engine produced it. }\n"
        self.assertEqual(loads(text), {"source": {"type": "string", "enum": ["local", "server"],
                                                  "description": "Which engine produced it."}})

    def test_nested_flow_mixed_and_empty(self):
        self.assertEqual(loads("a: [[1, 2], [], {k: [x, {j: v}]}, [[z]]]\n"),
                         {"a": [["1", "2"], [], {"k": ["x", {"j": "v"}]}, [["z"]]]})

    def test_nested_flow_any_depth(self):
        depth = 3000
        got = loads("a: " + "[" * depth + "x" + "]" * depth + "\n")["a"]
        for _ in range(depth - 1):
            self.assertEqual(len(got), 1)
            got = got[0]
        self.assertEqual(got, ["x"])

    def test_nested_flow_mapping_any_depth(self):
        depth = 2000
        got = loads("a: " + "{k: " * depth + "x" + "}" * depth + "\n")["a"]
        for _ in range(depth - 1):
            got = got["k"]
        self.assertEqual(got, {"k": "x"})

    def test_one_key_may_repeat_in_different_mappings(self):
        self.assertEqual(loads("a: {k: 1}\nb: {k: 2}\nc:\n  k: 3\nl:\n  - k: 4\n  - k: 5\n"),
                         {"a": {"k": "1"}, "b": {"k": "2"}, "c": {"k": "3"}, "l": [{"k": "4"}, {"k": "5"}]})


class BlockScalarTest(YamlCase):
    def test_literal_keeps_line_breaks_and_one_final_newline(self):
        self.assertEqual(loads("a: |\n  one\n  two\nb: 1\n"), {"a": "one\ntwo\n", "b": "1"})

    def test_literal_strip(self):
        self.assertEqual(loads("a: |-\n  one\n  two\nb: 1\n"), {"a": "one\ntwo", "b": "1"})

    def test_folded_joins_lines_with_a_space(self):
        self.assertEqual(loads("a: >\n  one\n  two\nb: 1\n"), {"a": "one two\n", "b": "1"})

    def test_folded_strip(self):
        self.assertEqual(loads("a: >-\n  one\n  two\nb: 1\n"), {"a": "one two", "b": "1"})

    def test_folded_leading_blank_lines_are_newlines(self):
        self.assertEqual(loads("a: >\n\n  one\n"), {"a": "\none\n"})

    def test_folded_blank_lines_become_newlines(self):
        self.assertEqual(loads("a: >-\n  one\n\n  two\n\n\n  three\n"), {"a": "one\ntwo\n\nthree"})

    def test_blank_lines_inside_a_literal_are_text(self):
        self.assertEqual(loads("a: |\n  one\n\n  two\n\n\n  three\nb: 1\n"), {"a": "one\n\ntwo\n\n\nthree\n", "b": "1"})

    def test_hash_lines_inside_a_block_are_text(self):
        self.assertEqual(loads("a: |\n  one\n  # not a comment\n    # nor this\n  two\nb: 1\n"),
                         {"a": "one\n# not a comment\n  # nor this\ntwo\n", "b": "1"})

    def test_the_first_line_sets_the_indent_and_deeper_lines_keep_their_extra(self):
        self.assertEqual(loads("a: |\n    four\n      six\n    four\nb: 1\n"), {"a": "four\n  six\nfour\n", "b": "1"})

    def test_no_extra_leading_space_on_a_two_space_file(self):
        self.assertEqual(loads("k:\n  a: |\n    text\n"), {"k": {"a": "text\n"}})

    def test_leading_blank_lines_are_text_and_trailing_ones_are_dropped(self):
        self.assertEqual(loads("a: |\n\n  one\n\n\nb: 1\n"), {"a": "\none\n", "b": "1"})
        self.assertEqual(loads("a: |-\n  one\n\n\n"), {"a": "one"})

    def test_a_whitespace_only_line_keeps_only_the_spaces_beyond_the_indent(self):
        # as YAML reads it: a line of spaces is text for the spaces past the block's indent, and an empty line otherwise
        self.assertEqual(loads("a: |\n  one\n      \n  two\n"), {"a": "one\n    \ntwo\n"})
        self.assertEqual(loads("a: |\n    one\n      \n    two\n"), {"a": "one\n  \ntwo\n"})
        self.assertEqual(loads("a: |\n    one\n    \n    two\n"), {"a": "one\n\ntwo\n"})
        self.assertEqual(loads("a: |\n    one\n  \n    two\n"), {"a": "one\n\ntwo\n"})

    def test_whitespace_only_lines_read_as_real_yaml_reads_them(self):
        # these are the values YAML defines for these documents
        cases = (
            ("|", "a: |\n    one\n      \n    two\nb: 1\n", "one\n  \ntwo\n"),
            ("|", "a: |\n    one\n      \nb: 1\n", "one\n  \n"),
            ("|", "a: |\n    one\n      \n", "one\n  \n"),
            ("|", "a: |\n    one\n      ", "one\n  "),
            ("|", "a: |\n    one\n    \nb: 1\n", "one\n"),
            ("|", "a: |\n    \n    one\nb: 1\n", "\none\n"),
            ("|-", "a: |-\n    one\n      \n    two\nb: 1\n", "one\n  \ntwo"),
            ("|-", "a: |-\n    one\n      \nb: 1\n", "one\n  "),
            ("|-", "a: |-\n    one\n      \n", "one\n  "),
            ("|-", "a: |-\n    one\n      ", "one\n  "),
            ("|-", "a: |-\n    one\n    \nb: 1\n", "one"),
            ("|-", "a: |-\n    \n    one\nb: 1\n", "\none"),
            (">", "a: >\n    one\n  \n    two\nb: 1\n", "one\ntwo\n"),
            (">", "a: >\n    one\n    \nb: 1\n", "one\n"),
            (">", "a: >\n    \n    one\nb: 1\n", "\none\n"),
            (">-", "a: >-\n    one\n  \n    two\nb: 1\n", "one\ntwo"),
            (">-", "a: >-\n    one\n    \nb: 1\n", "one"),
            (">-", "a: >-\n    \n    one\nb: 1\n", "\none"),
        )
        for header, text, expected in cases:
            with self.subTest(header=header, text=text):
                self.assertEqual(loads(text)["a"], expected)

    def test_trailing_spaces_on_a_text_line_are_kept(self):
        self.assertEqual(loads("a: |\n  one  \n  two\n"), {"a": "one  \ntwo\n"})

    def test_a_block_scalar_last_in_the_document(self):
        self.assertEqual(loads("a: |\n  one\n  two\n"), {"a": "one\ntwo\n"})
        self.assertEqual(loads("a: |-\n  one\n"), {"a": "one"})

    def test_a_block_scalar_at_the_end_of_a_file_keeps_a_final_line_break_only_if_the_file_has_one(self):
        # real YAML: with no line end after the last text line there is no final line break to keep
        self.assertEqual(loads("a: |\n  one\n  two"), {"a": "one\ntwo"})
        self.assertEqual(loads("a: >\n  one\n  two"), {"a": "one two"})
        self.assertEqual(loads("a: |-\n  one"), {"a": "one"})
        self.assertEqual(loads("a: |\r\n  one"), {"a": "one"})
        self.assertEqual(loads("a: |\n  one\n\n"), {"a": "one\n"})
        self.assertEqual(loads("b:\n  a: |\n    one\n"), {"b": {"a": "one\n"}})

    def test_a_block_scalar_in_a_sequence_item_mapping(self):
        text = "l:\n  - id: a\n    notes: |\n      first\n      second\n    n: 1\n  - id: b\n"
        self.assertEqual(loads(text), {"l": [{"id": "a", "notes": "first\nsecond\n", "n": "1"}, {"id": "b"}]})

    def test_a_block_scalar_ends_at_a_comment_at_a_lesser_indent(self):
        self.assertEqual(loads("a:\n  b: |\n    text\n# note\n  c: 1\n"), {"a": {"b": "text\n", "c": "1"}})

    def test_block_text_may_look_like_yaml(self):
        text = ("a: |\n  data: {\"x\": 1}\n  - not an item\n  key: [not, flow]\n  &anchor *alias !tag\n"
                "  it's a 'quote' and a \"quote\"\n  url: http://x/y#frag\nb: 1\n")
        expected = ("data: {\"x\": 1}\n- not an item\nkey: [not, flow]\n&anchor *alias !tag\n"
                    "it's a 'quote' and a \"quote\"\nurl: http://x/y#frag\n")
        self.assertEqual(loads(text), {"a": expected, "b": "1"})

    def test_block_scalar_in_crlf(self):
        self.assertEqual(loads("a: |\r\n  one\r\n\r\n  two\r\nb: 1\r\n"), {"a": "one\n\ntwo\n", "b": "1"})

    def test_a_more_indented_line_inside_a_literal_is_kept(self):
        self.assertEqual(loads("a: |\n  x\n    y\n  z\n"), {"a": "x\n  y\nz\n"})

    def test_a_key_after_a_block_scalar_at_a_lesser_indent(self):
        self.assertEqual(loads("a:\n  b: |\n    t\nc: 1\n"), {"a": {"b": "t\n"}, "c": "1"})


if __name__ == "__main__":
    unittest.main()
