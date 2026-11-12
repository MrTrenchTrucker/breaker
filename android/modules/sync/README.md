# sync — README

Keeps every transcription in step with the server (F13). Dictation made offline waits in a queue on the phone and is sent when the server is reachable, even in local-only mode, so nothing is lost. Sync stays off in release builds until Phase 19 (encryption by default).

## How it works

- `QueuedSyncService` implements `core.SyncService`. Build it once per process with `QueuedSyncService.create(context, uploader, probe, pullTransport, pullSink)`; the two pull arguments are optional. It also has `pendingCount()` and `close()`, plus `isDeleteQueued(clientId)` and `clearPullCursor()` for the app layer.
- `enqueue` stores one row in a private SQLite file on the phone and returns once it is committed. The row holds the transcription id, the text, the source (`local` or `server`), the model, the duration and the time the transcription was made. Audio is never stored here. Queuing the same transcription id twice keeps the first copy. A save of an id that is queued as a delete replaces the delete: the latest local action wins, one row, the save.
- `pushPending` asks the `ConnectivityProbe` first. When the server is not reachable it does nothing. Otherwise it sends the rows queued when the drain started, oldest first, in the order they were queued, and returns a `SyncReport`. A row queued during the drain waits for the next drain. Only one drain runs at a time: a second call made meanwhile returns `SyncReport(0, 0)`.
- With no `RecordKeys` port (the default), each push is built by `SyncBodyCodec.plainPush` at `key_version` 0. The `encrypted` field holds the body as padded base64 with no line breaks and no cryptography. With a port (an optional last argument, `recordKeys`), the port seals the body bytes at its current key version and the module sends the sealed text as it is; a port without keys stops the drain and nothing is pushed in plain form. A 409 on such a push is remembered by key version, so later drains stop at once until the port reports another version. A pulled row at `key_version` 1 or higher is opened through the port. A delete row is never sealed and never consults the port.
- The push of one row (build, send, sort the answer) is done by `PushRow`, an internal class the drain calls for each push row; it parks a row refused for good, remembers a 409 by key version and tells the drain how to go on. The drain itself keeps the counts, the removal of a confirmed row and the delete rows.
- With a port and a pull transport, each drain ends with one bounded migration pass: it lists the account's own records (without touching the stored pull cursor) and, for each live record stored below the port's current key version that is not waiting in the queue, asks the port to re-seal it and pushes the result at the same client id and the new key version. At most 50 records a pass; a record the port cannot convert is counted and left alone; a 409 is remembered with the drain's own memory. The numbers (`replaced`, `unconvertible`, `failed`) never carry an id or a text.
- With a port and a pending transport (a last optional argument, `pendingTransport`), each drain also ends with one bounded pending-sealed pass: it fetches the owner's still-sealed agent results, strictly decodes the list (only a structural fault refuses the whole list; a bad row is skipped and counted once), skips a row whose id waits in the queue, asks the port to convert each box (at most 50 a pass) and pushes the result as a new record under the row's id. A row the port cannot convert, or whose upload is refused for good, is counted and left on the server, and it is not tried again until the key version changes. The numbers (`converted`, `unconvertible`, `failed`, `skipped`) and the positions of skipped rows never carry an id or a text.
- The uploader answers `CONFIRMED` (the row is removed), `UNREACHABLE` (the drain stops quietly) or `FAILED` (the drain stops and counts one failure). A row is removed only after `CONFIRMED`, and the drain removes its own row by the `seq` it read - so a delete queued mid-drain is never removed by a save's confirmation.
- The uploader reports a refusal about the row itself by throwing `PermanentRefusalException(status, reason)`. Only a 400 or 413 is permanent (ADR-038): the module parks the row, whether it is a save or a delete - a delete answered 400 or 413 is parked like a refused save. It stays in the queue file with its reason (cut to 200 characters), is never retried and never removed, the drain goes on to the next row, and `SyncReport.parked` counts it in that drain. Any other status, or any other exception, is `FAILED` as before. A 409 is not covered by ADR-038 and still stops the drain at its row; it cannot occur today (ADR-039). `pendingCount()` leaves parked rows out; `parkedCount()` and `parkedReason(clientId)` read them. Saving a parked id again unparks and replaces the row and keeps its place in the queue.
- A row the module cannot push is skipped: its stored source is not `local` or `server`, its client id is not a lowercase canonical UUID, or the codec refuses its body. The row stays queued, the drain goes on to the next row, and the drain counts the row in `failed`.
- Each push carries a `client_id` (the transcription id) and a `key_version` of 0. The server dedupes on that pair (ADR-006), so a retry never duplicates.

## What the app supplies

The module has no HTTP client. The app supplies the `SyncUploader` and the `ConnectivityProbe`. A class that implements `SyncUploader` also needs api-contracts on its own classpath (the app declares it when it wires). The uploader sends the `SyncPush` it is given to `POST /v1/sync` with the user's token; that transport is not built here. The wire types come from api-contracts (ADR-017).

## When the drain runs

The drain runs only while the app process is alive: the scheduler and its backoff timer live in memory, and nothing is in flight when the process dies. The queue is durable in SQLite, so nothing is lost - a saved transcription waits there until the next drain. When the enqueue throws, the text is saved and the row is not queued, and nothing queues it later.

## Not built yet

- Sending tombstones for deleted records.
- The real port adapter in the app, the app's pending transport and the wiring flip (ADR-018). The own-record migration and re-key pass and the sync half of the pending-sealed conversion are built (see How it works).
- WIRED (ADR-035): the pull. `QueuedSyncService` builds a `PullTrigger` when the app gives it both a
  `PullTransport` and a `PullSink` (the app's `wiring/PullChainWiring.kt` builds `PullHttpTransport`),
  and `pushPending` runs one pull cycle after a finished drain, under the same drain mutex. PullRun
  (sync/PullRun.kt, #441) runs one pull (a 410 lists again once); PullSink (core/port/PullSink.kt, #442)
  is the port history implements, with applyRemoteRow (#444), applyRemoteDelete (#442) and retainOnly
  (#445) in history/SqliteHistoryStore.kt; the pull cursor store (#446) and isQueued/queuedIds (#447) in
  sync/SyncQueueDatabase.kt; PullApplier (sync/PullApplier.kt, #448); PullCycle (sync/PullCycle.kt,
  #450), which reads the stored cursor, pulls, applies, then saves the cursor, and pulls once more after
  a full list. A failing pull never crashes the worker and never saves a cursor; the next drain retries.
  A local delete goes by the DELETE route, and a save after the phone's own delete carries `resave` on
  its first push.

## Build and test

- Unit tests run on the JVM: `./gradlew :android:modules:sync:test`.
- The Android SQLite adapter, `SqliteSyncQueueDatabase`, cannot run on a JVM, so a test reads its source text and checks it against `SyncQueueSql`.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
