package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The loader and a half-finished unpack: a verified archive whose files directory
 * holds only some of the four profile files is not an installed model. It is
 * refused as not installed, the engine is never asked, and the archive and its
 * markers stay so that installing again repairs it.
 */
class ModelLoaderFourNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val modelId = "tiny"
    private val bytes = "test model bytes".toByteArray()
    private val digest = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry(modelId, ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
    private val names = TarFixtures.TINY_FILES

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = SherpaTranscript("", emptyList(), "en")

        override fun release() {}
    }

    private class Rig(val store: LocalModelStore, val loader: ModelLoader, val sink: MutableList<String>, val asked: MutableList<SherpaModel>)

    /** A verified archive with both markers and the four files, each file then changed by [damage] when given. */
    private fun rig(damage: ((File) -> Unit)? = null, damaged: String? = null): Rig {
        val store = LocalModelStore(tmp.newFolder())
        val sink = ArrayList<String>()
        val asked = ArrayList<SherpaModel>()
        val factory = SherpaRecognizerFactory { model -> asked.add(model); IdleRecognizer }
        val loader = ModelLoader(store, factory, { if (it == modelId) entry else null }, ModelDebugSink { sink.add(it) })
        Fixtures.writeBytes(store.archiveFile(modelId), bytes)
        store.markVerified(modelId, digest)
        store.storeChecksums(modelId, "model.archive\t$digest")
        for (name in names) Fixtures.writeBytes(File(store.extractedDirectory(modelId), name), name.toByteArray())
        if (damage != null) damage(File(store.extractedDirectory(modelId), damaged!!))
        return Rig(store, loader, sink, asked)
    }

    @Test
    fun `a files directory missing one of the four names is not installed and the archive is kept`() {
        var refusals = 0
        for (name in names) {
            val damages = listOf<Pair<String, (File) -> Unit>>(
                "missing" to { file: File -> assertTrue("fixture: removing $name", file.delete()) },
                "empty" to { file: File -> Fixtures.writeBytes(file, ByteArray(0)); Unit },
            )
            for ((label, damage) in damages) {
                val r = rig(damage, name)
                val what = "$name $label"
                assertTrue("$what: fixture: the archive alone looks installed", r.store.isInstalled(modelId))
                assertTrue("$what: fixture: the files directory has content", r.store.extractedDirectory(modelId).list()!!.isNotEmpty())

                val result = r.loader.load(modelId)
                assertTrue("$what: expected a refusal but got $result", result is ModelLoader.LoadResult.Refused)
                val refused = result as ModelLoader.LoadResult.Refused
                assertEquals("$what: refusal", ModelLoader.Refusal.NOT_INSTALLED, refused.refusal)
                assertEquals("$what: sentence", ModelMessages.MODEL_NOT_INSTALLED, refused.detail)
                assertFalse("$what: nothing was deleted, so nothing is reported", refused.leftOnDisk)
                assertEquals("$what: the engine must not be asked", 0, r.asked.size)
                assertEquals(
                    "$what: sink",
                    listOf("NOT_INSTALLED: model 'tiny' is verified but has no unpacked files"),
                    r.sink,
                )

                assertArrayEquals("$what: the archive must stay unchanged", bytes, r.store.archiveFile(modelId).readBytes())
                assertEquals("$what: verified marker", digest, r.store.lastVerifiedDigest(modelId))
                assertEquals("$what: checksums", "model.archive\t$digest", r.store.storedChecksums(modelId))
                for (other in names.filter { it != name }) {
                    assertTrue("$what: $other must stay", File(r.store.extractedDirectory(modelId), other).isFile)
                }
                refusals++
            }
        }
        assertEquals("every name was damaged both ways", 8, refusals)
    }

    @Test
    fun `the same fixture with all four names in place loads so the refusal comes from the damaged name`() {
        val r = rig()
        val result = r.loader.load(modelId)
        assertTrue("expected a loaded model but got $result", result is ModelLoader.LoadResult.Ready)
        assertEquals(1, r.asked.size)
        assertEquals(r.store.extractedDirectory(modelId), r.asked.single().directory)
        assertTrue(r.sink.isEmpty())
    }
}
