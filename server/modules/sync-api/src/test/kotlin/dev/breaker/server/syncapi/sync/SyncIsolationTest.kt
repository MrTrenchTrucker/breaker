package dev.breaker.server.syncapi.sync

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One account never sees or changes another account's rows. */
internal class SyncIsolationTest : SyncRouteTestBase() {

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `an account pulls only its own rows`() = runBlocking<Unit> {
        val alice = bearer(issueSession(account("alice", 1)).secret.reveal())
        val bob = bearer(issueSession(account("bob", 2)).secret.reveal())
        testApplication {
            application { installSyncRoutes() }
            assertEquals("alice push", HttpStatusCode.NoContent, client.postSync(alice, utf8(pushBody(CLIENT_ID, "0", validEncrypted("alice")))).status)
            assertEquals("bob push", HttpStatusCode.NoContent, client.postSync(bob, utf8(pushBody(SECOND_CLIENT_ID, "0", validEncrypted("bob")))).status)
            val aliceView = client.pullSync(alice).bodyAsText()
            val bobView = client.pullSync(bob).bodyAsText()
            assertTrue("alice sees her row", aliceView.contains(CLIENT_ID))
            assertFalse("alice does not see bob's row", aliceView.contains(SECOND_CLIENT_ID))
            assertTrue("bob sees his row", bobView.contains(SECOND_CLIENT_ID))
            assertFalse("bob does not see alice's row", bobView.contains(CLIENT_ID))
        }
    }

    @Test
    fun `a push of another account's client id is stored for the pushing account and leaves the other row unchanged`() =
        runBlocking<Unit> {
            val alice = bearer(issueSession(account("alice", 1)).secret.reveal())
            val bob = bearer(issueSession(account("bob", 2)).secret.reveal())
            val aliceText = validEncrypted("alice")
            val bobText = validEncrypted("bob")
            testApplication {
                application { installSyncRoutes() }
                client.postSync(alice, utf8(pushBody(CLIENT_ID, "0", aliceText)))
                val bobPush = client.postSync(bob, utf8(pushBody(CLIENT_ID, "0", bobText)))
                assertEquals("bob's push of the same client id", HttpStatusCode.NoContent, bobPush.status)
                val bobView = client.pullSync(bob).bodyAsText()
                val aliceView = client.pullSync(alice).bodyAsText()
                assertTrue("bob holds his own text", bobView.contains("\"encrypted\":\"$bobText\""))
                assertTrue("alice keeps her text", aliceView.contains("\"encrypted\":\"$aliceText\""))
                assertFalse("alice does not hold bob's text", aliceView.contains(bobText))
            }
        }
}
