"""Every refusal names the entry it refuses, or the line the entry sits on.

The generator's promise (its module docstring and the registry format) is that
every refusal names the entry and its line. Four classes used to name neither:
a missing required field, an unknown field, an empty scalar, and (before the
id-shape refusal was given a label) the id shape itself. A refusal that does
not say WHICH entry is wrong forces the reader to count sequence items by
hand in a hand-edited yaml.

An entry is named by its id whenever the id is a usable non-empty scalar, and
by the line it starts on otherwise - the id is precisely what is wrong in the
"otherwise" cases (absent, empty), so there is nothing else to name it by.

One test per refusal class, each planting the bad shape in the real entry's
text and asserting the refusal names the entry (or, for a broken id, the line).
A plant the generator would ACCEPT fails the test (the helper raises).

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


def entry_line(text):
    """The 1-based line of the single entry's sequence marker."""
    return next(i + 1 for i, l in enumerate(text.splitlines())
                if l.strip().startswith("- id:"))


def line_in(msg):
    """The line number a refusal points at."""
    m = re.search(r"line (\d+)", msg)
    assert m, f"refusal names no line: {msg!r}"
    return int(m.group(1))


class MissingRequiredFieldNamesTheEntryTest(unittest.TestCase):
    def test_missing_required_field_names_the_entry_and_its_line(self):
        text = ts.models_text()
        planted = "\n".join(l for l in text.splitlines()
                            if not l.startswith("    family:"))
        msg = ts.refuses(planted)
        self.assertIn("required field 'family' is missing", msg)
        self.assertIn("entry 'small' ", msg)
        self.assertEqual(line_in(msg), entry_line(text))


class UnknownFieldNamesTheEntryTest(unittest.TestCase):
    def test_unknown_field_names_the_entry_and_the_field_line(self):
        text = ts.single_entry_yaml("small")
        planted = replace_once(text, "    family: sherpa-onnx",
                               "    family: sherpa-onnx\n    flavour: vanilla")
        msg = ts.refuses(planted)
        self.assertIn("unknown field 'flavour'", msg)
        self.assertIn("entry 'small' ", msg)
        self.assertEqual(line_in(msg),
                         planted.splitlines().index("    flavour: vanilla") + 1)


class EmptyScalarNamesTheEntryTest(unittest.TestCase):
    def test_empty_scalar_names_the_entry_and_the_field_line(self):
        text = ts.single_entry_yaml("small")
        planted = replace_once(text, "    family: sherpa-onnx", '    family: ""')
        msg = ts.refuses(planted)
        self.assertIn("must be a non-empty scalar", msg)
        self.assertIn("entry 'small' ", msg)
        self.assertEqual(line_in(msg),
                         next(i + 1 for i, l in enumerate(text.splitlines())
                              if l.startswith("    family:")))


class EmptyIdNamesTheLineTest(unittest.TestCase):
    """An empty id is the one case with no id to name: the line is the name."""

    def test_empty_id_is_named_by_its_line(self):
        text = ts.models_text()
        planted = replace_once(text, "id: small", 'id: ""')
        msg = ts.refuses(planted)
        self.assertIn("field 'id'", msg)
        self.assertIn(f"entry at line {entry_line(text)}", msg)


class MissingIdNamesTheLineTest(unittest.TestCase):
    def test_missing_id_is_named_by_its_line(self):
        text = ts.models_text()
        # the sequence marker must still open a mapping, so the entry starts
        # on a field that is not 'id'
        planted = replace_once(text, "  - id: small\n",
                               "  - license_name: Custom ASR License\n")
        msg = ts.refuses(planted)
        self.assertIn("required field 'id' is missing", msg)
        self.assertIn(f"entry at line {entry_line(text)}", msg)


class IdShapeNamesTheEntryTest(unittest.TestCase):
    """The id-shape refusal names the offending id even though it is illegal."""

    def test_bad_id_shape_still_names_the_id_it_refused(self):
        text = ts.models_text()
        planted = replace_once(text, "id: small", "id: 7small")
        msg = ts.refuses(planted)
        self.assertIn("entry '7small' ", msg)
        self.assertEqual(line_in(msg), entry_line(text))


if __name__ == "__main__":
    unittest.main()
