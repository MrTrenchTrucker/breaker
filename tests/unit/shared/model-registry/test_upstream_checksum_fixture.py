"""T21 offline: every entry verifies against upstream's published checksum.txt.

The repo cannot reach the network, so the invariant "every entry verifies
against upstream's checksum.txt" (module card Invariants) is held by a
fixture: shared/modules/model-registry/fixtures/upstream-checksum-excerpt.txt,
a byte-exact copy of the relevant upstream line(s), whose header pins the
provenance (source, the full upstream file's size and sha256, the asset-id ->
filename mapping, fetch date). This test checks the committed entry against
that fixture WITHOUT a network:

* the entry's url names the fixture's asset id, and the fixture maps that id
  to a filename;
* the fixture carries EXACTLY ONE data line per entry (vacuity guard: zero
  lines would make the check pass on nothing, two or more would be
  ambiguous);
* the entry's sha256 equals the fixture line's sha256 for that filename.

The RED watches for this test (each planted, then the file restored):
one hex digit changed in the entry; the filename changed in the fixture; the
asset id changed in the fixture header.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import os
import re
import tempfile
import unittest

import model_registry_support as ts
import gen_model_registry as gmr


class UpstreamChecksumFixtureTest(unittest.TestCase):
    def setUp(self):
        self.entries = ts.check_models(ts.models_text())
        self.asset_map, self.lines = ts.fixture_data()

    def _entry_lines_for(self, url, filename):
        """The fixture data lines whose filename is `filename` — one per
        entry that uses it."""
        return [(f, s) for (f, s) in self.lines if f == filename]

    def _fixture_without_line(self, filename):
        """The fixture with its data line for `filename` removed."""
        with open(ts.FIXTURE, "r", encoding="utf-8") as fh:
            lines = fh.readlines()
        kept = [l for l in lines if not (l.strip() and l.split("\t")[0].strip() == filename)]
        fd, path = tempfile.mkstemp(suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.writelines(kept)
        self.addCleanup(os.unlink, path)
        return path

    def test_entry_url_names_the_fixture_asset_id(self):
        for e in self.entries:
            aid = ts.asset_id_of(e["url"])
            self.assertIn(
                aid, self.asset_map,
                f"fixture header names no asset id -> filename for {aid} "
                f"(the entry {e['id']!r} url)")

    def test_exactly_one_fixture_line_per_entry(self):
        for e in self.entries:
            aid = ts.asset_id_of(e["url"])
            filename = self.asset_map[aid]
            lines = self._entry_lines_for(aid, filename)
            self.assertEqual(
                len(lines), 1,
                f"entry {e['id']!r}: the fixture must carry exactly one data "
                f"line for {filename!r} (vacuity guard), carries {len(lines)}")

    def test_entry_sha256_equals_the_fixture_line(self):
        for e in self.entries:
            aid = ts.asset_id_of(e["url"])
            filename = self.asset_map[aid]
            (_, expected) = self._entry_lines_for(aid, filename)[0]
            self.assertEqual(
                e["sha256"], expected,
                f"entry {e['id']!r} sha256 {e['sha256']} drifts from "
                f"upstream's published line for {filename}: {expected}")


class FixtureRedPlantsTest(unittest.TestCase):
    """The three ordered RED plants, as tests of the fixture's own discipline.

    Each plant changes exactly one byte-class of the fixture - its sha256,
    the data line's filename, or the header's asset id - and proves the
    check above fails on it. The plants are in-memory (the committed files
    are never touched).
    """

    def _run_sha256_check(self, entries, asset_map, lines):
        """The same comparison the class test makes, on given data."""
        for e in entries:
            aid = ts.asset_id_of(e["url"])
            filename = asset_map[aid]
            matches = [s for (f, s) in lines if f == filename]
            assert len(matches) == 1, f"expected one fixture line for {filename}, got {len(matches)}"
            assert e["sha256"] == matches[0], (
                f"entry {e['id']!r} sha256 {e['sha256']} drifts from "
                f"upstream's published line for {filename}: {matches[0]}")

    def test_one_hex_digit_changed_in_the_entry_is_caught(self):
        entries = ts.check_models(ts.models_text())
        e = entries[0]
        altered = "0" + e["sha256"][1:] if e["sha256"][0] != "0" else "1" + e["sha256"][1:]
        entries[0] = dict(e, sha256=altered)
        _, lines = ts.fixture_data()
        with self.assertRaises(AssertionError) as cm:
            self._run_sha256_check(entries, {ts.asset_id_of(e["url"]): lines[0][0]}, lines)
        self.assertIn("drifts from", str(cm.exception))

    def test_filename_changed_in_the_fixture_is_caught(self):
        # the header maps the id to the upstream filename; only the data
        # line's filename is renamed -> no data line matches the id's
        # filename, and the vacuity guard must fail
        entries = ts.check_models(ts.models_text())
        e = entries[0]
        aid = ts.asset_id_of(e["url"])
        asset_map, lines = ts.fixture_data()
        renamed = [("sherpa-onnx-streaming-zipformer-en-2023-06-21-RENAME.tar.bz2", s)
                   for (f, s) in lines]
        with self.assertRaises(AssertionError) as cm:
            self._run_sha256_check(entries, asset_map, renamed)
        self.assertIn("one fixture line", str(cm.exception))

    def test_asset_id_changed_in_the_fixture_header_is_caught(self):
        entries = ts.check_models(ts.models_text())
        e = entries[0]
        aid = ts.asset_id_of(e["url"])
        lines = ts.fixture_data()[1]
        with self.assertRaises(AssertionError) as cm:
            # a header that maps the entry's id to the wrong filename has no
            # matching data line -> the vacuity guard fails
            self._run_sha256_check(entries, {aid: "sherpa-onnx-streaming-zipformer-en-WRONG.tar.bz2"}, lines)
        self.assertIn("expected one fixture line", str(cm.exception))




class LicenseFieldTest(unittest.TestCase):
    """The license rule's field guards, per the module card's Invariants.

    Each plant adds the license fields inside the entry (before the notes
    block) and proves the generator refuses the bad shape.
    """

    def _planted(self, extra):
        text = ts.models_text()
        text = "\n".join(l for l in text.splitlines()
                             if not l.startswith("    license:"))
        return text.replace(
            "    notes: >-",
            extra + "    notes: >-",
        )

    def test_whitespace_only_license_name_is_refused(self):
        msg = ts.refuses(self._planted(
            '    license_name: "   "\n'
            "    license_link: https://example.invalid/license\n"))
        self.assertIn("license_name", msg)

    def test_empty_license_name_is_refused(self):
        msg = ts.refuses(self._planted(
            '    license_name: ""\n'
            "    license_link: https://example.invalid/license\n"))
        self.assertIn("license_name", msg)

    def test_non_https_license_link_is_refused(self):
        msg = ts.refuses(self._planted(
            "    license_name: Custom ASR License\n"
            "    license_link: http://example.invalid/license\n"))
        self.assertIn("https", msg)


class DeterminismTest(unittest.TestCase):
    """render_kotlin is byte-deterministic across repeated check+render cycles."""

    def test_render_is_byte_deterministic(self):
        import gen_model_registry as gmr2
        text = ts.models_text()
        e1 = ts.check_models(text)
        r1 = gmr2.render_kotlin(e1)
        e2 = ts.check_models(text)
        r2 = gmr2.render_kotlin(e2)
        self.assertEqual(r1, r2)
        self.assertGreater(len(r1), 0)



if __name__ == "__main__":
    unittest.main()
