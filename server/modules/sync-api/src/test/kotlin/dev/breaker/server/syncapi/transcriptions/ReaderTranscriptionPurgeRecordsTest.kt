package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ReaderTranscriptionPurgeRecordsTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun newStore(temp: TempDatabase): TranscriptionStore =
        SyncRouteTestSupport.newStore(temp, clock)

    private fun newTokenStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun aliceId(temp: TempDatabase): Long =
        TokenFixtures.register(temp, "alice", 1).accountId

    private fun pushRow(store: TranscriptionStore, accountId: Long, clientId: String) =
        runBlocking { store.push(accountId, clientId, 0, SyncRouteTestSupport.ENCRYPTED) }

    private fun deleteRow(store: TranscriptionStore, accountId: Long, clientId: String) =
        runBlocking { store.delete(accountId, clientId) }

    private fun purgedThrough(temp: TempDatabase, accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no account" }
                    rows.getLong(1)
                }
            }
        }

    private fun deleteRecordCount(temp: TempDatabase, accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    rows.getLong(1)
                }
            }
        }

    private fun liveRowCount(temp: TempDatabase, accountId: Long): Long =
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 0"
            ).use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no count row" }
                    rows.getLong(1)
                }
            }
        }

    // change numbers: c1 push=1, c2 push=2, c3 push=3, delete c1=4, delete c2=5. Head is 5.
    @Test
    fun `purge removes delete records at or below the floor and raises purged through`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val sessionId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }).tokenId
        pushRow(store, accountId, clientId(1))
        pushRow(store, accountId, clientId(2))
        pushRow(store, accountId, clientId(3))
        deleteRow(store, accountId, clientId(1)) // change 4
        deleteRow(store, accountId, clientId(2)) // change 5
        // Pull at since 5: head is 5, so the store clamps to min(5, 5) = 5. Floor = 5.
        inTransaction(temp) { connection ->
            tokenStore.setPulledChangeOn(connection, sessionId, accountId, 5L)
            assertEquals("sync-api: floor of one session at 5 is 5", 5L,
                tokenStore.pullFloorOn(connection, accountId))
            val removed = TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 5)
            assertEquals("sync-api: highest removed is 5", 5L, removed)
        }
        assertEquals("sync-api: purge removed both delete records", 0L, deleteRecordCount(temp, accountId))
        assertEquals("sync-api: live row c3 untouched", 1L, liveRowCount(temp, accountId))
        assertEquals("sync-api: purged_through = highest removed = 5", 5L, purgedThrough(temp, accountId))
    }

    @Test
    fun `purge at exactly the floor removes the record at the floor`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        pushRow(store, accountId, clientId(1)) // change 1
        pushRow(store, accountId, clientId(2)) // change 2
        deleteRow(store, accountId, clientId(1)) // change 3 (delete record at 3)
        pushRow(store, accountId, clientId(3)) // change 4
        deleteRow(store, accountId, clientId(2)) // change 5 (delete record at 5)
        val tokenStore = newTokenStore(temp)
        val sessionId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }).tokenId
        inTransaction(temp) { connection ->
            tokenStore.setPulledChangeOn(connection, sessionId, accountId, 3)
            assertEquals("sync-api: floor = 3", 3L, tokenStore.pullFloorOn(connection, accountId))
            val removed = TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 3)
            assertEquals("sync-api: record at floor 3 removed, highest = 3", 3L, removed)
        }
        assertEquals("sync-api: delete record at floor removed", 1L, deleteRecordCount(temp, accountId))
        assertEquals("sync-api: purged_through = 3", 3L, purgedThrough(temp, accountId))
    }

    @Test
    fun `purge never touches live rows`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        pushRow(store, accountId, clientId(1)) // change 1 (live row at/below the floor)
        pushRow(store, accountId, clientId(2)) // change 2 (live row at/below the floor)
        deleteRow(store, accountId, clientId(1)) // change 3 (delete record at floor 3)
        val tokenStore = newTokenStore(temp)
        val sessionId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }).tokenId
        inTransaction(temp) { connection ->
            tokenStore.setPulledChangeOn(connection, sessionId, accountId, 3L)
            assertEquals("sync-api: floor = 3", 3L, tokenStore.pullFloorOn(connection, accountId))
            TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 3)
        }
        assertEquals("sync-api: delete record removed", 0L, deleteRecordCount(temp, accountId))
        assertEquals("sync-api: live row c2 untouched", 1L, liveRowCount(temp, accountId))
    }

    @Test
    fun `purged through never goes down`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        pushRow(store, accountId, clientId(1)) // change 1
        deleteRow(store, accountId, clientId(1)) // change 2 (delete record)
        val tokenStore = newTokenStore(temp)
        val sessionId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }).tokenId
        var first: Long? = null
        var second: Long? = null
        inTransaction(temp) { connection ->
            tokenStore.setPulledChangeOn(connection, sessionId, accountId, 2L)
            assertEquals("sync-api: floor = 2", 2L, tokenStore.pullFloorOn(connection, accountId))
            first = TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 2)
            second = TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 1)
        }
        assertEquals("sync-api: first purge raises purged_through to 2", 2L, first)
        assertEquals("sync-api: second purge removes nothing", 0L, second)
        assertEquals("sync-api: purged_through stayed 2, never lowered", 2L, purgedThrough(temp, accountId))
    }

    @Test
    fun `purge at floor zero removes nothing when there are no delete records`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        pushRow(store, accountId, clientId(1)) // change 1, live row only
        val tokenStore = newTokenStore(temp)
        val sessionId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }).tokenId
        inTransaction(temp) { connection ->
            tokenStore.setPulledChangeOn(connection, sessionId, accountId, 0L)
            assertEquals("sync-api: floor = 0", 0L, tokenStore.pullFloorOn(connection, accountId))
            val removed = TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 0)
            assertEquals("sync-api: no delete records, removes nothing", 0L, removed)
        }
        assertEquals("sync-api: purged_through stays 0", 0L, purgedThrough(temp, accountId))
        assertEquals("sync-api: live row untouched", 1L, liveRowCount(temp, accountId))
    }

    @Test
    fun `purge does not count agent tokens for the floor`() {
        val temp = openTemp()
        val accountId = aliceId(temp)
        val store = newStore(temp)
        pushRow(store, accountId, clientId(1)) // change 1
        deleteRow(store, accountId, clientId(1)) // change 2 (delete record)
        val tokenStore = newTokenStore(temp)
        val sessionId = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }).tokenId
        TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent", null) })
        // Session pulls at since 2: floor = 2. The agent token (no number) does not pull it down to null.
        inTransaction(temp) { connection ->
            tokenStore.setPulledChangeOn(connection, sessionId, accountId, 2L)
            assertEquals("sync-api: agent token ignored, floor = 2", 2L,
                tokenStore.pullFloorOn(connection, accountId))
            val removed = TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, 2)
            assertEquals("sync-api: record at floor removed", 2L, removed)
        }
        // Accept control: with no live session number, the floor is null and no purge runs.
        val temp2 = openTemp()
        val accountId2 = aliceId(temp2)
        val store2 = newStore(temp2)
        pushRow(store2, accountId2, clientId(1))
        deleteRow(store2, accountId2, clientId(1))
        val tokenStore2 = newTokenStore(temp2)
        TokenFixtures.issuedOf(
            runBlocking { tokenStore2.issueSession(accountId2) }).tokenId
        TokenFixtures.issuedOf(
            runBlocking { tokenStore2.issueAgent(accountId2, AgentScope.TRANSCRIBE, "agent", null) })
        inTransaction(temp2) { connection ->
            assertNull("sync-api: no number set means no floor",
                tokenStore2.pullFloorOn(connection, accountId2))
        }
    }
}
