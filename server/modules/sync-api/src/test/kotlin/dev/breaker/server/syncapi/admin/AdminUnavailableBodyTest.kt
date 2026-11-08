package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 503 body of the admin routes is the one body the contract documents for every route. */
class AdminUnavailableBodyTest {

    @Test
    fun `the admin 503 body is the one the contract documents`() {
        assertEquals(SyncRouteTestSupport.UNAVAILABLE_BODY, SERVICE_UNAVAILABLE_BODY)
    }
}
