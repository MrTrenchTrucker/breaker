package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the capture loop leaves behind when its thread is interrupted inside a read.
 *
 * The loop is run directly, on a thread the test owns, so the thread's
 * interrupt status after [CaptureLoop.run] returns can be looked at. The fake
 * device throws [InterruptedException] itself, the way a blocked read does when
 * its thread is interrupted. Whether or not a stop was asked for, the loop must
 * hand the interrupt back to its thread, record no failure, and still end the
 * take.
 *
 * A failure means an interrupt that arrived inside a read was swallowed as an
 * ordinary end of the take, so the thread lost its interrupt status, or it was
 * recorded as a fault, or the take was not ended.
 */
class CaptureLoopInterruptTest {

    /** What the capture thread saw when [CaptureLoop.run] returned. */
    private class Outcome {
        @Volatile var interruptedAtEnd: Boolean? = null
        @Volatile var escaped: Throwable? = null
    }

    /**
     * A device whose first read raises [fault]; when [stopFlag] is given the
     * read first asks for a stop, as a stop that lands while the read is in
     * flight does. The signal [insideRead] counts down once the read is under way.
     */
    private class FaultingReadSource(
        private val fault: () -> Throwable,
        private val stopFlag: AtomicBoolean?,
    ) : MicSource {
        override val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ
        override val channelCount: Int = 1
        val insideRead = CountDownLatch(1)

        override fun open() = Unit

        override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
            insideRead.countDown()
            stopFlag?.set(true)
            throw fault()
        }

        override fun close() = Unit
    }

    /** Everything one run of the loop needs, and what it leaves in them. */
    private class Rig(stopped: Boolean, fault: () -> Throwable) {
        val running = AtomicBoolean(true)
        val stopRequested = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val source = FaultingReadSource(fault, if (stopped) stopRequested else null)
        val outcome = Outcome()
        private val session = AtomicLong(1L)
        private val loop = CaptureLoop(
            source = source,
            pipeline = CapturePcmPipeline(
                channelCount = 1,
                sampleRateHz = AudioFormat.SAMPLE_RATE_HZ,
                suppressor = PassThroughNoiseSuppressor,
            ),
            running = running,
            stopRequested = stopRequested,
            session = session,
            failureRef = failure,
            readBufferSamples = READ_SAMPLES,
        )

        /** Runs the loop on its own thread and waits, bounded, for it to return. */
        fun runToEnd(what: String) {
            val thread = Thread({
                try {
                    loop.run(PcmRingBuffer(capacitySamples = RING_SAMPLES), 1L)
                } catch (t: Throwable) {
                    outcome.escaped = t
                }
                outcome.interruptedAtEnd = Thread.currentThread().isInterrupted
            }, "audio-test-capture-loop")
            thread.isDaemon = true
            thread.start()
            assertTrue(
                "audio: the loop's read was never reached ($what), so the test is not in the state it is about",
                source.insideRead.await(WAIT_SECONDS, TimeUnit.SECONDS),
            )
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse(
                "audio: the loop did not return within ${WAIT_SECONDS}s ($what)",
                thread.isAlive,
            )
            assertNull("audio: run() let a throwable escape ($what): ${outcome.escaped}", outcome.escaped)
        }
    }

    @Test
    fun `an interrupt inside a read that a stop is closing is handed back to the thread`() {
        val rig = Rig(stopped = true) { InterruptedException("audio: fake interrupt inside a stopping read") }
        rig.runToEnd("interrupt, stop requested")

        assertEquals(
            "audio: the capture thread lost its interrupt status when the interrupt landed in a read during a stop",
            true,
            rig.outcome.interruptedAtEnd,
        )
        assertNull(
            "audio: an interrupt during a stopping read was recorded as a failure: ${rig.failure.get()}",
            rig.failure.get(),
        )
        assertFalse(
            "audio: the take was not ended after an interrupt during a stopping read",
            rig.running.get(),
        )
    }

    @Test
    fun `an interrupt inside a read with no stop requested is handed back to the thread`() {
        val rig = Rig(stopped = false) { InterruptedException("audio: fake interrupt inside a read") }
        rig.runToEnd("interrupt, no stop requested")

        assertFalse("audio: this control must run with no stop requested", rig.stopRequested.get())
        assertEquals(
            "audio: the capture thread lost its interrupt status when the interrupt landed in a read",
            true,
            rig.outcome.interruptedAtEnd,
        )
        assertNull(
            "audio: an interrupt during a read was recorded as a failure: ${rig.failure.get()}",
            rig.failure.get(),
        )
        assertFalse("audio: the take was not ended after an interrupt during a read", rig.running.get())
    }

    @Test
    fun `an ordinary failure of a read that a stop is closing leaves the thread uninterrupted`() {
        val rig = Rig(stopped = true) { MicSourceException("audio: fake device closed under the read") }
        rig.runToEnd("ordinary failure, stop requested")

        assertEquals(
            "audio: a stop-closed read failure set an interrupt status that nothing had raised",
            false,
            rig.outcome.interruptedAtEnd,
        )
        assertNull(
            "audio: a failure caused by the stop closing the device was recorded: ${rig.failure.get()}",
            rig.failure.get(),
        )
        assertFalse("audio: the take was not ended after a stop-closed read failure", rig.running.get())
    }

    private companion object {
        private const val READ_SAMPLES = 160
        private const val RING_SAMPLES = 1_600
    }
}
