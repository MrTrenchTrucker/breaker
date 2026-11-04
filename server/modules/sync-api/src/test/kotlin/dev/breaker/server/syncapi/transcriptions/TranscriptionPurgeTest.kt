package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Deleting every transcription of one account writes no change number and leaves other accounts alone. */
internal class TranscriptionPurgeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private var first: Long = 0L
    private var target: Long = 0L

    // first (id 1) has two rows; target (id 2) has three, one of them deleted by the store (a tombstone row).
    @Before
    fun openWorld() {
        temp = openTemp()
        store = SyncRouteTestSupport.newStore(temp)
        first = TokenFixtures.register(temp, "first", 1).accountId
        target = TokenFixtures.register(temp, "target", 2).accountId
        push(first, 0)
        push(first, 1)
        push(target, 2)
        push(target, 3)
        push(target, 4)
        val deleted = runBlocking { store.delete(target, clientId(4)) }
        assertEquals("sync-api: setup delete", DeleteResult.Deleted, deleted)
    }

    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun push(accountId: Long, n: Int) {
        val result = runBlocking { store.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n", PushResult.Stored, result)
    }

    private fun purge(accountId: Long): Int =
        inTransaction(temp) { connection -> TranscriptionPurge.deleteAllOn(connection, accountId) }

    private fun rowsOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT count(*) FROM transcriptions WHERE account_id = $accountId").single()
    }

    private fun changeSeqOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT change_seq FROM accounts WHERE id = $accountId").single()
    }

    @Test
    fun `deleteAllOn removes every row of the account tombstones too and returns the count`() {
        assertEquals("sync-api: the count", 3, purge(target))
        assertEquals("sync-api: rows left for the account", 0L, rowsOf(target))
    }

    @Test
    fun `deleteAllOn leaves the rows of another account`() {
        purge(target)
        assertEquals("sync-api: rows of the other account", 2L, rowsOf(first))
    }

    @Test
    fun `deleteAllOn writes no change number and no tombstone`() {
        val before = changeSeqOf(target)
        purge(target)
        assertEquals("sync-api: change_seq of the account", before, changeSeqOf(target))
        assertEquals("sync-api: change_seq of the other account", 2L, changeSeqOf(first))
    }

    @Test
    fun `deleteAllOn for an id with no account returns 0 and deletes nothing`() {
        assertEquals("sync-api: the count", 0, purge(target + 1000L))
        assertEquals("sync-api: rows of the first account", 2L, rowsOf(first))
        assertEquals("sync-api: rows of the target account", 3L, rowsOf(target))
    }
}
