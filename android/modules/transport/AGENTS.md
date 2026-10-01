# AGENTS.md — android/modules/transport/

## Purpose

Whether the Local Server is reachable (TCP connect, 1.5 s timeout, 30 s TTL cache); it probes only the configured Local Server, so there is no cloud path to fall through to.
Core's `DictateUseCase` asks this probe and picks the engine itself
(**server-primary**); this module only answers the question and **never
contacts any other host** (Security Review fix #1).

**Build phase:** Phase 5, together with `stt-server`. Needs first: `core` (on main).

## Owns
Whether the Local Server is reachable (TCP connect, 1.5 s timeout, 30 s TTL cache); it probes only the configured Local Server, so there is no cloud path to fall through to.

## Public Interface
`TcpConnectivityProbe`, which implements `core.ConnectivityProbe`
(`isServerReachable(): Boolean`), the only port this module implements.
`android/app` constructs it as `TcpConnectivityProbe(serverUrlProvider, clock)`
and its DI wiring binds it into `core.DictateUseCase` (ADR-001), which makes the
routing decision itself from the probe's answer: which engine runs, the
`LOCAL_MODEL_MISSING` refusal, the server-primary fallback. This module never
imports `DictateUseCase` or a sibling module.

`TcpConnectivityProbe` also has `refresh(): Boolean`: probe now, ignoring the
30 s cache, and cache the fresh answer (for example right after the user edits
the server address). It is not on the core port, so only the holder of the
concrete probe (`android/app`) can call it.

**Public types** — this list is the module's registry line, kept in the same
order and spelling:

- `TcpConnectivityProbe`

**How core uses the answer (`AppSettings.mode`, a `core.SttMode`):**
- `AUTO` (server-primary, the default): `DictateUseCase` asks this probe once,
  before the attempt. Reachable → the server engine, then the LLM formatter;
  not reachable → the on-device engine, then the rule-based formatter. A
  server failure after that is surfaced, not retried on the phone.
- `LOCAL`: always on-device, no probe. **No model installed → clear error
  (`LOCAL_MODEL_MISSING`), no server attempt, no misleading "Set API key."**
- `SERVER`: always the server, no probe; fails loudly if unreachable.

**Audio queue (ADR-002):** met by core, not by this module. `DictateUseCase`
records the whole clip before it asks the probe, so no audio waits on a probe
and none is dropped. This module holds no audio. Never block the UI.

## Invariants
- Reachable only when the configured Local Server accepts a TCP connect within
  1.5 s; refused, timed out or no network all answer "not reachable".
- The answer is cached for 30 s and can be refreshed on demand.
- The probe never contacts any host other than the configured Local Server.
- Met and tested in core's `DictateUseCase`, listed so nobody rebuilds them
  here: server-primary picks the server when reachable and the phone when not
  (F1–F3); `LOCAL` with no model → `LOCAL_MODEL_MISSING` (fix #1 regression
  test); a probe never costs audio.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- The engines themselves (stt-*)
- Sync (sync)
- Routing between engines and the fallback decision (`core.DictateUseCase`)

## Test Locations
- Unit (Kotlin): `android/modules/transport/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:transport:test`
- Contract: `tests/contract/test_transport_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_transport_contract.py`
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
- Probe TTL-cached 30 s — never block dictation on the server.
- Speech engines: `core.SttEngine` instances are wired into
  `core.DictateUseCase` by `android/app` (ADR-001), not into this module. It
  never sees an `SttEngine` and never imports `stt-ondevice` or `stt-server`.
