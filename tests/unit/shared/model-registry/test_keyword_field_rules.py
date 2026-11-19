"""Every field rule of the speech entries also refuses a bad keyword entry.

One test per rule, each planting one bad value in the committed keyword entry
and asserting the refusal names that entry (`entry 'kws-gigaspeech-3m' line
N:`) and says why. A plant the generator would ACCEPT fails the test (the
helper raises). Boundary values are tested on both sides of each range: the
last good value is accepted, the first bad one is refused.

Two rules differ for a keyword entry and are pinned in both directions:
a hyphen may sit inside the id, and params may carry a decimal point. Neither
may leak into the speech list. upstream_commit is optional for a keyword entry
(no source revision is recorded for it) and still required for speech.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import unittest

import model_registry_support as ts
from keyword_support import (KW_ID, KW_NAME, KW_PREFIX, KW_SHA, accepted_kw,
                             refused_kw, replace_once)

KW_URL_LINE = ("url: https://api.github.com/repos/k2-fsa/sherpa-onnx"
               "/releases/assets/166405338")
ASSET_PATH_TAIL = "/repos/k2-fsa/sherpa-onnx/releases/assets/166405338"


def url_refusal(url):
    return refused_kw(KW_URL_LINE, f'url: "{url}"')


class KeywordSizeRuleTest(unittest.TestCase):
    OLD = "size_mb: 17"

    def test_the_first_valid_size_is_accepted(self):
        self.assertEqual(1, accepted_kw(self.OLD, "size_mb: 1")["size_mb"])

    def test_zero_is_refused(self):
        self.assertIn("positive integer", refused_kw(self.OLD, "size_mb: 0"))

    def test_a_decimal_is_refused(self):
        self.assertIn("positive integer", refused_kw(self.OLD, "size_mb: 14.9"))

    def test_a_leading_zero_is_refused(self):
        self.assertIn("size_mb", refused_kw(self.OLD, "size_mb: 015"))

    def test_a_negative_size_is_refused(self):
        self.assertIn("size_mb", refused_kw(self.OLD, "size_mb: -15"))


class KeywordSha256RuleTest(unittest.TestCase):
    OLD = f"sha256: {KW_SHA}"

    def test_63_hex_is_refused(self):
        msg = refused_kw(self.OLD, "sha256: " + "a" * 63)
        self.assertIn("64", msg)
        self.assertIn("hex", msg)

    def test_65_hex_is_refused(self):
        self.assertIn("64", refused_kw(self.OLD, "sha256: " + "a" * 65))

    def test_40_hex_is_refused(self):
        self.assertIn("64", refused_kw(self.OLD, "sha256: " + "ab" * 20))

    def test_uppercase_is_refused(self):
        self.assertIn("lowercase hex", refused_kw(
            self.OLD, "sha256: " + KW_SHA.upper()))

    def test_a_non_hex_letter_is_refused(self):
        self.assertIn("lowercase hex", refused_kw(
            self.OLD, "sha256: g" + KW_SHA[1:]))

    def test_a_valid_hex_the_fixture_never_carries_is_refused(self):
        self.assertIn("does not pair with the entry's sha256", refused_kw(
            self.OLD, "sha256: " + "0" * 64))

    def test_a_speech_sha_under_the_keyword_name_is_refused(self):
        self.assertIn("does not pair with the entry's sha256", refused_kw(
            self.OLD, "sha256: " + ts.entry("small")["sha256"]))


class KeywordUrlAuthorityRuleTest(unittest.TestCase):
    def test_a_non_https_scheme_is_refused(self):
        self.assertIn("https", url_refusal("http://api.github.com"
                                           + ASSET_PATH_TAIL))

    def test_a_port_is_refused(self):
        self.assertIn("port", url_refusal("https://api.github.com:8443"
                                          + ASSET_PATH_TAIL))
        self.assertIn("port", url_refusal("https://api.github.com:443"
                                          + ASSET_PATH_TAIL))

    def test_userinfo_is_refused_and_never_echoed(self):
        msg = url_refusal("https://admin:hunter2@api.github.com"
                          + ASSET_PATH_TAIL)
        self.assertIn("must not carry userinfo", msg)
        self.assertNotIn("hunter2", msg)
        self.assertNotIn("admin", msg)

    def test_another_host_is_refused_naming_it(self):
        self.assertIn("raw.githubusercontent.com", url_refusal(
            "https://raw.githubusercontent.com" + ASSET_PATH_TAIL))

    def test_the_web_host_asset_route_is_refused(self):
        self.assertIn("api.github.com", url_refusal("https://github.com"
                                                    + ASSET_PATH_TAIL))

    def test_a_tag_route_is_refused(self):
        self.assertIn("not pinned", url_refusal(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
            "kws-models/" + KW_NAME))

    def test_a_query_is_refused(self):
        self.assertIn("query or fragment", url_refusal(
            "https://api.github.com" + ASSET_PATH_TAIL + "?x=1"))

    def test_a_fragment_is_refused(self):
        self.assertIn("query or fragment", url_refusal(
            "https://api.github.com" + ASSET_PATH_TAIL + "#x"))

    def test_a_trailing_segment_is_refused(self):
        self.assertIn("not pinned", url_refusal(
            "https://api.github.com" + ASSET_PATH_TAIL + "/extra"))

    def test_a_letter_in_the_asset_id_is_refused(self):
        self.assertIn("not pinned", url_refusal(
            "https://api.github.com" + ASSET_PATH_TAIL + "x"))


class KeywordArchiveNameRuleTest(unittest.TestCase):
    OLD = f"archive_name: {KW_NAME}"

    def test_a_slash_is_refused(self):
        self.assertIn("without a path separator", refused_kw(
            self.OLD, "archive_name: models/" + KW_NAME))

    def test_a_backslash_is_refused(self):
        self.assertIn("without a path separator", refused_kw(
            self.OLD, "archive_name: models\\" + KW_NAME))

    def test_an_empty_name_is_refused(self):
        self.assertIn("must be a non-empty scalar", refused_kw(
            self.OLD, 'archive_name: ""'))

    def test_a_name_the_fixture_does_not_pair_is_refused(self):
        self.assertIn("does not pair with the entry's sha256", refused_kw(
            self.OLD, "archive_name: " + ts.entry("small")["archive_name"]))


class KeywordOtherFieldRulesTest(unittest.TestCase):
    def test_a_family_outside_the_enum_is_refused(self):
        msg = refused_kw("family: sherpa-onnx\n    params: 3.3M",
                         "family: whisperx\n    params: 3.3M")
        self.assertIn("'whisperx'", msg)

    def test_hosted_must_be_true_or_false(self):
        # the keyword entry's own `hosted: false` line is the only one that
        # follows its license line directly
        msg = refused_kw("license: Apache-2.0\n    hosted: false\n    archive_name: "
                         + KW_NAME,
                         "license: Apache-2.0\n    hosted: no\n    archive_name: "
                         + KW_NAME)
        self.assertIn("true or false", msg)

    def test_tamper_verified_must_be_true_or_false(self):
        # two-line anchor: only the keyword entry has Apache-2.0 after the flag
        msg = refused_kw("    tamper_verified: false\n    license: Apache-2.0\n",
                         "    tamper_verified: 0\n    license: Apache-2.0\n")
        self.assertIn("tamper_verified", msg)

    def test_an_spdx_id_with_whitespace_is_refused(self):
        msg = refused_kw("license: Apache-2.0\n    hosted: false\n    archive_name: "
                         + KW_NAME,
                         "license: Apache 2.0\n    hosted: false\n    archive_name: "
                         + KW_NAME)
        self.assertIn("SPDX", msg)

    def test_a_missing_required_field_is_refused_naming_the_entry(self):
        text = ts.models_text()
        at = text.index("\nkeyword_models:\n") + 1
        head, tail = text[:at], text[at:]
        tail = replace_once(tail, "    size_mb: 17\n", "")
        msg = ts.refuses(head + tail)
        self.assertIn("required field 'size_mb' is missing", msg)
        self.assertTrue(msg.startswith(KW_PREFIX), msg)

    def test_an_unknown_field_is_refused_at_its_own_line(self):
        # two-line anchor: only the keyword entry has Apache-2.0 after the flag
        planted = replace_once(
            ts.models_text(),
            "    tamper_verified: false\n    license: Apache-2.0\n",
            "    tamper_verified: false\n    flavour: vanilla\n"
            "    license: Apache-2.0\n")
        msg = ts.refuses(planted)
        self.assertIn("unknown field 'flavour'", msg)
        self.assertTrue(msg.startswith(KW_PREFIX), msg)
        want = next(i + 1 for i, l in enumerate(planted.splitlines())
                    if l.strip() == "flavour: vanilla")
        self.assertIn(f"line {want}:", msg)



if __name__ == "__main__":
    unittest.main()
