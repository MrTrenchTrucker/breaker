package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.http.HttpStatusCode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthCompleteResetRouteIdentityTest : ResetRouteTestBase() {

    private val wrongCode = "A".repeat(43)

    @Test
    fun `every failed lookup answers the same status and the same body bytes`() {
        val adminText = startAdmin()
        // Registration order gives the ids: root 1, erin 2, alice 3.
        val erinCode = pendingAccount(adminText, "Erin", 5)
        // Erin's code lives 24 hours, so it is dead AT this instant.
        clock.advance(Duration.ofHours(24))
        val aliceCode = pendingAccount(adminText, "Alice", 2)
        val carolCode = pendingAccount(adminText, "Carol", 3)
        val daveCode = pendingAccount(adminText, "Dave", 4)
        val ginaCode = pendingAccount(adminText, "Gina", 6)
        val hankCode = pendingAccount(adminText, "Hank", 7)
        runSql(temp, "UPDATE accounts SET reset_pending = 0 WHERE username_lower = 'dave'")
        assertEquals("sync-api: Gina completes once", HttpStatusCode.OK, postComplete("Gina", ginaCode).status)
        recordWrongTries("hank", 5)
        val aliceBefore = stateOf(3L)
        val hankBefore = attemptsOf("hank")
        assertTrue("sync-api: Hank is locked", hankBefore != "none")

        val cases = listOf(
            "unknown name" to completeBody("Nobody", aliceCode),
            "wrong code of the right shape" to completeBody("Alice", wrongCode),
            "malformed code of 5 characters" to completeBody("Alice", "short"),
            "malformed code of 44 characters" to completeBody("Alice", aliceCode + "A"),
            "expired code" to completeBody("Erin", erinCode),
            "used code" to completeBody("Gina", ginaCode),
            "code row of an account that is not pending" to completeBody("Dave", daveCode),
            "locked name with a wrong code" to completeBody("Hank", wrongCode),
            "code of another account" to completeBody("Alice", carolCode),
            "name with an illegal character" to completeBody("Al ice", aliceCode),
        )
        val seen = LinkedHashSet<String>()
        for ((label, body) in cases) {
            val reply = postTo(completePath, null, body)
            assertAnswer(label, reply, HttpStatusCode.Unauthorized, refusedBody)
            seen.add(reply.status.value.toString() + " " + reply.body)
        }
        assertEquals("sync-api: the answers are not one single answer", 1, seen.size)
        assertEquals("sync-api: the wrong codes changed Alice", aliceBefore, stateOf(3L))
        assertEquals("sync-api: a wrong code touched the wrong-password row", hankBefore, attemptsOf("hank"))
        assertEquals("sync-api: Alice has no wrong-password row", "none", attemptsOf("alice"))
        assertEquals("sync-api: a right code of Hank still completes", HttpStatusCode.OK, postComplete("Hank", hankCode).status)
        assertEquals("sync-api: the code of Alice is unspent", HttpStatusCode.OK, postComplete("Alice", aliceCode).status)
    }

    @Test
    fun `an agent token gets 403 with the sync body before the body is read`() {
        val adminText = startAdmin()
        val code = pendingAccount(adminText, "Alice", 2)
        val agent = agentText(register("Bob", 3))
        val before = stateOf(2L)
        val bodies = listOf(
            "valid body" to completeBody("Alice", code),
            "malformed body" to "{ not json",
            "body over 4096 bytes" to padTo(completeBody("Alice", code), 5000),
        )
        for ((label, body) in bodies) {
            assertAnswer(label, postTo(completePath, agent, body), HttpStatusCode.Forbidden, SyncRouteTestSupport.FORBIDDEN_BODY)
        }
        assertEquals("sync-api: the agent calls changed Alice", before, stateOf(2L))
        assertEquals("sync-api: the code still completes", HttpStatusCode.OK, postComplete("Alice", code).status)
    }

    @Test
    fun `a session header is ignored and the session expiry does not move`() {
        val adminText = startAdmin()
        val code = pendingAccount(adminText, "Alice", 2)
        val issued = SyncRouteTestSupport.issuedSession(tokenStore, register("Bob", 3))
        val expiryBefore = expiryOf(issued.tokenId)
        clock.advance(Duration.ofHours(1))
        val reply = postCompleteWithAuthorization("Bearer " + issued.secret.reveal(), completeBody("Alice", code))
        assertEquals("sync-api: completion with a session header", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: the session expiry moved", expiryBefore, expiryOf(issued.tokenId))
        assertTrue("sync-api: the session still works", sessionWorks(issued.secret.reveal()))
    }

    @Test
    fun `a garbage Authorization header is ignored`() {
        val adminText = startAdmin()
        val code = pendingAccount(adminText, "Alice", 2)
        val headers = listOf("Bearer", "Basic dXNlcjpwYXNz", "Bearer two words", "Other abc", "Bearer " + SyncRouteTestSupport.UNKNOWN_TOKEN)
        for (header in headers) {
            val wrong = postCompleteWithAuthorization(header, completeBody("Alice", wrongCode))
            assertAnswer("wrong code with the header '$header'", wrong, HttpStatusCode.Unauthorized, refusedBody)
        }
        val right = postCompleteWithAuthorization("Bearer " + SyncRouteTestSupport.UNKNOWN_TOKEN, completeBody("Alice", code))
        assertEquals("sync-api: right code with an unknown token header", HttpStatusCode.OK, right.status)
    }
}
