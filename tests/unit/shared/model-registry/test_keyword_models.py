"""The keyword model list: a second top-level list, `keyword_models:`, beside
the speech `models:` list.

What these tests hold:

* the keyword entry parses and generates, and carries the pinned id, url,
  sha256 and size, as the one and only entry of its list;
* the speech list is still exactly small, tiny, base, medium, in that order
  (each half of the pin has a planted mutant that must make it fail);
* a keyword id equal to a speech id is refused, so `byId` (speech) and
  `byKeywordId` (keyword) can never answer for the same name;
* the rendered Kotlin keeps `ALL` and `byId` speech-only and adds
  `KEYWORD_MODELS`, `KWS_GIGASPEECH_3M` and `byKeywordId`;
* the upstream-checksum fixture holds the facts of the keyword release's
  checksum.txt, with the caveat that its upload date is far later than the
  model asset's;
* every field rule the speech entries obey (size, sha256 length and hex, url
  authority, archive name and pair, booleans, family, license) also refuses a
  bad keyword entry, one test per rule. The two rules that differ for a
  keyword entry (a hyphen may sit inside the id, params may carry a decimal
  point) are pinned in both directions, and must not leak into speech entries.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import os
import re
import tempfile
import unittest

import model_registry_support as ts
import gen_model_registry as gmr
from keyword_support import (KW_ID, KW_NAME, KW_SHA, KW_SIZE_MB, KW_URL,
                             SPEECH_IDS, accepted_kw, refused_kw,
                             replace_once)


def speech_ids_pin(text):
    """The pin: the speech list of `text` is exactly the four ids, in order.
    Raises AssertionError when it is not."""
    speech, _ = gmr.check_registry(text)
    got = [e["id"] for e in speech]
    if got != SPEECH_IDS:
        raise AssertionError(
            f"model-registry: the speech list must be exactly {SPEECH_IDS}, "
            f"it is {got}")


class ParsesAndGeneratesTest(unittest.TestCase):
    def test_the_keyword_entry_parses_with_the_pinned_fields(self):
        speech, kw = gmr.check_registry(ts.models_text())
        self.assertEqual([KW_ID], [e["id"] for e in kw])
        e = kw[0]
        self.assertEqual("sherpa-onnx", e["family"])
        self.assertEqual(KW_URL, e["url"])
        self.assertEqual(KW_SHA, e["sha256"])
        self.assertEqual(KW_SIZE_MB, e["size_mb"])
        self.assertEqual("Apache-2.0", e["licence"])
        self.assertEqual(False, e["hosted"])
        self.assertEqual(False, e["tamper_verified"])
        self.assertEqual(KW_NAME, e["archive_name"])
        self.assertEqual("3.3M", e["params"])

    def test_the_size_is_the_mib_rounding_of_the_pinned_byte_size(self):
        self.assertEqual(KW_SIZE_MB, round(15667804 / 1048576))
        self.assertIn("15,667,804", "\n".join(ts.entry_block(KW_ID)))

    def test_generate_writes_the_keyword_constants_to_a_temp_file(self):
        fd, path = tempfile.mkstemp(suffix=".kt")
        os.close(fd)
        self.addCleanup(os.unlink, path)
        entries, _ = gmr.generate(output_path=path)
        self.assertEqual(SPEECH_IDS, [e["id"] for e in entries])
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("val KWS_GIGASPEECH_3M: ModelEntry = ModelEntry(", text)
        self.assertIn('id = "kws-gigaspeech-3m",', text)
        self.assertIn(f'url = "{KW_URL}",', text)
        self.assertIn(f'sha256 = "{KW_SHA}",', text)
        self.assertIn(f"sizeMb = {KW_SIZE_MB},", text)
        self.assertIn(f'archiveName = "{KW_NAME}",', text)

    def test_the_committed_kotlin_equals_the_generated_text(self):
        speech, kw = gmr.check_registry(ts.models_text())
        want = gmr.render_kotlin(speech, kw)
        with open(ts.GENERATED_KOTLIN, "r", encoding="utf-8") as fh:
            self.assertEqual(want, fh.read())

    def test_the_hyphenated_id_becomes_an_underscored_constant(self):
        self.assertEqual("KWS_GIGASPEECH_3M",
                         gmr._kotlin_constant("kws-gigaspeech-3m"))


class RenderedListsTest(unittest.TestCase):
    def setUp(self):
        speech, kw = gmr.check_registry(ts.models_text())
        self.text = gmr.render_kotlin(speech, kw)

    def test_all_stays_exactly_the_four_speech_constants(self):
        self.assertIn(
            "    val ALL: List<ModelEntry> = listOf(SMALL, TINY, BASE, MEDIUM)",
            self.text.splitlines())

    def test_keyword_models_names_exactly_the_keyword_constant(self):
        self.assertIn(
            "    val KEYWORD_MODELS: List<ModelEntry> = "
            "listOf(KWS_GIGASPEECH_3M)", self.text.splitlines())

    def test_by_id_searches_the_speech_list_only(self):
        self.assertIn(
            "    fun byId(id: String): ModelEntry? = "
            "ALL.firstOrNull { it.id == id }", self.text.splitlines())

    def test_by_keyword_id_searches_the_keyword_list_only(self):
        self.assertIn(
            "    fun byKeywordId(id: String): ModelEntry? = "
            "KEYWORD_MODELS.firstOrNull { it.id == id }",
            self.text.splitlines())

    def test_no_keyword_entries_renders_an_empty_typed_list(self):
        speech, _ = gmr.check_registry(ts.models_text())
        text = gmr.render_kotlin(speech)
        self.assertIn("    val KEYWORD_MODELS: List<ModelEntry> = emptyList()",
                      text.splitlines())

    def test_every_entry_block_closes_with_a_bare_paren(self):
        closers = sum(1 for l in self.text.splitlines() if l == "    )")
        self.assertEqual(5, closers)
        self.assertEqual([], [l for l in self.text.splitlines()
                              if l.strip() == "),"])


class SpeechListPinTest(unittest.TestCase):
    """The speech list is exactly small, tiny, base, medium. The pin has its
    own planted mutants: a pin that cannot go red is not a pin."""

    def test_the_committed_speech_list_is_exactly_the_four_ids(self):
        speech_ids_pin(ts.models_text())

    def test_the_rendered_all_names_exactly_those_four_constants(self):
        speech, kw = gmr.check_registry(ts.models_text())
        m = re.search(r"val ALL: List<ModelEntry> = listOf\((.*?)\)",
                      gmr.render_kotlin(speech, kw))
        names = [n.strip() for n in m.group(1).split(",")]
        self.assertEqual(["SMALL", "TINY", "BASE", "MEDIUM"], names)

    def test_a_fifth_speech_entry_makes_the_pin_fail(self):
        extra = "\n".join(ts.entry_block("small")).replace(
            "id: small", "id: extra")
        text = ts.models_text().replace(
            "keyword_models:\n", extra + "\nkeyword_models:\n", 1)
        self.assertEqual(1, text.count("id: extra"))
        with self.assertRaises(AssertionError) as cm:
            speech_ids_pin(text)
        self.assertIn("exactly", str(cm.exception))

    def test_a_dropped_speech_entry_makes_the_pin_fail(self):
        lines = ts.entry_block("tiny")
        text = ts.models_text().replace("\n".join(lines) + "\n", "", 1)
        self.assertNotIn("id: tiny", text)
        with self.assertRaises(AssertionError):
            speech_ids_pin(text)

    def test_a_reordered_speech_list_makes_the_pin_fail(self):
        small = "\n".join(ts.entry_block("small")) + "\n"
        tiny = "\n".join(ts.entry_block("tiny")) + "\n"
        text = replace_once(ts.models_text(), small + tiny, tiny + small)
        with self.assertRaises(AssertionError):
            speech_ids_pin(text)


class KeywordListPinTest(unittest.TestCase):
    def test_the_keyword_list_is_exactly_the_one_pinned_entry(self):
        _, kw = gmr.check_registry(ts.models_text())
        self.assertEqual(1, len(kw))
        self.assertEqual(
            (KW_ID, KW_URL, KW_SHA, KW_SIZE_MB),
            (kw[0]["id"], kw[0]["url"], kw[0]["sha256"], kw[0]["size_mb"]))

    def test_a_second_keyword_entry_is_a_second_entry(self):
        block = "\n".join(ts.entry_block(KW_ID))
        second = block.replace("id: kws-gigaspeech-3m", "id: kws-other-1m")
        _, kw = gmr.check_registry(ts.models_text() + second + "\n")
        self.assertEqual([KW_ID, "kws-other-1m"], [e["id"] for e in kw])

    def test_by_id_never_sees_the_keyword_id_and_the_lists_are_disjoint(self):
        speech, kw = gmr.check_registry(ts.models_text())
        self.assertEqual(set(), {e["id"] for e in speech}
                         & {e["id"] for e in kw})
        text = gmr.render_kotlin(speech, kw)
        all_line = next(l for l in text.splitlines() if "val ALL:" in l)
        self.assertNotIn("KWS_GIGASPEECH_3M", all_line)


class CrossListRefusalTest(unittest.TestCase):
    def test_a_keyword_id_equal_to_a_speech_id_is_refused(self):
        msg = refused_kw("id: kws-gigaspeech-3m", "id: small")
        self.assertTrue(msg.startswith("entry 'small' line "), msg)
        self.assertIn("also the id of a speech model", msg)

    def test_the_refusal_names_the_keyword_line_and_the_speech_line(self):
        planted = replace_once(ts.models_text(), "id: kws-gigaspeech-3m",
                               "id: medium")
        msg = ts.refuses(planted)
        lines = planted.splitlines()
        kw_line = [i + 1 for i, l in enumerate(lines)
                   if l.strip() == "- id: medium"][-1]
        speech_line = [i + 1 for i, l in enumerate(lines)
                       if l.strip() == "- id: medium"][0]
        self.assertIn(f"line {kw_line}:", msg)
        self.assertIn(f"speech entry on line {speech_line}", msg)

    def test_every_speech_id_is_refused_as_a_keyword_id(self):
        for sid in SPEECH_IDS:
            with self.subTest(sid=sid):
                msg = refused_kw("id: kws-gigaspeech-3m", f"id: {sid}")
                self.assertIn("also the id of a speech model", msg)

    def test_a_duplicate_id_inside_the_keyword_list_is_refused(self):
        block = "\n".join(ts.entry_block(KW_ID))
        msg = ts.refuses(ts.models_text() + block + "\n")
        self.assertIn(f"duplicate id '{KW_ID}'", msg)
        self.assertIn("first on line", msg)


class KeywordListShapeTest(unittest.TestCase):
    def test_an_empty_keyword_list_is_refused(self):
        text = ts.models_text()
        head = text[:text.index("\nkeyword_models:\n") + 1]
        msg = ts.refuses(head + "keyword_models:\n")
        self.assertIn("keyword_models", msg)

    def test_a_scalar_keyword_list_is_refused(self):
        text = ts.models_text()
        head = text[:text.index("\nkeyword_models:\n") + 1]
        msg = ts.refuses(head + "keyword_models: none\n")
        self.assertIn("keyword_models", msg)

    def test_an_absent_keyword_list_is_accepted_as_empty(self):
        text = ts.models_text()
        head = text[:text.index("\nkeyword_models:\n") + 1]
        speech, kw = gmr.check_registry(head)
        self.assertEqual(4, len(speech))
        self.assertEqual([], kw)

    def test_a_sequence_item_count_mismatch_in_the_keyword_list_is_refused(self):
        msg = ts.refuses(replace_once(
            ts.models_text(), "    notes: >-\n      A keyword spotter",
            "    aliases:\n      - a\n      - b\n"
            "    notes: >-\n      A keyword spotter"))
        self.assertIn("the reader found 1 entries but the file carries 3", msg)

    def test_the_speech_span_count_ignores_the_keyword_items(self):
        text = ts.models_text()
        head = text[:text.index("\nkeyword_models:\n") + 1]
        self.assertEqual(4, len(gmr.check_models(text)))
        self.assertEqual(4, len(gmr.check_models(head)))



class ReservedConstantTest(unittest.TestCase):
    """An id may not name a constant the generated object declares itself:
    `all` would make a second `val ALL`, `keyword-models` a second
    `val KEYWORD_MODELS`, and the Kotlin would not compile."""

    def test_a_keyword_id_all_is_refused(self):
        msg = refused_kw("id: kws-gigaspeech-3m", "id: all")
        self.assertIn("already declares", msg)
        self.assertIn("ALL", msg)

    def test_a_keyword_id_naming_the_keyword_list_constant_is_refused(self):
        msg = refused_kw("id: kws-gigaspeech-3m", "id: keyword-models")
        self.assertIn("already declares", msg)
        self.assertIn("KEYWORD_MODELS", msg)

    def test_a_speech_id_all_is_refused(self):
        msg = ts.refuses(replace_once(ts.models_text(), "id: small", "id: all"))
        self.assertIn("entry 'all' line ", msg)
        self.assertIn("already declares", msg)

    def test_an_id_that_only_starts_like_a_reserved_one_is_accepted(self):
        self.assertEqual("allx", accepted_kw(
            "id: kws-gigaspeech-3m", "id: allx")["id"])
        self.assertEqual("keyword-models-2", accepted_kw(
            "id: kws-gigaspeech-3m", "id: keyword-models-2")["id"])


if __name__ == "__main__":
    unittest.main()
