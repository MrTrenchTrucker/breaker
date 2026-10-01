"""What tools/yaml_subset.py refuses at the start of a plain key, value or entry, and in flow keys.

Covers the reserved indicator characters at each place a plain text may begin (SITES), characters that are not
printable, a leading question mark in a flow collection, the merge key, a curly bracket or a colon in a flow key, and
which reason wins when a line breaks two rules. Every refusal test asserts the reason and the line, and each edited
document is built from an accepted one (the control), with the edit checked to have landed.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import unittest

from yaml_support import YamlCase, YamlSubsetError, edit, load, loads, write_file


class CurlyBracketInFlowKeyTest(YamlCase):
    def test_a_curly_bracket_inside_a_plain_flow_key(self):
        for good in ("l: {k: v}\n", "l: [{k: v}]\n"):
            with self.subTest(document=good):
                self.refuses(good, "{k: v}", "{k{1}: v}", 1, "'{' is not allowed in a plain key in a flow mapping")

    def test_a_closing_square_bracket_inside_a_plain_flow_key(self):
        for good in ("a: 1\nl: {k: v}\n", "a: 1\nl: [{k: v}]\n"):
            with self.subTest(document=good):
                self.refuses(good, "{k: v}", "{k]: v}", 2, "unbalanced brackets")


class ColonAfterAKeyBoundaryTest(YamlCase):
    """A colon with no space after it does not end a key: the entry is refused for a plain or a quoted key in a flow
    mapping, and for a quoted key in a block line."""

    def test_a_flow_mapping_entry_whose_colon_has_no_space_after_it_is_refused(self):
        for new in ("{a:1}", "{k: 1, a:1}"):
            with self.subTest(text=new):
                err = self.refuses("a: 1\nl: {k: 1}\n", "{k: 1}", new, 2, "is not a key/value pair")
                self.assertIn("'a:1'", err.reason)

    def test_a_quoted_flow_key_whose_colon_has_no_space_after_it_is_refused(self):
        for new in ('{"a":1}', "{'a':1}", '{k: 1, "a":1}'):
            with self.subTest(text=new):
                self.refuses("a: 1\nl: {k: 1}\n", "{k: 1}", new, 2, "unexpected text after the closing quote")

    def test_a_quoted_flow_key_at_the_end_of_the_text_is_refused(self):
        for new in ('{"a"', "{'a'"):
            with self.subTest(text=new):
                err = self.refuses("a: 1\nl: {k: 1}\n", "{k: 1}", new, 2, "is not a key/value pair")
                self.assertIn(f"'{new[1:]}' is not a key/value pair", err.reason)

    def test_a_quoted_block_key_whose_colon_has_no_space_after_it_is_refused(self):
        for new in ('"a":b', "'a':b"):
            with self.subTest(text=new):
                err = self.refuses("a: 1\nk: 2\n", "k: 2", new, 2, "expected 'key: value'")
                self.assertIn(f"was '{new}'", err.reason)

    def test_a_quoted_scalar_followed_by_a_second_quoted_scalar_is_refused(self):
        for good, old, new in (
            ('k: "a"\nm: 1\n', '"a"', "\"a\"'b'"),
            ("k: 'a'\nm: 1\n", "'a'", "'a'\"b\""),
            ('l: ["a"]\nm: 1\n', '"a"', "\"a\"'b'"),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, 1, "unexpected text after the closing quote")


# -- how a plain key, value or entry may begin ----------------------------------------------

# the characters YAML reserves as indicators that no plain key, value or entry may start with; the others that reserve one
# (- ? : # & * ! [ { " ') are handled by their own rules or are text where they stand
INDICATORS = ",]}|>%@`"


class Site:
    """One place where a plain key, value or entry begins: a document that reads, and the text that goes at `old`."""

    def __init__(self, label, noun, good, old, read, structural="", claimed=""):
        self.label = label
        self.noun = noun  # what the refusal calls the text: "key" or "value"
        self.good = good
        self.old = old
        self.read = read  # what the document reads as when the text at `old` is the string it is given
        self.structural = structural  # characters the flow syntax takes before any rule about plain text sees them
        self.claimed = claimed  # characters an earlier rule, with its own reason, takes first


SITES = (
    Site("block key", "key", "a: 1\nk: 2\n", "k", lambda s: {"a": "1", s: "2"}),
    Site("sequence item key", "key", "l:\n  - k: 1\n", "k", lambda s: {"l": [{s: "1"}]}, claimed="|>"),
    Site("flow mapping key", "key", "a: 1\nl: {k: 1}\n", "k", lambda s: {"a": "1", "l": {s: "1"}}, structural=",]}"),
    Site("block value", "value", "a: 1\nk: v\n", "v", lambda s: {"a": "1", "k": s}, claimed="|>"),
    Site("block sequence item", "value", "l:\n  - v\n", "v", lambda s: {"l": [s]}, claimed="|>"),
    Site("flow sequence entry", "value", "a: 1\nl: [v]\n", "v", lambda s: {"a": "1", "l": [s]}, structural=",]}"),
    Site("flow mapping value", "value", "a: 1\nl: {k: v}\n", "v", lambda s: {"a": "1", "l": {"k": s}}, structural=",]}"),
)


class PlainStartTest(YamlCase):
    """A plain key, value or entry that starts with a reserved indicator is refused at each place SITES lists: a block
    key, the key of a sequence item's mapping, a flow mapping key, a block value, a block sequence item, a flow
    sequence entry and a flow mapping value."""

    def test_a_plain_key_value_or_entry_cannot_start_with_a_reserved_indicator(self):
        checked = 0
        for site in SITES:
            other = "value" if site.noun == "key" else "key"
            for char in INDICATORS:
                if char in site.structural or char in site.claimed:
                    continue
                checked += 1
                with self.subTest(site=site.label, char=char):
                    err = self.refuses(site.good, site.old, f"{char}a", 2, f"cannot start with '{char}'")
                    self.assertIn("quote it", err.reason)
                    self.assertIn(f"plain {site.noun}", err.reason)
                    self.assertNotIn(f"plain {other}", err.reason)
        self.assertEqual(checked, 41, "the matrix of sites and characters that reach the refusal changed size")

    def test_a_block_scalar_indicator_at_the_start_of_a_block_value_keeps_its_block_scalar_reason(self):
        for char in "|>":
            with self.subTest(char=char):
                err = self.refuses("a: 1\nk: v\n", "v", f"{char}a", 2, f"'{char}a' is not a supported block scalar header")
                self.assertNotIn("cannot start with", err.reason)

    def test_a_block_scalar_indicator_at_the_start_of_a_sequence_item_keeps_its_block_scalar_reason(self):
        for text in ("|a", ">a", "|a: 1", ">a: 1"):
            with self.subTest(item=text):
                err = self.refuses("l:\n  - v\n", "v", text, 2, "a block scalar cannot be a sequence item")
                self.assertNotIn("cannot start with", err.reason)

    def test_a_block_scalar_indicator_at_the_start_of_a_later_key_of_a_sequence_item_is_refused_as_a_key(self):
        """Only the first key of an item is claimed by the block scalar rule; a later key has no such rule in front."""
        for char in INDICATORS:
            for good, old, line in (
                ("l:\n  - a: 1\n    k: 2\n", "k", 3),
                ("l:\n  - a: 1\n    k: 2\n    m: 3\n", "m", 4),
                ("l:\n- a: 1\n  k: 2\n", "k", 3),
                ("l:\n  - x\n  - a: 1\n    k: 2\n", "k", 4),
            ):
                with self.subTest(char=char, text=edit(good, old, f"{char}b")):
                    err = self.refuses(good, old, f"{char}b", line,
                                       f"a plain key cannot start with '{char}'; quote it")
                    self.assertNotIn("block scalar", err.reason)

    def test_a_flow_mapping_key_cannot_start_with_a_dash_and_a_space(self):
        for good, old, new in (
            ("a: 1\nl: {k: 1}\n", "{k: 1}", "{- a: 1}"),
            ("a: 1\nl: {k: 1}\n", "{k: 1}", "{k: 1, - a: 2}"),
            ("a: 1\nl: [{k: 1}]\n", "{k: 1}", "{- a: 1}"),
        ):
            with self.subTest(text=new):
                self.refuses(good, old, new, 2, "cannot start with '- '")

    def test_a_flow_mapping_key_that_is_a_dash_followed_by_spaces_is_refused(self):
        """`{-: 1}` reads; with spaces between the dash and the colon the key is a dash and a space: refused."""
        for good, old, new in (
            ("l: {-: 1}\n", "-:", "- :"),
            ("l: {-: 1}\n", "-:", "-  :"),
            ("l: {a: 1, -: 2}\n", "-:", "- :"),
            ("l: {-:}\n", "-:", "- :"),
            ("l: [{-: 1}]\n", "-:", "- :"),
        ):
            with self.subTest(text=edit(good, old, new)):
                err = self.refuses(good, old, new, 1, "cannot start with '- '")
                self.assertIn("plain key", err.reason)

    def test_a_quoted_scalar_or_key_may_start_with_any_of_the_indicators(self):
        for site in SITES:
            for char in INDICATORS:
                for quote in ('"', "'"):
                    with self.subTest(site=site.label, char=char, quote=quote):
                        text = edit(site.good, site.old, f"{quote}{char}a{quote}")
                        self.assertEqual(loads(text), site.read(f"{char}a"))

    def test_the_indicators_are_refused_only_at_the_start(self):
        for site in SITES:
            for char in INDICATORS:
                if char in site.structural:
                    continue
                with self.subTest(site=site.label, char=char):
                    self.assertEqual(loads(edit(site.good, site.old, f"a{char}a")), site.read(f"a{char}a"))

    def test_a_dash_a_colon_or_a_question_mark_that_is_not_an_indicator_may_start_a_key_or_value(self):
        for text, expected in (
            ("-a: 1\n", {"-a": "1"}),
            (":a: 1\n", {":a": "1"}),
            ("-: 1\n", {"-": "1"}),
            ("k: ?a\n", {"k": "?a"}),
            ("k: -a\n", {"k": "-a"}),
            ("k: :a\n", {"k": ":a"}),
            ("l:\n  - ?a\n", {"l": ["?a"]}),
            ("l:\n  - -a\n", {"l": ["-a"]}),
            ("l: {-a: 1}\n", {"l": {"-a": "1"}}),
            ("l: {-: 1}\n", {"l": {"-": "1"}}),
            ("l: [-a]\n", {"l": ["-a"]}),
        ):
            with self.subTest(text=text):
                self.assertEqual(loads(text), expected)


class PrintableCharacterTest(YamlCase):
    """Beyond the control characters, YAML text may not hold U+FFFE, U+FFFF or a lone surrogate."""

    NOT_PRINTABLE = ("\ufffe", "\uffff", "\ud800", "\udbff", "\udc00", "\udfff")
    NEXT_TO_THEM = ("\ufffd", "\ue000", "\ud7ff", "\U00010000", "\U0010ffff", "\ufdd0")

    @staticmethod
    def placements(char):
        """(where, good document, text to replace, its replacement holding `char`, line, what it reads as if `char` is allowed)"""
        return (
            ("plain value", "a: 1\nb: 2\nc: 3\n", "b: 2", f"b: x{char}y", 2, {"a": "1", "b": f"x{char}y", "c": "3"}),
            ("plain key", "a: 1\nb: 2\nc: 3\n", "b: 2", f"b{char}: 2", 2, {"a": "1", f"b{char}": "2", "c": "3"}),
            ("whole-line comment", "a: 1\n# note\nb: 2\n", "# note", f"# no{char}te", 2, {"a": "1", "b": "2"}),
            ("quoted scalar", 'a: 1\nb: "x"\n', '"x"', f'"x{char}"', 2, {"a": "1", "b": f"x{char}"}),
            ("flow entry", "a: 1\nl: [x, y]\n", "[x, y]", f"[x, y{char}]", 2, {"a": "1", "l": ["x", f"y{char}"]}),
            ("block scalar line", "a: |\n  one\n  two\n", "  two", f"  t{char}wo", 3, {"a": f"one\nt{char}wo\n"}),
            ("value ending its line", "a: 1\nb: 2\nc: 3\n", "b: 2", f"b: 2{char}", 2,
             {"a": "1", "b": f"2{char}", "c": "3"}),
            ("value ending the last line", "a: 1\nb: 2\n", "b: 2\n", f"b: 2{char}\n", 2, {"a": "1", "b": f"2{char}"}),
            ("comment ending its line", "a: 1\n# note\nb: 2\n", "# note", f"# note{char}", 2, {"a": "1", "b": "2"}),
        )

    def test_the_code_points_yaml_forbids_beyond_the_control_range(self):
        for char in self.NOT_PRINTABLE:
            for where, good, old, new, line, _ in self.placements(char):
                with self.subTest(char=f"U+{ord(char):04X}", where=where):
                    err = self.refuses(good, old, new, line, "is not a printable YAML character")
                    self.assertIn(f"U+{ord(char):04X}", err.reason)
                    self.assertNotIn("control character", err.reason)

    def test_the_code_points_next_to_the_forbidden_ones_are_read(self):
        for char in self.NEXT_TO_THEM:
            for where, good, old, new, _, expected in self.placements(char):
                with self.subTest(char=f"U+{ord(char):04X}", where=where):
                    self.assertEqual(loads(edit(good, old, new)), expected)

    def test_a_file_holding_a_forbidden_code_point_is_refused_with_its_line(self):
        self.assertEqual(load(write_file(self, b"a: 1\nb: xy\nc: 3\n")), {"a": "1", "b": "xy", "c": "3"})
        for name, encoded in (("U+FFFE", b"\xef\xbf\xbe"), ("U+FFFF", b"\xef\xbf\xbf")):
            with self.subTest(char=name):
                path = write_file(self, b"a: 1\nb: x" + encoded + b"y\nc: 3\n")
                with self.assertRaises(YamlSubsetError) as caught:
                    load(path)
                err = caught.exception
                self.assertEqual((err.name, err.line), (path, 2))
                self.assertIn("is not a printable YAML character", err.reason)
                self.assertIn(name, err.reason)
                self.assertNotIn("control character", err.reason)

    def test_a_file_holding_the_code_point_next_to_them_is_read(self):
        path = write_file(self, b"a: 1\nb: x\xef\xbf\xbdy\nc: 3\n")
        self.assertEqual(load(path), {"a": "1", "b": "x\ufffdy", "c": "3"})

    def test_a_file_holding_an_encoded_surrogate_is_refused_as_invalid_utf8(self):
        self.assertEqual(load(write_file(self, b"a: 1\nb: xy\nc: 3\n")), {"a": "1", "b": "xy", "c": "3"})
        path = write_file(self, b"a: 1\nb: x\xed\xa0\x80y\nc: 3\n")
        with self.assertRaises(YamlSubsetError) as caught:
            load(path)
        self.assertEqual((caught.exception.name, caught.exception.line), (path, 2))
        self.assertIn("not valid UTF-8", caught.exception.reason)
        self.assertNotIn("printable", caught.exception.reason)


class FlowQuestionMarkTest(YamlCase):
    """A leading '?' in a flow collection starts a key to some readers and is text to others, so it has to be quoted."""

    REASON = "cannot start with '?' in a flow collection"

    def test_a_flow_sequence_entry_cannot_start_with_a_question_mark(self):
        for new, shown in (("[?a, b]", "?a"), ("[b, ?a]", "?a"), ("[?-]", "?-"), ("[?a b]", "?a b")):
            with self.subTest(text=new):
                self.refuses("a: 1\nl: [x, b]\n", "[x, b]", new, 2, f"{self.REASON} ('{shown}'); quote it")

    def test_a_flow_mapping_value_cannot_start_with_a_question_mark(self):
        for new, shown in (("{k: ?a}", "?a"), ("{j: 1, k: ?a}", "?a"), ("{k: ?a b}", "?a b"), ("{k: ?-}", "?-")):
            with self.subTest(text=new):
                self.refuses("a: 1\nl: {k: v}\n", "{k: v}", new, 2, f"{self.REASON} ('{shown}'); quote it")

    def test_a_flow_mapping_key_that_starts_with_a_question_mark_keeps_its_key_reason(self):
        for new, key in (("{?a: 1}", "'?a'"), ("{k: 1, ?c: 2}", "'?c'")):
            with self.subTest(text=new):
                err = self.refuses("a: 1\nl: {k: 1}\n", "{k: 1}", new, 2, "are not supported on a key")
                self.assertIn(key, err.reason)
                self.assertNotIn("in a flow collection", err.reason)

    def test_a_question_mark_that_does_not_start_an_entry_is_text(self):
        for text, expected in (
            ("k: ?a\n", {"k": "?a"}),
            ("l:\n  - ?a\n", {"l": ["?a"]}),
            ("l: [a, b?c]\n", {"l": ["a", "b?c"]}),
            ("l: {k: b?c}\n", {"l": {"k": "b?c"}}),
            ('l: ["?a", \'?b\']\n', {"l": ["?a", "?b"]}),
            ('l: {k: "?a", j: \'?b\'}\n', {"l": {"k": "?a", "j": "?b"}}),
            ('l: {"?a": 1}\n', {"l": {"?a": "1"}}),
        ):
            with self.subTest(text=text):
                self.assertEqual(loads(text), expected)


class MergeKeyTest(YamlCase):
    """A plain `<<` key merges a mapping into its parent in some YAML readers and is an ordinary key in others."""

    def test_a_plain_merge_key_is_refused(self):
        for where, good, old, new in (
            ("block key", "a: 1\nb: {a: 1}\n", "b:", "<<:"),
            ("block key with a space before the colon", "a: 1\nb: {a: 1}\n", "b:", "<< :"),
            ("sequence item key", "l:\n  - b: {a: 1}\n", "b:", "<<:"),
            ("sequence item key with a space before the colon", "l:\n  - b: {a: 1}\n", "b:", "<< :"),
            ("flow key", "a: 1\nl: {b: {a: 1}}\n", "b:", "<<:"),
            ("flow key with a space before the colon", "a: 1\nl: {b: {a: 1}}\n", "b:", "<< :"),
        ):
            with self.subTest(where=where):
                err = self.refuses(good, old, new, 2, "merge key")
                self.assertIn("quote it", err.reason)

    def test_a_quoted_merge_key_and_text_that_only_holds_the_two_characters_are_read(self):
        for text, expected in (
            ('a: 1\n"<<": 2\n', {"a": "1", "<<": "2"}),
            ("a: 1\n'<<': 2\n", {"a": "1", "<<": "2"}),
            ('l:\n  - "<<": 1\n', {"l": [{"<<": "1"}]}),
            ('l: {"<<": 1}\n', {"l": {"<<": "1"}}),
            ("a<<: 1\n", {"a<<": "1"}),
            ("<<a: 1\n", {"<<a": "1"}),
            ("l:\n  - a<<: 1\n", {"l": [{"a<<": "1"}]}),
            ("l: {<<a: 1}\n", {"l": {"<<a": "1"}}),
            ("k: <<\n", {"k": "<<"}),
            ("l: [<<]\n", {"l": ["<<"]}),
            ("l: {k: <<}\n", {"l": {"k": "<<"}}),
        ):
            with self.subTest(text=text):
                self.assertEqual(loads(text), expected)


class RuleOrderTest(YamlCase):
    """Which reason wins when a line breaks two rules: the rule that comes first reports; the other is not named."""

    BLOCK = "a: 1\nk: 2\n"
    FLOW = "a: 1\nl: {k: 1}\n"
    ANCHOR_KEY = "are not supported on a key"
    HASH_KEY = "a '#' in a plain key"
    EDGE_KEY = "a plain key cannot start or end with"
    START_KEY = "a plain key cannot start with"
    ANCHOR_VALUE = "anchors, aliases and tags are not supported"
    COMMENT = "trailing comments are not supported"
    EDGE_VALUE = "a plain value cannot start or end with"
    START_VALUE = "a plain value cannot start with"
    COLON_SPACE = "a plain value may not contain ': '"
    END_COLON = "cannot end with ':'"
    UNPRINTABLE = "is not a printable YAML character"
    UNPRINTABLE_CHARS = PrintableCharacterTest.NOT_PRINTABLE
    # a block value that starts with | or > is read as a block scalar header, by a rule of its own
    VALUE_STARTS = tuple(char for char in INDICATORS if char not in "|>")

    # (the two rules, the good document, the text in it to replace, the replacements that break both rules, the line
    #  refused, the reason that must be reported, the reason of the other rule that must not be)
    ROWS = (
        ("key: '&*!?' before '#'", BLOCK, "k: 2", tuple(f"{char}#: 2" for char in "&*!?"), 2, ANCHOR_KEY, HASH_KEY),
        ("key: '[{' before '#'", BLOCK, "k: 2", ("[#: 2", "{#: 2"), 2, "a mapping key cannot start with", HASH_KEY),
        ("key: an edge no-break space before '[{'", BLOCK, "k: 2", ("[\u00a0: 2", "{\u00a0: 2", "[a\u00a0: 2"), 2,
         EDGE_KEY, "a mapping key cannot start with"),
        ("key: '#' before a reserved start", BLOCK, "k: 2", tuple(f"{char}#: 2" for char in INDICATORS), 2,
         HASH_KEY, START_KEY),
        ("flow key: '#' before '- '", FLOW, "{k: 1}", ("{- #: 1}", "{k: 1, - #: 2}"), 2,
         HASH_KEY, "cannot start with '- '"),
        ("key: an edge no-break space before '&*!?'", BLOCK, "k: 2", tuple(f"{char}\u00a0: 2" for char in "&*!?"), 2,
         EDGE_KEY, ANCHOR_KEY),
        ("key: an edge no-break space before '#'", BLOCK, "k: 2", ("a#\u00a0: 2",), 2, EDGE_KEY, HASH_KEY),
        ("value: an edge no-break space before '&*!'", BLOCK, "k: 2", tuple(f"k: {char}\u00a0" for char in "&*!"), 2,
         EDGE_VALUE, ANCHOR_VALUE),
        ("value: an edge no-break space before '#'", BLOCK, "k: 2", ("k: a#\u00a0",), 2, EDGE_VALUE, COMMENT),
        ("value: an edge no-break space before '- '", BLOCK, "k: 2", ("k: - a\u00a0",), 2, EDGE_VALUE,
         "a plain value cannot start with '-' followed by a space"),
        ("value: '- ' before '#'", BLOCK, "k: 2", ("k: - #c",), 2,
         "a plain value cannot start with '-' followed by a space", COMMENT),
        ("value: '?' before '#'", BLOCK, "k: 2", ("k: ? #", "k: ? #c"), 2,
         "cannot be '?' or start with '? '", COMMENT),
        ("value: '?' before ': '", BLOCK, "k: 2", ("k: ? a: b", "k: ? : b"), 2,
         "cannot be '?' or start with '? '", COLON_SPACE),
        ("value: '#' before a reserved start", BLOCK, "k: 2", tuple(f"k: {char}#" for char in VALUE_STARTS), 2,
         COMMENT, START_VALUE),
        ("value: '#' before ': '", BLOCK, "k: 2", ("k: a#: b", "k: a: b#"), 2, COMMENT, COLON_SPACE),
        ("value: ': ' before a trailing ':'", BLOCK, "k: 2", ("k: a: b:",), 2, COLON_SPACE, END_COLON),
        ("value: ': ' before a reserved start", BLOCK, "k: 2", tuple(f"k: {char}a: b" for char in VALUE_STARTS), 2,
         COLON_SPACE, START_VALUE),
        ("value: a trailing ':' before a reserved start", BLOCK, "k: 2",
         tuple(f"k: {char}:" for char in VALUE_STARTS), 2, END_COLON, START_VALUE),
        ("document: a byte-order mark before U+FFFE, U+FFFF and a lone surrogate", "a: 1\n", "a: 1",
         tuple(f"\ufeffa: {char}" for char in UNPRINTABLE_CHARS), 1, "a byte-order mark", UNPRINTABLE),
        ("document: a byte-order mark before a control character", "a: 1\n", "a: 1", ("\ufeffa: \x01",), 1,
         "a byte-order mark", "control character"),
        ("document: a control character before U+FFFE, U+FFFF and a lone surrogate", "a: 1\nb: 2\n", "1\nb: 2",
         tuple(f"{char}\nb: \x01" for char in UNPRINTABLE_CHARS), 2, "control character", UNPRINTABLE),
        ("document: U+FFFE, U+FFFF and a lone surrogate before a lone carriage return",
         "a: 1\nb: 2\nd: 3\n", "b: 2\nd: 3", tuple(f"b\rc\nd: {char}" for char in UNPRINTABLE_CHARS), 3,
         UNPRINTABLE, "a carriage return outside a CRLF line end"),
    )

    def test_the_rule_that_comes_first_is_the_one_that_reports(self):
        for relation, good, old, replacements, line, first, second in self.ROWS:
            for new in replacements:
                with self.subTest(relation=relation, text=new):
                    err = self.refuses(good, old, new, line, first)
                    self.assertNotIn(second, err.reason)


if __name__ == "__main__":
    unittest.main()
