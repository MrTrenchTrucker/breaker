package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthChangePasswordRouteAuthTest : AuthChangePasswordRouteTestBase() {

    private fun assertAgentRefused(label: String, body: String) {
        val id = keyedAccount("alice", 1)
        val agent = agentText(id)
        val before = snapshotOf(id)
        assertAnswer(label, postChange(agent, body), HttpStatusCode.Forbidden, SyncRouteTestSupport.FORBIDDEN_BODY)
        assertUnchanged(id, before, label)
    }

    private fun assertUnauthorized(label: String, id: Long, reply: Reply) {
        assertAnswer(label, reply, HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        assertEquals("sync-api: $label changed key_version", 1, columnsOf(id).keyVersion)
    }

    /** Keeps one wrong-password row alive, because the proof with the limit off neither counts nor clears. */
    private fun assertConflict(label: String, id: Long, session: String, body: String, expected: String) {
        limitOff()
        if (wrongCountOf("alice") == 0L) recordWrongTries("alice", 1)
        val countBefore = wrongCountOf("alice")
        val before = snapshotOf(id)
        assertAnswer(label, postChange(session, body), HttpStatusCode.Conflict, expected)
        assertUnchanged(id, before, label)
        assertEquals("sync-api: $label touched the wrong-password row", countBefore, wrongCountOf("alice"))
    }

    @Test
    fun `an agent token gets 403 with the sync body for a valid body and writes nothing`() {
        assertAgentRefused("agent with a valid body", validChangeBody())
    }

    @Test
    fun `an agent token with a malformed body still gets 403 and not 400`() {
        assertAgentRefused("agent with a malformed body", "{ not json")
    }

    @Test
    fun `an agent token with a 5000 byte body still gets 403 and not 413`() {
        assertAgentRefused("agent with a long body", padTo(validChangeBody(), 5000))
    }

    @Test
    fun `no token and an unknown token get 401 with the sync body`() {
        val id = keyedAccount("alice", 1)
        assertUnauthorized("post without a token", id, postChange(null, validChangeBody()))
        assertUnauthorized("post with an unknown token", id, postChange(SyncRouteTestSupport.UNKNOWN_TOKEN, validChangeBody()))
    }

    @Test
    fun `a malformed Authorization header gets 401 with the sync body`() {
        val id = keyedAccount("alice", 1)
        for (header in listOf("Bearer", "Basic dXNlcjpwYXNz", "Bearer two words", "Other abc")) {
            assertUnauthorized("post with the header '$header'", id, postChangeWithAuthorization(header, validChangeBody()))
        }
    }

    @Test
    fun `no token with a malformed or a 5000 byte body gets 401 and not 400 or 413`() {
        val id = keyedAccount("alice", 1)
        assertUnauthorized("malformed body", id, postChange(null, "{ not json"))
        assertUnauthorized("long body", id, postChange(null, padTo(validChangeBody(), 5000)))
    }

    @Test
    fun `a revoked session gets 401 and writes nothing`() {
        val id = keyedAccount("alice", 1)
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, id)
        assertTrue("sync-api: the session was not revoked in the set-up", runBlocking { tokenStore.revokeSession(issued.tokenId) })
        val before = snapshotOf(id)
        assertUnauthorized("post with a revoked session", id, postChange(issued.secret.reveal(), validChangeBody()))
        assertUnchanged(id, before, "the post with a revoked session")
    }

    @Test
    fun `an expired session gets 401 and writes nothing`() {
        val id = keyedAccount("alice", 1)
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, id)
        TokenFixtures.expireSession(temp, issued.tokenId)
        val before = snapshotOf(id)
        assertUnauthorized("post with an expired session", id, postChange(issued.secret.reveal(), validChangeBody()))
        assertUnchanged(id, before, "the post with an expired session")
    }

    @Test
    fun `a wrong old verifier gets 401 with the exact body is counted and writes nothing`() {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        val before = snapshotOf(id)
        assertAnswer("first wrong try", postChange(session, withOldVerifier(wrongVerifierBytes)), HttpStatusCode.Unauthorized, oldVerifierWrongBody)
        assertEquals("sync-api: one wrong try is counted", 1L, wrongCountOf("alice"))
        assertAnswer("second wrong try", postChange(session, withOldVerifier(wrongVerifierBytes)), HttpStatusCode.Unauthorized, oldVerifierWrongBody)
        assertEquals("sync-api: two wrong tries are counted", 2L, wrongCountOf("alice"))
        assertUnchanged(id, before, "the wrong tries")
    }

    @Test
    fun `five wrong old verifiers lock the name and the sixth request gets 423 even with the right one`() {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        repeat(5) { n ->
            val reply = postChange(session, withOldVerifier(wrongVerifierBytes))
            assertAnswer("wrong try ${n + 1}", reply, HttpStatusCode.Unauthorized, oldVerifierWrongBody)
        }
        val before = snapshotOf(id)
        val locked = postChangeFull(session, validChangeBody())
        assertEquals("sync-api: status of the sixth request", HttpStatusCode.Locked, locked.status)
        assertEquals("sync-api: Retry-After of the sixth request", "900", locked.retryAfter)
        assertEquals("sync-api: body of the sixth request", lockedBody, locked.body)
        assertUnchanged(id, before, "the locked request")
        val login = loginAs("alice", oldVerifierBytes)
        assertAnswer("login of the locked name", login, HttpStatusCode.Locked, lockedBody)
    }

    @Test
    fun `an old verifier that is not base64 or has 31 or 33 bytes gets 400 and is not counted`() {
        val id = keyedAccount("alice", 1)
        val session = sessionToken(id)
        val before = snapshotOf(id)
        val cases = listOf(
            "stars" to "*".repeat(44),
            "31 bytes" to textOf(ByteArray(31) { i -> (i + 9).toByte() }),
            "33 bytes" to textOf(ByteArray(33) { i -> (i + 9).toByte() }),
        )
        for ((label, text) in cases) {
            assertAnswer("old verifier of $label", postChange(session, withText("old_auth_verifier", text)), HttpStatusCode.BadRequest, badRequestBody)
        }
        assertEquals("sync-api: a refused old verifier must not be counted", 0L, wrongCountOf("alice"))
        assertUnchanged(id, before, "the refused old verifiers")
    }

    @Test
    fun `a keyless account gets 409 with the no keys body and writes nothing`() {
        val id = register("alice", 1)
        assertConflict("keyless account", id, sessionToken(id), validChangeBody(), noKeysBody)
    }

    @Test
    fun `a new_key_version that is equal lower or stored plus two gets 409 and the right one gets 200`() {
        val id = keyedAccount("alice", 1)
        runSql(temp, "UPDATE accounts SET key_version = 2 WHERE id = $id")
        val session = sessionToken(id)
        assertConflict("version equal to stored", id, session, withKeyVersion(2), versionBody)
        assertConflict("version lower than stored", id, session, withKeyVersion(1), versionBody)
        assertConflict("version stored plus two", id, session, withKeyVersion(4), versionBody)
        assertAnswer("version stored plus one", postChange(session, withKeyVersion(3)), HttpStatusCode.OK, """{"key_version":3}""")
    }

    @Test
    fun `a held prev_wrapped_dek gets 409 with the unfinished body and writes nothing`() {
        val id = keyedAccount("alice", 1)
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        assertConflict("prev held", id, sessionToken(id), validChangeBody(), unfinishedBody)
    }

    @Test
    fun `two causes at once answer the first one in the fixed order`() {
        val keyless = register("alice", 1)
        assertConflict("keyless and wrong version", keyless, sessionToken(keyless), withKeyVersion(5), noKeysBody)
    }

    @Test
    fun `a wrong version and a held prev_wrapped_dek answer the key_version message`() {
        val id = keyedAccount("alice", 1)
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        assertConflict("wrong version and prev held", id, sessionToken(id), withKeyVersion(5), versionBody)
    }
}
