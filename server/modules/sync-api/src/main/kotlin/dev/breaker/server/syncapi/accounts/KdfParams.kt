package dev.breaker.server.syncapi.accounts

/** The password-hardening cost the client chose; the server stores and returns it, it never runs it. */
internal data class KdfParams(val memoryKib: Int, val iterations: Int, val parallelism: Int)

/** Accepted range per field, both ends included: 64 to 256 MiB, 3 to 10 passes, 1 to 4 lanes. */
internal object KdfBounds {
    const val MIN_MEMORY_KIB = 65536
    const val MAX_MEMORY_KIB = 262144
    const val MIN_ITERATIONS = 3
    const val MAX_ITERATIONS = 10
    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 4

    // Version 1 fixes the whole derivation scheme, so no other version can be stored yet.
    const val REQUIRED_KDF_VERSION = 1
}

internal fun KdfParams.withinBounds(): Boolean =
    memoryKib >= KdfBounds.MIN_MEMORY_KIB && memoryKib <= KdfBounds.MAX_MEMORY_KIB &&
        iterations >= KdfBounds.MIN_ITERATIONS && iterations <= KdfBounds.MAX_ITERATIONS &&
        parallelism >= KdfBounds.MIN_PARALLELISM && parallelism <= KdfBounds.MAX_PARALLELISM
