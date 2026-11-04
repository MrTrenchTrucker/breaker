package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.auth.runSql
import org.junit.Assert.assertEquals
import org.junit.Test

/** A pending account is refused first. The change checks run in a fixed order: pending, then no keys. */
internal class PasswordChangeStorePendingTest : PasswordChangeStoreTestBase() {

    private fun clearKeys() {
        runSql(temp, "UPDATE accounts SET wrapped_dek = NULL, wrapped_box_privkey = NULL, box_pubkey = NULL WHERE id = $alice")
    }

    private fun markPending() {
        runSql(temp, "UPDATE accounts SET reset_pending = 1 WHERE id = $alice")
    }

    @Test
    fun `a pending keyless account gets ResetPending before NoKeys and nothing is written`() {
        clearKeys()
        markPending()
        val before = fullState()
        assertEquals("sync-api: both causes present", PasswordChangeResult.ResetPending, change(alice, session.tokenId, newRequest()))
        assertEquals("sync-api: a refused change wrote something", before, fullState())
    }

    @Test
    fun `the same keyless account without the pending mark gets NoKeys`() {
        clearKeys()
        assertEquals("sync-api: control", PasswordChangeResult.NoKeys, change(alice, session.tokenId, newRequest()))
    }

    @Test
    fun `a pending keyed account gets ResetPending also for a wrong key version`() {
        markPending()
        assertEquals("sync-api: wrong key version", PasswordChangeResult.ResetPending, change(alice, session.tokenId, newRequest(keyVersion = 7)))
    }

    @Test
    fun `a pending account does not stop another account from changing`() {
        markPending()
        val result = change(bob, bobSession.tokenId, newRequest())
        assertEquals("sync-api: bob key_version", 2, changed(result).keyVersion)
    }
}
