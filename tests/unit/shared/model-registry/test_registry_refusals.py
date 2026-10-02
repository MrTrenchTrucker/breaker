"""The generator refuses a bad models.yaml entry BEFORE it writes anything.

One test per refusal class named by the module card's Invariants and the
registry format: missing required field, unknown field, duplicate id, id
shape, family, sha256 shape, upstream_commit shape, url rule (tag route,
"latest", another host, a trailing path segment, a non-https scheme, a query
or fragment), size_mb, params, the boolean fields, and the license rule.
Each test plants the bad shape in the real entry's text and asserts the
refusal names the entry's line and the reason; a plant the generator would
ACCEPT fails the test (the helper raises).

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


class MissingRequiredFieldTest(unittest.TestCase):
    def test_missing_required_field_is_refused_with_field_and_line(self):
        text = ts.models_text()
        planted = "\n".join(l for l in text.splitlines() if not l.startswith("    family:"))
        msg = ts.refuses(planted)
        self.assertIn("family", msg)
        self.assertIn("missing", msg)
        # the line is the entry's line (the entry marker carries the id)
        m = re.search(r"line (\d+):", msg)
        self.assertIsNotNone(m)
        entry_line = next(i + 1 for i, l in enumerate(text.splitlines())
                          if l.strip().startswith("- id:"))
        self.assertEqual(int(m.group(1)), entry_line)


class UnknownFieldTest(unittest.TestCase):
    def test_unknown_field_is_refused_with_field_and_line(self):
        text = ts.models_text()
        planted = replace_once(text, "    family: sherpa-onnx",
                               "    family: sherpa-onnx\n    flavour: vanilla")
        msg = ts.refuses(planted)
        self.assertIn("flavour", msg)
        self.assertIn("unknown field", msg)
        m = re.search(r"line (\d+):", msg)
        self.assertEqual(int(m.group(1)), 14)  # the planted line, in the real file


class DuplicateIdTest(unittest.TestCase):
    def test_duplicate_id_is_refused_naming_both_lines(self):
        text = ts.models_text()
        second = text.split("models:\n", 1)[1]
        planted = text + second  # a full second entry, marker at line 32
        msg = ts.refuses(planted)
        self.assertIn("duplicate id 'small'", msg)
        self.assertIn("first on line 12", msg)
        self.assertIn("line 32:", msg)  # the second entry, where the clash sits


class IdShapeTest(unittest.TestCase):
    def test_id_with_uppercase_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "id: small", "id: Small")
        msg = ts.refuses(planted)
        self.assertIn("lowercase alphanumerics", msg)


class FamilyTest(unittest.TestCase):
    def test_family_outside_the_enum_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "family: sherpa-onnx", "family: whisperx")
        msg = ts.refuses(planted)
        self.assertIn("'whisperx'", msg)
        self.assertIn("sherpa-onnx, whisper", msg)


class Sha256ShapeTest(unittest.TestCase):
    def test_short_sha256_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "sha256: b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
                               "sha256: b97e7ff7")
        msg = ts.refuses(planted)
        self.assertIn("64", msg)
        self.assertIn("hex", msg)

    def test_uppercase_sha256_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "sha256: b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
                               "sha256: B97E7FF75A27136F4DD33C99BB7D5F493094397501BE4739578C6CD95EA7F422")
        msg = ts.refuses(planted)
        self.assertIn("lowercase hex", msg)

    def test_sha256_of_40_hex_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "sha256: b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
                               "sha256: " + "ab" * 20)
        msg = ts.refuses(planted)
        self.assertIn("64", msg)
        self.assertIn("hex", msg)

    def test_sha256_of_63_hex_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "sha256: b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
                               "sha256: " + "a" * 63)
        msg = ts.refuses(planted)
        self.assertIn("64", msg)
        self.assertIn("hex", msg)

    def test_sha256_of_65_hex_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "sha256: b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
                               "sha256: " + "a" * 65)
        msg = ts.refuses(planted)
        self.assertIn("64", msg)
        self.assertIn("hex", msg)



class UpstreamCommitShapeTest(unittest.TestCase):
    def test_short_upstream_commit_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "upstream_commit: 9a65b6ea94c311ca770c2bf895b30f456a22d703",
                               "upstream_commit: 9a65b6ea")
        msg = ts.refuses(planted)
        self.assertIn("40", msg)
        self.assertIn("upstream_commit", msg)

    def test_upstream_commit_uppercase_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "upstream_commit: 9a65b6ea94c311ca770c2bf895b30f456a22d703",
                               "upstream_commit: 9A65B6EA94C311CA770C2BF895B30F456A22D703")
        msg = ts.refuses(planted)
        self.assertIn("upstream_commit", msg)
        self.assertIn("lowercase hex", msg)


class UrlRuleTest(unittest.TestCase):
    GOOD = "url: https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150"

    def _refused(self, url):
        msg = ts.refuses(replace_once(ts.models_text(), self.GOOD, f"url: {url}"))
        self.assertIn("url", msg)
        return msg

    def test_short_commit_url_is_refused(self):
        msg = self._refused(
            "https://github.com/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/commit/9a65b6e")
        self.assertIn("url", msg)

    def test_fragment_is_refused(self):
        # quoted: an unquoted '#' would start a trailing comment and the
        # reader's guard would refuse before the url rule sees the fragment
        msg = self._refused(
            '"https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150#x"')
        self.assertIn("query or fragment", msg)

    def test_letter_in_asset_id_is_refused(self):
        self._refused(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/19197215x")

    def test_url_refusal_names_the_entry(self):
        msg = self._refused(
            "http://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150")
        self.assertTrue(msg.startswith("entry 'small' line "))

    def test_tag_route_is_refused(self):
        msg = self._refused(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/x.tar.bz2")
        self.assertIn("not pinned", msg)

    def test_latest_is_refused(self):
        msg = self._refused(
            "https://github.com/k2-fsa/sherpa-onnx/releases/latest/x.tar.bz2")
        self.assertIn("not pinned", msg)

    def test_another_host_is_refused(self):
        msg = self._refused(
            "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/releases/assets/1")
        self.assertIn("raw.githubusercontent.com", msg)

    def test_trailing_path_segment_is_refused(self):
        self._refused(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150/extra")

    def test_non_https_scheme_is_refused(self):
        msg = self._refused(
            "http://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150")
        self.assertIn("https", msg)

    def test_query_is_refused(self):
        msg = self._refused(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/191972150?x=1")
        self.assertIn("query or fragment", msg)

    def test_commit_naming_url_is_accepted(self):
        # the card's other accepted shape: a URL naming a full 40-char commit
        text = replace_once(
            ts.models_text(), self.GOOD,
            "url: https://github.com/csukuangfj/sherpa-onnx-streaming-zipformer-"
            "en-2023-06-21/commit/9a65b6ea94c311ca770c2bf895b30f456a22d703")
        entries = ts.check_models(text)
        self.assertEqual(entries[0]["id"], "small")


class SizeMbTest(unittest.TestCase):
    def test_non_integer_size_is_refused(self):
        planted = replace_once(ts.models_text(), "size_mb: 349", "size_mb: 34.5")
        msg = ts.refuses(planted)
        self.assertIn("size_mb", msg)
        self.assertIn("positive integer", msg)

    def test_zero_size_is_refused(self):
        planted = replace_once(ts.models_text(), "size_mb: 349", "size_mb: 0")
        msg = ts.refuses(planted)
        self.assertIn("size_mb", msg)


class ParamsTest(unittest.TestCase):
    def test_params_without_the_m_suffix_is_refused(self):
        planted = replace_once(ts.models_text(), "params: 70M", "params: 20")
        msg = ts.refuses(planted)
        self.assertIn("params", msg)
        self.assertIn("M suffix", msg)


class BooleanTest(unittest.TestCase):
    def test_hosted_with_a_word_other_than_true_false_is_refused(self):
        planted = replace_once(ts.models_text(), "hosted: false", "hosted: no")
        msg = ts.refuses(planted)
        self.assertIn("hosted", msg)
        self.assertIn("true or false", msg)

    def test_tamper_verified_with_a_digit_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "tamper_verified: false", "tamper_verified: 0")
        msg = ts.refuses(planted)
        self.assertIn("tamper_verified", msg)


class LicenseTest(unittest.TestCase):
    def test_no_spdx_id_and_no_name_link_is_refused(self):
        planted = "\n".join(l for l in ts.models_text().splitlines()
                            if not l.startswith("    license:"))
        msg = ts.refuses(planted)
        self.assertIn("license", msg)
        self.assertIn("license_name", msg)

    def test_spdx_id_with_whitespace_is_refused(self):
        planted = replace_once(ts.models_text(),
                               "license: Apache-2.0", "license: Apache 2.0")
        msg = ts.refuses(planted)
        self.assertIn("SPDX", msg)

    def test_name_plus_link_is_accepted(self):
        text = ts.models_text()
        text = "\n".join(l for l in text.splitlines() if not l.startswith("    license:"))
        # the replacement fields sit inside the entry, before the notes block
        text = text.replace(
            "    notes: >-",
            "    license_name: Custom ASR License\n"
            "    license_link: https://example.invalid/license\n"
            "    notes: >-",
        )
        entries = ts.check_models(text)
        self.assertEqual(entries[0]["licence"], "Custom ASR License")

class EntryNamedTest(unittest.TestCase):
    def test_refusal_names_the_entry_and_line(self):
        text = ts.models_text()
        planted = replace_once(text, "sha256: b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422", "sha256: " + "a" * 63)
        msg = ts.refuses(planted)
        self.assertTrue(msg.startswith("entry 'small' line "), f"refusal did not name the entry: {msg!r}")
        self.assertIn("sha256", msg)


if __name__ == "__main__":
    unittest.main()
