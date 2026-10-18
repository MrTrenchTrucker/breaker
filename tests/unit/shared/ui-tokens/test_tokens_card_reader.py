"""The card reader has to be able to say no.

The card (shared/modules/ui-tokens/AGENTS.md) is the specification the two files
are compared against, so the reader of it is held to the same standard: every way
a card can drift is refused with the reason. The reader self-tests use synthetic
text, never the live card, so a legitimate edit of a card value does not trip
them.

These tests are here rather than in test_tokens_card_conformance.py so that file
stays under the module's 500-line file cap; the reader itself lives in
tokens_support.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER

PALETTE_TOKENS = ts.PALETTE_TOKENS

SYNTHETIC_CARD = (
    "## Palette\n\n**Light mode**\n"
    "| Token | Value | Use |\n|---|---|---|\n"
    + "".join(f"| `{t}` | `#123456` | x |\n" for t in PALETTE_TOKENS)
    + "\n**Dark mode**\n"
    "| Token | Value | Use |\n|---|---|---|\n"
    + "".join(f"| `{t}` | `#654321` | x |\n" for t in PALETTE_TOKENS)
    + "\n## Typography\n"
    "- **Display/headings:** Alpha or Beta (condensed, wrapped\n  onto a second line).\n"
    "- **Body/UI:** Gamma (clean).\n"
    "- **Mono:** Delta for ids.\n"
    "- All open.\n\n"
    "## Style rules\n- hard edges (radius \u2264 4 px)\n- **Touch targets \u2265 48 px** on mobile;\n\n"
    "## State colors - the indicator\n\n"
    "| Token | Light | Dark | Meaning |\n|---|---|---|---|\n"
    "| `sent` (green) | `#111111` | `#222222` | a |\n"
    "| `warning` (orange) | `#333333` | `#444444` | b |\n"
    "| `danger` (red) | `#555555` | `#666666` | c |\n\n"
    "## LED\n- 12\u201316 segment display; fills.\n\n"
    "## Responsive\n- Breakpoints: **mobile** (< 640 px) \u00b7 **tablet** (640\u20131024 px) \u00b7\n"
    "  **desktop** (> 1024 px).\n"
)


class CardReaderTest(unittest.TestCase):
    """The card reader has to be able to say no."""

    def test_reader_returns_the_expected_shape_from_a_well_formed_card(self):
        card = ts.parse_card(SYNTHETIC_CARD)
        self.assertEqual(set(card["light"]), set(PALETTE_TOKENS))
        self.assertEqual(card["dark"]["bg"], "#654321")
        self.assertEqual(card["state"]["warning"], ("#333333", "#444444"))

    def test_reader_refuses_a_card_with_no_light_table(self):
        with self.assertRaises(AssertionError):
            ts.parse_card(SYNTHETIC_CARD.replace("**Light mode**", "**Bright mode**"))

    def test_reader_refuses_a_card_with_no_state_heading(self):
        with self.assertRaises(AssertionError):
            ts.parse_card(SYNTHETIC_CARD.replace("## State colors", "## Status colours"))

    def test_reader_refuses_a_table_missing_a_token(self):
        broken = SYNTHETIC_CARD.replace("| `trim` | `#123456` | x |\n", "", 1)
        with self.assertRaises(AssertionError):
            ts.parse_card(broken)

    def test_reader_refuses_a_value_that_is_not_hex(self):
        broken = SYNTHETIC_CARD.replace("`#123456`", "`red`", 1)
        with self.assertRaises(AssertionError):
            ts.parse_card(broken)

    def test_reader_refuses_a_duplicated_palette_row(self):
        broken = SYNTHETIC_CARD.replace(
            "| `trim` | `#123456` | x |\n", "| `trim` | `#123456` | x |\n| `bg` | `#0000FF` | x |\n", 1
        )
        with self.assertRaises(AssertionError) as caught:
            ts.parse_card(broken)
        self.assertIn("more than once", str(caught.exception))

    def test_reader_refuses_a_duplicated_state_row(self):
        broken = SYNTHETIC_CARD.replace(
            "| `danger` (red) | `#555555` | `#666666` | c |\n",
            "| `danger` (red) | `#555555` | `#666666` | c |\n| `sent` (again) | `#000000` | `#000000` | d |\n",
        )
        with self.assertRaises(AssertionError) as caught:
            ts.parse_card(broken)
        self.assertIn("more than once", str(caught.exception))

    def test_reader_refuses_a_state_table_missing_a_row(self):
        broken = SYNTHETIC_CARD.replace("| `warning` (orange) | `#333333` | `#444444` | b |\n", "")
        with self.assertRaises(AssertionError):
            ts.parse_card(broken)


class CardTypographyAndProseReaderTest(unittest.TestCase):
    def test_typography_bullets_are_read_with_their_wrapped_lines(self):
        bullets = ts.card_typography(SYNTHETIC_CARD)
        self.assertIn("Beta", bullets["Display/headings"])
        self.assertIn("second line", bullets["Display/headings"])
        self.assertNotIn("Delta", bullets["Body/UI"])
        self.assertIn("Delta", bullets["Mono"])

    def test_typography_reader_refuses_a_missing_section_or_bullet(self):
        with self.assertRaises(AssertionError):
            ts.card_typography(SYNTHETIC_CARD.replace("## Typography", "## Fonts"))
        with self.assertRaises(AssertionError):
            ts.card_typography(SYNTHETIC_CARD.replace("- **Mono:**", "- **Monospace:**"))

    def test_prose_numbers_are_read_from_their_sentences(self):
        self.assertEqual(
            ts.card_prose_numbers(SYNTHETIC_CARD),
            {
                "radius_max": 4, "touch_min": 48, "mobile_below": 640, "tablet_from": 640,
                "tablet_to": 1024, "desktop_above": 1024, "led_min": 12, "led_max": 16,
            },
        )

    def test_prose_reader_refuses_a_reworded_or_repeated_sentence(self):
        with self.assertRaises(AssertionError):
            ts.card_prose_numbers(SYNTHETIC_CARD.replace("radius \u2264 4 px", "radius under 4 px"))
        with self.assertRaises(AssertionError):
            ts.card_prose_numbers(SYNTHETIC_CARD + "\n- another (radius \u2264 6 px)\n")

    def test_prose_reader_follows_a_changed_number(self):
        edited = SYNTHETIC_CARD.replace("\u2265 48 px", "\u2265 56 px")
        self.assertEqual(ts.card_prose_numbers(edited)["touch_min"], 56)


class RepeatedCaptionTest(unittest.TestCase):
    """The card reader takes the table under a caption, so a second table under the
    same caption would pass unread. It refuses a repeated caption and names both lines.

    Each case appends a "Proposed revision" section to the synthetic card; that section
    starts on line 57 (the card is 53 lines, then a blank, a heading and a blank)."""

    @staticmethod
    def _with(extra):
        return SYNTHETIC_CARD + "\n## Proposed revision\n\n" + extra

    @staticmethod
    def _palette_table(caption, value):
        return (
            caption + "\n| Token | Value | Use |\n|---|---|---|\n"
            + "".join(f"| `{t}` | `{value}` | x |\n" for t in PALETTE_TOKENS)
        )

    def _refusal(self, call):
        with self.assertRaises(AssertionError) as caught:
            call()
        return str(caught.exception)

    def test_the_card_the_cases_start_from_reads_with_each_caption_once(self):
        # Control: the refusals below are for the repeated caption, not for a card
        # that could not be read to begin with.
        self.assertEqual(set(ts.parse_card(SYNTHETIC_CARD)["light"]), set(PALETTE_TOKENS))
        self.assertIn("Delta", ts.card_typography(SYNTHETIC_CARD)["Mono"])
        self.assertEqual(ts.card_bands(SYNTHETIC_CARD), ["mobile", "tablet", "desktop"])
        self.assertEqual(len(SYNTHETIC_CARD.splitlines()), 53)

    def test_a_repeated_light_mode_caption_is_refused_with_both_lines(self):
        message = self._refusal(lambda: ts.parse_card(self._with(self._palette_table("**Light mode**", "#0000FF"))))
        self.assertIn("the '**Light mode**' caption on 2 lines (3, 57)", message)

    def test_a_repeated_dark_mode_caption_is_refused_with_both_lines(self):
        message = self._refusal(lambda: ts.parse_card(self._with(self._palette_table("**Dark mode**", "#0000FF"))))
        self.assertIn("the '**Dark mode**' caption on 2 lines (16, 57)", message)

    def test_a_repeated_caption_is_found_through_trailing_spaces(self):
        message = self._refusal(lambda: ts.parse_card(self._with(self._palette_table("**Light mode**   ", "#0000FF"))))
        self.assertIn("(3, 57)", message)

    def test_a_repeated_state_colors_heading_is_refused_with_both_lines(self):
        extra = (
            "## State colors (proposed)\n\n| Token | Light | Dark | Meaning |\n|---|---|---|---|\n"
            "| `sent` | `#000001` | `#000002` | a |\n| `warning` | `#000003` | `#000004` | b |\n"
            "| `danger` | `#000005` | `#000006` | c |\n"
        )
        message = self._refusal(lambda: ts.parse_card(self._with(extra)))
        self.assertIn("the '## State colors' heading on 2 lines (40, 57)", message)

    def test_a_repeated_typography_heading_is_refused_with_both_lines(self):
        extra = "## Typography\n- **Display/headings:** Zeta\n- **Body/UI:** Eta\n- **Mono:** Theta\n"
        message = self._refusal(lambda: ts.card_typography(self._with(extra)))
        self.assertIn("the '## Typography' section on 2 lines (29, 57)", message)

    def test_a_repeated_breakpoints_bullet_is_refused_with_both_lines(self):
        message = self._refusal(lambda: ts.card_bands(self._with("- Breakpoints: **phone** (< 500 px).\n")))
        self.assertIn("2 '- Breakpoints:' bullets (lines 52, 57)", message)

    def test_a_missing_caption_is_still_refused_as_missing(self):
        message = self._refusal(lambda: ts.parse_card(SYNTHETIC_CARD.replace("**Light mode**", "**Bright mode**")))
        self.assertIn("the card has no '**Light mode**' caption", message)


if __name__ == "__main__":
    unittest.main()
