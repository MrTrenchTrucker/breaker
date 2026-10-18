"""The CSS reader has to be able to say no, and to say why: each refusal is checked
against its own reason, so a sheet refused for some other reason does not pass.

It reads `tokens.css`, the stylesheet side of the module's two files. The tests
are here rather than in test_tokens_card_conformance.py so that file stays under
the module's 500-line file cap; the reader itself lives in tokens_support.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts


class CssReaderTest(unittest.TestCase):

    WELL_FORMED = (
        ":root { --color-bg: #FFFFFF; --radius: 4px; }\n"
        "@media (prefers-color-scheme: dark) { :root:not([data-theme=\"light\"]) { --color-bg: #000000; } }\n"
        ":root[data-theme=\"dark\"] { --color-bg: #000000; }\n"
    )

    def edit(self, old, new, count=-1):
        """WELL_FORMED with `old` replaced by `new`; fails if `old` is not there, so an
        edit that matched nothing cannot hand the reader a good sheet to 'refuse'."""
        self.assertIn(old, self.WELL_FORMED, f"the edit target {old!r} is not in the well-formed sheet")
        return self.WELL_FORMED.replace(old, new, count)

    def refused(self, css, reason):
        """The reader raises, with the ui-tokens prefix and a message matching `reason`."""
        with self.assertRaises(AssertionError) as caught:
            ts.parse_css(css)
        message = str(caught.exception)
        self.assertTrue(message.startswith("ui-tokens: tokens.css"), message)
        self.assertRegex(message, reason)

    def test_reader_reads_a_well_formed_sheet(self):
        out = ts.parse_css(self.WELL_FORMED)
        self.assertEqual(out["root"], {"color-bg": "#FFFFFF", "radius": "4px"})
        self.assertEqual(out["dark_media"], {"color-bg": "#000000"})
        self.assertEqual(out["dark_attr"], {"color-bg": "#000000"})

    def test_reader_refuses_a_sheet_with_no_rules(self):
        self.refused("body { color: red; }", "has a rule for 'body'")
        self.refused("", "has no ")

    def test_reader_refuses_a_missing_block(self):
        self.refused(self.edit(':root[data-theme="dark"] { --color-bg: #000000; }\n', ""), "has no .*dark_attr")

    def test_reader_refuses_a_duplicate_root_rule(self):
        self.refused(self.WELL_FORMED + ":root { --x: 1; }\n", "more than one 'root' rule")

    def test_reader_refuses_a_qualified_root_selector(self):
        self.refused(self.edit(":root { --color-bg", "body :root { --color-bg", 1), "has a rule for 'body :root'")
        self.refused(self.edit(":root { --color-bg", "html:root { --color-bg", 1), "has a rule for 'html:root'")

    def test_reader_refuses_a_nested_at_rule_in_root(self):
        self.refused(
            self.edit("--radius: 4px;", "--radius: 4px; @media (min-width: 1px) { --y: 1; }", 1),
            "without a ';'|not a custom property",
        )

    def test_reader_refuses_a_declaration_outside_any_rule(self):
        self.refused(self.WELL_FORMED + "--stray: 1;\n", "text outside any rule")
        self.refused("--stray: 1;\n" + self.WELL_FORMED, "has a rule for '--stray")

    def test_reader_refuses_a_statement_that_is_not_a_custom_property(self):
        self.refused(self.edit("--radius: 4px;", "--radius: 4px; border-radius: 12px;", 1), "not a custom property.*border-radius")

    def test_reader_refuses_an_uppercase_or_underscored_custom_property_name(self):
        self.refused(self.edit("--radius:", "--Radius:", 1), "not a custom property.*--Radius")
        self.refused(self.edit("--radius:", "--radius_x:", 1), "not a custom property.*--radius_x")

    def test_reader_refuses_a_duplicate_declaration_in_a_block(self):
        self.refused(self.edit("--radius: 4px;", "--radius: 4px; --radius: 5px;", 1), "declares --radius twice")

    def test_reader_refuses_a_wrong_inner_selector_in_the_media_rule(self):
        self.refused(self.edit(':root:not([data-theme="light"])', ":root"), "must hold exactly one")

    def test_reader_refuses_a_last_statement_without_a_semicolon(self):
        self.refused(self.edit("--radius: 4px;", "--radius: 4px", 1), "without a ';'")

    def test_reader_refuses_an_unclosed_brace(self):
        self.refused(self.WELL_FORMED + ":root { --x: 1;\n", "unclosed")

    def test_reader_refuses_a_block_with_no_declaration(self):
        self.refused(self.edit(':root[data-theme="dark"] { --color-bg: #000000; }', ':root[data-theme="dark"] { }'), "no declaration")

    def test_reader_ignores_a_commented_out_declaration(self):
        out = ts.parse_css(self.edit("--radius: 4px;", "--radius: 4px; /* --hidden: 1; */", 1))
        self.assertNotIn("hidden", out["root"])

    def test_number_helpers_accept_ascii_digits_only(self):
        self.assertEqual(ts.css_px("48px"), 48)
        self.assertEqual(ts.css_int("12"), 12)
        for bad in ("\uff11\uff12px", "4 px", "4em", ""):
            with self.assertRaises(AssertionError):
                ts.css_px(bad)
        for bad in ("\uff11\uff12", "1.5", "-3", ""):
            with self.assertRaises(AssertionError):
                ts.css_int(bad)

    def test_palette_helper_refuses_a_value_that_is_not_a_six_digit_hex(self):
        for bad in ("#FFF", "#GGGGGG", "red", "#FFFFFFFF"):
            with self.assertRaises(AssertionError):
                ts.css_palette({"color-bg": bad})
        self.assertEqual(ts.css_palette({"color-bg": "#abcdef", "radius": "4px"}), {"bg": "#ABCDEF"})


if __name__ == "__main__":
    unittest.main()
