package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout

/**
 * The real pieces wired together: a real model loader over a real store, the real
 * recognizer factory (through its internal constructor, with a fake opener), the real
 * recognizer adapter, and the real engine. Only the native engine is faked. These tests
 * show that the parts agree with each other, which no test of a single part can show.
 */
class SherpaOnnxRecognizerWiringTest {

    // A net for the failure arm only: no test relies on it, and the expected red
    // is always an assertion that names the claim.
    @get:Rule
    val safetyNet: Timeout = Timeout.seconds(60)

    @get:Rule
    val tmp = TemporaryFolder()

    private val engines = ArrayList<OnDeviceSttEngine>()

    @After
    fun closeEngines() {
        engines.forEach { it.close() }
    }

    private val modelId = "tiny"
    private val archiveBytes = "wiring test archive bytes".toByteArray()
    private val digest = Fixtures.independentSha256(archiveBytes)
    private val entry = ModelEntry(modelId, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)

    // Text the failing openers carry. It must never reach a user-facing value.
    private val secret = "secret-native-detail"

    private class Rig(
        val store: LocalModelStore,
        val opener: FactoryProbeOpener,
        val sink: MutableList<String>,
        val engine: OnDeviceSttEngine,
    )

    /**
     * A store holding an installed, verified model with its four unpacked files, a real
     * loader over it, a real factory with [numThreads] over a fake opener that does
     * [behaviour], and a real engine over the loader.
     */
    private fun rig(numThreads: Int, behaviour: (TransducerFiles, Int) -> NativeStreamingRecognizer): Rig {
        val store = LocalModelStore(tmp.newFolder())
        Fixtures.writeBytes(store.archiveFile(modelId), archiveBytes)
        store.markVerified(modelId, digest)
        store.storeChecksums(modelId, "model.archive\t$digest")
        Fixtures.seedExtracted(store, modelId)
        assertTrue("fixture: the model must be installed", store.isInstalled(modelId))
        assertTrue("fixture: the four unpacked files must be in place", store.isExtracted(modelId))

        val opener = FactoryProbeOpener(behaviour)
        val sink = ArrayList<String>()
        val loader = ModelLoader(
            store,
            SherpaOnnxRecognizerFactory(opener, numThreads),
            { requested -> if (requested == modelId) entry else null },
            ModelDebugSink { sink.add(it) },
        )
        val engine = OnDeviceSttEngine(loader)
        engines.add(engine)
        return Rig(store, opener, sink, engine)
    }

    /** One second of audio at 16 kHz. The request language differs from the profile language on purpose. */
    private fun request(): SttRequest = SttRequest(
        pcm = FloatArray(16_000) { 0.1f },
        wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
        model = modelId,
        language = "de",
    )

    /** Runs [call]; anything that escapes the engine becomes an assertion that names [claim]. */
    private fun <T> callOrFail(claim: String, call: () -> T): T =
        try {
            call()
        } catch (e: Throwable) {
            throw AssertionError("$claim: the call threw $e instead of returning a result", e)
        }

    @Test
    fun `behind a real model loader and engine the fake opener transcript comes back as a success`() {
        val native = ScriptedNative(decodesNeeded = 2, text = "hello wiring")
        val rig = rig(numThreads = 3) { _, _ -> native }

        val result = callOrFail("transcribe over the real loader") { rig.engine.transcribe(request()) }

        assertTrue("the transcript of the opened engine must come back as a success but got $result", result is SttResult.Success)
        val success = result as SttResult.Success
        assertEquals("the text must be the text the opened engine produced", "hello wiring", success.text)
        assertEquals("the language must be the model profile language, not the request language", "en", success.language)
        assertEquals("one segment spans the clip", 1, success.segments.size)
        assertEquals("the segment carries the transcript text", "hello wiring", success.segments.single().text)

        // The opener received what the loader and the factory located, and the factory's thread count.
        assertEquals("the engine must be opened once", 1, rig.opener.files.size)
        assertEquals("the opener must get the thread count the factory was built with", listOf(3), rig.opener.threads)
        val profile = ExtractionProfiles.forModel(modelId)!!
        val directory = rig.store.extractedDirectory(modelId)
        val files = rig.opener.files.single()
        assertEquals("encoder file", File(directory, profile.encoder), files.encoder)
        assertEquals("decoder file", File(directory, profile.decoder), files.decoder)
        assertEquals("joiner file", File(directory, profile.joiner), files.joiner)
        assertEquals("tokens file", File(directory, profile.tokens), files.tokens)

        // The clip really went through the native stream, and everything was released again.
        assertEquals("one stream per decode", 1, native.count("createStream"))
        assertEquals("the decode steps the opened engine asked for must all run", 2, native.count("decode"))
        assertEquals("the clip and the silence block are fed", 2, native.fed.size)
        assertEquals("the first block is the whole clip", 16_000, native.fed[0].length)
        assertEquals("the clip is fed at 16 kHz", 16_000, native.fed[0].rate)
        assertEquals("the stream must be released", 1, native.count("streamRelease"))
        assertEquals("the recognizer must be released once the engine is done", 1, native.count("release"))
        assertTrue("a successful load writes no refusal text to the debug sink: ${rig.sink}", rig.sink.isEmpty())
    }

    /** Opens fail with [thrown]; the engine must answer with the engine failure and let nothing escape. */
    private fun assertOpenFailureBecomesDecodeFailed(label: String, thrown: Throwable) {
        val rig = rig(numThreads = 2) { _, _ -> throw thrown }

        val result = callOrFail("$label through transcribe") { rig.engine.transcribe(request()) }

        // The loader reports an unusable engine and the engine maps that to the decode failure.
        // A raw exception that bypassed the loader would show up as the model-unreadable failure instead.
        assertEquals("$label: wrong failure for an engine that cannot be opened", ErrorMapping.decodeFailed(), result)
        assertEquals("$label: the opener must have been asked once", 1, rig.opener.files.size)

        // The refusal came from the loader's engine arm, and the native error text stayed out of it.
        assertEquals("$label: the loader must write exactly one refusal text: ${rig.sink}", 1, rig.sink.size)
        val line = rig.sink.single()
        assertTrue("$label: the refusal must be the engine one but was: $line", line.startsWith("ENGINE_UNUSABLE: engine failed: "))
        assertFalse("$label: the native error text reached the debug sink: $line", line.contains(secret))

        // A refused engine is not a bad model: nothing is deleted.
        assertTrue("$label: the archive must stay", rig.store.isInstalled(modelId))
        assertTrue("$label: the unpacked files must stay", rig.store.isExtracted(modelId))
    }

    @Test
    fun `behind a real model loader and engine an opener that throws an Exception gives the decode failed failure and transcribe does not throw`() {
        assertOpenFailureBecomesDecodeFailed("Exception", IllegalStateException(secret))
    }

    @Test
    fun `behind a real model loader and engine an opener that throws a LinkageError gives the decode failed failure and transcribe does not throw`() {
        assertOpenFailureBecomesDecodeFailed("LinkageError", UnsatisfiedLinkError("cannot load $secret"))
    }
}
