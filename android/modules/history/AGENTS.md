# AGENTS.md — android/modules/history/

## Purpose

Saves the transcriptions it is given in a private SQLite database on the phone,
so the user can find and re-copy them later. Old transcriptions expire by the
ADR-010 precise rule when `purgeExpired()` is called; the app runs that call
once at start (`BreakerApp`) through `PurgeGuard` (in the app), which skips it for at most 7 days when automatic time is off
and over 30 days have passed since the last purge, a suspected forward clock jump. Every delete of a stored row leaves a tombstone for sync to carry to
the server (see Tombstones below for what is in place).

**Build phase:** Phase 2. Needs first: `core` (on `main`). The retention rules are finished in Phase 21 (data lifecycle).

## Owns
SQLite transcription history, 30-day TTL cleanup, tombstones.

## Public Interface

- `SqliteHistoryStore` — built only via `SqliteHistoryStore.create(context, clock)`; implements `core.HistoryStore`;
  exposes `purgeExpired(): PurgeReport` and `sweepExpiredTombstones(): Int`.
- `PurgeReport` — the report `purgeExpired()` returns.

**Internal (not public):** `RetentionPolicy`, `Tombstone`, `TombstonePurgeReport`,
`HistorySql`, `RetentionBoundary`, `AppPrivateStorage`, `HistoryDatabase`,
`SqliteHistoryDatabase` (the Android adapter), `TranscriptionRow`,
`MappingFailure`. The tombstone stays internal; a core tombstone port is
deferred to a later phase. The store's other methods (`count`, `close` and the
internal form of the tombstone sweep) are internal too;
`sweepExpiredTombstones`, `pendingTombstoneIds`, `pendingTombstoneIdsAfter` and `clearAllTombstones` are public;
`applyRemoteRow`, `applyRemoteDelete` and `retainOnly` are public, as the PullSink calls this store implements.

**Storage:** SQLite via the Android platform API (android.database.sqlite), no Room dependency. Table `transcriptions`:
`id, text, source (local|server), model, duration_ms, created_at, audio_path (nullable, off by default)`.
The table has no `sync_status` column: a history row carries no sync state.
The file is `breaker_history.db` (`HistorySql.DATABASE_NAME`), schema version 1 (`HistorySql.SCHEMA_VERSION`); neither has changed since the module's first commit, and `onUpgrade` drops nothing.

**UI:** the history screen (list, Copy and Delete buttons, a 5-second Undo for Delete through the HistoryStore port) is the
ui module's; this module supplies the data it shows. `list(limit)` returns the newest first (`created_at DESC, id DESC`,
`HistorySql.SELECT_NEWEST`). The page size (50 per Load more, cap 300) and the six-line fold are ui's
(`PAGE_SIZE`, `ROW_CAP`, `COLLAPSE_LINES`), not this module's. The list shows every
transcription as its own card, each folded alone. The first-card stretch (a ui layout bug, fixed in the ui module and
emulator-proven; not yet seen on the project owner's phone) is not this module's.

**Storage protection (T7):** the database is app-private: a bare file name,
opened in the app's own directory. Encryption at rest belongs to crypto and is
not done here.

**Retention (F28):** the local purge (`purgeExpired()`) deletes transcriptions
that have expired under the **ADR-010 precise rule** (TTL). The phone and the
server use the same rule with different windows and clocks: the phone keeps 30 x 24 h
(ADR-047; clock `created_at` inside the encrypted body), the server keeps 3 months, 90 x 24 h
(ADR-010; clock `stored_at_ms`, the time of the last write), so the server always keeps a
row longer than the phone does. The server's expiry job is built (ADR-035; a daily job in
the sync-api module, live rows only, by `stored_at_ms`). The "30-day" TTL on the Owns line
means that precise rule with the phone's 30 x 24 hour window.

