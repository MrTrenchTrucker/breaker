package dev.breaker.server.syncapi

/** Runs the shutdown steps that main owns, in order: [stopInternal], then [stopSweep], then [closeDatabase].
 *
 * The public engine has already stopped when `start(wait = true)` returns (or never started), so it
 * is not a step here. The sign-in sweep stops after the internal engine and before the database
 * closes, so no sweep can reach a closed database. Each step runs in its own `try` so one failure
 * never skips the next: if the internal engine fails to stop, the sweep still stops and the database
 * is still closed. The first exception encountered is rethrown once all three steps have run.
 */
internal fun shutdownInOrder(
    stopInternal: () -> Unit,
    closeDatabase: () -> Unit,
    stopSweep: () -> Unit = {},
) {
    var firstFailure: Throwable? = null
    try {
        stopInternal()
    } catch (failure: Throwable) {
        firstFailure = failure
    }
    try {
        stopSweep()
    } catch (failure: Throwable) {
        if (firstFailure == null) firstFailure = failure
    }
    try {
        closeDatabase()
    } catch (failure: Throwable) {
        if (firstFailure == null) firstFailure = failure
    }
    firstFailure?.let { throw it }
}
