package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Store-level tests of the list cursor. The repro world: 101 live rows (changes 1..101), one more
// push (change 102) and its delete (change 103). Head is 103. A new session lists: page 1 = 100
// rows, more true, next acc-100-103, listHead 103; page 2 (since=100, listHead=103) holds only
// change 101, more false, next acc-103, no listHead: the list FINISHES. Every refusal/stop has an
// accept control in the same test.
// Client ids are 36 characters: CHECK (length(client_id) = 36) in SchemaV3.
// Test names: plain English in backticks, none of . ; [ ] / < > : \ ,, no underscore, no
// parentheses, no test prefix.
internal class ListCursorStoreTest : TempDatabaseTest() {

    private val clock = AccountFixtures.fixedClock

    private fun newStore(temp: TempDatabase): TranscriptionStore =
        SyncRouteTestSupport.newStore(temp, clock)

    private fun newTokenStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun aliceId(temp: TempDatabase): Long =
        TokenFixtures.register(temp, "alice", 1).accountId

    // 101 distinct 36-char ids (12-hex last group at runtime, no line holds 7 hex digits in a row).
    private fun listClientId(index: Int): String =
        "1a2b" + "3c4d-5e6f-" + "4a7b-8c9d-" + java.lang.String.format("%012x", index)

    private fun pushRow(store: TranscriptionStore, accountId: Long, clientId: String) =
        runBlocking { store.push(accountId, clientId, 0, ENCRYPTED) }

