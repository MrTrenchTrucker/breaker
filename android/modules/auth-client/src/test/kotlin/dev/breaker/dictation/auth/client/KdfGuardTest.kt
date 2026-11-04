package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused
import org.junit.Test

class KdfGuardTest {
    // ---- guard bounds: at bound and one step past on both sides (assert exact KdfRefusal reason) ----

    @Test fun memoryKibAcceptsLowerBound() {
        assertTrue(KdfGuard.check(65536, 3, 1, 1))
    }

    @Test fun memoryKibRejectsBelowLowerBound() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(65535, 3, 1, 1) }
        assertEquals(KdfRefusal.MEMORY_BELOW_FLOOR, ex.reason)
    }

    @Test fun memoryKibAcceptsUpperBound() {
        assertTrue(KdfGuard.check(262144, 3, 1, 1))
    }

    @Test fun memoryKibRejectsAboveUpperBound() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(262145, 3, 1, 1) }
        assertEquals(KdfRefusal.MEMORY_ABOVE_CEILING, ex.reason)
    }

    @Test fun iterationsAcceptsLowerBound() {
        assertTrue(KdfGuard.check(65536, 3, 1, 1))
    }

    @Test fun iterationsRejectsBelowLowerBound() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(65536, 2, 1, 1) }
        assertEquals(KdfRefusal.ITERATIONS_BELOW_FLOOR, ex.reason)
    }

    @Test fun iterationsAcceptsUpperBound() {
        assertTrue(KdfGuard.check(65536, 10, 1, 1))
    }

    @Test fun iterationsRejectsAboveUpperBound() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(65536, 11, 1, 1) }
        assertEquals(KdfRefusal.ITERATIONS_ABOVE_CEILING, ex.reason)
    }

    @Test fun parallelismAcceptsLowerBound() {
        assertTrue(KdfGuard.check(65536, 3, 1, 1))
    }

    @Test fun parallelismRejectsBelowLowerBound() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(65536, 3, 0, 1) }
        assertEquals(KdfRefusal.PARALLELISM_BELOW_FLOOR, ex.reason)
    }

    @Test fun parallelismAcceptsUpperBound() {
        assertTrue(KdfGuard.check(65536, 3, 4, 1))
    }

    @Test fun parallelismRejectsAboveUpperBound() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(65536, 3, 5, 1) }
        assertEquals(KdfRefusal.PARALLELISM_ABOVE_CEILING, ex.reason)
    }

    // ---- kdf_version with in-range params is still refused ----

    @Test fun kdfVersionTwoWithInRangeParamsRefused() {
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(65536, 5, 2, 2) }
        assertEquals(KdfRefusal.UNSUPPORTED_VERSION, ex.reason)
    }

    @Test fun orderOutOfRangeVersionUsesRangeRefusal() {
        // memory below floor AND version != 1: the RANGE family must win over VERSION.
        val ex = assertThrows(KdfRefused::class.java) { KdfGuard.check(64, 5, 2, 2) }
        assertEquals(KdfRefusal.MEMORY_BELOW_FLOOR, ex.reason)
    }
}
