package dev.breaker.dictation.updater

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The create-once holder: one claim per ledger key, and each test gives back the key it took. */
internal class CreateGuardTest {

    @Test
    fun `a second claim of the same ledger key throws`() {
        CreateGuard.claim(KEY_ALPHA)
        try {
            val thrown = runCatching { CreateGuard.claim(KEY_ALPHA) }.exceptionOrNull()
            assertTrue("a second claim of the same key must throw", thrown is IllegalStateException)
        } finally {
            CreateGuard.release(KEY_ALPHA)
        }
    }

    @Test
    fun `a claim keyed by another ledger path is allowed`() {
        CreateGuard.claim(KEY_ALPHA)
        try {
            val thrown = runCatching { CreateGuard.claim(KEY_BETA) }.exceptionOrNull()
            assertNull(thrown)
        } finally {
            CreateGuard.release(KEY_ALPHA)
            CreateGuard.release(KEY_BETA)
        }
    }

    @Test
    fun `a released ledger key can be claimed again`() {
        CreateGuard.claim(KEY_ALPHA)
        CreateGuard.release(KEY_ALPHA)
        try {
            val thrown = runCatching { CreateGuard.claim(KEY_ALPHA) }.exceptionOrNull()
            assertNull(thrown)
        } finally {
            CreateGuard.release(KEY_ALPHA)
        }
    }
}

/** A ledger key used by the guard tests. */
private const val KEY_ALPHA: String = "ledger-alpha"

/** A second ledger key used by the guard tests. */
private const val KEY_BETA: String = "ledger-beta"
