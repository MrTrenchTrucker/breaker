package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The decode-deadline brick, end to end, on a REAL clock.
 *
 * A fake recognizer whose decode BLOCKS until the test releases it, and a
 * decode deadline with a stateful limit: 1 ms for the abandoned take, then the
 * production limit for the recovery take after the abandoned decode has returned.
 * The sequence the engine must produce:
 *
 *  - take 1: the deadline wins while the native call is still blocked, so the
 *    slot returns [ErrorMapping.decodeTimedOut] and the decode is abandoned;
 *  - take 2: refused with the EXISTING [ErrorMapping.decodeBusy] sentence,
 *    loading nothing and starting no second decode;
 *  - the test releases the blocked native call and waits for the worker's own
 *    return (the [RecordingWorkers] "finished" report, which DecodeBound writes
 *    after it completes workerDone);
 *  - take 3: the slot is free again and the engine decodes.
 *
 * Unlike [OnDeviceSttEngineBoundTest], which fires the deadline by hand, this
 * test arms the real deadline, so it exercises the production arming path and
 * the recovery that follows the worker's return. No user-facing word is added:
 * the two failures are the existing [ErrorMapping] values.
 *
 * RED-FIRST: on the broken behaviour (the slot never recovers after the
 * abandoned native call returns, e.g. abandonedRunning ignores workerDone), the
 * take-3 assertion fails by name — it gets [ErrorMapping.decodeBusy] instead of
 * a transcript. On the base, the take-2 assertion is the guard for the other
 * half: an engine that dropped the busy refusal would start a second decode and
 * fail the busy equality and the load count.
 */
class OnDeviceSttEngineAbandonRecoveryTest {

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
    fun `a decode past its deadline times out, the next take is busy, and the engine recovers when the native call returns`() {
        val parkUntil = CompletableDeferred<Unit>()
        parks.add(parkUntil)
        val log = Channel<String>(Channel.UNLIMITED)
        val parked = ParkingRecognizer(parkUntil, log)
        val later = CountingRecognizer()
        val loader = QueueLoader(listOf(parked, later))
        val watcher = newSlotWatcher()
        // Take 1 must race a REAL 1 ms deadline against the blocked native call; that
        // is the behaviour under test and it stays. The recovery take must not race the
        // same 1 ms against a dispatcher hand-off, which a slow runner can lose. The
        // limit therefore has state: 1 ms until the abandoned decode has returned, then
        // the production limit for the clip. AtomicBoolean because the limit is read on
        // the deadline's Dispatchers.Default timer thread while the test thread flips it,
        // and kotlin.jvm.Volatile cannot annotate a local variable.
        val recovered = AtomicBoolean(false)
        val deadline = DecodeDeadline.afterAudio(limitMs = { audioMs ->
            if (recovered.get()) decodeLimitMs(audioMs) else 1L
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

        // Take 3: the slot is free again and the engine decodes a fresh recognizer.
        val third = engine.transcribe(clipRequest())
        assertTrue(
            "take 3 must return a transcript once the abandoned decode returned, got $third",
            third is SttResult.Success,
        )
        assertEquals("the recovered take must load a fresh recognizer", 2, loader.loads)
    }
}
