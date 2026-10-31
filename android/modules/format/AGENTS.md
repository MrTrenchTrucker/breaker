# AGENTS.md — android/modules/format/

## Purpose

Whisper Flow-style formatting: server LLM + rule-based local formatter. Whisper Flow-style formatting — raw dictation → clean structured text
(F8). Non-destructive (N9): same meaning, only reformat; never invent content.

**Build phase:** Phase 8. The rule-based local formatter can start now; the server LLM path needs `whisper-server` (Phase 4). Needs first: `core` and `format-prompts` (both on main).

## Owns
Whisper Flow-style formatting: server LLM + rule-based local formatter.

## Public Interface `core.Formatter` port.

**Two adapters, one interface:**
1. `ServerLlmFormatter` (primary) — calls the EXISTING `/v1/chat/completions` LLM
   on Local Server with the strict formatting prompt from `shared/format-prompts`.
   Temperature 0, structured output off (plain text), non-destructive system prompt.
2. `RuleBasedFormatter` (fallback) — deterministic regex grammar for enumerations
   ("one is / two is / three is" → numbered list), sentence casing, filler-word
   removal. Zero cost, always available offline. Ordinal markers
   match without regard to letter case (Locale.ROOT); item text keeps its own
   casing.

**Selection:** core's `DictateUseCase` picks the adapter, not `transport`: a
transcript from the server engine goes to the LLM formatter, an on-device
transcript to the rule-based one (never to the server), and nothing is
formatted when `formatting_enabled` is off.

**Example (must pass as a golden test):**
- In:  "I have 3 things I want you to go over one is file a, two is file b, three is file c"
- Out: "I have 3 things I want you to go over:\n\n1. is file a.\n2. is file b.\n3. is file c."

## Invariants
- Golden tests: numbered lists, bullet lists, filler removal, punctuation.
- **Diff-check test (N9):** formatted output must not contain content absent from
  the raw text (token-overlap check). LLM path: temperature 0, structured output off (plain text).
- Rule-based formatter is fully unit-testable offline.
- Formatting on/off setting respected (F10).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_format_prompts (registered in modules.toml)
- android_http (registered in modules.toml)

## Does Not Own
- The LLM itself (server)
- Prompts (shared/format-prompts)

## Test Locations
- Unit (Kotlin): `android/modules/format/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:format:test`
- Contract: `tests/contract/test_format_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_format_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Formatting must be non-destructive (N9) — diff-check tests.
- `ServerLlmFormatter` asks `{base}/v1/chat/completions`; the base address, the key and the model name come from three providers read on every call. On ANY problem (a blank base or model, a transport error, a status other than 200, a reply that cannot be read or has the wrong shape, or an answer the N9 guard refuses) it returns what the fallback `Formatter` returns; a blank transcript comes back unchanged with no call. The guard accepts an answer only when it is not blank, is at most four times the transcript length plus 64 characters, every word of it is a word of the transcript (list numbers of up to three digits aside), and at least half as many words that are not numbers remain as the transcript had. The adapter is a library: no app slot uses it yet.

## Deliberate limits of the rule-based formatter

Three limits of the rule-based fallback are deliberate, not defects. They are
documented here so they are not "fixed" by accident:

- Empty and already-punctuated finals are left alone. `finalPunctuation` trims the
  text and then adds a period only when it is non-empty and does not already end in
  `.`, `?` or `!`: text that trims to empty gets no punctuation, and a final
  sentence mark the speaker said is not duplicated.
- A sentence end right after a digit is not a boundary. `SENTENCE_END` looks behind
  the mark for a non-digit, so a mark after a mid-sentence number does not start a new
  sentence: the next word is not capitalized after it.
- A numbered run is only as good as its joins. `findRun` continues a run while each
  next item is joined by exactly `", "` (comma plus one space); any other join ends
  the run there and the rest is left as ordinary text.
