"""Every token the module card publishes is present, with the exact value.

The card (shared/modules/ui-tokens/AGENTS.md) is the specification. These tests
read its tables, its typography bullets and the numbers in its prose off disk and
compare tokens.css and tokens.kt against them, so a value changed in a file
without the card, or in the card without the files, is a red run that names the
token. They never skip: a missing card or table is a failure, because a gate that
quietly stops gating is worse than none.

They also refuse extras: a file that declares a token the card does not give
fails, so no value is invented.

The reader tests at the end use synthetic text, not the live files, so a
legitimate edit of a token value does not trip them.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER


class CardToCssTest(unittest.TestCase):
    def setUp(self):
        self.card = ts.read_card()
        self.css = ts.read_css()

    def _expect(self, mode):
        expected = dict(self.card[mode])
        index = 0 if mode == "light" else 1
        expected["sent"] = self.card["state"]["sent"][index]
        expected["warning"] = self.card["state"]["warning"][index]
        return expected

    def _check_block(self, block_name, mode):
        expected = self._expect(mode)
        actual = ts.css_palette(self.css[block_name])
        self.assertEqual(
            set(actual), set(expected),
            f"ui-tokens: tokens.css {block_name} declares color tokens {sorted(actual)}, "
            f"the card gives {sorted(expected)} ({CARD})",
        )
        for token, value in expected.items():
            with self.subTest(block=block_name, token=token):
                self.assertEqual(
                    actual[token], value.upper(),
                    f"ui-tokens: tokens.css {block_name} --color-{token} is {actual[token]}, "
                    f"the card's {mode} value is {value} ({CARD})",
                )

    def test_light_values_in_root_are_the_cards(self):
        self._check_block("root", "light")

    def test_dark_values_under_system_preference_are_the_cards(self):
        self._check_block("dark_media", "dark")

    def test_dark_values_under_explicit_theme_are_the_cards(self):
        self._check_block("dark_attr", "dark")

    def test_state_danger_equals_the_palette_danger_in_both_modes(self):
        for index, block in ((0, "root"), (1, "dark_attr")):
            with self.subTest(block=block):
                self.assertEqual(
                    ts.css_palette(self.css[block])["danger"],
                    self.card["state"]["danger"][index].upper(),
                    f"ui-tokens: the card's state-table danger and the css --color-danger "
                    f"disagree in {block} ({CARD})",
                )

    def test_css_declares_exactly_the_expected_custom_properties(self):
        for block_name, block in self.css.items():
            with self.subTest(block=block_name):
                names = set(block)
                allowed = ts.EXPECTED_CSS_VARS if block_name == "root" else ts.EXPECTED_CSS_COLOR_VARS
                self.assertEqual(
                    names, allowed,
                    f"ui-tokens: tokens.css {block_name} declares {sorted(names - allowed)} the "
                    f"card does not give and lacks {sorted(allowed - names)} ({CARD})",
                )


class CardToKotlinTest(unittest.TestCase):
    def setUp(self):
        self.card = ts.read_card()
        self.kotlin = ts.read_kotlin()

    def _check(self, palette_name, mode):
        expected = dict(self.card[mode])
        index = 0 if mode == "light" else 1
        expected["sent"] = self.card["state"]["sent"][index]
        expected["warning"] = self.card["state"]["warning"][index]
        actual = {ts.kebab(k): v for k, v in self.kotlin["palettes"][palette_name].items()}
        self.assertEqual(
            set(actual), set(expected),
            f"ui-tokens: tokens.kt {palette_name} declares {sorted(actual)}, the card gives "
            f"{sorted(expected)} ({CARD})",
        )
        for token, value in expected.items():
            with self.subTest(palette=palette_name, token=token):
                self.assertEqual(
                    actual[token]["hex"], value.upper(),
                    f"ui-tokens: tokens.kt {palette_name}.{token} is {actual[token]['hex']}, "
                    f"the card's {mode} value is {value} ({CARD})",
                )
                self.assertEqual(
                    actual[token]["alpha"], 0xFF,
                    f"ui-tokens: tokens.kt {palette_name}.{token} is not opaque: its alpha "
                    f"byte is {actual[token]['alpha']:#04x}, not 0xFF",
                )

    def test_light_palette_is_the_cards(self):
        self._check("LIGHT", "light")

    def test_dark_palette_is_the_cards(self):
        self._check("DARK", "dark")

    def test_kotlin_declares_exactly_the_expected_fields(self):
        for name, palette in self.kotlin["palettes"].items():
            with self.subTest(palette=name):
                self.assertEqual(set(palette), ts.EXPECTED_KOTLIN_PALETTE_FIELDS)
        self.assertEqual(set(self.kotlin["metrics"]), ts.EXPECTED_KOTLIN_METRICS_FIELDS)
        self.assertEqual(set(self.kotlin["type"]), ts.EXPECTED_KOTLIN_TYPE_FIELDS)
        self.assertEqual(set(self.kotlin["led"]), ts.EXPECTED_KOTLIN_LED_FIELDS)

    def test_kotlin_declares_no_name_or_type_the_card_does_not_give(self):
        names, types = ts.kotlin_declarations(ts.read_text(ts.KOTLIN))
        self.assertEqual(
            names, ts.EXPECTED_KOTLIN_NAMES,
            f"ui-tokens: tokens.kt declares {sorted(names - ts.EXPECTED_KOTLIN_NAMES)} the card "
            f"does not give and lacks {sorted(ts.EXPECTED_KOTLIN_NAMES - names)} ({CARD})",
        )
        self.assertEqual(
            types, ts.EXPECTED_KOTLIN_TYPES,
            f"ui-tokens: tokens.kt declares types {sorted(types - ts.EXPECTED_KOTLIN_TYPES)} the "
            f"card does not give and lacks {sorted(ts.EXPECTED_KOTLIN_TYPES - types)} ({CARD})",
        )

    def test_each_type_family_is_named_by_its_own_role_in_the_card(self):
        bullets = ts.card_typography(ts.read_text(ts.CARD))
        type_ = self.kotlin["type"]
        roles = {
            "Display/headings": [type_["displayFamily"], type_["displayAlternateFamily"]],
            "Body/UI": [type_["bodyFamily"]],
            "Mono": [type_["monoFamily"]],
        }
        for label, families in roles.items():
            for family in families:
                with self.subTest(role=label, family=family):
                    self.assertTrue(
                        ts.names_word(bullets[label], family),
                        f"ui-tokens: tokens.kt gives {family!r} as the {label} family, which "
                        f"the card's '{label}' bullet does not name ({CARD}, Typography)",
                    )


class CardToCssFontsTest(unittest.TestCase):
    def test_each_css_font_stack_is_named_by_its_own_role_in_the_card(self):
        bullets = ts.card_typography(ts.read_text(ts.CARD))
        root = ts.read_css()["root"]
        roles = {
            "Display/headings": root["font-display"],
            "Body/UI": root["font-body"],
            "Mono": root["font-mono"],
        }
        for label, stack in roles.items():
            families = ts.css_font_families(stack)
            self.assertTrue(families, f"ui-tokens: the {label} font stack {stack!r} names no family")
            for family in families:
                with self.subTest(role=label, family=family):
                    self.assertTrue(
                        ts.names_word(bullets[label], family),
                        f"ui-tokens: tokens.css gives {family!r} as a {label} family, which "
                        f"the card's '{label}' bullet does not name ({CARD}, Typography)",
                    )


class CardProseNumbersTest(unittest.TestCase):
    """The numbers the card states in prose are the numbers the files carry."""

    def setUp(self):
        self.prose = ts.card_prose_numbers(ts.read_text(ts.CARD))
        root = ts.read_css()["root"]
        kotlin = ts.read_kotlin()
        self.files = {
            "tokens.css": {
                "radius": ts.css_px(root["radius"]),
                "touch": ts.css_px(root["touch-target-min"]),
                "mobile": ts.css_px(root["breakpoint-mobile"]),
                "tablet": ts.css_px(root["breakpoint-tablet"]),
                "led": (ts.css_int(root["led-segments-min"]), ts.css_int(root["led-segments-max"])),
            },
            "tokens.kt": {
                "radius": kotlin["metrics"]["cornerRadiusDp"],
                "touch": kotlin["metrics"]["minTouchTargetDp"],
                "mobile": kotlin["metrics"]["mobileBreakpointDp"],
                "tablet": kotlin["metrics"]["tabletBreakpointDp"],
                "led": (kotlin["led"]["minSegments"], kotlin["led"]["maxSegments"]),
            },
        }

    def test_the_card_states_the_numbers_the_design_fixes(self):
        # The card is read for these numbers; if the card itself is edited to another
        # number, this is the test that fails and names the change.
        self.assertEqual(
            self.prose,
            {
                "radius_max": 4, "touch_min": 48,
                "mobile_below": 640, "tablet_from": 640, "tablet_to": 1024, "desktop_above": 1024,
                "led_min": 12, "led_max": 16,
            },
            f"ui-tokens: the numbers in the card's prose changed ({CARD})",
        )

    def test_the_card_bands_are_contiguous(self):
        p = self.prose
        self.assertEqual(p["mobile_below"], p["tablet_from"])
        self.assertEqual(p["tablet_to"], p["desktop_above"])

    def test_each_file_agrees_with_the_cards_prose(self):
        p = self.prose
        for name, v in self.files.items():
            with self.subTest(file=name):
                self.assertLessEqual(v["radius"], p["radius_max"], f"ui-tokens: {name} radius exceeds the card's limit ({CARD})")
                self.assertGreaterEqual(v["touch"], p["touch_min"], f"ui-tokens: {name} touch target is below the card's minimum ({CARD})")
                self.assertEqual(v["mobile"], p["mobile_below"], f"ui-tokens: {name} mobile breakpoint differs from the card ({CARD})")
                self.assertEqual(v["tablet"], p["tablet_to"], f"ui-tokens: {name} tablet breakpoint differs from the card ({CARD})")
                self.assertEqual(v["led"], (p["led_min"], p["led_max"]), f"ui-tokens: {name} LED segment range differs from the card ({CARD})")


# ── reader self-tests: synthetic text only ─────────────────────────────────
def synthetic_kotlin(remove=None, extra_type_line="", comment_out=None, drop_to_int=False):
    """A minimal tokens.kt-shaped text. `remove` drops a palette field from LIGHT,
    `comment_out` comments one out, `drop_to_int` breaks one literal's shape."""
    def palette(name, remove=None, comment_out=None, drop_to_int=False):
        lines = []
        for i, field in enumerate(sorted(ts.EXPECTED_KOTLIN_PALETTE_FIELDS)):
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


