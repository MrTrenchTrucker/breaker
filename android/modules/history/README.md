# History — README

Persistent transcription history for easy re-copying. The module saves the
transcriptions it is given in a small SQLite database on the phone and removes
old ones when `purgeExpired()` is called. The app builds the store once
(`BreakerApp`) and runs `purgeExpired()` once at start, off the main thread, through `PurgeGuard`, which can skip it for a suspected forward clock jump.

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
instant is more than 30 days (30 x 24 h) before the purge's current instant (the
phone's window, ADR-047). A row exactly at the cutoff is kept. The rule is computed on
UTC instants, with no time zone and no calendar months. The server keeps rows for
3 months (90 x 24 h, ADR-010) on its own clock, the time of a row's last write, so it
always keeps a row longer than the phone does. The server's expiry job is built
(ADR-035; a daily job in the sync-api module, live rows only, by `stored_at_ms`).

## Tombstones

Every delete of a stored row leaves a tombstone for sync to carry to the server.
A purge records one for each expired row it removes, and `delete` records one
when it removed a row; `delete` of an id this device does not hold leaves none.
A delete made elsewhere (the web FE, another device) has an entry point,
`applyRemoteDelete`, the PullSink call this store implements, which records a
tombstone even when this device held no such row; the sync module's PullApplier calls it.

Tombstones are written and kept for sync to carry: the sync queue pushes each one
as a delete row, and the start-up backfill, described below, queues a delete for every
tombstone that has none. The tombstone type stays internal to this module.
`sweepExpiredTombstones()` drops the tombstones recorded more than 30 days before now,
measured from the delete: the project owner's ruling (ADR-047), the same window as the
transcriptions and the same 30 days as the idle sign-out of ADR-031. The app calls it
once at start-up, only when sync is on, and only after the backfill walked every
tombstone to the end.

The start-up backfill reads `pendingTombstoneIds(limit)`, which returns only USER-reason tombstones: `RETENTION` is the phone's own 30-day age-out and the server handles its own retention, and `REMOTE` is a delete the server already processed, so neither is pushed as a delete. When more than `limit` are pending, the app pages through them with `pendingTombstoneIdsAfter(lastId, limit)`; both order by `id DESC`, so the walk queues every USER tombstone exactly once and stops when a page comes back short.

Pulled rows (ADR-035): `applyRemoteRow`, the PullSink call, stores one pulled live row, one
row per id, replacing a held row. It refuses the row, stores nothing and answers false when
this device holds a tombstone for that id, of any reason, or when the row's `createdAt` is
strictly older than the retention cutoff (the same cutoff `purgeExpired` applies, strictly
less than; a row exactly at the cutoff is stored); the tombstone stays (a row deleted anywhere
does not come back; the case of reviving a row from another device is left open, nothing is
lost because the server keeps the row). The check and the write are one transaction, and the
store reads a tombstone only for that refusal.

Built and wired (ADR-035, #521): the sync module's `PullApplier` calls `applyRemoteRow` and
`applyRemoteDelete` after each pull; the app starts a pull after each push drain when sync is on (release
builds have sync off); an id waiting unsent in the sync push queue is skipped by sync before it calls, because the queue is sync's and history cannot see it.
Both parts of the delete merge are built: a local delete followed by a local save of the same id
clears the local tombstone, so the row is live again; and a remote delete is applied even if this device
saved the same id again, and a remote tombstone is held across a local re-save, because the server wins
for a delete made on another device.
History stays the only module that writes the local store; the sync module's PullApplier calls
the PullSink methods. `retainOnly`, the PullSink call for the prune after a full list, removes every
local row whose id is not in the set it is given, in one transaction, writes no tombstone and
answers how many rows it removed; a row removed that way returns with the next incremental
pull; the sync module will call it only after a finished full list.

## Public surface

- `SqliteHistoryStore`, built only through `SqliteHistoryStore.create(context, clock)`.
  It implements `core.HistoryStore` and `core.PullSink` and exposes `purgeExpired(): PurgeReport`.
- `PurgeReport`, what `purgeExpired()` returns.

Everything else is internal, including the store's other methods
(`count`, `close`, the tombstone sweep and the
pending-tombstone list). The app calls `purgeExpired()` once at start
(`BreakerApp`), through `PurgeGuard`: it skips the purge for at most 7 days when automatic time is off and over 30 days have
passed since the last purge.

The app creates the store once per process and shares it through its wiring. Each `create()` opens its own database handle on the
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
