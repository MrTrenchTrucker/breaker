package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The reentrancy and closed-engine guards of preload, diagnostics and the async
 * entry point, and the scope the async calls run in. No test waits on a clock: a
 * call from inside a decode that is not refused at once would wait for its own
 * slot, so each such wait is raced against the report that the engine handed a
 * second task to its dispatcher.
 */
class OnDeviceSttEngineGuardTest {

    // A net for the failure arm only: no test relies on it; the expected red is an assertion that names the claim.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val engines = ArrayList<OnDeviceSttEngine>()

    // Calls that may park their thread are made on this scope, never on the test thread. It is
    // not a child of the test, so a parked call can never keep the test from ending.
    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun track(engine: OnDeviceSttEngine): OnDeviceSttEngine = engine.also { engines.add(it) }

    @After
    fun closeEngines() {
        engines.forEach { it.close() }
        callers.cancel()
    }

    /** A recognizer that runs [inside] with the number of this decode while it holds the slot. */
    private class InsideDecode(private val inside: (Int) -> Unit) : SherpaRecognizer {
        var decodes = 0

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            decodes++
            inside(decodes)
            return SherpaTranscript("hello", emptyList(), "en")
        }

        override fun release() {}
    }

    /** Counts how often it was asked and gives the answer of [inner]. */
    private class CountingLoader(private val inner: ModelLoaderPort) : ModelLoaderPort {
        var loads = 0

        override fun load(modelId: String): ModelLoader.LoadResult {
            loads++
            return inner.load(modelId)
        }
    }

    /** Runs every task at once on the thread that hands it over. */
    private class InlineDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    /** An error no engine code catches by name. */
    private class DecodeBoom : Error()

    private fun requestFor(model: String): SttRequest = SttRequest(
        pcm = floatArrayOf(0.1f, 0.2f, 0.3f),
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = model,
        language = "en",
    )

    /** Runs [call]; an exception that escapes the engine becomes an assertion that names [claim]. */
    private inline fun <T> callOrFail(claim: String, call: () -> T): T =
        try {
            call()
        } catch (e: Throwable) {
            throw AssertionError("$claim: the call threw $e instead of returning a result", e)
        }

    /** Makes [call] from inside a decode of a watched engine and returns what it gave back. */
    private fun <T> callInsideDecode(what: String, call: (OnDeviceSttEngine) -> T): T {
        val inner = CompletableDeferred<T>()
        var engine: OnDeviceSttEngine? = null
        val watcher = newSlotWatcher()
        val recognizer = InsideDecode { inner.complete(call(engine!!)) }
        engine = track(OnDeviceSttEngine(readyLoader(recognizer), watcher))

        val outer = engine!!.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(1, "the outer call must be handed to the engine's own dispatcher")
        val result = awaitUnlessStuck(
            outer,
            watcher,
            "$what called from inside a decode went to the dispatcher instead of returning at once, " +
                "so it would wait forever on the slot its own caller holds",
        )
        assertTrue("the outer decode must still return a transcript", result is SttResult.Success)
        return runBlocking { inner.await() }
    }

    @Test
    fun `preload called from inside a decode is refused at once without using the dispatcher`() {
        val result = callInsideDecode("preload") { it.preload("tiny") }
        assertEquals("preload from inside a decode must give the reentrant failure", ErrorMapping.reentrantDecode(), result)
    }

    @Test
    fun `diagnostics called from inside a decode returns the snapshot at once without using the dispatcher`() {
        val snapshot = callInsideDecode("diagnostics") { it.diagnostics() }
        assertEquals(
            "diagnostics called from inside a decode must show the state at that moment",
            OnDeviceSttEngine.Diagnostics("tiny", "tiny", 1, 1),
            snapshot,
        )
    }

    @Test
    fun `diagnostics on an open engine goes through the dispatcher exactly once`() {
        val watcher = newSlotWatcher()
        val engine = track(OnDeviceSttEngine(readyLoader(CountingRecognizer()), watcher))
        val snapshot = callOrFail("diagnostics on an open engine") { engine.diagnostics() }

        assertEquals(OnDeviceSttEngine.Diagnostics(null, null, 0, 0), snapshot)
        watcher.expectDispatched(1, "diagnostics on an open engine must be handed to the engine's own dispatcher")
        watcher.expectNoMoreDispatched("diagnostics must put exactly one task on the dispatcher")
    }

    @Test
    fun `diagnostics on a closed engine returns the snapshot without using the dispatcher`() {
        val watcher = newSlotWatcher()
        val engine = track(OnDeviceSttEngine(readyLoader(CountingRecognizer()), watcher))
        callOrFail("transcribe before close") { engine.transcribe(validSlotRequest()) }
        watcher.expectDispatched(1, "the transcribe call must be handed to the engine's own dispatcher")
        engine.close()
        val snapshot = callOrFail("diagnostics on a closed engine") { engine.diagnostics() }

        assertEquals(
            "a closed engine must still return the state it had",
            OnDeviceSttEngine.Diagnostics("tiny", "tiny", 1, 1),
            snapshot,
        )
        watcher.expectNoMoreDispatched("diagnostics on a closed engine must not use the dispatcher")
    }

    @Test
    fun `preload goes through the engine's own dispatcher exactly once`() {
        val watcher = newSlotWatcher()
        val engine = track(OnDeviceSttEngine(readyLoader(CountingRecognizer()), watcher))
        val result = callOrFail("preload") { engine.preload("tiny") }

        assertNull("preload of a model that loads must return no failure", result)
        watcher.expectDispatched(1, "preload must be handed to the engine's own dispatcher, not another one")
        watcher.expectNoMoreDispatched("preload must put exactly one task on the dispatcher")
    }

    @Test
    fun `preload on a closed engine returns the closed failure without using the dispatcher`() {
        val watcher = newSlotWatcher()
        val engine = track(OnDeviceSttEngine(readyLoader(CountingRecognizer()), watcher))
        engine.close()
        val result = callOrFail("preload on a closed engine") { engine.preload("tiny") }

        assertEquals("a closed engine must return the engine-closed failure", ErrorMapping.engineClosed(), result)
        watcher.expectNoMoreDispatched("preload on a closed engine must not use the dispatcher")
    }

    @Test
    fun `a preload queued behind a running decode returns the closed failure when the engine closes while it waits`() {
        val watcher = newSlotWatcher()
        val holdUntil = CompletableDeferred<Unit>()
        val log = Channel<String>(Channel.UNLIMITED)
        val loader = CountingLoader(readyLoader(HoldingRecognizer(holdUntil, log)))
        val engine = track(OnDeviceSttEngine(loader, watcher))

        runBlocking<Unit> {
            try {
                val started = startOffThread(callers) { engine.transcribeAsync(validSlotRequest()) }
                expectSlotBeforeDecode(
                    watcher,
                    log,
                    "the running call must be handed to the engine's own dispatcher before its decode starts",
                )
                val running = started.await()
                // The decode must hold the slot before the preload is made.
                val entered = select<String?> {
                    log.onReceive { it }
                    running.onAwait { null }
                    watcher.dispatched.onReceive {
                        throw AssertionError(
                            "the running call handed a second task to the slot it already holds, " +
                                "so it would wait forever",
                        )
                    }
                }
                assertEquals("the running decode must reach the recognizer", "enter", entered)

                val queued = async(Dispatchers.IO) { engine.preload("tiny") }
                // The preload is on the slot behind the running decode once its task is reported.
                val reported = select<Boolean> {
                    watcher.dispatched.onReceive { true }
                    queued.onAwait { false }
                }
                assertTrue("the preload must wait for the slot behind the running decode", reported)

                engine.close()
                holdUntil.complete(Unit)

                val first = callOrFail("the running decode") { running.await() }
                val second = callOrFail("the queued preload") { queued.await() }
                assertTrue("the decode that was already running must still finish", first is SttResult.Success)
                assertEquals(
                    "a preload queued before close must return the engine-closed failure when its turn comes",
                    ErrorMapping.engineClosed(),
                    second,
                )
                assertEquals("a preload queued before close must not ask the loader for a model", 1, loader.loads)
            } finally {
                holdUntil.complete(Unit)
            }
        }
    }

    @Test
    fun `diagnostics keeps the requested model and the loaded model apart after transcribe`() {
        val ready = readyLoader(CountingRecognizer())
        val refused = ModelLoader.LoadResult.Refused(ModelLoader.Refusal.NOT_INSTALLED, "unused detail")
        val loader = ModelLoaderPort { id -> if (id == "model-a") ready.load(id) else refused }
        val engine = track(OnDeviceSttEngine(loader, newSlotWatcher()))

        callOrFail("transcribe of model-a") { engine.transcribe(requestFor("model-a")) }
        callOrFail("transcribe of model-b") { engine.transcribe(requestFor("model-b")) }
        val snapshot = engine.diagnostics()

        // Current behaviour: a refused load records the requested id but does not clear the loaded id.
        assertEquals("lastModelId must be the model asked for last", "model-b", snapshot.lastModelId)
        assertEquals("loadedModelId must be the model that loaded last", "model-a", snapshot.loadedModelId)
        assertEquals("only the first transcribe verified a model", 1, snapshot.verificationCount)
        assertEquals("only the first transcribe decoded", 1, snapshot.decodeCount)
    }

    @Test
    fun `transcribeAsync called from inside a decode on the same thread is refused as reentrant`() {
        // The inline dispatcher runs the nested async body on the thread that is inside the decode,
        // while the reentrancy marker of that thread is set.
        var engine: OnDeviceSttEngine? = null
        var nested: Deferred<SttResult>? = null
        val recognizer = InsideDecode { n -> if (n == 1) nested = engine!!.transcribeAsync(validSlotRequest()) }
        engine = track(OnDeviceSttEngine(readyLoader(recognizer), InlineDispatcher()))

        val outer = engine!!.transcribeAsync(validSlotRequest())
        val outerResult = callOrFail("the outer async call") { runBlocking { outer.await() } }

        assertTrue("the outer decode must return a transcript", outerResult is SttResult.Success)
        val inner = nested
        assertNotNull("the decode must have started the nested async call", inner)
        assertEquals(
            "an async call made from inside a decode must return the reentrant failure",
            ErrorMapping.reentrantDecode(),
            callOrFail("the nested async call") { runBlocking { inner!!.await() } },
        )
        assertEquals("the refused nested call must never reach the recognizer", 1, recognizer.decodes)
    }

    @Test
    fun `an error thrown by one async decode does not stop the next async call`() {
        val watcher = newSlotWatcher()
        val recognizer = InsideDecode { n -> if (n == 1) throw DecodeBoom() }
        val engine = track(OnDeviceSttEngine(readyLoader(recognizer), watcher))
        val stuck = "an async call handed a second task to its own slot while another call was running"
        fun joinOrStuck(call: Deferred<SttResult>) = runBlocking {
            select<Unit> {
                call.onJoin { }
                watcher.dispatched.onReceive { throw AssertionError(stuck) }
            }
        }

        val first = engine.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(1, "the first call must be handed to the engine's own dispatcher")
        joinOrStuck(first)
        assertTrue("an error thrown by a decode must end that call as failed", first.isCancelled)

        val second = engine.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(
            1,
            "the next call must be handed to the engine's own dispatcher: " +
                "the failure of the first call cancelled the scope the engine runs async calls in",
        )
        joinOrStuck(second)
        assertFalse(
            "the next call was cancelled: the failure of the first call cancelled the engine's scope",
            second.isCancelled,
        )
        assertTrue("the next call must return a transcript", runBlocking { second.await() } is SttResult.Success)
    }
}
