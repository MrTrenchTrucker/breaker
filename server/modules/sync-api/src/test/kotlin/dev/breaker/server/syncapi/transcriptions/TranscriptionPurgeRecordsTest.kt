package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Every change number below comes from a real push or delete through the store. The first write of
// a row is an insert, so change 1 is always a live row; a delete record can only take a later number.
internal class TranscriptionPurgeRecordsTest : TempDatabaseTest() {

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

    // One digit makes the client id: the same digit on two accounts is the same client id on two rows.
    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun push(accountId: Long, n: Int) {
        val result = runBlocking { store.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n", PushResult.Stored, result)
    }

    private fun pushResave(accountId: Long, n: Int) {
        val result = runBlocking {
            store.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED, resave = true)
        }
        assertEquals("sync-api: setup re-save $n", PushResult.Stored, result)
    }

    private fun delete(accountId: Long, n: Int) {
        val result = runBlocking { store.delete(accountId, clientId(n)) }
        assertEquals("sync-api: setup delete $n", DeleteResult.Deleted, result)
    }

    private fun purge(accountId: Long, floor: Long): Long = inTransaction(temp) { connection ->
        TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, floor)
    }

    private fun changeNumbers(accountId: Long): List<Long> = inTransaction(temp) { connection ->
        TestDatabases.longs(
            connection,
            "SELECT change_no FROM transcriptions WHERE account_id = $accountId ORDER BY change_no",
        )
    }

    private fun purgedThrough(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT purged_through FROM accounts WHERE id = $accountId").single()
    }

    private fun changeSeqOf(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT change_seq FROM accounts WHERE id = $accountId").single()
    }

    // Raw SQL: a purged_through above the head is a state no route can reach, so the test sets it directly.
    private fun setPurgedThrough(accountId: Long, value: Long) {
        inTransaction(temp) { connection ->
            val sql = "UPDATE accounts SET purged_through = ? WHERE id = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, value)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
        }
    }

    // Changes: push 1 is 1, push 2 is 2, delete 1 is 3, delete 2 is 4. A floor of 3 removes change 3 only.
    @Test
    fun `purge at the floor removes the delete record at the floor and keeps the one above`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        delete(alice, 2)
        assertEquals("sync-api: removed number", 3L, purge(alice, 3L))
        assertEquals("sync-api: change numbers left", listOf(4L), changeNumbers(alice))
    }

    // Changes: push 1 is 1 (live), push 2 is 2, delete 2 is 3. A floor of 3 removes the delete record only.
    @Test
    fun `purge leaves a live row below the floor and removes only deleted rows`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 2)
        assertEquals("sync-api: removed number", 3L, purge(alice, 3L))
        assertEquals("sync-api: change numbers left", listOf(1L), changeNumbers(alice))
    }

    // Alice: push 1 is 1, push 2 is 2, delete 1 is 3. Bob: push 1 is 1, delete 1 is 2, below the floor of 3.
    @Test
    fun `purge leaves a delete record of another account below the floor`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        push(bob, 1)
        delete(bob, 1)
        assertEquals("sync-api: removed number", 3L, purge(alice, 3L))
        assertEquals("sync-api: bob change numbers", listOf(2L), changeNumbers(bob))
        assertEquals("sync-api: alice change numbers", listOf(2L), changeNumbers(alice))
    }

    // Changes: push 1 is 1, push 2 is 2, delete 1 is 3, push 3 is 4 (live). A floor of 4 removes change 3.
    // The highest number removed is 3, so purged_through must be 3, not the floor 4.
    @Test
    fun `purged_through becomes the highest number removed and not the floor`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        push(alice, 3)
        assertEquals("sync-api: purged_through before", 0L, purgedThrough(alice))
        assertEquals("sync-api: removed number", 3L, purge(alice, 4L))
        assertEquals("sync-api: purged_through after", 3L, purgedThrough(alice))
        assertEquals("sync-api: change numbers left", listOf(2L, 4L), changeNumbers(alice))
    }

    // Changes: pushes 1 to 4 are 1 to 4, delete 1 is 5. purged_through is set to 20 by SQL, which no
    // route can reach because the store never writes a number above the head. A purge of 5 must not lower it.
    @Test
    fun `purged_through is never lowered by a purge`() {
        push(alice, 1)
        push(alice, 2)
        push(alice, 3)
        push(alice, 4)
        delete(alice, 1)
        setPurgedThrough(alice, 20L)
        assertEquals("sync-api: removed number", 5L, purge(alice, 5L))
        assertEquals("sync-api: purged_through stays", 20L, purgedThrough(alice))
    }

    // Changes: push 1 is 1, push 2 is 2, delete 1 is 3. A floor of 2 has no delete record at or below it.
    // The accept control at the end uses the same world with a floor of 3, which removes change 3.
    @Test
    fun `purge with nothing below the floor returns 0 and changes no row`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        assertEquals("sync-api: nothing removed", 0L, purge(alice, 2L))
        assertEquals("sync-api: purged_through unchanged", 0L, purgedThrough(alice))
        assertEquals("sync-api: change numbers unchanged", listOf(2L, 3L), changeNumbers(alice))
        assertEquals("sync-api: control removed number", 3L, purge(alice, 3L))
    }

    // Changes: push 1 is 1, push 2 is 2, delete 1 is 3, so change_seq is 3 before the purge.
    @Test
    fun `purge writes no change number to the account`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        assertEquals("sync-api: change_seq before", 3L, changeSeqOf(alice))
        assertEquals("sync-api: removed number", 3L, purge(alice, 3L))
        assertEquals("sync-api: change_seq after", 3L, changeSeqOf(alice))
    }

    // Changes on each account: push 1 is 1, push 2 is 2, delete 1 is 3, delete 2 is 4, push 3 is 5,
    // delete 3 is 6. A floor of 4 removes changes 3 and 4 from alice and returns 4. Bob keeps all three.
    @Test
    fun `purge returns the highest removed number and leaves the other account alone`() {
        for (accountId in listOf(alice, bob)) {
            push(accountId, 1)
            push(accountId, 2)
            delete(accountId, 1)
            delete(accountId, 2)
            push(accountId, 3)
            delete(accountId, 3)
        }
        assertEquals("sync-api: returned number", 4L, purge(alice, 4L))
        assertEquals("sync-api: purged_through of alice", 4L, purgedThrough(alice))
        assertEquals("sync-api: alice change numbers left", listOf(6L), changeNumbers(alice))
        assertEquals("sync-api: bob change numbers", listOf(3L, 4L, 6L), changeNumbers(bob))
    }

    // Alice: push 1 is 1, push 2 is 2, delete 1 is 3, re-save 1 is 4 (live again; its old number 3
    // is below the floor). Bob, the control: push 1 is 1, push 2 is 2, delete 1 is 3, and a floor of
    // 4 removes that delete record.
    @Test
    fun `a re-saved delete record is kept even when its old number is at or below the floor`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        pushResave(alice, 1)
        push(bob, 1)
        push(bob, 2)
        delete(bob, 1)
        assertEquals("sync-api: control removed number", 3L, purge(bob, 4L))
        assertEquals("sync-api: alice removed number", 0L, purge(alice, 4L))
        assertEquals("sync-api: alice change numbers left", listOf(2L, 4L), changeNumbers(alice))
        assertEquals("sync-api: alice purged_through", 0L, purgedThrough(alice))
    }
}
