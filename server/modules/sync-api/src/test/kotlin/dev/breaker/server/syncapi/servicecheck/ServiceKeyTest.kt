package dev.breaker.server.syncapi.servicecheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ServiceKeyTest {

    @Test
    fun `an empty key cannot be built`() {
        assertThrows("sync-api: an empty key", IllegalArgumentException::class.java) { ServiceKey("") }
    }

    @Test
    fun `a whitespace only key cannot be built`() {
        for (blank in listOf(" ", "   ", "\t", "\n", " \t\n ")) {
            assertThrows("sync-api: a whitespace key", IllegalArgumentException::class.java) { ServiceKey(blank) }
        }
    }

    @Test
    fun `the exact key matches`() {
        assertTrue("sync-api: the exact key", ServiceKey("test-key").matches("test-key"))
    }

    @Test
    fun `a missing or blank header never matches`() {
        val key = ServiceKey("test-key")
        assertFalse("sync-api: no header", key.matches(null))
        assertFalse("sync-api: an empty header", key.matches(""))
        assertFalse("sync-api: a whitespace header", key.matches("   "))
    }

    @Test
    fun `a key that is longer or shorter or differs by one character does not match`() {
        val key = ServiceKey("test-key")
        assertEquals("sync-api: a longer key", false, key.matches("test-key-extra"))
        assertEquals("sync-api: a shorter key", false, key.matches("test-ke"))
        assertEquals("sync-api: one character differs", false, key.matches("test-kex"))
        assertEquals("sync-api: a different case", false, key.matches("TEST-KEY"))
    }
}
