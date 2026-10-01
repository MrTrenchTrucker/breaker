"""What tools/yaml_subset.py refuses in whitespace, document markers, control characters and a few plain texts.

Covers whitespace at the edge of a plain scalar or key and inside block scalars, lines that hold only a non-ASCII
space, a document marker on line one and after it, the control character range, and plain keys and values that hold
a hash, a question mark or a trailing colon. Every refusal test asserts the reason and the line, and each edited
document is built from an accepted one (the control), with the edit checked to have landed.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import unittest

from yaml_support import YamlCase, edit, loads

# -- structures YAML reads one way must not come back another way with no refusal ------------

# every non-ASCII character `str.isspace()` calls whitespace, except U+0080..U+009F (control characters, refused before
# whitespace is looked at): derived here, not typed, so none is left out
UNICODE_SPACES = tuple(chr(code) for code in range(0x80, 0x10000) if chr(code).isspace() and not 0x80 <= code <= 0x9F)


class PlainKeyHashTest(YamlCase):
    def test_a_hash_in_a_plain_block_key(self):
        for good, old, new, line, shown in (
            ("a: 1\nb: 2\n", "b: 2", "b #c: 2", 2, "'b #c'"),
            ("a: 1\nb: 2\n", "b: 2", "b#c: 2", 2, "'b#c'"),
            ("a:\n  b: 2\n", "  b: 2", "  b #c: 2", 2, "'b #c'"),
        ):
            with self.subTest(text=new):
                err = self.refuses(good, old, new, line, "a '#' in a plain key")
                self.assertIn(shown, err.reason)

    def test_a_trailing_comment_written_after_a_sequence_item_that_holds_a_colon(self):
        err = self.refuses("l:\n  - /latest/\n", "/latest/", "/latest/   # never pin: moving target", 2, "a '#' in a plain key")
        self.assertIn("/latest/   # never pin", err.reason)

    def test_a_hash_in_a_plain_key_of_a_sequence_item_mapping(self):
        good = "l:\n  - id: x\n    n: 1\n"
        for old, new, line in (("  - id: x", "  - id #c: x", 2), ("    n: 1", "    n #c: 1", 3)):
            with self.subTest(text=new):
                self.refuses(good, old, new, line, "a '#' in a plain key")

    def test_a_hash_in_a_plain_flow_key(self):
        for good, old, new in (
            ("l: {a: 1, b: 2}\n", "b: 2", "b #c: 2"),
            ("l: {a: 1, b: 2}\n", "{a: 1", "{a#c: 1"),
            ("l: [{a: 1}]\n", "{a: 1}", "{a #c: 1}"),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, 1, "a '#' in a plain key")

    def test_a_hash_that_starts_a_plain_key_after_a_dash_or_inside_braces(self):
        for good, old, new, line, shown in (
            ("l:\n  - a: 1\n", "a: 1", "#a: 1", 2, "'#a'"),
            ("l:\n  - a: 1\n", "a: 1", "#: 1", 2, "'#'"),
            ("l: {a: 1}\n", "a: 1", "#a: 1", 1, "'#a'"),
            ("l: {a: 1, b: 2}\n", "b: 2", "#b: 2", 1, "'#b'"),
        ):
            with self.subTest(text=edit(good, old, new)):
                err = self.refuses(good, old, new, line, "a '#' in a plain key")
                self.assertIn(shown, err.reason)


class QuestionMarkValueTest(YamlCase):
    def test_a_question_mark_indicator_as_a_block_value(self):
        for value in ("? a", "?"):
            with self.subTest(value=value):
                self.refuses("k: a\nm: 2\n", "k: a", f"k: {value}", 1, "cannot be '?' or start with '? '")

    def test_a_question_mark_indicator_as_a_sequence_item(self):
        for value in ("? a", "?"):
            with self.subTest(value=value):
                self.refuses("l:\n  - a\n  - b\n", "  - a", f"  - {value}", 2, "cannot be '?' or start with '? '")

    def test_a_question_mark_indicator_as_a_flow_entry(self):
        for good, old, new in (
            ("l: [a, b]\n", "[a, b]", "[? a, b]"),
            ("l: [a, b]\n", "[a, b]", "[a, ?]"),
            ("l: {k: v}\n", "{k: v}", "{k: ? v}"),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, 1, "cannot be '?' or start with '? '")


class TrailingColonTest(YamlCase):
    def test_a_plain_block_value_that_ends_in_a_colon(self):
        for bad in ("k: a:", "k: a:  ", "k: a::"):
            with self.subTest(text=bad):
                self.refuses("k: a\nm: 2\n", "k: a", bad, 1, "cannot end with ':'")

    def test_a_plain_value_that_ends_in_a_colon_inside_a_sequence_item_mapping(self):
        self.refuses("l:\n  - k: a\n", "k: a", "k: a:", 2, "cannot end with ':'")

    def test_a_plain_flow_entry_that_ends_in_a_colon(self):
        for bad in ("[a:]", "[a:, b]", "[a, b: ]", "[[a:], b]"):
            with self.subTest(flow=bad):
                self.refuses("l: [a, b]\n", "[a, b]", bad, 1, "cannot end with ':'")
        for bad in ("{k: a:}", "{k: a: }", "{k: [a:]}"):
            with self.subTest(flow=bad):
                self.refuses("l: {k: v}\n", "{k: v}", bad, 1, "cannot end with ':'")


class EdgeWhitespaceTest(YamlCase):
    """Only an ASCII space is trimmed. Any other whitespace at the edge of a plain scalar or key is refused."""

    def test_a_trailing_no_break_space_on_a_plain_value_is_refused_not_trimmed(self):
        self.refuses("sha256: abc\n", "abc", "abc\u00a0", 1,
                     "a plain value cannot start or end with the whitespace character U+00A0")

    def test_the_derived_set_of_unicode_spaces_holds_the_separators_these_tests_rely_on(self):
        for char in ("\u00a0", "\u1680", "\u2003", "\u2028", "\u2029", "\u202f", "\u205f", "\u3000"):
            self.assertIn(char, UNICODE_SPACES)
        self.assertGreaterEqual(len(UNICODE_SPACES), 18)
        self.assertTrue(all(ord(char) > 0x9F for char in UNICODE_SPACES), "a control character slipped into the set")

    def test_a_unicode_space_at_either_edge_of_a_plain_block_value_key_or_item(self):
        for char in UNICODE_SPACES:
            for good, old, news, line, noun in (
                ("k: a\nm: 2\n", "k: a", (f"k: a{char}", f"k: {char}a"), 1, "value"),
                ("k: a\nm: 2\n", "k: a", (f"k{char}: a", f"{char}k: a"), 1, "key"),
                ("l:\n  - a\n  - b\n", "  - a", (f"  - a{char}", f"  - {char}a"), 2, "value"),
            ):
                for bad in news:
                    with self.subTest(char=hex(ord(char)), text=bad):
                        self.refuses(good, old, bad, line,
                                     f"a plain {noun} cannot start or end with the whitespace character U+{ord(char):04X}")

    def test_a_unicode_space_at_either_edge_of_a_plain_flow_entry_or_key(self):
        for char in UNICODE_SPACES:
            for good, old, bad, noun in (
                ("l: [a, b]\n", "[a, b]", f"[a{char}, b]", "value"), ("l: [a, b]\n", "[a, b]", f"[a, {char}b]", "value"),
                ("l: [a, b]\n", "[a, b]", f"[{char}]", "value"), ("l: [a, b]\n", "[a, b]", f"[a, {char}]", "value"),
                ("l: {k: v}\n", "{k: v}", f"{{k: v{char}}}", "value"), ("l: {k: v}\n", "{k: v}", f"{{k{char}: v}}", "key"),
                ("l: {k: v}\n", "{k: v}", f"{{{char}k: v}}", "key"),
            ):
                with self.subTest(char=hex(ord(char)), text=bad):
                    self.refuses(good, old, bad, 1,
                                 f"a plain {noun} cannot start or end with the whitespace character U+{ord(char):04X}")

    def test_a_plain_scalar_with_a_different_space_at_each_edge_is_refused_for_the_one_at_its_start(self):
        for lead, trail in (("\u00a0", "\u3000"), ("\u3000", "\u00a0"), ("\u2003", "\u2028"), ("\u2029", "\u1680")):
            for good, old, bad, line, noun in (
                ("k: a\nm: 2\n", "k: a", f"k: {lead}a{trail}", 1, "value"),
                ("k: a\nm: 2\n", "k: a", f"{lead}k{trail}: a", 1, "key"),
                ("l:\n  - a\n  - b\n", "  - a", f"  - {lead}a{trail}", 2, "value"),
                ("l:\n  - a: 1\n", "a: 1", f"{lead}a{trail}: 1", 2, "key"),
                ("l: [a, b]\n", "[a, b]", f"[{lead}a{trail}, b]", 1, "value"),
                ("l: [a, b]\n", "[a, b]", f"[a, {lead}b{trail}]", 1, "value"),
                ("l: {k: v}\n", "{k: v}", f"{{k: {lead}v{trail}}}", 1, "value"),
                ("l: {k: v}\n", "{k: v}", f"{{{lead}k{trail}: v}}", 1, "key"),
            ):
                with self.subTest(lead=hex(ord(lead)), trail=hex(ord(trail)), text=bad):
                    err = self.refuses(good, old, bad, line, f"a plain {noun} cannot start or end with")
                    self.assertEqual(err.reason, f"a plain {noun} cannot start or end with the whitespace character "
                                                 f"U+{ord(lead):04X}; quote it")

    def test_a_unicode_space_at_the_edge_of_a_flow_mapping_entry_that_is_not_key_value_is_shown(self):
        for char in UNICODE_SPACES:
            for new, shown in (
                (f"{{{char},", char), (f"{{{char}}}", char),
                (f"{{k: v, {char}x}}", f"{char}x"), (f"{{x{char}}}", f"x{char}"),
            ):
                with self.subTest(char=hex(ord(char)), text=new):
                    self.refuses("l: {k: v}\n", "{k: v}", new, 1, f"'{shown}' is not a key/value pair")

    def test_a_unicode_space_after_a_closing_quote_is_named_not_swallowed(self):
        for char in UNICODE_SPACES:
            with self.subTest(char=hex(ord(char))):
                err = self.refuses("k: \"a\"\nm: 2\n", '"a"', f'"a"{char}', 1, "unexpected text after the closing quote")
                self.assertIn(repr(char), err.reason)

    def test_a_unicode_space_after_a_flow_collection_is_named_not_swallowed(self):
        for char in UNICODE_SPACES:
            for good, old, new, line in (
                ("l: [a]\nm: 2\n", "[a]", f"[a]{char}", 1),
                ("k: {a: 1}\nm: 2\n", "{a: 1}", f"{{a: 1}}{char}", 1),
                ("l:\n  - [a]\n  - b\n", "[a]", f"[a]{char}", 2),
            ):
                with self.subTest(char=hex(ord(char)), text=new):
                    err = self.refuses(good, old, new, line, "unexpected text after the flow collection")
                    self.assertIn(repr(char), err.reason)


class BlockScalarWhitespaceRefusalTest(YamlCase):
    def test_a_tab_on_a_blank_line_inside_a_block_scalar(self):
        for header in ("|", "|-", ">", ">-"):
            for blank in ("\t", "  \t", "    \t", "\t    "):
                with self.subTest(header=header, blank=blank):
                    good = f"a: {header}\n    one\n\n    two\nb: 1\n"
                    self.refuses(good, "one\n\n", f"one\n{blank}\n", 3, "a tab is not supported in a block scalar")

    def test_a_tab_on_a_blank_line_after_the_last_text_line_of_a_block_scalar(self):
        self.refuses("a: |\n    one\n\nb: 1\n", "one\n\n", "one\n  \t\n", 3, "a tab is not supported in a block scalar")

    def test_a_tab_on_a_whitespace_only_line_before_the_first_text_line_of_a_block_scalar(self):
        for header in ("|", "|-", ">", ">-"):
            for blank in ("\t", "  \t", "    \t", "\t    "):
                with self.subTest(header=header, blank=blank):
                    self.refuses(f"a: {header}\n    one\nb: 1\n", f"{header}\n", f"{header}\n{blank}\n", 2,
                                 "a tab is not supported in a block scalar")

    def test_a_tab_at_the_edge_of_a_line_that_looks_like_a_comment_inside_a_block_scalar(self):
        for header in ("|", "|-", ">", ">-"):
            for good, old, news, line in (
                (f"a: {header}\n  one\n  #x\nb: 1\n", "#x", ("#x\t", "\t#x", "#x \t", " \t#x"), 3),
                (f"a: {header}\n  #x\n  one\nb: 1\n", "#x", ("#x\t", "\t#x"), 2),
            ):
                for new in news:
                    with self.subTest(header=header, text=edit(good, old, new)):
                        self.refuses(good, old, new, line, "a tab is not supported in a block scalar")

    def test_a_leading_whitespace_only_line_with_more_spaces_than_the_text(self):
        for header in ("|", "|-", ">", ">-"):
            with self.subTest(header=header):
                self.refuses(f"a: {header}\n    one\nb: 1\n", f"{header}\n", f"{header}\n      \n", 2, "before the first text line")

    def test_a_leading_whitespace_only_line_with_exactly_one_space_more_than_the_text(self):
        for header in ("|", "|-", ">", ">-"):
            with self.subTest(header=header):
                self.refuses(f"a: {header}\n    \n    one\nb: 1\n", f"{header}\n    \n", f"{header}\n     \n", 2,
                             "before the first text line")

    def test_a_folded_scalar_with_a_whitespace_only_line_deeper_than_its_text(self):
        for good, old, new, line in (
            ("a: >\n    one\n    two\nb: 1\n", "one\n", "one\n      \n", 3),
            ("a: >-\n    one\nb: 1\n", "one\n", "one\n      \n", 3),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, line, "a more-indented line inside a folded block scalar")

    def test_a_folded_scalar_line_with_exactly_one_space_more_than_its_text_is_refused(self):
        for good, old, new, line in (
            ("a: >\n    one\n    two\nb: 1\n", "two", " two", 3),
            ("a: >-\n    one\n    two\nb: 1\n", "two", " two", 3),
            ("a: >\n    one\n    two\nb: 1\n", "one\n", "one\n     \n", 3),
            ("a: >-\n    one\n    two\nb: 1\n", "one\n", "one\n     \n", 3),
            ("a: >\n    one\nb: 1\n", "one\n", "one\n     \n", 3),
        ):
            with self.subTest(text=edit(good, old, new)):
                self.refuses(good, old, new, line, "a more-indented line inside a folded block scalar")

    def test_whitespace_only_lines_in_a_crlf_block_scalar_are_refused_as_they_are_with_lf(self):
        for header in ("|", ">"):
            with self.subTest(header=header, what="more spaces than the text"):
                self.refuses(f"a: {header}\r\n    \r\n    one\r\nb: 1\r\n", f"{header}\r\n    \r\n", f"{header}\r\n      \r\n", 2,
                             "before the first text line")
            with self.subTest(header=header, what="a tab"):
                self.refuses(f"a: {header}\r\n    one\r\n\r\n    two\r\nb: 1\r\n", "one\r\n\r\n", "one\r\n\t\r\n", 3,
                             "a tab is not supported in a block scalar")
        with self.subTest(what="deeper than the text of a folded scalar"):
            self.refuses("a: >\r\n    one\r\n    two\r\nb: 1\r\n", "one\r\n", "one\r\n      \r\n", 3,
                         "a more-indented line inside a folded block scalar")


class LateDocumentMarkerTest(YamlCase):
    def test_a_document_marker_with_content_after_line_one(self):
        for good, old, new in (
            ("# ok\nmodels: []\n", "models: []", "--- models: []"),
            ("a: 1\nb: 2\n", "b: 2", "--- b: 2"),
            ("a: 1\nb: 2\n", "b: 2", "... b"),
            ("a: 1\nb: 2\n", "b: 2", "--- # c"),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, 2, "document markers are not supported")

    def test_a_document_marker_with_content_in_a_crlf_file(self):
        self.refuses("a: 1\r\nb: 2\r\n", "b: 2", "--- b: 2", 2, "document markers are not supported")

    def test_a_document_marker_alone_on_its_line_in_a_crlf_file(self):
        """The marker is the whole line only once the CR of the line end is set aside."""
        for good, old, new, line in (
            ("a: 1\r\nb: 2\r\n", "b: 2", "---", 2),
            ("a: 1\r\nb: 2\r\n", "b: 2", "...", 2),
            ("a: 1\r\nb: 2\r\n", "b: 2", "  ---  ", 2),
            ("a: 1\r\nb: 2\r\n", "b: 2", "...  ", 2),
            ("a: 1\r\n", "a: 1\r\n", "---\r\na: 1\r\n", 1),
            ("a: 1\r\n", "a: 1\r\n", "...\r\na: 1\r\n", 1),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, line, "document markers are not supported")

    def test_a_marker_followed_by_a_non_ascii_space_is_not_a_document_marker(self):
        """Only an ASCII space after `---` or `...` makes the line a marker; any other space leaves it text, and the
        line is refused for what it is. With an ASCII space in the same place, each is refused as a document marker."""
        for char in UNICODE_SPACES:
            for good, old, template, line, reason, twin in (
                ("a: 1\nb: 2\n", "b: 2", "---{}", 2, "expected 'key: value', was '---{}'", "document markers"),
                ("a: 1\nb: 2\n", "b: 2", "...{}", 2, "expected 'key: value', was '...{}'", "document markers"),
                ("a: 1\nb: 2\n", "b: 2", "---{}x", 2, "expected 'key: value', was '---{}x'", "document markers"),
                ("a: 1\nb: 2\n", "b: 2", "...{}x", 2, "expected 'key: value', was '...{}x'", "document markers"),
                ("a: 1\nb: 2\n", "b: 2", "---{}# c", 2, "expected 'key: value', was '---{}# c'", "document markers"),
                ("a: 1\nb: 2\n", "a: 1", "...{}\na: 1", 1, "expected 'key: value', was '...{}'", "document markers"),
                ("a: 1\nb: 2\n", "a: 1", "---{}x: 1\nc: 3", 1, "multi-document YAML is not supported",
                 "multi-document YAML is not supported"),
                ("a: 1\r\nb: 2\r\n", "b: 2", "---{}", 2, "expected 'key: value', was '---{}'", "document markers"),
            ):
                with self.subTest(char=hex(ord(char)), text=template):
                    self.refuses(good, old, template.format(char), line, reason.format(char))
                    self.refuses(good, old, template.format(" "), line, twin)


class ControlCharacterRangeTest(YamlCase):
    def test_every_c0_control_character_except_tab_lf_cr(self):
        for code in range(0x20):
            if code in (0x09, 0x0A, 0x0D):
                continue
            with self.subTest(char=hex(code)):
                err = self.refuses("a: 1\nb: 2\nc: 3\n", "b: 2", f"b: x{chr(code)}y", 2, "control character")
                self.assertIn(f"U+{code:04X}", err.reason)

    def test_delete_and_the_c1_controls(self):
        for code in range(0x7F, 0xA0):
            with self.subTest(char=hex(code)):
                err = self.refuses("a: 1\nb: 2\nc: 3\n", "b: 2", f"b: x{chr(code)}y", 2, "control character")
                self.assertIn(f"U+{code:04X}", err.reason)

    def test_delete_and_a_c1_control_in_a_comment_a_quoted_scalar_and_a_block_scalar(self):
        for char in ("\x7f", "\x85", "\x9f"):
            for where, good, old, new, line in (
                ("comment", "a: 1\n# note\nb: 2\n", "# note", f"# no{char}te", 2),
                ("quoted scalar", 'a: "x"\n', '"x"', f'"x{char}"', 1),
                ("block scalar", "a: |\n  one\n  two\n", "  two", f"  t{char}wo", 3),
            ):
                with self.subTest(char=hex(ord(char)), where=where):
                    self.refuses(good, old, new, line, "control character")


class NonAsciiSpaceLineTest(YamlCase):
    """A line that holds only a non-ASCII space is text, not an empty line: it is refused where it stands.
    A line of ASCII spaces and tabs is empty, and reads as if it were not there."""

    def test_a_line_holding_only_a_non_ascii_space_is_refused_where_it_stands(self):
        nesting = "the indentation does not match any enclosing level"
        continued = "a value cannot continue on the next line"
        for char in UNICODE_SPACES:
            shown = f"expected 'key: value', was '{char}'"
            for label, good, old, new, line, reason in (
                ("the first line", "a: 1\n", "a: 1", f"{char}\na: 1", 1, shown),
                ("between two keys", "a: 1\nb: 2\n", "b: 2", f"{char}\nb: 2", 2, shown),
                ("after the last key", "a: 1\n", "a: 1\n", f"a: 1\n{char}\n", 2, shown),
                ("after comments and blank lines", "# c\n\na: 1\n", "a: 1", f"a: 1\n{char}", 4, shown),
                ("indented under a value", "a: 1\nb: 2\n", "b: 2", f"  {char}\nb: 2", 2, continued),
                ("inside a block mapping", "a:\n  b: 1\n  c: 2\n", "  c: 2", f"  {char}\n  c: 2", 3, shown),
                ("at the start of a block mapping", "a:\n  b: 1\n", "  b: 1", f"  {char}\n  b: 1", 2, shown),
                ("between the keys of a sequence item", "l:\n  - a: 1\n    b: 2\n", "    b: 2",
                 f"    {char}\n    b: 2", 3, shown),
                ("between two sequence items", "l:\n  - a\n  - b\n", "  - b", f"  {char}\n  - b", 3, nesting),
                ("before a sequence", "l:\n  - a\n", "  - a", f"  {char}\n  - a", 2, shown),
                ("in a CRLF file", "a: 1\r\nb: 2\r\n", "b: 2", f"{char}\r\nb: 2", 2, shown),
            ):
                with self.subTest(label, char=hex(ord(char))):
                    self.refuses(good, old, new, line, reason)

    def test_a_line_holding_only_ascii_spaces_and_tabs_is_empty_and_changes_nothing(self):
        for blank in ("", " ", "   ", "\t", " \t ", "\t\t"):
            for good, old, new in (
                ("a: 1\nb: 2\n", "b: 2", f"{blank}\nb: 2"),
                ("a: 1\n", "a: 1", f"{blank}\na: 1"),
                ("a:\n  b: 1\n  c: 2\n", "  c: 2", f"  {blank}\n  c: 2"),
                ("l:\n  - a: 1\n    b: 2\n", "    b: 2", f"    {blank}\n    b: 2"),
                ("l:\n  - a\n  - b\n", "  - b", f"  {blank}\n  - b"),
            ):
                with self.subTest(blank=blank, text=new):
                    self.assertEqual(loads(edit(good, old, new)), loads(good))


class DocumentMarkerOnLineOneTest(YamlCase):
    """`...` followed by a space is a document marker on line one as it is on any later line; `---` keeps its own reason."""

    def test_an_end_marker_followed_by_content_on_line_one(self):
        for good, old, new in (
            ("a: 1\n", "a: 1", "... a: 1"),
            ("a: x\n", "a:", "... :"),
            ("k: |\n  x\n", "k: |", "... k: |"),
            ("a:\n  b: 1\n", "a:", "... a:"),
            ("a: 1\r\nb: 2\r\n", "a: 1", "... a: 1"),
            ("a:\r\n  b: 1\r\n", "a:", "... a:"),
        ):
            with self.subTest(text=edit(good, old, new)):
                self.refuses(good, old, new, 1, "document markers are not supported")

    def test_three_dots_that_do_not_form_a_marker_stay_text_on_line_one(self):
        for text, expected in (
            ("...a: 1\n", {"...a": "1"}),
            ("...c: 3\n", {"...c": "3"}),
            ("...a: 1\r\nb: 2\r\n", {"...a": "1", "b": "2"}),
            ("  ... a: 1\n", {"... a": "1"}),
        ):
            with self.subTest(text=text):
                self.assertEqual(loads(text), expected)


if __name__ == "__main__":
    unittest.main()
