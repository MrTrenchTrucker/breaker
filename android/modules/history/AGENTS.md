# AGENTS.md — android/modules/history/

## Purpose

Saves the transcriptions it is given in a private SQLite database on the phone,
so the user can find and re-copy them later. Old transcriptions expire by the
ADR-010 precise rule when `purgeExpired()` is called; the app shell is to wire
that call. Every delete of a stored row leaves a tombstone for sync to carry to
the server (see Tombstones below for what is in place).

**Build phase:** Phase 2. Needs first: `core` (on `main`). The retention rules are finished in Phase 21 (data lifecycle).

## Owns
SQLite transcription history, 3-month TTL cleanup, tombstones.

## Public Interface

- `SqliteHistoryStore` — built only via `SqliteHistoryStore.create(context, clock)`; implements `core.HistoryStore`;
  exposes `purgeExpired(): PurgeReport`.
- `PurgeReport` — the report `purgeExpired()` returns.

**Internal (not public):** `RetentionPolicy`, `Tombstone`, `TombstonePurgeReport`,
`HistorySql`, `RetentionBoundary`, `AppPrivateStorage`, `HistoryDatabase`,
`SqliteHistoryDatabase` (the Android adapter), `TranscriptionRow`,
`MappingFailure`. The tombstone stays internal; a core tombstone port is
deferred to a later phase. The store's other methods (`applyRemoteDelete`,
`count`, `close`, the tombstone sweep and the pending-tombstone list) are
internal too.

**Storage:** SQLite via the Android platform API (android.database.sqlite), no Room dependency. Table `transcriptions`:
`id, text, source (local|server), model, duration_ms, created_at, audio_path (nullable, off by default)`.
The table has no `sync_status` column: a history row carries no sync state.

**UI:** the history screen (list, tap-to-copy, long-press delete, search) is the
ui module's; this module supplies the data it shows.

**Storage protection (T7):** the database is app-private: a bare file name,
opened in the app's own directory. Encryption at rest belongs to crypto and is
not done here.

**Retention (F28):** the local purge (`purgeExpired()`) deletes transcriptions
that have expired under the **ADR-010 precise rule** (TTL). By design the phone
and the server both use that same rule; the repository has no server retention
code yet. The "3-month" TTL on the Owns line means that precise rule: 90 x 24
hours, per ADR-010.

**Tombstones:** every delete of a stored row leaves a tombstone for sync to
carry to the server: the purge tombstones each row it removes, and `delete`
tombstones the row it removes. `delete` of an id this device does not hold
leaves none. A delete made elsewhere (the web FE, another device) has an
internal entry point, `applyRemoteDelete`, which records a tombstone even when
this device held no such row; no shipped code calls it yet. Tombstones are
written and kept; no shipped code reads or sweeps them yet, because the port
that lets sync reach them is deferred. The 90-day tombstone window, measured
from the delete, is a module design choice; the spec sets no tombstone lifetime.

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
  The repository has no server retention code yet.
- `purgeExpired()` is to be called by the app's scheduler (android/app); nothing calls it until the app shell wires it.
- Tombstones are written and kept; no shipped code reads or sweeps them yet. The port that lets sync reach them is
  deferred, so the tombstone table only grows until it lands.
- When the app wires the store, create it once per process and share it through the app's wiring. Each `create()`
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
