package dev.breaker.dictation.wiring

/**
 * Schedules sync drains behind one small interface with exactly two operations: run a block
 * as soon as possible, or run it after a delay. Why two and not three: there is no third
 * operation. Cancellation is the [Handle] that `runAfter` returns — only one delayed run is
 * armed at a time, so nothing else needs cancelling. `runSoon` submits at once and needs
 * nothing to cancel. Coalescing lives in the worker, not here, so a fake scheduler drives
 * the whole thing in a JVM test. No Android types appear in this interface.
 *
 * The executor is injected because scheduling is the one place that touches the clock, and
 * the `Background` port carries each run onto the serial background thread so the drain
 * never runs on the caller's thread.
 */
interface SyncScheduler {
    /** Run [block] as soon as possible on the serial background thread. */
    fun runSoon(block: () -> Unit)

    /**
     * Run [block] after [delayMs] milliseconds on the serial background thread.
     * Returns a handle; calling [Handle.cancel] before the delay elapses drops the block.
     */
    fun runAfter(delayMs: Long, block: () -> Unit): Handle

    interface Handle {
        fun cancel()
    }
}

/**
 * The real [SyncScheduler]: an injected scheduled executor for the delay, the [Background]
 * port (SerialBackground in production) for the run itself.
 *
 * The executor is never created here. `host/AppThreads.kt` is "the only file in the app
 * that names a handler, a looper, an executor or a thread" (AppThreads.kt:13), and it stays
 * that way — whoever wires the app creates the executor in `host/` and passes it in together
 * with `SerialBackground` as the `Background`. This class only submits through the port, so
 * the drain itself always lands on the one serial thread and never on the caller's thread.
 */
internal class ScheduledSyncScheduler(
    private val executor: java.util.concurrent.ScheduledExecutorService,
    private val background: Background,
) : SyncScheduler {

    override fun runSoon(block: () -> Unit) {
        background.submit(block)
    }

    override fun runAfter(delayMs: Long, block: () -> Unit): SyncScheduler.Handle {
        val future = executor.schedule({ background.submit(block) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return object : SyncScheduler.Handle {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }
}
