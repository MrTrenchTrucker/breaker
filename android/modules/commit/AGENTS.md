# AGENTS.md — android/modules/commit/

## Purpose

CommitService: IME text commit + clipboard fallback + toast. CommitService — get transcription text into the user's target field.
*Text commit / text insertion* — never "injection."

**Build phase:** Phase 7 (start a spike of the IME part in Phase 5; it carries the most platform risk). Needs first: `core` (on main).

## Owns
CommitService: IME text commit + clipboard fallback + toast.

## Public Interface `core.TextCommitter`.

Implements `commit(request: CommitRequest): CommitOutcomeResult`.

- `CommitService : core.TextCommitter`. Its constructor is `internal`; the only
  public way to get one is `adapter/CommitServices.create(context)`, the one place
  the app builds it (once per process, from the application context).
- `commit` is blocking, not `suspend`. The caller owns the threading and normally
  calls it from a worker thread (a call on the main thread runs the block
  inline). It never throws an `Exception`, and no string it builds can contain
  the text.
- `BreakerInputMethodService` is public because the framework creates it; the
  app lists it in its manifest (see "What the app must add later").
- Layout: six pure Kotlin files in the module's package (no Android class is
  named in them) and seven files in `adapter/`, the only place that names
  Android classes.

**Outcomes** (`detail` is a fixed text, never the committed text):

| Outcome | detail | When |
|---|---|---|
| `COMMITTED` | none | The focused field of our keyboard accepted the text. |
| `COPIED` | none | There was no focused field, and the clipboard took the text. |
| `COPIED` | `The field did not accept the text, so it was copied instead.` | A field was focused but refused the text or threw, and the clipboard took the text. |
| `FAILED` | `The text could not be put anywhere.` | No field took the text and the clipboard was unavailable or threw. |
| `FAILED` | `The screen was not responding, so the text was not sent.` | The block did not start in time or the main thread refused it, or the caller was interrupted while waiting. |

**Logic (on "send"):**
1. Take the text from the request and make a fresh "still wanted" flag for this
   call (a `Job`).
2. Make the one hop to the main thread: post the block and wait for it. Every
   platform call (finding the field, typing into it, the clipboard, the toast)
   runs inside that one block and nowhere else. A block that has started on the
   main thread is waited for, and the result reports what it did. A block that
   has not started when the deadline passes is dropped: it never runs, and the
   call fails as not responding. The deadline only frees the caller; it cannot
   stop a step that is already running. The deadline is 5 seconds, a safety net
   for a stuck main thread, not a measured value.
3. Inside the block: if a field of our keyboard is focused, hand it the text. If
   it accepts, the result is `COMMITTED` and nothing else runs. If it refuses or
   throws, remember the refusal and go on. If no field is focused, go on.
4. Copy the text to the clipboard. The sensitive flag is always set: dictated
   text is private. If the clipboard is unavailable or throws, the result is
   `FAILED` with "The text could not be put anywhere."
5. Show the toast "Copied to clipboard." only when the SDK level is 32 or lower.
   From level 33 (Android 13) the system shows its own copy confirmation, so ours
   would be a second one. A failing toast never turns a copy into a failure: the
   text is already on the clipboard.
6. Return `COPIED`, with the refusal detail when step 3 saw a refusal and no
   detail otherwise. Outside the block: if the block did not start in time (the
   deadline passed, the main thread refused the post, or the wait was
   interrupted), cancel the flag and return `FAILED` with "The screen was not
   responding, so the text was not sent." An interrupt seen anywhere is put back
   on the caller's thread.

**Late-hop guard:** the hop drops a block that has not started at the deadline,
so it never runs. As a second line, the flag from step 1 is cancelled whenever
the hop fails: a main thread that runs a block late anyway finds the flag
inactive and touches nothing (no commit, no copy, no toast). A step already
running cannot be stopped; the guard only prevents the next one. If the wait is
interrupted after the block started, the call still fails as not responding and
the block finishes on its own.

**IME:** Android `android.inputmethodservice` framework (same mechanism
Fleksy/SwiftKey use). One-time user setup: Settings → Keyboards → enable our IME.
Onboarding screen in the app links there (R5).

## What the app must add later
The module is a library. It has no manifest entry and no `method.xml`, so until
the app adds these, the system does not offer our keyboard:
- A `<service>` entry for `dev.breaker.dictation.commit.adapter.BreakerInputMethodService`
  with `android:permission="android.permission.BIND_INPUT_METHOD"` and an
  intent filter for `android.view.InputMethod`.
- `<meta-data android:name="android.view.im" android:resource="@xml/method"/>` on
  that service, and the file `res/xml/method.xml`.
- The keyboard view (the service has no input view yet).
- The onboarding link to the keyboard settings.
- One call to `CommitServices.create(context)` per process, handed to the use
  case that needs a `TextCommitter`.
- The preview toggle (optional), which is not built here.

## Invariants
- Send with focused field → text appears inline (F4).
- Send with no focused field: the text goes to the clipboard, with a toast on SDK
  level 32 or lower only (F5).
- Commit only fires on explicit send tap (T8).
- Preview toggle shows text before commit (optional, settings).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Formatting (format)
- History storage (history)

## Test Locations
- Unit (Kotlin): `android/modules/commit/src/test/kotlin/dev/breaker/dictation/commit/`. Run: `./gradlew :android:modules:commit:test`
  - Service tests: `CommitServiceImeTest`, `CommitServiceClipboardTest`, `CommitServiceFailureTest`, `CommitServiceRedactionTest` (with the fakes in `Fakes.kt`).
  - Hop tests: `CommitServiceThreadHopTest`, `CommitServiceLateHopTest`, `CommitServiceInterruptTest`, `CommitServiceExplicitSendTest`, and, for the wait itself, `PostedMainThreadTest`, `PostedMainThreadClaimTest`, `PostedMainThreadServiceTest` (with the fakes in `HopFakes.kt`).
  - Other tests: `NoticeGatingTest`, `FocusedFieldRegistryTest`.
  - Scan tests (read the source text): `PureFilesScanTest`, `AndroidConfinementTest`, `ConcurrencyRuleScanTest`, `TestRulesScanTest` (with the helper `SourceFiles.kt`).
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
- The toast is shown only on SDK level 32 or lower, because Android 13 and later
  show their own copy confirmation, and ours would be a second one.
- The text of a refused or failed commit is never in a detail. Details are the
  fixed constants in `CommitTexts`; keep it so when adding an outcome.
- A step already running cannot be stopped, by the deadline or by the late-hop
  guard. The deadline only frees a caller whose block has not started.
- The only process-wide holder is `adapter/ImeHolder`, because the framework
  creates the keyboard service itself. No test touches it; every test builds its
  own registry.
- Known risk: the dictation screen is an Activity, and showing it ends the
  keyboard's input session, which clears the focused field. Until the app keeps
  the session alive (or the commit happens from a surface that does not end it),
  the clipboard path will be the common one and `COMMITTED` the rare one.
- Not verified on a device (the adapter files run no JVM test, and nothing in
  this module has been run on a device):
  - The keyboard service, and what `currentInputConnection` returns in
    `onStartInput` and `onFinishInput` on real apps.
  - Whether the clip's sensitive extra hides the copy preview on Android 13 and
    later.
  - The toast on SDK level 32 and lower, and its absence from level 33.
  - The hop timing on a busy main thread.
  - The whole focused-field flow, from a field gaining focus to text appearing in
    it.
  - Where the cursor lands after the text is committed.
