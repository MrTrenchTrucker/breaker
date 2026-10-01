package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The thread that drains the ring buffer into fixed-size frames.
 *
 * It exists so a listener that takes 200 ms on a frame cannot stall the driver:
 * a stalled driver overruns and the platform drops audio with no record of it.
 * The overflow lands in [PcmRingBuffer] instead, which counts it.
 */
internal class DispatchLoop(
    /** Frame size handed to the listener, in samples. */
    private val frameSize: Int,
    /** The session's own progress: false once the take stops being read. */
    private val running: AtomicBoolean,
    /** Which take is current; a thread whose take is replaced retires. */
    private val session: AtomicLong,
    private val failureRef: AtomicReference<Throwable?>,
) {

    /**
     * Delivers [ring]'s audio to [target] in whole frames, then the take's
     * remainder short rather than padded.
     */
    fun run(ring: PcmRingBuffer, target: AudioListener, mine: Long) {
        val readBuffer = FloatArray(frameSize * 2)
        var pending = FloatArray(frameSize)
        var pendingCount = 0
        try {
            while (true) {
                // A take's audio belongs to that take, so a thread that has
                // been replaced stops rather than draining on.
                if (!isCurrent(mine)) break
                val read = ring.read(readBuffer, 0, readBuffer.size, block = true)
                if (read == 0) {
                    // Nothing waiting and the session is over: the last partial
                    // frame goes out short rather than padded with silence.
                    if (!running.get()) break
                    continue
                }
                var consumed = 0
                while (consumed < read) {
                    // Checked per frame, not per read: a listener that blocks
                    // past the stop can come back holding a whole read of the
                    // previous take, and the rest of it is that take's audio
                    // too, not this one's. It cannot be put back — the buffer
                    // is gone — but the buffer is this take's own, so those
                    // samples were this take's to begin with and the take that
                    // replaced it is not short because of them.
                    if (!isCurrent(mine)) return
                    val take = minOf(frameSize - pendingCount, read - consumed)
                    System.arraycopy(readBuffer, consumed, pending, pendingCount, take)
                    pendingCount += take
                    consumed += take
                    if (pendingCount == frameSize) {
                        target.onFrame(pending.copyOf())
                        pendingCount = 0
                    }
                }
            }
            // Only while this is still the current take: once the next one has
            // started these are the previous take's samples, and delivering them
            // now would be a late frame from a take that already ended.
            if (pendingCount > 0 && isCurrent(mine)) {
                target.onFrame(pending.copyOf(pendingCount))
            }
        } catch (e: InterruptedException) {
            // A read interrupted from outside the capture is the thread being
            // torn down, not the listener misbehaving; it is not a failure.
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            // A straggler that throws belongs to a replaced take; recording its
            // failure and clearing the flag would end the take that replaced it.
            if (isCurrent(mine)) {
                failureRef.compareAndSet(null, e)
                running.set(false)
            }
        }
    }

    private fun isCurrent(mine: Long): Boolean = session.get() == mine
}
