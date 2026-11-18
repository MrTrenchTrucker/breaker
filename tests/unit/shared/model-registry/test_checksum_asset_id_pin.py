"""The checksum asset id pinned in DownloadLimits.kt equals the fixture header's.

The app fetches upstream's checksum.txt by a pinned GitHub release-asset id
(`DownloadLimits.checksumsUrl`). Upstream re-uploaded that asset, so the id is a
PIN that must move together with the provenance recorded in the fixture header
(`shared/modules/model-registry/fixtures/upstream-checksum-excerpt.txt`): a
DownloadLimits.kt that names one checksum asset and a fixture that records
another is a fresh download that 404s. This test holds the two together WITHOUT
a network and WITHOUT Gradle:

* the id is read from DownloadLimits.kt as the LAST PATH SEGMENT of the
  checksumsUrl string, strictly - exactly one `val checksumsUrl` declaration,
  exactly one `/releases/assets/<digits>` occurrence in the whole file, and that
  occurrence is the url's last path segment;
* the id is read from the fixture header as its "asset id <digits>" text,
  strictly - exactly one such occurrence;
* a missing id, a non-numeric id, or more than one id in either file is a
  FAILURE, never a vacuous pass (the Kotlin url's last segment is checked with
  `isdigit()`; in the fixture a non-numeric id matches no `[0-9]+` and is
  refused as "no id found").

The RED watches for this test (each planted in memory; the committed files are
never touched): the id changed in DownloadLimits.kt; the id changed in the
fixture header; the id missing from either; two ids in either.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))  # tests/unit/shared/model-registry -> repo root
DOWNLOAD_LIMITS = os.path.join(
    ROOT, "android", "modules", "stt-ondevice", "src", "main", "kotlin",
    "dev", "breaker", "dictation", "stt", "ondevice", "DownloadLimits.kt",
)
FIXTURE = os.path.join(
    ROOT, "shared", "modules", "model-registry", "fixtures",
    "upstream-checksum-excerpt.txt",
)

# The one checksumsUrl declaration, and every release-asset id in the file.
CHECKSUMS_URL = re.compile(r'val\s+checksumsUrl:\s*String\s*=\s*"([^"]*)"')
KOTLIN_ASSET_ID = re.compile(r"/releases/assets/([0-9]+)")
# The fixture header's provenance line: "asset id <digits>".
FIXTURE_ASSET_ID = re.compile(r"asset id ([0-9]+)")
# A later section of the fixture (another release, with its own checksum asset)
# starts at a line beginning "# --- "; the speech models' header is what comes
# before the first such line.
FIXTURE_SECTION_MARK = re.compile(r"^# --- ", re.MULTILINE)


def _sole(text, pattern, what):
    """The one id `pattern` finds in `text`, or a loud refusal.

    Zero matches and more than one are both failures: a pin that finds no id
    has proved nothing, and one that finds two cannot say which is meant.
    """
    ids = pattern.findall(text)
    if not ids:
        raise AssertionError(f"{what}: no id found (expected exactly one)")
    if len(ids) > 1:
        raise AssertionError(f"{what}: more than one id found: {ids}")
    return ids[0]


def checksum_id_in_download_limits(text):
    """The release-asset id DownloadLimits.kt pins for the checksum list."""
    urls = CHECKSUMS_URL.findall(text)
    if not urls:
        raise AssertionError(
            "DownloadLimits.kt: no `val checksumsUrl: String = \"...\"` "
            "declaration found")
    if len(urls) > 1:
        raise AssertionError(
            f"DownloadLimits.kt: more than one checksumsUrl declaration "
            f"found: {urls}")
    url = urls[0]
    tail = url.rstrip("/").rsplit("/", 1)[-1]
    if not tail.isdigit():
        raise AssertionError(
            f"DownloadLimits.kt: the checksumsUrl {url!r} ends in {tail!r}, "
            f"which is not a numeric asset id")
    aid = _sole(text, KOTLIN_ASSET_ID, "DownloadLimits.kt")
    if tail != aid:
        raise AssertionError(
            f"DownloadLimits.kt: the checksumsUrl {url!r} ends in {tail!r}, "
            f"not the file's asset id {aid!r}")
    return aid


def checksum_id_in_fixture_header(text):
    """The release-asset id the fixture header records for the speech models' checksum list.

    Only the text before the first section mark is read: a later section names the
    checksum asset of a different release, which DownloadLimits.kt does not pin.
    """
    header = FIXTURE_SECTION_MARK.split(text, maxsplit=1)[0]
    return _sole(header, FIXTURE_ASSET_ID, "the fixture header")


def assert_checksum_asset_ids_agree(kotlin_text, fixture_text):
    """The pin check: raise unless the two files name the SAME single asset id."""
    kotlin_id = checksum_id_in_download_limits(kotlin_text)
    fixture_id = checksum_id_in_fixture_header(fixture_text)
    if kotlin_id != fixture_id:
        raise AssertionError(
            f"the checksum asset id pinned in DownloadLimits.kt is "
            f"{kotlin_id}, but the fixture header names {fixture_id}")
    return kotlin_id


class ChecksumAssetIdPinTest(unittest.TestCase):
    """The pin in the Kotlin and the provenance in the fixture are one id."""

    def setUp(self):
        with open(DOWNLOAD_LIMITS, "r", encoding="utf-8") as fh:
            self.kotlin = fh.read()
        with open(FIXTURE, "r", encoding="utf-8") as fh:
            self.fixture = fh.read()

    def test_the_checksum_asset_id_pinned_in_DownloadLimits_kt_equals_the_id_named_in_the_fixture_header(self):
        kotlin_id = checksum_id_in_download_limits(self.kotlin)
        fixture_id = checksum_id_in_fixture_header(self.fixture)
        self.assertEqual(
            kotlin_id, fixture_id,
            f"the checksum asset id pinned in DownloadLimits.kt "
            f"({DOWNLOAD_LIMITS}) is {kotlin_id}, but the fixture header "
            f"({FIXTURE}) names {fixture_id}",
        )


class ChecksumAssetIdMutantTest(unittest.TestCase):
    """The firing mutants, each planted in memory; the committed files are
    never touched. Every plant asserts it stuck before it is judged, so a
    mutant that silently did nothing cannot read as a pass."""

    def setUp(self):
        with open(DOWNLOAD_LIMITS, "r", encoding="utf-8") as fh:
            self.kotlin = fh.read()
        with open(FIXTURE, "r", encoding="utf-8") as fh:
            self.fixture = fh.read()
        self.kotlin_id = checksum_id_in_download_limits(self.kotlin)
        self.fixture_id = checksum_id_in_fixture_header(self.fixture)

    def _refused(self, kotlin_text, fixture_text, why):
        with self.assertRaises(AssertionError) as cm:
            assert_checksum_asset_ids_agree(kotlin_text, fixture_text)
        self.assertIn(why, str(cm.exception))
        return str(cm.exception)

    def test_the_id_changed_in_DownloadLimits_kt_is_refused(self):
        planted = self.kotlin.replace(
            f"/releases/assets/{self.kotlin_id}", "/releases/assets/999999999")
        self.assertNotEqual(planted, self.kotlin, "the plant did not stick")
        self._refused(planted, self.fixture, "is 999999999")

    def test_the_id_changed_in_the_fixture_header_is_refused(self):
        planted = self.fixture.replace(
            f"asset id {self.fixture_id}", "asset id 999999999")
        self.assertNotEqual(planted, self.fixture, "the plant did not stick")
        self._refused(self.kotlin, planted, "names 999999999")

    def test_the_id_missing_from_DownloadLimits_kt_is_refused(self):
        planted = re.sub(
            r'val\s+checksumsUrl:\s*String\s*=\s*"[^"]*"',
            'val checksumsUrl: String = "https://api.github.com/repos/'
            'k2-fsa/sherpa-onnx/releases/tags/asr-models"',
            self.kotlin, count=1)
        self.assertNotEqual(planted, self.kotlin, "the plant did not stick")
        self._refused(planted, self.fixture, "not a numeric asset id")

    def test_the_id_missing_from_the_fixture_header_is_refused(self):
        planted = self.fixture.replace(
            f"asset id {self.fixture_id}", "asset id unknown")
        self.assertNotEqual(planted, self.fixture, "the plant did not stick")
        self._refused(self.kotlin, planted, "no id found")

    def test_two_ids_in_DownloadLimits_kt_are_refused(self):
        planted = self.kotlin.replace(
            "internal fun isHttpsScheme",
            "// see also https://api.github.com/repos/k2-fsa/sherpa-onnx"
            "/releases/assets/123456789\ninternal fun isHttpsScheme",
            1)
        self.assertNotEqual(planted, self.kotlin, "the plant did not stick")
        self._refused(planted, self.fixture, "more than one id found")

    def test_two_ids_in_the_fixture_header_are_refused(self):
        planted = self.fixture.replace(
            "# Asset id -> filename",
            "# asset id 123456789 -> a second pin\n# Asset id -> filename",
            1)
        self.assertNotEqual(planted, self.fixture, "the plant did not stick")
        self._refused(self.kotlin, planted, "more than one id found")

    def test_an_id_in_a_later_fixture_section_is_not_read_as_the_header_id(self):
        mark = FIXTURE_SECTION_MARK.search(self.fixture)
        self.assertIsNotNone(mark, "the fixture has no later section, so this check proves nothing")
        planted = self.fixture[:mark.end()] + "asset id 123456789 (a later release)\n" + self.fixture[mark.end():]
        self.assertNotEqual(planted, self.fixture, "the plant did not stick")
        self.assertEqual(self.fixture_id, checksum_id_in_fixture_header(planted))
        self.assertEqual(self.kotlin_id, assert_checksum_asset_ids_agree(self.kotlin, planted))


if __name__ == "__main__":
    unittest.main()
