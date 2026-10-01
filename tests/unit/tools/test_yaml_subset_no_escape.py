"""Only a refusal may leave the reader, whatever the text.

A reader for a subset promises one failure type: a caller catches YamlSubsetError and reports
`<name>:<line>: <reason>`. An IndexError from a corner nobody wrote a test for turns that into a
traceback. This module damages documents with single edits and asserts that each damaged text is
either read or refused, and that a refusal keeps its contract: its line lies inside the text and
`str(err)` is `<name>:<line>: <reason>` with a reason.

* The sweep takes each small document and makes EVERY single edit of it: each item of an alphabet of
  awkward characters inserted at each position, each character replaced by each item, each character
  deleted, the text cut short at each position. Nothing is drawn, so what it reaches does not depend
  on luck.
* The sampler applies a fixed-seed sequence of single edits to every document, the two large ones
  (a spec and a registry) included, which are too big to sweep.

The documents and the alphabet are chosen to reach the refusals of the reader. Two refusals need other
inputs, so this module leaves them to the file and refusal modules: a file that is not valid UTF-8
(only `load()` reads bytes) and a document nested past the recursion limit.

Both run over fixed documents, the sweep by construction and the sampler with a fixed seed, so a failure
names a text that can be replayed, and both are bounded: the alarm of the shared test case (a reader
that loops) raises AssertionError, and a sweep lets it through instead of counting one more escape. A
sweep reads tens of thousands of texts and the alarm counts wall-clock seconds, so the class that holds
the sweeps has a wider limit than the shared one.

Controls keep the probe honest. It must see both outcomes (its thresholds are lower bounds: a reader
that refuses more of these texts reads fewer of them), it must catch an escape planted in a loader of
its own (any exception type but the reader's), it must notice a refusal that breaks its contract, it
must stop at the alarm, and its seed must stay a fixed literal and be honoured when a caller passes
another. The characters and documents written for particular rules are pinned by name, so that tidying
the lists cannot drop one unseen.
The alarm of the shared test case is covered by test_yaml_subset_no_escape_alarm.py.

Run: python3 -m unittest discover -s tests/unit/tools -t tests/unit/tools
"""
import ast
import importlib.util
import random
import signal
import unittest

from test_yaml_subset_shapes import REGISTRY, SPEC
from yaml_support import YamlCase, YamlSubsetError, loads
from yaml_escape_support import ALPHABET, NAME, lines_in, single_edits, sweep

SEED = 20260930
ROUNDS = 400  # damaged texts per document in the sampler

SMALL = (
    "a: 1\nb: 2\n",
    "a:\n  b:\n    c: deep\n  d: shallow\ne: top\n",
    "l:\n  - x\n  - y\n",
    "l:\n  - id: a\n    n: 1\n  - id: b\n",
    "l: [a, b, c]\nm: {k: v, j: [1, 2], n: {p: q}}\n",
    "t: \"quoted \\\" and \\\\ and \\n\"\ns: 'it''s'\n",
    "a: |\n  one\n  two\nb: >\n  fold\n  ed\nc: |-\n  strip\nd: >-\n  x\n",
    "a: |\n  one\n\n  two\n\n\nb: 1\n",
    "a: |\n    one\n      \n    two\n",
    "# comment\n\nk: v\n  # another\nn: 1\n",
    "k:\n  - a: 1\n    b: |\n      text\n  - c\n",
    "a: 1\r\nb: [x, y]\r\n",
    "k: [a, [b, [c]]]\nm: {a: {b: {c: d}}}\n",
    "u: http://x.example/y?z=1\nq: \"a:\"\nr: ?a\n",
    "l:\n  - a:\n  - b: c\nm: {d:, e: f}\n",
    "---\na: 1\n",
    "a: 1\n---\nb: 2\n",
    "a: 1\n...\n",
    "- a\n- b\n",
    "a: &x 1\nb: *x\nc: !t 1\n",
    "? a\n",
    "a: b: c\n",
    "a: [b\n",
    "a: {b: c\n",
    "a: 'b\n",
    "a: \"b\\\n",
    "a: b # c\n",
    "a:\tb\n",
    "a: |\n",
    "a: >+\n  x\n",
    "a: [a,, b]\nb: {k: v,}\n",
    "a: b\na: c\n",
    "a: - x\n",
    "a: |\n  x\n b: 1\n",
    "a:\n    b: 1\n  c: 2\n",
    "\ufeffa: 1\n",
    "a: 1\rb\n",
    # a flow mapping with quoted keys, a quoted key with no value, a duplicate flow key
    "m: {\"k\": v, 'j': [1]}\n",
    "m: {\"k\"}\n",
    "m: {a: 1, a: 2}\n",
    # a block scalar as a sequence item, a tab on a comment-looking line and on a blank line inside a block scalar
    "l:\n  - |\n",
    "a: |\n  one\n  #\ttab\n",
    "a: |\n  one\n\t\n  two\n",
    # a comment after a quoted value, an escaped quote inside a flow collection
    "a: \"b\" # c\n",
    "a: [\"b\\\"c\"]\n",
    # a value or a flow entry that starts with an indicator character, and a key that does
    "k: @a\n",
    "k: `a\n",
    "k: [|a]\n",
    "|a: 1\n",
    "l:\n  - %a\n",
    "l: [?a, b]\n",
    # the merge key, a document marker followed by a mapping entry on the first line
    "x:\n  <<: {a: 1}\n",
    "... a: 1\n",
    # characters the C0 and C1 rule does not cover, one of them a lone surrogate
    "k: a\ufffeb\n",
    "k: a\uffffb\n",
    "k: a\ud800b\n",
)
SEEDS = SMALL + (SPEC, REGISTRY)


