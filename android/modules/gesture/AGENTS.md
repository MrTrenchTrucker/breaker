# AGENTS.md — android/modules/gesture/

## Purpose

Shake-to-wake (accelerometer). Shake-to-wake: detect a shake gesture and arm the floating tile.

**In:** accelerometer samples.
**Out:** a callback on this module's own `ShakeDetector`, not a core port
(core defines none for the shake). The app's DI wiring (`android/app`)
connects it to `overlay`'s `FloatingTile.show()`; `gesture` never imports
`overlay` (ADR-001).

**Implementation:**
- `SensorManager` accelerometer (`TYPE_ACCELEROMETER`), sensor permission required.
- Shake detector: high-pass filter on acceleration magnitude; count threshold
  crossings in a 500 ms window → fire.
- Listener registered only while idle (battery, N4).
- Fallback: if sensor unavailable/denied → always-visible tile setting.

**Build phase:** Phase 6, together with `overlay`. Needs first: `core` (on main).

## Invariants
- Shake wakes tile reliably (tune threshold on target device).
- No false wakes from normal walking (test on device).
- Sensor listener inactive while dictating (battery).

## Owns
Shake-to-wake (accelerometer).

## Public Interface
ShakeDetector

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Voice wake (phrases)
- Tile (overlay)

## Test Locations
- Unit (Kotlin): `android/modules/gesture/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:gesture:test`
- Contract: `tests/contract/test_gesture_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_gesture_contract.py`
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
- Sensor permission can be denied — always-visible tile is the fallback.
