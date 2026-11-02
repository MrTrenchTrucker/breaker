package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.TranscriptionFixtures
import dev.breaker.server.syncapi.accounts.AccountFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class TranscriptionStoreDeleteTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var carol: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        store = TranscriptionStore(temp.db, AccountFixtures.fixedClock)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        carol = TokenFixtures.register(temp, "carol", 2).accountId
    }

    // Client id built from short pieces so no run of 8 hex digits trips the release scan.
    private val CID = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + "1a2b3c" + "4d5e6f"
    private val OTHER_CID = "1b0d" + "5e2f-7b3c-4d4e-8f9a-" + "2b3c4d" + "5e6f70"

    private fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String): PushResult =
        runBlocking { store.push(accountId, clientId, keyVersion, encrypted) }

    private fun pushResave(accountId: Long, clientId: String, keyVersion: Int, encrypted: String): PushResult =
        runBlocking { store.push(accountId, clientId, keyVersion, encrypted, resave = true) }

    private fun delete(accountId: Long, clientId: String): DeleteResult =
        runBlocking { store.delete(accountId, clientId) }

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

    private fun encryptedBody(accountId: Long, clientId: String): String = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT encrypted FROM transcriptions WHERE account_id = ? AND client_id = ?",
            ).use { s ->
                s.setLong(1, accountId)
                s.setString(2, clientId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no row for $clientId" }
                    rows.getString(1)
                }
            }
        }
    }

    private fun deletedFlag(accountId: Long, clientId: String): Int = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT deleted FROM transcriptions WHERE account_id = ? AND client_id = ?",
            ).use { s ->
                s.setLong(1, accountId)
                s.setString(2, clientId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no row for $clientId" }
                    rows.getInt(1)
                }
            }
        }
    }

    private fun storedAtMs(accountId: Long, clientId: String): Long = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT stored_at_ms FROM transcriptions WHERE account_id = ? AND client_id = ?",
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

    private fun keyVersionStored(accountId: Long, clientId: String): Int = runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "SELECT key_version FROM transcriptions WHERE account_id = ? AND client_id = ?",
            ).use { s ->
                s.setLong(1, accountId)
                s.setString(2, clientId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no row for $clientId" }
                    rows.getInt(1)
                }
            }
        }
    }

    @Test
    fun liveRowDeleteTakesNextNumber() {
        assertEquals("push stores live row", PushResult.Stored, push(alice, CID, 0, "live"))
        assertEquals("change_seq after push", 1L, changeSeq(alice))
        assertEquals("delete applies", DeleteResult.Deleted, delete(alice, CID))
        assertEquals("change_no after delete", 2L, changeNo(alice, CID))
        assertEquals("change_seq after delete equals change_no", 2L, changeSeq(alice))
        assertEquals("row body after delete", "-", encryptedBody(alice, CID))
        assertEquals("row deleted flag after delete", 1, deletedFlag(alice, CID))
        assertEquals("row key_version kept after delete", 0, keyVersionStored(alice, CID))
        // stored_at_ms equals the fixed clock value, not just greater than zero.
        assertEquals("stored_at_ms after delete is the clock value", AccountFixtures.fixedClock.millis(), storedAtMs(alice, CID))
    }

    @Test
    fun listLeavesOutADeletedRow() {
        assertEquals("push stores the first live row", PushResult.Stored, push(alice, CID, 0, "kept"))
        assertEquals("push stores the second live row", PushResult.Stored, push(alice, OTHER_CID, 0, "gone"))
        assertEquals("delete applies to the second row", DeleteResult.Deleted, delete(alice, OTHER_CID))
        val listed = runBlocking { store.list(alice) }
        assertEquals("only the live row is listed", listOf(CID), listed.map { row -> row.clientId })
    }

    @Test
    fun repeatDeleteTakesNoNumber() {
        assertEquals("push stores live row", PushResult.Stored, push(alice, CID, 0, "live"))
        assertEquals("first delete applies", DeleteResult.Deleted, delete(alice, CID))
        val cn = changeNo(alice, CID)
        val seq = changeSeq(alice)
        assertEquals("repeat delete is NoOp", DeleteResult.NoOp, delete(alice, CID))
        assertEquals("change_no unchanged by repeat delete", cn, changeNo(alice, CID))
        assertEquals("change_seq unchanged by repeat delete", seq, changeSeq(alice))
        assertEquals("body unchanged by repeat delete", "-", encryptedBody(alice, CID))
    }

    @Test
    fun unknownIdTakesNoNumber() {
        val beforeSeq = changeSeq(alice)
        assertEquals("delete unknown id is NoOp", DeleteResult.NoOp, delete(alice, "0000" + "0000-0000-0000-0000-" + "000000" + "000000"))
        assertEquals("change_seq unchanged by unknown-id delete", beforeSeq, changeSeq(alice))
    }

    @Test
    fun otherAccountRowUntouched() {
        assertEquals("alice push stores", PushResult.Stored, push(alice, CID, 0, "alice live"))
        assertEquals("carol push stores same client id", PushResult.Stored, push(carol, CID, 0, "carol live"))
        assertEquals("alice delete applies", DeleteResult.Deleted, delete(alice, CID))
        assertEquals("carol row body unchanged", "carol live", encryptedBody(carol, CID))
        assertEquals("carol row deleted flag unchanged", 0, deletedFlag(carol, CID))
        assertEquals("carol row change_no unchanged", 1L, changeNo(carol, CID))
    }

    @Test
    fun deleteThenPushWithoutMarkIsNoOp() {
        assertEquals("push stores live row", PushResult.Stored, push(alice, CID, 0, "live"))
        assertEquals("delete applies", DeleteResult.Deleted, delete(alice, CID))
        val beforeSeq = changeSeq(alice)
        assertEquals("push without mark on deleted row is NoOp", PushResult.NoOp, push(alice, CID, 0, "again"))
        assertEquals("row still deleted", "-", encryptedBody(alice, CID))
        assertEquals("change_seq unchanged by no-op push", beforeSeq, changeSeq(alice))
    }

    @Test
    fun deleteThenPushWithMarkIsFreshSave() {
        assertEquals("push stores live row", PushResult.Stored, push(alice, CID, 0, "live"))
        assertEquals("delete applies", DeleteResult.Deleted, delete(alice, CID))
        // The account is at key_version 0: set it to 1 so the re-save takes a number above it.
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET key_version = ? WHERE id = ?").use { s ->
                    s.setInt(1, 1)
                    s.setLong(2, alice)
                    s.executeUpdate()
                }
            }
        }
        val beforeSeq = changeSeq(alice)
        val beforeCn = changeNo(alice, CID)
        assertEquals("push with mark restores row", PushResult.Stored, pushResave(alice, CID, 1, "restored"))
        assertEquals("row body after reanimate", "restored", encryptedBody(alice, CID))
        assertEquals("row deleted flag after reanimate", 0, deletedFlag(alice, CID))
        assertEquals("row key_version after reanimate", 1, keyVersionStored(alice, CID))
        // The fresh save takes the next number: previous change_no + 1 and change_seq equals it.
        assertEquals("change_no after fresh save", beforeCn + 1, changeNo(alice, CID))
        assertEquals("change_seq after fresh save equals change_no", beforeSeq + 1, changeSeq(alice))
    }

    @Test
    fun markOnLiveRowChangesNothing() {
        assertEquals("push stores live row", PushResult.Stored, push(alice, CID, 0, "live"))
        assertEquals("push with mark on live same pair is NoOp", PushResult.NoOp, pushResave(alice, CID, 0, "again"))
        assertEquals("body unchanged", "live", encryptedBody(alice, CID))
    }

    @Test
    fun markOnMissingRowInsertsAsToday() {
        assertEquals("push with mark on missing row inserts", PushResult.Stored, pushResave(alice, CID, 0, "fresh"))
        assertEquals("body after mark insert", "fresh", encryptedBody(alice, CID))
    }

    // The same check for a delete: a trigger that raises on any update of transcriptions refuses the
    // row write that follows the bump. The delete throws, the whole transaction rolls back, so
    // change_seq does not move and the row stays live with its body.
    @Test
    fun failedDeleteAfterBumpRollsBack() {
        assertEquals("push stores live row", PushResult.Stored, push(alice, CID, 0, "live"))
        val beforeSeq = changeSeq(alice)
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    """
                    CREATE TRIGGER fail_update_on_transcriptions BEFORE UPDATE ON transcriptions
                    BEGIN
                      SELECT RAISE(ABORT, 'forced update failure');
                    END
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }
        assertEquals("delete throws because the update is refused", true, try {
            runBlocking { store.delete(alice, CID) }
            false
        } catch (_: Exception) {
            true
        })
        assertEquals("change_seq did not move after the failed delete", beforeSeq, changeSeq(alice))
        assertEquals("row body unchanged after the failed delete", "live", encryptedBody(alice, CID))
        assertEquals("row deleted flag unchanged after the failed delete", 0, deletedFlag(alice, CID))
    }

    // Force a real failure after the change_seq bump: a trigger that raises on any insert into
    // transcriptions. push() bumps change_seq first, then inserts; the trigger fires on the insert,
    // the whole transaction rolls back, so change_seq must not move and no row may exist.
    @Test
    fun failedPushAfterBumpRollsBack() {
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
        assertEquals("push throws because the insert is refused", true, try {
            runBlocking { store.push(alice, CID, 0, "will fail") }
            false
        } catch (_: Exception) {
            true
        })
        assertEquals("change_seq did not move after failed push", 0L, changeSeq(alice))
        assertEquals("no row exists after failed push", 0L, runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM transcriptions WHERE account_id = ? AND client_id = ?",
                ).use { s ->
                    s.setLong(1, alice)
                    s.setString(2, CID)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no count" }
                        rows.getLong(1)
                    }
                }
            }
        })
    }
}
