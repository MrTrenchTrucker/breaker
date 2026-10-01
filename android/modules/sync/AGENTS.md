# AGENTS.md — android/modules/sync/

## Purpose

Offline-first sync queue, idempotent push (F13, N12). Offline-first sync queue (F13). Every transcription syncs to the
server when reachable — even local-only mode — with no data loss on offline
dictation.

**Build phase:** Phase 11 (the `key_version` replace and re-key parts arrive in Phase 19, the pending-sealed fetch in Phase 20). Needs first: `core` (on main) and the server's `sync-api`.

## Owns
Offline-first sync queue, idempotent push (F13, N12), and delivery of pending
sealed agent-token results (ADR-018).

## Public Interface `core.SyncService` port.

**Queue:**
- Rows in the history SQLite store carry `sync_status = pending|synced`.
- A background worker pushes pending transcriptions to `POST /v1/sync` whenever
  the server is reachable (reuses the transport probe).
- **Idempotent (N12):** each push carries a `client_id` and the record's
  `key_version` (ADR-006); the server dedupes on that **pair**, not on
  `client_id` alone, so an ordinary retry (same pair) never duplicates, while
  a re-key's push of the same `client_id` at a **higher** `key_version`
  atomically replaces the stored record instead of being treated as a
  duplicate. (Not a content hash: a content hash would never match across
  two encrypted uploads of the "same" plaintext, because AES-256-GCM uses a
  fresh nonce each time — see `android/modules/crypto`.)

**Pending sealed results (ADR-018, F27, Phase 20):** right after each
successful login, while the unwrapped DEK and box private key are in memory,
fetch `GET /v1/sync/pending-sealed`, convert each record through
`core.CryptoService` (`unsealAndReencrypt`, which the port gains in Phase 19,
implemented by `android/modules/crypto`), and push the result through the ordinary
`POST /v1/sync` with `client_id` set to the pending record's id, at the
account's current `key_version` — a new record, not a replace. The server
deletes the pending row once that lands; if another device of the same owner
converted it first, the (client id, `key_version`) dedupe makes this push a
no-op. Like the web FE, this client cannot tell a real sealed row from a
forged one (T26).

**Re-key replacement (ADR-006, Phase 19):** after `android/modules/auth-client`
and `android/modules/crypto` complete a password change — which always
generates a new DEK and box keypair and advances `key_version` — this module
re-encrypts and replaces every one of the account's own records still below
the new `key_version`, through the same push path, at the higher version.
This resumes at the next login if it is interrupted, the same as the
Phase-11–18 plaintext migration does: login then returns `prev_wrapped_dek`,
which `android/modules/crypto` unwraps with the new DEK so the remaining
records can still be read and replaced. The not-yet-synced local queue is
re-encrypted under the new DEK before it is pushed; an item still under the
old DEK would be refused (409) as a new record below the current
`key_version`.

**Auth:** uses the user token from `auth-client`; sync is scoped to the user's
namespace server-side (N13).

## Invariants
- Offline dictation → queued by `enqueue` → pushed by `pushPending` on
  reconnect → counted in that call's `SyncReport`; nothing is dropped.
- Retry storm after reconnect doesn't duplicate (N12 test).
- No sync traffic except to Local Server (N2).
- Sync is off in release builds until Phase 19 (encryption by default): no
  release build turns it on and no real user's data is synced before then
  (`docs/04-build-order.md`, "Gates and releases").

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Encryption (crypto)
- Auth (auth-client)
- History storage (history)

## Test Locations
- Unit (Kotlin): `android/modules/sync/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:sync:test`
- Contract: `tests/contract/test_sync_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_sync_contract.py`
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
- Core's `Transcription` and `SyncService` carry no `key_version` yet, though
  the pair dedupe needs it on the wire from Phase 11 (`0` for plaintext rows).
  Adding it to core is an interface change for the project leader, never a
  field this module invents on its own.
- A record the re-key cannot decrypt keeps the re-key unfinished, and the server refuses password changes (409) until it is replaced or deleted. Surface it to the user rather than retrying forever (ADR-006).
- Idempotent (N12): (`client_id`, `key_version`) pair dedupe (not a hash) —
  retries must never duplicate, and a re-key's higher `key_version` at the
  same `client_id` must replace, never duplicate or no-op.
- The login token and encryption reach this module as `core.AuthService` and `core.CryptoService`, wired by `android/app` (ADR-001); it never imports `auth-client` or `crypto`.
