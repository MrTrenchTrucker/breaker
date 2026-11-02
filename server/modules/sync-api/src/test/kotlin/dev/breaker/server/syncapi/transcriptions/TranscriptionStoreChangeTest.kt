package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class TranscriptionStoreChangeTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        store = TranscriptionStore(temp.db)
        // The first registered account is the admin and the second a user.
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String): PushResult =
        runBlocking { store.push(accountId, clientId, keyVersion, encrypted) }

    private fun setKeyVersion(accountId: Long, keyVersion: Int) =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { s ->
                    s.setInt(1, keyVersion)
                    s.setLong(2, accountId)
                    s.executeUpdate()
                }
            }
        }

    private fun changeNo(accountId: Long, clientId: String): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT change_no FROM transcriptions WHERE account_id = ? AND client_id = ?",
            ).use { s ->
                s.setLong(1, accountId)
                s.setString(2, clientId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no row for $clientId" }
                    rows.getLong(1)
                }
            }
        }
    }

    private fun changeSeq(accountId: Long): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no account" }
                    rows.getLong(1)
                }
            }
        }
    }

    private fun markDeleted(accountId: Long, clientId: String) = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "UPDATE transcriptions SET deleted = 1, encrypted = '-' WHERE account_id = ? AND client_id = ?",
            ).use { s ->
                s.setLong(1, accountId)
                s.setString(2, clientId)
                s.executeUpdate()
            }
        }
    }

    @Test
    fun `new client ids take 1 2 3 on one account while another account starts at 1`() {
        assertEquals(
            "sync-api: alice's first push stores",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "one"),
        )
        assertEquals(
            "sync-api: alice's second push stores",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_TWO, 0, "two"),
        )
        assertEquals(
            "sync-api: alice's third push stores",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_THREE, 0, "three"),
        )
        assertEquals(
            "sync-api: alice CLIENT_ONE change_no",
            1L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals(
            "sync-api: alice CLIENT_TWO change_no",
            2L,
            changeNo(alice, TranscriptionFixtures.CLIENT_TWO),
        )
        assertEquals(
            "sync-api: alice CLIENT_THREE change_no",
            3L,
            changeNo(alice, TranscriptionFixtures.CLIENT_THREE),
        )
        assertEquals("sync-api: alice change_seq", 3L, changeSeq(alice))

        // Bob starts at 1 independently
        assertEquals(
            "sync-api: bob's push stores",
            PushResult.Stored,
            push(bob, TranscriptionFixtures.CLIENT_ONE, 0, "bob one"),
        )
        assertEquals(
            "sync-api: bob CLIENT_ONE change_no",
            1L,
            changeNo(bob, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals("sync-api: bob change_seq", 1L, changeSeq(bob))
    }

    @Test
    fun `a replace takes the next number and bumps change_seq`() {
        assertEquals(
            "sync-api: first push stores",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "first"),
        )
        assertEquals(
            "sync-api: CLIENT_ONE change_no after first",
            1L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals("sync-api: change_seq after first", 1L, changeSeq(alice))

        setKeyVersion(alice, 1)
        assertEquals(
            "sync-api: replace stores with next number",
            PushResult.Replaced,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "second"),
        )
        assertEquals(
            "sync-api: CLIENT_ONE change_no after replace",
            2L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals("sync-api: change_seq after replace", 2L, changeSeq(alice))

        setKeyVersion(alice, 2)
        assertEquals(
            "sync-api: second replace stores",
            PushResult.Replaced,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 2, "third"),
        )
        assertEquals(
            "sync-api: CLIENT_ONE change_no after second replace",
            3L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals("sync-api: change_seq after second replace", 3L, changeSeq(alice))
    }

    @Test
    fun `a no-op takes no number and leaves change_seq unchanged`() {
        assertEquals(
            "sync-api: first push stores",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "first"),
        )
        val beforeSeq = changeSeq(alice)
        assertEquals(
            "sync-api: same pair same version is a no-op",
            PushResult.NoOp,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "again"),
        )
        assertEquals(
            "sync-api: CLIENT_ONE change_no after no-op",
            1L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals("sync-api: change_seq unchanged by no-op", beforeSeq, changeSeq(alice))
    }

    @Test
    fun `lower key version takes no number`() {
        setKeyVersion(alice, 1)
        assertEquals(
            "sync-api: push at account version stores",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "v1"),
        )
        assertEquals("sync-api: change_seq after v1", 1L, changeSeq(alice))
        assertEquals(
            "sync-api: lower key version is refused",
            PushResult.RefusedLower,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "v0"),
        )
        assertEquals("sync-api: change_seq unchanged by refusal", 1L, changeSeq(alice))
        assertEquals(
            "sync-api: CLIENT_ONE change_no unchanged by refusal",
            1L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
    }

    @Test
    fun `above-account key version takes no number`() {
        assertEquals(
            "sync-api: push above account version is refused",
            PushResult.RefusedAboveAccount,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "x"),
        )
        assertEquals("sync-api: change_seq unchanged by above-account refusal", 0L, changeSeq(alice))
    }

    @Test
    fun `a deleted stored row at a lower version answers NoOp to a higher push with no number taken`() {
        assertEquals(
            "sync-api: push at version 0 stores live row",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "live"),
        )
        assertEquals(
            "sync-api: CLIENT_ONE change_no after store",
            1L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        markDeleted(alice, TranscriptionFixtures.CLIENT_ONE)
        setKeyVersion(alice, 1)
        val beforeSeq = changeSeq(alice)
        assertEquals(
            "sync-api: a push at the higher version 1 to the deleted row is a no-op, not a replace",
            PushResult.NoOp,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "after delete"),
        )
        assertEquals(
            "sync-api: CLIENT_ONE change_no unchanged after delete no-op",
            1L,
            changeNo(alice, TranscriptionFixtures.CLIENT_ONE),
        )
        assertEquals("sync-api: change_seq unchanged by delete no-op", beforeSeq, changeSeq(alice))
    }

    @Test
    fun `the first registered account is ADMIN`() = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT role FROM accounts ORDER BY id LIMIT 1").use { s ->
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no accounts" }
                    assertEquals(
                        "sync-api: first account is ADMIN",
                        Role.ADMIN.dbValue,
                        rows.getString(1),
                    )
                }
            }
        }
    }
}
