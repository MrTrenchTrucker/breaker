"""The archive_name field: the four generator refusals, the pair check's two
directions, the two carried refusal-naming fixes, and the committed registry's
own pairs, for the upstream file name each entry now carries.

The four refusals, one test class each, each planting exactly one bad shape in
one committed entry (so the refusal that fires is the one being tested):
* no archive_name at all (a required field, like every other one);
* an empty archive_name;
* a path separator (a / or a \\) anywhere in the name;
* a (archive_name, sha256) pair that is not a line of the upstream fixture -
  in both directions: a sha the fixture never carries, and a fixture sha that
  belongs to a DIFFERENT entry's name (the check must verify the pair, not the
  sha alone).

The two carried refusal-naming fixes, RED first on the committed tree:
* a refusal about a named field of an entry whose id is unusable points at
  the field's line, not the entry's sequence marker;
* a field-line scan does not mistake a body line of a `notes: >-` block
  (wrapped prose that happens to read like the field) for the field itself.

The committed registry's four pairs are pinned too: each entry's
(archive_name, sha256) is a line of the fixture, and the names are the
upstream file names byte for byte.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


def entry_block_lines(text, eid):
    """The 0-based line indices of the models.yaml entry whose id is `eid`."""
    lines = text.splitlines()
    start = next(i for i, l in enumerate(lines)
                 if re.match(rf"^\s*- id: {eid}$", l))
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.match(r"^\s*- id: \S", lines[i]):
            end = i
            break
    return list(range(start, end))


def line_of(msg):
    """The line number a refusal points at."""
    m = re.search(r"line (\d+)", msg)
    assert m, f"refusal names no line: {msg!r}"
    return int(m.group(1))


SMALL_NAME = ("sherpa-onnx-streaming-zipformer-en-2023-06-21-mobile.tar.bz2")
TINY_NAME = ("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2")
BASE_NAME = "sherpa-onnx-whisper-base.en.tar.bz2"
MEDIUM_NAME = "sherpa-onnx-whisper-medium.en.tar.bz2"
SMALL_SHA = "b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422"
BASE_SHA = "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542"


class ArchiveNameMissingTest(unittest.TestCase):
    def test_missing_archive_name_is_refused(self):
        lines = ts.models_text().splitlines()
        kept = [l for i, l in enumerate(lines)
                if not (i in entry_block_lines(ts.models_text(), "small")
                        and l.strip().startswith("archive_name:"))]
        assert len(kept) == len(lines) - 1, "expected exactly one archive_name line"
        msg = ts.refuses("\n".join(kept) + "\n")
        self.assertIn("required field 'archive_name' is missing", msg)
        self.assertIn("entry 'small'", msg)


class ArchiveNameEmptyTest(unittest.TestCase):
    def test_empty_archive_name_is_refused(self):
        planted = replace_once(
            ts.models_text(),
            f"    archive_name: {SMALL_NAME}",
            '    archive_name: ""')
        msg = ts.refuses(planted)
        self.assertIn("must be a non-empty scalar", msg)
        self.assertIn("archive_name", msg)
        self.assertIn("entry 'small'", msg)


class ArchiveNameSeparatorTest(unittest.TestCase):
    def _plant(self, prefix):
        return replace_once(
            ts.models_text(),
            f"    archive_name: {SMALL_NAME}",
            f"    archive_name: {prefix}{SMALL_NAME}")

    def test_slash_in_archive_name_is_refused(self):
        msg = ts.refuses(self._plant("models/"))
        self.assertIn("without a path separator", msg)
        self.assertIn("entry 'small'", msg)

    def test_backslash_in_archive_name_is_refused(self):
        msg = ts.refuses(self._plant("models\\"))
        self.assertIn("without a path separator", msg)
        self.assertIn("entry 'small'", msg)


class ArchiveNamePairTest(unittest.TestCase):
    def test_a_sha_the_fixture_never_carries_is_refused(self):
        planted = replace_once(
            ts.models_text(),
            f"    sha256: {SMALL_SHA}",
            f"    sha256: {'0' * 64}")
        msg = ts.refuses(planted)
        self.assertIn("does not pair with the entry's sha256", msg)
        self.assertIn("entry 'small'", msg)


class ArchiveNamePairIsAPairTest(unittest.TestCase):
    """The pair check checks the pair: the fixture's own sha for one entry,
    carried under a DIFFERENT entry's archive_name, must be refused - a check
    that looked at the sha alone would accept it."""

    def test_a_fixture_sha_carried_by_the_wrong_name_is_refused(self):
        # small's name with base's sha: the sha is a fixture line, but not
        # the line for small's name
        planted = replace_once(
            ts.models_text(),
            f"    sha256: {SMALL_SHA}",
            f"    sha256: {BASE_SHA}")
        msg = ts.refuses(planted)
        self.assertIn("does not pair with the entry's sha256", msg)
        self.assertIn("entry 'small'", msg)


class UnusableIdNamesTheFieldLineTest(unittest.TestCase):
    """A refusal about a named field of an entry whose id is unusable names
    the field's line - the entry's sequence marker is the id's only name when
    the refusal is about the id itself."""

    def test_empty_id_refusal_names_the_field_line_not_the_marker(self):
        # the marker no longer carries the id, so the marker line and the id
        # line are different numbers: a refusal that points at the marker
        # cannot pass for one that points at the field
        planted = replace_once(
            ts.models_text(),
            "  - id: small\n    family: sherpa-onnx\n",
            '  - family: sherpa-onnx\n    id: ""\n')
        msg = ts.refuses(planted)
        self.assertIn("must be a non-empty scalar", msg)
        self.assertIn("entry at line", msg)
        id_line = next(i + 1 for i, l in enumerate(planted.splitlines())
                       if l.strip() == 'id: ""')
        marker_line = next(i + 1 for i, l in enumerate(planted.splitlines())
                           if l.strip().startswith("- family:"))
        self.assertNotEqual(id_line, marker_line)
        self.assertEqual(line_of(msg), id_line)


class BlockScalarBodyIsNotAFieldLineTest(unittest.TestCase):
    """A body line of a `notes: >-` block that happens to read like the field
    is prose: the field-line scan must point at the field's own line."""

    def test_a_notes_body_line_that_reads_like_the_field_is_not_it(self):
        # the committed small entry, re-ordered so the notes block sits ABOVE
        # a malformed sha256, and one of the notes' wrapped lines reads
        # exactly like that field
        lines = ts.entry_block("small")
        rest = list(lines[1:])
        rest.pop(next(i for i, l in enumerate(rest)
                      if l.strip().startswith("sha256:")))
        notes_i = next(i for i, l in enumerate(rest)
                       if l.strip() == "notes: >-")
        rest.insert(notes_i + 1, "      sha256: " + "0" * 64)
        planted = ("models:\n" + lines[0] + "\n"
                   + "\n".join(rest + ["    sha256: ABC"]) + "\n")
        msg = ts.refuses(planted)
        self.assertIn("sha256 is not 64 lowercase hex characters", msg)
        real_line = next(i + 1 for i, l in enumerate(planted.splitlines())
                         if l.strip() == "sha256: ABC")
        fake_lines = [i + 1 for i, l in enumerate(planted.splitlines())
                      if l.strip() == "sha256: " + "0" * 64]
        self.assertTrue(fake_lines, "the plant must carry the body line")
        self.assertEqual(line_of(msg), real_line)
        self.assertNotIn(line_of(msg), fake_lines)


