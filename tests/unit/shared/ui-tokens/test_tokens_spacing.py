"""The spacing scale: five size names, equal in both files, positive and increasing.

The card, tokens.kt and tokens.css each name the same five steps, smallest first.
The rules are checked on the real files, and each rule is also checked to refuse a
copy of the real text with one edit, so a rule that could not say no is seen.

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import re
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER

# The five steps: (Kotlin field, CSS property, dp), smallest first.
STEPS = [
    ("spaceXsDp", "space-xs", 4),
    ("spaceSDp", "space-s", 8),
    ("spaceMDp", "space-m", 12),
    ("spaceLDp", "space-l", 16),
    ("spaceXlDp", "space-xl", 32),
]

SPACING_WORD = re.compile(r"spac|gap|gutter|margin|padding", flags=re.I)


def card_spacing_line(card):
    """The one card line that gives the spacing scale; it must appear exactly once."""
    lines = [line for line in card.splitlines() if line.startswith("- Spacing:")]
    if len(lines) != 1:
        raise AssertionError(f"ui-tokens: the card has {len(lines)} '- Spacing:' lines, expected one ({CARD})")
    return lines[0]


def scale_problems(kotlin_text, css_text, card_text):
    """Every way the three texts break the spacing rules. An empty list means it holds.
    Each message starts with the name of the rule it breaks."""
    problems = []
    metrics = ts.parse_kotlin(kotlin_text)["metrics"]
    root = ts.parse_css(css_text)["root"]

    # names: the spacing-looking names are the five steps, plus the gutter in Kotlin only.
    kt_names = sorted(n for n in metrics if SPACING_WORD.search(n))
    css_names = sorted(n for n in root if SPACING_WORD.search(n))
    if kt_names != sorted(["gutterDp"] + [kt for kt, _, _ in STEPS]):
        problems.append(f"names: tokens.kt spacing-looking metrics are {kt_names}")
    if css_names != sorted(css for _, css, _ in STEPS):
        problems.append(f"names: tokens.css spacing-looking names are {css_names}")

    # equal: each step has the same value in both files, and it is the contract value.
    for kt, css, dp in STEPS:
        if kt not in metrics or css not in root:
            problems.append(f"equal: {kt} or --{css} is missing from its file")
            continue
        kt_value = metrics[kt]
        css_value = ts.css_px(root[css])
        if kt_value != dp or css_value != dp:
            problems.append(
                f"equal: {kt} is {kt_value} in tokens.kt and --{css} is {css_value} in "
                f"tokens.css; the contract gives {dp}"
            )

    # order: the steps are positive and strictly increasing.
    values = [metrics[kt] for kt, _, _ in STEPS if kt in metrics]
    if not values or values[0] <= 0 or any(a >= b for a, b in zip(values, values[1:])):
        problems.append(f"order: the steps {values} are not positive and strictly increasing")

    # card: the card's spacing line gives the same names and values as the files.
    line = card_spacing_line(card_text)
    card_kt = [(k, int(v)) for k, v in re.findall(r"(space[A-Za-z]+Dp) (\d+)", line)]
    card_css = [(k, int(v)) for k, v in re.findall(r"(--space-[a-z]+) (\d+)px", line)]
    files_kt = [(kt, metrics.get(kt)) for kt, _, _ in STEPS]
    files_css = [(f"--{css}", ts.css_px(root[css]) if css in root else None) for _, css, _ in STEPS]
    if card_kt != files_kt or card_css != files_css:
        problems.append(
            f"card: the card's spacing line gives {card_kt} and {card_css}; "
            f"the files give {files_kt} and {files_css}"
        )
    return problems


def edit_once(text, old, new):
    """The text with one edit. The old text must occur exactly once, so a sample that
    matched nothing or twice cannot pass for a real sample."""
    found = text.count(old)
    if found != 1:
        raise AssertionError(f"the sample text {old!r} occurs {found} times, expected once")
    return text.replace(old, new)


class SpacingScaleTest(unittest.TestCase):
    def setUp(self):
        self.kotlin = ts.read_text(ts.KOTLIN)
        self.css = ts.read_text(ts.CSS)
        self.card = ts.read_text(ts.CARD)

    def test_the_real_files_hold_every_spacing_rule(self):
        self.assertEqual(scale_problems(self.kotlin, self.css, self.card), [])

    def test_the_equal_rule_refuses_a_changed_css_value(self):
        css = edit_once(self.css, "--space-s: 8px;", "--space-s: 9px;")
        problems = scale_problems(self.kotlin, css, self.card)
        self.assertTrue(any(p.startswith("equal:") for p in problems), problems)

    def test_the_equal_rule_refuses_a_changed_kotlin_value(self):
        kotlin = edit_once(self.kotlin, "spaceLDp = 16,", "spaceLDp = 17,")
        problems = scale_problems(kotlin, self.css, self.card)
        self.assertTrue(any(p.startswith("equal:") for p in problems), problems)

    def test_the_order_rule_refuses_a_repeated_step(self):
        kotlin = edit_once(self.kotlin, "spaceMDp = 12,", "spaceMDp = 8,")
        problems = scale_problems(kotlin, self.css, self.card)
        self.assertTrue(any(p.startswith("order:") for p in problems), problems)

    def test_the_order_rule_refuses_a_step_that_is_not_positive(self):
        kotlin = edit_once(self.kotlin, "spaceXsDp = 4,", "spaceXsDp = 0,")
        problems = scale_problems(kotlin, self.css, self.card)
        self.assertTrue(any(p.startswith("order:") for p in problems), problems)

    def test_the_names_rule_refuses_an_extra_kotlin_name(self):
        kotlin = edit_once(self.kotlin, "spaceXlDp = 32,", "spaceXlDp = 32,\n        spaceHugeDp = 40,")
        problems = scale_problems(kotlin, self.css, self.card)
        self.assertTrue(any(p.startswith("names:") for p in problems), problems)

    def test_the_names_rule_refuses_a_renamed_css_step(self):
        css = edit_once(self.css, "--space-xl: 32px;", "--space-xx: 32px;")
        problems = scale_problems(self.kotlin, css, self.card)
        self.assertTrue(any(p.startswith("names:") for p in problems), problems)

    def test_the_card_rule_refuses_a_changed_card_value(self):
        card = edit_once(self.card, "spaceLDp 16,", "spaceLDp 15,")
        problems = scale_problems(self.kotlin, self.css, card)
        self.assertTrue(any(p.startswith("card:") for p in problems), problems)

    def test_the_card_rule_refuses_a_changed_card_css_value(self):
        card = edit_once(self.card, "--space-xl 32px", "--space-xl 33px")
        problems = scale_problems(self.kotlin, self.css, card)
        self.assertTrue(any(p.startswith("card:") for p in problems), problems)

    def test_the_card_spacing_line_is_refused_when_missing(self):
        # The zero-line branch: the message must name the count it found.
        with self.assertRaisesRegex(AssertionError, "has 0 '- Spacing:' lines"):
            scale_problems(self.kotlin, self.css, self.card.replace("- Spacing:", "- Spacings:"))

    def test_the_card_spacing_line_is_refused_when_repeated(self):
        # The more-than-one branch: the card carries the same line twice.
        repeated = self.card + "\n" + card_spacing_line(self.card) + "\n"
        with self.assertRaisesRegex(AssertionError, "has 2 '- Spacing:' lines"):
            scale_problems(self.kotlin, self.css, repeated)


if __name__ == "__main__":
    unittest.main()
