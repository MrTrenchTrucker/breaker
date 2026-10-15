package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.SttError
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Creates no recognizer: it counts the calls and throws the error it was given. */
private class ThrowingFactory(private val error: Throwable) : SherpaRecognizerFactory {
    var calls: Int = 0

    override fun create(model: SherpaModel): SherpaRecognizer {
        calls++
        throw error
    }
}

class ModelLoaderOutOfMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun loaderWith(store: LocalModelStore, factory: SherpaRecognizerFactory, sink: MutableList<String>) =
        ModelLoader(
            store = store,
            factory = factory,
            lookup = { requested -> if (requested == StampFixture.MODEL) StampFixture.ENTRY else null },
            debug = ModelDebugSink { sink.add(it) },
        )

    private fun installedStore(): LocalModelStore {
        val store = LocalModelStore(tmp.newFolder())
        seedStampArchive(store)
        seedStampUnpacked(store)
        return store
    }

    @Test
    fun `an out of memory error while the engine is created is refused with the engine sentence`() {
        val factory = ThrowingFactory(OutOfMemoryError("test engine"))
        val loader = loaderWith(installedStore(), factory, ArrayList())

        val refused = stampRefusedOf(loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertEquals("the sentence", ModelMessages.ENGINE_COULD_NOT_START, refused.detail)
        assertEquals("the factory was asked once", 1, factory.calls)
    }

    @Test
    fun `a plain exception while the engine is created is refused the same way`() {
        val factory = ThrowingFactory(IllegalStateException("engine init failed"))
        val loader = loaderWith(installedStore(), factory, ArrayList())

        val refused = stampRefusedOf(loader.load(StampFixture.MODEL))

        assertEquals("the refusal", ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertEquals("the sentence", ModelMessages.ENGINE_COULD_NOT_START, refused.detail)
        assertEquals("the factory was asked once", 1, factory.calls)
    }

    @Test
    fun `another error while the engine is created still reaches the caller`() {
        val loader = loaderWith(installedStore(), ThrowingFactory(StackOverflowError()), ArrayList())

        val thrown = try {
            loader.load(StampFixture.MODEL)
            null
        } catch (e: StackOverflowError) {
            e
        }

        assertNotNull("a stack overflow must reach the caller and must not be refused", thrown)
    }

    @Test
    fun `an out of memory refusal leaves the model installed`() {
        val store = installedStore()
        val loader = loaderWith(store, ThrowingFactory(OutOfMemoryError("test engine")), ArrayList())

        stampRefusedOf(loader.load(StampFixture.MODEL))

        assertTrue("the model stays installed", store.isInstalled(StampFixture.MODEL))
        assertTrue("the unpacked files stay", store.isExtracted(StampFixture.MODEL))
        assertNotNull("the stamp stays", store.readStamp(StampFixture.MODEL))
    }

    @Test
    fun `through the engine an out of memory error in the model load is a failed transcribe and not a thrown error`() {
        val loader = loaderWith(installedStore(), ThrowingFactory(OutOfMemoryError("test engine")), ArrayList())
        val engine = OnDeviceSttEngine(loader)
        try {
            val result = engine.transcribe(
                SttRequest(
                    pcm = FloatArray(16_000) { 0.1f },
                    wavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
                    model = StampFixture.MODEL,
                    language = "de",
                ),
            )
            assertEquals(
                "the failed load comes back as a failure result",
                SttResult.Failure(SttError.OTHER, "The on-device engine could not transcribe the audio."),
                result,
            )
        } finally {
            engine.close()
        }
    }
}
