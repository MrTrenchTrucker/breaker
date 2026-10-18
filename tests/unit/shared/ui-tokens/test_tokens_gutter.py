"""The one fixed host gutter: where it is fixed, and what carries it.

The gutter is a single dp metric the Android host applies around every screen's
content. Apart from it, the only spacing-looking names are the five steps of the
spacing scale (spaceXsDp to spaceXlDp in tokens.kt, --space-xs to --space-xl in
tokens.css), and no other spacing-looking name may appear in tokens.css or
tokens.kt. It is not a CSS value: the gutter is an Android layout concern, so
tokens.css does not carry it, and tokens.kt does.

The card (shared/modules/ui-tokens/AGENTS.md) is the specification; the reader
self-test at the end uses synthetic text, not the live files, so a legitimate
edit of a value does not trip it.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import re
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER

# The names that look like a spacing value: the gutter and the five scale steps are
# the only ones the design gives, so every other name matching this is a scale the
# card does not give.
SPACING_WORD = re.compile(r"spac|gap|gutter|margin|padding", flags=re.I)
SPACE_KOTLIN = ["spaceXsDp", "spaceSDp", "spaceMDp", "spaceLDp", "spaceXlDp"]
SPACE_CSS = ["space-xs", "space-s", "space-m", "space-l", "space-xl"]


class GutterConformanceTest(unittest.TestCase):
    """The gutter is exactly one fixed metric, in one file, with one value."""

    def setUp(self):
        self.kotlin = ts.read_kotlin()

    def test_the_kotlin_gutter_is_the_host_gutter(self):
        self.assertEqual(
            self.kotlin["metrics"]["gutterDp"], 16,
            f"ui-tokens: tokens.kt metrics.gutterDp is {self.kotlin['metrics']['gutterDp']}, "
            f"the host applies a 16 dp gutter to every screen's root ({CARD})",
        )

    def test_the_kotlin_gutter_and_the_five_steps_are_the_only_spacing_metrics(self):
        metrics = self.kotlin["metrics"]
        spacing = sorted(name for name in metrics if SPACING_WORD.search(name))
        self.assertEqual(
            spacing, sorted(["gutterDp"] + SPACE_KOTLIN),
            f"ui-tokens: the spacing-looking metrics are {spacing}; only the one fixed "
            f"gutter gutterDp and the five scale steps are allowed ({CARD})",
        )

    def test_the_css_declares_no_gutter(self):
        # The gutter is an Android layout concern, not a web design token, so
        # tokens.css must not carry it under any name. Its spacing-looking names are
        # the five scale steps and nothing else.
        root = ts.read_css()["root"]
        spacing = sorted(name for name in root if SPACING_WORD.search(name))
        self.assertEqual(
            spacing, sorted(SPACE_CSS),
            f"ui-tokens: tokens.css root spacing-looking names are {spacing}; only the five "
            f"scale steps are allowed, and the gutter is an Android layout concern, not a "
            f"web design token ({CARD})",
        )


class GutterReaderTest(unittest.TestCase):
    """The gutter reader reads what the card gives, and refuses a gutter that is not one."""

    def test_the_reader_keeps_a_gutter_of_the_wrong_shape(self):
        # A gutter that is not a whole number is kept raw, not dropped, so the
        # declared-fields test names it instead of passing it over in silence.
        metrics = ts.parse_kotlin(synthetic_with_gutter("16.0"))["metrics"]
        self.assertEqual(metrics["gutterDp"], "16.0")

    def test_the_reader_reads_a_whole_gutter_as_a_number(self):
        metrics = ts.parse_kotlin(synthetic_with_gutter("16"))["metrics"]
        self.assertEqual(metrics["gutterDp"], 16)


def synthetic_with_gutter(gutter):
    """A well-formed tokens.kt-shaped text whose metrics carry the gutter [gutter]."""
    return (
        "object TruckingTokens {\n"
        + synthetic_palette("LIGHT")
        + synthetic_palette("DARK")
        + '    val type = TruckingType(\n        displayFamily = "A",\n        displayAlternateFamily = "B",\n'
        + '        bodyFamily = "C",\n        monoFamily = "D",\n    )\n'
        + "    val metrics = TruckingMetrics(\n        cornerRadiusDp = 4,\n        minTouchTargetDp = 48,\n"
        + "        mobileBreakpointDp = 640,\n        tabletBreakpointDp = 1024,\n"
        + f"        gutterDp = {gutter},\n    )\n"
        + "    val ledBar = TruckingLedBar(minSegments = 12, maxSegments = 16)\n}\n"
    )


def synthetic_palette(name):
    lines = []
    for i, field in enumerate(sorted(ts.EXPECTED_KOTLIN_PALETTE_FIELDS)):
        value = "0xFF%06X" % (0x101010 + i)
        lines.append(f"        {field} = TokenColor({value}.toInt()),")
    return f"    val {name} = TruckingPalette(\n" + "\n".join(lines) + "\n    )\n"


if __name__ == "__main__":
    unittest.main()
