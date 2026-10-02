"""The registry refuses a zero-parameter or zero-byte model.

Both shapes are already refused by the shape rules (`^[1-9][0-9]*M$` for
params, `^[1-9][0-9]*$` for size_mb), so these are PINS, not bug fixes: they
exist so a widening of either rule - "a count may start with any digit" - is
caught here. A pin cannot be proven RED against the committed tree, because the
committed tree is right; its RED is the mutant that removes the refusal.

Both refusals must also name the entry, like every other refusal.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


class ZeroParamsRefusedTest(unittest.TestCase):
    def test_params_of_zero_millions_is_refused(self):
        planted = replace_once(ts.models_text(), "params: 70M", "params: 0M")
        msg = ts.refuses(planted)
        self.assertIn("params '0M'", msg)
        self.assertIn("positive integer with an M suffix", msg)
        self.assertIn("entry 'small' ", msg)


class ZeroSizeRefusedTest(unittest.TestCase):
    def test_size_mb_of_zero_is_refused(self):
        planted = replace_once(ts.models_text(), "size_mb: 349", "size_mb: 0")
        msg = ts.refuses(planted)
        self.assertIn("size_mb '0'", msg)
        self.assertIn("positive integer", msg)
        self.assertIn("entry 'small' ", msg)


class LeadingZeroRefusedTest(unittest.TestCase):
    """The rule that refuses `0` also refuses a leading zero - `07M` is not a
    count a human wrote, it is a zero-padded string."""

    def test_params_with_a_leading_zero_is_refused(self):
        planted = replace_once(ts.models_text(), "params: 70M", "params: 07M")
        msg = ts.refuses(planted)
        self.assertIn("M suffix", msg)

    def test_size_mb_with_a_leading_zero_is_refused(self):
        planted = replace_once(ts.models_text(), "size_mb: 349", "size_mb: 0349")
        msg = ts.refuses(planted)
        self.assertIn("size_mb", msg)


if __name__ == "__main__":
    unittest.main()
