package dev.breaker.server.syncapi

/** Runs the two shutdown steps that main owns, in order: [stopInternal], then [closeDatabase].
 *
 * The public engine has already stopped when `start(wait = true)` returns (or never started), so it
 * is not a step here. Each step runs in its own `try` so one failure never skips the next: if the
 * internal engine fails to stop, the database is still closed. The first exception encountered is
 * rethrown once both steps have run.
 */
internal fun shutdownInOrder(
    stopInternal: () -> Unit,
    closeDatabase: () -> Unit,
) {
    var firstFailure: Throwable? = null
    try {
        stopInternal()
    } catch (failure: Throwable) {
        firstFailure = failure
    }
    try {
        closeDatabase()
    } catch (failure: Throwable) {
        if (firstFailure == null) firstFailure = failure
    }
    firstFailure?.let { throw it }
}
