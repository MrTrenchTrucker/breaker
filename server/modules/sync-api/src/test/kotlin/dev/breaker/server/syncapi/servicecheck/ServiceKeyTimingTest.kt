package dev.breaker.server.syncapi.servicecheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// the service-key check must not early-return on a missing header. A null, a blank,
// a wrong key and the exact key all run the same constant-time compare, so they cost the
// same. A counting [ServiceKey] proves the compare runs on every presented path (a boolean
// assertion alone cannot tell a compare from an early return).
internal class ServiceKeyTimingTest {

    /** A [ServiceKey] that counts every constant-time compare, then delegates. */
    private class CountingKey(text: String) : ServiceKey(text) {
        var compareCalls: Int = 0
        override fun compare(presented: ByteArray, key: ByteArray): Boolean {
            compareCalls += 1
            return super.compare(presented, key)
        }
    }

    private fun matchesOnce(key: CountingKey, presented: String?): Boolean {
        key.compareCalls = 0
        val result = key.matches(presented)
        return result
    }

    @Test
    fun `a missing header runs the constant-time compare exactly once`() {
        val key = CountingKey("test-key")
        val result = matchesOnce(key, null)
        assertEquals("a missing header runs the compare once (no early return)", 1, key.compareCalls)
        assertFalse("a missing header does not match", result)
    }

    @Test
    fun `a blank header runs the constant-time compare exactly once`() {
        for (blank in listOf("", "   ", "\t")) {
            val key = CountingKey("test-key")
            val result = matchesOnce(key, blank)
            assertEquals("a blank header ($blank) runs the compare once", 1, key.compareCalls)
            assertFalse("a blank header does not match", result)
        }
    }

    @Test
    fun `a wrong key runs the constant-time compare exactly once`() {
        for (wrong in listOf("test-key-extra", "test-ke", "test-kex", "TEST-KEY")) {
            val key = CountingKey("test-key")
            val result = matchesOnce(key, wrong)
            assertEquals("a wrong key ($wrong) runs the compare once", 1, key.compareCalls)
            assertFalse("a wrong key does not match", result)
        }
    }

    @Test
    fun `the exact key runs the constant-time compare exactly once and matches`() {
        val key = CountingKey("test-key")
        val result = matchesOnce(key, "test-key")
        assertEquals("the exact key runs the compare once", 1, key.compareCalls)
        assertTrue("the exact key matches", result)
    }
}
