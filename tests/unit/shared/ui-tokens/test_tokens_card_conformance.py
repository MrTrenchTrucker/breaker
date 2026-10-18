"""Every token the module card publishes is present, with the exact value.

The card (shared/modules/ui-tokens/AGENTS.md) is the specification. These tests
read its tables, its typography bullets and the numbers in its prose off disk and
compare tokens.css and tokens.kt against them, so a value changed in a file
without the card, or in the card without the files, is a red run that names the
token. They never skip: a missing card or table is a failure, because a gate that
quietly stops gating is worse than none.

They also refuse extras: a file that declares a token the card does not give
fails, so no value is invented.

This file holds only the card-to-file comparisons, so it stays under the module's
500-line file cap. The reader self-tests live in test_tokens_card_reader,
test_tokens_css_reader and test_tokens_kotlin_reader; the gutter, which is one
metric rather than a card table, has its own test_tokens_gutter.

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


if __name__ == "__main__":
    unittest.main()