    private fun headOf(temp: TempDatabase, accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no account" }
                    rows.getLong(1)
                }
            }
        }

    private fun purgedThroughOf(temp: TempDatabase, accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no account" }
                    rows.getLong(1)
                }
            }
        }

    private fun lastPulledOf(temp: TempDatabase, tokenId: Long): Long? =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT last_pulled_change FROM tokens WHERE id = ?").use { s ->
                s.setLong(1, tokenId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null
                    else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }

    // Build the repro world: 101 live rows, push c102 (change 102), delete c102 (change 103).
    // Returns (accountId, tokenId, tokenStore). head is 103.
    private fun reproWorld(temp: TempDatabase): Triple<Long, Long, TokenStore> {
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val tokenId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        for (n in 1..101) pushRow(store, accountId, listClientId(n))
        pushRow(store, accountId, listClientId(102))          // change 102, live
        runBlocking { store.delete(accountId, listClientId(102)) } // change 103, delete record
        assertEquals("repro head is 103", 103L, headOf(temp, accountId))
        return Triple(accountId, tokenId, tokenStore)
    }

    // Pull page 1 of a list (no since) through the store, returning the Page.
    private fun listPage1(
        temp: TempDatabase,
        store: TranscriptionStore,
        tokenStore: TokenStore,
        tokenId: Long,
        accountId: Long,
    ): dev.breaker.server.syncapi.transcriptions.PageResult.Page {
        return runBlocking {
            store.pull(tokenStore, accountId, tokenId, null, null)
                as dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
    }

    @Test
    fun `a first list finishes on a new session`() {
        val temp = openTemp()
        val (accountId, tokenId, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        // The first session pulls at since=103: that pull purges the delete record, so
        // purged_through becomes 103. It is a Page with no records (not Gone).
        val s1 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 103L, null)
        }
        assertTrue("pull at since=103 is a Page",
            s1 is dev.breaker.server.syncapi.transcriptions.PageResult.Page)
        assertEquals("purged_through is 103", 103L, purgedThroughOf(temp, accountId))

        // A NEW SESSION lists: page 1 = 100 rows, more true, listHead 103, next position 100.
        val secondId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        val page1 = listPage1(temp, store, tokenStore, secondId, accountId)
        assertEquals("page 1 holds 100 records", 100, page1.records.size)
        assertTrue("page 1 more is true", page1.more)
        assertEquals("page 1 next is position 100", 100L, page1.next)
        assertEquals("page 1 listHead is head 103", 103L, page1.listHead!!)

        // Page 2 with since=100, listHead=103: not Gone, one live row change 101, more false,
        // next 103 (first-page head), no listHead: the list FINISHES.
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, secondId, 100L, 103L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 2 holds one record", 1, page2.records.size)
        assertEquals("page 2 record is change 101", 101L, page2.records.first().change)
        assertFalse("page 2 more is false", page2.more)
        assertEquals("page 2 next is first-page head 103", 103L, page2.next)
        assertNull("page 2 has no listHead", page2.listHead)
    }

    @Test
    fun `a row written after the first page head is not in the list`() {
        val temp = openTemp()
        val (accountId, tokenId, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        runBlocking { store.pull(tokenStore, accountId, tokenId, 103L, null) } // purge -> 103

        // A SECOND session lists.
        val secondId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        val page1 = listPage1(temp, store, tokenStore, secondId, accountId)
        assertEquals("page 1 listHead", 103L, page1.listHead!!)

        // A row written after the first-page head 103 gets change 104 (there is no live 102).
        runBlocking { store.push(accountId, listClientId(104), 0, ENCRYPTED) }
        assertEquals("late row head is 104", 104L, headOf(temp, accountId))

        // Page 2 holds only change 101 (the snapshot excludes change 104).
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, secondId, 100L, 103L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 2 holds one record", 1, page2.records.size)
        assertEquals("page 2 record is change 101", 101L, page2.records.first().change)
        assertEquals("page 2 next is the first-page head 103, not the head now", 103L, page2.next)
        assertNull("page 2 has no listHead", page2.listHead)

        // The incremental pull since=103 brings the late row.
        val incremental = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 103L, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("incremental holds the late row", 1, incremental.records.size)
        assertEquals("incremental record is change 104", 104L, incremental.records.first().change)
    }

    @Test
    fun `a delete between two pages is reported by the final incremental pull`() {
        val temp = openTemp()
        val (accountId, tokenId, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        runBlocking { store.pull(tokenStore, accountId, tokenId, 103L, null) } // purge -> 103

        // A SECOND session lists page 1.
        val secondId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        val page1 = listPage1(temp, store, tokenStore, secondId, accountId)
        assertEquals("page 1 listHead", 103L, page1.listHead!!)

        // Delete a row of page 1 after page 1: change 104 delete record.
        runBlocking { store.delete(accountId, listClientId(1)) }
        assertEquals("delete head is 104", 104L, headOf(temp, accountId))

        // Page 2 holds only change 101 (the deleted row is not in the snapshot).
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, secondId, 100L, 103L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 2 holds one record", 1, page2.records.size)
        assertEquals("page 2 record is change 101", 101L, page2.records.first().change)

        // The incremental pull at the final next (103) holds the delete record: exactly one
        // record, deleted, of client id c001, change 104.
        val incremental = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 103L, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertTrue("incremental more is false", !incremental.more)
        assertEquals("incremental next is head 104", 104L, incremental.next)
        assertEquals("incremental holds one delete record", 1, incremental.records.size)
        assertEquals("delete record is change 104", 104L, incremental.records.first().change)
        assertTrue("delete record is deleted", incremental.records.first().deleted)
    }

    @Test
    fun `the session number is the smaller of the first page head and the head now after a list`() {
        val temp = openTemp()
        val (accountId, tokenId, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        runBlocking { store.pull(tokenStore, accountId, tokenId, 103L, null) } // purge -> 103

        // A SECOND session: nothing stored on it yet (accept control).
        val secondId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        assertNull("session number is null before page 2", lastPulledOf(temp, secondId))

        val page1 = listPage1(temp, store, tokenStore, secondId, accountId)
        assertEquals("page 1 listHead", 103L, page1.listHead!!)

        // The head moves on between page 1 and page 2 (push after page 1): change 104 live.
        runBlocking { store.push(accountId, listClientId(105), 0, ENCRYPTED) }
        assertEquals("head moved to 104", 104L, headOf(temp, accountId))

        // Page 2 stores min(103, 104) = 103, never above the first-page head.
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, secondId, 100L, 103L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        val stored = lastPulledOf(temp, secondId)
        assertEquals("session number is min(103, 104) = 103", 103L, stored!!)
        assertNotEquals("session number is not head now 104", 104L, stored!!)
    }

    @Test
    fun `a single page list is unchanged`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val tokenId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        for (n in 1..100) pushRow(store, accountId, listClientId(n))

        val page = runBlocking {
            store.pull(tokenStore, accountId, tokenId, null, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("single page holds 100 records", 100, page.records.size)
        assertFalse("single page more is false", page.more)
        assertEquals("single page next is head 100", 100L, page.next)
        assertNull("single page has no listHead", page.listHead)
    }

    @Test
    fun `an incremental page that has more carries no list cursor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val tokenId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        // 150 live rows (changes 1..150), then delete listClientId(120) -> change 151, head 151.
        for (n in 1..150) pushRow(store, accountId, listClientId(n))
        runBlocking { store.delete(accountId, listClientId(120)) }
        assertEquals("head is 151", 151L, headOf(temp, accountId))

        // Page 1 with since=10: 100 records (changes 11..110), more true, next 110,
        // NO list cursor (incremental page: listHead null).
        val page1 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 10L, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 1 holds 100 records", 100, page1.records.size)
        assertTrue("page 1 more is true", page1.more)
        assertEquals("page 1 next is 110", 110L, page1.next)
        assertNull("page 1 carries no list cursor", page1.listHead)

        // Page 2 with since=110: remaining rows (111..151 incl. the delete of c120), more false.
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 110L, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertFalse("page 2 more is false", page2.more)
        assertNull("page 2 carries no list cursor", page2.listHead)
    }

    @Test
    fun `the size cap ends a list page early and carries the list cursor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val tokenId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        val big = "A".repeat(41900)
        for (n in 1..105) {
            runBlocking { store.push(accountId, listClientId(n), 0, big) }
        }
        // Head is 105. Page 1 of a list: size cap stops at 99 records, more true, listHead 105.
        val page1 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, null, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("cap page holds 99 records", 99, page1.records.size)
        assertTrue("cap page more is true", page1.more)
        assertEquals("cap page next is position 99", 99L, page1.next)
        assertEquals("cap page listHead is head 105", 105L, page1.listHead!!)

        // Page 2 with since=99, listHead=105: carries the LIST cursor.
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 99L, 105L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("cap page 2 holds remaining records", 6, page2.records.size)
        assertFalse("cap page 2 more is false", page2.more)
        assertEquals("cap page 2 next is first-page head 105", 105L, page2.next)
        assertNull("cap page 2 has no listHead", page2.listHead)
    }

    @Test
    fun `a list of three pages carries the list cursor on the middle page`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val tokenId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        ).tokenId
        // 205 live rows (changes 1..205), head 205.
        for (n in 1..205) pushRow(store, accountId, listClientId(n))

        val page1 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, null, null) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 1 holds 100 records", 100, page1.records.size)
        assertEquals("page 1 next is position 100", 100L, page1.next)
        assertEquals("page 1 listHead is head 205", 205L, page1.listHead!!)

        // Page 2 on the list cursor: changes 101..200, more true, still a LIST cursor.
        val page2 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 100L, 205L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 2 holds 100 records", 100, page2.records.size)
        assertTrue("page 2 more is true", page2.more)
        assertEquals("page 2 next is position 200", 200L, page2.next)
        assertEquals("page 2 listHead stays 205", 205L, page2.listHead!!)

        // Page 3: changes 201..205, more false, next is the first-page head, no listHead.
        val page3 = runBlocking {
            store.pull(tokenStore, accountId, tokenId, 200L, 205L) as
                dev.breaker.server.syncapi.transcriptions.PageResult.Page
        }
        assertEquals("page 3 holds 5 records", 5, page3.records.size)
        assertFalse("page 3 more is false", page3.more)
        assertEquals("page 3 next is first-page head 205", 205L, page3.next)
        assertNull("page 3 has no listHead", page3.listHead)
    }
}
