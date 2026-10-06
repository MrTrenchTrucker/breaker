package dev.breaker.dictation.transport

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// STAGE 2d pure move: the parking double the wedged-lookup tests drive,
// out of TcpConnectivityProbeWedgedLookupTest. Same package, internal
// visibility, byte-identical bodies de-indented to top level.

/** Ceiling on a wedged lookup if nothing releases it; a safety valve. */
internal const val WEDGE_AWAIT_MS = 30_000L

/**
 * A resolver whose hosts can be made to hang, standing in for a name lookup
 * the platform has given up on handing back.
 *
 * A real `InetAddress.getAllByName` cannot be interrupted: the JDK hands the
 * name to the OS resolver and waits on it, so a lookup against a blackholed
 * resolver, a captive portal or a VPN mid-handshake parks that thread until
 * the platform gives up on its own. So this double does what the real thing
 * does — it **ignores the interrupt** the budget's `task.cancel(true)` sends
 * and keeps waiting. A double that honoured the interrupt would let the
 * worker free itself and the defects under test would not reproduce at all.
 *
 * Every host asked about is recorded on the way IN, which is what lets a
 * test see that a lookup was STARTED - and therefore see that a lookup
 * refused before it started is absent. Once released, a host resolves to a
 * loopback address carrying its name, so a scripted CONNECTED answer means
 * a genuine success path.
 */
internal class WedgeResolver {
    private val latches = LinkedHashMap<String, CountDownLatch>()
    private val asked = CopyOnWriteArrayList<String>()

    /** Wedges [host] until [release] is called, or until the await times out. */
    fun wedge(host: String): CountDownLatch = latches.getOrPut(host) { CountDownLatch(1) }

    /** True while [host]'s resolution is still inside its await. */
    fun parked(host: String): Boolean = latches[host]?.let { it.count > 0L } ?: false

    /**
     * How many lookups of [host] this resolver was actually asked for.
     *
     * Recorded on the way in, so a lookup that is refused before it is
     * started is visibly absent: the only way to start a second lookup is to
     * reach this resolver at all.
     */
    fun lookupsOf(host: String): Int = asked.count { it == host }

    fun release(host: String) {
        latches[host]?.countDown()
    }

    fun releaseAll() {
        latches.values.forEach { it.countDown() }
    }

    /** The resolver itself: wedges the scripted hosts, answers the rest. */
    fun asResolver(): HostResolver = HostResolver { host ->
        asked.add(host)
        latches[host]?.let { latch ->
            awaitIgnoringInterrupts(latch)
        }
        listOf(FakeHostResolver.loopbackFor(host))
    }

    /**
     * Awaits [latch] to completion however often the thread is interrupted.
     *
     * An `await` throws [InterruptedException] and CLEARS the interrupt
     * status, so swallowing it and awaiting again is what "interrupts do not
     * stop this lookup" means in code. The status is restored afterwards so
     * the park is not silently swallowing a shutdown request either, and
     * the wait is bounded so a test that forgets its `finally` fails with a
     * real answer rather than poisoning the rest of the JVM.
     */
    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WEDGE_AWAIT_MS)
        var interrupted = false
        while (latch.count > 0L && System.nanoTime() < deadline) {
            try {
                latch.await(WEDGE_AWAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
