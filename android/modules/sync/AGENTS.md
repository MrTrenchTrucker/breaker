# AGENTS.md — android/modules/sync/

## Purpose

Offline-first sync queue, idempotent push (F13, N12). Offline-first sync queue (F13). Every transcription syncs to the server when reachable - even local-only mode - with no data loss on offline dictation.

**Build phase:** Phase 11, development only. Every push is built at `key_version` 0: the body is the R1 JSON object, base64 encoded, with no cryptography. The `key_version` 1 form, the `key_version` replace, the re-key and the pending-sealed fetch are not built. Needs first: `core` (on main) and the server's `sync-api`.

## Owns

The offline-first sync queue on the phone, and the idempotent push (F13, N12) of its rows at `key_version` 0.

## Public Interface

- `QueuedSyncService` implements `core.SyncService` (`enqueue`, `pushPending`). Build it with `QueuedSyncService.create(context, uploader, probe)`. `pendingCount()` and `close()` are public too.
- `SyncUploader`: `fun upload(push: SyncPush): UploadOutcome`. `UploadOutcome` has `CONFIRMED`, `UNREACHABLE` and `FAILED`, in that order.
- The push and the body types come from api-contracts: `dev.breaker.shared.api.SyncPush` and `dev.breaker.shared.api.sync` (`SyncBody`, `SyncBodyCodec`, `SyncBodyException`, `SyncSource`). The module calls `SyncBodyCodec` and never writes the wire form by hand (ADR-017).

**Queue:**
- `enqueue` stores one row: the transcription id as `client_id`, the text, the source as its wire text (`local` or `server`), the model, the duration and `created_at` (epoch milliseconds, when the transcription was made). Audio is never queued.
- `pushPending` asks the `ConnectivityProbe` first. When the server is reachable it sends the rows that were queued when the drain started, oldest first, by `seq`, and returns a `SyncReport`. A row queued during the drain waits for the next drain. Only one drain runs at a time.
- Every push is `SyncBodyCodec.plainPush(clientId, body)` at `key_version` 0.

**Uploader:** the app supplies it. A class that implements `SyncUploader` also needs api-contracts on its own classpath (the app declares it when it wires). It sends the `SyncPush` to `POST /v1/sync` with the user's token and answers `CONFIRMED`, `UNREACHABLE` or `FAILED`. This module has no HTTP client.

## Invariants
- Offline dictation, queued by `enqueue`, then pushed by `pushPending` on reconnect, then counted in that call's `SyncReport`; nothing is dropped.
- `enqueue` returns once the row is committed. A second enqueue of the same transcription id keeps the first copy.
- Every push is built by `SyncBodyCodec.plainPush` at `key_version` 0. No other key version is built or sent.
- A row is removed only after the uploader answers `CONFIRMED`.
- A drain walks only the rows queued when it started. The highest seq is read once, after the probe answers. A row queued during the drain, including one queued by the uploader, waits for the next drain, so one drain always ends.
- A row the drain cannot push is skipped: its stored source is not `local` or `server`, its client id is not a lowercase canonical UUID, or the codec refuses its body. A skipped row stays queued, the drain goes on to the next row, and each drain that reaches the row counts it in `SyncReport.failed`. A skipped row is never removed.
- `FAILED`, and an uploader that throws, stop the drain at that row and count one failure. `UNREACHABLE` stops the drain and counts no failure. A server that is not reachable gives `SyncReport(0, 0)` and no walk over the rows.
- One drain runs at a time (`Mutex.tryLock`). A call made meanwhile returns `SyncReport(0, 0)` at once.
- A retry sends the same client id at the same key version, so the server dedupes the pair (N12, ADR-006).
- No sync traffic except to Local Server (N2).
- Sync is off in release builds until Phase 19 (encryption by default): no release build turns it on and no real user's data is synced before then (`docs/04-build-order.md`, "Gates and releases").

## Depends On
- android
- android_core
- shared_api_contracts

## Does Not Own
- Encryption (crypto)
- Auth (auth-client), including the user token
- History storage (history)
- The body codec, which is api-contracts
- The HTTP call to `POST /v1/sync`, which the app's uploader makes

## Test Locations
- Unit (Kotlin): `android/modules/sync/src/test/kotlin/dev/breaker/dictation/sync/`. Run: `./gradlew :android:modules:sync:test`
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
- The queue file holds the text in clear on the device. The manifest sets `android:allowBackup="false"` and the backup rules exclude every domain, so a backup does not restore the queue (ADR-028).
- At `key_version` 0 the body is not encrypted, so the server can read the text. This is the development form. It is replaced when the `key_version` 1 form is built.
- A row the drain skips repeats in `SyncReport.failed` on every drain that reaches it. It stays queued until a later permanent-refusal path exists.
- A row the server refuses for good returns `FAILED` on every push and blocks the rows behind it until a later permanent-refusal path exists.
- The client id must be a lowercase canonical UUID. The app's id source (`UuidIdSource`) mints one. A transcription with any other id is skipped on every drain.
- The Android SQLite adapter, `SqliteSyncQueueDatabase`, cannot run on a JVM. Its tests read its source text and check it against `SyncQueueSql`.
- The module imports no auth or crypto module. Modules talk through core ports or the app's wiring (ADR-001). The app supplies the uploader, which carries the user token.

## Not Built Yet
- The HTTP uploader for `POST /v1/sync`: the token, and the 204, 401, 403 and 409 answers.
- The background worker that calls `pushPending`.
- Telling a permanent server refusal from a transient failure.
- Tombstones for deleted records (a separate sync path, not in the body).
- Everything at `key_version` 1 or higher: the authenticated encryption form, the `key_version` replace, the re-key, and fetching pending sealed agent-token results (ADR-018, Phases 19 and 20).
- Wiring the module into `android/app`.
- PLANNED, ADR-035, not built: the pull. After each drain the module asks the server for the rows changed since its last pull, keeps a changes-since cursor (the server's change number, sent back as `since`), and hands the pulled rows to history. A row is matched by its client id, so a row this device pushed is not stored twice. The server has the push route `POST /v1/sync` and a plain pull route, `GET /v1/transcriptions`, which returns the account's whole list as one JSON array, oldest first, and reads no query parameter and no body. The server has no change number, no `since` parameter and no delete flag yet, so the changes-since cursor is not built.
- PLANNED, ADR-035, not built: the pulled deletes. A delete made on another device arrives as a flag on the row in the same change feed as the rows, and reaches history's `applyRemoteDelete`. A remote delete is applied even if this device saved the same id again.

