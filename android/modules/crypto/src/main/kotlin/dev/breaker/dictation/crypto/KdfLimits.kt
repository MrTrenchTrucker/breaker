package dev.breaker.dictation.crypto

import dev.breaker.dictation.core.model.KdfParams
import dev.breaker.dictation.core.model.KdfRefusal
import dev.breaker.dictation.core.model.KdfRefused

/**
 * The floor and the ceiling for every key-derivation setting, in one place.
 *
 * The floors are the lowest values ADR-006 allows. The ceilings exist because a
 * stored value handed back at login could be buggy or hostile and must not make
 * the phone try to allocate it. Both ends are inclusive.
 */
internal object KdfLimits {
    /** Lowest memory cost accepted, in KiB (64 MiB, the ADR-006 floor). */
    const val MEMORY_MIN_KIB = 65536

    /** Highest memory cost accepted, in KiB (256 MiB). */
    const val MEMORY_MAX_KIB = 262144

    /** Lowest number of passes accepted (the ADR-006 floor). */
    const val ITERATIONS_MIN = 3

    /** Highest number of passes accepted. */
    const val ITERATIONS_MAX = 10

    /** Lowest number of lanes accepted (the ADR-006 floor). */
    const val PARALLELISM_MIN = 1

    /** Highest number of lanes accepted. */
    const val PARALLELISM_MAX = 4

    /** The tag length required, in bytes. */
    const val OUTPUT_BYTES = 32

    /** The salt length required, in bytes. */
    const val SALT_BYTES = 16

    /** The only KDF version number supported. */
    const val SUPPORTED_VERSION = 1
}

/**
 * Throws [KdfRefused] for the first rule that [salt] or [params] breaks, and
 * returns normally when every rule holds.
 *
 * The rules are checked in this order: memory, iterations, parallelism, tag
 * length, salt length, version. Within a setting the floor is checked before
 * the ceiling. The version is checked last on purpose, so a claimed version
 * never relaxes any range check.
 *
 * This runs before any derivation work, so a refused value costs nothing: no
 * password is touched and no memory is allocated for it. It does nothing else
 * and changes nothing.
 */
internal fun checkKdfParams(salt: ByteArray, params: KdfParams) {
    if (params.memoryKib < KdfLimits.MEMORY_MIN_KIB) throw KdfRefused(KdfRefusal.MEMORY_BELOW_FLOOR)
    if (params.memoryKib > KdfLimits.MEMORY_MAX_KIB) throw KdfRefused(KdfRefusal.MEMORY_ABOVE_CEILING)
    if (params.iterations < KdfLimits.ITERATIONS_MIN) throw KdfRefused(KdfRefusal.ITERATIONS_BELOW_FLOOR)
    if (params.iterations > KdfLimits.ITERATIONS_MAX) throw KdfRefused(KdfRefusal.ITERATIONS_ABOVE_CEILING)
    if (params.parallelism < KdfLimits.PARALLELISM_MIN) throw KdfRefused(KdfRefusal.PARALLELISM_BELOW_FLOOR)
    if (params.parallelism > KdfLimits.PARALLELISM_MAX) throw KdfRefused(KdfRefusal.PARALLELISM_ABOVE_CEILING)
    if (params.outputLength != KdfLimits.OUTPUT_BYTES) throw KdfRefused(KdfRefusal.OUTPUT_LENGTH_NOT_32)
    if (salt.size != KdfLimits.SALT_BYTES) throw KdfRefused(KdfRefusal.BAD_SALT_LENGTH)
    if (params.version != KdfLimits.SUPPORTED_VERSION) throw KdfRefused(KdfRefusal.UNSUPPORTED_VERSION)
}
