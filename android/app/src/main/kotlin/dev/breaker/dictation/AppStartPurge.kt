package dev.breaker.dictation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Schedules the one start-up purge on [scope], off the caller's thread.
 *
 * [purge] runs once inside a coroutine on [scope]. A failure is caught and
 * handed to [onFailure] exactly once — it is never allowed to escape the
 * coroutine, so a damaged database or a locked file cannot reach the thread's
 * uncaught-exception handler and take the app down with it. A
 * [CancellationException] is rethrown, not reported: it means the scope was
 * cancelled, not that the purge failed, and the scope's own cancellation
 * handling owns it. [onFailure] is the caller's concern (the app logs it);
 * this function only decides when and how many times it is called.
 */
fun scheduleAppStartPurge(
    scope: CoroutineScope,
    purge: () -> Unit,
    onFailure: (Throwable) -> Unit,
): Job = scope.launch {
    try {
        purge()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
    }
}
