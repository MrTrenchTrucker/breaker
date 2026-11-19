package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The keyword model loader refuses for the same reasons the speech loader does, one to
 * one, and opens no engine. Every case uses made-up archive bytes and a made-up entry.
 */
class KeywordModelLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    private val bytes = "keyword model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry(KEYWORD_MODEL_ID, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.root)
    }

    private fun lookupOf(found: ModelEntry?): (String) -> ModelEntry? = { id -> if (id == KEYWORD_MODEL_ID) found else null }

    private fun loader(found: ModelEntry? = entry, verifyCalls: IntArray = IntArray(1)): KeywordModelLoader =
        KeywordModelLoader(
            store,
            lookupOf(found),
            NoDebugSink,
            { file, pin, expectedBytes ->
                verifyCalls[0]++
                ModelIntegrity.verify(file, pin, expectedBytes)
            },
        )

    private fun seedArchive(withChecksums: Boolean = true, withFiles: Boolean = true) {
        val dir = store.directoryFor(KEYWORD_MODEL_ID)
        dir.mkdirs()
        File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
        store.markVerified(KEYWORD_MODEL_ID, digest)
        if (withChecksums) Fixtures.writeLegacyChecksums(store, KEYWORD_MODEL_ID, "model.archive\t$digest")
        if (withFiles) Fixtures.seedExtracted(store, KEYWORD_MODEL_ID)
    }

    private fun refused(result: KeywordLoad): KeywordLoad.Refused {
        assertTrue("stt-ondevice keyword loader: expected a refusal but got $result", result is KeywordLoad.Refused)
        return result as KeywordLoad.Refused
    }

    @Test
    fun `an id the keyword lookup does not know is refused as unknown`() {
        val result = refused(loader(found = null).load())
        assertEquals("stt-ondevice keyword loader: unknown id", ModelLoader.Refusal.UNKNOWN_MODEL, result.refusal)
        assertEquals(ModelMessages.MODEL_UNKNOWN, result.detail)
    }

    @Test
    fun `an entry that is not a sherpa family model is refused as wrong family`() {
        val whisper = ModelEntry(KEYWORD_MODEL_ID, ModelFamily.WHISPER, "https://example.com/model", digest, 1, "MIT", false)
        val result = refused(loader(found = whisper).load())
        assertEquals("stt-ondevice keyword loader: wrong family", ModelLoader.Refusal.WRONG_FAMILY, result.refusal)
        assertEquals(ModelMessages.MODEL_WRONG_FAMILY, result.detail)
    }

    @Test
    fun `a model that is not installed is refused as not installed`() {
        val result = refused(loader().load())
        assertEquals("stt-ondevice keyword loader: not installed", ModelLoader.Refusal.NOT_INSTALLED, result.refusal)
        assertEquals(ModelMessages.MODEL_NOT_INSTALLED, result.detail)
    }

    @Test
    fun `missing stored checksums do not stop the load and the archive is kept`() {
        seedArchive(withChecksums = false)
        val result = loader().load()
        assertTrue("stt-ondevice keyword loader: no checksums must still load but got $result", result is KeywordLoad.Ready)
        assertEquals(digest, (result as KeywordLoad.Ready).model.digest)
        assertTrue(store.isInstalled(KEYWORD_MODEL_ID))
    }

    @Test
    fun `stored checksums that cannot be parsed do not stop the load`() {
        seedArchive()
        Fixtures.writeLegacyChecksums(store, KEYWORD_MODEL_ID, "not valid checksums")
        val result = loader().load()
        assertTrue("stt-ondevice keyword loader: bad checksums must still load but got $result", result is KeywordLoad.Ready)
        assertEquals(digest, (result as KeywordLoad.Ready).model.digest)
        assertTrue(store.isInstalled(KEYWORD_MODEL_ID))
    }

    @Test
    fun `an archive that fails verification is refused and the model is deleted`() {
        seedArchive()
        File(store.directoryFor(KEYWORD_MODEL_ID), LocalModelStore.ARCHIVE_NAME).writeBytes("tampered".toByteArray())
        val result = refused(loader().load())
        assertEquals("stt-ondevice keyword loader: tampered archive", ModelLoader.Refusal.VERIFICATION_REFUSED, result.refusal)
        assertEquals(ModelMessages.CHECK_FAILED_DELETED, result.detail)
        assertFalse("stt-ondevice keyword loader: the model directory must be gone", store.directoryFor(KEYWORD_MODEL_ID).exists())
    }

    @Test
    fun `a verified archive without unpacked files is refused as not installed and kept`() {
        seedArchive(withFiles = false)
        val result = refused(loader().load())
        assertEquals("stt-ondevice keyword loader: no unpacked files", ModelLoader.Refusal.NOT_INSTALLED, result.refusal)
        assertTrue(store.isInstalled(KEYWORD_MODEL_ID))
    }

    @Test
    fun `a ready load carries the model id the unpacked directory and the archive digest and opens no engine`() {
        seedArchive()
        val result = loader().load()
        assertTrue("stt-ondevice keyword loader: expected Ready but got $result", result is KeywordLoad.Ready)
        val model = (result as KeywordLoad.Ready).model
        assertEquals(KEYWORD_MODEL_ID, model.modelId)
        assertEquals(store.extractedDirectory(KEYWORD_MODEL_ID), model.directory)
        assertEquals(digest, model.digest)
    }

    @Test
    fun `the first ready load writes the stamp and the second load trusts it without checking the archive`() {
        seedArchive()
        val calls = IntArray(1)
        val subject = loader(verifyCalls = calls)
        assertTrue(subject.load() is KeywordLoad.Ready)
        assertEquals("stt-ondevice keyword loader: first load verifies the archive once", 1, calls[0])
        assertTrue(
            "stt-ondevice keyword loader: the stamp file must exist after the first load",
            File(store.extractedDirectory(KEYWORD_MODEL_ID), UnpackedStamp.FILE_NAME).isFile,
        )
        assertTrue(subject.load() is KeywordLoad.Ready)
        assertEquals("stt-ondevice keyword loader: the second load must not hash the archive", 1, calls[0])
    }

    @Test
    fun `a changed unpacked file after the stamp is refused and deleted`() {
        seedArchive()
        val subject = loader()
        assertTrue(subject.load() is KeywordLoad.Ready)
        val encoder = File(store.extractedDirectory(KEYWORD_MODEL_ID), ExtractionProfiles.forModel(KEYWORD_MODEL_ID)!!.encoder)
        encoder.writeText("changed to a different length of text")
        val result = refused(subject.load())
        assertEquals("stt-ondevice keyword loader: changed file", ModelLoader.Refusal.NOT_INSTALLED, result.refusal)
        assertFalse(encoder.exists())
    }

    @Test
    fun `the default lookup is the keyword list not the speech list`() {
        val result = refused(KeywordModelLoader(store).load())
        assertEquals(
            "stt-ondevice keyword loader: the keyword id must be known to the default lookup",
            ModelLoader.Refusal.NOT_INSTALLED,
            result.refusal,
        )
    }

    @Test
    fun `speech loading is unchanged for small and tiny`() {
        for (id in listOf("small", "tiny")) {
            val speechEntry = ModelEntry(id, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false, sizeBytes = bytes.size.toLong())
            val dir = store.directoryFor(id)
            dir.mkdirs()
            File(dir, LocalModelStore.ARCHIVE_NAME).writeBytes(bytes)
            store.markVerified(id, digest)
            Fixtures.writeLegacyChecksums(store, id, "model.archive\t$digest")
            Fixtures.seedExtracted(store, id)
            val recognizer = object : SherpaRecognizer {
                override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = SherpaTranscript("", emptyList(), "en")

                override fun release() {}
            }
            val speech = ModelLoader(store, { recognizer }, { requested -> if (requested == id) speechEntry else null })
            val result = speech.load(id)
            assertTrue("stt-ondevice speech loader: $id must still load but got $result", result is ModelLoader.LoadResult.Ready)
            assertEquals(id, (result as ModelLoader.LoadResult.Ready).model.modelId)
            assertTrue("stt-ondevice speech loader: $id must get the recognizer from its factory", result.recognizer === recognizer)
        }
    }
}
