package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.port.AudioListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A read that fails with an [Error], not an exception, after a stop was asked for.
 *
 * Only an exception is the ordinary way a closed device ends a read. An [Error]
 * is a fault in the program or the runtime, and a stop must not hide it.
 *
 * A failure means an [Error] thrown from a read while a stop was pending was
 * swallowed as a clean end instead of being recorded.
 */
class MicCaptureStopDuringReadErrorTest {

    /** A fault that is an [Error] and nothing else, defined here so no real one is needed. */
    private class ReadFaultError : Error("audio: fake read fault that is an Error")

    /** Blocks inside its first read until it is closed, then throws [ReadFaultError]. */
    private class ErrorAfterCloseMicSource : MicSource {
        override val sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ
        override val channelCount: Int = 1
        val insideRead = CountDownLatch(1)
        val thrown = ReadFaultError()
        private val closed = CountDownLatch(1)

        override fun open() = Unit

        override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
            insideRead.countDown()
            check(closed.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                "audio: the fake device was never closed within ${WAIT_SECONDS}s"
            }
            throw thrown
        }

        override fun close() = closed.countDown()
    }

    @Test
    fun `an error thrown from a read after a stop was requested is still recorded`() {
        val source = ErrorAfterCloseMicSource()
        val capture = MicCapture(source = source)
        capture.start(AudioListener { })
        assertTrue(
            "audio: the capture thread never reached read(), so the test is not in the state it is about",
            source.insideRead.await(WAIT_SECONDS, TimeUnit.SECONDS),
        )
        capture.stop()
        awaitNoSessionThreads("stop() with a read that fails with an Error")

        assertNotNull("audio: an Error thrown from a read after a stop was swallowed", capture.failure)
        assertSame(
            "audio: the recorded failure is not the Error the read threw",
            source.thrown,
            capture.failure,
        )
    }
}
