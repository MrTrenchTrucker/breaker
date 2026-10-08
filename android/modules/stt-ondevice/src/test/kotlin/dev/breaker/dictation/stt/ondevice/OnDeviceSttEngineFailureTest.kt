package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttResult
import java.io.File
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * How the engine reports problems that come out of the model store and the
 * recognizer: as a failure result, never as a thrown exception, and never by
 * swallowing a cancellation on the async path.
 *
 * None of these tests waits on a clock. A call that throws is turned into an
 * assertion that names the claim, so a broken engine fails by name.
 */
class OnDeviceSttEngineFailureTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the test.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    private val engines = ArrayList<OnDeviceSttEngine>()

    private fun engineOver(loader: ModelLoaderPort): OnDeviceSttEngine {
        val engine = OnDeviceSttEngine(loader, newSlotWatcher())
        engines.add(engine)
        return engine
    }

    @After
    fun closeEngines() = engines.forEach { it.close() }

    /** A loader whose load always throws [failure]. */
    private class ThrowingLoader(private val failure: Throwable) : ModelLoaderPort {
        override fun load(modelId: String): ModelLoader.LoadResult = throw failure
    }

    /** A loader that throws [failure] on its first call and answers [then] on every later call. */
    private class ThrowOnceLoader(
        private val failure: Throwable,
        private val then: ModelLoader.LoadResult,
    ) : ModelLoaderPort {
        private var calls = 0

        override fun load(modelId: String): ModelLoader.LoadResult {
            calls++
            if (calls == 1) throw failure
            return then
        }
    }

    /** A recognizer whose decode always throws [failure]; it counts how often it was released. */
    private class ThrowingRecognizer(private val failure: Throwable) : SherpaRecognizer {
        var releases = 0

        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = throw failure

        override fun release() {
            releases++
        }
    }

    private fun refusedLoader(leftOnDisk: Boolean): ModelLoaderPort =
        FakeLoader(ModelLoader.LoadResult.Refused(ModelLoader.Refusal.VERIFICATION_REFUSED, "x", leftOnDisk = leftOnDisk))

    /** Runs [call]; a throw becomes an assertion that names [claim]. */
    private fun <T> callOrFail(claim: String, call: () -> T): T =
        try {
            call()
        } catch (e: Throwable) {
            throw AssertionError("$claim: the call threw instead of returning, it threw $e").also { it.initCause(e) }
        }

    private fun detailOf(claim: String, result: SttResult?): String {
        assertTrue("$claim: expected a failure result but got $result", result is SttResult.Failure)
        val failure = result as SttResult.Failure
        assertEquals("$claim: wrong error kind", SttError.LOCAL_MODEL_MISSING, failure.error)
        return failure.detail.orEmpty()
    }

    // --- a store failure while loading comes back as a failure ---

    @Test
    fun `transcribe returns a failure when the loader throws an IOException`() {
        val engine = engineOver(ThrowingLoader(IOException("disk")))
        val claim = "transcribe with a loader that throws an IOException"

        val result = callOrFail(claim) { engine.transcribe(validSlotRequest()) }

        assertEquals("$claim must return the model-unreadable failure", ErrorMapping.modelUnreadable(), result)
    }

    @Test
    fun `transcribe returns a failure when the loader throws a RuntimeException`() {
        val engine = engineOver(ThrowingLoader(IllegalStateException("store")))
        val claim = "transcribe with a loader that throws a RuntimeException"

        val result = callOrFail(claim) { engine.transcribe(validSlotRequest()) }

        assertEquals("$claim must return the model-unreadable failure", ErrorMapping.modelUnreadable(), result)
    }

    @Test
    fun `the model-unreadable failure carries the exact user sentence`() {
        val engine = engineOver(ThrowingLoader(IOException("disk")))

        val result = callOrFail("transcribe with a loader that throws an IOException") {
            engine.transcribe(validSlotRequest())
        }

        assertEquals(
            "the failure text for an unreadable model changed",
            SttResult.Failure(SttError.OTHER, "The on-device model could not be read."),
            result,
        )
    }

    @Test
    fun `preload returns a failure when the loader throws an IOException`() {
        val engine = engineOver(ThrowingLoader(IOException("disk")))
        val claim = "preload with a loader that throws an IOException"

        val result = callOrFail(claim) { engine.preload("tiny") }

        assertEquals("$claim must return the model-unreadable failure", ErrorMapping.modelUnreadable(), result)
    }

    @Test
    fun `preload returns a failure when the loader throws a RuntimeException`() {
        val engine = engineOver(ThrowingLoader(IllegalStateException("store")))
        val claim = "preload with a loader that throws a RuntimeException"

        val result = callOrFail(claim) { engine.preload("tiny") }

        assertEquals("$claim must return the model-unreadable failure", ErrorMapping.modelUnreadable(), result)
    }

    @Test
    fun `transcribeAsync completes with a failure when the loader throws an IOException`() {
        val watcher = newSlotWatcher()
        val engine = OnDeviceSttEngine(ThrowingLoader(IOException("disk")), watcher)
        engines.add(engine)
        val claim = "transcribeAsync with a loader that throws an IOException"

        val call = engine.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(1, "$claim did not hand its own task to the dispatcher")
        joinUnlessStuck(
            call,
            watcher,
            "$claim: the engine handed a second task to its own slot, so the call would wait on the slot it holds",
        )

        assertFalse("$claim ended exceptional or cancelled instead of completing with a failure", call.isCancelled)
        assertEquals(
            "$claim must complete with the model-unreadable failure",
            ErrorMapping.modelUnreadable(),
            runBlocking { call.await() },
        )
    }

    @Test
    fun `a loader that throws once does not leave the engine refusing later calls`() =
        runBlocking<Unit> {
            val recognizer = CountingRecognizer()
            val ready = ModelLoader.LoadResult.Ready(SherpaModel("tiny", File("unused-model-dir"), "unused-digest"), recognizer)
            // The event loop of this runBlocking is one thread that runs queued tasks in order,
            // so both calls run on the test thread, one after the other.
            val loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val engine = OnDeviceSttEngine(ThrowOnceLoader(IOException("disk"), ready), loop)
            try {
                val first = engine.transcribeAsync(validSlotRequest())
                val second = engine.transcribeAsync(validSlotRequest())
                first.join()
                second.join()

                assertFalse("the first call, whose load threw, did not complete with a failure", first.isCancelled)
                assertEquals(
                    "the first call must report the model-unreadable failure",
                    ErrorMapping.modelUnreadable(),
                    first.await(),
                )
                assertFalse("the second call did not complete", second.isCancelled)
                assertTrue(
                    "the second call after a failed load was not a transcript: " +
                        "the failed load left the reentrancy marker set",
                    second.await() is SttResult.Success,
                )
                assertEquals("the second call must reach the recognizer once", 1, recognizer.count)
            } finally {
                engine.close()
            }
        }

    // --- cancellation is not swallowed on the async path; blocking calls never throw ---

    @Test
    fun `transcribeAsync ends cancelled when the decode throws a cancellation`() {
        val recognizer = ThrowingRecognizer(CancellationException("x"))
        val watcher = newSlotWatcher()
        val engine = OnDeviceSttEngine(readyLoader(recognizer), watcher)
        engines.add(engine)

        val call = engine.transcribeAsync(validSlotRequest())
        watcher.expectDispatched(
            1,
            "transcribeAsync with a decode that throws a cancellation did not hand its own task to the dispatcher",
        )
        joinUnlessStuck(
            call,
            watcher,
            "transcribeAsync with a decode that throws a cancellation: the engine handed a second task to its own slot, " +
                "so the call would wait on the slot it holds",
        )

        assertTrue(
            "transcribeAsync turned a cancellation thrown by the decode into an ordinary result",
            call.isCancelled,
        )
        assertEquals("the recognizer must be released exactly once", 1, recognizer.releases)
    }

    @Test
    fun `transcribe returns a failure when the decode throws a cancellation`() {
        val recognizer = ThrowingRecognizer(CancellationException("x"))
        val engine = engineOver(readyLoader(recognizer))
        val claim = "transcribe with a decode that throws a cancellation"

        val result = callOrFail(claim) { engine.transcribe(validSlotRequest()) }

        assertEquals("$claim must return the decode-failed failure", ErrorMapping.decodeFailed(), result)
        assertEquals("the recognizer must be released exactly once", 1, recognizer.releases)
    }

    @Test
    fun `preload returns a failure when the loader throws a cancellation`() {
        val engine = engineOver(ThrowingLoader(CancellationException("x")))
        val claim = "preload with a loader that throws a cancellation"

        val result = callOrFail(claim) { engine.preload("tiny") }

        assertEquals("$claim must return the decode-failed failure", ErrorMapping.decodeFailed(), result)
    }

    @Test
    fun `transcribe reports a loader cancellation as decode failed and not as an unreadable model`() {
        val engine = engineOver(ThrowingLoader(CancellationException("x")))
        val claim = "transcribe with a loader that throws a cancellation"

        val result = callOrFail(claim) { engine.transcribe(validSlotRequest()) }

        assertEquals("$claim must return the decode-failed failure", ErrorMapping.decodeFailed(), result)
    }

    @Test
    fun `transcribe still returns a failure when the decode throws a RuntimeException`() {
        val recognizer = ThrowingRecognizer(IllegalArgumentException("bad audio"))
        val engine = engineOver(readyLoader(recognizer))
        val claim = "transcribe with a decode that throws a RuntimeException"

        val result = callOrFail(claim) { engine.transcribe(validSlotRequest()) }

        assertEquals("$claim must return the decode-failed failure", ErrorMapping.decodeFailed(), result)
        assertEquals("the recognizer must be released exactly once", 1, recognizer.releases)
    }

    // --- the left-on-disk sentence reaches the user through the engine ---

    @Test
    fun `a refused checksum with the delete failed is reported as not deleted`() {
        val engine = engineOver(refusedLoader(leftOnDisk = true))

        val viaTranscribe = detailOf("transcribe", engine.transcribe(validSlotRequest()))
        val viaPreload = detailOf("preload", engine.preload("tiny"))

        for ((entry, detail) in listOf("transcribe" to viaTranscribe, "preload" to viaPreload)) {
            assertTrue(
                "$entry: a model left on disk must be reported as not deleted: $detail",
                detail.contains("could not be deleted"),
            )
            assertFalse(
                "$entry: a model left on disk was reported as deleted: $detail",
                detail.contains("was deleted"),
            )
        }
    }

    @Test
    fun `a refused checksum with the model deleted is reported as deleted`() {
        val engine = engineOver(refusedLoader(leftOnDisk = false))

        val viaTranscribe = detailOf("transcribe", engine.transcribe(validSlotRequest()))
        val viaPreload = detailOf("preload", engine.preload("tiny"))

        for ((entry, detail) in listOf("transcribe" to viaTranscribe, "preload" to viaPreload)) {
            assertTrue(
                "$entry: a deleted model must be reported as deleted: $detail",
                detail.contains("was deleted"),
            )
            assertFalse(
                "$entry: a deleted model was reported as not deleted: $detail",
                detail.contains("could not be deleted"),
            )
        }
    }
}
