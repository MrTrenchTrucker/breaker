# Format Prompts — README

The strict formatting prompts for the server LLM path of the `format` module,
held here so the phone and any server-side tooling agree on one wording. This
module owns the words and the parameters they are sent with; the formatter that
sends them and the LLM that receives them live elsewhere.

## What is in this module

- `prompts/format-v1.md` — the shipped prompt: the system prompt the server LLM
  path sends (in a fenced block), the parameters it is sent with (in the front
  matter), and a worked example of the case it exists for. The rule behind the
  parameters: **formatting is non-destructive** — temperature 0, structured
  output and JSON mode off, plain text out, and a token budget that grows with
  the input instead of truncating it.
- `src/main/kotlin/dev/breaker/shared/prompts/FormatPrompt.kt` — the same
  contract as types, in three parts:
  - `PromptParameters` — the fixed values: temperature 0, structured output
    off, JSON mode off, plain text, and `maxTokensFor()`, the token budget for
    a given input (a small multiple of the input, with headroom, never below a
    floor, and monotonic in the input).
  - `FormatPrompt` — one versioned prompt. Its constructor refuses a prompt
    that does not run at temperature 0, that turns structured output or JSON
    mode on, or that has lost any of the clauses that keep formatting
    non-destructive — a prompt that says different words from what it promises
    fails to build, not to format.
  - `PromptCatalog` — the shipped ids. A caller names an id and the catalogue
    loads that prompt from the classpath; an id the module does not ship is a
    start-up failure, never a fallback to different wording.
- `src/test/kotlin/dev/breaker/shared/prompts/FormatPromptTest.kt` — the
  prompt's contract: the shipped wording says what it must, the parameters
  cannot let the model rewrite, and the file in the repository is the same
  bytes as the copy on the classpath.

## How the prompt gets to the LLM

`build.gradle.kts` points the module's resources at `prompts/`, so the file in
the folder is the single copy: there is no second generated copy for a consumer
to read. `PromptCatalog` loads the file by id and reads the system prompt out
of its fenced block, so the surrounding prose can be edited without touching
what the model receives.

## Changing a prompt

1. Change the wording.
2. Bump the `id`/`version` and the filename to match: `format-v2.md`.
3. Update the golden tests in `android/modules/format` in the same change.

Never edit a shipped version in place. A prompt that changes under a version
number is drift, and drift here is invisible until the output changes under a
user.

Full module card: `AGENTS.md` in this folder.
