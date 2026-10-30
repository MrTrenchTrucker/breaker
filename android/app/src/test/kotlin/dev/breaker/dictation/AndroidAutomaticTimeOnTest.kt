package dev.breaker.dictation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the retention rule's fail-safe at the read seam [automaticTimeFrom]: a
 * resolver read that cannot answer is read as automatic time on (true), because
 * suspecting a jump only ever *delays* a purge, and a false "manual" would delay
 * one it did not need to. The `read` seam keeps the platform call out of the test.
 */
internal class AndroidAutomaticTimeOnTest {

    @Test
    fun `a resolver read of one means automatic time is on`() {
        assertTrue(automaticTimeFrom { 1 })
    }

    @Test
    fun `a resolver read of zero means automatic time is off`() {
        assertFalse(automaticTimeFrom { 0 })
    }

    @Test
    fun `a resolver that throws is read as automatic time on`() {
        assertTrue(automaticTimeFrom { throw SecurityException("no auto time") })
    }
}
