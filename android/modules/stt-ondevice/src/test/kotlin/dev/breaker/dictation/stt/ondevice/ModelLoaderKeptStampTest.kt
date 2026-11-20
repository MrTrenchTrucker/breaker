package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The handoff between the two repaired stamp paths. The installer keeps a model whose stamp it
 * cannot record (Installed with stampFailed), and the loader heals that model on the next start: it
 * loads once, verifies the archive once, and writes the stamp from the unpacked files.
 *
 * The rig is the one ModelLoaderStampMissingTest uses (StampRig's counted seams, StampCalls and
 * StampFactory); that file is not edited here.
 */
class ModelLoaderKeptStampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Installs a tiny archive with a digest that cannot measure one file, so no stamp is written. */
    private fun installKept(store: LocalModelStore, sink: MutableList<String>): ModelEntry {
        val archive = TarFixtures.tinyArchive()
        val entry = ModelEntry(
            "tiny",
            ModelFamily.SHERPA_ONNX,
            "https://example.com/model",
            Fixtures.independentSha256(archive),
            1,
            "Apache-2.0",
            false,
            sizeBytes = archive.size.toLong(),
        )
        val unmeasurable: (File) -> String? = { file ->
            if (file.name == TarFixtures.TINY_FILES[2]) null else Fixtures.independentSha256(file.readBytes())
        }
        val installed = ModelInstaller(
            store,
            ArchiveFetcher(archive),
            ModelDebugSink { sink.add(it) },
            Seams().extractor(),
            unmeasurable,
            usableSpace = { Long.MAX_VALUE },
        ).install(entry)
        assertTrue(
            "expected an install but got $installed",
            installed is ModelInstaller.InstallResult.Installed,
        )
        assertTrue(
            "the install kept the model but could not stamp it",
            (installed as ModelInstaller.InstallResult.Installed).stampFailed,
        )
        return entry
    }

    private fun loaderFor(
        store: LocalModelStore,
        entry: ModelEntry,
        calls: StampCalls,
        sink: MutableList<String>,
    ): ModelLoader = ModelLoader(
        store = store,
        factory = StampFactory(calls),
        lookup = { requested -> if (requested == entry.id) entry else null },
        debug = ModelDebugSink { sink.add(it) },
        verify = { file, pin, bytes ->
            calls.verify++
            ModelIntegrity.verify(file, pin, bytes)
        },
        digestOf = { file ->
            calls.digest++
            ModelIntegrity.sha256(file)
        },
    )

    @Test
    fun `a kept model with no stamp loads once and gets its stamp written`() {
        val store = LocalModelStore(tmp.newFolder())
        val sink = ArrayList<String>()
        val entry = installKept(store, sink)
        assertNull("fixture: the install wrote no stamp", store.readStamp(entry.id))
        assertTrue("fixture: the install left the model unpacked", store.isExtracted(entry.id))

        val calls = StampCalls()
        stampReadyOf(loaderFor(store, entry, calls, sink).load(entry.id))

        assertEquals("the archive is verified exactly once", 1, calls.verify)
        assertEquals("the engine is created once", 1, calls.create)
        val stamp = checkNotNull(store.readStamp(entry.id)) { "the load must write the missing stamp" }
        assertTrue("the stamp is for the pin and the four names", stamp.isFor(entry.sha256, TarFixtures.TINY_FILES))
        assertEquals(
            "the recorded digests are the real digests of the files",
            TarFixtures.TINY_FILES.map { Fixtures.independentSha256(File(store.extractedDirectory(entry.id), it).readBytes()) },
            stamp.files.map { it.sha256 },
        )
        assertEquals(
            "the install reported the stamp failure once",
            1,
            sink.count { it.contains("could not record the unpacked-file stamp") },
        )
    }

    @Test
    fun `the healed model is not verified again on the next load`() {
        val store = LocalModelStore(tmp.newFolder())
        val sink = ArrayList<String>()
        val entry = installKept(store, sink)
        val calls = StampCalls()
        val loader = loaderFor(store, entry, calls, sink)

        stampReadyOf(loader.load(entry.id))
        assertEquals("the first load verifies the archive once", 1, calls.verify)
        assertEquals("the first load hashes the four files for the stamp", 4, calls.digest)

        stampReadyOf(loader.load(entry.id))

        assertEquals("the healed stamp spares the archive on the next load", 1, calls.verify)
        assertEquals("and no unpacked file is hashed again", 4, calls.digest)
        assertEquals("the engine is created once per load", 2, calls.create)
    }
}