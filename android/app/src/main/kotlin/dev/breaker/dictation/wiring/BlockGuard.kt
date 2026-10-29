package dev.breaker.dictation.wiring

/**
 * Runs [block] and swallows any exception so a failing tile or notification cannot stop a take or reach the
 * caller. Moved out of [TileCoordinator] to keep that file within its line cap; every call site is
 * unchanged, so this body is byte-identical to what it replaced.
 */
internal fun guarded(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        // A tile or notification that fails must not stop the take or reach the caller.
    }
}
