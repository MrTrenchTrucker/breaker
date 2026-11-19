"""The pin and presentation fields of a registry entry: every refusal fires.

Each entry now carries `size_bytes` and `asset_id` (our own pin) and, in the
speech list, `kind`, `offered` and the optional `label` and `rating`
(tools/model_registry_fields.py). One test per refusal, each planted inside ONE
entry of the committed text so the plant cannot pass for a different reason, and
each paired with a positive control (the nearest accepted value) so a refusal
that fires on everything would show up as a failed control.

Not built: nothing outside the registry reads these fields yet.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts
from keyword_support import KW_ID, refused_kw, replace_once


def plant(eid, old, new, text=None):
    """The committed text with one line of entry `eid` changed (`old` must
    occur once inside that entry's block, so a line both speech entries share,
    like `offered: true`, is changed only in the entry named)."""
    text = ts.models_text() if text is None else text
    block = "\n".join(ts.entry_block(eid))
    assert block.count(old) == 1, f"expected exactly one {old!r} in {eid}"
    assert text.count(block) == 1, f"the {eid} block is not unique"
    return text.replace(block, block.replace(old, new))


def drop(eid, line):
    return plant(eid, line + "\n", "")


class AssetIdMustMatchTheUrlTest(unittest.TestCase):
    def test_an_asset_id_that_differs_from_the_url_is_refused(self):
        msg = ts.refuses(plant("parakeet", "asset_id: 719769366",
                               "asset_id: 719769367"))
        self.assertIn("entry 'parakeet' line ", msg)
        self.assertIn("differs from the asset the url names (719769366)", msg)

    def test_the_matching_asset_id_is_accepted(self):
        self.assertEqual(719769366, ts.entry("parakeet")["asset_id"])

    def test_the_url_changed_instead_is_refused_the_same_way(self):
        msg = ts.refuses(plant(
            "parakeet", "releases/assets/719769366", "releases/assets/42"))
        self.assertIn("differs from the asset the url names (42)", msg)

    def test_a_keyword_asset_id_that_differs_is_refused(self):
        msg = refused_kw("asset_id: 191975328", "asset_id: 191975329")
        self.assertIn("differs from the asset the url names", msg)

    def test_a_non_numeric_or_zero_asset_id_is_refused(self):
        for bad in ("abc", "0", "-5", "1.5", "007"):
            with self.subTest(bad=bad):
                msg = ts.refuses(plant("tiny", "asset_id: 143510207",
                                       f"asset_id: {bad}"))
                self.assertIn("asset_id", msg)
                self.assertIn("positive integer", msg)


class SizeBytesTest(unittest.TestCase):
    def test_zero_and_text_and_negative_and_decimal_are_refused(self):
        for bad in ("0", "big", "-1", "1.5", "0127887156"):
            with self.subTest(bad=bad):
                msg = ts.refuses(plant("tiny", "size_bytes: 127887156",
                                       f"size_bytes: {bad}"))
                self.assertIn("entry 'tiny' line ", msg)
                self.assertIn("size_bytes", msg)
                self.assertIn("positive integer", msg)

    def test_a_positive_size_is_accepted_and_converted_to_an_int(self):
        self.assertEqual(127887156, ts.entry("tiny")["size_bytes"])
        e = ts.check_models(plant("tiny", "size_bytes: 127887156",
                                  "size_bytes: 1"))
        self.assertEqual(1, next(x for x in e if x["id"] == "tiny")["size_bytes"])

    def test_a_keyword_size_of_zero_is_refused_too(self):
        msg = refused_kw("size_bytes: 15667804", "size_bytes: 0")
        self.assertIn("positive integer", msg)


class ZeroAndNegativeSizeTest(unittest.TestCase):
    """A zero or negative registry size is refused, each on its own, for a
    speech entry and for the keyword entry (a size of 0 is also the Kotlin
    default, so a registry holding one would look unpinned)."""

    def test_size_bytes_zero_is_refused(self):
        msg = ts.refuses(plant("tiny", "size_bytes: 127887156", "size_bytes: 0"))
        self.assertIn("entry 'tiny' line ", msg)
        self.assertIn("size_bytes '0' must be a positive integer", msg)

    def test_size_bytes_minus_one_is_refused(self):
        msg = ts.refuses(plant("tiny", "size_bytes: 127887156", "size_bytes: -1"))
        self.assertIn("entry 'tiny' line ", msg)
        self.assertIn("size_bytes '-1' must be a positive integer", msg)

    def test_a_keyword_size_bytes_of_minus_one_is_refused(self):
        msg = refused_kw("size_bytes: 15667804", "size_bytes: -1")
        self.assertIn("size_bytes '-1' must be a positive integer", msg)

    def test_asset_id_zero_and_minus_one_are_refused(self):
        for bad in ("0", "-1"):
            with self.subTest(bad=bad):
                msg = ts.refuses(plant("tiny", "asset_id: 143510207",
                                       f"asset_id: {bad}"))
                self.assertIn(f"asset_id '{bad}' must be a positive integer", msg)

    def test_size_bytes_one_is_the_accepted_control(self):
        e = ts.check_models(plant("tiny", "size_bytes: 127887156", "size_bytes: 1"))
        self.assertEqual(1, next(x for x in e if x["id"] == "tiny")["size_bytes"])


class KindTest(unittest.TestCase):
    def test_an_unknown_kind_is_refused(self):
        for bad in ("batch", "Offline", "STREAMING", "both"):
            with self.subTest(bad=bad):
                msg = ts.refuses(plant("parakeet", "kind: offline",
                                       f"kind: {bad}"))
                self.assertIn("is not one of streaming, offline", msg)

    def test_both_kinds_are_accepted(self):
        self.assertEqual("offline", ts.entry("parakeet")["kind"])
        self.assertEqual("streaming", ts.entry("small")["kind"])


class OfferedNeedsLabelAndRatingTest(unittest.TestCase):
    def test_offered_without_a_label_is_refused(self):
        msg = ts.refuses(drop("parakeet", "    label: Better"))
        self.assertIn("entry 'parakeet' line ", msg)
        self.assertIn("an offered entry needs a label", msg)

    def test_offered_without_a_rating_is_refused(self):
        msg = ts.refuses(drop("parakeet", "    rating: 2"))
        self.assertIn("entry 'parakeet' line ", msg)
        self.assertIn("an offered entry needs a rating", msg)

    def test_a_label_and_a_rating_make_it_accepted_control(self):
        e = ts.entry("parakeet")
        self.assertEqual(("Better", 2, True), (e["label"], e["rating"], e["offered"]))

    def test_an_unoffered_entry_may_have_neither(self):
        e = ts.entry("tiny")
        self.assertEqual((None, None, False), (e["label"], e["rating"], e["offered"]))

    def test_offered_must_be_true_or_false(self):
        msg = ts.refuses(plant("parakeet", "offered: true", "offered: yes"))
        self.assertIn("'offered' must be exactly true or false", msg)


class LabelAndRatingShapeTest(unittest.TestCase):
    def test_rating_four_and_minus_one_are_refused(self):
        for bad in ("4", "-1", "10", "two", "1.5", "03"):
            with self.subTest(bad=bad):
                msg = ts.refuses(plant("parakeet", "rating: 2",
                                       f"rating: {bad}"))
                self.assertIn("a whole number from 0 to 3", msg)

    def test_the_edges_zero_and_three_are_accepted(self):
        for ok in (0, 3):
            with self.subTest(ok=ok):
                e = ts.check_models(plant("parakeet", "rating: 2",
                                          f"rating: {ok}"))
                self.assertEqual(ok, next(
                    x for x in e if x["id"] == "parakeet")["rating"])

    def test_an_empty_or_padded_label_is_refused(self):
        for bad in ('""', '" "', '" Better"', '"Better "'):
            with self.subTest(bad=bad):
                msg = ts.refuses(plant("parakeet", "label: Better",
                                       f"label: {bad}"))
                self.assertIn("label", msg)


class SpeechOnlyFieldsTest(unittest.TestCase):
    def test_a_keyword_entry_with_a_speech_only_field_is_refused(self):
        for line in ("kind: streaming", "offered: false", "label: Good",
                     "rating: 1"):
            with self.subTest(line=line):
                msg = refused_kw("    size_bytes: 15667804\n",
                                 "    size_bytes: 15667804\n    " + line + "\n")
                self.assertIn("belongs to a speech entry only", msg)

    def test_a_speech_entry_missing_a_required_new_field_is_refused(self):
        for field, line in (("kind", "    kind: offline"),
                            ("size_bytes", "    size_bytes: 487211443"),
                            ("asset_id", "    asset_id: 719769366"),
                            ("offered", "    offered: true")):
            with self.subTest(field=field):
                msg = ts.refuses(drop("parakeet", line))
                self.assertIn(f"required field '{field}' is missing", msg)

    def test_a_keyword_entry_missing_the_pin_is_refused(self):
        msg = refused_kw("    size_bytes: 15667804\n", "")
        self.assertIn("required field 'size_bytes' is missing", msg)


class ListRulesStillHoldTest(unittest.TestCase):
    def test_a_keyword_id_that_is_also_a_new_speech_id_is_refused(self):
        msg = refused_kw("id: " + KW_ID, "id: parakeet")
        self.assertIn("also the id of a speech model", msg)

    def test_a_planted_duplicate_of_the_new_entry_is_refused(self):
        block = "\n".join(ts.entry_block("parakeet"))
        text = replace_once(ts.models_text(), "\n# Keyword-spotting models.",
                            block + "\n\n# Keyword-spotting models.")
        msg = ts.refuses(text)
        self.assertIn("duplicate id 'parakeet'", msg)


if __name__ == "__main__":
    unittest.main()
