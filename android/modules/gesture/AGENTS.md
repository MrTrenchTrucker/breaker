# AGENTS.md - android/modules/gesture/

## Purpose

Shake-to-wake (accelerometer): detect a shake gesture. A detection begins a take; the app decides what a detection does.

**In:** accelerometer samples.
**Out:** a callback on this module's own `ShakeDetector`, not a core port
(core defines none for the shake). The app's DI wiring (`android/app`)
begins a take on each detection; `gesture` never imports `overlay` (ADR-001).

**Implementation:**
- `SensorManager` accelerometer (`TYPE_ACCELEROMETER`), no permission needed at the used rate (see rate note).
- Shake detector: high-pass filter on acceleration magnitude; count threshold
  crossings in a 500 ms window -> fire.
- Listener armed by app intent - the app arms the detector when a shake should mean something; while a take runs it ignores detections (a battery reason keeps the detector quiet).
- Fallback: if the sensor is unavailable -> always-visible tile setting.

- Rate: SENSOR_DELAY_GAME = 20,000 microseconds per sample = 50 per second. No permission needed at that rate (HIGH_SAMPLING_RATE_SENSORS is only for rates above 200 Hz on API 31+).

**Build phase:** Phase 6, together with `overlay`. Needs first: `core` (on main).

## Invariants
- A detection begins a take reliably (tune threshold on target device).
- No false wakes from normal walking (test on device).
- A shake while a take runs is ignored by the app (the detector fires; the app decides - a battery reason keeps it quiet).

## Owns
Shake-to-wake (accelerometer).

## Public Interface
ShakeHandle - the public entry: `ShakeHandle.create(onShake, port: ShakePort)` wires a port to the detector (the SensorManager-backed port is `SensorManagerShakeSource`; tests pass a fake port). Start/stop on the main thread; a start the port refuses reports false and stays retryable. The detector itself is module-internal, reached only through the handle.

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
- Device check (not covered by unit tests): a real shake fires once; screen-off delivery works; events arrive on the main looper (no Handler registered).
