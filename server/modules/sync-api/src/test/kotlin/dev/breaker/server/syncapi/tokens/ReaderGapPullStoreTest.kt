package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ReaderGapPullStoreTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private fun newStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun newTranscriptionStore(temp: TempDatabase): TranscriptionStore =
        SyncRouteTestSupport.newStore(temp, clock)

    private fun issueSession(store: TokenStore, accountId: Long): Long =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) }).tokenId

    private fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    private fun pushRow(store: TranscriptionStore, accountId: Long, clientId: String) =
        runBlocking { store.push(accountId, clientId, 0, SyncRouteTestSupport.ENCRYPTED) }

    private fun deleteRow(store: TranscriptionStore, accountId: Long, clientId: String) =
        runBlocking { store.delete(accountId, clientId) }

    private fun setNumber(store: TokenStore, temp: TempDatabase, tokenId: Long,
                          accountId: Long, since: Long) {
        inTransaction(temp) { connection ->
            store.setPulledChangeOn(connection, tokenId, accountId, since)
        }
    }

    @Test
    fun `setting another account's pulled change leaves this session's row untouched`() {
        // setPulledChangeOn forgets the account check: a session of another account writes
        // with this account's id. The original matches no row (null); the break stores on
        // the wrong row.
        // World: Alice pushes 7 -> head 7; Bob pushes 3 -> head 3.
        // Bob's session pulls at since 5 with Alice's account id:
        // original -> null; break -> stores MIN(5, Alice's head 7) = 5 on Bob's row.
        val temp = openTemp()
        val alice = TokenFixtures.register(temp, "alice", 1)
        val bob = TokenFixtures.register(temp, "bob", 2)
        val store = newStore(temp)

        pushRows(temp, alice.accountId, 7)
        pushRows(temp, bob.accountId, 3)

        val aliceSession = issueSession(store, alice.accountId)
        val bobSession = issueSession(store, bob.accountId)

        val result = inTransaction(temp) { connection ->
            store.setPulledChangeOn(connection, bobSession, alice.accountId, 5L)
        }

        assertNull("sync-api: another account's pulled change returns null", result)
        assertNull("sync-api: Bob's stored number stays null",
            inTransaction(temp) { connection ->
                connection.prepareStatement("SELECT last_pulled_change FROM tokens WHERE id = ?").use { s ->
                    s.setLong(1, bobSession)
                    s.executeQuery().use { rows ->
                        if (!rows.next()) null else {
                            val v = rows.getLong(1)
                            if (rows.wasNull()) null else v
                        }
                    }
                }
            })
    }

    @Test
    fun `a pulled change on the same account stores the number`() {
        // Accept control: Bob pulls at since 5 with Bob's own account id.
        // Both original and break: WHERE id = bobSession AND account_id = Bob ->
        // stores MIN(5, Bob's head 3) = 3 and returns 3.
        val temp = openTemp()
        val bob = TokenFixtures.register(temp, "bob", 2)
        val store = newStore(temp)
        pushRows(temp, bob.accountId, 3)
        val bobSession = issueSession(store, bob.accountId)

        val result = inTransaction(temp) { connection ->
            store.setPulledChangeOn(connection, bobSession, bob.accountId, 5L)
        }

        assertEquals("sync-api: same account stores MIN(5, head 3) = 3", 3L, result)
    }

    @Test
    fun `a full list pull purges the delete records under the floor`() {
        // Full list pull (since null) computes floor and purges.
        // World: Alice pushes c1=1, deletes c1=2 (delete record at change 2).
        // Earlier setPulledChangeOn at since 2 -> number stored = MIN(2, head 2) = 2; floor = 2.
        // Original purges delete at change 2: count 0, purged_through 2.
        // Break: since null -> floor null -> no purge: count 1, purged_through 0.
        val temp = openTemp()
        val alice = TokenFixtures.register(temp, "alice", 1)
        val store = newTranscriptionStore(temp)
        val tokenStore = newStore(temp)
        val session = issueSession(tokenStore, alice.accountId)

        pushRow(store, alice.accountId, clientId(1))   // change 1
        deleteRow(store, alice.accountId, clientId(1)) // change 2, delete record

        setNumber(tokenStore, temp, session, alice.accountId, 2L)

        runBlocking { store.pull(tokenStore, alice.accountId, session, null) }

        val purged = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, alice.accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }

        assertEquals("sync-api: full list pull purges, purged_through = 2", 2L, purged)
    }

    @Test
    fun `the purge stops at the floor of the slowest session and not at the since of the caller`() {
        // Break uses since instead of floor. Both sessions are Alice's.
        // World: push c1=1, push c2=2; slow session sets number MIN(2, head 2) = 2;
        // delete c1=3 (delete record at change 3); push c3=4; delete c2=5
        // (delete record at change 5); head 5. Pulling session pulls since 5.
        // Floor = lowest = 2. Original purges at floor 2 (nothing at or below 2):
        // count stays 2, purged_through 0. Break purges at since 5: count 0, purged_through 5.
        val temp = openTemp()
        val alice = TokenFixtures.register(temp, "alice", 1)
        val store = newTranscriptionStore(temp)
        val tokenStore = newStore(temp)
        val slowSession = issueSession(tokenStore, alice.accountId)
        val pullSession = issueSession(tokenStore, alice.accountId)

        pushRow(store, alice.accountId, clientId(1))   // change 1
        pushRow(store, alice.accountId, clientId(2))   // change 2
        setNumber(tokenStore, temp, slowSession, alice.accountId, 2L)
        deleteRow(store, alice.accountId, clientId(1)) // change 3, delete record
        pushRow(store, alice.accountId, clientId(3))   // change 4
        deleteRow(store, alice.accountId, clientId(2)) // change 5, delete record

        runBlocking { store.pull(tokenStore, alice.accountId, pullSession, 5L) }

        val count = inTransaction(temp) { connection ->
            val sql = "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            connection.prepareStatement(sql).use { s ->
                s.setLong(1, alice.accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }
        val purged = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, alice.accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }

        assertEquals("sync-api: purge at floor 2 removes nothing, count stays 2", 2L, count)
        assertEquals("sync-api: purge at floor 2 raises nothing, purged_through = 0", 0L, purged)
    }

    @Test
    fun `the purge runs at floor 5 when the slow session catches up`() {
        // Accept control for n08: both original and break purge when the floor is high enough.
        // World: same as n08 test (slow at 2, deletes at 3 and 5, head 5).
        // Slow session catches up: setNumber(slow, 5) floor = 5.
        // Pulling session pulls since 5: original floor 5 -> purge at 5: delete records removed -> count 0.
        // Break: since 5 -> purge at 5: count 0, purged_through 5. Same result: both green.
        val temp = openTemp()
        val alice = TokenFixtures.register(temp, "alice", 1)
        val store = newTranscriptionStore(temp)
        val tokenStore = newStore(temp)
        val slowSession = issueSession(tokenStore, alice.accountId)
        val pullSession = issueSession(tokenStore, alice.accountId)

        pushRow(store, alice.accountId, clientId(1))   // change 1
        pushRow(store, alice.accountId, clientId(2))   // change 2
        setNumber(tokenStore, temp, slowSession, alice.accountId, 2L)
        deleteRow(store, alice.accountId, clientId(1)) // change 3, delete record
        pushRow(store, alice.accountId, clientId(3))   // change 4
        deleteRow(store, alice.accountId, clientId(2)) // change 5, delete record

        // Slow session catches up: floor now 5.
        setNumber(tokenStore, temp, slowSession, alice.accountId, 5L)

        runBlocking { store.pull(tokenStore, alice.accountId, pullSession, 5L) }

        val count = inTransaction(temp) { connection ->
            val sql = "SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1"
            connection.prepareStatement(sql).use { s ->
                s.setLong(1, alice.accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }
        val purged = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, alice.accountId)
                s.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val v = rows.getLong(1)
                        if (rows.wasNull()) null else v
                    }
                }
            }
        }

        assertEquals("sync-api: purge at floor 5 removes both, count stays 0", 0L, count)
        assertEquals("sync-api: purge at floor 5 raises purged_through to 5", 5L, purged)
    }

    private fun pushRows(temp: TempDatabase, accountId: Long, count: Int) {
        runBlocking {
            val store = SyncRouteTestSupport.newStore(temp, clock)
            repeat(count) { i ->
                store.push(accountId, clientId(i), 0, SyncRouteTestSupport.ENCRYPTED)
            }
        }
    }
}
