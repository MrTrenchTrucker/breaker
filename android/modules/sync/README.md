# sync — README

Keeps every transcription in step with the server (F13). Dictation made offline waits in a queue on the phone and is sent when the server is reachable, even in local-only mode, so nothing is lost. Sync stays off in release builds until Phase 19 (encryption by default).

## How it works

- `QueuedSyncService` implements `core.SyncService`. Build it once per process with `QueuedSyncService.create(context, uploader, probe)`. It also has `pendingCount()` and `close()`.
- `enqueue` stores one row in a private SQLite file on the phone and returns once it is committed. The row holds the transcription id, the text, the source (`local` or `server`), the model, the duration and the time the transcription was made. Audio is never stored here. Queuing the same transcription id twice keeps the first copy.
- `pushPending` asks the `ConnectivityProbe` first. When the server is not reachable it does nothing. Otherwise it sends the rows queued when the drain started, oldest first, in the order they were queued, and returns a `SyncReport`. A row queued during the drain waits for the next drain. Only one drain runs at a time: a second call made meanwhile returns `SyncReport(0, 0)`.
- Each push is built by `SyncBodyCodec.plainPush` at `key_version` 0. The `encrypted` field holds the body as padded base64 with no line breaks and no cryptography.
- The uploader answers `CONFIRMED` (the row is removed), `UNREACHABLE` (the drain stops quietly) or `FAILED` (the drain stops and counts one failure). A row is removed only after `CONFIRMED`.
- The uploader reports a refusal about the row itself by throwing `PermanentRefusalException(status, reason)`. Only a 400 or 413 is permanent (ADR-038): the module parks the row. It stays in the queue file with its reason (cut to 200 characters), is never retried and never removed, the drain goes on to the next row, and `SyncReport.parked` counts it in that drain. Any other status, or any other exception, is `FAILED` as before. A 409 is not covered by ADR-038 and still stops the drain at its row. `pendingCount()` leaves parked rows out; `parkedCount()` and `parkedReason(clientId)` read them. Saving a parked id again unparks and replaces the row and keeps its place in the queue.
- A row the module cannot push is skipped: its stored source is not `local` or `server`, its client id is not a lowercase canonical UUID, or the codec refuses its body. The row stays queued, the drain goes on to the next row, and the drain counts the row in `failed`.
- Each push carries a `client_id` (the transcription id) and a `key_version` of 0. The server dedupes on that pair (ADR-006), so a retry never duplicates.

## What the app supplies

The module has no HTTP client. The app supplies the `SyncUploader` and the `ConnectivityProbe`. A class that implements `SyncUploader` also needs api-contracts on its own classpath (the app declares it when it wires). The uploader sends the `SyncPush` it is given to `POST /v1/sync` with the user's token; that transport is not built here. The wire types come from api-contracts (ADR-017).

## Not built yet

- The background worker that calls `pushPending`.
- The HTTP uploader for `POST /v1/sync`.
- Sending tombstones for deleted records.
- The `key_version` 1 form, the `key_version` replace, the re-key, and fetching pending sealed agent-token results (ADR-018).
- PLANNED (ADR-035, not built): the pull after each drain, with a changes-since cursor (the `next` of the last complete pull, sent back as `since`), the list-again rule for a 410, the removal of local rows the server dropped after a full list, and the hand-off of pulled rows and pulled deletes to history. A local delete goes by the DELETE route, and a save after the phone's own delete carries `resave` on its first push.

## Build and test

- Unit tests run on the JVM: `./gradlew :android:modules:sync:test`.
- The Android SQLite adapter, `SqliteSyncQueueDatabase`, cannot run on a JVM, so a test reads its source text and checks it against `SyncQueueSql`.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
