package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** A take-3 recognizer whose decode delays 50 ms, proving the stateful limit
 * lets the recovery take succeed instead of racing a 1 ms timer against a
 * dispatcher hand-off. Under the old constant 1 ms limit this same variant
 * fails at the take-3 assertion; under the new stateful limit it passes. */
class OnDeviceSttEngineAbandonRecoveryDelayTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the claim.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val engines = ArrayList<OnDeviceSttEngine>()
    private val workers = ArrayList<RecordingWorkers>()
    private val parks = ArrayList<CompletableDeferred<Unit>>()

    private fun track(engine: OnDeviceSttEngine): OnDeviceSttEngine {
        engines.add(engine)
        return engine
    }

    @After
    fun stopEverything() {
        // Free every blocked decode first, so a failed assertion cannot leave a
        // worker parked; then close and wait for the workers to drain.
        parks.forEach { it.complete(Unit) }
        engines.forEach { it.close() }
        workers.forEach { it.awaitAllFinished() }
    }

    @Test
    fun `a recovery take whose decode outlasts the old one millisecond limit still returns a transcript`() {
        val parkUntil = CompletableDeferred<Unit>()
        parks.add(parkUntil)
        val log = Channel<String>(Channel.UNLIMITED)
        val parked = ParkingRecognizer(parkUntil, log)
        val slow = SlowRecoveryRecognizer()
        val loader = QueueLoader(listOf(parked, slow))
        val watcher = newSlotWatcher()
        val recovered = AtomicBoolean(false)
        // Record the limit take 1 asks for. Only take 1's request is GUARANTEED observable: take 1
        // returns solely because its timer fired, so the lambda ran. Take 3 returns because the
        // WORKER completed the outcome (DecodeBound.kt:109), and the finally at :134 then calls
        // armed.close() -> timer.cancel() (:26). limitMs is called INSIDE the launched coroutine
        // (:23), so on a starved runner take 3's timer can be cancelled before it ever starts and
        // its entry would be missing. Asserting on it would be a new timing race, so we do not.
        // The queue is concurrent because the lambda runs on the timer thread.
        val requestedLimits = ConcurrentLinkedQueue<Long>()
        val deadline = DecodeDeadline.afterAudio(limitMs = { audioMs ->
            val limit = if (recovered.get()) decodeLimitMs(audioMs) else 1L
            requestedLimits.add(limit)
            limit
        })
        val decodeWorkers = RecordingWorkers()
        workers.add(decodeWorkers)
        val engine = track(OnDeviceSttEngine(loader, watcher, deadline, decodeWorkers))

        // Take 1: the decode blocks, the 1 ms deadline wins, the slot returns the
        // timeout failure while the native call is still running.
        val first = engine.transcribe(clipRequest())
        assertEquals(
            "take 1 must return the timeout failure, not the late transcript",
            ErrorMapping.decodeTimedOut(),
            first,
        )
        assertTrue(
            "the abandoned decode must still be running while its native call is blocked",
            engine.abandonedDecodeRunning,
        )
        decodeWorkers.expectDispatched(1, "take 1 must hand exactly one decode to the workers")
        assertEquals(
            "take 1 must ask for the short 1 ms limit, not the production one",
            listOf(1L),
            requestedLimits.toList(),
        )

        // Take 2: refused with the existing busy sentence; it loads nothing and
        // starts no second decode next to the stuck one.
        val second = engine.transcribe(clipRequest())
        assertEquals(
            "take 2 must be refused with the existing busy sentence",
            ErrorMapping.decodeBusy(),
            second,
        )
        assertEquals("the busy refusal must not ask the loader for another model", 1, loader.loads)
        decodeWorkers.expectNoMoreDispatched("the busy refusal must not hand a decode to the workers")

        // Release the abandoned native call and wait for the worker's own return.
        parkUntil.complete(Unit)
        decodeWorkers.awaitOneFinished()
        assertFalse(
            "the abandoned state must clear once the native call returned",
            engine.abandonedDecodeRunning,
        )

        // The abandoned decode has returned and the slot is free. The recovery take is
        // not the one under the 1 ms deadline, so it gets the production limit.
        recovered.set(true)

        // Take 3: the slow recognizer decodes 50 ms; under the production limit it succeeds.
        val third = engine.transcribe(clipRequest())
        assertTrue(
            "take 3 must return a transcript once the abandoned decode returned, got $third",
            third is SttResult.Success,
        )
        assertEquals("the recovered take must load the slow recognizer", 2, loader.loads)
        assertEquals("the slow recognizer really decoded once", 1, slow.decodeCount)
    }

    /** A recognizer whose decode delays 50 ms (a coroutine delay, not Thread.sleep)
     * and then returns a transcript. Its decode count is readable after the call returns. */
    private class SlowRecoveryRecognizer : SherpaRecognizer {
        var decodeCount = 0

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            runBlocking { delay(50) }
            decodeCount++
            return SherpaTranscript("hello", emptyList(), "en")
        }

        override fun release() {}
    }
}
