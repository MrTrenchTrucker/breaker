package dev.breaker.dictation.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The thread that does nothing but read the device.
 *
 * Every read is converted, downmixed, resampled and suppressed before it is
 * written to the take's ring buffer, so the listener never receives a frame the
 * pipeline has not already finished with.
 */
internal class CaptureLoop(
    private val source: MicSource,
    private val pipeline: CapturePcmPipeline,
    /** The session's own progress: false once the take stops being read. */
    private val running: AtomicBoolean,
    /** Which take is current; a thread whose take is replaced retires. */
    private val session: AtomicLong,
    private val failureRef: AtomicReference<Throwable?>,
    private val readBufferSamples: Int,
) {

    /**
     * Reads the device into [ring] until the session ends, the device fails, or
     * this thread's take has been replaced.
     */
    fun run(ring: PcmRingBuffer, mine: Long) {
        val readBuffer = ShortArray(readBufferSamples)
        var consecutiveEmptyReads = 0
        try {
            while (running.get() && isCurrent(mine)) {
                val read = source.read(readBuffer, 0, readBuffer.size)
                // A read still in flight when the take is replaced must not be
                // delivered into the next take's buffer.
                if (!isCurrent(mine)) break
                when {
                    read < 0 -> throw MicSourceException(
                        "the microphone driver reported error code $read",
                    )
                    read == 0 -> {
                        // Nothing this time. Spin gently rather than hot, and
                        // give up if the device has genuinely stopped producing
                        // rather than sitting on a transient.
                        if (++consecutiveEmptyReads > MAX_CONSECUTIVE_EMPTY_READS) {
                            throw MicSourceException(
                                "the microphone produced no audio in " +
                                    "$consecutiveEmptyReads consecutive reads",
                            )
                        }
                        Thread.sleep(EMPTY_READ_PAUSE_MS)
                    }
                    else -> {
                        consecutiveEmptyReads = 0
                        ring.write(pipeline.convert(readBuffer, read))
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            if (isCurrent(mine)) failureRef.compareAndSet(null, e)
        } finally {
            // A take whose sessions have been replaced is not this thread's to
            // finish: the resampler, the suppressor and the flag all belong to
            // the take that replaced it now, and a straggler writing to them
            // would put this take's tail into the next one's audio.
            if (isCurrent(mine)) {
                // The take's tail, recovered before the dispatcher is told the
                // session is over.
                //
                // It runs here, on the capture thread, rather than in stop(),
                // for two reasons. stop() is on the caller's thread, and the
                // resampler is not thread-safe — a drain there would reach into
                // state the capture thread is still using. And a capture can
                // end without stop() at all, when the device fails or stops
                // producing audio: that ending reaches this same exit path, so a
                // drain in stop() would cover only the endings a caller asked
                // for. Both endings come through here, which is what makes the
                // tail whole either way.
                //
                // The drained samples go into the ring BEFORE the flag drops,
                // because the flag is what the dispatcher reads to decide the
                // take is finished. Drained afterwards, they would arrive behind
                // the final frame — delivered late, and out of order relative to
                // the frame that came before them.
                //
                // The drain is the one call on this exit path that runs
                // somebody else's code: the suppressor is a pluggable seam and
                // may be native, so it can throw where nothing else on the
                // capture thread can. Left bare, that throw would escape
                // run() with the flag still up — the dispatcher would never see
                // the take finish, and a take that ended quietly would leave
                // the caller waiting on a session that is over.
                //
                // So it is recorded the way every other capture-thread failure
                // is: through failureRef, where a caller reads it. A take
                // short by the drained tail is a take whose short is reported,
                // which is not the same as one whose short is invisible.
                try {
                    ring.write(pipeline.drainTail())
                } catch (e: Throwable) {
                    failureRef.compareAndSet(null, e)
                } finally {
                    // The dispatcher drains whatever is left, then sees the flag
                    // drop. A thread whose take has already been replaced must
                    // not drop the flag: the flag now belongs to the take that
                    // replaced it, and a straggler clearing it would end
                    // somebody else's capture.
                    //
                    // Nested finally, so that no future edit to the drain can
                    // put a throw between the take's last samples and the flag
                    // that says the take is over. The flag is the one thing
                    // here that must not be skippable.
                    running.set(false)
                }
            }
        }
    }

    private fun isCurrent(mine: Long): Boolean = session.get() == mine

    private companion object {
        private const val MAX_CONSECUTIVE_EMPTY_READS = 500
        private const val EMPTY_READ_PAUSE_MS = 1L
    }
}
