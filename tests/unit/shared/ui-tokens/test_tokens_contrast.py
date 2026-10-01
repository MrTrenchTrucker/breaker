"""WCAG AA contrast for every color that can be text, in both modes.

Two tiers, by what the color is used for:

* text roles must reach 4.5:1 (normal text): text, text-muted, primary,
  primary-hover, danger, warning, accent and sent, each on bg and on surface;
* non-text marks must reach 3:1: trim, on bg and on surface.

warning and accent are drawn as text by the app, so they are in the text tier
even though the card lists them as highlight and state colors.

One table, `all_pairs()`, lists every (mode, foreground, background, minimum).
Everything below is driven from it. Ratios are computed from tokens.css, in
tokens_support; parity with tokens.kt is a separate test. Contrast lives in this
Python tier only.

The gate is `test_pairs_meet_their_minimum`: every pair that is not pinned must
reach its minimum, so a new failing pair turns the run red and names the mode,
the two tokens and the ratio.

A pair that is below its minimum today is pinned in KNOWN_BELOW_MINIMUM, with a
floor: the ratio it measures today, rounded down. Each pin gets an
`unittest.expectedFailure` test, generated from that dict so a marker and its
entry cannot drift apart, whose message carries the measured ratio. A pinned pair
that starts to pass is an "unexpected success", which fails the run: when a value
is changed so the pair passes, its entry must come out, and it is then under the
plain gate. A pinned pair that gets worse than its floor fails
`test_known_below_minimum_pairs_do_not_get_worse`. An expected failure cannot tell
a low ratio from an unrelated crash, so the exact-set test asserts that the pins
are exactly the pairs below their minimum.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER

TEXT_MIN = 4.5
NON_TEXT_MIN = 3.0

# The colors that can be text, each on both surfaces.
TEXT_TOKENS = ["text", "text-muted", "primary", "primary-hover", "danger", "warning", "accent", "sent"]
# Marks that are not text: borders and stripes.
NON_TEXT_TOKENS = ["trim"]
SURFACES = ["bg", "surface"]
MODES = ("light", "dark")

# (mode, foreground token, background token) -> floor, for the pairs measured
# below their minimum. The set is empty: the light warning and accent values were
# darkened until every pair passes the plain gate, and the pins came out with
# them. The mechanism stays, and the exact-set test below keeps it honest: a pair
# that falls below its minimum again is a regression, and the run fails naming it.
KNOWN_BELOW_MINIMUM = {}


def meets(ratio, minimum):
    return ratio >= minimum


def all_pairs():
    """Every (mode, fg, bg, minimum) the gate covers."""
    for mode in MODES:
        for fg in TEXT_TOKENS:
            for bg in SURFACES:
                yield mode, fg, bg, TEXT_MIN
        for fg in NON_TEXT_TOKENS:
            for bg in SURFACES:
                yield mode, fg, bg, NON_TEXT_MIN


MINIMUM = {(mode, fg, bg): minimum for mode, fg, bg, minimum in all_pairs()}


def palettes():
    css = ts.read_css()
    return {"light": ts.css_palette(css["root"]), "dark": ts.css_palette(css["dark_media"])}


def describe(palette_map, mode, fg, bg, minimum):
    p = palette_map[mode]
    ratio = ts.contrast_ratio(p[fg], p[bg])
    return (
        ratio,
        f"ui-tokens: {mode} {fg} {p[fg]} on {bg} {p[bg]} measures {ratio:.2f}:1, "
        f"below the {minimum}:1 WCAG AA minimum ({CARD}, Invariants)",
    )


class ContrastInstrumentTest(unittest.TestCase):
    """The contrast checker can say bad news, and agrees with known values."""

    def test_black_on_white_is_21(self):
        self.assertAlmostEqual(ts.contrast_ratio("#000000", "#FFFFFF"), 21.0, places=6)

    def test_a_color_on_itself_is_1(self):
        self.assertAlmostEqual(ts.contrast_ratio("#1E7A46", "#1E7A46"), 1.0, places=9)

    def test_order_of_the_arguments_does_not_matter(self):
        a = ts.contrast_ratio("#1E7A46", "#FFFFFF")
        self.assertAlmostEqual(a, ts.contrast_ratio("#FFFFFF", "#1E7A46"), places=9)

    def test_the_well_known_grey_boundary_is_flagged_and_accepted_correctly(self):
        # #767676 on white is the darkest grey that reaches 4.5:1; #777777 just misses.
        self.assertFalse(meets(ts.contrast_ratio("#777777", "#FFFFFF"), TEXT_MIN))
        self.assertTrue(meets(ts.contrast_ratio("#767676", "#FFFFFF"), TEXT_MIN))


class ContrastTableTest(unittest.TestCase):
    """The table covers every color that can be text, so the gate cannot go quiet by omission."""

    def test_the_table_gates_every_color_token_except_the_two_surfaces(self):
        gated = set(TEXT_TOKENS) | set(NON_TEXT_TOKENS)
        self.assertEqual(
            gated, set(ts.COLOR_TOKENS) - set(SURFACES),
            f"ui-tokens: the contrast table gates {sorted(gated)}; the color tokens are "
            f"{sorted(ts.COLOR_TOKENS)} ({CARD})",
        )

    def test_the_text_tier_is_exactly_the_colors_the_app_can_draw_as_text(self):
        # The lists themselves, written out, not the constants the table was built from.
        self.assertEqual(
            set(TEXT_TOKENS),
            {"text", "text-muted", "primary", "primary-hover", "danger", "warning", "accent", "sent"},
        )
        self.assertEqual(set(NON_TEXT_TOKENS), {"trim"})
        self.assertEqual(len(TEXT_TOKENS), len(set(TEXT_TOKENS)), "a text token is listed twice")

    def test_every_gated_token_is_checked_on_both_surfaces_in_both_modes(self):
        # Written out, not built from MODES / SURFACES / the token lists: a mode or a
        # surface dropped from those would otherwise drop out of this expectation too.
        gated = ["text", "text-muted", "primary", "primary-hover", "danger", "warning", "accent", "sent", "trim"]
        expected = {(mode, fg, bg) for mode in ("light", "dark") for fg in gated for bg in ("bg", "surface")}
        self.assertEqual(len(expected), 36)
        self.assertEqual(set(MINIMUM), expected)
        self.assertEqual(len(list(all_pairs())), len(expected), "a pair is listed twice")

    def test_text_tokens_are_held_to_the_text_minimum(self):
        for (mode, fg, bg), minimum in MINIMUM.items():
            with self.subTest(mode=mode, fg=fg, bg=bg):
                # The numbers themselves, not the constants the table was built from.
                self.assertEqual(minimum, 4.5 if fg in TEXT_TOKENS else 3.0)

    def test_every_pin_names_a_pair_in_the_table(self):
        self.assertEqual(
            sorted(set(KNOWN_BELOW_MINIMUM) - set(MINIMUM)), [],
            "ui-tokens: KNOWN_BELOW_MINIMUM pins a pair the contrast table does not list",
        )


class ContrastTest(unittest.TestCase):
    def setUp(self):
        self.palettes = palettes()

    def test_pairs_meet_their_minimum(self):
        for mode, fg, bg, minimum in all_pairs():
            if (mode, fg, bg) in KNOWN_BELOW_MINIMUM:
                continue
            with self.subTest(mode=mode, fg=fg, bg=bg):
                ratio, message = describe(self.palettes, mode, fg, bg, minimum)
                self.assertTrue(meets(ratio, minimum), message)

    def test_known_below_minimum_is_exactly_the_pairs_below_minimum(self):
        below = set()
        for mode, fg, bg, minimum in all_pairs():
            ratio, _ = describe(self.palettes, mode, fg, bg, minimum)
            if not meets(ratio, minimum):
                below.add((mode, fg, bg))
        self.assertEqual(
            below, set(KNOWN_BELOW_MINIMUM),
            f"ui-tokens: pairs below their minimum now: {sorted(below)}; pinned as expected "
            f"failures: {sorted(KNOWN_BELOW_MINIMUM)}. Pin a new failure, or remove the pin "
            f"for a pair that now passes.",
        )

    def test_known_below_minimum_pairs_do_not_get_worse(self):
        for (mode, fg, bg), floor in KNOWN_BELOW_MINIMUM.items():
            with self.subTest(mode=mode, fg=fg, bg=bg):
                p = self.palettes[mode]
                ratio = ts.contrast_ratio(p[fg], p[bg])
                self.assertGreaterEqual(
                    ratio, floor,
                    f"ui-tokens: {mode} {fg} {p[fg]} on {bg} {p[bg]} measures {ratio:.2f}:1, "
                    f"worse than the {floor}:1 it is pinned at ({CARD}, Invariants)",
                )


def _pin_test(mode, fg, bg):
    """An expected-failure test for one pinned pair: it asserts the pair reaches its
    minimum, which it does not today. When it does, the run reports an unexpected success."""
    minimum = MINIMUM[(mode, fg, bg)]

    def test(self):
        ratio, message = describe(self.palettes, mode, fg, bg, minimum)
        self.assertTrue(meets(ratio, minimum), message)

    test.__name__ = f"test_{mode}_{fg}_on_{bg}_reaches_its_minimum".replace("-", "_")
    return unittest.expectedFailure(test)


for _mode, _fg, _bg in KNOWN_BELOW_MINIMUM:
    if (_mode, _fg, _bg) in MINIMUM:
        _test = _pin_test(_mode, _fg, _bg)
        setattr(ContrastTest, _test.__name__, _test)


if __name__ == "__main__":
    unittest.main()
