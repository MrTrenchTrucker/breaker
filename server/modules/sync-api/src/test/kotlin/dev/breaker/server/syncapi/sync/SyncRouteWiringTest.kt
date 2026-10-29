package dev.breaker.server.syncapi.sync

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.syncApiModule
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The module wiring: both sync routes are installed at their exact paths, and nothing else answers there. */
internal class SyncRouteWiringTest : SyncRouteTestBase() {

    @Before
    fun setUp() {
        openSyncStores()
    }

    @Test
    fun `the full module answers both sync routes at their exact paths`() = runBlocking<Unit> {
        val auth = bearer(issueSession(account("alice", 1)).secret.reveal())
        testApplication {
            application { syncApiModule(AccountFixtures.newStore(temp), tokenStore, transcriptionStore) }
            val push = client.postSync(auth, utf8(pushBody(CLIENT_ID, "0", validEncrypted())))
            assertEquals("push path", HttpStatusCode.NoContent, push.status)
            val pull = client.pullSync(auth)
            assertEquals("pull path", HttpStatusCode.OK, pull.status)
            assertTrue("the pull shows the row", pull.bodyAsText().contains(CLIENT_ID))
        }
    }

    @Test
    fun `other methods and paths on the sync paths are not successes`() = runBlocking<Unit> {
        testApplication {
            application { syncApiModule(AccountFixtures.newStore(temp), tokenStore, transcriptionStore) }
            val getSync = client.get(SYNC_PATH)
            val postPull = client.post(PULL_PATH)
            assertTrue("GET $SYNC_PATH must not be a 2xx", getSync.status.value !in 200..299)
            assertTrue("POST $PULL_PATH must not be a 2xx", postPull.status.value !in 200..299)
            assertEquals("a trailing slash is not the route", HttpStatusCode.NotFound, client.post("$SYNC_PATH/").status)
        }
    }
}
