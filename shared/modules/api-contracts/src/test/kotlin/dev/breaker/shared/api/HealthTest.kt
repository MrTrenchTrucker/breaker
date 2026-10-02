package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTest {

    @Test
    fun `Health has status, forwardingTo, and uptime fields`() {
        val health = Health(
            status = "ok",
            forwardingTo = "whisper-server:8080",
            uptime = "1h23m45s"
        )
        assertEquals("ok", health.status)
        assertEquals("whisper-server:8080", health.forwardingTo)
        assertEquals("1h23m45s", health.uptime)
    }
}
