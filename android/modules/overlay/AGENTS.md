# AGENTS.md — android/modules/overlay/

## Purpose

Floating tile = CB mic glyph + LED bar meter (WindowManager overlay, F36). The floating tile that appears over any app and starts dictation.

**In:** `show()`/`hide()`, called by the app's DI wiring (ADR-001), not through
a core port: on a shake (`gesture`), on the wake phrase (`PhraseEvent.Wake`,
which `phrases` delivers through `core.PhraseTrigger`), or from a manual
toggle. `overlay` never imports `gesture` or `phrases`.
**Out:** tap event → opens dictation UI (a normal in-app Activity; only the tile itself uses `TYPE_APPLICATION_OVERLAY`).

**Implementation:**
- `WindowManager` + `TYPE_APPLICATION_OVERLAY` + `setFloating(true)` (Android 11+).
- **Caveat (verified in research):** overlay windows cannot take focus and an
  EditText inside an overlay won't raise the keyboard — so the tile is **tap-only**;
  the dictation UI opens in a normal window. This matches the design.
- Permission to verify: `SYSTEM_ALERT_WINDOW` (version-dependent).
- Reference: LexiSharp Keyboard's overlay management (WindowManager API,
  permissions, lifecycle, multi-window coordination).
- Draggable tile; position persisted to settings.
- Permission set stays minimal: mic, internet, foreground service [1].

**Look & feel (F36):**
- The tile IS the **CB mic glyph** (favicon art from `shared/ui-tokens`).
- **LED bar meter** (digital Cobra-style segments) renders **directly above the
  mic** when the mic is awake and recording; fills with audio level.
- State colors: armed = pulsing green ring · recording = green LED fill ·
  **sent = green** (copy confirmed) · **server-fail→local fallback = orange** ·
  **complete failure = red**.

**Build phase:** Phase 6, together with `gesture`. Needs first: `core` and `ui-tokens` (both on main).

## Invariants
- Tile floats above other apps; tap opens dictation UI (F4).
- Tile is draggable and position persists.
- No focus stealing; other apps keep focus while tile is shown (T4).
- Tile renders the CB mic glyph; LED bar fills above it while recording (F36).
- State colors correct: sent green · fallback orange · failure red (F36).

## Owns
Floating tile = CB mic glyph + LED bar meter (WindowManager overlay, F36).

## Public Interface
FloatingTile

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)

## Does Not Own
- Dictation UI (ui)
- Gesture/voice triggers

## Test Locations
- Unit (Kotlin): `android/modules/overlay/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:overlay:test`
- Contract: `tests/contract/test_overlay_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_overlay_contract.py`
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
- Overlay windows can't take focus — tile is tap-only; dictation opens in a normal window.
