"""Contract test for the `shared/modules/format-prompts` module.

Pins the structural contract this module has with the rest of the repository
(registry entry, card, Gradle wiring, declared dependencies) and checks that
the module card agrees with the shipped prompt file: the system prompt it
quotes, the parameters it states, and the test paths it names. The Kotlin
tests hold the prompt against the code; nothing else held the card against
the prompt, which is the gap this class now closes.
"""
import os
import re
import unittest

import contract_support


def _norm(text):
    """Whitespace- and quote-normalised text, for comparing quoted wording."""
    text = text.replace("\u2019", "'").replace("\u2018", "'")
    return " ".join(text.split()).lower()


def _fence_lines(card):
    """True for each line that sits inside a fenced code block.

    A card may show a section layout in a fenced example; that example is
    content, not a declaration, so a `## ` line inside a ``` fence is not a
    heading. Mirrors the shared reader's fence rule, kept local on purpose.
    """
    lines = card.splitlines()
    inside = []
    in_fence = False
    for line in lines:
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            inside.append(True)  # the fence line itself is not a heading
            continue
        inside.append(in_fence)
    return inside


def _card_section(card, heading):
    """Body of a `## heading` section.

    Mirrors the shared reader in contract_support, kept local on purpose: a
    second, independent reading of the same rule. If both used one helper, a
    bug in that helper would satisfy them both at once. Same rule as the
    shared one: the heading must be the NAME at the start of a `## ` line (a
    `### ` sub-heading is content; "## Ownership notes" is not the Owns
    section), a `## ` line inside a fenced code block is an example and is
    never counted, a repeated heading fails loudly, and an absent section
    returns "".
    """
    name = heading[3:]  # strip the leading "## "
    lines = card.splitlines()
    fenced = _fence_lines(card)
    matches = []
    for i, line in enumerate(lines):
        if fenced[i]:
            continue
        if line.startswith("## "):
            head = line[3:]
            if head == name or (
                head.startswith(name)
                and not (head[len(name)].isalnum() or head[len(name)] == "_")
            ):
                matches.append(i)
    if not matches:
        return ""
    if len(matches) != 1:
        raise AssertionError(
            f"{heading!r} opens {len(matches)} sections in this card — a "
            "repeated heading is a broken card, not a first-wins read"
        )
    start = matches[0]
    end = len(lines)
    for j in range(start + 1, len(lines)):
        if not fenced[j] and lines[j].startswith("## "):
            end = j
            break
    return "\n".join(lines[start:end])


def _fenced_block(text, open_fence):
    """The first fenced block opening with open_fence.

    A markdown block closes at the first line that is a bare triple-backtick
    fence, which is why the close is searched for as a line-start ``` rather
    than as the opening fence again: the closing fence of a ```text block is
    just ```, and searching for the opening string would sail past it to the
    next ```text opening. (The Kotlin reader agrees on the open and the close,
    but differs inside the block: it refuses any other fence line there, so a
    block this helper would sail past, the reader would refuse as
    unterminated.)
    """
    open_idx = text.find(open_fence)
    if open_idx < 0:
        return ""
    start = text.find("\n", open_idx)
    if start < 0:
        return ""
    end = text.find("\n```", start)
    return text[start + 1:end] if end > start else ""


