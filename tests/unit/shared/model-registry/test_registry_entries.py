"""Registry entry tests: the per-entry pins, the per-entry tamper cases and
the ALL-order check, for every models.yaml entry.

The refusal tests own the generator's refusal classes. These hold the rest of
the registry:

* every entry is pinned to the exact values the generator ships (id, family,
  url, sha256, size_mb, licence, hosted). The lookup is BY ID, so an entry
  added later breaks nothing here.
* each entry's tamper case: a corrupted copy of THAT entry (a flipped sha256
  digit, a tag-ified url, a removed field, an added unknown field) that the
  generator or the T21 fixture check must catch, each case its own test.
* ALL lists every entry in models.yaml order: the rendered Kotlin's `val ALL`
  must name one constant per entry, in file order.

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import re
import unittest

import model_registry_support as ts


def replace_once(text, old, new):
    assert text.count(old) == 1, f"expected exactly one {old!r}"
    return text.replace(old, new)


def entry_block_lines(text, eid):
    """The (0-based) line indices of the models.yaml entry whose id is `eid`:
    from its sequence marker to the next sequence marker, exclusive."""
    lines = text.splitlines()
    start = next(i for i, l in enumerate(lines)
                 if re.match(rf"^\s*-\s+id:\s*{eid}\s*$", l))
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.match(r"^\s*-\s+id:\s*\S", lines[i]):
            end = i
            break
    return list(range(start, end))


def drop_entry_field(text, eid, field):
    """The yaml text with the one `field:` line of the entry `eid` removed."""
    lines = text.splitlines()
    keep = [i for i in range(len(lines))
            if not (i in entry_block_lines(text, eid)
                    and lines[i].strip().startswith(f"{field}:"))]
    assert len(keep) == len(lines) - 1, "expected exactly one field line"
    return "\n".join(lines[i] for i in keep) + "\n"


class EntryPinsTest(unittest.TestCase):
    """Every entry is pinned to the exact values the generator ships."""

    PINS = {
        "small": {
            "family": "sherpa-onnx",
            "params": "70M",
            "url": "https://api.github.com/repos/k2-fsa/sherpa-onnx"
                   "/releases/assets/191972150",
            "sha256": "b97e7ff75a27136f4dd33c99bb7d5f493094397501be4739578c6cd95ea7f422",
            "size_mb": 349,
            "licence": "Apache-2.0",
            "hosted": False,
        },
        "tiny": {
            "family": "sherpa-onnx",
            "params": "20M",
            "upstream_commit":
                "be162ecc09bade73063a671fad9d18220149d25b",
            "tamper_verified": False,
            # the two fields the round-1 gate found unpinned: neither is
            # rendered into the Kotlin, so only a yaml pin catches a
            # hand-edit. tamper_verified is a promise (true only after the
            # tamper test ran on that entry) — flipping it on an entry that
            # never had the test must go RED.

            "url": "https://api.github.com/repos/k2-fsa/sherpa-onnx"
                   "/releases/assets/143510207",
            "sha256": "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9",
            "size_mb": 122,
            "licence": "Apache-2.0",
            "hosted": False,
        },
        "base": {
            "family": "whisper",
            "params": "73M",
            "upstream_commit":
                "59eea950fc76df2453efb57e6c0fd334548e8ffe",
            "tamper_verified": False,

            "url": "https://api.github.com/repos/k2-fsa/sherpa-onnx"
                   "/releases/assets/196350763",
            "sha256": "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542",
            "size_mb": 199,
            "licence": "MIT",
            "hosted": False,
        },
        "medium": {
            "family": "whisper",
            "params": "764M",
            "upstream_commit":
                "251ab4521f354490e8f2c206fbd0b7f3f6b0a7ec",
            "tamper_verified": False,

            "url": "https://api.github.com/repos/k2-fsa/sherpa-onnx"
                   "/releases/assets/179372814",
            "sha256": "73d95c169a410b5f23a79f8901374b26e0a16a09ea7f02b5e1db983f4cdfdd67",
            "size_mb": 1818,
            "licence": "MIT",
            "hosted": False,
        },
    }

    def test_every_pinned_entry_is_present_and_exact(self):
        by_id = {e["id"]: e for e in ts.check_models(ts.models_text())}
        for eid, want in self.PINS.items():
            self.assertIn(
                eid, by_id,
                f"entry {eid!r} is missing from models.yaml")
            got = by_id[eid]
            for field in want:
                # every field the pin names is asserted; the pin names
                # upstream_commit and tamper_verified only for tiny, base and
                # medium (small's are pinned by the generator refusal tests'
                # plants, which anchor on its exact committed strings)
                self.assertEqual(
                    want[field], got[field],
                    f"entry {eid!r}: {field} is {got[field]!r}, expected "
                    f"{want[field]!r}")

    def test_entry_lookup_by_id_finds_each_pinned_entry(self):
        for eid in self.PINS:
            e = ts.entry(eid)
            self.assertEqual(eid, e["id"])


class TinyTamperTest(unittest.TestCase):
    """`tiny`'s own tamper cases: a corrupted copy of that entry the check
    must catch, each case its own test."""

    def _entry(self):
        return ts.entry("tiny")

    def test_tiny_sha256_flip_is_caught_by_the_t21_fixture_check(self):
        # a 64-hex sha that is simply wrong: the generator's shape rule lets
        # it through, the T21 fixture comparison is the guard that bites
        e = self._entry()
        altered = ("0" + e["sha256"][1:]) if e["sha256"][0] != "0" \
            else ("1" + e["sha256"][1:])
        planted = [dict(e, sha256=altered)]
        _, lines = ts.fixture_data()
        with self.assertRaises(AssertionError) as cm:
            ts.assert_sha256_matches_fixture(
                planted, {ts.asset_id_of(e["url"]): lines[0][0]}, lines)
        self.assertIn("drifts from", str(cm.exception))

    def test_tiny_tag_url_is_refused(self):
        text = ts.models_text()
        good = ("url: https://api.github.com/repos/k2-fsa/sherpa-onnx"
                "/releases/assets/143510207")
        tag = ("url: https://github.com/k2-fsa/sherpa-onnx/releases/download/"
               "asr-models/sherpa-onnx-streaming-zipformer-en-20M-"
               "2023-02-17.tar.bz2")
        msg = ts.refuses(replace_once(text, good, tag))
        self.assertIn("not pinned", msg)

    def test_tiny_removed_field_is_refused(self):
        planted = drop_entry_field(ts.models_text(), "tiny", "size_mb")
        msg = ts.refuses(planted)
        self.assertIn("size_mb", msg)
        self.assertIn("missing", msg)

    def test_tiny_unknown_field_is_refused(self):
        lines = ts.models_text().splitlines()
        idx = entry_block_lines(ts.models_text(), "tiny")[0]
        lines.insert(idx + 1, "    flavour: vanilla")
        msg = ts.refuses("\n".join(lines) + "\n")
        self.assertIn("flavour", msg)
        self.assertIn("unknown field", msg)


class WhisperTamperMixin:
    """The four tamper cases for one whisper entry, each its own test. The
    mixin carries the per-entry values; each subclass pins one id."""

    eid = None
    good_url = None
    tag_url = None

    def _entry(self):
        return ts.entry(self.eid)

    def test_sha256_flip_is_caught_by_the_t21_fixture_check(self):
        # a 64-hex sha that is simply wrong: the generator's shape rule lets
        # it through, the T21 fixture comparison is the guard that bites
        e = self._entry()
        altered = ("0" + e["sha256"][1:]) if e["sha256"][0] != "0" \
            else ("1" + e["sha256"][1:])
        planted = [dict(e, sha256=altered)]
        _, lines = ts.fixture_data()
        with self.assertRaises(AssertionError) as cm:
            ts.assert_sha256_matches_fixture(
                planted, {ts.asset_id_of(e["url"]): lines[0][0]}, lines)
        self.assertIn("drifts from", str(cm.exception))

    def test_tag_url_is_refused(self):
        text = ts.models_text()
        good = "url: " + self.good_url
        self.assertEqual(text.count(good), 1,
                         "the entry's url line must appear exactly once")
        msg = ts.refuses(replace_once(text, good, "url: " + self.tag_url))
        self.assertIn("not pinned", msg)

    def test_removed_field_is_refused(self):
        planted = drop_entry_field(ts.models_text(), self.eid, "size_mb")
        msg = ts.refuses(planted)
        self.assertIn("size_mb", msg)
        self.assertIn("missing", msg)

    def test_unknown_field_is_refused(self):
        lines = ts.models_text().splitlines()
        idx = entry_block_lines(ts.models_text(), self.eid)[0]
        lines.insert(idx + 1, "    flavour: vanilla")
        msg = ts.refuses("\n".join(lines) + "\n")
        self.assertIn("flavour", msg)
        self.assertIn("unknown field", msg)


class BaseTamperTest(WhisperTamperMixin, unittest.TestCase):
    eid = "base"
    good_url = ("https://api.github.com/repos/k2-fsa/sherpa-onnx"
                "/releases/assets/196350763")
    tag_url = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/"
               "asr-models/sherpa-onnx-whisper-base.en.tar.bz2")


class MediumTamperTest(WhisperTamperMixin, unittest.TestCase):
    eid = "medium"
    good_url = ("https://api.github.com/repos/k2-fsa/sherpa-onnx"
                "/releases/assets/179372814")
    tag_url = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/"
               "asr-models/sherpa-onnx-whisper-medium.en.tar.bz2")


class RenderShapeTest(unittest.TestCase):
    """The rendered Kotlin must stay valid property declarations for any
    entry count: no line of the rendered file is `),` — a comma after a
    property declaration is not Kotlin, and with a single entry the
    separator was never emitted, so only a test over several entries can see
    it. Every entry block therefore closes with a bare `)` line. A text test
    cannot prove compilation: building the module is the compile proof.
    """

    def _render(self, eids):
        import gen_model_registry as gmr
        blocks = "\n".join("\n".join(ts.entry_block(eid)) for eid in eids)
        entries = ts.check_models("models:\n" + blocks + "\n")
        return gmr.render_kotlin(entries), entries

    def test_no_entry_block_closes_with_a_comma_for_1_2_3_4_entries(self):
        for eids in (("small",),
                     ("small", "tiny"),
                     ("small", "tiny", "base"),
                     ("small", "tiny", "base", "medium")):
            rendered, entries = self._render(eids)
            bad = [l for l in rendered.splitlines() if l.strip() == "),"]
            self.assertEqual(
                bad, [],
                f"{len(eids)}-entry render carries a `),` line — a comma "
                f"after a property declaration is not Kotlin: {bad!r}")
            # and every entry block DOES close with a bare `)` line (so the
            # render cannot 'fix' the comma by dropping the closer)
            bare_closers = sum(
                1 for l in rendered.splitlines() if l == "    )")
            self.assertEqual(
                bare_closers, len(entries),
                f"{len(eids)}-entry render: {bare_closers} bare `)` entry "
                f"closers, expected {len(entries)}")


class AllOrderTest(unittest.TestCase):
    def test_all_lists_every_entry_in_yaml_order(self):
        import gen_model_registry as gmr
        entries = ts.check_models(ts.models_text())
        kotlin = gmr.render_kotlin(entries)
        m = re.search(r"val ALL: List<ModelEntry> = listOf\((.*?)\)", kotlin)
        self.assertIsNotNone(m, "the rendered Kotlin carries no `val ALL`")
        names = [n.strip() for n in m.group(1).split(",") if n.strip()]
        expected = [gmr._kotlin_constant(e["id"]) for e in entries]
        self.assertEqual(expected, names,
                         "`val ALL` must name one constant per entry, in "
                         "models.yaml order")

    def test_all_follows_file_order_not_alphabetical_order(self):
        # the committed order (small, tiny) happens to be alphabetical too,
        # so pin the rule with a reordered file: tiny first. A render that
        # sorted by name would ship SMALL, TINY and fail here.
        import gen_model_registry as gmr
        reordered = ("models:\n"
                     + "\n".join(ts.entry_block("tiny")) + "\n"
                     + "\n".join(ts.entry_block("small")) + "\n")
        entries = ts.check_models(reordered)
        kotlin = gmr.render_kotlin(entries)
        m = re.search(r"val ALL: List<ModelEntry> = listOf\((.*?)\)", kotlin)
        names = [n.strip() for n in m.group(1).split(",") if n.strip()]
        self.assertEqual(["TINY", "SMALL"], names,
                         "`val ALL` must follow models.yaml file order, not "
                         "a sorted order")


if __name__ == "__main__":
    unittest.main()
