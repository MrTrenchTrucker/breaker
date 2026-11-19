"""Engine-field refusals, part two: file names, limits, language, notice and
unknown keys. Each is planted inside ONE entry of the committed text and paired
with its nearest accepted value (rules: tools/model_registry_engine.py). Built
and run here; the app code that reads these fields is another module's work.

The limit rules: the stream limit must cover the pinned size, the entry limit
and the written limit. A written limit below the entry limit is NOT refused,
because the committed `small` and `tiny` data have one (a large entry may be
read past without being written); the test below shows both are accepted.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts
import gen_model_registry as gmr
from keyword_support import replace_once
from test_pin_field_refusals import plant


def accepted(text):
    return {e["id"]: e for e in gmr.check_registry(text)[0]}


class FileNameRefusalTest(unittest.TestCase):
    def test_an_empty_file_name_is_refused(self):
        msg = ts.refuses(plant("parakeet", "files_tokens: tokens.txt",
                               'files_tokens: ""'))
        self.assertIn("files_tokens must be a non-empty file name", msg)

    def test_a_file_name_with_a_slash_is_refused(self):
        msg = ts.refuses(plant("parakeet", "files_encoder: encoder.int8.onnx",
                               "files_encoder: sub/encoder.int8.onnx"))
        self.assertIn("files_encoder 'sub/encoder.int8.onnx' must be a plain file name", msg)

    def test_a_file_name_with_a_backslash_is_refused(self):
        msg = ts.refuses(plant("parakeet", "files_joiner: joiner.int8.onnx",
                               'files_joiner: "a\\\\joiner.onnx"'))
        self.assertIn("files_joiner", msg)
        self.assertIn("plain file name", msg)

    def test_a_file_name_with_dot_dot_is_refused(self):
        for name in ("..", "..tokens.txt", "a..b"):
            with self.subTest(name=name):
                msg = ts.refuses(plant("parakeet", "files_decoder: decoder.int8.onnx",
                                       f"files_decoder: {name}"))
                self.assertIn("plain file name", msg)

    def test_a_plain_name_with_dots_is_accepted(self):
        by = accepted(plant("parakeet", "files_decoder: decoder.int8.onnx",
                            "files_decoder: decoder.v2.int8.onnx"))
        self.assertEqual("decoder.v2.int8.onnx", by["parakeet"]["files"]["files_decoder"])


class LimitRefusalTest(unittest.TestCase):
    FIELDS = ("limit_entry_bytes", "limit_written_bytes", "limit_stream_bytes",
              "limit_entries", "limit_depth")

    def test_a_limit_that_is_zero_negative_or_not_a_number_is_refused(self):
        block = "\n".join(ts.entry_block("tiny"))
        for field in self.FIELDS:
            line = next(l for l in block.splitlines() if l.strip().startswith(field))
            for bad in ("0", "-1", "many"):
                with self.subTest(field=field, bad=bad):
                    msg = ts.refuses(plant("tiny", line, f"    {field}: {bad}"))
                    self.assertIn(f"{field} '{bad}' must be a positive integer", msg)

    def test_a_stream_limit_below_the_pinned_size_is_refused(self):
        text = replace_once(ts.models_text(), "limit_entry_bytes: 134217728",
                            "limit_entry_bytes: 1000")
        text = replace_once(text, "limit_written_bytes: 83886080",
                            "limit_written_bytes: 1000")
        ok = replace_once(text, "limit_stream_bytes: 167772160",
                          "limit_stream_bytes: 127887156")  # equal to size_bytes
        self.assertEqual(127887156, accepted(ok)["tiny"]["limits"]["limit_stream_bytes"])
        msg = ts.refuses(replace_once(text, "limit_stream_bytes: 167772160",
                                      "limit_stream_bytes: 127887155"))
        self.assertIn("limit_stream_bytes 127887155 is below the pinned size_bytes 127887156", msg)

    def test_a_stream_limit_below_the_entry_or_written_limit_is_refused(self):
        for field, big in (("limit_entry_bytes: 134217728", "limit_entry_bytes: 167772161"),
                           ("limit_written_bytes: 83886080", "limit_written_bytes: 167772161")):
            with self.subTest(field=field):
                msg = ts.refuses(plant("tiny", field, big))
                self.assertIn("limit_stream_bytes 167772160 is below", msg)
        edge = plant("tiny", "limit_entry_bytes: 134217728", "limit_entry_bytes: 167772160")
        self.assertEqual(167772160, accepted(edge)["tiny"]["limits"]["limit_entry_bytes"])

    def test_a_written_limit_below_the_entry_limit_is_data_small_and_tiny_carry(self):
        by = accepted(ts.models_text())
        for eid in ("small", "tiny"):
            lim = by[eid]["limits"]
            with self.subTest(id=eid):
                self.assertLess(lim["limit_written_bytes"], lim["limit_entry_bytes"])


class LanguageAndNoticeRefusalTest(unittest.TestCase):
    def test_a_language_of_the_wrong_shape_is_refused(self):
        msg = ts.refuses(plant("small", "language: en", "language: English"))
        self.assertIn("language 'English' must be empty", msg)
        self.assertEqual("en-GB", accepted(plant("small", "language: en",
                                                 "language: en-GB"))["small"]["language"])

    def test_a_blank_notice_is_refused(self):
        for value in ('""', '"   "', ""):
            with self.subTest(value=value):
                text = plant("tiny", "    language: en\n",
                             f"    language: en\n    notice: {value}\n")
                self.assertIn("notice must not be blank", ts.refuses(text))

    def test_a_notice_with_a_trailing_line_break_is_refused(self):
        text = plant("tiny", "    language: en\n",
                     "    language: en\n    notice: >\n      Some text.\n")
        self.assertIn("notice must not be blank or carry leading or trailing", ts.refuses(text))

    def test_a_notice_on_any_speech_entry_is_accepted_when_exact(self):
        text = plant("tiny", "    language: en\n",
                     "    language: en\n    notice: >-\n      Some text.\n")
        self.assertEqual("Some text.", accepted(text)["tiny"]["notice"])


class UnknownAndKeywordKeyTest(unittest.TestCase):
    def test_an_unknown_engine_key_is_refused(self):
        for key in ("files_extra: x", "limit_path_length: 128", "model_types: x"):
            with self.subTest(key=key):
                msg = ts.refuses(plant("tiny", "    language: en\n",
                                       f"    language: en\n    {key}\n"))
                self.assertIn("unknown field", msg)

    def test_a_keyword_entry_with_an_engine_key_is_refused(self):
        block = "\n".join(ts.entry_block("kws-gigaspeech-3m"))
        for key in ("model_type: x", "language: en", "default: false",
                    "notice: hello", "files_tokens: tokens.txt",
                    "limit_depth: 4"):
            with self.subTest(key=key):
                text = ts.models_text().replace(
                    block, block.replace("    size_bytes:", f"    {key}\n    size_bytes:", 1))
                self.assertNotEqual(ts.models_text(), text)
                self.assertEqual(0, text.count(block))  # the plant stuck
                self.assertIn("belongs to a speech entry only", ts.refuses(text))

    def test_the_committed_keyword_entry_is_unchanged_and_accepted(self):
        kw = gmr.check_registry(ts.models_text())[1]
        self.assertEqual(["kws-gigaspeech-3m"], [e["id"] for e in kw])
        self.assertEqual(17626723, kw[0]["size_bytes"])


if __name__ == "__main__":
    unittest.main()
