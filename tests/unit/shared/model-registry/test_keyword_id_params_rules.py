"""The two keyword-entry rules that differ from the speech rules, and the
optional commit: pinned in both directions, and shown not to leak into the
speech list.

* the id may hold single hyphens between alphanumerics (the constant turns
  them into underscores); uppercase, a leading digit, a double or trailing
  hyphen and an underscore are refused; a hyphen in a SPEECH id stays refused;
* params may carry a decimal point (3.3M); a missing M, zero, a bare or doubled
  point are refused; a decimal count in a SPEECH entry stays refused;
* upstream_commit is optional for a keyword entry (checked when given) and
  still required for a speech entry.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts
from keyword_support import (KW_ID, KW_PREFIX, accepted_kw, refused_kw,
                             replace_once)


class KeywordIdRuleTest(unittest.TestCase):
    """A keyword id may hold single hyphens between alphanumerics."""

    def test_uppercase_is_refused(self):
        self.assertIn("lowercase alphanumerics", refused_kw(
            "id: kws-gigaspeech-3m", "id: Kws-gigaspeech-3m"))

    def test_a_leading_digit_is_refused(self):
        self.assertIn("starting with a letter", refused_kw(
            "id: kws-gigaspeech-3m", "id: 3kws"))

    def test_a_double_hyphen_is_refused(self):
        self.assertIn("lowercase alphanumerics", refused_kw(
            "id: kws-gigaspeech-3m", "id: kws--gigaspeech"))

    def test_a_trailing_hyphen_is_refused(self):
        self.assertIn("lowercase alphanumerics", refused_kw(
            "id: kws-gigaspeech-3m", "id: kws-"))

    def test_an_underscore_is_refused(self):
        self.assertIn("lowercase alphanumerics", refused_kw(
            "id: kws-gigaspeech-3m", "id: kws_gigaspeech"))

    def test_a_plain_alphanumeric_keyword_id_is_accepted(self):
        self.assertEqual("kws3", accepted_kw(
            "id: kws-gigaspeech-3m", "id: kws3")["id"])

    def test_a_hyphen_in_a_speech_id_is_still_refused(self):
        msg = ts.refuses(replace_once(ts.models_text(), "id: small",
                                      "id: sm-all"))
        self.assertIn("entry 'sm-all' line ", msg)
        self.assertIn("lowercase alphanumerics", msg)


class KeywordParamsRuleTest(unittest.TestCase):
    OLD = "params: 3.3M"

    def test_a_decimal_count_with_the_m_suffix_is_accepted(self):
        self.assertEqual("3M", accepted_kw(self.OLD, "params: 3M")["params"])
        self.assertEqual("3.3M", accepted_kw(self.OLD, self.OLD)["params"])

    def test_a_missing_m_suffix_is_refused(self):
        self.assertIn("M suffix", refused_kw(self.OLD, "params: 3.3"))

    def test_zero_is_refused(self):
        self.assertIn("M suffix", refused_kw(self.OLD, "params: 0M"))
        self.assertIn("M suffix", refused_kw(self.OLD, "params: 0.0M"))

    def test_a_trailing_or_leading_point_is_refused(self):
        self.assertIn("M suffix", refused_kw(self.OLD, "params: 3.M"))
        self.assertIn("M suffix", refused_kw(self.OLD, "params: .3M"))

    def test_two_points_are_refused(self):
        self.assertIn("M suffix", refused_kw(self.OLD, "params: 3.3.3M"))

    def test_a_decimal_count_in_a_speech_entry_is_still_refused(self):
        msg = ts.refuses(replace_once(ts.models_text(), "params: 70M",
                                      "params: 3.3M"))
        self.assertIn("entry 'small' line ", msg)
        self.assertIn("positive integer with an M suffix", msg)


class KeywordUpstreamCommitRuleTest(unittest.TestCase):
    ANCHOR = "    tamper_verified: false\n"

    def test_the_committed_keyword_entry_carries_no_commit(self):
        self.assertEqual([], [l for l in ts.entry_block(KW_ID)
                              if l.strip().startswith("upstream_commit:")])

    def test_a_short_commit_is_refused(self):
        msg = refused_kw(self.ANCHOR,
                         self.ANCHOR + "    upstream_commit: 9a65b6ea\n")
        self.assertIn("40 lowercase hex", msg)

    def test_an_uppercase_commit_is_refused(self):
        msg = refused_kw(self.ANCHOR, self.ANCHOR
                         + "    upstream_commit: " + "A" * 40 + "\n")
        self.assertIn("40 lowercase hex", msg)

    def test_a_full_commit_is_accepted_when_one_is_given(self):
        e = accepted_kw(self.ANCHOR, self.ANCHOR
                        + "    upstream_commit: " + "a" * 40 + "\n")
        self.assertEqual("a" * 40, e["upstream_commit"])

    def test_a_speech_entry_still_requires_its_commit(self):
        lines = [l for l in ts.models_text().splitlines()
                 if not l.strip().startswith("upstream_commit: 9a65b6ea")]
        msg = ts.refuses("\n".join(lines) + "\n")
        self.assertIn("required field 'upstream_commit' is missing", msg)
        self.assertIn("entry 'small' ", msg)



if __name__ == "__main__":
    unittest.main()
