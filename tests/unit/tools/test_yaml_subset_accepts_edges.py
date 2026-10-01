"""What tools/yaml_subset.py accepts at the edges: whitespace and indicator characters that stay text.

Each test writes the document out and states the exact structure expected, so a text that an edge rule refuses or
changes fails here.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import unittest

from yaml_support import YamlCase, loads


class EdgesAndIndicatorsThatStayTextTest(YamlCase):
    """What the refusals of edge whitespace and of a leading indicator must NOT touch.

    Each document here is valid YAML 1.2 and reads as text.
    """

    def test_a_whole_line_comment_needs_no_space_after_the_hash(self):
        text = "#top\n#\na: 1\n  #indented\nb:\n#between\n  - x\n#!bang\n  - y\n#end\n"
        self.assertEqual(loads(text), {"a": "1", "b": ["x", "y"]})

    def test_a_hash_inside_a_quoted_key_is_text(self):
        self.assertEqual(loads('"a #b": 1\nc: {"d#e": f}\nl:\n  - "g #h": i\n'),
                         {"a #b": "1", "c": {"d#e": "f"}, "l": [{"g #h": "i"}]})

    def test_a_question_mark_that_is_not_an_indicator_stays_text(self):
        # a block value and a block item; '?' followed by a space or nothing is the indicator and is refused
        self.assertEqual(loads("k: ?a\nm:\n  - ?f\nn: a?b\n"), {"k": "?a", "m": ["?f"], "n": "a?b"})

    def test_a_question_mark_after_the_first_character_stays_text(self):
        cases = (
            ("a flow sequence entry ending in one", "l: [a?, b]\n", {"l": ["a?", "b"]}),
            ("a flow mapping value ending in one", "l: {k: a?}\n", {"l": {"k": "a?"}}),
            ("a flow mapping key ending in one", "l: {a?: b}\n", {"l": {"a?": "b"}}),
            ("a block key ending in one", "a?: 1\n", {"a?": "1"}),
            ("a sequence item key ending in one", "l:\n  - a?: 1\n", {"l": [{"a?": "1"}]}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_colon_that_does_not_end_the_text_stays_text(self):
        self.assertEqual(loads("k: a:b\nl: [c:d, e]\nu: http://x.example/y\nq: \"a:\"\n"),
                         {"k": "a:b", "l": ["c:d", "e"], "u": "http://x.example/y", "q": "a:"})

    def test_a_colon_with_no_space_after_it_does_not_end_a_plain_key(self):
        # a plain key ends at the first colon that is followed by a space or by nothing, not at any colon
        cases = (
            ("a block key", "a:b: 1\n", {"a:b": "1"}),
            ("a block key with several colons", "x:y:z: 1\n", {"x:y:z": "1"}),
            ("a block key with a mapping under it", "a:b:\n  c: 1\n", {"a:b": {"c": "1"}}),
            ("a block key with no value", "a:b:\n", {"a:b": None}),
            ("a block key with a comma after the colon", "a:,b: 1\n", {"a:,b": "1"}),
            ("a later block key with a comma after the colon", "a: 1\nb:,c: 2\n", {"a": "1", "b:,c": "2"}),
            ("a sequence item key", "l:\n  - a:b: 1\n", {"l": [{"a:b": "1"}]}),
            ("a sequence item key with a comma after the colon", "l:\n  - a:,b: 1\n", {"l": [{"a:,b": "1"}]}),
            ("a sequence item with a comma after a colon", "l:\n  - a:,b\n", {"l": ["a:,b"]}),
            ("a flow mapping key", "l: {a:b: 1}\n", {"l": {"a:b": "1"}}),
            ("a flow mapping key with several colons", "l: {a:b:c: 1}\n", {"l": {"a:b:c": "1"}}),
            ("a flow mapping key with no value", "l: {a:b:}\n", {"l": {"a:b": None}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_key_with_no_value_is_still_a_key_with_no_value(self):
        self.assertEqual(loads("l:\n  - a:\n  - b: c\nm: {d:, e: f}\n"),
                         {"l": [{"a": None}, {"b": "c"}], "m": {"d": None, "e": "f"}})

    def test_whitespace_inside_a_plain_or_quoted_scalar_is_kept(self):
        self.assertEqual(loads('k: a\u00a0b\nl: [c\u2003d]\nq: "e\u00a0"\n"f\u00a0": g\n'),
                         {"k": "a\u00a0b", "l": ["c\u2003d"], "q": "e\u00a0", "f\u00a0": "g"})

    def test_a_non_ascii_space_inside_a_plain_key_is_kept(self):
        # only a space at the edge of a plain key is refused; inside it, a no-break or an em space is part of the text
        cases = (
            ("a block key", "a\u00a0b: 1\n", {"a\u00a0b": "1"}),
            ("a sequence item key", "l:\n  - a\u2003b: 1\n", {"l": [{"a\u2003b": "1"}]}),
            ("a flow mapping key", "l: {a\u00a0b: 1}\n", {"l": {"a\u00a0b": "1"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_dashes_and_dots_that_are_not_a_document_marker_are_text(self):
        self.assertEqual(loads("a: 1\n---b: 2\n...c: 3\nd: |\n  --- x\n"),
                         {"a": "1", "---b": "2", "...c": "3", "d": "--- x\n"})

    def test_a_marker_looking_line_indented_four_spaces_or_more_is_text(self):
        # a marker followed by text counts at column 0; indented by four spaces or more the same characters are text
        cases = (
            ("a block scalar line of dashes", "a: |\n    --- x\n", {"a": "--- x\n"}),
            ("a block scalar line of dots", "a: |\n    ... x\n", {"a": "... x\n"}),
            ("a block scalar line indented by eight", "a: |\n        --- x\n", {"a": "--- x\n"}),
            ("a key of dashes and a word", "a:\n    --- x: 1\n", {"a": {"--- x": "1"}}),
            ("a key of dots and a word", "a:\n    ... x: 1\n", {"a": {"... x": "1"}}),
            ("a key indented by eight", "a:\n        ... x: 1\n", {"a": {"... x": "1"}}),
            ("a later key of a sequence item mapping", "l:\n  - a: 1\n    --- x: 2\n",
             {"l": [{"a": "1", "--- x": "2"}]}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_marker_looking_line_indented_four_spaces_does_not_move_the_line_of_a_later_refusal(self):
        # the refusal belongs to the line after the marker-looking one, so that line has to be read as text first
        block_scalar = "indented less than the block's first line"
        nesting = "does not match any enclosing level"
        cases = (
            ("a block scalar line of dashes", "a: |\n    --- x\n  b: 1\n", 3, block_scalar),
            ("a block scalar line of dots", "a: |\n    ... x\n  b: 1\n", 3, block_scalar),
            ("a key of dashes and a word", "a:\n    --- x: 1\n  c: 2\n", 3, nesting),
            ("a key of dots and a word", "a:\n    ... x: 1\n  c: 2\n", 3, nesting),
            ("a key of dashes alone", "a:\n    ---: 1\n  c: 2\n", 3, nesting),
        )
        for label, text, line, reason in cases:
            with self.subTest(label):
                self.refuses_doc(text, line, reason)

    def test_dots_glued_to_a_key_on_the_first_line_are_part_of_the_key(self):
        cases = (
            ("one key", "...a: 1\n", {"...a": "1"}),
            ("another key", "...c: 3\n", {"...c": "3"}),
            ("a key with a value", "...x: y\n", {"...x": "y"}),
            ("two such keys", "...a: 1\n...c: 3\n", {"...a": "1", "...c": "3"}),
            ("a key with a mapping under it", "...a:\n  b: 1\n", {"...a": {"b": "1"}}),
            ("a CRLF file", "...a: 1\r\nb: 2\r\n", {"...a": "1", "b": "2"}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_two_dashes_at_the_start_of_the_first_line_are_part_of_a_key(self):
        # the treatment of a leading '---' on line one must leave a leading '--' alone
        cases = (
            ("two dashes and a letter", "--a: 1\n", {"--a": "1"}),
            ("two dashes alone", "--: 1\n", {"--": "1"}),
            ("a key with a mapping under it", "--a:\n  b: 1\n", {"--a": {"b": "1"}}),
            ("a CRLF file", "--a: 1\r\nb: 2\r\n", {"--a": "1", "b": "2"}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_dashes_or_dots_glued_to_a_key_on_a_later_line_are_part_of_the_key(self):
        cases = (
            ("dashes and letters", "a: 1\n---abc: 1\n", {"a": "1", "---abc": "1"}),
            ("dashes and a letter", "a: 1\n---x: y\n", {"a": "1", "---x": "y"}),
            ("dots and a letter", "a: 1\n...x: y\n", {"a": "1", "...x": "y"}),
            ("dots and another letter", "a: 1\n...c: 3\n", {"a": "1", "...c": "3"}),
            ("dashes after a nested mapping", "a:\n  b: 1\n---abc: 2\n", {"a": {"b": "1"}, "---abc": "2"}),
            ("a CRLF file", "a: 1\r\n---abc: 1\r\n...x: y\r\n", {"a": "1", "---abc": "1", "...x": "y"}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_dashes_or_dots_followed_by_a_non_ascii_space_and_a_key_are_one_plain_key(self):
        # only an ASCII space after a marker at column 0 makes it a marker; any other space is text inside the key
        for char in ("\u00a0", "\u1680", "\u2003", "\u2028", "\u2029", "\u202f", "\u205f", "\u3000"):
            cases = (
                ("dashes on a later line", f"a: 1\n---{char}x: 2\n", {"a": "1", f"---{char}x": "2"}, 2),
                ("dots on a later line", f"a: 1\n...{char}x: 2\n", {"a": "1", f"...{char}x": "2"}, 2),
                ("dots on the first line", f"...{char}a: 1\nb: 2\n", {f"...{char}a": "1", "b": "2"}, 1),
                ("dashes after a nested mapping", f"a:\n  b: 1\n---{char}x: 2\n",
                 {"a": {"b": "1"}, f"---{char}x": "2"}, 3),
                ("dots in a CRLF file", f"a: 1\r\n...{char}x: 2\r\n", {"a": "1", f"...{char}x": "2"}, 2),
                ("dashes before a mapping", f"a: 1\n---{char}x:\n  y: 2\n", {"a": "1", f"---{char}x": {"y": "2"}}, 2),
            )
            for label, text, expected, line in cases:
                with self.subTest(label, char=hex(ord(char))):
                    self.assertEqual(loads(text), expected)
                    # the control: with an ASCII space in that place the same line is a document marker
                    self.refuses_doc(text.replace(char, " "), line, "document markers are not supported")

    def test_a_quoted_scalar_or_key_may_start_with_an_indicator_character(self):
        # written in quotes, each of these characters is ordinary text wherever a scalar or a key may go
        for char in ",]}|>%@`":
            cases = (
                ("a double-quoted block key", f'"{char}a": 1\n', {f"{char}a": "1"}),
                ("a single-quoted block key", f"'{char}a': 1\n", {f"{char}a": "1"}),
                ("a double-quoted block value", f'k: "{char}x"\n', {"k": f"{char}x"}),
                ("a single-quoted block value", f"k: '{char}x'\n", {"k": f"{char}x"}),
                ("a double-quoted sequence item", f'l:\n  - "{char}x"\n', {"l": [f"{char}x"]}),
                ("a double-quoted sequence item key", f'l:\n  - "{char}a": 1\n', {"l": [{f"{char}a": "1"}]}),
                ("a double-quoted flow sequence entry", f'l: ["{char}a"]\n', {"l": [f"{char}a"]}),
                ("a single-quoted flow sequence entry", f"l: ['{char}a']\n", {"l": [f"{char}a"]}),
                ("a double-quoted flow mapping key", f'l: {{"{char}a": b}}\n', {"l": {f"{char}a": "b"}}),
                ("a single-quoted flow mapping key", f"l: {{'{char}a': b}}\n", {"l": {f"{char}a": "b"}}),
                ("a double-quoted flow mapping value", f'l: {{k: "{char}b"}}\n', {"l": {"k": f"{char}b"}}),
                ("a single-quoted flow mapping value", f"l: {{k: '{char}b'}}\n", {"l": {"k": f"{char}b"}}),
            )
            for label, text, expected in cases:
                with self.subTest(char=char, where=label):
                    self.assertEqual(loads(text), expected)

    def test_an_indicator_character_inside_a_plain_scalar_or_key_stays_text(self):
        # only the first character of a plain scalar or key can be an indicator; later ones are text
        for char in ",]}|>%@`":
            cases = [
                ("inside a block value", f"k: a{char}b\n", {"k": f"a{char}b"}),
                ("at the end of a block value", f"k: a{char}\n", {"k": f"a{char}"}),
                ("inside a block key", f"a{char}b: 1\n", {f"a{char}b": "1"}),
                ("at the end of a block key", f"a{char}: 1\n", {f"a{char}": "1"}),
                ("inside a sequence item", f"l:\n  - a{char}b\n", {"l": [f"a{char}b"]}),
                ("inside a sequence item key", f"l:\n  - a{char}b: 1\n", {"l": [{f"a{char}b": "1"}]}),
            ]
            if char not in ",]}":  # these three end a flow entry instead
                cases += [
                    ("inside a flow sequence entry", f"l: [a{char}b]\n", {"l": [f"a{char}b"]}),
                    ("at the end of a flow sequence entry", f"l: [a{char}, c]\n", {"l": [f"a{char}", "c"]}),
                    ("inside a flow mapping value", f"l: {{k: a{char}b}}\n", {"l": {"k": f"a{char}b"}}),
                    ("inside a flow mapping key", f"l: {{a{char}b: 1}}\n", {"l": {f"a{char}b": "1"}}),
                ]
            for label, text, expected in cases:
                with self.subTest(char=char, where=label):
                    self.assertEqual(loads(text), expected)

    def test_a_dash_a_colon_or_a_question_mark_may_start_a_plain_scalar_or_key(self):
        # none of these starts anything in a block, and none is refused as an indicator
        cases = (
            ("a block key starting with a dash", "-a: 1\n", {"-a": "1"}),
            ("a block key starting with a colon", ":a: 1\n", {":a": "1"}),
            ("a block value starting with a dash", "k: -a\n", {"k": "-a"}),
            ("a block value starting with a colon", "k: :a\n", {"k": ":a"}),
            ("a block value starting with a question mark", "k: ?a\n", {"k": "?a"}),
            ("a sequence item starting with a question mark", "l:\n  - ?a\n", {"l": ["?a"]}),
            ("a flow sequence entry starting with a dash", "l: [-a]\n", {"l": ["-a"]}),
            ("a flow mapping key starting with a dash", "l: {-a: 1}\n", {"l": {"-a": "1"}}),
            ("a flow mapping value starting with a dash", "l: {a: -b}\n", {"l": {"a": "-b"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_dash_and_a_space_inside_a_key_a_value_or_an_entry_is_text(self):
        # only a key, value or entry that STARTS with '- ' is a sequence item indicator; further in, the same two
        # characters are text, whether they come before the colon of a key or inside a flow collection
        cases = (
            ("a flow mapping key with a dash between words", "l: {a - b: 1}\n", {"l": {"a - b": "1"}}),
            ("a flow mapping key ending in a dash", "l: {a - : 1}\n", {"l": {"a -": "1"}}),
            ("a later flow mapping key with a dash between words", "l: {k: 1, first - last: 2}\n",
             {"l": {"k": "1", "first - last": "2"}}),
            ("a flow mapping key with a dash before a word", "l: {a -b: 1}\n", {"l": {"a -b": "1"}}),
            ("a block key with a dash before a word", "a -b: 1\n", {"a -b": "1"}),
            ("a flow sequence entry with a dash between words", "l: [a - b]\n", {"l": ["a - b"]}),
            ("a flow mapping value with a dash between words", "l: {k: a - b}\n", {"l": {"k": "a - b"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_plain_key_value_or_entry_may_start_with_punctuation_or_a_non_ascii_character(self):
        # only the reserved indicators are refused as a first character; any other one is text, at each of the places
        # where a plain key, value or entry can begin (outside double quotes a backslash has no meaning)
        places = (
            ("a block key", "a: 1\n%s: 2\n", lambda text: {"a": "1", text: "2"}),
            ("a sequence item key", "l:\n  - %s: 1\n", lambda text: {"l": [{text: "1"}]}),
            ("a flow mapping key", "l: {%s: 1}\n", lambda text: {"l": {text: "1"}}),
            ("a block value", "k: %s\n", lambda text: {"k": text}),
            ("a sequence item", "l:\n  - %s\n", lambda text: {"l": [text]}),
            ("a flow sequence entry", "l: [%s]\n", lambda text: {"l": [text]}),
            ("a flow mapping value", "l: {k: %s}\n", lambda text: {"l": {"k": text}}),
        )
        for char in ("=", "_", "^", "(", ")", "+", ";", "\\", "\u00e9", "\u65e5", "\U0001f600"):
            for label, template, structure in places:
                with self.subTest(char=f"U+{ord(char):04X}", where=label):
                    self.assertEqual(loads(template % (char + "a")), structure(char + "a"))

    def test_a_colon_may_start_a_plain_flow_entry_flow_mapping_value_or_flow_mapping_key(self):
        # the chosen reading: YAML 1.2 takes a ':' followed by a character that is not a space as plain text, and so
        # does this reader inside a flow collection; a reader built on YAML 1.1 may refuse these
        cases = (
            ("a flow sequence entry", "l: [:a]\n", {"l": [":a"]}),
            ("a later flow sequence entry", "l: [x, :a]\n", {"l": ["x", ":a"]}),
            ("a flow mapping value", "l: {k: :a}\n", {"l": {"k": ":a"}}),
            ("a later flow mapping value", "l: {k: 1, j: :a}\n", {"l": {"k": "1", "j": ":a"}}),
            ("a flow mapping key", "l: {:a: 1}\n", {"l": {":a": "1"}}),
            ("a later flow mapping key", "l: {k: 1, :a: 2}\n", {"l": {"k": "1", ":a": "2"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_quoted_double_angle_key_is_an_ordinary_key(self):
        cases = (
            ("a double-quoted block key", '"<<": 1\n', {"<<": "1"}),
            ("a single-quoted block key", "'<<': 1\n", {"<<": "1"}),
            ("a quoted key with spaces before its colon", '"<<" : 1\n', {"<<": "1"}),
            ("a quoted sequence item key", 'l:\n  - "<<": 1\n', {"l": [{"<<": "1"}]}),
            ("a double-quoted flow mapping key", 'l: {"<<": 1}\n', {"l": {"<<": "1"}}),
            ("a single-quoted flow mapping key", "l: {'<<': 1}\n", {"l": {"<<": "1"}}),
            ("a quoted key next to other keys", 'x:\n  "<<": {a: 1}\n  b: 2\n',
             {"x": {"<<": {"a": "1"}, "b": "2"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_a_double_angle_that_is_not_the_whole_plain_key_stays_text(self):
        cases = (
            ("a block key ending in it", "a<<: 1\n", {"a<<": "1"}),
            ("a block key starting with it", "<<a: 1\n", {"<<a": "1"}),
            ("a block key holding it", "x<<y: 1\n", {"x<<y": "1"}),
            ("a sequence item key ending in it", "l:\n  - a<<: 1\n", {"l": [{"a<<": "1"}]}),
            ("a flow mapping key ending in it", "l: {a<<: 1}\n", {"l": {"a<<": "1"}}),
            ("a flow mapping key starting with it", "l: {<<a: 1}\n", {"l": {"<<a": "1"}}),
            ("a block value that is it", "k: <<\n", {"k": "<<"}),
            ("a block value holding it", "k: a<<b\n", {"k": "a<<b"}),
            ("a sequence item that is it", "l:\n  - <<\n", {"l": ["<<"]}),
            ("a flow sequence entry that is it", "l: [<<]\n", {"l": ["<<"]}),
            ("a flow mapping value that is it", "l: {k: <<}\n", {"l": {"k": "<<"}}),
        )
        for label, text, expected in cases:
            with self.subTest(label):
                self.assertEqual(loads(text), expected)

    def test_characters_at_the_edges_of_the_excluded_ranges_are_still_text(self):
        # each of these is a printable YAML character, and reads as itself in each placement below
        for code in (0xD7FF, 0xE000, 0xFDD0, 0xFFFD, 0x10000, 0x1F600, 0x10FFFF):
            char = chr(code)
            cases = (
                ("a plain value", f"k: a{char}b\n", {"k": f"a{char}b"}),
                ("a plain key", f"a{char}b: v\n", {f"a{char}b": "v"}),
                ("a double-quoted scalar", f'k: "a{char}b"\n', {"k": f"a{char}b"}),
                ("a single-quoted scalar", f"k: '{char}'\n", {"k": char}),
                ("a flow entry", f"l: [a{char}b]\n", {"l": [f"a{char}b"]}),
                ("a block scalar line", f"k: |\n  a{char}b\n", {"k": f"a{char}b\n"}),
                ("a whole-line comment", f"# a{char}b\nk: v\n", {"k": "v"}),
                ("a comment after the last key", f"k: v\n# a{char}b\n", {"k": "v"}),
            )
            for label, text, expected in cases:
                with self.subTest(code=f"U+{code:04X}", where=label):
                    self.assertEqual(loads(text), expected)


if __name__ == "__main__":
    unittest.main()
