# AGENTS.md — android/modules/sync/

## Purpose

Offline-first sync queue, idempotent push (F13, N12). Offline-first sync queue (F13). Every transcription syncs to the server when reachable - even local-only mode - with no data loss on offline dictation.

**Build phase:** Phase 11, development only. Every push is built at `key_version` 0: the body is the R1 JSON object, base64 encoded, with no cryptography. The `key_version` 1 form, the `key_version` replace, the re-key and the pending-sealed fetch are not built. Needs first: `core` (on main) and the server's `sync-api`.

## Owns

The offline-first sync queue on the phone, and the idempotent push (F13, N12) of its rows at `key_version` 0.

## Public Interface

- `QueuedSyncService` implements `core.SyncService` (`enqueue`, `pushPending`). Build it with `QueuedSyncService.create(context, uploader, probe)`. `pendingCount()`, `parkedCount()`, `parkedReason(clientId)` and `close()` are public too. `pendingCount()` leaves parked rows out.
- `SyncUploader`: `fun upload(push: SyncPush): UploadOutcome`. `UploadOutcome` has `CONFIRMED`, `UNREACHABLE` and `FAILED`, in that order. An uploader reports a refusal about the row itself by throwing `PermanentRefusalException(status, reason)`; only status 400 and 413 park the row (ADR-038).
- The push and the body types come from api-contracts: `dev.breaker.shared.api.SyncPush` and `dev.breaker.shared.api.sync` (`SyncBody`, `SyncBodyCodec`, `SyncBodyException`, `SyncSource`). The module calls `SyncBodyCodec` and never writes the wire form by hand (ADR-017).

**Queue:**
- `enqueue` stores one row: the transcription id as `client_id`, the text, the source as its wire text (`local` or `server`), the model, the duration and `created_at` (epoch milliseconds, when the transcription was made). Audio is never queued.
- `pushPending` asks the `ConnectivityProbe` first. When the server is reachable it sends the rows that were queued when the drain started, oldest first, by `seq`, and returns a `SyncReport`. A row queued during the drain waits for the next drain. Only one drain runs at a time.
- Every push is `SyncBodyCodec.plainPush(clientId, body)` at `key_version` 0.

**Uploader:** the app supplies it. A class that implements `SyncUploader` also needs api-contracts on its own classpath (the app declares it when it wires). It sends the `SyncPush` to `POST /v1/sync` with the user's token and answers `CONFIRMED`, `UNREACHABLE` or `FAILED`, or throws `PermanentRefusalException` for a 400 or 413. This module has no HTTP client.

**Pull transport and loop (internal):** `PullTransport` is the seam the app is to implement (answered or unreachable, never throws for those) and `PullLoop.run` drives page after page with five outcomes: `Ok`, `BadCursor`, `Other`, `Unreachable` and `Refused`. No-progress (next equals the cursor just sent, or a page with more true holds no records) and the page cap of 1000 each end as Refused. 410 is Other for now. Nothing is stored and nothing is handed to history. Nothing calls it yet.

**Pull page decoder (internal):** `PullPageDecoder.decode` turns the text of one pull page into the shared `TranscriptionPage` or refuses it with `PullPageException`; it uses its own small strict JSON reader (`PullPageJson`, nesting deeper than 8 refused). A live record needs `client_id` (a lowercase canonical UUID), `change` (an integer, at least 1), `deleted`, `stored_at`, `key_version` and `encrypted`; a delete record needs the first four and must carry neither `key_version` nor `encrypted`. It does not parse or check the cursor `next` (a non-empty text, returned unchanged), and a refusal message never contains text from the body. Nothing calls it yet.

**Pull request (internal):** `PullRequest` describes one pull call: the fixed path `/v1/transcriptions` and the optional `since`. `PullRequest.first()` has none; `PullRequest.after(cursor)` keeps the stored cursor text exactly (no trim, no case change, no check of its form) and `query()` writes it percent-encoded (UTF-8 bytes; only A-Z a-z 0-9 - . _ ~ are left alone, a space is `%20`); an empty cursor is refused with `IllegalArgumentException`. `PullStatus.of(status)` reads 200 as `OK`, 400 as `BAD_CURSOR` and every other status as `OTHER`. Nothing calls them yet.

