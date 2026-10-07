package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * What a closed engine and a wrong sample rate do, and what the engine records
 * about the models it was asked for.
 *
 * Every refusal here must happen before the engine touches its dispatcher or
 * its loader, so each test watches both. A wrong result must fail an
 * assertion that names the claim, never end in a wait.
 */
class OnDeviceSttEngineBehaviourTest {

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

    /** A loader that counts how often it was asked and always gives the same answer. */
    private class CountingLoader(private val result: ModelLoader.LoadResult) : ModelLoaderPort {
        var loads = 0

        override fun load(modelId: String): ModelLoader.LoadResult {
            loads++
            return result
        }
    }

    private fun ready(recognizer: SherpaRecognizer): ModelLoader.LoadResult =
        ModelLoader.LoadResult.Ready(SherpaModel("tiny", File("unused-model-dir"), "unused-digest"), recognizer)

    private fun refused(refusal: ModelLoader.Refusal): ModelLoader.LoadResult =
        ModelLoader.LoadResult.Refused(refusal, "unused detail")

    private fun request(model: String = "tiny", rate: Int = 16_000): SttRequest = SttRequest(
        pcm = floatArrayOf(0.1f, 0.2f, 0.3f),
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = model,
        language = "en",
        sampleRateHz = rate,
    )

    /** Runs [block]; an exception that escapes the engine becomes an assertion that names [claim]. */
    private inline fun <T> callOrFail(claim: String, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            throw AssertionError("$claim: the call threw $e instead of returning a result", e)
        }

    @Test
    fun `a closed engine refuses transcribe without touching the dispatcher or the loader`() {
        val watcher = newSlotWatcher()
        val loader = CountingLoader(ready(CountingRecognizer()))
        val engine = track(OnDeviceSttEngine(loader, watcher))
        engine.close()

        val result = callOrFail("transcribe on a closed engine") { engine.transcribe(request()) }

        assertEquals("a closed engine must return the engine-closed failure", ErrorMapping.engineClosed(), result)
        watcher.expectNoMoreDispatched("a closed engine must refuse transcribe before it uses its dispatcher")
        assertEquals("a closed engine must not ask the loader for a model", 0, loader.loads)
    }

    @Test
    fun `a closed engine refuses transcribeAsync without asking the loader`() {
        val watcher = newSlotWatcher()
        val loader = CountingLoader(ready(CountingRecognizer()))
        val engine = track(OnDeviceSttEngine(loader, watcher))
        engine.close()

        val call = engine.transcribeAsync(request())
        watcher.expectDispatched(1, "the call must be handed to the engine's dispatcher")
        val result = callOrFail("transcribeAsync on a closed engine") {
            awaitUnlessStuck(call, watcher, "transcribeAsync on a closed engine handed a second task to its slot")
        }

        assertEquals("a closed engine must return the engine-closed failure", ErrorMapping.engineClosed(), result)
        assertEquals("a closed engine must not ask the loader for a model", 0, loader.loads)
    }

    @Test
    fun `a closed engine refuses preload without touching the dispatcher or the loader`() {
        val watcher = newSlotWatcher()
        val loader = CountingLoader(ready(CountingRecognizer()))
        val engine = track(OnDeviceSttEngine(loader, watcher))
        engine.close()

        val result = callOrFail("preload on a closed engine") { engine.preload("tiny") }

        assertEquals("a closed engine must return the engine-closed failure", ErrorMapping.engineClosed(), result)
        watcher.expectNoMoreDispatched("a closed engine must refuse preload before it uses its dispatcher")
        assertEquals("a closed engine must not ask the loader for a model", 0, loader.loads)
    }

    @Test
    fun `a call queued behind a running decode returns the closed failure when the engine closes while it waits`() {
        val watcher = newSlotWatcher()
        val holdUntil = CompletableDeferred<Unit>()
        val log = Channel<String>(Channel.UNLIMITED)
        val loader = CountingLoader(ready(HoldingRecognizer(holdUntil, log)))
        val engine = track(OnDeviceSttEngine(loader, watcher))

        runBlocking {
            try {
                val running = engine.transcribeAsync(request())
                watcher.expectDispatched(1, "the first call must be handed to the engine's dispatcher")
                // The decode must hold the slot before the second call is made.
                val entered = select<String?> {
                    log.onReceive { it }
                    running.onAwait { null }
                }
                assertEquals("the first decode must reach the recognizer", "enter", entered)

                val queued = async(Dispatchers.IO) { engine.transcribe(request()) }
                // The second call is on the slot behind the running decode once its task is reported.
                val reported = select<Boolean> {
                    watcher.dispatched.onReceive { true }
                    queued.onAwait { false }
                }
                assertTrue("the second call must wait for the slot behind the running decode", reported)

                engine.close()
                holdUntil.complete(Unit)

                val first = callOrFail("the running decode") { running.await() }
                val second = callOrFail("the queued call") { queued.await() }
                assertTrue("the decode that was already running must still finish", first is SttResult.Success)
                assertEquals(
                    "a call queued before close must return the engine-closed failure when its turn comes",
                    ErrorMapping.engineClosed(),
                    second,
                )
                assertEquals("a call queued before close must not ask the loader for a model", 1, loader.loads)
            } finally {
                holdUntil.complete(Unit)
            }
        }
    }

