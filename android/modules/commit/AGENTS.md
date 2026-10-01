# AGENTS.md — android/modules/commit/

## Purpose

CommitService: IME text commit + clipboard fallback + toast. CommitService — get transcription text into the user's target field.
*Text commit / text insertion* — never "injection."

**Build phase:** Phase 7 (start a spike of the IME part in Phase 5; it carries the most platform risk). Needs first: `core` (on main).

## Owns
CommitService: IME text commit + clipboard fallback + toast.

## Public Interface `core.TextCommitter`.

Implements `commit(request: CommitRequest): CommitOutcomeResult`.

**Logic (on "send"):**
1. If our IME is active and a text field is focused →
   `InputMethodService.commitText(...)` inserts text inline (candidate bar optional).
2. Else → clipboard copy + toast "Copied to clipboard."
3. Return `CommitOutcomeResult(outcome, detail)`: `outcome` is one of
   `COMMITTED|COPIED|FAILED`; `detail` is an optional user-facing reason,
   never the committed text.

**IME:** Android `android.inputmethodservice` framework (same mechanism
Fleksy/SwiftKey use). One-time user setup: Settings → Keyboards → enable our IME.
Onboarding screen in the app links there (R5).

## Invariants
- Send with focused field → text appears inline (F4).
- Send with no field → clipboard + toast (F5).
- Commit only fires on explicit send tap (T8).
- Preview toggle shows text before commit (optional, settings).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Formatting (format)
- History storage (history)

## Test Locations
- Unit (Kotlin): `android/modules/commit/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:commit:test`
- Contract: `tests/contract/test_commit_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_commit_contract.py`
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
- Terminology: text commit, never 'injection'.
