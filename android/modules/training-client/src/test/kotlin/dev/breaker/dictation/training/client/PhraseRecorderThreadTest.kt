package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder with a second thread playing the capture thread. Each test makes ONE extra
 * thread, waits for it with a bounded join, and never loops on a timer. Written by reading
 * only: not compiled, not run.
 */
class PhraseRecorderThreadTest {

    private val source = ScriptedAudioSource()
    private val recorder = PhraseRecorder(source)
    private val failure = AtomicReference<Throwable?>(null)

    private fun capture(body: () -> Unit): Thread = Thread {
        try {
            body()
        } catch (thrown: Throwable) {
            failure.set(thrown)
        }
    }

    @Test
    fun `frames delivered from another thread while finishing give a consistent result`() {
        val firstFramesIn = CountDownLatch(1)
        recorder.start(PhraseKind.WAKE)
        val capturing = capture {
            repeat(60) { index ->
                source.deliver(FloatArray(1_000) { 0.5f })
                if (index == 19) firstFramesIn.countDown()
            }
        }
        capturing.start()
        assertTrue(firstFramesIn.await(2_000, TimeUnit.MILLISECONDS))

        val result = recorder.finish()
        capturing.join(2_000)

        assertFalse(capturing.isAlive)
        assertNull(failure.get())
        when (result) {
            is RecordResult.Accepted -> {
                val pcm = result.sample.pcm
                assertEquals(0, pcm.size % 1_000)
                assertTrue(pcm.size in 20_000..RecorderBounds.MAX_SAMPLES)
                assertTrue(pcm.all { it == 0.5f })
            }
            is RecordResult.Rejected -> assertEquals(RejectReason.TOO_LONG, result.reason)
        }
    }

    @Test
    fun `finish does not hold the lock while the source stops so a capture thread can still deliver`() {
        val blockedInsideStop = AtomicBoolean(true)
        recorder.start(PhraseKind.WAKE)
        source.deliverSamples(RecorderBounds.MIN_SAMPLES)
        source.onStop = {
            val capturing = capture { source.deliverTo(0, FloatArray(10) { 0.5f }) }
            capturing.start()
            capturing.join(2_000)
            blockedInsideStop.set(capturing.isAlive)
        }

        val result = recorder.finish()

        assertFalse(blockedInsideStop.get())
        assertNull(failure.get())
        assertTrue(result is RecordResult.Accepted)
    }

    @Test
    fun `a frame from another thread after finish is dropped and the next recording is clean`() {
        recorder.start(PhraseKind.WAKE)
        source.deliverSamples(RecorderBounds.MIN_SAMPLES)
        recorder.finish()
        val late = capture { source.deliverTo(0, FloatArray(30_000) { 0.9f }) }
        late.start()
        late.join(2_000)
        recorder.start(PhraseKind.WAKE)
        source.deliverTo(1, FloatArray(RecorderBounds.MIN_SAMPLES) { 0.1f })

        val sample = (recorder.finish() as RecordResult.Accepted).sample

        assertFalse(late.isAlive)
        assertNull(failure.get())
        assertEquals(RecorderBounds.MIN_SAMPLES, sample.pcm.size)
    }
}
