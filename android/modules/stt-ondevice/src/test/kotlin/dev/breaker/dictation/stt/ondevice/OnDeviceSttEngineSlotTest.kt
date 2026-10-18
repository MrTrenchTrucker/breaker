package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttResult
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * How the async entry point uses the engine's single decode slot.
 *
 * None of these tests waits on a clock. A broken engine must fail an
 * assertion that names the test, so each wait is either on a result the engine
 * returns or on the report that the engine handed a second task to its own slot.
 */
class OnDeviceSttEngineSlotTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the test.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val engines = ArrayList<OnDeviceSttEngine>()

    private fun track(engine: OnDeviceSttEngine): OnDeviceSttEngine {
        engines.add(engine)
        return engine
    }

    @After
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    @Test
    fun `transcribeAsync clears the reentrancy marker so the next async decode on the same thread runs`() =
        runBlocking<Unit> {
            val recognizer = CountingRecognizer()
            // The event loop of this runBlocking is one thread that runs queued tasks in order,
            // so both decodes run on the test thread, one after the other.
            val loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val engine = track(OnDeviceSttEngine(readyLoader(recognizer), loop))

            val first = engine.transcribeAsync(validSlotRequest())
            val second = engine.transcribeAsync(validSlotRequest())
            val firstResult = first.await()
            val secondResult = second.await()

            assertTrue("the first async decode did not return a transcript", firstResult is SttResult.Success)
            assertTrue(
                "the second async decode on the same thread was refused as reentrant: " +
                    "the async path left the reentrancy marker set after the first decode",
                secondResult is SttResult.Success,
            )
            assertEquals("both async calls must reach the recognizer", 2, recognizer.count)
        }

    @Test
    fun `transcribeAsync puts one task on the dispatcher and never waits on its own slot`() {
        val watcher = newSlotWatcher()
        val engine = track(OnDeviceSttEngine(readyLoader(CountingRecognizer()), watcher))

        val call = engine.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(1, "the call must be handed to the engine's dispatcher")
        val result = awaitUnlessStuck(
            call,
            watcher,
            "transcribeAsync handed a second task to its own single slot: " +
                "it called the blocking transcribe, which waits forever for the slot its caller holds",
        )

        assertTrue("transcribeAsync did not return a transcript", result is SttResult.Success)
    }

    @Test
    fun `transcribeAsync hands exactly one task to the engine's dispatcher per call`() {
        val watcher = newSlotWatcher()
        val engine = track(OnDeviceSttEngine(readyLoader(CountingRecognizer()), watcher))
        val stuck = "transcribeAsync handed a second task to its own single slot while another call was running"

        val first = engine.transcribeAsync(validSlotRequest())
        val second = engine.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(2, "each call must be handed to the engine's own dispatcher")
        awaitUnlessStuck(first, watcher, stuck)
        awaitUnlessStuck(second, watcher, stuck)

        watcher.expectNoMoreDispatched("two calls must put exactly two tasks on the engine's dispatcher")
    }
}
