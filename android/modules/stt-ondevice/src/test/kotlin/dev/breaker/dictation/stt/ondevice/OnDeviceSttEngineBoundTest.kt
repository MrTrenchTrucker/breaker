package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * How the engine bounds a decode that never returns: the timeout failure, when
 * the deadline is armed, and the refusal while an abandoned decode still runs.
 *
 * No test here reads a clock. A decode is parked on a signal that the test
 * sends only AFTER it fired the deadline by hand. An engine that honours the
 * deadline has already returned the timeout failure by then; an engine that
 * ignores it returns the late transcript, and the assertion fails by name.
 *
 * What no test here can prove: that the blocked caller returned while the
 * native decode was still parked. Any wait for that would end only by the
 * safety net if the engine were broken, because no event separates "never"
 * from "not yet" without a clock. The suite proves the value (the timeout
 * failure, not the late transcript), who releases the recognizer, the busy
 * refusal and the recovery.
 */
class OnDeviceSttEngineBoundTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the claim.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    // Blocking calls must not run on the test thread. This scope is not a child of the
    // test, so a parked call can never keep the test from ending.
    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rigs = ArrayList<BoundRig>()

    private fun track(rig: BoundRig): BoundRig {
        rigs.add(rig)
        return rig
    }

    @After
    fun stopEverything() {
        rigs.forEach { it.stop() }
        callers.cancel()
    }

    /** Runs [block]; an exception that escapes the engine becomes an assertion that names [claim]. */
    private inline fun <T> callOrFail(claim: String, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            throw AssertionError("$claim: the call threw $e instead of returning a result", e)
        }

    /**
     * One blocking transcribe made on another scope, so a broken engine cannot park the test thread.
     * The wait ends by name when the call is never handed to the slot, or when a second task is handed
     * to the slot the call already holds.
     */
    private fun transcribeOffThread(rig: BoundRig, claim: String, samples: Int = 16_000): SttResult {
        val call = callers.async { callOrFail(claim) { rig.engine.transcribe(clipRequest(samples = samples)) } }
        rig.awaitSlotTask(call, "$claim must be handed to the engine's slot")
        return awaitUnlessStuck(call, rig.watcher, "$claim handed a second task to its slot")
    }

    @Test
    fun `transcribe returns the timeout failure when the deadline fires while the decode is parked`() {
        val rig = track(BoundRig(parkFirst = true))
        val call = callers.async { rig.engine.transcribe(clipRequest()) }
        rig.awaitSlotTask(call, "transcribe must be handed to the engine's slot")
        rig.expireWhileParked(call, "transcribe")

        rig.parkUntil.complete(Unit)
        val result = awaitUnlessStuck(call, rig.watcher, "transcribe handed a second task to its slot")

        assertEquals(
            "transcribe must return the timeout failure, not the late transcript",
            ErrorMapping.decodeTimedOut(),
            result,
        )
    }

    @Test
    fun `transcribeAsync returns the timeout failure when the deadline fires while the decode is parked`() {
        val rig = track(BoundRig(parkFirst = true))
        val call = rig.engine.transcribeAsync(clipRequest())
        rig.watcher.expectDispatched(1, "the call must be handed to the engine's slot")
        rig.expireWhileParked(call, "transcribeAsync")

        rig.parkUntil.complete(Unit)
        val result = awaitUnlessStuck(call, rig.watcher, "transcribeAsync handed a second task to its slot")

        assertEquals(
            "transcribeAsync must return the timeout failure, not the late transcript",
            ErrorMapping.decodeTimedOut(),
            result,
        )
    }

    @Test
    fun `the deadline is armed once per decode with the clip length in milliseconds`() {
        val rig = track(BoundRig(false, CountingRecognizer(), CountingRecognizer()))

        transcribeOffThread(rig, "transcribe of 16000 samples", samples = 16_000)
        assertEquals("one decode must arm the deadline once, for 1000 ms", listOf(1000L), rig.deadline.audioMs)

        transcribeOffThread(rig, "transcribe of 32000 samples", samples = 32_000)
        assertEquals(
            "a second decode must arm the deadline once more, for 2000 ms",
            listOf(1000L, 2000L),
            rig.deadline.audioMs,
        )
    }

    @Test
    fun `a decode that finishes first returns its transcript, disarms the deadline once and a late fire changes nothing`() {
        val rig = track(BoundRig(false, CountingRecognizer(), CountingRecognizer()))

        val first = transcribeOffThread(rig, "the first transcribe")
        assertTrue("a decode that finishes must return its transcript", first is SttResult.Success)
        assertEquals("a decode that finished must disarm its deadline once", 1, rig.deadline.closeCount)

        rig.deadline.fire()
        assertFalse("a late fire must not mark the engine abandoned", rig.engine.abandonedDecodeRunning)

        val second = transcribeOffThread(rig, "the transcribe after a late fire")
        assertTrue("the engine must still decode after a late fire", second is SttResult.Success)
        assertEquals("the second decode must arm once more", 2, rig.deadline.armCount)
    }

    @Test
    fun `a decode that throws disarms the deadline`() {
        val rig = track(BoundRig(false, FailingRecognizer()))

        val result = transcribeOffThread(rig, "transcribe with a failing decode")

        assertEquals("a throwing decode must return the decode failure", ErrorMapping.decodeFailed(), result)
        assertEquals("a decode that threw must disarm its deadline once", 1, rig.deadline.closeCount)
    }

    @Test
    fun `transcribe made while an abandoned decode still runs is refused as busy and starts no decode`() {
        val rig = track(BoundRig(true, CountingRecognizer()))
        val first = callers.async { rig.engine.transcribe(clipRequest()) }
        rig.awaitSlotTask(first, "the first transcribe must be handed to the engine's slot")
        rig.expireWhileParked(first, "the first transcribe")

        val second = callers.async { rig.engine.transcribe(clipRequest()) }
        rig.awaitSlotTask(second, "the second transcribe must be handed to the engine's slot")
        val result = awaitUnlessStuck(second, rig.watcher, "the second transcribe handed a second task to its slot")

        assertEquals("a call made during an abandoned decode must be refused as busy", ErrorMapping.decodeBusy(), result)
        assertEquals("a busy refusal must not ask the loader for another model", 1, rig.loader.loads)
        rig.workers.expectNoMoreDispatched("a busy refusal must not hand a decode to the workers")
    }

    @Test
    fun `transcribeAsync made while an abandoned decode still runs is refused as busy and starts no decode`() {
        val rig = track(BoundRig(true, CountingRecognizer()))
        val first = rig.engine.transcribeAsync(clipRequest())
        rig.watcher.expectDispatched(1, "the first call must be handed to the engine's slot")
        rig.expireWhileParked(first, "the first transcribeAsync")

        val second = rig.engine.transcribeAsync(clipRequest())
        rig.watcher.expectDispatched(1, "the second call must be handed to the engine's slot")
        val result = awaitUnlessStuck(second, rig.watcher, "the second call handed a second task to its slot")

        assertEquals("a call made during an abandoned decode must be refused as busy", ErrorMapping.decodeBusy(), result)
        assertEquals("a busy refusal must not ask the loader for another model", 1, rig.loader.loads)
        rig.workers.expectNoMoreDispatched("a busy refusal must not hand a decode to the workers")
    }

    @Test
    fun `preload arms no deadline and hands nothing to the workers`() {
        val rig = track(BoundRig(false, CountingRecognizer()))

        val failure = callOrFail("preload") { rig.engine.preload("tiny") }

        assertNull("preload of a loadable model must succeed", failure)
        assertEquals("preload must load the model once", 1, rig.loader.loads)
        assertEquals("preload must not arm a deadline", 0, rig.deadline.armCount)
        rig.workers.expectNoMoreDispatched("preload must not hand anything to the workers")
    }

    @Test
    fun `calls refused before the decode arm no deadline and use no worker`() {
        val closed = track(BoundRig(false, CountingRecognizer()))
        closed.engine.close()
        val closedResult = callOrFail("transcribe on a closed engine") { closed.engine.transcribe(clipRequest()) }
        assertEquals("a closed engine must refuse", ErrorMapping.engineClosed(), closedResult)

        val wrongRate = track(BoundRig(false, CountingRecognizer()))
        val rateResult = callOrFail("transcribe at 8000 Hz") { wrongRate.engine.transcribe(clipRequest(rate = 8000)) }
        assertEquals("a wrong sample rate must be refused", ErrorMapping.audioWrongRate(8000), rateResult)

        val refusedModel = track(BoundRig(false))
        val modelResult = callOrFail("transcribe with a refused model") { refusedModel.engine.transcribe(clipRequest()) }
        assertEquals("a refused model must be refused", ErrorMapping.noModelInstalled("tiny"), modelResult)

        for ((name, rig) in listOf("closed" to closed, "wrong rate" to wrongRate, "refused model" to refusedModel)) {
            assertEquals("a call refused as $name must not arm a deadline", 0, rig.deadline.armCount)
            rig.workers.expectNoMoreDispatched("a call refused as $name must not use a worker")
        }
    }

    /**
     * Parks a first call, fires the deadline, frees the park, and waits until the call returned
     * and the worker finished. Returns the first call's result.
     */
    private fun expireThenFinish(rig: BoundRig): SttResult {
        val call = callers.async { rig.engine.transcribe(clipRequest()) }
        rig.awaitSlotTask(call, "the first transcribe must be handed to the engine's slot")
        rig.expireWhileParked(call, "the first transcribe")

        rig.parkUntil.complete(Unit)
        val result = awaitUnlessStuck(call, rig.watcher, "the first transcribe handed a second task to its slot")
        rig.workers.awaitOneFinished()
        return result
    }

    @Test
    fun `the recognizer of an abandoned decode is released exactly once and only after its decode returned`() {
        val rig = track(BoundRig(true))

        val result = expireThenFinish(rig)

        assertEquals("the call must return the timeout failure", ErrorMapping.decodeTimedOut(), result)
        assertEquals(
            "the recognizer of an abandoned decode must be released exactly once",
            1,
            rig.parked.releaseCount,
        )
        assertFalse(
            "the recognizer of an abandoned decode must not be released while its decode still runs",
            rig.parked.releasedWhileDecoding,
        )
    }

    @Test
    fun `an abandoned decode that throws after its deadline changes nothing and its recognizer is released once`() {
        val rig = track(BoundRig.parkThenThrow(CountingRecognizer()))

        val result = expireThenFinish(rig)

        assertEquals(
            "a late exception from an abandoned decode must not replace the timeout failure",
            ErrorMapping.decodeTimedOut(),
            result,
        )
        assertEquals(
            "the recognizer of an abandoned decode that threw must be released exactly once",
            1,
            rig.parked.releaseCount,
        )
        assertFalse(
            "the recognizer of an abandoned decode that threw must not be released while its decode still runs",
            rig.parked.releasedWhileDecoding,
        )
        assertFalse("a finished abandoned decode must clear the abandoned state", rig.engine.abandonedDecodeRunning)

        val next = try {
            rig.engine.transcribe(clipRequest())
        } catch (e: Throwable) {
            throw AssertionError("transcribe after a late exception: the call threw $e instead of returning a result", e)
        }
        assertTrue("the engine must decode again after a late exception, got $next", next is SttResult.Success)
    }

    @Test
    fun `the engine accepts calls again once the abandoned decode has returned`() {
        val rig = track(BoundRig(true, CountingRecognizer()))

        expireThenFinish(rig)
        assertFalse("a finished abandoned decode must clear the abandoned state", rig.engine.abandonedDecodeRunning)

        val third = try {
            rig.engine.transcribe(clipRequest())
        } catch (e: Throwable) {
            throw AssertionError("transcribe after recovery: the call threw $e instead of returning a result", e)
        }

        assertTrue("the engine must decode again once the abandoned decode returned, got $third", third is SttResult.Success)
        assertEquals("the recovered call must load a fresh recognizer", 2, rig.loader.loads)
        assertFalse("the recovered engine must not report an abandoned decode", rig.engine.abandonedDecodeRunning)
    }

    @Test
    fun `an interrupt of the waiting caller returns the timeout failure and leaves the interrupt flag set`() {
        val rig = track(BoundRig(parkFirst = true))
        val call = callers.async { callOrFail("the interrupted transcribe") { rig.engine.transcribe(clipRequest()) } }
        rig.awaitSlotTask(call, "the interrupted transcribe must be handed to the engine's slot")
        rig.awaitEnter(call, "the decode must reach the recognizer before the interrupt")
        val slotThread = rig.loader.loadThread ?: throw AssertionError("the loader must have recorded the slot thread")

        // A coroutine primitive cannot deliver a thread interrupt to one specific thread, so this
        // test holds the slot thread and interrupts it. The decode is parked and the deadline is
        // never fired, so the interrupt is the only thing that can end the wait.
        slotThread.interrupt()
        val result = awaitUnlessStuck(call, rig.watcher, "the interrupted transcribe handed a second task to its slot")

        assertEquals("an interrupted wait must return the timeout failure", ErrorMapping.decodeTimedOut(), result)
        assertTrue("an interrupted wait must leave the decode abandoned and running", rig.engine.abandonedDecodeRunning)
        assertEquals("an interrupted wait must disarm the deadline once", 1, rig.deadline.closeCount)
        assertTrue(
            "the interrupt flag must still be set when the slot thread leaves the wait",
            rig.deadline.interruptedAtClose,
        )
        assertFalse(
            "the recognizer of an interrupted decode must not be released while its decode still runs",
            rig.parked.releasedWhileDecoding,
        )
    }
}
