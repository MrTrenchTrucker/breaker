package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A microphone whose read fails once the device is closed under it, the way a
 * real one does.
 *
 * A real recording device that is closed while another thread is inside its
 * read does not politely return 0: that read fails, with an error code or with
 * an exception. [FakeMicSource] returns 0 when closed, so it cannot show what
 * the capture does with such a failure; this fake can.
 *
 * Nothing here sleeps. [insideRead] counts down when the capture thread is in
 * its first read of a take, and the read then waits on a signal that only
 * [close] sends, so a test is on the exact instant a caller's stop lands in the
 * middle of a read. Every wait is bounded, and a wait that runs out is a
 * failure with a message, never a hang.
 */
internal class ThrowingAfterCloseMicSource(
    /** What a read that was blocked when the device closed reports. */
    private val afterClose: AfterClose,
) : MicSource {

    /** The two shapes a read failure takes on a real device. */
    enum class AfterClose {
        /** The read throws a [MicSourceException]. */
        THROW,

        /** The read returns a negative driver error code. */
        NEGATIVE_CODE,
    }

    override val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ
    override val channelCount: Int = 1

    /**
     * When true, the next take's first read fails at once, with no close
     * involved (a genuine device fault). Read at every [open].
     */
    @Volatile
    var failImmediately: Boolean = false

    /**
     * Only with [failImmediately]: the failure is thrown, but stays in flight
     * until [close] is called, so the capture thread observes the failure only
     * after a stop has been requested.
     */
    @Volatile
    var holdFailureUntilClosed: Boolean = false

    @Volatile
    var insideRead = CountDownLatch(1)
        private set

    @Volatile
    private var closedSignal = CountDownLatch(1)

    @Volatile
    private var failNow = false

    @Volatile
    private var holdNow = false

    @Volatile
    var isOpen: Boolean = false
        private set

    @Volatile
    var closeCalls: Int = 0
        private set

    override fun open() {
        insideRead = CountDownLatch(1)
        closedSignal = CountDownLatch(1)
        failNow = failImmediately
        holdNow = holdFailureUntilClosed
        isOpen = true
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        insideRead.countDown()
        if (failNow) {
            if (!holdNow) throw MicSourceException(GENUINE_FAILURE)
            try {
                throw MicSourceException(GENUINE_FAILURE)
            } finally {
                awaitClosed()
            }
        }
        awaitClosed()
        return when (afterClose) {
            AfterClose.THROW -> throw MicSourceException(CLOSED_UNDER_READ)
            AfterClose.NEGATIVE_CODE -> CLOSED_UNDER_READ_CODE
        }
    }

    override fun close() {
        isOpen = false
        closeCalls++
        closedSignal.countDown()
    }

    private fun awaitClosed() {
        check(closedSignal.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            "audio: the fake device was never closed within ${WAIT_SECONDS}s"
        }
    }

    companion object {
        /** The message of a read that failed because the device was closed. */
        const val CLOSED_UNDER_READ = "audio: fake device closed during read"

        /** The code of a read that failed because the device was closed. */
        const val CLOSED_UNDER_READ_CODE = -3

        /** The message of a failure that has nothing to do with a stop. */
        const val GENUINE_FAILURE = "audio: fake device fault"
    }
}
