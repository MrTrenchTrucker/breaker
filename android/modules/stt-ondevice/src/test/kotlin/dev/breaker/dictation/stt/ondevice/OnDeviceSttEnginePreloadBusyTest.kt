package dev.breaker.dictation.stt.ondevice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Preload while an abandoned decode still runs: it is refused as busy, exactly
 * like transcribe, and it loads nothing, so no second recognizer is created
 * next to the stuck one. Once the abandoned decode has returned, preload works
 * again.
 *
 * No test here reads a clock. The deadline is fired by hand and every wait is
 * on a signal.
 */
class OnDeviceSttEnginePreloadBusyTest {

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

    @Test
    fun `preload made while an abandoned decode still runs is refused as busy and loads nothing until that decode returned`() {
        val rig = track(BoundRig(true, CountingRecognizer()))
        val first = callers.async { rig.engine.transcribe(clipRequest()) }
        rig.awaitSlotTask(first, "the first transcribe must be handed to the engine's slot")
        rig.expireWhileParked(first, "the first transcribe")

        // Arm one: the decode is abandoned and its worker is still inside the native call.
        val during = callers.async { callOrFail("preload during an abandoned decode") { rig.engine.preload("tiny") } }
        rig.awaitSlotTask(during, "the preload must be handed to the engine's slot")
        val busy = awaitUnlessStuck(during, rig.watcher, "the preload handed a second task to its slot")

        assertEquals("a preload made during an abandoned decode must be refused as busy", ErrorMapping.decodeBusy(), busy)
        assertEquals("a busy preload must not ask the loader for another model", 1, rig.loader.loads)
        rig.workers.expectNoMoreDispatched("a busy preload must not hand anything to the workers")

        // Arm two, the control: the busy answer ends with the abandoned decode, it is not permanent.
        rig.parkUntil.complete(Unit)
        val firstResult = awaitUnlessStuck(first, rig.watcher, "the first transcribe handed a second task to its slot")
        rig.workers.awaitOneFinished()
        assertEquals("the abandoned transcribe must have returned the timeout failure", ErrorMapping.decodeTimedOut(), firstResult)
        assertFalse("a finished abandoned decode must clear the abandoned state", rig.engine.abandonedDecodeRunning)

        val after = callOrFail("preload after the abandoned decode returned") { rig.engine.preload("tiny") }

        assertNull("preload must work again once the abandoned decode returned, got $after", after)
        assertEquals("the preload after recovery must load the model", 2, rig.loader.loads)
    }
}