def damage(text, rnd):
    position = rnd.randrange(len(text) + 1)
    kind = rnd.choice(("insert", "delete", "replace", "truncate"))
    if kind == "insert":
        return text[:position] + rnd.choice(ALPHABET) + text[position:]
    if kind == "delete":
        return text[:position] + text[position + 1:]
    if kind == "replace":
        return text[:position] + rnd.choice(ALPHABET) + text[position + 1:]
    return text[:position]


def damaged_texts(rounds=ROUNDS, seed=SEED, documents=SEEDS):
    """The sampler: `rounds` single edits of each document, drawn by a generator seeded with `seed`."""
    rnd = random.Random(seed)
    for text in documents:
        for _ in range(rounds):
            yield damage(text, rnd)


def probe(loader, rounds=ROUNDS, seed=SEED):
    """The sampler over every document."""
    return sweep(loader, damaged_texts(rounds, seed))


def exhaustive(loader, documents=SMALL):
    """The sweep of every single edit of every small document."""
    return sweep(loader, (damaged for text in documents for damaged in single_edits(text)))


def fresh_copy():
    """This module executed again as a module of its own: its seed and its documents are read anew."""
    spec = importlib.util.spec_from_file_location("no_escape_probe_copy", __file__)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def report(outcome):
    shortest = sorted(outcome.escapes, key=lambda item: len(item[0]))[:5]
    return f"yaml_subset: {len(outcome.escapes)} damaged texts escaped as another exception type; shortest: {shortest!r}"


