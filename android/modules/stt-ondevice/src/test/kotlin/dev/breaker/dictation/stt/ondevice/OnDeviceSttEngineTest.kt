package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.model.SttSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

class OnDeviceSttEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // A net for the failure arm only: no test relies on it; the expected red is an assertion that names the test.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    // Blocking calls must not run on the test thread. This scope is not a child of the
    // test, so a parked call can never keep the test from ending.
    private val callers = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun stopCallers() {
        callers.cancel()
    }

    private fun validRequest(
        model: String = "tiny",
        sampleRateHz: Int = AudioFormat.SAMPLE_RATE_HZ,
    ): SttRequest = SttRequest(
        pcm = floatArrayOf(0.1f, 0.2f, 0.3f),
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = model,
        language = "en",
        sampleRateHz = sampleRateHz,
    )

    private class FakeLoader(
        private val result: ModelLoader.LoadResult,
    ) : ModelLoaderPort {
        override fun load(modelId: String): ModelLoader.LoadResult = result
    }

    private class StubRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            return SherpaTranscript("hello", emptyList<SttSegment>(), "en")
        }
        override fun release() {}
    }

    private class ReleaseCountingRecognizer(
        private val throwOnDecode: Boolean = false,
    ) : SherpaRecognizer {
        var releaseCount = 0
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            if (throwOnDecode) throw SherpaTranscriptionException("boom")
            return SherpaTranscript("hello", emptyList<SttSegment>(), "en")
        }
        override fun release() { releaseCount++ }
    }

    private class ThrowingRecognizer : SherpaRecognizer {
        var releaseCount = 0
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
            throw IllegalStateException("decode blew up")
        }
        override fun release() {
            releaseCount++
        }
    }

    @Test
    fun `transcribe returns OTHER when audio sample rate is wrong`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Refused(
            ModelLoader.Refusal.UNKNOWN_MODEL, "no model"
        )))
        val result = engine.transcribe(validRequest(sampleRateHz = 8000))
        assertTrue(result is SttResult.Failure)
        assertEquals(SttError.OTHER, (result as SttResult.Failure).error)
    }

    @Test
    fun `transcribe returns OTHER when engine is closed`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), StubRecognizer()
        )))
        engine.close()
        val result = engine.transcribe(validRequest())
        assertTrue(result is SttResult.Failure)
        assertEquals(SttError.OTHER, (result as SttResult.Failure).error)
    }

    @Test
    fun `transcribeAsync serialises decode calls on single-threaded dispatcher`() {
        val release = CompletableDeferred<Unit>()
        val log = Channel<String>(Channel.UNLIMITED)
        val recognizer = HoldingRecognizer(release, log)
        val watcher = newSlotWatcher()
        val engine = OnDeviceSttEngine(readyLoader(recognizer), watcher)

        val first = engine.transcribeAsync(validRequest())
        val second = engine.transcribeAsync(validRequest())
        try {
            watcher.expectDispatched(2, "both calls must be handed to the engine's own dispatcher")
        } finally {
            release.complete(Unit)
        }

        val stuck = "transcribeAsync serialises decode calls on single-threaded dispatcher: " +
            "the engine handed a second task to its own slot"
        val firstResult = awaitUnlessStuck(first, watcher, stuck)
        val secondResult = awaitUnlessStuck(second, watcher, stuck)

        assertTrue("the first async call did not return a transcript", firstResult is SttResult.Success)
        assertTrue(
            "second async decode refused: the async path left the reentrancy marker set",
            secondResult is SttResult.Success,
        )
        assertEquals(
            "the two decodes must run one after the other",
            listOf("enter", "exit", "enter", "exit"),
            drain(log),
        )
    }

    @Test
    fun `transcribeAsync calls decode exactly once`() {
        val recognizer = CountingRecognizer()
        val watcher = newSlotWatcher()
        val engine = OnDeviceSttEngine(readyLoader(recognizer), watcher)
        val call = engine.transcribeAsync(validRequest())
        watcher.expectDispatched(1, "the call must be handed to the engine's own dispatcher")
        awaitUnlessStuck(call, watcher, "transcribeAsync calls decode exactly once: the engine handed a second task to its own slot")
        assertEquals(1, recognizer.count)
    }

    @Test
    fun `preload returns null when loader returns Ready`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), StubRecognizer()
        )))
        val result = engine.preload("tiny")
        assertNull(result)
    }

    @Test
    fun `preload returns Failure when loader returns Refused`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Refused(
            ModelLoader.Refusal.UNKNOWN_MODEL, "no model"
        )))
        val result = engine.preload("tiny")
        assertNotNull(result)
        assertTrue(result is SttResult.Failure)
    }

    @Test
    fun `diagnostics returns initial state with null model ids and zero counts`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Refused(
            ModelLoader.Refusal.UNKNOWN_MODEL, "no model"
        )))
        val diag = engine.diagnostics()
        assertNull(diag.lastModelId)
        assertNull(diag.loadedModelId)
        assertEquals(0, diag.verificationCount)
        assertEquals(0, diag.decodeCount)
    }

    @Test
    fun `diagnostics reflects model ids after successful preload`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), StubRecognizer()
        )))
        engine.preload("tiny")
        val diag = engine.diagnostics()
        assertEquals("tiny", diag.lastModelId)
        assertEquals("tiny", diag.loadedModelId)
    }

    @Test
    fun `close marks engine closed and transcribe returns OTHER`() {
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), StubRecognizer()
        )))
        engine.close()
        val result = engine.transcribe(validRequest())
        assertTrue(result is SttResult.Failure)
        assertEquals(SttError.OTHER, (result as SttResult.Failure).error)
    }

    @Test
    fun `transcribe refuses reentrant call from inside decode`() {
        val inner = CompletableDeferred<SttResult>()
        var engine: OnDeviceSttEngine? = null
        val recognizer = object : SherpaRecognizer {
            override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
                val e = engine ?: error("engine not set")
                // No assertion in here: an AssertionError thrown inside decode escapes the engine as an Error.
                inner.complete(e.transcribe(validRequest()))
                return SherpaTranscript("hello", emptyList(), "en")
            }
            override fun release() {}
        }
        val watcher = newSlotWatcher()
        engine = OnDeviceSttEngine(readyLoader(recognizer), watcher)

        // transcribe blocks, so it runs off the test thread. Its own hand-off to the
        // dispatcher always happens; a second one means the inner call was not refused.
        val outer = callers.async { engine!!.transcribe(validRequest()) }
        runBlocking {
            select<Unit> {
                watcher.dispatched.onReceive { }
                outer.onAwait {
                    throw AssertionError(
                        "transcribe refuses reentrant call from inside decode: the outer call returned " +
                            "without handing a task to the dispatcher",
                    )
                }
            }
        }
        val result = runBlocking {
            select<SttResult> {
                outer.onAwait { it }
                watcher.dispatched.onReceive {
                    throw AssertionError(
                        "transcribe refuses reentrant call from inside decode: the call made from " +
                            "inside decode went to the dispatcher instead of being refused at once, " +
                            "so it would wait forever on the slot its own caller holds",
                    )
                }
            }
        }

        assertTrue(result is SttResult.Success)
        val innerResult = runBlocking { inner.await() }
        assertTrue(innerResult is SttResult.Failure)
        assertEquals(SttError.OTHER, (innerResult as SttResult.Failure).error)
        val innerDetail = (innerResult as SttResult.Failure).detail
        assertNotNull(innerDetail)
        assertTrue(innerDetail!!.contains("already transcribing"))
    }

    @Test
    fun `transcribe resets reentrancy flag after completion`() {
        // Unconfined runs each blocking call on the calling thread, so both calls
        // below use the same thread by construction.
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), StubRecognizer()
        )), Dispatchers.Unconfined)
        val first = engine.transcribe(validRequest())
        assertTrue(first is SttResult.Success)
        val second = engine.transcribe(validRequest())
        assertTrue(
            "the second transcribe on the same thread was refused: " +
                "the blocking path left the reentrancy marker set",
            second is SttResult.Success,
        )
    }

    @Test
    fun `transcribeAsync refuses reentrant call from inside decode`() {
        // The outer call is async; the inner call is transcribe, made from inside a decode
        // that transcribeAsync started.
        val inner = CompletableDeferred<SttResult>()
        var engine: OnDeviceSttEngine? = null
        val recognizer = object : SherpaRecognizer {
            override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript {
                val e = engine ?: error("engine not set")
                // No assertion in here: an AssertionError thrown inside decode escapes the engine as an Error.
                inner.complete(e.transcribe(validRequest()))
                return SherpaTranscript("hello", emptyList(), "en")
            }
            override fun release() {}
        }
        val watcher = newSlotWatcher()
        engine = OnDeviceSttEngine(readyLoader(recognizer), watcher)

        val outer = engine!!.transcribeAsync(validRequest())
        watcher.expectDispatched(1, "the call must be handed to the engine's own dispatcher")
        val result = awaitUnlessStuck(
            outer,
            watcher,
            "transcribeAsync refuses reentrant call from inside decode: the call made from " +
                "inside decode went to the dispatcher instead of being refused at once, " +
                "so it would wait forever on the slot its own caller holds",
        )

        assertTrue(result is SttResult.Success)
        val innerResult = runBlocking { inner.await() }
        assertTrue(innerResult is SttResult.Failure)
        assertEquals(SttError.OTHER, (innerResult as SttResult.Failure).error)
        val innerDetail = (innerResult as SttResult.Failure).detail
        assertNotNull(innerDetail)
        assertTrue(innerDetail!!.contains("already transcribing"))
    }

    @Test
    fun `transcribe returns OTHER when decode throws IllegalStateException`() {
        val recognizer = ThrowingRecognizer()
        val engine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), recognizer
        )))
        val result = engine.transcribe(validRequest())
        assertTrue(result is SttResult.Failure)
        assertEquals(SttError.OTHER, (result as SttResult.Failure).error)
        val detail = (result as SttResult.Failure).detail
        assertNotNull(detail)
        assertEquals("The on-device engine could not transcribe the audio.", detail)
        assertEquals(1, recognizer.releaseCount)
    }

    @Test
    fun `transcribe calls release exactly once after successful decode and after decode throws`() {
        // Phase 1: successful decode
        val successRecognizer = ReleaseCountingRecognizer(throwOnDecode = false)
        val successEngine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), successRecognizer
        )))
        val successResult = successEngine.transcribe(validRequest())
        assertTrue(successResult is SttResult.Success)
        assertEquals(1, successRecognizer.releaseCount)

        // Phase 2: decode throws
        val throwRecognizer = ReleaseCountingRecognizer(throwOnDecode = true)
        val throwEngine = OnDeviceSttEngine(FakeLoader(ModelLoader.LoadResult.Ready(
            SherpaModel("tiny", tmp.root, "digest"), throwRecognizer
        )))
        val throwResult = throwEngine.transcribe(validRequest())
        assertTrue(throwResult is SttResult.Failure)
        assertEquals(SttError.OTHER, (throwResult as SttResult.Failure).error)
        assertEquals(1, throwRecognizer.releaseCount)
    }

    @Test
    fun `transcribeAsync serialises decode calls on default dispatcher`() {
        // This test can catch two decodes overlapping, but it cannot prove they never could:
        // the default dispatcher has no hook that shows the second call had a chance to start.
        // The width of the default is pinned by the singleSlot test and by the contract test.
        val release = CompletableDeferred<Unit>()
        val log = Channel<String>(Channel.UNLIMITED)
        val recognizer = HoldingRecognizer(release, log)
        val engine = OnDeviceSttEngine(readyLoader(recognizer))

        var entered = ""
        val firstCall = callers.async { engine.transcribe(validRequest()) }
        val secondCall = try {
            // Waits on the first decode's own "enter"; ends in an assertion if the call returns first.
            entered = runBlocking {
                select<String> {
                    log.onReceive { it }
                    firstCall.onAwait {
                        throw AssertionError("the first decode returned before it was released")
                    }
                }
            }
            callers.async { engine.transcribe(validRequest()) }
        } finally {
            release.complete(Unit)
        }

        val firstResult = runBlocking { firstCall.await() }
        val secondResult = runBlocking { secondCall.await() }

        assertTrue("the first call did not return a transcript", firstResult is SttResult.Success)
        assertTrue("the second call did not return a transcript after the first", secondResult is SttResult.Success)
        assertEquals(
            "the two decodes must run one after the other",
            listOf("enter", "exit", "enter", "exit"),
            listOf(entered) + drain(log),
        )
    }
}
