package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** The names the phone and ADR-044 read in `GET /v1/status`: services, and per entry name and up, nothing else. */
class ServiceStatusTest {

    @Test
    fun `one service entry holds exactly name and up`() {
        val entry = ServiceStatus(name = "whisper-server", up = false)
        assertEquals("api-contracts: name", "whisper-server", entry.name)
        assertEquals("api-contracts: up", false, entry.up)
        assertEquals(
            "api-contracts: the fields of one entry",
            listOf("name", "up"),
            ServiceStatus::class.java.declaredFields.map { it.name }.sorted(),
        )
    }

    @Test
    fun `the reply holds exactly services`() {
        assertEquals(
            "api-contracts: the fields of the reply",
            listOf("services"),
            ServiceStatusReply::class.java.declaredFields.map { it.name }.sorted(),
        )
        assertEquals("api-contracts: the list", 1, ServiceStatusReply(listOf(ServiceStatus("sync-api", true))).services.size)
    }
}