class CommittedRegistryPairsTest(unittest.TestCase):
    """The committed registry's own pairs: each entry's (archive_name,
    sha256) is a line of the fixture, and the name is the upstream file name
    byte for byte (the names came from the fetch of the upstream
    checksum.txt recorded in the RC's fetch record - not typed by hand)."""

    EXPECTED = {
        "small": SMALL_NAME,
        "tiny": TINY_NAME,
        "base": BASE_NAME,
        "medium": MEDIUM_NAME,
    }

    def test_every_committed_name_is_the_upstream_file_name(self):
        by_id = {e["id"]: e for e in ts.check_models(ts.models_text())}
        for eid, want in self.EXPECTED.items():
            self.assertIn(eid, by_id)
            self.assertEqual(want, by_id[eid]["archive_name"])

    def test_every_committed_pair_is_a_fixture_line(self):
        entries = ts.check_models(ts.models_text())
        _, lines = ts.fixture_data()
        pairs = set(lines)
        for e in entries:
            self.assertIn(
                (e["archive_name"], e["sha256"]), pairs,
                f"entry {e['id']!r}: ({e['archive_name']!r}, "
                f"{e['sha256'][:16]}...) is not a fixture line")




class RenderedArchiveNamesTest(unittest.TestCase):
    """The Kotlin the generator emits must carry each entry's archive_name.

    render_kotlin is the single emitter of the committed Kotlin: the
    existing render tests pin the constant names and the `val ALL` order,
    not the field values, so a render that dropped the archiveName lines
    (or carried a wrong entry's name) sailed through them. This test
    extracts each entry's own block and pins the line inside it, so an
    empty name, a missing line, a swapped name or a dropped entry each
    fails it. The names come from models.yaml through the generator's own
    conversion - not typed here a second time.
    """

    def test_the_rendered_kotlin_carries_each_committed_archive_name(self):
        import gen_model_registry as gmr

        entries = ts.check_models(ts.models_text())
        self.assertEqual(4, len(entries),
                         "models.yaml is expected to carry the four "
                         "committed entries")
        kotlin = gmr.render_kotlin(entries)
        blocks = re.findall(
            r"    val \w+: ModelEntry = ModelEntry\(.*?\n    \)",
            kotlin, re.S)
        self.assertEqual(len(entries), len(blocks),
                         "the rendered Kotlin must carry one entry block "
                         "per models.yaml entry")
        for e, block in zip(entries, blocks):
            want = "archiveName = " + gmr._kotlin_str(e["archive_name"])
            self.assertIn(
                want, block,
                "entry %r: its rendered block does not carry %r"
                % (e["id"], want))

if __name__ == "__main__":
    unittest.main()
