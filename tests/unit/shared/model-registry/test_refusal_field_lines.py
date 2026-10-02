"""A refusal names the line of the thing it refuses.

Two invariants, one per class of refusal:

* the missing-required-field, unknown-field and empty-scalar refusals name the
  line of the FIELD they are talking about, not the entry's sequence marker —
  except a field that is ABSENT, which has no line of its own, so the entry's
  first line is the closest true thing to point at;
* the duplicate-id refusal names the line the id is ON, in both halves (the
  entry being refused and the "first on line" entry it clashes with).

The duplicate-id case needs the ordering spelled out because an entry need not
open with `id:`. With `- id: small` the marker IS the id line and the two
numberings coincide, so that ordering cannot tell a correct refusal from one
that points at the marker. With `- family: sherpa-onnx` first they differ by
one line, and that is the case that separates them.

Every entry here is the committed one with its opening fields replaced and,
where a test needs a field ABSENT, with that field's line removed — so each
entry still carries the values it ships, and the reader still sees one entry
per sequence item.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts

# the committed entry opens with these two lines, in this order
COMMITTED_OPENING = ("  - id: small\n", "    family: sherpa-onnx\n")
ID_FIRST = "  - id: small\n    family: sherpa-onnx\n"
FAMILY_FIRST = "  - family: sherpa-onnx\n    id: small\n"
# openings for the missing-field refusals: family is dropped from the body, so
# the opening must not supply it, and params moves up to keep the entry's
# first line distinct from its id line in the second shape
ID_THEN_BODY = "  - id: small\n"
PARAMS_FIRST = "  - params: 70M\n    id: small\n"


def line_in(msg):
    """The line number a refusal points at."""
    m = re.search(r"line (\d+)", msg)
    assert m, f"refusal names no line: {msg!r}"
    return int(m.group(1))


def lines_of(text, field):
    """The 1-based lines carrying `field:` in `text` — a sequence marker
    counts, because that is the line a `- id: small` entry puts its id on."""
    return [i + 1 for i, l in enumerate(text.splitlines())
            if re.match(rf"^\s*(-\s+)?{field}\s*:", l)]


def entry(opening, drop=()):
    """The committed entry with its two opening field lines replaced by
    `opening`, and the named fields' lines removed."""
    lines = ts.models_text().split("models:\n", 1)[1].splitlines(True)
    for i, expected in enumerate(COMMITTED_OPENING):
        assert lines[i] == expected, f"models.yaml opening moved: {lines[i]!r}"
    body = [l for l in lines[len(COMMITTED_OPENING):]
            if not any(l.startswith(f"    {f}:") for f in drop)]
    return opening + "".join(body)


def two_entries(opening):
    """models.yaml carrying that entry twice, so the duplicate-id refusal is
    what the reader hits."""
    one = entry(opening)
    return "models:\n" + one + one


class DuplicateIdNamesTheIdLineTest(unittest.TestCase):
    def test_family_first_duplicate_names_both_id_lines(self):
        text = two_entries(FAMILY_FIRST)
        id_lines = lines_of(text, "id")
        self.assertEqual(len(id_lines), 2)
        msg = ts.refuses(text)
        self.assertIn("duplicate id 'small'", msg)
        self.assertIn(f"entry 'small' line {id_lines[1]}:", msg)
        self.assertIn(f"first on line {id_lines[0]}", msg)

    def test_id_first_duplicate_names_the_id_line(self):
        # here the marker and the id line are the same line, so this pins the
        # message without separating it from a marker-pointing one
        text = two_entries(ID_FIRST)
        id_lines = lines_of(text, "id")
        self.assertEqual(len(id_lines), 2)
        msg = ts.refuses(text)
        self.assertIn(f"entry 'small' line {id_lines[1]}:", msg)
        self.assertIn(f"first on line {id_lines[0]}", msg)


class RefusalNamesTheFieldLineTest(unittest.TestCase):
    """The three call sites of the refusal prefix, each read against the line
    it is actually talking about."""

    def test_unknown_field_is_named_at_its_own_line(self):
        planted = ts.models_text().replace(
            "    family: sherpa-onnx",
            "    family: sherpa-onnx\n    flavour: vanilla")
        msg = ts.refuses(planted)
        self.assertIn("unknown field 'flavour'", msg)
        self.assertEqual(line_in(msg), lines_of(planted, "flavour")[0])

    def test_empty_scalar_is_named_at_its_own_line(self):
        planted = ts.models_text().replace("family: sherpa-onnx", 'family: ""')
        msg = ts.refuses(planted)
        self.assertIn("must be a non-empty scalar", msg)
        self.assertEqual(line_in(msg), lines_of(planted, "family")[0])

    def test_missing_field_is_named_at_the_entry_line(self):
        # a field that is absent has no line of its own: the entry's first
        # line is what the reader has to look at
        planted = "models:\n" + entry(ID_THEN_BODY, drop=("family", "params"))
        msg = ts.refuses(planted)
        self.assertIn("required field 'family' is missing", msg)
        self.assertEqual(line_in(msg), 2)

    def test_missing_field_names_the_marker_not_the_id_line(self):
        # same rule with an entry that opens on a field that is not `id`, so
        # the entry line and the id line are different numbers. The family
        # that is missing has no line of its own either way, so the entry's
        # own first line is the one to name.
        planted = "models:\n" + entry(PARAMS_FIRST, drop=("family", "params"))
        msg = ts.refuses(planted)
        self.assertIn("required field 'family' is missing", msg)
        self.assertEqual(line_in(msg), 2)
        self.assertNotEqual(line_in(msg), lines_of(planted, "id")[0])


if __name__ == "__main__":
    unittest.main()