    @Test
    fun `transcribe at the wrong sample rate returns the wrong-rate failure for the rate it was given`() {
        val watcher = newSlotWatcher()
        val loader = CountingLoader(ready(CountingRecognizer()))
        val engine = track(OnDeviceSttEngine(loader, watcher))

        val result = callOrFail("transcribe at 8000 Hz") { engine.transcribe(request(rate = 8000)) }

        assertEquals("the failure must be the wrong-rate failure for 8000 Hz", ErrorMapping.audioWrongRate(8000), result)
        watcher.expectNoMoreDispatched("a wrong sample rate must be refused before the engine uses its dispatcher")
        assertEquals("a wrong sample rate must be refused before the loader is asked", 0, loader.loads)
    }

    @Test
    fun `transcribeAsync at the wrong sample rate returns the wrong-rate failure without asking the loader`() {
        val watcher = newSlotWatcher()
        val loader = CountingLoader(ready(CountingRecognizer()))
        val engine = track(OnDeviceSttEngine(loader, watcher))

        val call = engine.transcribeAsync(request(rate = 8000))
        watcher.expectDispatched(1, "the call must be handed to the engine's dispatcher")
        val result = callOrFail("transcribeAsync at 8000 Hz") {
            awaitUnlessStuck(call, watcher, "transcribeAsync at 8000 Hz handed a second task to its slot")
        }

        assertEquals("the failure must be the wrong-rate failure for 8000 Hz", ErrorMapping.audioWrongRate(8000), result)
        assertEquals("a wrong sample rate must be refused before the loader is asked", 0, loader.loads)
    }

    @Test
    fun `diagnostics after one successful transcribe shows the model, one verification and one decode`() {
        val engine = track(OnDeviceSttEngine(CountingLoader(ready(CountingRecognizer())), newSlotWatcher()))

        val result = callOrFail("transcribe") { engine.transcribe(request(model = "model-a")) }
        val diagnostics = engine.diagnostics()

        assertTrue("transcribe did not return a transcript", result is SttResult.Success)
        assertEquals("lastModelId must be the requested model", "model-a", diagnostics.lastModelId)
        assertEquals("loadedModelId must be the model that loaded", "model-a", diagnostics.loadedModelId)
        assertEquals("one load must count one verification", 1, diagnostics.verificationCount)
        assertEquals("one decode must count one decode", 1, diagnostics.decodeCount)
    }

    @Test
    fun `diagnostics after a refused transcribe records the requested model and loads nothing`() {
        val loader = CountingLoader(refused(ModelLoader.Refusal.NOT_INSTALLED))
        val engine = track(OnDeviceSttEngine(loader, newSlotWatcher()))

        val result = callOrFail("transcribe") { engine.transcribe(request(model = "model-b")) }
        val diagnostics = engine.diagnostics()

        assertTrue("a refused model must give a failure", result is SttResult.Failure)
        assertEquals("lastModelId must be the requested model even when it is refused", "model-b", diagnostics.lastModelId)
        assertNull("a refused model must not count as loaded", diagnostics.loadedModelId)
        assertEquals("a refused model must not count a verification", 0, diagnostics.verificationCount)
        assertEquals("a refused model must not count a decode", 0, diagnostics.decodeCount)
    }

    @Test
    fun `diagnostics keeps the last requested model and the last loaded model apart`() {
        val loader = ModelLoaderPort { id ->
            if (id == "model-a") ready(CountingRecognizer()) else refused(ModelLoader.Refusal.NOT_INSTALLED)
        }
        val engine = track(OnDeviceSttEngine(loader, newSlotWatcher()))

        val first = callOrFail("preload of model-a") { engine.preload("model-a") }
        val second = callOrFail("preload of model-b") { engine.preload("model-b") }
        val diagnostics = engine.diagnostics()

        assertNull("the first preload must succeed", first)
        assertTrue("the second preload must be refused", second is SttResult.Failure)
        assertEquals("lastModelId must be the model asked for last", "model-b", diagnostics.lastModelId)
        assertEquals("loadedModelId must be the model that loaded last", "model-a", diagnostics.loadedModelId)
        assertEquals("only the first preload verified a model", 1, diagnostics.verificationCount)
        assertEquals("a preload never decodes", 0, diagnostics.decodeCount)
    }
}
