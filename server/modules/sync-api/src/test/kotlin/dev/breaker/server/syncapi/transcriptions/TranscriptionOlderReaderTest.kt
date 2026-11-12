package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// TranscriptionStore.older through the reader: the account's own live rows, strictly older than the
// bound, newest first, a tie broken by change number, fifty rows then more, the four MiB cost cap, a
// future bound, and an empty page. Rows are stored at offsets from TokenFixtures.START, so a stored
// time is the start plus the offset in milliseconds.
internal class TranscriptionOlderReaderTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L
    private val base: Long = TokenFixtures.START.toEpochMilli()

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = SyncRouteTestSupport.newStore(temp, clock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun clientIdOf(index: Int): String = "0000" + "0000-0000-4000-8000-" + String.format("%012d", index)

    private fun at(offsetMs: Long): Long = base + offsetMs

    // Pushes one live row for [accountId], stored at [offsetMs] after START.
    private fun pushAt(accountId: Long, offsetMs: Long, clientId: String, encrypted: String = SyncRouteTestSupport.ENCRYPTED) {
        clock.set(TokenFixtures.START.plusMillis(offsetMs))
        val pushed = runBlocking { store.push(accountId, clientId, 0, encrypted) }
        assertEquals("sync-api: the row is stored", PushResult.Stored, pushed)
    }

    private fun older(bound: OlderBound): OlderPage = runBlocking { store.older(alice, bound) }

    private fun idsOf(page: OlderPage): List<String> = page.records.map { it.clientId }

    // Fails when the account filter is dropped: bob's row would come back in alice's page.
    @Test
    fun `the older read returns only the rows of the account that asks`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(bob, 2_000L, clientIdOf(2))
        val page = older(OlderBound(at(10_000L), null))
        assertEquals(listOf(clientIdOf(1)), idsOf(page))
        assertFalse("sync-api: no more rows", page.more)
    }

    // Fails when the bound is not strict: a row stored exactly at the bound time would be kept.
    @Test
    fun `a row stored at the bound time is left out and the rows below it stay`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 2_000L, clientIdOf(2))
        pushAt(alice, 3_000L, clientIdOf(3))
        assertEquals(listOf(clientIdOf(1)), idsOf(older(OlderBound(at(2_000L), null))))
    }

    // Fails when the tie compares the change number inclusively: the row at the bound change would be kept.
    @Test
    fun `a bound with a change number breaks a tie on the same stored time`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 1_000L, clientIdOf(2))
        pushAt(alice, 1_000L, clientIdOf(3))
        assertEquals(listOf(clientIdOf(2), clientIdOf(1)), idsOf(older(OlderBound(at(1_000L), 3L))))
        assertEquals(emptyList<String>(), idsOf(older(OlderBound(at(1_000L), 1L))))
    }

    // Fails when the order is oldest first.
    @Test
    fun `the page is newest first`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 2_000L, clientIdOf(2))
        pushAt(alice, 3_000L, clientIdOf(3))
        assertEquals(listOf(clientIdOf(3), clientIdOf(2), clientIdOf(1)), idsOf(older(OlderBound(at(10_000L), null))))
    }

    // Fails when the live-only filter is dropped: the delete record of row one would come back.
    @Test
    fun `a deleted row is never returned`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 2_000L, clientIdOf(2))
        clock.set(TokenFixtures.START.plusMillis(3_000L))
        assertEquals("sync-api: the row is deleted", DeleteResult.Deleted, runBlocking { store.delete(alice, clientIdOf(1)) })
        assertEquals(listOf(clientIdOf(2)), idsOf(older(OlderBound(at(10_000L), null))))
    }

    // Fails when the row cap is not fifty: the first page then holds fifty one rows, and more is false.
    @Test
    fun `a page holds fifty rows and more is true and the next call gets the rest`() {
        for (index in 1..51) pushAt(alice, index * 1_000L, clientIdOf(index))
        val first = older(OlderBound(at(100_000L), null))
        assertEquals("sync-api: fifty rows in the first page", 50, first.records.size)
        assertTrue("sync-api: more after the first page", first.more)
        assertEquals("sync-api: the newest row comes first", clientIdOf(51), first.records[0].clientId)
        assertEquals("sync-api: the fiftieth row is the last", clientIdOf(2), first.records[49].clientId)
        val second = older(OlderBound(at(2_000L), 2L))
        assertEquals(listOf(clientIdOf(1)), idsOf(second))
        assertFalse("sync-api: no more after the second page", second.more)
    }

    // Fails when the byte cap is lifted or widened. A row of 100000 characters costs 100200: 41 of them
    // fit under four MiB and 42 do not, so the 41st row (the 5th pushed) ends the page.
    @Test
    fun `the four MiB cost cap ends a page early and the next row waits for the next call`() {
        val big = "A".repeat(100_000)
        for (index in 1..45) pushAt(alice, index * 1_000L, clientIdOf(index), big)
        val first = older(OlderBound(at(100_000L), null))
        assertEquals("sync-api: forty one rows fit under the cap", 41, first.records.size)
        assertTrue("sync-api: more after the capped page", first.more)
        assertEquals("sync-api: the forty first row is the last", clientIdOf(5), first.records.last().clientId)
        val second = older(OlderBound(at(5_000L), 5L))
        assertEquals(listOf(clientIdOf(4), clientIdOf(3), clientIdOf(2), clientIdOf(1)), idsOf(second))
        assertFalse("sync-api: no more after the rest", second.more)
    }

    // Fails when the first row is cut by the cap too: the single large row would be dropped and the page empty.
    @Test
    fun `a record that alone passes the cap is still emitted alone and the next row waits`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 2_000L, clientIdOf(2), "B".repeat(5 * 1024 * 1024))
        val first = older(OlderBound(at(10_000L), null))
        assertEquals(listOf(clientIdOf(2)), idsOf(first))
        assertTrue("sync-api: more after the single large record", first.more)
        val second = older(OlderBound(at(2_000L), 2L))
        assertEquals(listOf(clientIdOf(1)), idsOf(second))
        assertFalse("sync-api: no more after the small record", second.more)
    }

    // Fails when the bound is clamped below the rows: a future bound must return every live row.
    @Test
    fun `a bound in the future returns every live row`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 2_000L, clientIdOf(2))
        val page = older(OlderBound(at(864_000_000L), null))
        assertEquals(listOf(clientIdOf(2), clientIdOf(1)), idsOf(page))
        assertFalse("sync-api: no more rows", page.more)
    }

    // Fails when an empty page reports more: there is then nothing left for a next call.
    @Test
    fun `a bound at or below every row gives an empty page with more false`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        val atRow = older(OlderBound(at(1_000L), null))
        assertEquals(emptyList<String>(), idsOf(atRow))
        assertFalse("sync-api: no more at the bound", atRow.more)
        val below = older(OlderBound(at(500L), null))
        assertEquals(emptyList<String>(), idsOf(below))
        assertFalse("sync-api: no more below the rows", below.more)
    }

    // Fails when the reader takes key_version from the deleted column, which is always 0 for a live row,
    // or when the page record drops the stored key version. The raw row is stored at key version 3.
    @Test
    fun `a row with a non zero key version keeps it in the page`() {
        SyncRouteTestSupport.insertRawRow(temp, alice, clientIdOf(1), 3, SyncRouteTestSupport.ENCRYPTED, at(1_000L))
        val page = older(OlderBound(at(10_000L), null))
        assertEquals("sync-api: one raw row in the page", 1, page.records.size)
        assertEquals("sync-api: the stored key version is kept", 3, page.records[0].keyVersion)
    }

    // Fails when the reader sets the deleted flag of a live row to true: every record of a live page says false.
    @Test
    fun `every record of the page has the deleted flag false`() {
        pushAt(alice, 1_000L, clientIdOf(1))
        pushAt(alice, 2_000L, clientIdOf(2))
        val page = older(OlderBound(at(10_000L), null))
        assertEquals("sync-api: two live rows in the page", 2, page.records.size)
        assertTrue("sync-api: every record is live", page.records.all { !it.deleted })
    }

    // Fails when the cost of a row drops its 200 bytes of record overhead. Each row of 102,200 characters
    // costs 102,400. Forty rows make 4,096,000, which fits under the four MiB cap of 4,194,304; the forty
    // first would make 4,198,400, over the cap, so the page holds forty rows. Without the 200 each row
    // costs 102,200, and forty one rows (4,190,200) fit, so the page would hold forty one.
    @Test
    fun `the row cost counts two hundred over the encrypted length`() {
        val big = "A".repeat(102_200)
        for (index in 1..45) pushAt(alice, index * 1_000L, clientIdOf(index), big)
        val page = older(OlderBound(at(100_000L), null))
        assertEquals("sync-api: forty rows fit under the cap", 40, page.records.size)
        assertTrue("sync-api: more after the capped page", page.more)
        assertEquals("sync-api: the fortieth row is the last", clientIdOf(6), page.records.last().clientId)
    }

    // Fails when the cap is compared with greater or equal: the cap test is running + size > cap, so a page
    // whose cost is exactly four MiB keeps both rows. Each row here costs 2 * 1024 * 1024, and two of them
    // make exactly the cap.
    @Test
    fun `a page whose cost is exactly four MiB is not cut`() {
        val large = "B".repeat(2 * 1024 * 1024 - 200)
        pushAt(alice, 1_000L, clientIdOf(1), large)
        pushAt(alice, 2_000L, clientIdOf(2), large)
        val page = older(OlderBound(at(10_000L), null))
        assertEquals(listOf(clientIdOf(2), clientIdOf(1)), idsOf(page))
        assertFalse("sync-api: no more rows after the exact cap", page.more)
    }
}
