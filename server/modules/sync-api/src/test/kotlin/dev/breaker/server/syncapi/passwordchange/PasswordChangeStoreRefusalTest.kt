package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every refusal of the store writes nothing, and the order of the checks is pinned by two failures at once. */
internal class PasswordChangeStoreRefusalTest : PasswordChangeStoreTestBase() {

    private fun assertRefusedAndUntouched(expected: PasswordChangeResult, sessionId: Long, accountId: Long, version: Int) {
        val before = fullState()
        val result = change(accountId, sessionId, newRequest(version))
        assertEquals("sync-api: result for key_version $version", expected, result)
        assertEquals("sync-api: a refusal must write nothing", before, fullState())
    }

    @Test
    fun `TokenNotLive for a revoked session and nothing is written`() {
        runBlocking { tokens.revokeSession(session.tokenId) }

        assertRefusedAndUntouched(PasswordChangeResult.TokenNotLive, session.tokenId, alice, 2)
    }

    @Test
    fun `TokenNotLive for an expired session and nothing is written`() {
        TokenFixtures.expireSession(temp, session.tokenId)

        assertRefusedAndUntouched(PasswordChangeResult.TokenNotLive, session.tokenId, alice, 2)
    }

    @Test
    fun `TokenNotLive for a session of another account and nothing is written`() {
        assertRefusedAndUntouched(PasswordChangeResult.TokenNotLive, bobSession.tokenId, alice, 2)
    }

    @Test
    fun `TokenNotLive for an agent token id and nothing is written`() {
        assertRefusedAndUntouched(PasswordChangeResult.TokenNotLive, agent.tokenId, alice, 2)
    }

    @Test
    fun `TokenNotLive for an account id that does not exist`() {
        assertRefusedAndUntouched(PasswordChangeResult.TokenNotLive, session.tokenId, 999_999L, 2)
    }

    @Test
    fun `NoKeys for a keyless account and nothing is written`() {
        val carol = TokenFixtures.register(temp, "carol", 3).accountId
        val carolSession = SyncRouteTestSupport.issuedSession(tokens, carol)
        val carolBefore = snapshot(carol)

        assertRefusedAndUntouched(PasswordChangeResult.NoKeys, carolSession.tokenId, carol, 1)

        assertEquals("sync-api: the keyless account changed", carolBefore, snapshot(carol))
    }

    @Test
    fun `KeyVersionMismatch for 1 and 3 when the stored version is 1`() {
        val before = fullState()

        assertRefusedAndUntouched(PasswordChangeResult.KeyVersionMismatch, session.tokenId, alice, 1)
        assertRefusedAndUntouched(PasswordChangeResult.KeyVersionMismatch, session.tokenId, alice, 3)

        assertEquals("sync-api: the refusals changed the state", before, fullState())
    }

    @Test
    fun `KeyVersionMismatch for 1 and 2 and 4 when the stored version is 2`() {
        assertEquals("sync-api: setup change", 2, changed(change(alice, session.tokenId, newRequest())).keyVersion)
        val afterFirst = fullState()

        assertRefusedAndUntouched(PasswordChangeResult.KeyVersionMismatch, session.tokenId, alice, 1)
        assertRefusedAndUntouched(PasswordChangeResult.KeyVersionMismatch, session.tokenId, alice, 2)
        assertRefusedAndUntouched(PasswordChangeResult.KeyVersionMismatch, session.tokenId, alice, 4)

        assertEquals("sync-api: the refusals changed the state", afterFirst, fullState())
    }

    @Test
    fun `PreviousUnfinished when prev is held and the version is right`() {
        holdPrev()

        assertRefusedAndUntouched(PasswordChangeResult.PreviousUnfinished, session.tokenId, alice, 2)
    }

    @Test
    fun `a keyless account with a wrong version answers NoKeys and not KeyVersionMismatch`() {
        val carol = TokenFixtures.register(temp, "carol", 3).accountId
        val carolSession = SyncRouteTestSupport.issuedSession(tokens, carol)

        assertRefusedAndUntouched(PasswordChangeResult.NoKeys, carolSession.tokenId, carol, 5)
    }

    @Test
    fun `a wrong version with prev held answers KeyVersionMismatch and not PreviousUnfinished`() {
        holdPrev()

        assertRefusedAndUntouched(PasswordChangeResult.KeyVersionMismatch, session.tokenId, alice, 3)
    }
}
