# AGENTS.md — android/modules/sync/

## Purpose

Offline-first sync queue, idempotent push (F13, N12). Offline-first sync queue (F13). Every transcription syncs to the server when reachable - even local-only mode - with no data loss on offline dictation.

**Build phase:** Phase 11, development only. Every push is built at `key_version` 0: the body is the R1 JSON object, base64 encoded, with no cryptography. The `key_version` 1 form, the `key_version` replace, the re-key and the pending-sealed fetch are not built. Needs first: `core` (on main) and the server's `sync-api`.

## Owns

The offline-first sync queue on the phone, and the idempotent push (F13, N12) of its rows at `key_version` 0.

## Public Interface

- `QueuedSyncService` implements `core.SyncService` (`enqueue`, `pushPending`). Build it with `QueuedSyncService.create(context, uploader, probe, pullTransport, pullSink)`; the two pull arguments are optional and default to null. `pendingCount()`, `parkedCount()`, `parkedReason(clientId)` and `close()` are public too. `pendingCount()` leaves parked rows out. When both pull halves are given, `pullTrigger` is non-null and `pushPending` runs one pull cycle after a finished drain, under the same drain mutex; `enqueue` never runs a pull.
- `SyncUploader`: `fun upload(push: SyncPush): UploadOutcome`. `UploadOutcome` has `CONFIRMED`, `UNREACHABLE` and `FAILED`, in that order. An uploader reports a refusal about the row itself by throwing `PermanentRefusalException(status, reason)`; only status 400 and 413 park the row (ADR-038).
- `PullTransport`: `fun fetch(request: PullRequest): PullTransportResult`, the seam the app implements for the pull (the app's `wiring/PullChainWiring.kt` builds `PullHttpTransport`). `PullTransportResult` is `Answered(status, body)` for any HTTP answer, the status and the body text exactly as received, or `Unreachable` when the server could not be reached; an implementation never throws for those. `PullRequest` is public only for its `path()` and `query()`; its factories stay internal, so the app cannot build one.
- `PullTrigger`: a public `fun interface` with `run()`, the never-throwing seam the app reads from `QueuedSyncService.pullTrigger`. `QueuedSyncService` builds the cycle over its own database (the cursor store and the queue probe) and the app's `PullTransport` and `PullSink`; `run()` swallows every exception, so a failing pull changes neither the stored cursor nor the drain's report, and the next drain retries.
- The push and the body types come from api-contracts: `dev.breaker.shared.api.SyncPush` and `dev.breaker.shared.api.sync` (`SyncBody`, `SyncBodyCodec`, `SyncBodyException`, `SyncSource`). The module calls `SyncBodyCodec` and never writes the wire form by hand (ADR-017).

**Queue:**
- `enqueue` stores one row: the transcription id as `client_id`, the text, the source as its wire text (`local` or `server`), the model, the duration and `created_at` (epoch milliseconds, when the transcription was made). Audio is never queued.
- `pushPending` asks the `ConnectivityProbe` first. When the server is reachable it sends the rows that were queued when the drain started, oldest first, by `seq`, and returns a `SyncReport`. A row queued during the drain waits for the next drain. Only one drain runs at a time.
- Every push is `SyncBodyCodec.plainPush(clientId, body)` at `key_version` 0.

**Uploader:** the app supplies it. A class that implements `SyncUploader` also needs api-contracts on its own classpath (the app declares it when it wires). It sends the `SyncPush` to `POST /v1/sync` with the user's token and answers `CONFIRMED`, `UNREACHABLE` or `FAILED`, or throws `PermanentRefusalException` for a 400 or 413. This module has no HTTP client.

**Pull transport and loop:** `PullTransport` is the public seam the app implements (answered or unreachable, never throws for those; see Public Interface) and `PullLoop.run` (internal) drives page after page with six outcomes: `Ok`, `BadCursor`, `Gone` (a 410), `Other`, `Unreachable` and `Refused`. No-progress (next equals the cursor just sent, a page with more true holds no records, or, when both the cursor sent and next are incremental cursors of two digit parts, next names another account or its change number is not past the cursor's) and the page cap of 1000 each end as Refused. A listing cursor and any text that is not two digit parts keep the equality check only. Nothing is stored and nothing is handed to history. The pull trigger reaches it after each drain.

**Pull run (internal):** `PullRun.run` applies the list-again-once rule: a 400 or a 410 on a stored cursor drops it and lists again without since, once, and a second refusal ends as `Refused`; with no stored cursor a refusal ends as `Refused` at once. `fullList` is true only for a pull that listed from scratch, and a refused first attempt leaves none of its records in the result. It stores nothing and hands nothing to history. The pull trigger reaches it after each drain.

**Pull page decoder (internal):** `PullPageDecoder.decode` turns the text of one pull page into the shared `TranscriptionPage` or refuses it with `PullPageException`; it uses its own small strict JSON reader (`PullPageJson`, nesting deeper than 8 refused). A live record needs `client_id` (a lowercase canonical UUID), `change` (an integer, at least 1), `deleted`, `stored_at`, `key_version` and `encrypted`; a delete record needs the first four and must carry neither `key_version` nor `encrypted`. It does not parse or check the cursor `next` (a non-empty text, returned unchanged), and a refusal message never contains text from the body. `stored_at` must be a non-empty text and its form is not checked there; `PullApplier` parses the date from `stored_at` for a delete record. The pull trigger reaches it after each drain.

**Pull request (internal):** `PullRequest` describes one pull call: the fixed path `/v1/transcriptions` and the optional `since`. `PullRequest.first()` has none; `PullRequest.after(cursor)` keeps the stored cursor text exactly (no trim, no case change, no check of its form) and `query()` writes it percent-encoded (UTF-8 bytes; only A-Z a-z 0-9 - . _ ~ are left alone, a space is `%20`); an empty cursor is refused with `IllegalArgumentException`. `PullStatus.of(status)` reads 200 as `OK`, 400 as `BAD_CURSOR` and every other status as `OTHER`. The pull trigger reaches them after each drain.

**Pulled row body (internal):** `PulledRowBody.read(record)` returns the shared `SyncBody` of one live pulled record at key_version 0 (the codec's `decodePlain` on its `encrypted` text, unchanged). It refuses a delete record, a missing key_version, a key_version other than 0 (the message holds the number; nothing is decrypted) and a missing `encrypted` text with `PulledRowException`; a body the codec refuses becomes a `PulledRowException` whose message is the fixed words plus the codec's family-only message, with the codec's exception as the cause. No message holds text from the record or its body. The pull trigger reaches it after each drain.

**Pull cursor (internal):** the storage keeps one opaque text, the cursor of the last complete pull, in its own table next to the queue: `pullCursor()` reads it, `savePullCursor(cursor)` stores it and a second save replaces it, and `clearPullCursor()` removes it. All three are on `SyncQueueDatabase`; the storage never judges or parses the text, and the pull trigger reaches it after each drain.

**Queue reads (internal):** `isQueued(clientId)` and `queuedIds()` are reads on `SyncQueueDatabase`: whether a client id is waiting in the queue and the ids of every queue row, parked rows included, oldest first. The pull merge uses them (a queued id keeps the local edit; the prune keeps the queued ids); the pull trigger reaches them after each drain.

**Pull applier (internal):** `PullApplier.apply(result)` turns the result of one pull into calls on the core `PullSink`: for a delete record, `deleted_at` is parsed from `stored_at` with `SyncBodyCodec.parseCreatedAt` (exactly three fraction digits); a stored_at the codec refuses counts `unreadable` and calls nothing; an accepted delete goes to `applyRemoteDelete`. A live row whose id waits in the queue (`QueueProbe`) is skipped; any other live row is read and handed to `applyRemoteRow`; after a finished full list `retainOnly` keeps the list ids and the queued ids. It reads nothing from the network, stores nothing, writes no cursor and starts no pull; the pull trigger reaches it after each drain.

**Pull cycle (internal):** `PullCycle.run` runs one pull in order: the stored cursor is read, `PullRun.run` pulls, `PullApplier.apply` applies, and only then the cursor is saved; after a finished full list one more pull runs from the list's cursor; a second refusal of the stored cursor and of the list clears the stored cursor; at most two pull runs; the pull trigger runs it after each drain.

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
- Known limits of the pull (sync is off in release builds, so none of these runs there; each is to be fixed before sync is turned on):
  - The pull runs inside the drain lock, after the drain loop (`QueuedSyncService.pushPending`). A push that arrives during a pull
    returns at once with nothing sent, so a long full list (up to `PullLoop.MAX_PAGES` pages) skips pushes until it ends. One hung
    read ends the pull as unreachable after the 30 s read timeout.
  - The app's `SyncingHistoryStore.save` commits the save and then enqueues it: two steps. A pull that applies a row with the same id
    between them finds no queued row (`isQueued` is false) and replaces the fresh save.
  - `PullLoop.run` keeps the records of every page in memory and nothing is applied until the last page has arrived. A refusal or an
    unreachable server on page N drops pages 1 to N-1, and the next pull starts again.

## Not Built Yet
- Tombstones for deleted records (a separate sync path, not in the body).
- Everything at `key_version` 1 or higher: the authenticated encryption form, the `key_version` replace, the re-key, and fetching pending sealed agent-token results (ADR-018, Phases 19 and 20).
- ADR-035, wired: the pull. PullRun (PullRun.kt), PullApplier (PullApplier.kt), PullCycle (PullCycle.kt), the pull cursor store and isQueued/queuedIds (SyncQueueDatabase.kt) are in this module, and history implements the PullSink port (core/port/PullSink.kt). `QueuedSyncService` builds a `PullTrigger` over them when the app gives it both a `PullTransport` and a `PullSink`, and `pushPending` runs one pull cycle after a finished drain, under the same drain mutex; the app's `wiring/PullChainWiring.kt` builds the pull transport. A failing pull never crashes the worker and never saves a cursor; the next drain retries. The pull as decided: after each drain of the push queue the module asks the server for the rows changed since its last complete pull. It keeps the cursor `next` of that pull and sends it back as `since`, and it never builds or parses a cursor itself. The cursor is one opaque string in the module's own SQLite file next to the push queue. It moves forward only when a whole pull ended (`more` is false), so an interrupted pull starts again from the stored cursor; it is cleared with the queue on sign-out or account change. The first pull sends no `since`. It repeats the call with `since` set to `next` while the answer says `more` is true, and hands the pulled rows to history. A row is matched by its client id, so a row this device pushed is not stored twice. A pulled live row with the same client id: a local tombstone of any reason keeps the row from being stored and the tombstone stays; an id waiting unsent in the push queue keeps the local edit and the pulled row is skipped; otherwise the row is stored, one row per id. After a full list (a call with no `since`) the module removes the local rows that are not in the answer and are not waiting in its own push queue, so a phone that was revoked and signed in again does not keep rows the server dropped. A 410 answer means the cursor is too old, and a 400 answer means the stored cursor is damaged or of another account: the module drops the stored cursor and lists again without `since`, once per pull; a second refusal stops the pull and counts one failure, no loop. On the server, the push route `POST /v1/sync` and the pull route `GET /v1/transcriptions` are built, and so are the change numbers on the rows (migration 5). The pull route takes `since`, answers a page object with `items`, `next` and `more` (at most 100 records and 4 MiB a page), and answers 410 when `since` is older than the account's purged point. A call with no `since` returns the first page of the live rows.
- ADR-035, wired: deletes. PullApplier (PullApplier.kt) hands every pulled delete record to history's applyRemoteDelete and skips a queued id itself through isQueued (SyncQueueDatabase.kt); the pull trigger reaches it after each drain, so a delete made on another device arrives while sync is on. A delete made on another device arrives as a delete record in the same feed as the rows, and reaches history's `applyRemoteDelete`. A remote delete is applied even if this device saved the same id again. A local delete is sent by the `DELETE /v1/transcriptions/{client_id}` route. A queued delete is never refused for good, because an unknown id also answers 204. A local save of an id this phone itself deleted carries the boolean `resave` on that first push only, and never on a retry, so the server knows it is a fresh save and not a stale retry. A pulled delete record always goes to the remote delete call, even if this device saved the id again; the remote tombstone is held across a local re-save. This is not the local rule: a local delete then a local save clears the local tombstone.

