package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.DeleteResult
import dev.breaker.server.syncapi.transcriptions.PageResult.Gone
import dev.breaker.server.syncapi.transcriptions.PageResult.Page
import dev.breaker.server.syncapi.transcriptions.PushResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// TranscriptionStore.page unit tests: since null = live rows only; since N = rows above N incl.
// deletes; since < purged_through = Gone; since == purged_through = a page; 100-record cap; 4 MiB
// cap; more flag; next cursor. Client ids built from short pieces (no hex run of 7+).

internal class TranscriptionStorePageTest : TempDatabaseTest() {

    private companion object {
        const val CID_ONE = "0f9c" + "4d1e-6a2b-4c3d-" + "9e8f-1a" + "2b3c" + "4d5e6f"
        const val CID_TWO = "1a2b" + "3c4d-5e6f-4a7b-" + "8c9d-0e" + "1f2a" + "3b4c5d"
    }

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        store = TranscriptionStore(temp.db, AccountFixtures.fixedClock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
    }

    private fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String) =
        runBlocking { store.push(accountId, clientId, keyVersion, encrypted) }

    private fun delete(accountId: Long, clientId: String) =
        runBlocking { store.delete(accountId, clientId) }

    private fun setPurgedThrough(accountId: Long, value: Long) =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET purged_through = ? WHERE id = ?").use { s ->
                    s.setLong(1, value)
                    s.setLong(2, accountId)
                    s.executeUpdate()
                }
            }
        }

    private fun pageOf(accountId: Long, since: Long?) =
        runBlocking { store.page(accountId, since) }

    @Test
    fun sinceNullReturnsLiveRowsOnly() {
        assertEquals("push first", PushResult.Stored, push(alice, CID_ONE, 0, "one"))
        assertEquals("push second", PushResult.Stored, push(alice, CID_TWO, 0, "two"))
        val page = pageOf(alice, null)
        assertTrue("page is a Page", page is Page)
        val p = page as Page
        assertEquals("two live records", 2, p.records.size)
        assertEquals("record one client id", CID_ONE, p.records[0].clientId)
        assertEquals("record two client id", CID_TWO, p.records[1].clientId)
        assertFalse("no more", p.more)
        assertEquals("next is head", 2L, p.next)
    }

    @Test
    fun sinceNAboveHeadReturnsEmptyMoreFalseNextHead() {
        assertEquals("push one", PushResult.Stored, push(alice, CID_ONE, 0, "one"))
        val page = pageOf(alice, 5L)
        val p = page as Page
        assertEquals("zero records", 0, p.records.size)
        assertFalse("no more", p.more)
        assertEquals("next is head", 1L, p.next)
    }

    @Test
    fun sinceNIncludesDeleteRecord() {
        assertEquals("push", PushResult.Stored, push(alice, CID_ONE, 0, "one"))
        assertEquals("delete", DeleteResult.Deleted, delete(alice, CID_ONE))
        // since = 1 returns rows above change 1: the delete at change 2.
        val page = pageOf(alice, 1L)
        val p = page as Page
        assertEquals("one record", 1, p.records.size)
        assertEquals("delete record client id", CID_ONE, p.records[0].clientId)
        assertTrue("deleted true", p.records[0].deleted)
        assertEquals("change 2", 2L, p.records[0].change)
        assertFalse("no key version on delete", p.records[0].keyVersion != null)
        assertFalse("no encrypted on delete", p.records[0].encrypted != null)
        assertFalse("no more", p.more)
        assertEquals("next is change 2", 2L, p.next)
    }

    @Test
    fun sinceBelowPurgedThroughIsGone() {
        assertEquals("push", PushResult.Stored, push(alice, CID_ONE, 0, "one"))
        setPurgedThrough(alice, 1L)
        val page = pageOf(alice, 0L)
        assertTrue("page is Gone", page is Gone)
    }

    @Test
    fun sinceEqualToPurgedThroughIsNotGone() {
        assertEquals("push", PushResult.Stored, push(alice, CID_ONE, 0, "one"))
        setPurgedThrough(alice, 1L)
        val page = pageOf(alice, 1L)
        assertTrue("page is a Page", page is Page)
        val p = page as Page
        assertEquals("zero records", 0, p.records.size)
        assertFalse("no more", p.more)
        assertEquals("next is head", 1L, p.next)
    }

    @Test
    fun pageCapsAt100Records() {
        for (index in 1..101) {
            assertEquals("push $index", PushResult.Stored, push(alice, ("0000" + "0000-0000-4000-8000-" + String.format("%012d", index)), 0, "data"))
        }
        val page = pageOf(alice, null)
        val p = page as Page
        assertEquals("100 records", 100, p.records.size)
        assertTrue("more true", p.more)
        assertEquals("next is change of 100th", 100L, p.next)
    }

    @Test
    fun pageCapsAtSizeLimit() {
        // A record counts its encrypted length plus 200. With 41900 characters of encrypted text a
        // record counts 42100: 99 of them (about 4.17 MB) fit below the 4 MiB cap and the 100th would
        // pass it. The 100-record cap would allow 100, so only the size limit stops the page at 99.
        // These literal numbers are pinned here: they are not derived from the production constants.
        val big = "A".repeat(41900)
        for (index in 1..105) {
            assertEquals("push $index", PushResult.Stored, push(alice, ("0000" + "0000-0000-4000-8000-" + String.format("%012d", index)), 0, big))
        }
        val page = pageOf(alice, null)
        val p = page as Page
        assertEquals("exactly 99 records", 99, p.records.size)
        assertTrue("more true", p.more)
        assertEquals("next is change 99", 99L, p.next)
    }

    @Test
    fun oversizedSingleRecordStillEmitted() {
        // One record alone that does not fit the cap is still returned alone; a following small
        // row proves more is true and next is the oversized record's change.
        val huge = "B".repeat(5 * 1024 * 1024) // one record of 5 MiB alone exceeds the 4 MiB cap
        assertEquals("push huge", PushResult.Stored, push(alice, CID_ONE, 0, huge))
        assertEquals("push small", PushResult.Stored, push(alice, CID_TWO, 0, "two"))
        val page = pageOf(alice, null)
        val p = page as Page
        assertEquals("one record", 1, p.records.size)
        assertEquals("same client id", CID_ONE, p.records[0].clientId)
        assertTrue("more true", p.more)
        assertEquals("next is change 1", 1L, p.next)
    }

    @Test
    fun moreFalseWhenPageFits() {
        assertEquals("push", PushResult.Stored, push(alice, CID_ONE, 0, "one"))
        val page = pageOf(alice, null)
        val p = page as Page
        assertEquals("one record", 1, p.records.size)
        assertFalse("more false", p.more)
        assertEquals("next is head", 1L, p.next)
    }
}
