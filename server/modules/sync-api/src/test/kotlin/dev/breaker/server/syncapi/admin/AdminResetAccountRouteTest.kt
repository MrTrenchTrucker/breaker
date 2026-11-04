package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.ResetRouteTestBase
import dev.breaker.server.syncapi.auth.presetPrev
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.auth.tokenStateOf
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AdminResetAccountRouteTest : ResetRouteTestBase() {

    /** The valid request for Alice is refused with [status] and [body], and her account is untouched. */
    private fun assertRefused(label: String, alice: Long, bearer: String?, status: HttpStatusCode, body: String) {
        val before = stateOf(alice)
        assertAnswer(label, postReset(bearer, nameBody("Alice")), status, body)
        assertEquals("sync-api: $label changed the account", before, stateOf(alice))
        assertEquals("sync-api: $label marked the account pending", 0L, pendingFlag(alice))
    }

    @Test
    fun `no token and an unknown token get 401 with the admin body`() {
        startAdmin()
        val alice = register("Alice", 2)
        assertRefused("no token", alice, null, HttpStatusCode.Unauthorized, adminUnauthorizedBody)
        assertRefused("unknown token", alice, SyncRouteTestSupport.UNKNOWN_TOKEN, HttpStatusCode.Unauthorized, adminUnauthorizedBody)
    }

    @Test
    fun `a user session gets 403 and changes nothing`() {
        startAdmin()
        val alice = register("Alice", 2)
        val bobText = sessionToken(register("Bob", 3))
        assertRefused("user session", alice, bobText, HttpStatusCode.Forbidden, adminForbiddenBody)
    }

    @Test
    fun `a transcribe agent token of the admin gets 403 and changes nothing`() {
        startAdmin()
        val alice = register("Alice", 2)
        val agent = SyncRouteTestSupport.agentToken(tokenStore, adminId)
        assertRefused("transcribe agent", alice, agent, HttpStatusCode.Forbidden, adminForbiddenBody)
    }

    @Test
    fun `an admin session resets the account and answers one reset_code of 43 characters`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val code = resetAs(adminText, "ALICE")
        assertTrue("sync-api: the code alphabet", code.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals("sync-api: pending mark", 1L, pendingFlag(alice))
        assertEquals("sync-api: one code row", 1L, codeRows(alice))
    }

    @Test
    fun `an admin scoped agent token resets the account`() {
        startAdmin()
        val alice = register("Alice", 2)
        codeOf(postReset(adminAgentText(adminId), nameBody("alice")))
        assertEquals("sync-api: pending mark", 1L, pendingFlag(alice))
    }

    @Test
    fun `a reset clears keys tokens transcriptions and the lock of that account only and keeps key_version`() {
        val adminText = startAdmin()
        val alice = keyedAccount("Alice", 2)
        val bob = keyedAccount("Bob", 3)
        val aliceText = sessionToken(alice)
        val bobText = sessionToken(bob)
        agentText(alice)
        presetPrev(temp, alice, KeyPatterns.prevWrappedDek())
        runSql(temp, "UPDATE accounts SET rekeyed_at = ${KeyPatterns.REKEYED_AT_MS} WHERE id = $alice")
        SyncRouteTestSupport.insertRawRow(temp, alice, SyncRouteTestSupport.CLIENT_ID, 1, SyncRouteTestSupport.ENCRYPTED, 1L)
        SyncRouteTestSupport.insertRawRow(temp, bob, SyncRouteTestSupport.CLIENT_ID, 1, SyncRouteTestSupport.ENCRYPTED, 1L)
        recordWrongTries("alice", 2)
        recordWrongTries("bob", 1)
        val bobBefore = stateOf(bob)
        val bobAttempts = attemptsOf("bob")
        clock.advance(Duration.ofMinutes(5))
        resetAs(adminText, "ALICE")
        val columns = columnsOf(alice)
        assertNull("sync-api: wrapped_dek", columns.wrappedDek)
        assertNull("sync-api: wrapped_box_privkey", columns.wrappedBoxPrivkey)
        assertNull("sync-api: box_pubkey", columns.boxPubkey)
        assertNull("sync-api: prev_wrapped_dek", columns.prevWrappedDek)
        assertEquals("sync-api: key_version is kept", 1, columns.keyVersion)
        assertEquals("sync-api: rekeyed_at is kept", KeyPatterns.REKEYED_AT_MS, columns.rekeyedAt)
        assertEquals("sync-api: reset_at", TokenFixtures.START.toEpochMilli() + Duration.ofMinutes(5).toMillis(), columns.resetAt)
        assertFalse("sync-api: a token of Alice is still live", tokenStateOf(temp, alice).contains("live"))
        assertFalse("sync-api: the session of Alice still works", sessionWorks(aliceText))
        assertEquals("sync-api: transcriptions of Alice", 0L, transcriptionRows(alice))
        assertEquals("sync-api: wrong-password row of Alice", "none", attemptsOf("alice"))
        assertEquals("sync-api: Bob is untouched", bobBefore, stateOf(bob))
        assertEquals("sync-api: transcriptions of Bob", 1L, transcriptionRows(bob))
        assertEquals("sync-api: wrong-password row of Bob", bobAttempts, attemptsOf("bob"))
        assertTrue("sync-api: the session of Bob stopped working", sessionWorks(bobText))
    }

    @Test
    fun `an unknown name gets 404 with the exact body`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val before = stateOf(alice)
        assertAnswer("unknown name", postReset(adminText, nameBody("Nobody")), HttpStatusCode.NotFound, notFoundBody)
        assertEquals("sync-api: the unknown name changed Alice", before, stateOf(alice))
    }

    @Test
    fun `a name with a space or 65 characters or no characters gets the same 404`() {
        val adminText = startAdmin()
        for (name in listOf("a b", "x".repeat(65), "")) {
            assertAnswer("name '$name'", postReset(adminText, nameBody(name)), HttpStatusCode.NotFound, notFoundBody)
        }
        assertEquals("sync-api: the admin was reset", 0L, pendingFlag(adminId))
    }
}
