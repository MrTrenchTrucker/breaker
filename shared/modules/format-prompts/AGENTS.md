# AGENTS.md — shared/modules/format-prompts/

## Purpose

Strict non-destructive formatting prompts for the server LLM. The strict formatting prompts used by the server LLM path of the
`format` module. Versioned here so the app and any server-side tooling agree.

**Contract:**
- System prompt: "Reformat the user's dictation into clean, well-structured text.
  Preserve every fact and the exact meaning. Add punctuation, fix casing, remove
  filler words, and convert spoken enumerations ('one is X, two is Y') into
  numbered lists. Never add, remove, or change content. Output text only."
- Parameters: temperature 0, structured-output/JSON mode off (plain text),
  max_tokens proportional to input.
- Versioned: `format-v1.md` (current). Changes require golden-test updates in
  `android/modules/format`.

**Build phase:** Built (on main).

## Invariants
- Prompt is versioned and referenced by the app (no drift).
- Golden example from F8 passes through the LLM path.
- The N9 diff-check lives in android/format's tests; this module supplies the prompt wording and refuses a prompt that drops a non-destructive clause.

## Owns
Strict non-destructive formatting prompts for the server LLM.

## Public Interface
- `prompts/*` — the shipped prompt files, the data of record (e.g. `format-v1.md`).
- `PromptParameters` — the fixed parameters the prompt is held to: temperature 0, structured output off, JSON mode off, plain text, and the token-budget rule (`maxTokensFor`).
- `FormatPrompt` — one versioned prompt: the fields it carries and the constructor that refuses wording that lost a non-destructive clause or a parameter.
- `PromptCatalog` — the shipped prompt ids and the load-by-id path that refuses an id this module does not ship.

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Formatter logic (android/format)
- The LLM (server)

## Test Locations
- Unit (Kotlin): `shared/modules/format-prompts/src/test/kotlin/dev/breaker/shared/prompts/`. Run: ./gradlew :shared:modules:format-prompts:test
- Contract: `tests/contract/test_format_prompts_contract.py`. Run: python3 -m unittest discover -s tests/contract -t tests/contract -p test_format_prompts_contract.py
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
- Temperature 0, structured output off (plain text) — hallucination is still the risk (R10).
