"""What the module's prose says is checked like what its values say.

A sentence in a README or a header comment is a claim. When one is wrong, nothing
else in the module fails: the values are right and the tests are green while a
reader does what the prose told them. So the claims that were wrong once are pinned
here, each with the reason it is pinned.

* `data-theme` goes on the root element. The stylesheet's selectors match `:root`,
  so the attribute on any other element does nothing.
* A width handed to `bandFor` is not rounded first. No rounding direction is right
  at both 640 and 1024, so the docs must not tell a caller to round.
* The card says no spacing scale exists, once, under Known Gotchas, and the files
  do not define one. Its first Purpose line stays the registry's `owns` text.

Module card: shared/modules/ui-tokens/AGENTS.md

Run: python3 -m unittest discover -s tests/unit/shared/ui-tokens -p 'test_*.py'
"""
import re
import tomllib
import unittest

import tokens_support as ts

CARD = ts.CARD_POINTER
README = ts.MODULE / "README.md"

# The card's line on spacing, as ruled: there is no scale, and a consumer that needs
# one proposes it as a card change.
SPACING_LINE = (
    "Spacing: no scale defined yet. When a consumer needs one, it is proposed as a "
    "card change, and its values go to the project owner (design values are theirs)."
)

# Sentences that told a caller to round a width first. Wrong at one of the two edges.
ROUNDING_ADVICE = (
    "rounded up by the caller",
    "must be rounded",
    "should be rounded",
    "round it up",
    "round up first",
    "round it first",
)


def paragraphs(text):
    return [" ".join(p.split()) for p in re.split(r"\n\s*\n", text) if p.strip()]


def first_comment(css):
    match = re.match(r"\s*/\*(.*?)\*/", css, flags=re.S)
    if not match:
        raise AssertionError("ui-tokens: tokens.css does not open with a header comment")
    return " ".join(re.sub(r"^\s*\*", "", match.group(1), flags=re.M).split())


class DataThemeOnTheRootElementTest(unittest.TestCase):
    """The selectors are `:root[data-theme="dark"]` and `:root:not([data-theme="light"])`,
    so `data-theme` on <body> does nothing. The docs say where it goes."""

    def _says_root_element(self, text, where):
        self.assertIn("root element", text, f"ui-tokens: {where} must say data-theme goes on the root element")
        self.assertIn("<html>", text, f"ui-tokens: {where} must name the root element, <html>")
        self.assertNotIn("<body>", text, f"ui-tokens: {where} must not point data-theme at <body>")

    def test_the_readme_says_data_theme_goes_on_the_root_element(self):
        about = [p for p in paragraphs(ts.read_text(README)) if "data-theme" in p]
        self.assertTrue(about, "ui-tokens: the README no longer mentions data-theme")
        self._says_root_element(" ".join(about), "the README paragraph on data-theme")

    def test_the_css_header_says_data_theme_goes_on_the_root_element(self):
        self._says_root_element(first_comment(ts.read_text(ts.CSS)), "the tokens.css header")

    def test_the_selectors_the_docs_describe_are_the_ones_the_css_has(self):
        # The claim "the selectors match :root only" is only true while it is.
        css = ts.read_text(ts.CSS)
        selectors = re.findall(r"([^{}/]+)\{", re.sub(r"/\*.*?\*/", "", css, flags=re.S))
        attribute = [s.strip() for s in selectors if "data-theme" in s]
        self.assertEqual(
            sorted(attribute),
            sorted([':root:not([data-theme="light"])', ':root[data-theme="dark"]']),
            "ui-tokens: a selector other than :root carries data-theme; the docs say the root element only",
        )


class BandForIsNotToldToRoundTest(unittest.TestCase):
    """`bandFor` compares a fractional width exactly. Rounding up is wrong at 640
    (639.5 is mobile) and rounding down is wrong at 1024 (1024.5 is desktop)."""

    def _texts(self):
        return {"README.md": ts.read_text(README), "tokens.kt": ts.read_text(ts.KOTLIN)}

    def test_no_doc_tells_a_caller_to_round_a_width_first(self):
        for name, text in self._texts().items():
            flat = " ".join(text.split())
            for advice in ROUNDING_ADVICE:
                with self.subTest(file=name, advice=advice):
                    self.assertNotIn(advice, flat, f"ui-tokens: {name} tells a caller to round a width; no direction is right at both edges")

    def test_the_docs_give_the_two_widths_that_show_why(self):
        # The examples are checked as the claims they are, not as bare numbers: a doc that
        # still mentions 1024.5 while calling it tablet would otherwise pass.
        for name, text in self._texts().items():
            flat = " ".join(text.split())
            with self.subTest(file=name):
                self.assertIn("639.5 is mobile", flat, f"ui-tokens: {name} must say 639.5 is mobile")
                self.assertIn("1024.5 is desktop", flat, f"ui-tokens: {name} must say 1024.5 is desktop")

    def test_the_advice_list_would_catch_the_old_sentence(self):
        old = 'a fractional width must be rounded up by the caller, because "wider than 1024" includes 1024.5.'
        self.assertTrue(any(a in " ".join(old.split()) for a in ROUNDING_ADVICE))


class SpacingTest(unittest.TestCase):
    """The card says no spacing scale exists, and it is true."""

    def _card_lines(self):
        return ts.read_text(ts.CARD).splitlines()

    def test_the_card_carries_the_spacing_line_once_under_known_gotchas(self):
        lines = self._card_lines()
        headings = [i for i, line in enumerate(lines) if line.strip() == "## Known Gotchas"]
        self.assertEqual(len(headings), 1, f"ui-tokens: the card must have one '## Known Gotchas' heading ({CARD})")
        body = []
        for line in lines[headings[0] + 1:]:
            if line.startswith("## "):
                break
            body.append(line)
        bullets = [line[2:].rstrip() for line in body if line.startswith("- ")]
        self.assertEqual(
            bullets.count(SPACING_LINE), 1,
            f"ui-tokens: Known Gotchas must carry the spacing line exactly once ({CARD})",
        )
        self.assertEqual(
            "\n".join(lines).count(SPACING_LINE), 1,
            f"ui-tokens: the spacing line must appear once in the whole card ({CARD})",
        )

    def test_the_first_purpose_paragraph_is_the_registrys_owns_text(self):
        with open(ts.ROOT / "modules.toml", "rb") as handle:
            owns = tomllib.load(handle)["module"]["shared_ui_tokens"]["owns"]
        self.assertIn("spacing", owns, "ui-tokens: the registry's owns text keeps spacing")
        lines = self._card_lines()
        purposes = [i for i, line in enumerate(lines) if line.strip() == "## Purpose"]
        self.assertEqual(len(purposes), 1, f"ui-tokens: the card must have one '## Purpose' heading ({CARD})")
        first = next(line for line in lines[purposes[0] + 1:] if line.strip())
        self.assertTrue(
            first.startswith(owns),
            f"ui-tokens: the card's first Purpose line must start with the registry's owns text, word for word ({CARD})",
        )

    def test_the_files_define_no_spacing_scale(self):
        css_names = set(ts.read_css()["root"])
        kotlin_names, kotlin_types = ts.kotlin_declarations(ts.read_text(ts.KOTLIN))
        for name in css_names | kotlin_names | kotlin_types:
            with self.subTest(name=name):
                self.assertIsNone(
                    re.search(r"spac|gap|gutter|margin|padding", name, flags=re.I),
                    f"ui-tokens: {name} looks like a spacing token; the card says none is defined ({CARD})",
                )


if __name__ == "__main__":
    unittest.main()
