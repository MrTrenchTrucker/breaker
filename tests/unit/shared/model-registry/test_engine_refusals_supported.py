"""Engine-field refusals, part one: the supported set and the default flag.

Each refusal is planted inside ONE entry of the committed text and paired with
the nearest accepted value, so a refusal that fires on everything fails its
control. Rules: tools/model_registry_engine.py; the supported set:
tools/model_types.py. Built and run here; the app code that reads these fields
is another module's work.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts
import gen_model_registry as gmr
from keyword_support import replace_once
from test_pin_field_refusals import plant


def accepted(text):
    return gmr.check_registry(text)[0]


class SupportedSetRefusalTest(unittest.TestCase):
    def test_an_offered_entry_outside_the_supported_set_is_refused(self):
        msg = ts.refuses(plant("parakeet", "model_type: nemo_transducer",
                               "model_type: whisper"))
        self.assertIn("entry 'parakeet' line ", msg)
        self.assertIn("is outside the supported set", msg)
        self.assertIn("only an entry with offered: false", msg)

    def test_the_pair_counts_not_the_type_alone_offline_with_no_type(self):
        msg = ts.refuses(plant("parakeet", 'model_type: nemo_transducer',
                               'model_type: ""'))
        self.assertIn("kind 'offline' with model_type ''", msg)

    def test_the_pair_counts_not_the_type_alone_streaming_with_nemo(self):
        msg = ts.refuses(plant("small", 'model_type: ""',
                               "model_type: nemo_transducer"))
        self.assertIn("kind 'streaming' with model_type 'nemo_transducer'", msg)

    def test_a_whisper_entry_that_tries_to_be_offered_is_refused(self):
        text = plant("base", "    offered: false\n",
                     "    label: Good\n    rating: 1\n    offered: true\n")
        self.assertIn("is outside the supported set", ts.refuses(text))

    def test_controls_an_unoffered_unsupported_entry_is_accepted(self):
        by = {e["id"]: e for e in accepted(ts.models_text())}
        self.assertFalse(by["base"]["offered"])
        self.assertEqual("whisper", by["base"]["model_type"])
        # an unoffered entry with a supported type, and one without, both pass
        changed = plant("tiny", 'model_type: ""', "model_type: nemo_transducer")
        self.assertEqual("nemo_transducer",
                         {e["id"]: e for e in accepted(changed)}["tiny"]["model_type"])
        other = plant("tiny", 'model_type: ""', "model_type: whisper")
        self.assertEqual("whisper",
                         {e["id"]: e for e in accepted(other)}["tiny"]["model_type"])

    def test_a_missing_model_type_is_refused(self):
        msg = ts.refuses(plant("tiny", '    model_type: ""\n', ""))
        self.assertIn("required field 'model_type' is missing", msg)

    def test_a_model_type_of_the_wrong_shape_is_refused(self):
        msg = ts.refuses(plant("base", "model_type: whisper", "model_type: Whisper"))
        self.assertIn("model_type 'Whisper' must be empty or lowercase", msg)

    def test_an_unsupported_entry_that_tries_to_be_the_default_is_refused(self):
        text = plant("base", "    model_type: whisper\n",
                     "    model_type: whisper\n    default: true\n")
        msg = ts.refuses(text)
        self.assertIn("entry 'base' line ", msg)
        self.assertIn("cannot be the default", msg)
        # the control: the same entry with default: false is accepted
        ok = plant("base", "    model_type: whisper\n",
                   "    model_type: whisper\n    default: false\n")
        self.assertEqual(5, len(accepted(ok)))


class SupportedEntryNeedsItsDataTest(unittest.TestCase):
    def test_a_supported_entry_without_files_is_refused(self):
        text = ts.models_text()
        for f in ("encoder", "decoder", "joiner", "tokens"):
            line = {"encoder": "    files_encoder: encoder.int8.onnx\n",
                    "decoder": "    files_decoder: decoder.int8.onnx\n",
                    "joiner": "    files_joiner: joiner.int8.onnx\n",
                    "tokens": "    files_tokens: tokens.txt\n"}[f]
            with self.subTest(dropped=f):
                msg = ts.refuses(plant("parakeet", line, ""))
                self.assertIn("given in part", msg)
                self.assertIn(f"files_{f}", msg)
        self.assertEqual(5, len(accepted(text)))

    def test_a_supported_entry_with_no_file_names_at_all_is_refused(self):
        block = ts.entry_block("parakeet")
        kept = [l for l in block if not l.strip().startswith("files_")]
        self.assertEqual(len(block) - 4, len(kept))
        msg = ts.refuses(ts.models_text().replace("\n".join(block), "\n".join(kept)))
        self.assertIn("a supported entry needs files_*", msg)

    def test_a_supported_entry_with_no_limits_is_refused(self):
        block = ts.entry_block("tiny")
        kept = [l for l in block if not l.strip().startswith("limit_")]
        self.assertEqual(len(block) - 5, len(kept))
        msg = ts.refuses(ts.models_text().replace("\n".join(block), "\n".join(kept)))
        self.assertIn("a supported entry needs limit_*", msg)

    def test_a_supported_entry_with_part_of_its_limits_is_refused(self):
        msg = ts.refuses(plant("tiny", "    limit_depth: 4\n", ""))
        self.assertIn("limit_* fields are given in part", msg)
        self.assertIn("limit_depth", msg)

    def test_a_supported_entry_with_no_language_is_refused(self):
        msg = ts.refuses(plant("tiny", "    language: en\n", ""))
        self.assertIn("a supported entry needs language", msg)

    def test_the_empty_language_is_a_value_not_an_absence(self):
        by = {e["id"]: e for e in accepted(ts.models_text())}
        self.assertEqual("", by["parakeet"]["language"])
        self.assertIsNone(by["base"]["language"])


class DefaultFlagRefusalTest(unittest.TestCase):
    def test_no_default_at_all_is_refused(self):
        msg = ts.refuses(plant("small", "    default: true\n", ""))
        self.assertIn("exactly one speech entry must carry default: true", msg)
        self.assertIn("found 0: none", msg)

    def test_two_defaults_are_refused_and_both_are_named(self):
        msg = ts.refuses(plant("parakeet", '    language: ""\n',
                               '    language: ""\n    default: true\n'))
        self.assertIn("found 2: small, parakeet", msg)

    def test_a_default_on_an_unoffered_entry_is_refused(self):
        msg = ts.refuses(plant("tiny", "    language: en\n",
                               "    language: en\n    default: true\n"))
        self.assertIn("entry 'tiny' line ", msg)
        self.assertIn("the default entry must be offered", msg)

    def test_the_default_may_move_to_another_offered_entry(self):
        text = replace_once(ts.models_text(), "    default: true\n", "")
        text = replace_once(text, '    language: ""\n',
                            '    language: ""\n    default: true\n')
        self.assertEqual(["parakeet"],
                         [e["id"] for e in accepted(text) if e["default"]])

    def test_a_default_value_other_than_true_or_false_is_refused(self):
        msg = ts.refuses(plant("small", "default: true", "default: yes"))
        self.assertIn("field 'default' must be exactly true or false", msg)

    def test_the_count_covers_every_entry_of_a_one_entry_file(self):
        # tiny alone has no default: the count is over ALL entries of the file
        msg = ts.refuses(ts.single_entry_yaml("tiny"))
        self.assertIn("found 0: none", msg)


if __name__ == "__main__":
    unittest.main()
