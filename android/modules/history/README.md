# History — README

Persistent transcription history for easy re-copying. The module saves the
transcriptions it is given in a small SQLite database on the phone and removes
old ones when `purgeExpired()` is called. The app is to wire that call; nothing
in the app calls `create()` or `purgeExpired()` yet.

## How it works

Three layers:

- `SqliteHistoryStore` is the module's entry point for the rest of the app. It
  implements `core.HistoryStore` and does its work through a `HistoryDatabase`,
  a small internal seam between the store and the real database.
- `HistorySql` holds the module's SQL statements and `WHERE` predicates (the two
  retention predicates are written once in `RetentionBoundary` and named here).
  The one exception is the delete by id, whose `id = ?` predicate the adapter
  spells inline.
- `SqliteHistoryDatabase` is the Android adapter: it fills the seam using the
  platform's own `android.database.sqlite`. There is no Room dependency.

## Retention

The **ADR-010 precise rule**: a transcription expires when its `created_at`
instant is more than 90 days (90 x 24 h) before the purge's current instant. A
row exactly at the cutoff is kept. The rule is computed on UTC instants, with no
time zone and no calendar months. By design the phone and the server both use
it; the repository has no server retention code yet.

## Tombstones

Every delete of a stored row leaves a tombstone for sync to carry to the server.
A purge records one for each expired row it removes, and `delete` records one
when it removed a row; `delete` of an id this device does not hold leaves none.
A delete made elsewhere (the web FE, another device) has an internal entry
point, `applyRemoteDelete`, which records a tombstone even when this device held
no such row; no shipped code calls it yet.

Tombstones are written and kept. No shipped code reads or sweeps them yet,
because the port that lets sync reach them is deferred to a later phase; until
then the tombstone table only grows, and they stay internal to this module. The
module defines a 90-day tombstone window, measured from the delete. That is a
module design choice; the spec sets no tombstone lifetime.

## Public surface

- `SqliteHistoryStore`, built only through `SqliteHistoryStore.create(context, clock)`.
  It implements `core.HistoryStore` and exposes `purgeExpired(): PurgeReport`.
- `PurgeReport`, what `purgeExpired()` returns.

Everything else is internal, including the store's other methods
(`applyRemoteDelete`, `count`, `close`, the tombstone sweep and the
pending-tombstone list). `purgeExpired()` is to be called by the app's
scheduler (android/app); nothing calls it until the app shell wires it.

When the app wires the store, it should create it once per process and share it
through the app's wiring. Each `create()` opens its own database handle on the
same file, so two stores on one file would contend for the write lock (expected
from how SQLite locks a file; no test here shows it). Close the store on
shutdown if the app wants it closed; `close()` is internal, so the app wiring
has to ask for it to be opened up when it needs it.

## Build and test

- Unit tests run on the JVM: `./gradlew :android:modules:history:testDebugUnitTest`.
- They use real SQLite through `sqlite-jdbc`, a test-only dependency that is
  never shipped in the app.
- That is the desktop SQLite, not the phone's, so the SQL stays in the common
  subset; the on-device proof comes with the Phase 1 launch test.
- `SqliteHistoryDatabase` is never run by a JVM test because it needs Android
  APIs, and the database file's mode (private to the app) needs a device to
  verify.
- The tests do run the adapter's three delete statements (`deleteById`,
  `deleteCreatedBefore`, `deleteTombstonesRecordedBefore`) on that SQLite, but
  by reading the adapter's source text, not by executing it (`deleteById`
  spells `id = ?` inline; the other two pass `HistorySql` predicates). That
  reading fails loudly if the adapter's delete calls change shape, and it is
  brittle by nature: when it breaks, update the reader; better, make the
  adapter take its SQL from `HistorySql`, so the tests can run those constants
  instead.
- `AdapterBindingsTest` reads the adapter's source the same way and checks, as
  text, the order in which `save` and `putTombstone` bind their values, and the
  positions from which it reads the row, tombstone, id and count columns,
  against the column order in `HistorySql` and the constructors of
  `TranscriptionRow` and `Tombstone`. It does not run the adapter; what Android
  does with the bound array or the cursor is not checked.
- The JVM twin the real-SQLite tests run (`JdbcHistoryDatabase`, in the test
  folder) models three things of the phone's adapter: how the delete text is
  assembled, the autocommit and transaction behaviour, and the row mapping. None
  of the three models is checked against Android.
- `create()` needs an Android `Context`, so no JVM test calls it; the tests
  build the store through its internal constructor with a test clock.
- These five are modelled and tested, not shipped: `AppPrivateStorage.resolve`,
  `AppPrivateStorage.isInside`, the `AppPrivateStorage.DATABASES_DIR` constant,
  `RetentionPolicy.isExpired` and `RetentionBoundary.isExpired`. The platform's
  `SQLiteOpenHelper` picks the databases folder and the shipped purge applies
  the SQL predicate (`created_at < ?`), so nothing in the shipped path calls
  them.
- The structural contract test is `tests/contract/test_history_contract.py`.

Full module card: `AGENTS.md` in this folder.
