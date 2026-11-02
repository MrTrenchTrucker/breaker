package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.TranscriptionFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

internal class TranscriptionStoreChangeOrderTest : TempDatabaseTest() {

    @Test
    fun `a push above the account version to a deleted row answers RefusedAboveAccount`() {
        val temp = openTemp()
        val store = TranscriptionStore(temp.db)
        val alice = TokenFixtures.register(temp, "alice", 1).accountId

        // Pins the order decided in CHANGES.md: above-account refusal runs before the
        // deleted check, so a push above the account version to a deleted row answers
        // RefusedAboveAccount, not NoOp. This order is unchanged from before this slice.
        runBlocking { store.push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "live") }
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "UPDATE transcriptions SET deleted = 1, encrypted = '-' WHERE account_id = ? AND client_id = ?",
                ).use { s ->
                    s.setLong(1, alice)
                    s.setString(2, TranscriptionFixtures.CLIENT_ONE)
                    s.executeUpdate()
                }
            }
        }

        assertEquals(
            "sync-api: above-account to deleted row is RefusedAboveAccount",
            PushResult.RefusedAboveAccount,
            runBlocking { store.push(alice, TranscriptionFixtures.CLIENT_ONE, 2, "above deleted") },
        )
        assertEquals(
            "sync-api: change_seq unchanged by above-account-to-deleted refusal",
            1L,
            changeSeq(temp, alice),
        )
    }

    @Test
    fun `a failed insert after the change_seq bump rolls back with no change_seq move and no row exists`() {
        val temp = openTemp()
        val store = TranscriptionStore(temp.db)
        val alice = TokenFixtures.register(temp, "alice", 1).accountId

        // Force a real failure on insert AFTER the bump: a trigger that raises on any
        // insert into transcriptions. push() bumps change_seq first, then inserts; the
        // trigger fires on the insert, the whole transaction rolls back, so change_seq
        // must not move and no row may exist.
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    """
                    CREATE TRIGGER fail_insert_on_transcriptions BEFORE INSERT ON transcriptions
                    BEGIN
                      SELECT RAISE(ABORT, 'forced insert failure');
                    END
                    """.trimIndent()
                ).use { it.executeUpdate() }
            }
        }

        val refusal: Exception? = try {
            runBlocking { store.push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "will fail") }
            null
        } catch (failure: Exception) {
            failure
        }
        assertEquals(
            "sync-api: the push must throw because the insert is refused",
            true,
            refusal != null,
        )
        assertEquals(
            "sync-api: change_seq did not move after failed push",
            0L,
            changeSeq(temp, alice),
        )
        assertEquals(
            "sync-api: no row exists after failed push",
            0L,
            rowCount(temp, alice, TranscriptionFixtures.CLIENT_ONE),
        )
    }

    private fun changeSeq(temp: dev.breaker.server.syncapi.db.TempDatabase, accountId: Long): Long =
        runBlocking {
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

    private fun rowCount(temp: dev.breaker.server.syncapi.db.TempDatabase, accountId: Long, clientId: String): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM transcriptions WHERE account_id = ? AND client_id = ?",
                ).use { s ->
                    s.setLong(1, accountId)
                    s.setString(2, clientId)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no count" }
                        rows.getLong(1)
                    }
                }
            }
        }
}