**Tombstones:** every delete of a stored row leaves a tombstone for sync to
carry to the server: the purge tombstones each row it removes, and `delete`
tombstones the row it removes. `delete` of an id this device does not hold
leaves none. A delete made elsewhere (the web FE, another device) has an
entry point, `applyRemoteDelete`, the PullSink call this store implements,
which records a tombstone even when this device held no such row; sync's
PullApplier calls it for every pulled delete (sync/PullApplier.kt:46), but only a
debuggable build with sync on runs a pull (BreakerApp.kt:87-88). Tombstones are
written and kept for sync to carry: the sync queue pushes each one as a delete row, and the app's start-up
backfill queues a delete for every tombstone that has none. The module sweeps them by age:
`sweepExpiredTombstones()` (public, for the app's start-up path) drops the tombstones recorded before the
30-day window and answers how many. The app calls it once at start-up, only when sync is on, only after
the backfill walked every tombstone to the end (a walk cut by its page cap sweeps nothing), and only when
the clock guard let the row purge run; a build without sync keeps its tombstones. A tombstone is also what
stops a pulled row from coming back (`applyRemoteRow` refuses an id that has one), so after the window a
pull can bring back a row deleted here, but only when the server still lists that row live, that is, when
the delete never reached the server, until that row reaches the server's 3-month retention; a delete that
reached the server stays guarded by the server's delete record. The 30-day tombstone window, measured from
the delete, is the project owner's ruling (ADR-047), the same window as the transcriptions and the same 30
days as the idle sign-out of ADR-031.

**Pulled rows (ADR-035):** `applyRemoteRow`, the PullSink call, stores one pulled live row, one row per id, replacing a held row; it refuses the row, stores nothing and answers false when this device holds a tombstone for that id, of any reason, and the tombstone stays (a row deleted anywhere does not come back; the case of reviving a row from another device is left open, nothing is lost because the server keeps the row). The check and the write are one transaction. This store reads a tombstone only for that refusal. **Built (ADR-035):** the sync module's PullApplier (sync/PullApplier.kt, #448) calls applyRemoteRow, applyRemoteDelete and retainOnly after a pull, and PullCycle (sync/PullCycle.kt, #450) runs it; a live row whose id waits unsent in the sync push queue is skipped by PullApplier before it calls, through isQueued (sync/PullApplier.kt:49, sync/SyncQueueDatabase.kt:108), because the queue is sync's and history cannot see it; a pulled delete is applied before that check (sync/PullApplier.kt:46), so a delete is applied even when its id waits unsent. Only a debuggable build with sync on builds and starts the pull: BreakerApp.kt:87-88 passes the pull sink and transport only then, and QueuedSyncService.kt:67 builds PullApplier and PullCycle from them. Of the delete merge, both parts are built in this store: a local delete followed by a local save of the same id clears the local tombstone, so the row is live again; and a remote delete (applyRemoteDelete, SqliteHistoryStore.kt:236) is applied even if this device saved the same id again, and its tombstone is held across a local re-save, because the server wins for a delete made on another device (save clears only the user's own tombstone, SqliteHistoryStore.kt:78). History stays the only module that writes the local store. `retainOnly`, the PullSink call for the prune after a full list, removes every local row whose id is not in the set it is given, in one transaction, writes no tombstone and answers how many rows it removed; a row removed that way returns with the next incremental pull; PullApplier calls it only after a finished full list.


## Invariants
- Every transcription saved regardless of source (F7).
- Tap-to-copy works from history; delete works.
- DB in app-private dir; no world-readable permissions.
- TTL purge follows the ADR-010 precise rule (a row exactly at the cutoff is kept); every delete of a stored row
  leaves a tombstone.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Sync transport (sync)
- Per-row sync status: history rows carry none; the sync module keeps its own queue (core.SyncService
  enqueue and pushPending)
- Encryption (crypto)

## Test Locations
- Unit: `android/modules/history/src/test/kotlin/dev/breaker/dictation/history/`
- Contract: `tests/contract/test_history_contract.py`
- Run the unit tests from the repo root: ./gradlew :android:modules:history:testDebugUnitTest
- Run the contract test from the repo root: python3 -m unittest discover -s tests/contract -t tests/contract -p test_history_contract.py
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

## Known Gotchas
- TTL + tombstones must stay consistent with server retention (F28); by design both follow the ADR-010 precise rule.
  The server's expiry job is built (ADR-035, sync-api module): the same window on the server's clock.
- The app builds the store once per process (`BreakerApp`, lazy `SqliteHistoryStore.create`) and runs `purgeExpired()` once
  at start, off the main thread and through `PurgeGuard` (see Purpose); a purge failure is logged, not thrown. The window is the fixed 30 days of ADR-047.
- NOT REPRODUCED (a defect report): a phone test of APK 8 found the history gone after an update. An in-place 6 to 8
  update keeps it (emulator test); APKs 1-5 had different signing keys, so 5 to 6 needed an uninstall. The database is app-private, so an update keeps it and an uninstall removes it; the name and schema version
  above have not changed. Required: an update keeps the history. The project owner ruled that an uninstall removes
  everything (ADR-028 Decision 2); the manifest change that stops backup restoring it is BUILT (`allowBackup=false` and rules files that exclude every domain, in the app module).
- FIXED defect H (robustness), 2026-10-21: a stored row whose `source` is neither `local` nor `server`
  no longer stops the list from loading. `SqliteHistoryStore.list` maps with `mapNotNull`: an unmappable row is
  skipped, is left on file (never deleted), and is reported through an injectable hook whose DEFAULT is
  `android.util.Log` (tag `BreakerHistory`; row id and stored source only, never the text), so the shipped
  `SqliteHistoryStore.create(context, clock)` — which the module gate freezes at two arguments, so the default is
  what production runs — logs it. `TranscriptionRow.toTranscription()` is unchanged and still throws
  `MappingFailure`; the new `toTranscriptionOrNull()` is the tolerant form. Pinned by StoreUnknownSourceTest (NEW),
  StoreReportHookGateTest (NEW), and the re-pointed-and-renamed StoreFailureTest and StoreOnSqliteTest.
- Tombstones are written and kept; the app's start-up backfill reads the user ones to queue their delete pushes, and
  `sweepExpiredTombstones()` drops them by age (see Tombstones above). A build without sync keeps them, so there the
  tombstone table only grows.
- A tombstone recorded by a LOCAL `delete` does not outlive a re-save of its id: `save` of the same id clears that
  tombstone in the same transaction as the row, so the row is live again and nothing is pending for it. A tombstone
  recorded by a REMOTE delete (`applyRemoteDelete`) is held across a local re-save, because the server wins for a
  delete made on another device: built. The sync merge that consumes it is PullApplier (sync/PullApplier.kt), built
  and wired (ADR-035, #521). A tombstone
  recorded by the age sweep is held across a local re-save too; the held cases are pinned by the store's tests.

- Keep the store at one per process, shared through the app's wiring. Each `create()`
  opens its own database handle on the same file, so two stores on one file would contend for the write lock
  (expected from how SQLite locks a file; no test here shows it). Close the store on
  shutdown if the app wants it closed; `close()` is internal, so the app wiring has to ask for it to be opened up
  when it needs it.
- These five are modelled and tested, not shipped: `AppPrivateStorage.resolve`, `AppPrivateStorage.isInside`, the
  `AppPrivateStorage.DATABASES_DIR` constant, `RetentionPolicy.isExpired` and `RetentionBoundary.isExpired`. The
  platform's `SQLiteOpenHelper` picks the databases folder and the shipped purge applies the SQL predicate
  (`created_at < ?`), so nothing in the shipped path calls them.
- The JVM tests run the desktop SQLite that ships in the test-only sqlite-jdbc jar, not the phone's SQLite, so the SQL
  stays in the common subset; the on-device proof comes with the Phase 1 launch test. `SqliteHistoryDatabase` (the
  Android adapter) runs no JVM test because it needs Android APIs; the database file's mode needs a device to verify
  too.
- The tests do run the adapter's three delete statements (`deleteById`, `deleteCreatedBefore`,
  `deleteTombstonesRecordedBefore`) on that SQLite, but by reading the adapter's source text, not by executing it
  (`deleteById` spells `id = ?` inline; the other two pass `HistorySql` predicates). That reading fails loudly if the
  adapter's delete calls change shape, and it is brittle by nature: when it breaks, update the reader; better, make
  the adapter take its SQL from `HistorySql`, so the tests can run those constants instead.
- `AdapterBindingsTest` reads the adapter's source the same way and checks, as text, the order in which `save` and
  `putTombstone` bind their values, and the positions from which it reads the row, tombstone, id and count columns,
  against the column order in `HistorySql` and the constructors of `TranscriptionRow` and `Tombstone`. It does not
  run the adapter; what Android does with the bound array or the cursor is not checked.
- The JVM twin the real-SQLite tests run (`JdbcHistoryDatabase`, in the test folder) models three things of the
  phone's adapter: how the delete text is assembled, the autocommit and transaction behaviour, and the row
  mapping. None of the three models is checked against Android.
- `create()` needs an Android `Context`, so no JVM test calls it; the tests build the store through its internal
  constructor with a test clock.
