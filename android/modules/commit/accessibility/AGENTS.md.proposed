# AGENTS.md — android/modules/commit/accessibility/

## Purpose

The accessibility text-insert mechanism (ADR-022): an Android
`AccessibilityService` that finds the focused editable field and inserts
dictated text into it, so text commit works with whatever keyboard the user
already has on screen, with no window of ours ever opening. Supersedes the IME
approach (ADR-005, now dead code in `commit/ime`).

**First working piece built.** The service, the focused-field resolver that it
publishes into the parent, and the pure insert rule exist, with tests that read
this module's own files. **Not built yet:** `ACTION_PASTE` (it would come only
behind a parent clipboard seam), zero event types in the service config, and
anything proven on a device: nothing here has been run on a device.

**Build phase:** Phase 7. Needs first: `commit` (this module's parent, for the
focused-field registry it publishes into).

## Owns

This module owns:
- The accessibility service itself (`AccessibilityService`), its service
  config (requesting only focused-view events and `canRetrieveWindowContent`,
  because finding the focused field needs it — nothing more).
- Finding the currently focused editable node.
- Inserting text: reading the field's current text and selection, building the
  new text, `AccessibilityNodeInfo.ACTION_SET_TEXT` to merge it in at the
  cursor (since `ACTION_SET_TEXT` replaces the node's whole text, the merge is
  this module's own job), then `ACTION_SET_SELECTION` to place the cursor after
  the inserted text. `ACTION_PASTE` is NOT built: it would come only behind a
  parent clipboard seam.
- The rules of the merge: hint text counts as empty text; a selection is
  replaced and a cursor is an insert point; a reported selection of -1 means
  the end of the text; selection values are clamped and a reversed selection is
  put in order; a surrogate pair is never split; a length limit refuses the
  insert and never cuts the text.
- Refusing, so the parent falls back to the clipboard: an empty text, a field
  of this app's own package, a field that is not editable or not enabled, and
  a node that is no longer valid.
- Refusing to insert into password fields (`isPassword`).
- Publishing the focused field into the parent's `FocusedFieldHolder` registry
  (`android_commit`, `adapter/FocusedFieldHolder`) so `CommitService` finds it
  the same way it will have found anything else published there.
- The limits ADR-022 states plainly: never storing, logging or sending screen
  content off the device; acting only on an explicit send, never passively.

## Public Interface

One public type: the final class `BreakerAccessibilityService`, the
`AccessibilityService` subclass that the framework creates and that the manifest
names (`.adapter.BreakerAccessibilityService`). Everything else is `internal`:
the pure insert rule, the node seam, the focused-field resolver and the Android
adapter. The service publishes its resolver through the parent's
`FocusedFieldHolder.publish` when it connects, and closes the returned handle
on unbind and on destroy.

## Depends On
- android_commit (registered in modules.toml)

## Does Not Own
- The mechanism-neutral commit decision between a focused field and the clipboard, plus the toast (commit)
- The dead keyboard (IME) adapter (commit/ime)
- Onboarding for the accessibility permission grant and the Android 13+
  restricted-setting step (ui)
- The floating tile that starts and stops dictation (overlay)

## Invariants

From ADR-022, enforced by tests that read this module's own files:
- Never inserts into a password field (`isPassword`).
  - Tested by: `NodeFocusedFieldRefusalTest`, `NodeFocusedFieldPrivacyTest`,
    `AdapterMappingGateTest`.
- Never stores, logs, or sends off the device any text or content read from
  the screen; it reads only what one insert needs, in the moment.
  - Tested by: `PrivacyScanTest`, `PureFilesScanTest`, `InsertPlanRedactionTest`,
    `NodeFocusedFieldPrivacyTest`, `NodeFocusedFieldErrorTest`, `AdapterGateTest`,
    `ManifestGateTest`.
- Acts only on an explicit send, never passively on whatever the service
  happens to see.
  - Tested by: `AdapterGateTest`, `NodeFocusedFieldRefusalTest`,
    `NodeFocusedFieldPrivacyTest`, `NodeFocusedFieldInsertTest`.
- The service config requests only focused-view events and
  `canRetrieveWindowContent` — nothing broader than finding the focused field
  needs.
  - Tested by: `ConfigGateTest`, `ManifestGateTest`, `ModuleFilesGateTest`.

## Test Locations
- Unit (Kotlin): `android/modules/commit/accessibility/src/test/kotlin/`. Run: `./gradlew :android:modules:commit:accessibility:test`
  - Insert rule: `InsertPlanMergeTest`, `InsertPlanSelectionTest`, `InsertPlanSurrogateTest`,
    `InsertPlanRefusalTest`, `InsertPlanRedactionTest`.
  - Doubles and their tests: `FakeFieldNode` (with the fake finder), `FakeFieldNodeTest`,
    `FakeFieldNodeSwitchesTest`.
  - Focused-field resolver: `NodeFocusedFieldInsertTest`, `NodeFocusedFieldRefusalTest`,
    `NodeFocusedFieldSetTextRefusalTest`, `NodeFocusedFieldReleaseTest`,
    `NodeFocusedFieldPrivacyTest`, `NodeFocusedFieldErrorTest`.
  - Gates that read the module's own files: `PureFilesScanTest`, `PrivacyScanTest`,
    `TestRulesScanTest`, `ManifestGateTest`, `ConfigGateTest`, `AdapterGateTest`,
    `AdapterMappingGateTest`, `ModuleFilesGateTest` (with the helpers `SourceFiles.kt`,
    `XmlFiles.kt` and the sample files `AdapterGateSamples.kt`, `AdapterMappingSamples.kt`).
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
- Own-package fields are refused: a field of this app is never a target, and the
  commit then goes to the clipboard.
- Hint text counts as empty text: the hint is dropped and the insert starts from nothing.
- A selection of -1 (either value negative) means the end of the text.
- A length limit refuses the insert; the text is never truncated to fit.
- Surrogate pairs are never split by the cursor or by a selection.
- A failing `ACTION_SET_SELECTION` is still a successful insert: the text landed and
  the cursor is best effort.
- `ACTION_SET_TEXT` can return true without changing the text, and there is no
  read-back (a second read of the text is forbidden by design). Not verified on a device.
- The focused field is found at send time with `findFocus(FOCUS_INPUT)`; it is not
  tracked from events.
- The node is released exactly once on every path, and a password field's text is
  never read.
- The receiver names in the adapter (`node`, `root`, `service`, `found`) are pinned by
  `AdapterGateTest` and `AdapterMappingGateTest`; renaming one needs both gates updated.
- Not verified on a device (nothing in this module has been run on one):
  - `findFocus(FOCUS_INPUT)` at send time while the floating tile is showing.
  - The cases where the active window has no root.
  - Zero event types in the service config.
  - WebView fields.
  - `ACTION_SET_SELECTION` right after `ACTION_SET_TEXT` (a stale selection).
  - Hint text handling, and the refusal of this app's own fields in practice.
  - Apps that return false for `ACTION_SET_TEXT`, or true without changing their text.
  - The Android 13+ restricted-setting flow (owned by `ui`).
  - Recycling a node on Android 11 and 12 compared with the no-op from Android 13.
  - Whether the service survives the system binding it again.
  - The paste fallback, only if `ACTION_SET_TEXT` turns out to be refused widely.
