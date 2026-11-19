"""The committed engine data of the model registry and the Kotlin generated from
it (ADR-050 J: models are data).

Groups: the committed values of every speech entry against the frozen table;
the one supported-set module and the Kotlin it is generated into; the Kotlin
names other modules read; the notice, byte for byte, against the ADR text.

Built: the generator, the data and these tests. Not compiled: the generated
Kotlin (nobody compiles this tree here). Not built: any engine path for the
whisper entries.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import os
import re
import unittest

import model_registry_support as ts  # first: it puts tools/ on sys.path
import gen_model_registry as gmr
import model_types

MIB = 1048576
ADR = os.path.join(ts.ROOT, "decisions",
                   "ADR-050-two-speech-models-user-chooses.md")
NOTICE_OPENING = "This app includes the Parakeet TDT 0.6B v3 model"


def adr_notice():
    """The ADR-050 H block quote, taken from the ADR file by its fixed opening
    words, without the leading `> `. Exactly one line must carry it."""
    with open(ADR, "r", encoding="utf-8") as fh:
        found = [l.strip() for l in fh.read().splitlines()
                 if l.strip().startswith("> " + NOTICE_OPENING)]
    if len(found) != 1:
        raise AssertionError(
            f"expected exactly one ADR-050 line opening with {NOTICE_OPENING!r}, "
            f"got {len(found)} (file: {ADR})")
    return found[0][2:]


def kotlin_literal(text, const):
    """The Kotlin string literal of `notice = "..."` inside the `const` entry
    block of generated `text`, read back through the three escapes the
    generator writes (backslash, quote, dollar)."""
    m = re.search(rf"    val {const}: ModelEntry = ModelEntry\(.*?\n    \)",
                  text, re.S)
    assert m, const
    lit = re.search(r'^        notice = "((?:[^"\\]|\\.)*)",$', m.group(0), re.M)
    assert lit, f"no notice line in {const}"
    return re.sub(r"\\(.)", r"\1", lit.group(1))


# id -> (model_type, files(enc, dec, join, tok) | None, limits(entry, written,
# stream MiB, entries, depth) | None, language | None, default)
FREEZE = {
    "small": ("", ("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                   "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
              (300, 160, 448, 32, 4), "en", True),
    "tiny": ("", ("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                  "joiner-epoch-99-avg-1.onnx", "tokens.txt"),
             (128, 80, 160, 32, 4), "en", False),
    "base": ("whisper", None, None, None, False),
    "medium": ("whisper", None, None, None, False),
    "parakeet": ("nemo_transducer", ("encoder.int8.onnx", "decoder.int8.onnx",
                                     "joiner.int8.onnx", "tokens.txt"),
                 (640, 704, 720, 32, 4), "", False),
}


class CommittedEngineDataTest(unittest.TestCase):
    def setUp(self):
        self.speech, self.kw = gmr.check_registry(ts.models_text())
        self.by = {e["id"]: e for e in self.speech}

    def test_every_entry_carries_exactly_the_frozen_engine_fields(self):
        self.assertEqual(sorted(FREEZE), sorted(self.by))
        for eid, (mtype, files, lim, lang, _) in FREEZE.items():
            e = self.by[eid]
            with self.subTest(id=eid):
                self.assertEqual(mtype, e["model_type"])
                self.assertEqual(
                    None if files is None else dict(zip(
                        ("files_encoder", "files_decoder", "files_joiner",
                         "files_tokens"), files)), e["files"])
                want = None if lim is None else {
                    "limit_entry_bytes": lim[0] * MIB,
                    "limit_written_bytes": lim[1] * MIB,
                    "limit_stream_bytes": lim[2] * MIB,
                    "limit_entries": lim[3], "limit_depth": lim[4]}
                self.assertEqual(want, e["limits"])
                self.assertEqual(lang, e["language"])

    def test_the_default_is_small_alone_and_it_is_offered(self):
        self.assertEqual(["small"], [e["id"] for e in self.speech if e["default"]])
        self.assertTrue(self.by["small"]["offered"])
        self.assertEqual({k: v[4] for k, v in FREEZE.items()},
                         {k: e["default"] for k, e in self.by.items()})

    def test_the_whisper_entries_are_unsupported_and_not_offered(self):
        for eid in ("base", "medium"):
            e = self.by[eid]
            with self.subTest(id=eid):
                self.assertFalse(model_types.is_supported(e["kind"], e["model_type"]))
                self.assertFalse(e["offered"])
                self.assertIsNone(e["files"])
                self.assertIsNone(e["limits"])

    def test_every_offered_entry_is_supported_and_complete(self):
        for e in self.speech:
            if e["offered"]:
                with self.subTest(id=e["id"]):
                    self.assertTrue(model_types.is_supported(e["kind"], e["model_type"]))
                    self.assertIsNotNone(e["files"])
                    self.assertIsNotNone(e["limits"])
                    self.assertIsNotNone(e["language"])

    def test_only_parakeet_carries_a_notice(self):
        self.assertEqual({"parakeet"}, {e["id"] for e in self.speech if e["notice"]})
        self.assertEqual([], [e for e in self.kw if "notice" in e])

    def test_keyword_entries_carry_no_engine_field(self):
        for e in self.kw:
            for f in ("model_type", "files", "limits", "language", "default", "notice"):
                self.assertNotIn(f, e)

    def test_every_limit_covers_the_pinned_size_and_the_stream_covers_the_rest(self):
        for e in self.speech:
            if e["limits"]:
                lim = e["limits"]
                with self.subTest(id=e["id"]):
                    self.assertGreaterEqual(lim["limit_stream_bytes"], e["size_bytes"])
                    self.assertGreaterEqual(lim["limit_stream_bytes"], lim["limit_entry_bytes"])
                    self.assertGreaterEqual(lim["limit_stream_bytes"], lim["limit_written_bytes"])


class SupportedSetTest(unittest.TestCase):
    def test_the_supported_set_is_exactly_streaming_empty_and_offline_nemo(self):
        self.assertEqual((("streaming", ""), ("offline", "nemo_transducer")),
                         model_types.SUPPORTED)

    def test_the_generated_kotlin_lists_the_same_pairs_in_the_same_order(self):
        text = gmr.render_kotlin(*gmr.check_registry(ts.models_text()))
        self.assertIn(
            '    val SUPPORTED_TYPES: Set<Pair<ModelKind, String>> = setOf('
            'ModelKind.STREAMING to "", ModelKind.OFFLINE to "nemo_transducer")\n',
            text)

    def test_the_committed_kotlin_is_what_the_generator_renders(self):
        with open(ts.GENERATED_KOTLIN, "r", encoding="utf-8") as fh:
            self.assertEqual(
                gmr.render_kotlin(*gmr.check_registry(ts.models_text())), fh.read())


class GeneratedKotlinNamesTest(unittest.TestCase):
    def setUp(self):
        self.text = gmr.render_kotlin(*gmr.check_registry(ts.models_text()))

    def test_the_two_new_data_classes(self):
        for want in (
                "data class ModelFiles(\n    val encoder: String,\n    val decoder: String,\n"
                "    val joiner: String,\n    val tokens: String,\n)",
                "data class ModelLimits(\n    val maxEntryBytes: Long,\n"
                "    val maxWrittenBytes: Long,\n    val maxStreamBytes: Long,\n"
                "    val maxEntries: Int,\n    val maxDepth: Int,\n)"):
            self.assertIn(want, self.text)

    def test_the_supported_check_names_every_part_it_needs(self):
        self.assertIn("    val isSupported: Boolean\n        get() = modelType != null "
                      "&& files != null && limits != null &&\n            language != null &&\n"
                      "            ModelRegistry.SUPPORTED_TYPES.contains(Pair(kind, modelType))\n}",
                      self.text)

    def test_the_parakeet_engine_lines_are_exact(self):
        self.assertIn(
            '        modelType = "nemo_transducer",\n'
            '        files = ModelFiles(encoder = "encoder.int8.onnx", decoder = '
            '"decoder.int8.onnx", joiner = "joiner.int8.onnx", tokens = "tokens.txt"),\n'
            "        limits = ModelLimits(maxEntryBytes = 671088640L, maxWrittenBytes = "
            "738197504L, maxStreamBytes = 754974720L, maxEntries = 32, maxDepth = 4),\n"
            '        language = "",\n        notice = "', self.text)

    def test_small_is_the_default_and_whisper_has_a_type_and_nothing_else(self):
        self.assertEqual(1, self.text.count("        isDefault = true,\n"))
        small = re.search(r"    val SMALL: ModelEntry = ModelEntry\(.*?\n    \)",
                          self.text, re.S).group(0)
        self.assertIn("        isDefault = true,\n", small)
        self.assertIn('        language = "en",\n', small)
        base = re.search(r"    val BASE: ModelEntry = ModelEntry\(.*?\n    \)",
                         self.text, re.S).group(0)
        self.assertTrue(base.endswith('        offered = false,\n'
                                      '        modelType = "whisper",\n    )'))


class NoticeIsByteExactTest(unittest.TestCase):
    def test_the_yaml_value_equals_the_adr_text(self):
        self.assertEqual(adr_notice(), ts.entry("parakeet")["notice"])

    def test_the_generated_kotlin_constant_equals_the_adr_text(self):
        text = gmr.render_kotlin(*gmr.check_registry(ts.models_text()))
        self.assertEqual(adr_notice(), kotlin_literal(text, "PARAKEET"))
        with open(ts.GENERATED_KOTLIN, "r", encoding="utf-8") as fh:
            self.assertEqual(adr_notice(), kotlin_literal(fh.read(), "PARAKEET"))

    def test_the_adr_text_is_the_expected_single_paragraph(self):
        text = adr_notice()
        self.assertTrue(text.startswith(NOTICE_OPENING))
        self.assertTrue(text.endswith("converted/quantized from the original."))
        self.assertNotIn("\n", text)
        self.assertIn("CC BY 4.0", text)


if __name__ == "__main__":
    unittest.main()