SYNTHETIC_CARD = (
    "## Palette\n\n**Light mode**\n"
    "| Token | Value | Use |\n|---|---|---|\n"
    + "".join(f"| `{t}` | `#123456` | x |\n" for t in ts.PALETTE_TOKENS)
    + "\n**Dark mode**\n"
    "| Token | Value | Use |\n|---|---|---|\n"
    + "".join(f"| `{t}` | `#654321` | x |\n" for t in ts.PALETTE_TOKENS)
    + "\n## Typography\n"
    "- **Display/headings:** Alpha or Beta (condensed, wrapped\n  onto a second line).\n"
    "- **Body/UI:** Gamma (clean).\n"
    "- **Mono:** Delta for ids.\n"
    "- All open.\n\n"
    "## Style rules\n- hard edges (radius ≤ 4 px)\n- **Touch targets ≥ 48 px** on mobile;\n\n"
    "## State colors - the indicator\n\n"
    "| Token | Light | Dark | Meaning |\n|---|---|---|---|\n"
    "| `sent` (green) | `#111111` | `#222222` | a |\n"
    "| `warning` (orange) | `#333333` | `#444444` | b |\n"
    "| `danger` (red) | `#555555` | `#666666` | c |\n\n"
    "## LED\n- 12–16 segment display; fills.\n\n"
    "## Responsive\n- Breakpoints: **mobile** (< 640 px) · **tablet** (640–1024 px) ·\n"
    "  **desktop** (> 1024 px).\n"
)


