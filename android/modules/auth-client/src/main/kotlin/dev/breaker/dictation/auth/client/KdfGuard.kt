package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused

/**
 * ADR-006 client floor. check() validates range FIRST (memory, iterations, parallelism) and then
 * kdf_version == 1; any out-of-range value is refused before version is read, so an out-of-range
 * version-2 response fails with a RANGE refusal rather than UNSUPPORTED_VERSION. On failure it
 * throws core's own KdfRefused carrying the exact matching reason.
 */
internal object KdfGuard {
    const val MEMORY_MIN_KIB = 65536
    const val MEMORY_MAX_KIB = 262144
    const val ITER_MIN = 3
    const val ITER_MAX = 10
    const val PARALLELISM_MIN = 1
    const val PARALLELISM_MAX = 4
    const val ALLOWED_VERSION = 1

    /** The Long-taking entry: wire numbers are compared as Long (never truncated to Int). */
    fun check(memoryKib: Long, iterations: Long, parallelism: Long, kdfVersion: Long): Boolean {
        if (memoryKib < MEMORY_MIN_KIB.toLong()) throw KdfRefused(KdfRefusal.MEMORY_BELOW_FLOOR)
        if (memoryKib > MEMORY_MAX_KIB.toLong()) throw KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
        if (iterations < ITER_MIN.toLong()) throw KdfRefused(KdfRefusal.ITERATIONS_BELOW_FLOOR)
        if (iterations > ITER_MAX.toLong()) throw KdfRefused(KdfRefusal.ITERATIONS_ABOVE_CEILING)
        if (parallelism < PARALLELISM_MIN.toLong()) throw KdfRefused(KdfRefusal.PARALLELISM_BELOW_FLOOR)
        if (parallelism > PARALLELISM_MAX.toLong()) throw KdfRefused(KdfRefusal.PARALLELISM_ABOVE_CEILING)
        if (kdfVersion != ALLOWED_VERSION.toLong()) throw KdfRefused(KdfRefusal.UNSUPPORTED_VERSION)
        return true
    }

    fun check(memoryKib: Int, iterations: Int, parallelism: Int, kdfVersion: Int): Boolean =
        check(memoryKib.toLong(), iterations.toLong(), parallelism.toLong(), kdfVersion.toLong())
}