**Pulled row body (internal):** `PulledRowBody.read(record)` returns the shared `SyncBody` of one live pulled record at key_version 0 (the codec's `decodePlain` on its `encrypted` text, unchanged). It refuses a delete record, a missing key_version, a key_version other than 0 (the message holds the number; nothing is decrypted) and a missing `encrypted` text with `PulledRowException`; a body the codec refuses becomes a `PulledRowException` whose message is the fixed words plus the codec's family-only message, with the codec's exception as the cause. No message holds text from the record or its body. Nothing calls it yet.

## Invariants
- Offline dictation, queued by `enqueue`, then pushed by `pushPending` on reconnect, then counted in that call's `SyncReport`; nothing is dropped.
- `enqueue` returns once the row is committed. A second enqueue of the same transcription id keeps the first copy.
- Every push is built by `SyncBodyCodec.plainPush` at `key_version` 0. No other key version is built or sent.
- A row is removed only after the uploader answers `CONFIRMED`.
- A drain walks only the rows queued when it started. The highest seq is read once, after the probe answers. A row queued during the drain, including one queued by the uploader, waits for the next drain, so one drain always ends.
- A row the drain cannot push is skipped: its stored source is not `local` or `server`, its client id is not a lowercase canonical UUID, or the codec refuses its body. A skipped row stays queued, the drain goes on to the next row, and each drain that reaches the row counts it in `SyncReport.failed`. A skipped row is never removed.
- A `PermanentRefusalException` with status 400 or 413 parks the row: it stays in the store, marked with its reason (at most 200 characters, never row text), is never retried and never removed, the drain goes on to the next row, and `SyncReport.parked` counts it in that drain (the total is `parkedCount()`). `head`, `nextAfter` and `pendingCount` leave parked rows out. Saving a parked id again unparks and replaces the row in one transaction and keeps its seq; saving a live id again keeps the first copy.
- `FAILED`, and an uploader that throws any other exception (or a `PermanentRefusalException` with another status), stop the drain at that row and count one failure. `UNREACHABLE` stops the drain and counts no failure. A server that is not reachable gives `SyncReport(0, 0)` and no walk over the rows.
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
- A row the drain skips repeats in `SyncReport.failed` on every drain that reaches it.
- A 409 is not covered by ADR-038: it is `FAILED` and stops the drain at its row. It cannot occur today (no client calls the keys route, so no account is above key_version 0); ADR-039 holds the options for the day it can.
- A parked row is not retried and does not go away by itself. A delete removes it (`remove` is not filtered); a retry action is a later feature.
- The client id must be a lowercase canonical UUID. The app's id source (`UuidIdSource`) mints one. A transcription with any other id is skipped on every drain.
- The Android SQLite adapter, `SqliteSyncQueueDatabase`, cannot run on a JVM. Its tests read its source text and check it against `SyncQueueSql`.
- The module imports no auth or crypto module. Modules talk through core ports or the app's wiring (ADR-001). The app supplies the uploader, which carries the user token.

## Not Built Yet
- The HTTP uploader for `POST /v1/sync`: the token, and the 204, 401, 403 and 409 answers.
- The background worker that calls `pushPending`.
- Tombstones for deleted records (a separate sync path, not in the body).
- Everything at `key_version` 1 or higher: the authenticated encryption form, the `key_version` replace, the re-key, and fetching pending sealed agent-token results (ADR-018, Phases 19 and 20).
- Wiring the module into `android/app`.
- PLANNED, ADR-035, not built (nothing in this module does this yet): the pull. After each drain of the push queue the module asks the server for the rows changed since its last complete pull. It keeps the cursor `next` of that pull and sends it back as `since`, and it never builds or parses a cursor itself. The cursor is one opaque string in the module's own SQLite file next to the push queue. It moves forward only when a whole pull ended (`more` is false), so an interrupted pull starts again from the stored cursor; it is cleared with the queue on sign-out or account change. The first pull sends no `since`. It repeats the call with `since` set to `next` while the answer says `more` is true, and hands the pulled rows to history. A row is matched by its client id, so a row this device pushed is not stored twice. A pulled live row with the same client id: a local tombstone of any reason keeps the row from being stored and the tombstone stays; an id waiting unsent in the push queue keeps the local edit and the pulled row is skipped; otherwise the row is stored, one row per id. After a full list (a call with no `since`) the module removes the local rows that are not in the answer and are not waiting in its own push queue, so a phone that was revoked and signed in again does not keep rows the server dropped. A 410 answer means the cursor is too old, and a 400 answer means the stored cursor is damaged or of another account: the module drops the stored cursor and lists again without `since`, once per pull; a second refusal stops the pull and counts one failure, no loop. On the server, the push route `POST /v1/sync` and the pull route `GET /v1/transcriptions` are built, and so are the change numbers on the rows (migration 5). The pull route takes `since`, answers a page object with `items`, `next` and `more` (at most 100 records and 4 MiB a page), and answers 410 when `since` is older than the account's purged point. A call with no `since` returns the first page of the live rows.
- PLANNED, ADR-035, not built (nothing in this module does this yet): deletes. A delete made on another device arrives as a delete record in the same feed as the rows, and reaches history's `applyRemoteDelete`. A remote delete is applied even if this device saved the same id again. A local delete is sent by the `DELETE /v1/transcriptions/{client_id}` route. A queued delete is never refused for good, because an unknown id also answers 204. A local save of an id this phone itself deleted carries the boolean `resave` on that first push only, and never on a retry, so the server knows it is a fresh save and not a stale retry. A pulled delete record always goes to the remote delete call, even if this device saved the id again; the remote tombstone is held across a local re-save. This is not the local rule: a local delete then a local save clears the local tombstone.

