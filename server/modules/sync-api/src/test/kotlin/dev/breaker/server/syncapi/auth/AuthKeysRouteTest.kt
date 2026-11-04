package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthKeysRouteTest : AuthKeysRouteTestBase() {

    private fun assertAnswer(label: String, reply: Reply, status: HttpStatusCode, body: String) {
        assertEquals("sync-api: $label status", status, reply.status)
        assertEquals("sync-api: $label body", body, reply.body)
    }

    private fun syncPushWith(agent: String): Reply {
        val body = SyncRouteTestSupport.pushBody(SyncRouteTestSupport.CLIENT_ID, "0", SyncRouteTestSupport.ENCRYPTED)
        val sync = SyncRouteTestSupport.postSync(tokenStore, transcriptionStore, agent, body)
        return Reply(sync.status, sync.body)
    }

    @Test
    fun `a keyless account posts valid keys and gets 200 with key_version 1 and the stored bytes`() {
        val id = register("alice", 1)
        assertAnswer("first post", postKeys(sessionToken(id), validBody()), HttpStatusCode.OK, """{"key_version":1}""")
        assertStored(id, 1, "the first post")
    }

    @Test
    fun `a second post gets 409 with the exact body and nothing changes`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        assertAnswer("first post", postKeys(session, validBody()), HttpStatusCode.OK, """{"key_version":1}""")
        val other = bodyOf(
            KeyPatterns.text(ByteArray(60) { 9 }),
            KeyPatterns.text(ByteArray(60) { 8 }),
            KeyPatterns.text(ByteArray(32) { 7 }),
        )
        assertAnswer("second post", postKeys(session, other), HttpStatusCode.Conflict, conflictBody)
        assertAnswer("third post with the same keys", postKeys(session, validBody()), HttpStatusCode.Conflict, conflictBody)
        assertStored(id, 1, "the later posts")
    }

    @Test
    fun `an agent token gets 403 with the sync body and the sync route answers it the same way`() {
        val id = register("alice", 1)
        val agent = agentText(id)
        val reply = postKeys(agent, validBody())
        assertAnswer("agent post", reply, HttpStatusCode.Forbidden, SyncRouteTestSupport.FORBIDDEN_BODY)
        assertAnswer("sync route with the same agent token", syncPushWith(agent), reply.status, reply.body)
        assertKeyless(id, "an agent post")
    }

    @Test
    fun `an agent token with a malformed body still gets 403 and not 400`() {
        val id = register("alice", 1)
        val reply = postKeys(agentText(id), "{ not json")
        assertAnswer("agent post with a malformed body", reply, HttpStatusCode.Forbidden, SyncRouteTestSupport.FORBIDDEN_BODY)
        assertKeyless(id, "an agent post with a malformed body")
    }

    @Test
    fun `an agent token with a 5000 byte body still gets 403 and not 413`() {
        val id = register("alice", 1)
        val reply = postKeys(agentText(id), padTo(validBody(), 5000))
        assertAnswer("agent post with a long body", reply, HttpStatusCode.Forbidden, SyncRouteTestSupport.FORBIDDEN_BODY)
        assertKeyless(id, "an agent post with a long body")
    }

    @Test
    fun `no token gets 401 with the sync body and writes nothing`() {
        val id = register("alice", 1)
        assertAnswer("post without a token", postKeys(null, validBody()), HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        assertKeyless(id, "a post without a token")
    }

    @Test
    fun `an unknown token gets 401 with the sync body and writes nothing`() {
        val id = register("alice", 1)
        val reply = postKeys(SyncRouteTestSupport.UNKNOWN_TOKEN, validBody())
        assertAnswer("post with an unknown token", reply, HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        assertKeyless(id, "a post with an unknown token")
    }

    @Test
    fun `a revoked session gets 401 with the sync body and writes nothing`() {
        val id = register("alice", 1)
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, id)
        assertTrue("sync-api: the session was not revoked in the set-up", runBlocking { tokenStore.revokeSession(issued.tokenId) })
        val reply = postKeys(issued.secret.reveal(), validBody())
        assertAnswer("post with a revoked session", reply, HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        assertKeyless(id, "a post with a revoked session")
    }

    @Test
    fun `a malformed Authorization header gets 401 with the sync body and writes nothing`() {
        val id = register("alice", 1)
        for (header in listOf("Bearer", "Basic dXNlcjpwYXNz", "Bearer two words", "Other abc")) {
            val reply = postKeysWithAuthorization(header, validBody())
            assertAnswer("post with the header '$header'", reply, HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        }
        assertKeyless(id, "a post with a malformed header")
    }

    @Test
    fun `no token with a malformed or a 5000 byte body gets 401 and not 400 or 413`() {
        val id = register("alice", 1)
        assertAnswer("malformed body", postKeys(null, "{ not json"), HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        val long = postKeys(null, padTo(validBody(), 5000))
        assertAnswer("long body", long, HttpStatusCode.Unauthorized, SyncRouteTestSupport.UNAUTHORIZED_BODY)
        assertKeyless(id, "posts without a token")
    }

    @Test
    fun `a keyless account at key_version 3 gets 200 with key_version 4`() {
        val id = register("alice", 1)
        runSql(temp, "UPDATE accounts SET key_version = 3 WHERE id = $id")
        assertAnswer("post at version 3", postKeys(sessionToken(id), validBody()), HttpStatusCode.OK, """{"key_version":4}""")
        assertStored(id, 4, "the post at version 3")
    }

    @Test
    fun `a post writes the account of its own session and leaves a second account untouched`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        assertAnswer("bob post", postKeys(sessionToken(bob), validBody()), HttpStatusCode.OK, """{"key_version":1}""")
        assertStored(bob, 1, "bob")
        assertKeyless(alice, "bob's post")
    }

    @Test
    fun `the route answers JSON content types for 200 409 and 400`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        for ((label, body) in listOf("200" to validBody(), "409" to validBody(), "400" to "{ not json")) {
            val type = contentTypeOfAnswer(session, body)
            assertTrue("sync-api: the $label answer has content type $type", type.startsWith("application/json"))
        }
    }

    @Test
    fun `a box_pubkey with a spare bit set decodes to the same bytes and is stored`() {
        val id = register("alice", 1)
        val spelled = nonCanonicalOf(boxPubkeyText)
        assertTrue("sync-api: the test spelling must differ from the canonical text", spelled != boxPubkeyText)
        val body = bodyOf(wrappedDekText, wrappedBoxPrivkeyText, spelled)
        assertAnswer("post with a spare bit", postKeys(sessionToken(id), body), HttpStatusCode.OK, """{"key_version":1}""")
        assertStored(id, 1, "the post with a spare bit")
    }
}
