"""The bands tokens.kt names are the bands the card names, and only those.

`LayoutBand` is the Kotlin side of the card's Responsive bullet: the same three
names, in the same order. The tests are here rather than in
test_tokens_card_conformance.py so that file stays under the module's 500-line
file cap; the readers live in tokens_support.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER


class LayoutBandPinTest(unittest.TestCase):
    """The bands tokens.kt names are the bands the card names, and only those."""

    def test_the_live_layout_bands_are_the_cards_three_bands(self):
        card = [band.upper() for band in ts.card_bands(ts.read_text(ts.CARD))]
        source = ts.kotlin_layout_bands(ts.read_text(ts.KOTLIN))
        self.assertEqual(source, card, f"ui-tokens: the LayoutBand entries differ from the bands the card names ({CARD})")
        # Written out, so a card and an enum changed together still have to be changed here.
        self.assertEqual(source, ["MOBILE", "TABLET", "DESKTOP"], f"ui-tokens: the three bands are pinned ({CARD})")

    def test_the_reader_sees_an_added_renamed_reordered_or_removed_entry(self):
        def entries(body):
            return ts.kotlin_layout_bands(f"enum class LayoutBand {{ {body} }}\n")

        self.assertEqual(entries("MOBILE, TABLET, DESKTOP"), ["MOBILE", "TABLET", "DESKTOP"])
        self.assertEqual(entries("MOBILE, TABLET, DESKTOP, WIDE"), ["MOBILE", "TABLET", "DESKTOP", "WIDE"])
        self.assertEqual(entries("MOBILE, MEDIUM, DESKTOP"), ["MOBILE", "MEDIUM", "DESKTOP"])
        self.assertEqual(entries("TABLET, MOBILE, DESKTOP"), ["TABLET", "MOBILE", "DESKTOP"])
        self.assertEqual(entries("MOBILE, TABLET"), ["MOBILE", "TABLET"])

    def test_the_reader_reads_entries_on_separate_lines_and_ignores_comments(self):
        text = "enum class LayoutBand {\n    MOBILE, // narrow\n    TABLET,\n    /* DESKTOP, */\n    DESKTOP\n}\n"
        self.assertEqual(ts.kotlin_layout_bands(text), ["MOBILE", "TABLET", "DESKTOP"])

    def test_the_reader_refuses_a_missing_or_repeated_enum(self):
        with self.assertRaises(AssertionError) as caught:
            ts.kotlin_layout_bands("enum class ThemeMode { LIGHT, DARK }\n")
        self.assertIn("declares 0 'enum class LayoutBand' bodies", str(caught.exception))
        twice = "enum class LayoutBand { A }\nenum class LayoutBand { B }\n"
        with self.assertRaises(AssertionError) as caught:
            ts.kotlin_layout_bands(twice)
        self.assertIn("declares 2 'enum class LayoutBand' bodies", str(caught.exception))

    def test_the_reader_refuses_an_entry_with_a_body_or_a_lower_case_name(self):
        for body in ("MOBILE, tablet", "MOBILE(1), TABLET"):
            with self.subTest(body=body):
                with self.assertRaises(AssertionError) as caught:
                    ts.kotlin_layout_bands(f"enum class LayoutBand {{ {body} }}\n")
                self.assertIn("not plain UPPER_CASE names", str(caught.exception))

    def test_the_band_reader_follows_a_wrapped_breakpoints_bullet(self):
        card = "- Breakpoints: **one** (a) \u00b7\n  **two** (b) \u00b7\n  **three** (c).\n\n- other: **not-a-band**\n"
        self.assertEqual(ts.card_bands(card), ["one", "two", "three"])


if __name__ == "__main__":
    unittest.main()
