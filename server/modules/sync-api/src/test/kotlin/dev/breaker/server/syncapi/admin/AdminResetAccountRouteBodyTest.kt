package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.ResetRouteTestBase
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AdminResetAccountRouteBodyTest : ResetRouteTestBase() {

    /** Every case is sent by a valid admin for a name that exists: each answer is 400 and nothing changes. */
    private fun assertAllBad(adminText: String, alice: Long, cases: List<Pair<String, String>>) {
        val before = stateOf(alice)
        for ((label, body) in cases) {
            assertAnswer(label, postReset(adminText, body), HttpStatusCode.BadRequest, badRequestBody)
        }
        assertEquals("sync-api: a refused body changed the account", before, stateOf(alice))
    }

    @Test
    fun `a body over 4096 bytes gets 413 and a body of exactly 4096 bytes is accepted`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val before = stateOf(alice)
        assertAnswer("5000 bytes", postReset(adminText, padTo(nameBody("Alice"), 5000)), HttpStatusCode.PayloadTooLarge, tooLargeBody)
        assertEquals("sync-api: the long body changed the account", before, stateOf(alice))
        codeOf(postReset(adminText, padTo(nameBody("Alice"), 4096)))
        assertEquals("sync-api: pending mark after the 4096 byte body", 1L, pendingFlag(alice))
    }

    @Test
    fun `a user session with a 5000 byte body gets 403 and not 413`() {
        startAdmin()
        val bobText = sessionToken(register("Bob", 2))
        assertAnswer("user session", postReset(bobText, padTo(nameBody("Bob"), 5000)), HttpStatusCode.Forbidden, adminForbiddenBody)
    }

    @Test
    fun `no body and a username of the wrong type get 400`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        assertAllBad(
            adminText,
            alice,
            listOf(
                "empty body" to "",
                "empty object" to "{}",
                "number" to """{"username":5}""",
                "boolean" to """{"username":true}""",
                "array" to """{"username":["Alice"]}""",
                "null" to """{"username":null}""",
                "wrong member name" to """{"user":"Alice"}""",
            ),
        )
    }

    @Test
    fun `an extra member a duplicate member and a body that is not an object get 400`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        assertAllBad(
            adminText,
            alice,
            listOf(
                "extra member" to """{"username":"Alice","extra":"x"}""",
                "duplicate member" to """{"username":"Alice","username":"Alice"}""",
                "array" to """["Alice"]""",
                "string" to "\"Alice\"",
                "truncated" to "{\"username\":\"Alice\"",
                "not json" to "{ not json",
            ),
        )
    }

    @Test
    fun `an admin who resets their own account gets 200 and loses their own tokens`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val aliceText = sessionToken(alice)
        val adminAgent = adminAgentText(adminId)
        codeOf(postReset(adminText, nameBody("ROOT")))
        assertAnswer("own session after its own reset", postReset(adminText, nameBody("Alice")), HttpStatusCode.Unauthorized, adminUnauthorizedBody)
        assertAnswer("own agent token after the reset", postReset(adminAgent, nameBody("Alice")), HttpStatusCode.Unauthorized, adminUnauthorizedBody)
        assertEquals("sync-api: the admin is pending", 1L, pendingFlag(adminId))
        assertEquals("sync-api: Alice is not pending", 0L, pendingFlag(alice))
        assertTrue("sync-api: the session of Alice stopped working", sessionWorks(aliceText))
    }

    @Test
    fun `a second reset replaces the first code and only the second code completes`() {
        val adminText = startAdmin()
        val alice = register("Alice", 2)
        val first = resetAs(adminText, "ALICE")
        val second = resetAs(adminText, "alice")
        assertNotEquals("sync-api: the two codes are equal", first, second)
        assertEquals("sync-api: one code row", 1L, codeRows(alice))
        assertAnswer("first code", postComplete("Alice", first), HttpStatusCode.Unauthorized, refusedBody)
        assertEquals("sync-api: second code status", HttpStatusCode.OK, postComplete("Alice", second).status)
    }
}
