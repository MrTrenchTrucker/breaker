package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every write of a reset, for one account out of three. */
internal class ResetStoreResetTest : ResetStoreTestBase() {

    @Test
    fun `a reset moves change_seq from 5 to 6 and sets purged_through to 6`() {
        assertEquals("sync-api: change_seq before", "5", col("change_seq"))
        assertEquals("sync-api: purged_through before", "0", col("purged_through"))
        reset("alice")
        assertEquals("sync-api: change_seq after", "6", col("change_seq"))
        assertEquals("sync-api: purged_through after", "6", col("purged_through"))
    }

    @Test
    fun `a reset keeps key_version and rekeyed_at`() {
        reset("alice")
        assertEquals("sync-api: key_version", "3", col("key_version"))
        assertEquals("sync-api: rekeyed_at", KeyPatterns.REKEYED_AT_MS.toString(), col("rekeyed_at"))
    }

    @Test
    fun `a reset replaces the verifier with 32 bytes that match neither the old bytes nor zeros`() {
        val before = col("hex(verifier_hash)")
        reset("alice")
        assertEquals("sync-api: verifier length", "32", col("length(verifier_hash)"))
        assertNotEquals("sync-api: the old verifier was kept", before, col("hex(verifier_hash)"))
        assertNotEquals("sync-api: the verifier is zeros", col("hex(zeroblob(32))"), col("hex(verifier_hash)"))
    }

    @Test
    fun `a reset leaves the name role salt and cost columns as they were`() {
        val sql = "SELECT username || ' ' || username_lower || ' ' || role || ' ' || hex(salt) || ' ' || kdf_memory_kib || " +
            "kdf_iterations || kdf_parallelism || kdf_version || verifier_algo || verifier_iters || hex(verifier_salt) || " +
            "created_at_ms FROM accounts WHERE id = $alice"
        val before = cell(sql)
        reset("alice")
        assertEquals("sync-api: a column a reset must not name changed", before, cell(sql))
    }

    @Test
    fun `a reset marks the account pending clears the four key columns and sets reset_at by the clock`() {
        clock.advance(Duration.ofMinutes(5))
        reset("alice")
        assertEquals("sync-api: reset_pending", "1", col("reset_pending"))
        for (name in listOf("wrapped_dek", "wrapped_box_privkey", "box_pubkey", "prev_wrapped_dek")) {
            assertEquals("sync-api: $name", "NULL", col(name))
        }
        assertEquals("sync-api: reset_at", clock.millis().toString(), col("reset_at"))
    }

    @Test
    fun `a reset ends every session and agent token of the account and deletes its hand-off codes`() {
        assertTrue("sync-api: setup session", isLive(aliceSession))
        reset("alice")
        assertFalse("sync-api: first session", isLive(aliceSession))
        assertFalse("sync-api: second session", isLive(aliceOther))
        assertFalse("sync-api: agent token", isLive(aliceAgent))
        assertEquals("sync-api: live rows", 0L, TokenFixtures.liveTokenCount(temp, alice))
        assertEquals("sync-api: hand-off codes", 0L, num("SELECT count(*) FROM signin_handoff WHERE account_id = $alice"))
    }

    @Test
    fun `a reset deletes the transcriptions and the wrong try row of the account`() {
        assertEquals("sync-api: setup rows", 5L, num("SELECT count(*) FROM transcriptions WHERE account_id = $alice"))
        reset("alice")
        assertEquals("sync-api: transcriptions", 0L, num("SELECT count(*) FROM transcriptions WHERE account_id = $alice"))
        assertEquals("sync-api: wrong try row", 0L, num("SELECT count(*) FROM signin_attempts WHERE username_lower = 'alice'"))
    }

    @Test
    fun `a reset touches no row of the other accounts`() {
        plantCode(bob, 5, clock.millis() + 3_600_000L)
        val before = others()
        reset("alice")
        assertEquals("sync-api: a row of another account changed", before, others())
        assertTrue("sync-api: bob session", isLive(bobSession))
        assertTrue("sync-api: bob agent token", isLive(bobAgent))
        assertTrue("sync-api: carol session", isLive(carolSession))
    }

    @Test
    fun `the new code is stored as its hash and lives 24 hours from the clock`() {
        clock.advance(Duration.ofMinutes(5))
        val code = reset("alice")
        assertEquals("sync-api: code length", 43, code.length)
        assertEquals("sync-api: one row", 1L, num("SELECT count(*) FROM reset_codes WHERE account_id = $alice"))
        assertEquals("sync-api: stored hash", hashHex(code), cell("SELECT lower(hex(code_hash)) FROM reset_codes WHERE account_id = $alice"))
        assertEquals("sync-api: issued", clock.millis(), num("SELECT issued_at_ms FROM reset_codes WHERE account_id = $alice"))
        assertEquals("sync-api: expires", clock.millis() + 86_400_000L, num("SELECT expires_at_ms FROM reset_codes WHERE account_id = $alice"))
    }

    @Test
    fun `a second reset replaces the code and the first code stops working`() {
        plantCode(alice, 7, clock.millis() + 1_000L)
        val first = reset("alice")
        val second = reset("alice")
        assertNotEquals("sync-api: two resets gave one code", first, second)
        assertEquals("sync-api: one row", 1L, num("SELECT count(*) FROM reset_codes WHERE account_id = $alice"))
        assertEquals("sync-api: stored hash", hashHex(second), cell("SELECT lower(hex(code_hash)) FROM reset_codes WHERE account_id = $alice"))
        assertFalse("sync-api: the first code still works", runBlocking { store.checkCode("alice", first) })
        assertTrue("sync-api: the second code does not work", runBlocking { store.checkCode("alice", second) })
    }

    @Test
    fun `a reset of an unknown name answers NoSuchAccount and writes nothing`() {
        val before = worldState()
        assertEquals("sync-api: result", ResetAccountResult.NoSuchAccount, runBlocking { store.resetAccount("nobody") })
        assertEquals("sync-api: a table changed", before, worldState())
    }

    @Test
    fun `the admin can reset the own account and the own tokens die`() {
        reset("bob")
        assertEquals("sync-api: bob pending", "1", col("reset_pending", bob))
        assertFalse("sync-api: bob session", isLive(bobSession))
        assertFalse("sync-api: bob agent token", isLive(bobAgent))
        assertTrue("sync-api: alice session", isLive(aliceSession))
        assertEquals("sync-api: alice change_seq", "5", col("change_seq"))
    }
}
