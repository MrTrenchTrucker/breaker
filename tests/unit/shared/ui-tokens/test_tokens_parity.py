"""tokens.kt and tokens.css carry identical values.

The two files are hand-written, so nothing but this test keeps the phone app and
the website the same design. It compares them directly, without going through
the card, so it still catches drift in a value the card does not hold. It reads
them with the CSS and Kotlin readers in tokens_support, the same readers the card
comparison uses; those readers have their own refusal tests in
test_tokens_card_conformance.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import re
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER


class ParityTest(unittest.TestCase):
    def setUp(self):
        self.css = ts.read_css()
        self.kotlin = ts.read_kotlin()

    def _kotlin_colors(self, palette):
        return {ts.kebab(k): v["hex"] for k, v in self.kotlin["palettes"][palette].items()}

    def _compare_palette(self, css_block, palette):
        css = ts.css_palette(self.css[css_block])
        kt = self._kotlin_colors(palette)
        self.assertEqual(
            set(css), set(kt),
            f"ui-tokens: tokens.css {css_block} and tokens.kt {palette} declare different "
            f"tokens: only in css {sorted(set(css) - set(kt))}, only in kotlin "
            f"{sorted(set(kt) - set(css))} ({CARD})",
        )
        for token in sorted(css):
            with self.subTest(token=token):
                self.assertEqual(
                    css[token], kt[token],
                    f"ui-tokens: {token} differs between tokens.css {css_block} ({css[token]}) "
                    f"and tokens.kt {palette} ({kt[token]}); the app and the website no "
                    f"longer look the same ({CARD})",
                )

    def test_light_palette_matches(self):
        self._compare_palette("root", "LIGHT")

    def test_dark_palette_matches_under_system_preference(self):
        self._compare_palette("dark_media", "DARK")

    def test_dark_palette_matches_under_explicit_theme(self):
        self._compare_palette("dark_attr", "DARK")

    def test_the_two_css_dark_blocks_are_identical(self):
        self.assertEqual(
            self.css["dark_media"], self.css["dark_attr"],
            "ui-tokens: the css dark set differs between the system-preference block and "
            "the explicit data-theme block; a user would see two different dark themes",
        )

    def test_metrics_match(self):
        root = self.css["root"]
        metrics = self.kotlin["metrics"]
        pairs = [
            ("radius", ts.css_px(root["radius"]), metrics["cornerRadiusDp"]),
            ("touch target", ts.css_px(root["touch-target-min"]), metrics["minTouchTargetDp"]),
            ("mobile breakpoint", ts.css_px(root["breakpoint-mobile"]), metrics["mobileBreakpointDp"]),
            ("tablet breakpoint", ts.css_px(root["breakpoint-tablet"]), metrics["tabletBreakpointDp"]),
        ]
        for name, css_value, kotlin_value in pairs:
            with self.subTest(metric=name):
                self.assertEqual(
                    css_value, kotlin_value,
                    f"ui-tokens: the {name} is {css_value} in tokens.css and {kotlin_value} in "
                    f"tokens.kt ({CARD})",
                )

    def test_led_segment_range_matches(self):
        root = self.css["root"]
        self.assertEqual(ts.css_int(root["led-segments-min"]), self.kotlin["led"]["minSegments"])
        self.assertEqual(ts.css_int(root["led-segments-max"]), self.kotlin["led"]["maxSegments"])

    def test_font_families_match(self):
        root = self.css["root"]
        type_ = self.kotlin["type"]
        self.assertEqual(
            ts.css_font_families(root["font-display"]),
            [type_["displayFamily"], type_["displayAlternateFamily"]],
            "ui-tokens: the display font stack differs between tokens.css and tokens.kt",
        )
        self.assertEqual(ts.css_font_families(root["font-body"]), [type_["bodyFamily"]])
        self.assertEqual(ts.css_font_families(root["font-mono"]), [type_["monoFamily"]])

    def test_each_css_font_stack_ends_with_a_generic_family(self):
        for var in ("font-display", "font-body", "font-mono"):
            with self.subTest(var=var):
                stack = ts.css_font_stack(self.css["root"][var])
                self.assertIn(
                    stack[-1], ts.GENERIC_FONT_KEYWORDS,
                    f"ui-tokens: --{var} is {self.css['root'][var]!r}; the last entry of a font "
                    f"stack must be a generic family so text still renders without the font",
                )
                self.assertEqual(
                    [e for e in stack[:-1] if e in ts.GENERIC_FONT_KEYWORDS], [],
                    f"ui-tokens: --{var} has a generic family before the last entry",
                )


def differences(css, kotlin):
    """The tokens whose value differs between the parsed css and the parsed Kotlin,
    over the light palette and the dark one."""
    out = []
    for palette, block in (("LIGHT", "root"), ("DARK", "dark_media")):
        from_css = ts.css_palette(css[block])
        from_kotlin = {ts.kebab(k): v["hex"] for k, v in kotlin["palettes"][palette].items()}
        out += [t for t in from_css if from_css[t] != from_kotlin.get(t)]
    return out


class ParityInstrumentTest(unittest.TestCase):
    """The comparison can say no: a one-sided edit is seen."""

    def test_a_one_sided_color_edit_is_a_mismatch(self):
        css = ts.parse_css(ts.read_text(ts.CSS))
        text = ts.read_text(ts.KOTLIN)
        # Edit the first color literal of the Kotlin file whatever its value is: change its
        # last hex digit, and expect exactly that token to disagree with the css.
        match = re.search(r"(\w+)\s*=\s*TokenColor\((0x[0-9A-Fa-f]{8})\.toInt\(\)\)", text)
        self.assertIsNotNone(match, "no TokenColor literal found in tokens.kt")
        literal = match.group(2)
        edited = literal[:-1] + ("1" if literal[-1] != "1" else "0")
        kt = ts.parse_kotlin(text[:match.start(2)] + edited + text[match.end(2):])
        self.assertEqual(differences(css, kt), [ts.kebab(match.group(1))])

    def test_a_one_sided_css_edit_is_a_mismatch(self):
        text = ts.read_text(ts.CSS)
        match = re.search(r"--color-([a-z-]+):\s*#([0-9A-Fa-f]{6});", text)
        self.assertIsNotNone(match, "no color custom property found in tokens.css")
        digits = match.group(2)
        edited = digits[:-1] + ("1" if digits[-1] != "1" else "0")
        css = ts.parse_css(text[:match.start(2)] + edited + text[match.end(2):])
        self.assertEqual(differences(css, ts.read_kotlin()), [match.group(1)])


class FontOrderTest(unittest.TestCase):
    """The display family listed first is the first choice; the alternate follows."""

    def test_display_stack_names_anton_first_then_oswald_in_both_files(self):
        self.assertEqual(ts.css_font_families(ts.read_css()["root"]["font-display"]), ["Anton", "Oswald"])
        type_ = ts.read_kotlin()["type"]
        self.assertEqual((type_["displayFamily"], type_["displayAlternateFamily"]), ("Anton", "Oswald"))


if __name__ == "__main__":
    unittest.main()