class CardReaderTest(unittest.TestCase):
    """The card reader has to be able to say no."""

    def test_reader_returns_the_expected_shape_from_a_well_formed_card(self):
        card = ts.parse_card(SYNTHETIC_CARD)
        self.assertEqual(set(card["light"]), set(ts.PALETTE_TOKENS))
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
            ts.card_prose_numbers(SYNTHETIC_CARD.replace("radius ≤ 4 px", "radius under 4 px"))
        with self.assertRaises(AssertionError):
            ts.card_prose_numbers(SYNTHETIC_CARD + "\n- another (radius ≤ 6 px)\n")

    def test_prose_reader_follows_a_changed_number(self):
        edited = SYNTHETIC_CARD.replace("≥ 48 px", "≥ 56 px")
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
            + "".join(f"| `{t}` | `{value}` | x |\n" for t in ts.PALETTE_TOKENS)
        )

    def _refusal(self, call):
        with self.assertRaises(AssertionError) as caught:
            call()
        return str(caught.exception)

    def test_the_card_the_cases_start_from_reads_with_each_caption_once(self):
        # Control: the refusals below are for the repeated caption, not for a card
        # that could not be read to begin with.
        self.assertEqual(set(ts.parse_card(SYNTHETIC_CARD)["light"]), set(ts.PALETTE_TOKENS))
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
        card = "- Breakpoints: **one** (a) ·\n  **two** (b) ·\n  **three** (c).\n\n- other: **not-a-band**\n"
        self.assertEqual(ts.card_bands(card), ["one", "two", "three"])


class CssReaderTest(unittest.TestCase):
    """The CSS reader has to be able to say no, and to say why: each refusal is checked
    against its own reason, so a sheet refused for some other reason does not pass."""

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


class KotlinReaderTest(unittest.TestCase):
    """The Kotlin reader has to be able to say no, and to read the compiler's way."""

    def test_signed_int_wrap_matches_the_compiler(self):
        self.assertEqual(ts.argb_from_kotlin_literal("0xFFFFFFFF"), -1)
        self.assertEqual(ts.argb_from_kotlin_literal("0xFF1E7A46"), -14779834)
        self.assertEqual(ts.argb_from_kotlin_literal("0x7F000000"), 0x7F000000)

    def test_reader_reads_a_well_formed_file(self):
        parsed = ts.parse_kotlin(synthetic_kotlin())
        self.assertEqual(set(parsed["palettes"]), {"LIGHT", "DARK"})
        self.assertEqual(set(parsed["palettes"]["LIGHT"]), ts.EXPECTED_KOTLIN_PALETTE_FIELDS)
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
        self.assertEqual(len(light), len(ts.EXPECTED_KOTLIN_PALETTE_FIELDS) - 1)

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