class SharedModulesFormatPromptsContractTest(contract_support.ModuleContractTest):
    MODULE = "shared_format_prompts"

    CARD = "shared/modules/format-prompts/AGENTS.md"
    PROMPT = "shared/modules/format-prompts/prompts/format-v1.md"

    # ── the card agrees with the shipped prompt file ─────────────────────

    def test_card_system_prompt_matches_the_prompt_file(self):
        """A card that quotes different wording than the file ships is drift."""
        card = contract_support._card_text(self.CARD)
        # The card quotes the prompt as an inline double-quoted string under
        # its Contract heading (it is hard-wrapped, so the quote spans lines).
        marker = card.find("- System prompt: ")
        self.assertGreaterEqual(
            marker, 0,
            "card %s carries no 'System prompt:' line under its Contract "
            "heading, so it quotes no wording to hold to" % self.CARD,
        )
        q1 = card.find('"', marker)
        q2 = card.find('"', q1 + 1)
        self.assertGreater(
            q2, q1,
            "card %s 'System prompt:' line opens a quote but never closes "
            "one, so no wording can be compared" % self.CARD,
        )
        quoted = card[q1 + 1:q2]
        prompt_text = contract_support._card_text(self.PROMPT)
        shipped = _fenced_block(
            prompt_text[prompt_text.find("## System prompt"):],
            "```text")
        self.assertTrue(
            shipped,
            "prompt file %s carries no ```text block under '## System prompt'"
            % self.PROMPT,
        )
        self.assertEqual(
            _norm(quoted), _norm(shipped),
            "card %s quotes a system prompt that does not match the shipped "
            "wording in %s (whitespace-normalised compare).\n"
            "card: %s\nfile: %s"
            % (self.CARD, self.PROMPT, _norm(quoted), _norm(shipped)),
        )

    def test_card_parameters_match_the_prompt_file(self):
        """Card and file must state the same parameters, or the reader is
        sent to a prompt the file does not carry."""
        card = contract_support._card_text(self.CARD)
        card_params = " ".join(
            line.strip() for line in card.splitlines()
            if line.strip().startswith("- Parameters:"))
        self.assertTrue(
            card_params,
            "card %s states no parameter line under its Contract heading"
            % self.CARD,
        )
        card_params_norm = _norm(card_params)
        # The card side reads VALUES, not words: a substring match would let
        # "temperature 0.2" pass as "temperature 0", and the bare word "json"
        # is present whether the mode is on or off, so a card stating the
        # wrong parameters would pass. The number is extracted and compared,
        # and the off state must be stated as a phrase, not implied.
        temps = re.findall(
            r"temperature\s*:?\s*(\d+(?:\.\d+)?)", card_params_norm)
        self.assertEqual(
            temps, ["0"],
            "card %s states temperature %r, but the shipped prompt file "
            "runs at temperature 0: %r" % (self.CARD, temps, card_params),
        )
        for mode in ("structured-output", "json"):
            self.assertIn(
                mode, card_params_norm,
                "card %s no longer names %s, which the shipped prompt file "
                "keeps off" % (self.CARD, mode),
            )
        self.assertIn(
            "mode off", card_params_norm,
            "card %s does not state that the structured-output/JSON mode "
            "is off as a phrase (it says %r); the shipped prompt file "
            "requires it off" % (self.CARD, card_params),
        )
        self.assertIn(
            "plain text", card_params_norm,
            "card %s does not state plain-text output, which is what the "
            "shipped prompt file's response_format is" % self.CARD,
        )
        front = contract_support._card_text(self.PROMPT).split("---", 2)[1]
        declared = {
            m.group(1): m.group(2).strip()
            for m in re.finditer(r"^\s{2}(\w+):\s*(.+)$", front, re.M)
        }
        expected = {
            "temperature": "0",
            "structured_output": "false",
            "json_mode": "false",
            "response_format": "text",
        }
        for key, value in expected.items():
            self.assertEqual(
                declared.get(key), value,
                "prompt file %s front matter declares %s: %r but the shipped "
                "contract (card states %s) requires %s: %r"
                % (self.PROMPT, key, declared.get(key),
                   card_params, key, value),
            )

    def test_every_path_named_in_test_locations_exists(self):
        """A card that points at tests that do not exist is a broken card."""
        card = contract_support._card_text(self.CARD)
        section = _card_section(card, "## Test Locations")
        paths = re.findall(r"`([^`]+)`", section)
        self.assertTrue(
            paths,
            "card %s Test Locations section names no paths at all" % self.CARD,
        )
        for rel in paths:
            self.assertTrue(
                os.path.exists(os.path.join(contract_support.ROOT, rel)),
                "card %s Test Locations names %r, which does not exist in the "
                "repository" % (self.CARD, rel),
            )

    def test_public_interface_names_every_registry_entry(self):
        """The card's Public Interface must name the registry's whole
        interface for this module, entry by entry: an interface the card
        fails to list is a drift the reader cannot see."""
        public = contract_support._load_registry()[self.MODULE]["public"]
        entries = [
            entry.strip()
            for part in public.split(";")
            for entry in part.split(",")
            if entry.strip()
        ]
        self.assertTrue(
            entries,
            "registry public for %s parsed to no entries: %r"
            % (self.MODULE, public),
        )
        card = contract_support._card_text(self.CARD)
        section = _card_section(card, "## Public Interface")
        # Exact bullet names, not substring containment. An entry is named
        # only as a bullet heading (`- `Entry` ...`), not as a word that
        # happens to appear in another bullet's prose, and not as a
        # superstring of the name. Both directions below match on this one
        # list of names, so a missing entry and an extra one are each
        # caught, and neither is fooled by a stray word.
        named = re.findall(r"^- `([^`]+)`", section, re.M)
        missing = [entry for entry in entries if entry not in named]
        self.assertEqual(
            missing, [],
            "card %s Public Interface does not name every registry public "
            "entry for %s; missing: %s (registry public: %r, card bullets: "
            "%r)"
            % (self.CARD, self.MODULE, missing, public, named),
        )
        # The reverse direction, for the same reason the shared machinery
        # runs its checks both ways: the registry's public is the module's
        # whole interface, so a card that names an entry the registry does
        # not carry is drift too. Without it, a registry that drops an
        # entry while the card keeps it passes green.
        extras = [name for name in named if name not in entries]
        self.assertEqual(
            extras, [],
            "card %s Public Interface names entries the registry does not "
            "carry for %s: %s (registry public: %r)"
            % (self.CARD, self.MODULE, extras, public),
        )


if __name__ == "__main__":
    unittest.main()
