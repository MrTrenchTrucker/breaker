package dev.breaker.dictation.host

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One bounded refresh of the server address probe per open of Settings, and one redraw when it
 * finishes. The work runs on its own named daemon thread; [onDone] always runs on that worker,
 * never on the caller's. A run already in flight is neither joined nor repeated: the next start
 * after it finishes begins a fresh one.
 */
internal class ProbeStatusRefresh(
    private val refreshProbe: () -> Unit,
    private val startThread: (() -> Unit) -> Unit = { work ->
        OneShotBackground("breaker-probe-status").submit(work)
    },
) {
    private val inFlight = AtomicBoolean(false)

    /** Starts one refresh unless a run is already going; [onDone] is called once when it settles. */
    fun start(onDone: () -> Unit) {
        if (!inFlight.compareAndSet(false, true)) return
        startThread {
            try {
                refreshProbe()
            } catch (_: Throwable) {
                // A failed probe keeps the cached answers as they were; the redraw still happens.
            } finally {
                inFlight.set(false)
            }
            onDone()
        }
    }
}
