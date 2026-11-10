package dev.breaker.dictation.wiring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for syncAllowed. Inputs are raw flag ints; no Android class is loaded.
 *
 * 2 = ApplicationInfo.FLAG_DEBUGGABLE (debuggable build)
 * 1 = ApplicationInfo.FLAG_SYSTEM (a non-debuggable flag bit)
 * 3 = FLAG_SYSTEM + FLAG_DEBUGGABLE together (mixed bits) */
internal class SyncAllowedTest {

    @Test
    fun `debuggable flag alone is allowed`() {
        assertTrue("syncAllowed(FLAG_DEBUGGABLE) must be true", syncAllowed(2))
    }

    @Test
    fun `no flags is not allowed`() {
        assertFalse("syncAllowed(0) must be false", syncAllowed(0))
    }

    @Test
    fun `a non debuggable flag bit alone is not allowed`() {
        // 1 = FLAG_SYSTEM, not FLAG_DEBUGGABLE
        assertFalse("syncAllowed(FLAG_SYSTEM) must be false", syncAllowed(1))
    }

    @Test
    fun `mixed bits with debuggable set is allowed`() {
        // 3 = FLAG_SYSTEM (1) + FLAG_DEBUGGABLE (2)
        assertTrue("syncAllowed(FLAG_SYSTEM or FLAG_DEBUGGABLE) must be true", syncAllowed(3))
    }
}
