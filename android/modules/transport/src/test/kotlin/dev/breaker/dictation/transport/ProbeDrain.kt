package dev.breaker.dictation.transport

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Drains [ProbeExecutor] so a worker left parked by this test's own probe cannot poison later tests.
 *
 * [ProbeExecutor] is a process-wide singleton on a pool of [ProbeExecutor.MAX_WEDGED_PROBES] slots
 * plus one headroom thread. A worker still parked past this test's `finally` turns every later test
 * in this JVM into a discarded task and a false "not reachable". The drain runs a FRESH probe on a
 * FRESH clock against a host nothing has cached, so a `true` can only have come from a dial that really
 * ran on a shared worker.
 *
 * The answer function is the seam: by default it builds the standard fresh probe
 * (FakeClock, FakeHostResolver, RecordingConnector scripted CONNECTED) and calls `isServerReachable()`;
 * the helper test injects its own function so "keeps trying" and "throws when the pool never answers"
 * can both be made to happen without parking real workers.
 *
 * @param hostLabel the per-class host label prefix, so no answer cache hits between classes
 * @param boundMs the per-class drain bound (10 s for most classes, 5 s for SlotReleaseOnce)
 * @param retryParkMs the park after attempt one (SlotReleaseOnce parks DRAIN_RETRY_PARK_MS; others pass 0)
 * @param failureMessage the class's own failure text (bound and attempt count are appended)
 * @param answer a function host name -> true/false; default builds the standard fresh probe
 *               and calls `isServerReachable()`
 */
internal fun drainProbeExecutor(
    hostLabel: String,
    boundMs: Long,
    retryParkMs: Long = 0L,
    failureMessage: String,
    answer: (String) -> Boolean = { host ->
        val drain = TcpConnectivityProbe(
            { "https://$host.invalid" },
            FakeClock(1_000L),
            FakeHostResolver(),
            RecordingConnector(script = listOf(ProbeOutcome.CONNECTED)),
        )
        drain.isServerReachable()
    },
) {
    val startedAt = System.nanoTime()
    val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(boundMs)
    var attempts = 0
    while (System.nanoTime() < deadline) {
        attempts++
        val host = "$hostLabel-$attempts"
        if (answer(host)) return
        if (retryParkMs > 0L) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(retryParkMs))
        }
    }
    val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    throw AssertionError(
        "$failureMessage (bound ${boundMs} ms, attempts $attempts, waited ${waitedMs} ms)"
    )
}
