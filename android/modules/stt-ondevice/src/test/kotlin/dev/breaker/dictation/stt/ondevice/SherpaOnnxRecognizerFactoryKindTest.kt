package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** An offline opener under test: it records each call and then does what [behaviour] says. */
internal class OfflineProbeOpener(
    private val behaviour: (TransducerFiles, Int) -> NativeOfflineRecognizer = { _, _ -> FakeOfflineNative() },
) : NativeOfflineOpener {
    val files = mutableListOf<TransducerFiles>()
    val threads = mutableListOf<Int>()

    override fun open(files: TransducerFiles, numThreads: Int): NativeOfflineRecognizer {
        this.files.add(files)
        threads.add(numThreads)
        return behaviour(files, numThreads)
    }
}

/**
 * Tests of the kind switch in the recognizer factory, with fake openers on both sides.
 * Every mutant named here is hand traced, not run.
 */
class SherpaOnnxRecognizerFactoryKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val parakeetNames = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    private val smallNames = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")

    private fun modelDir(names: List<String>): File {
        val dir = tmp.newFolder()
        for (name in names) File(dir, name).writeText("x")
        return dir
    }

    private val tinyNames = listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt")

    /** The factory's entry lookup: the real registry. The kind now comes from the entry, not from a second lookup. */
    private val lookup = { id: String -> ModelRegistry.byId(id) }

    private fun refusal(block: () -> Unit): SherpaTranscriptionException {
        try {
            block()
        } catch (e: SherpaTranscriptionException) {
            return e
        }
        fail("nothing was thrown")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun `an offline entry opens only the offline engine and a streaming entry only the streaming engine`() {
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 3, offline, lookup)

        val offlineDir = modelDir(parakeetNames)
        val parakeet = factory.create(SherpaModel("parakeet", offlineDir, "digest"))
        // Killer of a factory that always picks the streaming path: the first two assertions fire (offline 0, online 1).
        assertEquals("offline opener called once for parakeet", 1, offline.files.size)
        assertEquals("streaming opener not called for parakeet", 0, online.files.size)
        assertTrue("the recognizer is the offline one", parakeet is SherpaOfflineRecognizer)
        assertEquals("the thread count reaches the offline opener", listOf(3), offline.threads)
        assertEquals("parakeet encoder", File(offlineDir, "encoder.int8.onnx"), offline.files.single().encoder)

        factory.create(SherpaModel("small", modelDir(smallNames), "digest"))
        // Killer of a factory that always picks the offline path: the offline count would be 2 and the streaming 0.
        assertEquals("offline opener not called for small", 1, offline.files.size)
        assertEquals("streaming opener called once for small", 1, online.files.size)
    }

    @Test
    fun `a streaming entry gets the streaming recognizer and an offline entry gets the offline one`() {
        val factory = SherpaOnnxRecognizerFactory(FactoryProbeOpener { _, _ -> FactoryProbeNative() }, 2, OfflineProbeOpener(), lookup)
        assertTrue(factory.create(SherpaModel("small", modelDir(smallNames), "d")) is SherpaOnnxRecognizer)
        assertTrue(factory.create(SherpaModel("tiny", modelDir(tinyNames), "d")) is SherpaOnnxRecognizer)
        assertTrue(factory.create(SherpaModel("parakeet", modelDir(parakeetNames), "d")) is SherpaOfflineRecognizer)
    }

    @Test
    fun `whisper entries and unknown ids are refused whatever their kind and no opener is called`() {
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        for (kind in ModelKind.values()) {
            val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { id -> ModelRegistry.byId(id)?.copy(kind = kind) }
            for (id in listOf("base", "medium", "unknown", "")) {
                refusal { factory.create(SherpaModel(id, modelDir(parakeetNames), "d")) }
            }
        }
        assertEquals("streaming opener called for a refused id", 0, online.files.size)
        assertEquals("offline opener called for a refused id", 0, offline.files.size)
    }

    @Test
    fun `an id the registry does not know is refused`() {
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { null }
        val refused = refusal { factory.create(SherpaModel("small", modelDir(smallNames), "d")) }
        assertEquals(SherpaOnnxRecognizerFactory.NO_PROFILE_REFUSAL, refused.message)
        assertEquals(0, online.files.size)
        assertEquals(0, offline.files.size)
    }

    @Test
    fun `a failing offline opener becomes the fixed refusal with the original as cause and never touches the streaming opener`() {
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        for (error in listOf<Throwable>(IllegalStateException("no"), UnsatisfiedLinkError("no lib at /x/y.so"))) {
            val offline = OfflineProbeOpener { _, _ -> throw error }
            val factory = SherpaOnnxRecognizerFactory(online, 2, offline, lookup)
            val refused = refusal { factory.create(SherpaModel("parakeet", modelDir(parakeetNames), "d")) }
            assertSame("the cause is the original failure", error, refused.cause)
            assertEquals("the speech engine could not be opened", refused.message)
        }
        assertEquals("streaming opener called after an offline failure", 0, online.files.size)
    }

    @Test
    fun `the placeholder offline opener refuses with an Exception the loader maps to an unusable engine`() {
        // ModelLoader step 6 catches Exception from factory.create and returns Refusal.ENGINE_UNUSABLE;
        // mapRefusal turns that into ErrorMapping.decodeFailed(). The refusal here is a SherpaTranscriptionException (an Exception).
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val factory = SherpaOnnxRecognizerFactory(online, 2, UnavailableOfflineOpener, lookup)
        val refused = refusal { factory.create(SherpaModel("parakeet", modelDir(parakeetNames), "d")) }
        assertTrue("the cause is the placeholder's own failure", refused.cause is IllegalStateException)
        assertEquals(0, online.files.size)
        // The default constructor argument is the placeholder: a factory built without an offline opener refuses an offline model.
        val bare = SherpaOnnxRecognizerFactory(online, 2, lookup = lookup)
        refusal { bare.create(SherpaModel("parakeet", modelDir(parakeetNames), "d")) }
    }

    @Test
    fun `an offline entry with a missing file is refused before the offline opener is called`() {
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(FactoryProbeOpener { _, _ -> FactoryProbeNative() }, 2, offline, lookup)
        for (victim in parakeetNames) {
            refusal { factory.create(SherpaModel("parakeet", modelDir(parakeetNames.filter { it != victim }), "d")) }
        }
        assertEquals(0, offline.files.size)
    }

    @Test
    fun `the default kind lookup reads the registry entry`() {
        // Depends on the registry change that adds the kind field and marks parakeet OFFLINE (that change is part of this same build).
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 2, offline)
        factory.create(SherpaModel("parakeet", modelDir(parakeetNames), "d"))
        factory.create(SherpaModel("small", modelDir(smallNames), "d"))
        assertEquals("offline opener: parakeet only", 1, offline.files.size)
        assertEquals("streaming opener: small only", 1, online.files.size)
    }

    @Test
    fun `base and medium are refused even when the registry marks them OFFLINE and neither opener is called`() {
        // base and medium are kind OFFLINE in the registry but have no streaming profile and no unpack profile.
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { id -> ModelRegistry.byId(id)?.copy(kind = ModelKind.OFFLINE) }
        for (id in listOf("base", "medium")) {
            val refused = refusal { factory.create(SherpaModel(id, modelDir(parakeetNames), "d")) }
            assertEquals("refusal text for $id", "no streaming profile for this model", refused.message)
        }
        // Killer of a factory that decides by kind alone: the refusal above never throws, "nothing was thrown".
        assertEquals("offline opener called for a whisper entry", 0, offline.files.size)
        assertEquals("streaming opener called for a whisper entry", 0, online.files.size)
    }

    @Test
    fun `an unknown id is refused with the streaming profile message whatever the kind lookup says`() {
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        for (kind in listOf<ModelKind?>(null, ModelKind.OFFLINE, ModelKind.STREAMING)) {
            val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { id -> kind?.let { ModelRegistry.byId(id)?.copy(kind = it) } }
            val refused = refusal { factory.create(SherpaModel("no-such-model", modelDir(parakeetNames), "d")) }
            assertEquals("no streaming profile for this model", refused.message)
        }
        assertEquals(0, online.files.size)
        assertEquals(0, offline.files.size)
    }

    @Test
    fun `an id with an unpack profile whose kind is not OFFLINE and no streaming profile is refused`() {
        // Killer of a factory that decides by the unpack profile alone: parakeet would open the offline engine.
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        for (kind in listOf<ModelKind?>(null, ModelKind.STREAMING)) {
            val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { id -> kind?.let { ModelRegistry.byId(id)?.copy(kind = it) } }
            refusal { factory.create(SherpaModel("parakeet", modelDir(parakeetNames), "d")) }
        }
        assertEquals(0, offline.files.size)
        assertEquals(0, online.files.size)
    }

    @Test
    fun `the offline recognizer for parakeet carries a blank language so the request language applies`() {
        // Killer of a factory that passes a fixed language such as "en": the label would be "en", not "".
        val factory = SherpaOnnxRecognizerFactory(FactoryProbeOpener { _, _ -> FactoryProbeNative() }, 2, OfflineProbeOpener(), lookup)
        val recognizer = factory.create(SherpaModel("parakeet", modelDir(parakeetNames), "d"))
        assertEquals("", recognizer.decode(FloatArray(1_600), 16_000).language)
    }
}
