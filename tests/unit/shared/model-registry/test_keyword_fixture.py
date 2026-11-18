"""The upstream-checksum fixture carries the keyword release's facts.

The fixture holds the checksum.txt facts (asset id, size, sha256, the mobile
gigaspeech line), the asset id -> file name map, and the plain statement that
this checksum.txt asset was uploaded or updated on 2026-11-18, years after the
model asset, so its provenance is weaker than the speech entries'. The entry's
own notes say the same. The pair check of the generator reads the fixture:
a fixture without the keyword line refuses the keyword entry.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import os
import re
import tempfile
import unittest
from unittest import mock

import model_registry_support as ts
import gen_model_registry as gmr
from keyword_support import (CHECKSUM_TXT_SHA, KW_ID, KW_NAME, KW_PREFIX,
                             KW_SHA)


class FixtureFactsTest(unittest.TestCase):
    def setUp(self):
        with open(ts.FIXTURE, "r", encoding="utf-8") as fh:
            self.text = fh.read()
        self.asset_map, self.lines = ts.fixture_data()

    def test_the_fixture_holds_the_checksum_txt_facts(self):
        for fact in ("761308399", "639 bytes", CHECKSUM_TXT_SHA, "kws-models"):
            self.assertIn(fact, self.text)

    def test_the_mobile_gigaspeech_line_is_a_data_line_exactly_once(self):
        self.assertEqual(1, self.lines.count((KW_NAME, KW_SHA)))
        self.assertIn(f"{KW_NAME}\t{KW_SHA}\n", self.text)

    def test_the_header_maps_the_asset_id_to_the_archive_name(self):
        self.assertEqual(KW_NAME, self.asset_map["191975328"])

    def test_the_caveat_about_the_2026_11_18_upload_is_stated(self):
        flat = re.sub(r"\s*\n#\s*", " ", self.text)
        self.assertIn("CAVEAT: this checksum.txt asset was uploaded or "
                      "updated on 2026-11-18, years after the model asset",
                      flat)
        self.assertIn("provenance is weaker than", flat)

    def test_the_entry_notes_state_the_same_caveat(self):
        notes = " ".join(l.strip() for l in ts.entry_block(KW_ID))
        self.assertIn("2026-11-18", notes)
        self.assertIn("weaker", notes)
        self.assertIn("761308399", notes)

    def test_the_entry_sha_equals_the_fixture_line_through_the_shared_check(self):
        _, kw = gmr.check_registry(ts.models_text())
        ts.assert_sha256_matches_fixture(kw, self.asset_map, self.lines)

    def test_a_flipped_digit_in_the_entry_is_caught_by_the_shared_check(self):
        _, kw = gmr.check_registry(ts.models_text())
        planted = [dict(kw[0], sha256="0" + KW_SHA[1:])]
        with self.assertRaises(AssertionError) as cm:
            ts.assert_sha256_matches_fixture(planted, self.asset_map,
                                             self.lines)
        self.assertIn("drifts from", str(cm.exception))

    def _fixture_without(self, drop_pair):
        with open(ts.FIXTURE, "r", encoding="utf-8") as fh:
            kept = [l for l in fh.readlines() if l.rstrip("\n") != drop_pair]
        fd, path = tempfile.mkstemp(suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.writelines(kept)
        self.addCleanup(os.unlink, path)
        return path

    def test_a_fixture_without_the_keyword_line_refuses_the_entry(self):
        path = self._fixture_without(f"{KW_NAME}\t{KW_SHA}")
        with mock.patch.object(gmr, "FIXTURE", path):
            msg = ts.refuses(ts.models_text())
        self.assertTrue(msg.startswith(KW_PREFIX), msg)
        self.assertIn("does not pair with the entry's sha256", msg)



if __name__ == "__main__":
    unittest.main()
