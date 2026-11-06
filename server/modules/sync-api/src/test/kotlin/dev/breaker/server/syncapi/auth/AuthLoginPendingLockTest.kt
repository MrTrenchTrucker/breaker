package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.syncApiModule
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route tests for the lock of a pending name: five wrong logins, then a 423, and a locked completion. */
internal class AuthLoginPendingLockTest : ResetRouteTestBase() {

    /** One login answer with its Retry-After header, read while the test application is open. */
    private class LoginAnswer(val reply: Reply, val retryAfter: String?)

    private fun loginWithHeaders(name: String, verifierBytes: ByteArray): LoginAnswer {
        var answer: LoginAnswer? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(loginPath) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"$name","auth_verifier":"${textOf(verifierBytes)}"}""")
                }
                val reply = Reply(response.status, response.bodyAsText())
                answer = LoginAnswer(reply, response.headers[HttpHeaders.RetryAfter])
            }
        }
        return checkNotNull(answer)
    }

    @Test
    fun `five wrong logins answer 401 and a sixth answers 423 with Retry-After 900 for six names`() {
        val adminText = startAdmin()
        pendingAccount(adminText, "Alice", 2)
        pendingAccount(adminText, "Carol", 4)
        register("Bob", 3)
        register("Dan", 5)
        assertEquals(
            "sync-api: control login of an active name",
            HttpStatusCode.OK,
            loginAs("Bob", AccountFixtures.verifierBytes(3)).status,
        )
        for (name in listOf("Alice", "Carol", "Bob", "Dan", "Nobody", "Ghost")) {
            for (step in 1..5) {
                val reply = loginAs(name, wrongVerifierBytes)
                assertAnswer("wrong try $step", reply, HttpStatusCode.Unauthorized, loginRefusedBody)
            }
            val sixth = loginWithHeaders(name, wrongVerifierBytes)
            assertAnswer("sixth try", sixth.reply, HttpStatusCode.Locked, lockedBody)
            assertEquals("sync-api: Retry-After of the sixth try", "900", sixth.retryAfter)
        }
    }

    @Test
    fun `a pending name keeps five wrong tries in its row after the sixth try is locked`() {
        val adminText = startAdmin()
        pendingAccount(adminText, "Alice", 2)
        pendingAccount(adminText, "Carol", 4)
        for (name in listOf("Alice", "Carol")) {
            for (step in 1..5) {
                val reply = loginAs(name, wrongVerifierBytes)
                assertAnswer("wrong try $step", reply, HttpStatusCode.Unauthorized, loginRefusedBody)
            }
            assertAnswer("sixth try", loginAs(name, wrongVerifierBytes), HttpStatusCode.Locked, lockedBody)
        }
        assertTrue("sync-api: first pending row after the sixth try", attemptsOf("alice").startsWith("5/"))
        assertTrue("sync-api: second pending row after the sixth try", attemptsOf("carol").startsWith("5/"))
    }

    @Test
    fun `a pending name gets no 423 in six tries with the limit off and has no row`() {
        limitOff()
        val adminText = startAdmin()
        pendingAccount(adminText, "Alice", 2)
        pendingAccount(adminText, "Carol", 4)
        for (name in listOf("Alice", "Carol")) {
            for (step in 1..6) {
                val reply = loginAs(name, wrongVerifierBytes)
                assertAnswer("limit off try $step", reply, HttpStatusCode.Unauthorized, loginRefusedBody)
            }
        }
        assertEquals("sync-api: first pending row with the limit off", "none", attemptsOf("alice"))
        assertEquals("sync-api: second pending row with the limit off", "none", attemptsOf("carol"))
    }

    @Test
    fun `a locked pending name still completes the reset with the live code and logs in`() {
        val adminText = startAdmin()
        val code = pendingAccount(adminText, "Alice", 2)
        for (step in 1..5) {
            val reply = loginAs("Alice", wrongVerifierBytes)
            assertAnswer("wrong try $step", reply, HttpStatusCode.Unauthorized, loginRefusedBody)
        }
        assertAnswer("sixth try", loginAs("Alice", wrongVerifierBytes), HttpStatusCode.Locked, lockedBody)
        val completed = postComplete("Alice", code)
        assertEquals("sync-api: completion of a locked pending name", HttpStatusCode.OK, completed.status)
        assertEquals("sync-api: wrong-password row after the completion", "none", attemptsOf("alice"))
        val login = loginAs("Alice", newVerifierBytes)
        assertEquals("sync-api: login with the new verifier", HttpStatusCode.OK, login.status)
        val oldLogin = loginAs("Alice", AccountFixtures.verifierBytes(2))
        assertAnswer("control: old verifier", oldLogin, HttpStatusCode.Unauthorized, loginRefusedBody)
    }
}
