package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class ExpirySchedulerTest : TempDatabaseTest() {

    private companion object {
        const val T0: Long = 1_700_000_000_000L
        const val TTL: Long = 7_776_000_000L // the retention TTL, 90 x 24 h
        const val NOW: Long = T0 + TTL + 1
        const val SMALL_CAP: Int = 3
        const val SMALL_BOUND: Int = 2

        // Bob has nothing due: his turn is one empty batch, and every batch reads the clock once.
        const val BOB_BATCHES: Int = 1
    }

    // A clock that counts its reads: the scheduler reads it once per batch, so the reads are the batches.
    private class CountingClock(private var nowMs: Long, private val stepMs: Long = 0L) : Clock() {
        var reads: Int = 0
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant {
            reads++
            val now = nowMs
            nowMs += stepMs
            return Instant.ofEpochMilli(now)
        }
    }

    private class Pause(val duration: Duration, val deletedRowsOfAlice: Long)

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private lateinit var accounts: AccountStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp)
        store = TranscriptionStore(temp.db)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun clientId(n: Int): String = "0f9c4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun count(accountId: Long, deleted: Int): Long = inTransaction(temp) { connection ->
        connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = ?").use { s ->
            s.setLong(1, accountId)
            s.setInt(2, deleted)
            s.executeQuery().use { r -> if (r.next()) r.getLong(1) else -1L }
        }
    }

    private fun storedAtMsOf(accountId: Long, n: Int): Long = inTransaction(temp) { connection ->
        connection.prepareStatement("SELECT stored_at_ms FROM transcriptions WHERE account_id = ? AND client_id = ?").use { s ->
            s.setLong(1, accountId)
            s.setString(2, clientId(n))
            s.executeQuery().use { r -> if (r.next()) r.getLong(1) else -1L }
        }
    }

    // Pushes rows 1..rows for the account, then stamps them all stored at T0 (far past any NOW cutoff).
    private fun pushOld(accountId: Long, rows: Int) {
        for (n in 1..rows) {
            runBlocking { store.push(accountId, clientId(n), 0, "aGVsbG8=") }
        }
        setStoredAt(accountId, T0)
    }

    private fun setStoredAt(accountId: Long, storedAtMs: Long) {
        inTransaction(temp) { connection ->
            connection.prepareStatement("UPDATE transcriptions SET stored_at_ms = ? WHERE account_id = ?").use { s ->
                s.setLong(1, storedAtMs)
                s.setLong(2, accountId)
                s.executeUpdate()
            }
        }
    }

    // One run: the start delay passes, one run happens, and the pause for the interval ends the loop.
    // Every pause is noted with how many of alice's rows were already deleted when it was asked.
    private fun runOneRun(
        clock: Clock,
        scheduledStore: TranscriptionStore = store,
        scheduledAccounts: AccountStore = accounts,
        batchCap: Int = SMALL_CAP,
        maxBatches: Int = SMALL_BOUND,
    ): List<Pause> {
        val pauses = ArrayList<Pause>()
        try {
            runBlocking {
                ExpiryScheduler.runExpiryScheduler(
                    store = scheduledStore,
                    accounts = scheduledAccounts,
                    clock = clock,
                    pause = { duration ->
                        pauses.add(Pause(duration, count(alice, 1)))
                        if (pauses.size >= 2) throw CancellationException("stop after one run")
                    },
                    batchCap = batchCap,
                    maxBatches = maxBatches,
                )
            }
        } catch (_: CancellationException) {
        }
        return pauses
    }

    private fun captureLog(block: () -> Unit): List<String> {
        val logger = Logger.getLogger("dev.breaker.server.syncapi.expiry")
        val seen = ArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                seen.add(record.message ?: "")
            }

            override fun flush() {}

            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return seen
    }

    @Test
    fun `nothing runs before the start delay`() {
        pushOld(alice, 1)
        val pauses = runOneRun(CountingClock(NOW))
        assertEquals("two pauses in one run", 2, pauses.size)
        assertEquals("the first pause is the start delay", Duration.ofHours(1), pauses[0].duration)
        assertEquals("no row was deleted when the start delay was asked for", 0L, pauses[0].deletedRowsOfAlice)
        assertEquals("the second pause is the interval", Duration.ofHours(24), pauses[1].duration)
        assertEquals("the row was deleted by the time the interval was asked for", 1L, pauses[1].deletedRowsOfAlice)
    }

    @Test
    fun `nothing runs when it is cancelled during the start delay`() {
        pushOld(alice, 1)
        val clock = CountingClock(NOW)
        try {
            runBlocking {
                ExpiryScheduler.runExpiryScheduler(
                    store = store,
                    accounts = accounts,
                    clock = clock,
                    pause = { throw CancellationException("stopped before the first run") },
                )
            }
        } catch (_: CancellationException) {
        }
        assertEquals("no batch ran", 0, clock.reads)
        assertEquals("the row is still live", 1L, count(alice, 0))
    }

    @Test
    fun `a full batch is followed by another and a short batch stops the account`() {
        pushOld(alice, SMALL_CAP + 1)
        val clock = CountingClock(NOW)
        runOneRun(clock)
        assertEquals("a full batch then a short one is two batches", 2, clock.reads - BOB_BATCHES)
        assertEquals("every row was marked", (SMALL_CAP + 1).toLong(), count(alice, 1))
        assertEquals("no live row is left", 0L, count(alice, 0))
    }

    @Test
    fun `a short first batch is the only batch`() {
        pushOld(alice, 2)
        val clock = CountingClock(NOW)
        runOneRun(clock)
        assertEquals("one short batch and no more", 1, clock.reads - BOB_BATCHES)
        assertEquals("both rows were marked", 2L, count(alice, 1))
    }

    @Test
    fun `the per run bound stops an account that still has rows`() {
        pushOld(alice, SMALL_CAP * SMALL_BOUND + 1)
        val clock = CountingClock(NOW)
        runOneRun(clock)
        assertEquals("exactly the bound of batches ran", SMALL_BOUND, clock.reads - BOB_BATCHES)
        assertEquals("the bound times the cap rows were marked", (SMALL_CAP * SMALL_BOUND).toLong(), count(alice, 1))
        assertEquals("one row is left for the next run", 1L, count(alice, 0))
    }

    @Test
    fun `now is read at each batch and not once per run`() {
        pushOld(alice, SMALL_CAP + 1)
        runOneRun(CountingClock(NOW, stepMs = 60_000L))
        assertEquals("the first batch is stamped with the first now", NOW, storedAtMsOf(alice, 1))
        assertEquals("the second batch is stamped with the later now", NOW + 60_000L, storedAtMsOf(alice, SMALL_CAP + 1))
    }

    @Test
    fun `a row exactly at the cutoff is kept and one older is expired`() {
        runBlocking {
            store.push(alice, clientId(1), 0, "aGVsbG8=")
            store.push(alice, clientId(2), 0, "aGVsbG8=")
        }
        inTransaction(temp) { connection ->
            connection.prepareStatement("UPDATE transcriptions SET stored_at_ms = ? WHERE client_id = ?").use { s ->
                s.setLong(1, NOW - TTL)
                s.setString(2, clientId(1))
                s.executeUpdate()
                s.setLong(1, NOW - TTL - 1)
                s.setString(2, clientId(2))
                s.executeUpdate()
            }
        }
        runOneRun(CountingClock(NOW))
        assertEquals("the row at the cutoff is kept live", 1L, count(alice, 0))
        assertEquals("the row one millisecond older is a delete record", 1L, count(alice, 1))
        assertEquals("the kept row is row 1", NOW - TTL, storedAtMsOf(alice, 1))
    }

    @Test
    fun `an account with nothing due does not stop the run`() {
        pushOld(alice, 1)
        val bobHead = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, bob)
                s.executeQuery().use { r -> if (r.next()) r.getLong(1) else -1L }
            }
        }
        runOneRun(CountingClock(NOW))
        assertEquals("alice's row was marked", 1L, count(alice, 1))
        assertEquals("bob has no delete record", 0L, count(bob, 1))
        val bobAfter = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, bob)
                s.executeQuery().use { r -> if (r.next()) r.getLong(1) else -1L }
            }
        }
        assertEquals("bob's change number did not move", bobHead, bobAfter)
    }

    @Test
    fun `one account failing does not stop the others and its message is never logged`() {
        pushOld(alice, 1)
        runBlocking { store.push(bob, clientId(1), 0, "aGVsbG8=") }
        setStoredAt(bob, T0)
        inTransaction(temp) { connection ->
            connection.createStatement().use { s ->
                s.execute(
                    "CREATE TRIGGER forced_expiry_failure BEFORE UPDATE ON transcriptions " +
                        "WHEN NEW.account_id = $alice BEGIN SELECT RAISE(ABORT, 'forced-secret-text'); END",
                )
            }
        }
        var pauses: List<Pause> = emptyList()
        val lines = captureLog { pauses = runOneRun(CountingClock(NOW)) }
        assertEquals("alice's row is still live, the batch was rolled back", 1L, count(alice, 0))
        assertEquals("bob's row was still expired", 1L, count(bob, 1))
        assertEquals("the schedule went on to the interval pause", 2, pauses.size)
        assertEquals("one line was logged", 1, lines.size)
        assertTrue("the line names the scheduler", lines[0].startsWith("expiry scheduler: "))
        assertFalse("the failure message is not logged", lines[0].contains("forced-secret-text"))
    }

    @Test
    fun `a failure to list the accounts does not stop the schedule`() {
        pushOld(alice, 1)
        val dead = TestDatabases.openTemp()
        val deadAccounts = AccountFixtures.newStore(dead)
        dead.close()
        var pauses: List<Pause> = emptyList()
        val lines = captureLog { pauses = runOneRun(CountingClock(NOW), scheduledAccounts = deadAccounts) }
        assertEquals("the schedule went on to the interval pause", 2, pauses.size)
        assertEquals("one line was logged", 1, lines.size)
        assertEquals("alice's row was not touched", 1L, count(alice, 0))
    }

    @Test
    fun `the constants are the ones the card states`() {
        assertEquals(500, ExpiryScheduler.BATCH_CAP)
        assertEquals(10, ExpiryScheduler.MAX_BATCHES_PER_ACCOUNT_PER_RUN)
        assertEquals(Duration.ofHours(24), ExpiryScheduler.INTERVAL)
        assertEquals(Duration.ofHours(1), ExpiryScheduler.START_DELAY)
        assertEquals(7_776_000_000L, TranscriptionPurge.RETENTION_TTL_MS)
    }
}
