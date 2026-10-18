"""The Kotlin reader has to be able to say no, and to read the compiler's way.

It reads `tokens.kt`, the Kotlin side of the module's two files: the palettes,
the metrics, the type families and the LED range, and how `0xAARRGGBB.toInt()`
becomes a signed Int. The tests are here rather than in
test_tokens_card_conformance.py so that file stays under the module's 500-line
file cap; the reader itself lives in tokens_support.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts

EXPECTED_KOTLIN_PALETTE_FIELDS = ts.EXPECTED_KOTLIN_PALETTE_FIELDS


def synthetic_kotlin(remove=None, extra_type_line="", comment_out=None, drop_to_int=False):
    """A minimal tokens.kt-shaped text. `remove` drops a palette field from LIGHT,
    `comment_out` comments one out, `drop_to_int` breaks one literal's shape."""
    def palette(name, remove=None, comment_out=None, drop_to_int=False):
        lines = []
        for i, field in enumerate(sorted(EXPECTED_KOTLIN_PALETTE_FIELDS)):
            if field == remove:
                continue
            value = "0xFF%06X" % (0x101010 + i)
            literal = f"TokenColor({value}.toInt())"
            if drop_to_int and i == 0:
                literal = f"TokenColor({value})"
            line = f"        {field} = {literal},"
            if field == comment_out:
                line = "        // " + line.strip()
            lines.append(line)
        return f"    val {name} = TruckingPalette(\n" + "\n".join(lines) + "\n    )\n"

    return (
        "object TruckingTokens {\n"
        + palette("LIGHT", remove, comment_out, drop_to_int)
        + palette("DARK")
        + '    val type = TruckingType(\n        displayFamily = "A",\n        displayAlternateFamily = "B",\n'
        + '        bodyFamily = "C",\n        monoFamily = "D",\n' + extra_type_line + "    )\n"
        + "    val metrics = TruckingMetrics(\n        cornerRadiusDp = 4,\n        minTouchTargetDp = 48,\n"
        + "        mobileBreakpointDp = 640,\n        tabletBreakpointDp = 1024,\n    )\n"
        + "    val ledBar = TruckingLedBar(minSegments = 12, maxSegments = 16)\n}\n"
    )


class KotlinReaderTest(unittest.TestCase):

    def test_signed_int_wrap_matches_the_compiler(self):
        self.assertEqual(ts.argb_from_kotlin_literal("0xFFFFFFFF"), -1)
        self.assertEqual(ts.argb_from_kotlin_literal("0xFF1E7A46"), -14779834)
        self.assertEqual(ts.argb_from_kotlin_literal("0x7F000000"), 0x7F000000)

    def test_reader_reads_a_well_formed_file(self):
        parsed = ts.parse_kotlin(synthetic_kotlin())
        self.assertEqual(set(parsed["palettes"]), {"LIGHT", "DARK"})
        self.assertEqual(set(parsed["palettes"]["LIGHT"]), EXPECTED_KOTLIN_PALETTE_FIELDS)
        self.assertEqual(parsed["metrics"]["tabletBreakpointDp"], 1024)
        self.assertEqual(parsed["type"]["monoFamily"], "D")
        self.assertEqual(parsed["led"], {"minSegments": 12, "maxSegments": 16})

    def test_reader_refuses_a_literal_of_the_wrong_shape(self):
        with self.assertRaises(AssertionError):
            ts.parse_kotlin(synthetic_kotlin(drop_to_int=True))

    def test_reader_refuses_a_file_without_the_palettes(self):
        with self.assertRaises(AssertionError):
            ts.parse_kotlin("package x\n")

    def test_reader_keeps_a_wrongly_shaped_argument_so_it_is_named(self):
        type_ = ts.parse_kotlin(synthetic_kotlin(extra_type_line="        extraField = 0.08,\n"))["type"]
        self.assertEqual(type_["extraField"], "0.08")

    def test_a_family_name_is_matched_as_a_whole_word(self):
        self.assertTrue(ts.names_word("body: Inter (clean)", "Inter"))
        self.assertFalse(ts.names_word("body: Inter (clean)", "Inte"))
        self.assertFalse(ts.names_word("body: Inter (clean)", "nter"))

    def test_reader_ignores_a_commented_out_value(self):
        light = ts.parse_kotlin(synthetic_kotlin(comment_out="bg"))["palettes"]["LIGHT"]
        self.assertNotIn("bg", light, "a commented-out value must not be read as a token")
        self.assertEqual(len(light), len(EXPECTED_KOTLIN_PALETTE_FIELDS) - 1)

    def test_reader_sees_a_palette_field_that_is_missing(self):
        light = ts.parse_kotlin(synthetic_kotlin(remove="trim"))["palettes"]["LIGHT"]
        self.assertNotIn("trim", light)

    def test_declarations_are_read_by_name_and_comments_are_ignored(self):
        text = (
            "// val hidden = 1\n/* class Ghost */\n"
            "data class Box(val width: Int)\nobject Holder { val spacing = 8\n fun grow() = 1 }\n"
        )
        names, types = ts.kotlin_declarations(text)
        self.assertEqual(names, {"width", "spacing", "grow"})
        self.assertEqual(types, {"Box", "Holder"})


if __name__ == "__main__":
    unittest.main()
