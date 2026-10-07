# AGENTS.md — android/modules/commit/accessibility/

## Purpose

The accessibility text-insert mechanism (ADR-022): an Android
`AccessibilityService` that finds the focused editable field and inserts
dictated text into it, so text commit works with whatever keyboard the user
already has on screen, with no window of ours ever opening. Supersedes the IME
approach (ADR-005, now dead code in `commit/ime`).

**Not built yet.** This module carries no code, only its structure.

**Build phase:** Phase 7. Needs first: `commit` (this module's parent, for the
focused-field registry it publishes into).

## Owns

Once built, this module owns:
- The accessibility service itself (`AccessibilityService`), its service
  config (requesting only focused-view events and `canRetrieveWindowContent`,
  because finding the focused field needs it — nothing more).
- Finding the currently focused editable node.
- Inserting text: reading the field's current text and selection, building the
  new text, `AccessibilityNodeInfo.ACTION_SET_TEXT` to merge it in at the
  cursor (since `ACTION_SET_TEXT` replaces the node's whole text, the merge is
  this module's own job), then placing the cursor after the inserted text; or
  `ACTION_PASTE` from the clipboard as the other insertion path.
- Refusing to insert into password fields (`isPassword`).
- Publishing the focused field into the parent's `FocusedFieldHolder` registry
  (`android_commit`, `adapter/FocusedFieldHolder`) so `CommitService` finds it
  the same way it will have found anything else published there.
- The limits ADR-022 states plainly: never storing, logging or sending screen
  content off the device; acting only on an explicit send, never passively.

## Public Interface

None yet — no code exists. This section will name the public type(s) (the
`AccessibilityService` subclass the framework creates directly, and whatever
pure Kotlin logic backs it) once Phase 7 adds them.

## Depends On
- android_commit (registered in modules.toml)

## Does Not Own
- The mechanism-neutral commit decision between a focused field and the clipboard, plus the toast (commit)
- The dead keyboard (IME) adapter (commit/ime)
- Onboarding for the accessibility permission grant and the Android 13+
  restricted-setting step (ui)
- The floating tile that starts and stops dictation (overlay)

## Invariants

None enforced yet — there is no code. Once built, from ADR-022:
- Never inserts into a password field (`isPassword`).
- Never stores, logs, or sends off the device any text or content read from
  the screen; it reads only what one insert needs, in the moment.
- Acts only on an explicit send, never passively on whatever the service
  happens to see.
- The service config requests only focused-view events and
  `canRetrieveWindowContent` — nothing broader than finding the focused field
  needs.

## Test Locations
- Unit (Kotlin): `android/modules/commit/accessibility/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:commit:accessibility:test`
- Contract: `tests/contract/test_commit_accessibility_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_commit_accessibility_contract.py`
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
- Terminology: text commit, never "injection" (repo-wide rule).
- `ACTION_SET_TEXT` replaces a node's whole text; the merge-at-cursor behavior
  is this module's own job, not something the platform does for it (ADR-022).
- An accessibility service is a powerful permission — Android lets it read
  what is on screen. The limits in Invariants above are what keep that honest;
  they are not optional hardening, they are the design.
- Android 13+ shows sideloaded accessibility services as "restricted" until
  the user allows them in the app's info screen; onboarding (owned by `ui`)
  has to walk that step, not just the permission grant.
