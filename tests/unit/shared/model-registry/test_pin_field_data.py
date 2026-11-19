"""The committed registry data and the generated Kotlin for the pin fields.

Three groups: the committed values (five speech entries, who is offered, the
label and rating of each, size and asset id of each), the exact Kotlin the
generator renders for them, and the rule that lets an archive with no upstream
checksum line (parakeet) ship, which must stop applying the moment upstream does
list it.

Not built: nothing outside the registry reads these fields yet.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts  # first: it puts tools/ on sys.path
import gen_model_registry as gmr
import model_registry_fields as mrf
from test_pin_field_refusals import plant

PARAKEET_ARCHIVE = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
PARAKEET_SHA = "1495e91b59d7fc53ddca91f3431758ec99f8fa253fdb0e1e851be0f72b14b0f9"


class CommittedValuesTest(unittest.TestCase):
    def setUp(self):
        self.speech, self.kw = gmr.check_registry(ts.models_text())
        self.by = {e["id"]: e for e in self.speech}

    def test_the_speech_list_is_exactly_five_in_file_order(self):
        self.assertEqual(["small", "tiny", "base", "medium", "parakeet"],
                         [e["id"] for e in self.speech])

    def test_the_pinned_sizes_and_asset_ids(self):
        want = {"small": (365748162, 191972150), "tiny": (127887156, 143510207),
                "base": (208576005, 196350763), "medium": (1905872689, 179372814),
                "parakeet": (487211443, 719769366)}
        got = {i: (e["size_bytes"], e["asset_id"]) for i, e in self.by.items()}
        self.assertEqual(want, got)
        self.assertEqual((15667804, 191975328),
                         (self.kw[0]["size_bytes"], self.kw[0]["asset_id"]))

    def test_size_mb_is_the_mib_rounding_of_size_bytes_for_every_entry(self):
        for e in self.speech + self.kw:
            with self.subTest(id=e["id"]):
                self.assertEqual((e["size_bytes"] + 524288) // 1048576,
                                 e["size_mb"])

    def test_every_url_ends_with_its_asset_id(self):
        for e in self.speech + self.kw:
            with self.subTest(id=e["id"]):
                self.assertEqual(str(e["asset_id"]), ts.asset_id_of(e["url"]))

    def test_offered_is_exactly_small_and_parakeet(self):
        self.assertEqual(["small", "parakeet"],
                         [e["id"] for e in self.speech if e["offered"]])

    def test_only_offered_entries_carry_a_label_and_a_rating(self):
        for e in self.speech:
            with self.subTest(id=e["id"]):
                has = e["label"] is not None or e["rating"] is not None
                self.assertEqual(e["offered"], has)
                self.assertEqual(e["offered"], e["label"] is not None
                                 and e["rating"] is not None)

    def test_labels_ratings_and_kinds(self):
        got = {i: (e["kind"], e["label"], e["rating"]) for i, e in self.by.items()}
        self.assertEqual({
            "small": ("streaming", "Good", 1), "tiny": ("streaming", None, None),
            "base": ("offline", None, None),
            "medium": ("offline", None, None),
            "parakeet": ("offline", "Better", 2)}, got)

    def test_the_parakeet_pin_is_our_own_hash_and_licence(self):
        e = self.by["parakeet"]
        self.assertEqual(PARAKEET_SHA, e["sha256"])
        self.assertEqual(PARAKEET_ARCHIVE, e["archive_name"])
        self.assertEqual("CC-BY-4.0", e["licence"])
        self.assertEqual("", e["upstream_commit"])
        self.assertFalse(e["tamper_verified"])


class RenderedKotlinTest(unittest.TestCase):
    def setUp(self):
        self.text = gmr.render_kotlin(*gmr.check_registry(ts.models_text()))

    def block(self, const):
        m = re.search(rf"    val {const}: ModelEntry = ModelEntry\(.*?\n    \)",
                      self.text, re.S)
        self.assertIsNotNone(m, const)
        return m.group(0)

    def test_the_data_class_appends_the_new_fields_after_archive_name(self):
        want = ('    val archiveName: String = "",\n'
                "    val kind: ModelKind = ModelKind.STREAMING,\n"
                "    val sizeBytes: Long = 0L,\n    val assetId: Long = 0L,\n"
                "    val label: String? = null,\n    val rating: Int? = null,\n"
                "    val offered: Boolean = false,\n"
                "    val modelType: String? = null,\n"
                "    val files: ModelFiles? = null,\n"
                "    val limits: ModelLimits? = null,\n"
                "    val language: String? = null,\n"
                "    val isDefault: Boolean = false,\n"
                "    val notice: String? = null,\n) {")
        self.assertIn(want, self.text)
        self.assertIn("enum class ModelKind {\n    STREAMING,\n    OFFLINE,\n}",
                      self.text)

    def test_the_parakeet_block_is_exact(self):
        block = self.block("PARAKEET")
        self.assertTrue(block.startswith(
            "    val PARAKEET: ModelEntry = ModelEntry(\n"), block[:60])
        want = ('        archiveName = "' + PARAKEET_ARCHIVE + '",\n'
                "        kind = ModelKind.OFFLINE,\n        sizeBytes = 487211443L,\n"
                "        assetId = 719769366L,\n        label = \"Better\",\n"
                "        rating = 2,\n        offered = true,\n")
        # the pin and presentation lines are exact; the engine lines that
        # follow them are pinned in test_engine_field_data.py
        self.assertIn(want, block)
        self.assertTrue(block.endswith("\n    )"))

    def test_small_is_good_one_and_tiny_has_no_label_line(self):
        self.assertIn('        label = "Good",\n        rating = 1,\n'
                      "        offered = true,\n", self.block("SMALL"))
        tiny = self.block("TINY")
        self.assertNotIn("label =", tiny)
        self.assertNotIn("rating =", tiny)
        self.assertIn("        offered = false,\n", tiny)

    def test_a_keyword_block_carries_only_the_pin(self):
        kw = self.block("KWS_GIGASPEECH_3M")
        self.assertIn("        sizeBytes = 15667804L,\n        assetId = 191975328L,\n", kw)
        for field in ("kind =", "label =", "rating =", "offered ="):
            self.assertNotIn(field, kw)

    def test_all_lists_the_five_in_order_and_the_file_is_fresh(self):
        self.assertIn("listOf(SMALL, TINY, BASE, MEDIUM, PARAKEET)", self.text)
        with open(ts.GENERATED_KOTLIN, "r", encoding="utf-8") as fh:
            self.assertEqual(self.text, fh.read())


class NoUpstreamLineRuleTest(unittest.TestCase):
    LINE = ("other.tar.bz2", "a" * 64)

    def test_the_allow_list_is_exactly_the_parakeet_archive(self):
        self.assertEqual({PARAKEET_ARCHIVE}, set(mrf.NO_UPSTREAM_LINE))

    def test_the_committed_fixture_holds_no_line_for_it(self):
        _, lines = ts.fixture_data()
        self.assertEqual([], [f for f, _ in lines if f == PARAKEET_ARCHIVE])

    def test_a_listed_pair_is_accepted(self):
        self.assertTrue(mrf.archive_pair_ok("other.tar.bz2", "a" * 64, [self.LINE]))

    def test_an_unlisted_archive_with_no_line_is_refused(self):
        self.assertFalse(mrf.archive_pair_ok("unlisted.tar.bz2", "b" * 64, [self.LINE]))

    def test_the_allowed_archive_with_no_line_is_accepted(self):
        self.assertTrue(mrf.archive_pair_ok(PARAKEET_ARCHIVE, PARAKEET_SHA, [self.LINE]))

    def test_the_allowed_archive_stops_being_exempt_once_a_line_exists(self):
        other = (PARAKEET_ARCHIVE, "c" * 64)
        self.assertFalse(mrf.archive_pair_ok(PARAKEET_ARCHIVE, PARAKEET_SHA, [other]))
        self.assertTrue(mrf.archive_pair_ok(PARAKEET_ARCHIVE, "c" * 64, [other]))

    def test_the_generator_refuses_a_renamed_archive_with_no_line(self):
        msg = ts.refuses(plant("parakeet", "archive_name: " + PARAKEET_ARCHIVE,
                               "archive_name: renamed-parakeet.tar.bz2"))
        self.assertIn("does not pair with the entry's sha256", msg)

    def test_the_generator_refuses_a_changed_hash_on_a_listed_archive(self):
        flipped = ("0" if ts.entry("tiny")["sha256"][0] != "0" else "1") \
            + ts.entry("tiny")["sha256"][1:]
        msg = ts.refuses(plant("tiny", "sha256: " + ts.entry("tiny")["sha256"],
                               "sha256: " + flipped))
        self.assertIn("does not pair with the entry's sha256", msg)


class UpstreamCommitOptionalTest(unittest.TestCase):
    def test_an_unverified_speech_entry_may_omit_it_and_a_given_one_is_checked(self):
        self.assertEqual("", ts.entry("parakeet")["upstream_commit"])
        msg = ts.refuses(plant("parakeet", "    tamper_verified: false\n",
                               "    tamper_verified: false\n    upstream_commit: 9a65b6ea\n"))
        self.assertIn("40 lowercase hex", msg)

    def test_a_verified_speech_entry_still_needs_it(self):
        msg = ts.refuses(plant("parakeet", "tamper_verified: false",
                               "tamper_verified: true"))
        self.assertIn("required field 'upstream_commit' is missing", msg)


if __name__ == "__main__":
    unittest.main()
