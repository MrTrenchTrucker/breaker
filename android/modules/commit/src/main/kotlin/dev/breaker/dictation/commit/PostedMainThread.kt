package dev.breaker.dictation.commit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val NOT_RUN_MESSAGE: String = "commit: the main thread did not run the block in time"

/** How a block that ran on the main thread ended. It is carried as a value so the caller sees the very same throwable. */
private sealed class Finished<out T>

private class Returned<out T>(val value: T) : Finished<T>()

private class Threw(val failure: Throwable) : Finished<Nothing>()

/**
 * Runs a block on the main thread and frees the caller at the deadline, whatever
 * the main thread does.
 *
 * A block that has started on the main thread is waited for, and the call
 * reports what the block did. A block that has not started when the deadline
 * passes is dropped: it never runs, and the call fails as not responding. The
 * deadline only frees the caller; it cannot stop a step that is already running.
 *
 * Each call holds one claim with three states. The queued task and the deadline
 * race to change it from queued: the task to running, the deadline to abandoned.
 * Exactly one of them wins, so a block either never starts or is waited for.
 */
internal class PostedMainThread(
    private val poster: BlockPoster,
    private val onMainThread: () -> Boolean,
    private val deadline: HopDeadline,
) : MainThread {

    private enum class Claim { QUEUED, RUNNING, ABANDONED }

    override fun <T> call(block: () -> T): T {
        // Waiting for a task that only this very thread could run would never end.
        if (onMainThread()) {
            return block()
        }

        val claim = MutableStateFlow(Claim.QUEUED)
        val outcome = CompletableDeferred<Finished<T>>()
        val posted: Boolean = poster.post(
            Runnable {
                // Claim first: when the deadline already won, the block never starts.
                if (claim.compareAndSet(Claim.QUEUED, Claim.RUNNING)) {
                    val finished: Finished<T> = try {
                        Returned(block())
                    } catch (e: Throwable) {
                        Threw(e)
                    }
                    outcome.complete(finished)
                }
            },
        )
        if (!posted) {
            // The main thread will never run the task: fail now, the deadline is not awaited.
            claim.compareAndSet(Claim.QUEUED, Claim.ABANDONED)
            throw MainThreadUnavailable(NOT_RUN_MESSAGE)
        }

        val done: Finished<T> = try {
            // The one blocking wait of the module: the port's commit is a blocking
            // call and its caller is a worker thread, never the main thread. The
            // wait ends on the task's result or on the deadline, never on the
            // main thread alone.
            runBlocking {
                // Undispatched, so a deadline that has already passed claims before the
                // wait starts; a real deadline still suspends inside its delay.
                val timer = launch(start = CoroutineStart.UNDISPATCHED) {
                    deadline.elapsed()
                    if (claim.compareAndSet(Claim.QUEUED, Claim.ABANDONED)) {
                        outcome.complete(Threw(MainThreadUnavailable(NOT_RUN_MESSAGE)))
                    }
                }
                try {
                    outcome.await()
                } finally {
                    timer.cancel()
                }
            }
        } catch (_: InterruptedException) {
            claim.compareAndSet(Claim.QUEUED, Claim.ABANDONED)
            // The wait cleared the caller's interrupt flag when it threw; put it
            // back so the code further up the stack that set it still sees it.
            Thread.currentThread().interrupt()
            throw MainThreadUnavailable(NOT_RUN_MESSAGE)
        }

        return when (done) {
            is Returned<T> -> done.value
            is Threw -> throw done.failure
        }
    }
}
