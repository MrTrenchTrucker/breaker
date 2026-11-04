package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.VerifyResult
import dev.breaker.server.syncapi.auth.readKeyColumns
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a successful password change writes and ends. The refusals and the failures are in the neighbouring files. */
internal class PasswordChangeStoreTest : PasswordChangeStoreTestBase() {

    @Test
    fun `Changed with key_version 2 writes every column as sent when an old row holds prev`() {
        pushOld(alice, clientId(0))
        val request = newRequest()
        val before = fullState()

        val result = changed(change(alice, session.tokenId, request))

        assertEquals("sync-api: key_version of Changed", 2, result.keyVersion)
        assertColumns(alice, expectedColumns(request, AccountFixtures.hex(request.prevWrappedDek)), "the change")
        assertTrue("sync-api: the change must alter the state the tests compare", before != fullState())
    }

    @Test
    fun `with no transcriptions the rule clears prev at once and the other columns stay as sent`() {
        val request = newRequest()

        assertEquals("sync-api: key_version of Changed", 2, changed(change(alice, session.tokenId, request)).keyVersion)

        val columns = readKeyColumns(temp, alice)
        assertNull("sync-api: prev_wrapped_dek must be cleared with no rows", columns.prevWrappedDek)
        assertArrayEquals("sync-api: wrapped_dek", request.newWrappedDek, columns.wrappedDek)
        assertArrayEquals("sync-api: wrapped_box_privkey", request.newWrappedBoxPrivkey, columns.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: box_pubkey", request.newBoxPubkey, columns.boxPubkey)
        assertEquals("sync-api: key_version", 2, columns.keyVersion)
        assertEquals("sync-api: rekeyed_at", AccountFixtures.FIXED_MILLIS, columns.rekeyedAt)
    }

    @Test
    fun `with only a deleted old row the rule clears prev`() {
        pushOld(alice, clientId(0))
        deleteRow(alice, clientId(0))

        changed(change(alice, session.tokenId, newRequest()))

        assertNull("sync-api: a deleted old row must not hold prev", readKeyColumns(temp, alice).prevWrappedDek)
    }

    @Test
    fun `other sessions are revoked while this session and the agent token and a second account stay live`() {
        changed(change(alice, session.tokenId, newRequest()))

        assertEquals("sync-api: the other session is revoked at the clock time", AccountFixtures.FIXED_MILLIS, revokedAt(otherSession))
        assertFalse("sync-api: the other session must be dead", isLive(otherSession))
        assertNull("sync-api: this session must not be revoked", revokedAt(session))
        assertTrue("sync-api: this session must stay live", isLive(session))
        assertNull("sync-api: the agent token must not be revoked", revokedAt(agent))
        assertTrue("sync-api: the agent token must stay live", isLive(agent))
        assertNull("sync-api: the session of the second account must not be revoked", revokedAt(bobSession))
    }

    @Test
    fun `the hand-off codes of the account are deleted and a second account keeps its code`() {
        addCodes(alice, 1, 2)
        addCodes(bob, 3)
        assertEquals("sync-api: setup codes of alice", 2L, codeCount(alice))

        changed(change(alice, session.tokenId, newRequest()))

        assertEquals("sync-api: no code of alice remains", 0L, codeCount(alice))
        assertEquals("sync-api: the code of the second account stays", 1L, codeCount(bob))
    }

    @Test
    fun `the wrong try row of the name is cleared and another name keeps its row`() {
        wrongTries("alice", 3)
        wrongTries("bob", 2)
        assertEquals("sync-api: setup wrong tries of alice", 3L, wrongCountOf("alice"))

        changed(change(alice, session.tokenId, newRequest()))

        assertNull("sync-api: the row of alice must be gone", wrongCountOf("alice"))
        assertEquals("sync-api: the row of bob must stay", 2L, wrongCountOf("bob"))
    }

    @Test
    fun `the old verifier stops verifying and the new one verifies`() {
        assertTrue("sync-api: before, the old verifier verifies", verify(1) is VerifyResult.Verified)
        assertTrue("sync-api: before, the new verifier is rejected", verify(77) is VerifyResult.Rejected)

        changed(change(alice, session.tokenId, newRequest()))

        val accepted = verify(77)
        assertTrue("sync-api: after, the new verifier must verify: $accepted", accepted is VerifyResult.Verified)
        assertEquals("sync-api: it is the account of alice", alice, (accepted as VerifyResult.Verified).accountId)
        assertTrue("sync-api: after, the old verifier must be rejected", verify(1) is VerifyResult.Rejected)
    }

    @Test
    fun `a change leaves the columns of a second account untouched`() {
        val bobBefore = snapshot(bob)

        changed(change(alice, session.tokenId, newRequest()))

        assertEquals("sync-api: the second account changed", bobBefore, snapshot(bob))
    }

    @Test
    fun `the wrong try row of a name with capitals is cleared by its folded name and starts again at one`() {
        val dana = keyedAccount("Dana-Test.One", 3)
        val danaSession = SyncRouteTestSupport.issuedSession(tokens, dana)
        wrongTries("Dana-Test.One", 2)
        assertEquals("sync-api: setup wrong tries of the folded name", 2L, wrongCountOf("dana-test.one"))

        changed(change(dana, danaSession.tokenId, newRequest()))

        assertNull("sync-api: the row of the folded name must be gone", wrongCountOf("dana-test.one"))
        wrongTries("Dana-Test.One", 1)
        assertEquals("sync-api: a wrong try after the change starts again at one", 1L, wrongCountOf("dana-test.one"))
    }

    private fun verify(seed: Int): VerifyResult =
        runBlocking { accounts.verify("alice", AccountFixtures.verifier(seed)) }
}
