package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The loader's part of the unpack step: a verified archive is not enough, the
 * unpacked files must be there too; the archive is judged first; and the engine
 * is handed the directory of the unpacked files.
 */
class ModelLoaderUnpackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"

    /** The four file names of the profile of the "tiny" model. */
    private val fourFiles = TarFixtures.TINY_FILES.toTypedArray()
    private val bytes = "test model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry(modelId, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = SherpaTranscript("", emptyList(), "en")

        override fun release() {}
    }

    /** Records every model it is asked to create a recognizer for; throws [failure] when one is given. */
    private class CountingFactory(private val failure: Exception? = null) : SherpaRecognizerFactory {
        val models = ArrayList<SherpaModel>()

        override fun create(model: SherpaModel): SherpaRecognizer {
            models.add(model)
            if (failure != null) throw failure
            return IdleRecognizer
        }
    }

    private class Rig(val store: LocalModelStore, val factory: CountingFactory, val sink: MutableList<String>, val loader: ModelLoader)

    private fun rig(removalWorks: Boolean = true, factory: CountingFactory = CountingFactory()): Rig {
        val root = tmp.newFolder()
        val store = if (removalWorks) LocalModelStore(root) else LocalModelStore(root, remove = { false })
        val sink = ArrayList<String>()
        val loader = ModelLoader(store, factory, { if (it == modelId) entry else null }, ModelDebugSink { sink.add(it) })
        return Rig(store, factory, sink, loader)
    }

    /** Writes an archive, both markers and the named unpacked files. */
    private fun seed(rig: Rig, archive: ByteArray = bytes, vararg files: String) {
        Fixtures.writeBytes(rig.store.archiveFile(modelId), archive)
        rig.store.markVerified(modelId, digest)
        rig.store.storeChecksums(modelId, "model.archive\t$digest")
        for (name in files) Fixtures.writeBytes(File(rig.store.extractedDirectory(modelId), name), name.toByteArray())
    }

    private fun refusedOf(result: ModelLoader.LoadResult): ModelLoader.LoadResult.Refused {
        assertTrue("expected a refusal but got $result", result is ModelLoader.LoadResult.Refused)
        return result as ModelLoader.LoadResult.Refused
    }

    private fun readyOf(result: ModelLoader.LoadResult): ModelLoader.LoadResult.Ready {
        assertTrue("expected a loaded model but got $result", result is ModelLoader.LoadResult.Ready)
        return result as ModelLoader.LoadResult.Ready
    }

    /** What a load must leave alone when the files are missing: the archive and both markers. */
    private fun assertKept(rig: Rig) {
        assertTrue("the archive must stay", rig.store.archiveFile(modelId).isFile)
        assertArrayEquals("the archive must stay unchanged", bytes, rig.store.archiveFile(modelId).readBytes())
        assertEquals(digest, rig.store.lastVerifiedDigest(modelId))
        assertEquals("model.archive\t$digest", rig.store.storedChecksums(modelId))
    }

    private fun assertMissingFilesRefusal(label: String, rig: Rig) {
        val refused = refusedOf(rig.loader.load(modelId))
        assertEquals("$label: refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
        assertEquals("$label: sentence", ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
        assertFalse("$label: nothing was deleted, so nothing is reported", refused.leftOnDisk)
        assertEquals("$label: the engine must not be asked", 0, rig.factory.models.size)
        assertEquals(
            "$label: sink",
            listOf("NOT_INSTALLED: model 'tiny' is verified but has no unpacked files"),
            rig.sink,
        )
        assertKept(rig)
    }

    @Test
    fun `load refuses with NOT_INSTALLED when the archive is verified but the files directory is missing`() {
        val r = rig()
        seed(r)
        assertTrue("fixture: the archive alone looks installed", r.store.isInstalled(modelId))
        assertFalse("fixture: no files directory", r.store.extractedDirectory(modelId).exists())
        assertMissingFilesRefusal("missing directory", r)
        assertTrue("the sink text names the unpacked files", r.sink.single().contains("unpacked"))
    }

    @Test
    fun `load refuses with NOT_INSTALLED when the files directory is empty or is a plain file`() {
        val empty = rig()
        seed(empty)
        empty.store.extractedDirectory(modelId).mkdirs()
        assertMissingFilesRefusal("empty directory", empty)

        val plain = rig()
        seed(plain)
        plain.store.extractedDirectory(modelId).writeText("not a directory")
        assertMissingFilesRefusal("plain file", plain)
    }

    @Test
    fun `the same fixture with an unpacked file loads so the refusal comes from the missing files`() {
        val r = rig()
        seed(r, bytes, *fourFiles)
        val ready = readyOf(r.loader.load(modelId))
        assertEquals(modelId, ready.model.modelId)
        assertEquals(digest, ready.model.digest)
        assertEquals(1, r.factory.models.size)
    }

    @Test
    fun `a tampered archive without files still gets VERIFICATION_REFUSED and the delete`() {
        val tampered = "tampered".toByteArray()
        val worked = rig()
        seed(worked, tampered)
        val deleted = refusedOf(worked.loader.load(modelId))
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, deleted.refusal)
        assertEquals(ModelMessages.CHECK_FAILED_DELETED, deleted.detail)
        assertFalse(worked.store.directoryFor(modelId).exists())
        assertTrue(worked.sink.single(), worked.sink.single().startsWith("VERIFICATION_REFUSED: PIN_MISMATCH"))
        assertEquals(0, worked.factory.models.size)

        val stuck = rig(removalWorks = false)
        seed(stuck, tampered)
        val left = refusedOf(stuck.loader.load(modelId))
        assertEquals(ModelLoader.Refusal.VERIFICATION_REFUSED, left.refusal)
        assertTrue(left.leftOnDisk)
        assertEquals(ModelMessages.CHECK_FAILED_NOT_DELETED, left.detail)
    }

    @Test
    fun `unreadable checksums are reported before the files are looked at`() {
        val r = rig()
        Fixtures.writeBytes(r.store.archiveFile(modelId), bytes)
        r.store.markVerified(modelId, digest)
        val refused = refusedOf(r.loader.load(modelId))
        assertEquals(ModelLoader.Refusal.CHECKSUMS_UNREADABLE, refused.refusal)
        assertEquals(ModelMessages.NOT_VERIFIED_YET, refused.detail)
    }

    @Test
    fun `load hands the files directory to the factory`() {
        val r = rig()
        seed(r, bytes, *fourFiles)
        val ready = readyOf(r.loader.load(modelId))
        val files = r.store.extractedDirectory(modelId)
        assertEquals(files, ready.model.directory)
        assertNotEquals("not the model directory", r.store.directoryFor(modelId), ready.model.directory)
        assertEquals("the factory saw the same model", listOf(ready.model), r.factory.models)
        assertEquals(fourFiles.sorted(), ready.model.directory.list()!!.sorted())
        assertEquals(digest, ready.model.digest)
    }

    @Test
    fun `a load after the files appear succeeds without touching the archive`() {
        val r = rig()
        seed(r)
        assertEquals(ModelLoader.Refusal.NOT_INSTALLED, refusedOf(r.loader.load(modelId)).refusal)
        assertEquals(0, r.factory.models.size)

        for (name in fourFiles) Fixtures.writeBytes(File(r.store.extractedDirectory(modelId), name), "t".toByteArray())
        readyOf(r.loader.load(modelId))
        assertEquals(1, r.factory.models.size)
        assertKept(r)
    }

    @Test
    fun `an engine that fails with the files in place is still reported as ENGINE_UNUSABLE and nothing is deleted`() {
        val r = rig(factory = CountingFactory(IllegalStateException("init failed")))
        seed(r, bytes, *fourFiles)
        val refused = refusedOf(r.loader.load(modelId))
        assertEquals(ModelLoader.Refusal.ENGINE_UNUSABLE, refused.refusal)
        assertEquals(ModelMessages.ENGINE_COULD_NOT_START, refused.detail)
        assertEquals(1, r.factory.models.size)
        assertTrue("the files stay", r.store.isExtracted(modelId))
        assertKept(r)
    }
}
