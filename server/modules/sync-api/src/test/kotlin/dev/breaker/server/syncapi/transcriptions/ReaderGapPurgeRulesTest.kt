package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// A first write of a client id always lands on the account's next change number, and a delete
// lands on the next one after that; nothing here copies a number from the code.
internal class ReaderGapPurgeRulesTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        store = SyncRouteTestSupport.newStore(temp)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    // The module idiom for a 36 character client id: the same digit on two accounts is a
    // different client id, because the rows are separate per account.
    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun pushOne(accountId: Long, n: Int) {
        val outcome = runBlocking { store.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: push $n stores", PushResult.Stored, outcome)
    }

    private fun dropOne(accountId: Long, n: Int) {
        val outcome = runBlocking { store.delete(accountId, clientId(n)) }
        assertEquals("sync-api: drop $n deletes", DeleteResult.Deleted, outcome)
    }

    private fun purgeDeleteRecords(accountId: Long, floor: Long): Long {
        return inTransaction(temp) { connection ->
            TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, floor)
        }
    }

    private fun storedPurgedThrough(accountId: Long): Long =
        inTransaction(temp) { connection ->
            val sql = "SELECT purged_through FROM accounts WHERE id = " + accountId
            TestDatabases.longs(connection, sql).single()
        }

    private fun leftoverDeleteRecords(accountId: Long): Long =
        inTransaction(temp) { connection ->
            TestDatabases.longs(
                connection,
                "SELECT count(*) FROM transcriptions WHERE account_id = $accountId AND deleted = 1",
            ).single()
        }

    // The account's head: every push or delete has written to it once, and nothing here can
    // exceed the number of writes made in the world.
    private fun headOf(accountId: Long): Long =
        inTransaction(temp) { connection ->
            val sql = "SELECT change_seq FROM accounts WHERE id = " + accountId
            TestDatabases.longs(connection, sql).single()
        }

    // A number above the account's head cannot come out of the routes, where change numbers only
    // rise; the direct write below is the only way to put that state in front of the purge.
    private fun plantPurgedThrough(accountId: Long, value: Long) {
        inTransaction(temp) { connection ->
            val statement = connection.prepareStatement(
                "UPDATE accounts SET purged_through = ? WHERE id = ?"
            )
            statement.setLong(1, value)
            statement.setLong(2, accountId)
            statement.executeUpdate()
        }
    }

    // World: pushes 1 to 3 are changes 1 to 3, drops 1 and 2 are changes 4 and 5, so the head is
    // 5 and both delete records sit under a floor of 5. The planted number 20 is above the head.
    @Test
    fun `a purge never lowers a purged through that already sits above every delete record`() {
        pushOne(alice, 1)
        pushOne(alice, 2)
        pushOne(alice, 3)
        dropOne(alice, 1)
        dropOne(alice, 2)
        plantPurgedThrough(alice, 20L)
        assertEquals("sync-api: head before the purge", 5L, headOf(alice))
        assertEquals("sync-api: highest removed", 5L, purgeDeleteRecords(alice, 5L))
        assertEquals("sync-api: planted number stays 20", 20L, storedPurgedThrough(alice))
        assertEquals("sync-api: no delete record left of alice", 0L, leftoverDeleteRecords(alice))
    }

    // Accept control for the test above: the same world without the planted number. The purge
    // still removes both delete records and the raise guard holds from 0, so the number becomes 5.
    @Test
    fun `a purge still raises a purged through from a lower value`() {
        pushOne(alice, 1)
        pushOne(alice, 2)
        pushOne(alice, 3)
        dropOne(alice, 1)
        dropOne(alice, 2)
        assertEquals("sync-api: highest removed", 5L, purgeDeleteRecords(alice, 5L))
        assertEquals("sync-api: number raised to the highest removed", 5L, storedPurgedThrough(alice))
        assertEquals("sync-api: no delete record left of alice", 0L, leftoverDeleteRecords(alice))
    }

    // World: both accounts start numbering at 1. Alice: pushes 1 and 2, then drops 1 and 2, so her
    // delete records are changes 3 and 4. Bob: the same four writes, so his delete records are also
    // changes 3 and 4. A floor of 4 covers every record on both accounts.
    @Test
    fun `a purge removes delete records of its own account and leaves another account alone`() {
        pushOne(alice, 1)
        pushOne(alice, 2)
        dropOne(alice, 1)
        dropOne(alice, 2)
        pushOne(bob, 1)
        pushOne(bob, 2)
        dropOne(bob, 1)
        dropOne(bob, 2)
        assertEquals("sync-api: highest removed on alice", 4L, purgeDeleteRecords(alice, 4L))
        assertEquals("sync-api: alice keeps no delete record", 0L, leftoverDeleteRecords(alice))
        assertEquals("sync-api: bob keeps both delete records", 2L, leftoverDeleteRecords(bob))
    }

    // World: pushes 1 and 2 are changes 1 and 2, drop 1 is change 3, pushes 3 and 4 are changes 4
    // and 5. The only delete record sits at 3, well under the floor of 5, so the purge must raise
    // the number to 3, not to the floor it was given.
    @Test
    fun `purged through rises to the highest delete record removed and not to the floor`() {
        pushOne(alice, 1)
        pushOne(alice, 2)
        dropOne(alice, 1)
        pushOne(alice, 3)
        pushOne(alice, 4)
        assertEquals("sync-api: number starts at 0", 0L, storedPurgedThrough(alice))
        assertEquals("sync-api: highest removed", 3L, purgeDeleteRecords(alice, 5L))
        assertEquals("sync-api: number is 3 and not the floor 5", 3L, storedPurgedThrough(alice))
        assertEquals("sync-api: no delete record left of alice", 0L, leftoverDeleteRecords(alice))
    }

    // Accept control for the test above: pushes 1 to 4 are changes 1 to 4, drop 1 is change 5, so
    // the highest removed is the floor itself. The number written there is 5 on the correct code.
    @Test
    fun `purged through equals the floor when the highest removed is the floor`() {
        pushOne(alice, 1)
        pushOne(alice, 2)
        pushOne(alice, 3)
        pushOne(alice, 4)
        dropOne(alice, 1)
        assertEquals("sync-api: highest removed", 5L, purgeDeleteRecords(alice, 5L))
        assertEquals("sync-api: number is 5", 5L, storedPurgedThrough(alice))
        assertEquals("sync-api: no delete record left of alice", 0L, leftoverDeleteRecords(alice))
    }
}
