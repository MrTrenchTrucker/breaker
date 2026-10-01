# AGENTS.md — android/modules/history/

## Purpose

SQLite transcription history, 3-month TTL cleanup, tombstones. Persistent transcription history for easy re-copying.

## Owns
SQLite transcription history, 3-month TTL cleanup, tombstones.

## Public Interface `core.HistoryStore`.

**Storage:** SQLite via Room. Table `transcriptions`:
`id, text, source (local|server), model, duration_ms, created_at, audio_path (nullable, off by default)`.

**UI:** history screen — list, tap-to-copy, long-press delete, search.
App-private storage; optional encryption at rest (T7).

**Retention (F28):** local cleanup job deletes transcriptions older than
**3 months** (TTL). Deletes — local or from the web FE — propagate via
**tombstones** through sync so phone and server stay consistent.

## Invariants
- Every transcription saved regardless of source (F6).
- Tap-to-copy works from history; delete works.
- DB in app-private dir; no world-readable permissions.
- TTL purge removes rows older than 3 months; tombstones sync deletes.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Sync transport (sync)
- Encryption (crypto)

## Test Locations
- Unit: `tests/unit/android/history/`
- Contract: `tests/contract/test_history_contract.py`

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

## Known Gotchas
- 3-month TTL + tombstones must stay consistent with server retention (F28).
