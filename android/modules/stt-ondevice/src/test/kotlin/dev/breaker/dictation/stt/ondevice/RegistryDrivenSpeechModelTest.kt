package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelFiles
import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelLimits
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A speech model is data: an entry the registry would carry, passed through the lookup seams, installs, unpacks,
 * loads and opens with no code that knows its id. The ids below exist nowhere in main code. UNCOMPILED: nothing
 * here was compiled or run; every killer is hand traced (see the notes of the piece).
 */
class RegistryDrivenSpeechModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = ModelFiles("enc.x.onnx", "dec.x.onnx", "join.x.onnx", "toks.txt")
    private val limits = ModelLimits(
        maxEntryBytes = 1_048_576L, maxWrittenBytes = 2_097_152L, maxStreamBytes = 4_194_304L, maxEntries = 16, maxDepth = 3,
    )

    private fun entry(
        id: String,
        kind: ModelKind,
        modelType: String,
        archive: ByteArray = ByteArray(1),
        language: String = "",
    ) = ModelEntry(
        id = id, family = ModelFamily.SHERPA_ONNX, url = "https://example.com/m", sha256 = Fixtures.independentSha256(archive),
        sizeMb = 1, licence = "test", hosted = false, kind = kind, sizeBytes = archive.size.toLong(),
        modelType = modelType, files = names, limits = limits, language = language, offered = false,
    )

    private val top = "zz-top"
    private fun archive(): ByteArray = TarFixtures.bz2(
        TarFixtures.archiveOf(
            TarFixtures.dir(top),
            TarFixtures.file("$top/${names.tokens}", "t".toByteArray()),
            TarFixtures.file("$top/README.md", "r".toByteArray()),
            TarFixtures.file("$top/${names.joiner}", "j".toByteArray()),
            TarFixtures.file("$top/${names.decoder}", "d".toByteArray()),
            TarFixtures.file("$top/${names.encoder}", "e".toByteArray()),
        ),
    )

    private fun dirWith(vararg files: String): File = tmp.newFolder().also { d -> files.forEach { File(d, it).writeText("x") } }

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
    fun `an offline entry nobody wrote code for installs unpacks loads and opens with its own type and files`() {
        val bytes = archive()
        val fake = entry("zz-offline-model", ModelKind.OFFLINE, "nemo_transducer", bytes)
        val lookup = { id: String -> if (id == fake.id) fake else null }
        val store = LocalModelStore(tmp.newFolder(), lookup = lookup)
        val extractor = ModelExtractor(lookup = lookup)
        val installed = ModelInstaller(store, ArchiveFetcher(bytes), extractor = extractor, usableSpace = { Long.MAX_VALUE }, lookup = lookup).install(fake)
        // Killer of any id-keyed profile: no profile -> Refused(UNSUPPORTED_MODEL), so this equality fails.
        assertEquals(ModelInstaller.InstallResult.Installed(fake.id, fake.sha256), installed)
        // Killer of an allow-list that is not the entry's names: README or a wrong name would appear or a name be missing.
        assertEquals(
            (listOf(names.encoder, names.decoder, names.joiner, names.tokens) + UnpackedStamp.FILE_NAME).sorted(),
            store.extractedDirectory(fake.id).list()!!.sorted(),
        )
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 2, offline, lookup)
        val loaded = ModelLoader(store, factory, lookup, profileLookup = lookup).load(fake.id)
        assertTrue("expected Ready but got $loaded", loaded is ModelLoader.LoadResult.Ready)
        assertEquals("offline opener called once", 1, offline.files.size)
        assertEquals("streaming opener not called", 0, online.files.size)
        // Killer of a locate that drops the entry's model type (the empty string arrives instead).
        assertEquals("nemo_transducer", offline.files.single().modelType)
        val dir = store.extractedDirectory(fake.id)
        assertEquals(File(dir, names.encoder), offline.files.single().encoder)
        assertEquals(File(dir, names.decoder), offline.files.single().decoder)
        assertEquals(File(dir, names.joiner), offline.files.single().joiner)
        assertEquals(File(dir, names.tokens), offline.files.single().tokens)
    }

    @Test
    fun `the profile of an entry carries the limits and names of that entry`() {
        val fake = entry("zz-limits-model", ModelKind.OFFLINE, "nemo_transducer")
        val profile = ExtractionProfiles.forEntry(fake)
        assertNotNull(profile)
        // Killer of limits copied from a constant: each figure differs from every other entry's.
        assertEquals(1_048_576L, profile!!.limits.maxEntryBytes)
        assertEquals(2_097_152L, profile.limits.maxWrittenBytes)
        assertEquals(4_194_304L, profile.limits.maxStreamBytes)
        assertEquals(16, profile.limits.maxEntries)
        assertEquals(3, profile.limits.maxDepth)
        assertEquals(listOf("enc.x.onnx", "dec.x.onnx", "join.x.onnx", "toks.txt"), profile.files)
        assertEquals("an id the lookup does not know has no profile", null, ExtractionProfiles.forModel("zz-nobody") { null })
    }

    @Test
    fun `a streaming entry takes the online path with an empty model type`() {
        val fake = entry("zz-stream-model", ModelKind.STREAMING, "", language = "en")
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { id -> if (id == fake.id) fake else null }
        val recognizer = factory.create(SherpaModel(fake.id, dirWith(names.encoder, names.decoder, names.joiner, names.tokens), "d"))
        // Killer of a factory that always picks offline: the online count would be 0 and the type would be the offline recognizer.
        assertTrue(recognizer is SherpaOnnxRecognizer)
        assertEquals(1, online.files.size)
        assertEquals(0, offline.files.size)
        assertEquals("", online.files.single().modelType)
        assertEquals("the streaming profile reads the entry's language", "en", StreamingProfiles.forModel(fake.id) { fake }!!.language)
        assertNull("an offline entry has no streaming profile", StreamingProfiles.forModel("zz-off") { entry("zz-off", ModelKind.OFFLINE, "nemo_transducer") })
    }

    @Test
    fun `an entry with an unsupported kind and model type pair is refused and no opener is called`() {
        val bad = listOf(
            entry("zz-whisper-like", ModelKind.OFFLINE, "whisper"),
            entry("zz-wrong-pair", ModelKind.STREAMING, "nemo_transducer"),
            entry("zz-unknown-type", ModelKind.OFFLINE, "some_future_type"),
        )
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        for (fake in bad) {
            val factory = SherpaOnnxRecognizerFactory(online, 2, offline) { id -> if (id == fake.id) fake else null }
            val dir = dirWith(names.encoder, names.decoder, names.joiner, names.tokens)
            // Killer of a factory that decides by the files present: it would open one of the two openers.
            val e = refusal { factory.create(SherpaModel(fake.id, dir, "d")) }
            assertEquals(fake.id, "no streaming profile for this model", e.message)
            assertNull(fake.id, ExtractionProfiles.forEntry(fake))
            val fetcher = ArchiveFetcher(archive())
            val result = ModelInstaller(
                LocalModelStore(tmp.newFolder()) { id -> if (id == fake.id) fake else null }, fetcher,
                extractor = ModelExtractor { id -> if (id == fake.id) fake else null }, usableSpace = { Long.MAX_VALUE },
                lookup = { id -> if (id == fake.id) fake else null },
            ).install(fake)
            assertTrue("$result", result is ModelInstaller.InstallResult.Refused)
            assertEquals(ModelInstaller.Refusal.UNSUPPORTED_MODEL, (result as ModelInstaller.InstallResult.Refused).refusal)
            assertEquals("no download for ${fake.id}", 0, fetcher.modelFetches)
        }
        assertEquals("no streaming opener call", 0, online.files.size)
        assertEquals("no offline opener call", 0, offline.files.size)
    }

    @Test
    fun `the whisper entries of the real registry are refused by the factory`() {
        val online = FactoryProbeOpener { _, _ -> FactoryProbeNative() }
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(online, 2, offline)
        for (entry in listOf(ModelRegistry.BASE, ModelRegistry.MEDIUM)) {
            val dir = dirWith("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
            refusal { factory.create(SherpaModel(entry.id, dir, "d")) }
        }
        assertEquals(0, online.files.size + offline.files.size)
        refusal { factory.create(SherpaModel("zz-not-in-the-registry", dirWith(), "d")) }
    }

    @Test
    fun `a supported entry whose named file is missing is refused before any opener`() {
        val fake = entry("zz-missing-file", ModelKind.OFFLINE, "nemo_transducer")
        val offline = OfflineProbeOpener()
        val factory = SherpaOnnxRecognizerFactory(FactoryProbeOpener { _, _ -> FactoryProbeNative() }, 2, offline) { fake }
        // The directory holds the names the old code looked for, not the entry's: only the entry's names count.
        val dir = dirWith("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        val e = refusal { factory.create(SherpaModel(fake.id, dir, "d")) }
        assertEquals("the model files are missing or empty", e.message)
        assertEquals(0, offline.files.size)
    }
}