class NoEscapeTest(YamlCase):
    # A sweep reads tens of thousands of texts and the alarm counts wall-clock seconds, so on a busy machine the
    # shared limit could call a slow sweep a looping reader. The alarm still stops a reader that loops, later.
    HANG_SECONDS = 20

    # -- the reader under the sampler and under the sweep ---------------------------------------

    def test_only_a_refusal_ever_escapes_the_reader(self):
        outcome = probe(loads)
        self.assertEqual(outcome.escapes, [], report(outcome))
        self.assertEqual(outcome.broken[:5], [], f"yaml_subset: {len(outcome.broken)} refusals break their contract")

    def test_no_text_one_edit_from_a_small_document_escapes_the_reader(self):
        outcome = exhaustive(loads)
        self.assertEqual(outcome.escapes, [], report(outcome))
        self.assertEqual(outcome.broken[:5], [], f"yaml_subset: {len(outcome.broken)} refusals break their contract")

    # The read side is bounded more loosely than the refused side: a reader that refuses more of these odd texts
    # reads fewer of them, and never the other way round.

    def test_the_probe_reads_some_and_refuses_some(self):
        outcome = probe(loads)
        self.assertEqual(outcome.refused + outcome.read, len(set(damaged_texts())), "a text was left out or escaped")
        self.assertGreater(outcome.refused, 8800, "the damage should reach the refusals")
        self.assertGreater(outcome.read, 1200, "the damage should also leave readable documents")

    def test_the_sweep_reads_some_texts_and_refuses_many(self):
        outcome = exhaustive(loads)
        distinct = {damaged for text in SMALL for damaged in single_edits(text)}
        self.assertEqual(outcome.refused + outcome.read, len(distinct), "a text was left out or escaped")
        self.assertGreater(outcome.refused, 66500, "the edits should reach the refusals")
        self.assertGreater(outcome.read, 12000, "the edits should also leave readable documents")

    # -- the sampler and the sweep against loaders of their own ---------------------------------

    def test_the_probe_catches_an_escape_when_one_is_planted(self):
        for planted in (IndexError, ValueError, KeyError):
            with self.subTest(planted=planted.__name__):
                def broken(text, name, planted=planted):  # a loader of its own, so the control does not depend on the reader
                    if "\u00a0" in text:
                        raise planted("planted")
                    raise YamlSubsetError(name, 1, "refused")

                outcome = probe(broken)
                self.assertEqual({text for text, _ in outcome.escapes},
                                 {text for text in damaged_texts() if "\u00a0" in text})
                self.assertTrue(outcome.escapes)
                self.assertTrue(all(message.startswith(planted.__name__) for _, message in outcome.escapes))

    def test_the_sweep_catches_an_escape_planted_at_one_text(self):
        target = SMALL[0][:3] + "[" + SMALL[0][3:]
        self.assertIn(target, set(single_edits(SMALL[0])))
        for planted in (IndexError, ValueError):
            with self.subTest(planted=planted.__name__):
                def broken(text, name, planted=planted):
                    if text == target:
                        raise planted("planted")
                    return {}

                outcome = exhaustive(broken)
                self.assertEqual(outcome.escapes, [(target, f"{planted.__name__}: planted")])

    def test_a_plain_value_error_is_an_escape_and_a_refusal_is_not(self):
        def plain(text, name):
            raise ValueError("plain")

        def refusal(text, name):
            raise YamlSubsetError(name, 1, "refused")

        for label, run in (("sampler", probe), ("sweep", exhaustive)):
            with self.subTest(sweep=label):
                outcome = run(plain)
                self.assertEqual((outcome.refused, outcome.read), (0, 0))
                self.assertTrue(outcome.escapes)
                self.assertTrue(all(message == "ValueError: plain" for _, message in outcome.escapes))
                outcome = run(refusal)
                self.assertEqual(outcome.escapes, [])
                self.assertEqual(outcome.read, 0)
                self.assertTrue(outcome.refused)

    def test_the_alarm_of_a_looping_reader_stops_the_sweep_at_once(self):
        alarm = "yaml_subset: some.test ran for more than 5 s; the reader is looping"
        for label, run in (("sampler", probe), ("sweep", exhaustive)):
            with self.subTest(sweep=label):
                calls = []

                def looping(text, name):
                    calls.append(text)
                    raise AssertionError(alarm)

                with self.assertRaises(AssertionError) as caught:
                    run(looping)
                self.assertEqual(len(calls), 1, "the sweep went on after the alarm")
                self.assertIn(alarm, str(caught.exception))
                self.assertTrue(any(repr(calls[0]) in note for note in caught.exception.__notes__),
                                "the alarm should name the text that was being read")

    @unittest.skipUnless(hasattr(signal, "SIGALRM"), "the alarm needs SIGALRM")
    def test_the_alarm_of_this_class_allows_more_wall_clock_time_than_the_shared_limit(self):
        remaining, _ = signal.getitimer(signal.ITIMER_REAL)  # what is left of the alarm that guards this very test
        self.assertGreater(remaining, 0, "no alarm is armed")
        self.assertGreater(remaining, YamlCase.HANG_SECONDS, "a busy machine could call a slow sweep a looping reader")
        self.assertLessEqual(remaining, 30, "a reader that loops must still be stopped within half a minute")

    def test_a_refusal_that_breaks_its_contract_is_reported(self):
        # "a: 1\n" has two lines (the second is empty), "a\r\nb" two, "a\rb" two: LF, CRLF and a lone CR each end a line
        cases = (
            ("a: 1\n", YamlSubsetError(NAME, 2, "reason"), []),
            ("a: 1\n", YamlSubsetError(NAME, 1, "reason"), []),
            ("a\r\nb", YamlSubsetError(NAME, 2, "reason"), []),
            ("a\rb", YamlSubsetError(NAME, 2, "reason"), []),
            ("a: 1\n", YamlSubsetError(NAME, 0, "reason"), ["line"]),
            ("a: 1\n", YamlSubsetError(NAME, 3, "reason"), ["line"]),
            ("a\r\nb", YamlSubsetError(NAME, 3, "reason"), ["line"]),
            ("a: 1\n", YamlSubsetError(NAME, "1", "reason"), ["line"]),
            ("a: 1\n", YamlSubsetError(NAME, 1, ""), ["reason"]),
            ("a: 1\n", YamlSubsetError("other.yaml", 1, "reason"), ["str(err)"]),
        )
        for text, err, words in cases:
            with self.subTest(text=text, line=err.line, reason=err.reason, name=err.name):
                def refusing(_, name, err=err):
                    raise err

                outcome = sweep(refusing, [text])
                self.assertEqual(outcome.escapes, [])
                self.assertEqual(outcome.refused, 1)
                found = [problem for _, problems in outcome.broken for problem in problems]
                self.assertEqual(len(found), len(words), found)
                for word in words:
                    self.assertTrue(any(word in problem for problem in found), (word, found))

    def test_a_refusal_whose_message_is_not_built_from_its_parts_is_reported(self):
        class Garbled(YamlSubsetError):
            def __str__(self):
                return f"{self.name}:{self.line} {self.reason}"

        def garbled(text, name):
            raise Garbled(name, 1, "reason")

        self.assertEqual(len(sweep(garbled, ["a: 1\n"]).broken), 1)

    def test_lines_are_counted_by_lf_crlf_and_a_lone_cr(self):
        for text, expected in (("", 1), ("a", 1), ("a\n", 2), ("a\nb", 2), ("a\r\nb", 2), ("a\rb", 2), ("a\r\n", 2),
                               ("\r\r\n\n", 4), ("\u2028", 1), ("a\u0085b", 1)):
            with self.subTest(text=text):
                self.assertEqual(lines_in(text), expected)

    # -- what the sampler and the sweep are made of ---------------------------------------------

    def test_single_edits_lists_every_insert_replace_delete_and_truncation(self):
        edits = list(single_edits("ab", alphabet=("x", "yz")))
        self.assertEqual(set(edits), {
            "xab", "yzab", "axb", "ayzb", "abx", "abyz",  # an item inserted at each of three positions
            "xb", "yzb", "ax", "ayz",  # each character replaced by each item
            "b", "a",  # each character deleted
            "",  # cut short at position 0 (position 1 gives "a" again)
        })
        self.assertEqual(len(edits), 14)

    def test_the_sweep_hands_the_loader_every_edit_of_every_document_once(self):
        documents = ("ab", "c: d\n")
        seen = []

        def recording(text, name):
            seen.append(text)

        outcome = exhaustive(recording, documents=documents)
        expected = {damaged for text in documents for damaged in single_edits(text)}
        self.assertEqual(sorted(seen), sorted(expected))
        self.assertEqual((outcome.read, outcome.refused, outcome.escapes), (len(expected), 0, []))

    def test_the_small_documents_are_swept_in_full_and_the_large_ones_only_sampled(self):
        self.assertTrue(all(len(text) < 200 for text in SMALL), "a sweep of a large document is not cheap")
        self.assertGreater(len(SPEC), 1000)
        self.assertGreater(len(REGISTRY), 500)
        every = sum((2 * len(text) + 1) * len(ALPHABET) + 2 * len(text) for text in SMALL)
        self.assertEqual(sum(1 for text in SMALL for _ in single_edits(text)), every)
        distinct = {damaged for text in SMALL for damaged in single_edits(text)}
        self.assertGreater(len(distinct), 91000)

    def test_the_alphabet_keeps_its_control_noncharacter_surrogate_and_indicator_characters(self):
        # the last C1 control, two noncharacters, a lone surrogate, and three indicator characters
        for char in ("\x9f", "\ufffe", "\uffff", "\ud800", "@", "`", "%"):
            with self.subTest(char=char):
                self.assertTrue(char in ALPHABET, f"{char!r} is not in ALPHABET")

    def test_the_small_documents_keep_their_marker_merge_key_indicator_and_character_seeds(self):
        seeds = (
            "... a: 1\n",  # a document marker followed by an entry on the first line
            "x:\n  <<: {a: 1}\n",  # the merge key
            "l: [?a, b]\n",  # a flow entry that starts with a question mark
            "k: @a\n", "k: `a\n", "k: [|a]\n", "|a: 1\n", "l:\n  - %a\n",  # an indicator character first
            "k: a\ufffeb\n", "k: a\uffffb\n", "k: a\ud800b\n",  # two noncharacters and a lone surrogate
        )
        for text in seeds:
            with self.subTest(text=text):
                self.assertTrue(text in SMALL, f"{text!r} is not one of the small documents")

    def test_the_sampler_draws_one_text_per_round_for_every_seed_and_most_of_them_differ(self):
        texts = list(damaged_texts())
        self.assertEqual(len(texts), ROUNDS * len(SEEDS))
        self.assertGreater(len(set(texts)), 11800)  # the small documents have few edits, so they repeat

    def test_the_damage_draws_all_four_kinds_of_edit(self):
        text = "abcdef"  # none of these letters is in the alphabet, so each edit can be told by what is left of them
        rnd = random.Random(SEED)
        kinds = set()
        for _ in range(ROUNDS):
            damaged = damage(text, rnd)
            kept = sum(1 for char in damaged if char in text)
            extra = len(damaged) - kept
            if kept == len(text) and extra:
                kinds.add("insert")
            elif kept == len(text) - 1 and extra:
                kinds.add("replace")
            elif kept == len(text) - 1 and not extra and damaged != text[:-1]:
                kinds.add("delete")
            elif kept <= len(text) - 2 and not extra and text.startswith(damaged):
                kinds.add("truncate")
        self.assertEqual(kinds, {"insert", "replace", "delete", "truncate"})

    # -- determinism ----------------------------------------------------------------------------

    def test_the_probe_is_deterministic(self):
        self.assertEqual(probe(loads, rounds=25), probe(loads, rounds=25))

    def test_the_damaged_texts_are_the_same_on_every_call(self):
        self.assertEqual(list(damaged_texts(rounds=25)), list(damaged_texts(rounds=25)))

    def test_an_explicit_seed_is_honoured_by_the_sampler_and_by_the_probe(self):
        def read_by_the_probe(seed):
            seen = []
            probe(lambda text, name: seen.append(text), rounds=25, seed=seed)
            return seen

        for seed in (1, 2):
            with self.subTest(seed=seed):
                drawn = list(damaged_texts(rounds=25, seed=seed))
                self.assertEqual(drawn, list(damaged_texts(rounds=25, seed=seed)), "the same seed, other texts")
                # the probe reads each distinct text once, in the order they were drawn
                self.assertEqual(read_by_the_probe(seed), list(dict.fromkeys(drawn)))
        self.assertNotEqual(list(damaged_texts(rounds=25, seed=1)), list(damaged_texts(rounds=25, seed=2)))
        self.assertNotEqual(read_by_the_probe(1), read_by_the_probe(2))
        self.assertNotEqual(read_by_the_probe(1), read_by_the_probe(SEED))

    def test_a_fresh_copy_of_this_module_damages_the_documents_the_same_way(self):
        # the seed is read again for each copy, so a seed drawn from the clock (or hidden in a default) differs
        first, second = fresh_copy(), fresh_copy()
        self.assertEqual((first.SEED, first.ROUNDS), (SEED, ROUNDS))
        self.assertEqual((second.SEED, second.ROUNDS), (SEED, ROUNDS))
        self.assertEqual(list(first.damaged_texts(rounds=25)), list(second.damaged_texts(rounds=25)))
        self.assertEqual(list(first.damaged_texts(rounds=25)), list(damaged_texts(rounds=25)))

    def test_the_seed_and_the_number_of_rounds_are_written_as_integer_literals(self):
        with open(__file__, encoding="utf-8") as handle:
            tree = ast.parse(handle.read())
        for name in ("SEED", "ROUNDS"):
            with self.subTest(name=name):
                values = [node.value for node in tree.body if isinstance(node, ast.Assign)
                          and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)]
                self.assertEqual(len(values), 1, f"{name} should be assigned once, at module level")
                self.assertIsInstance(values[0], ast.Constant, f"{name} should be a literal, not an expression")
                self.assertIs(type(values[0].value), int)


if __name__ == "__main__":
    unittest.main()
