package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class TranscriptionStoreOrderTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TranscriptionFixtures.store(temp, clock)
        alice = TranscriptionFixtures.accountId(temp, "alice")
    }

    @Test
    fun `the list orders rows by stored time ascending`() {
        // Pushed later with client ids that sort the other way, so neither a client id order
        // nor a descending order can give the oldest-first answer.
        TranscriptionFixtures.push(store, alice, TranscriptionFixtures.clientId(3))
        clock.advance(Duration.ofSeconds(10))
        TranscriptionFixtures.push(store, alice, TranscriptionFixtures.clientId(2))
        clock.advance(Duration.ofSeconds(10))
        TranscriptionFixtures.push(store, alice, TranscriptionFixtures.clientId(1))
        assertEquals(
            "sync-api: rows must come back oldest first",
            listOf(
                TranscriptionFixtures.clientId(3),
                TranscriptionFixtures.clientId(2),
                TranscriptionFixtures.clientId(1),
            ),
            TranscriptionFixtures.list(store, alice).map { row -> row.clientId },
        )
    }

    @Test
    fun `rows that share one stored time are ordered by client id and not by insertion`() {
        TranscriptionFixtures.push(store, alice, TranscriptionFixtures.clientId(2))
        TranscriptionFixtures.push(store, alice, TranscriptionFixtures.clientId(1))
        val rows = TranscriptionFixtures.list(store, alice)
        assertEquals(
            "sync-api: the two rows must share one stored time for this test to mean anything",
            rows.first().storedAtMs,
            rows.last().storedAtMs,
        )
        assertEquals(
            "sync-api: ties are broken by client id",
            listOf(TranscriptionFixtures.clientId(1), TranscriptionFixtures.clientId(2)),
            rows.map { row -> row.clientId },
        )
    }

    @Test
    fun `two rows pushed in one second are ordered by client id whatever their milliseconds`() {
        // Both pushes fall in the same whole second. The later push carries the smaller client id,
        // so an order by the real milliseconds would put the first push first.
        val earlier = "ff000000-0000-4000-8000-000000000000"
        val later = "00000001-0000-4000-8000-000000000000"
        clock.set(TokenFixtures.START.plusMillis(100))
        TranscriptionFixtures.push(store, alice, earlier)
        clock.set(TokenFixtures.START.plusMillis(900))
        TranscriptionFixtures.push(store, alice, later)
        assertEquals(
            "sync-api: rows in one second come back by client id",
            listOf(later, earlier),
            TranscriptionFixtures.list(store, alice).map { row -> row.clientId },
        )
    }

    @Test
    fun `the stored time of a row is read from the injected clock`() {
        val moment = TokenFixtures.START.plusSeconds(5)
        clock.set(moment)
        TranscriptionFixtures.push(store, alice, TranscriptionFixtures.clientId(1))
        assertEquals(
            "sync-api: the stored time must be the clock's whole second",
            moment.toEpochMilli(),
            TranscriptionFixtures.list(store, alice).single().storedAtMs,
        )
    }
}
