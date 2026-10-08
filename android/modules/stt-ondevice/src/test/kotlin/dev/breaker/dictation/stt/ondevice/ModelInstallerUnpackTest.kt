package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the installer does with the unpack step: it runs only after the second
 * check and the metadata writes, it decides whether the model counts as
 * installed, a refusal deletes the model and picks the sentence by who was at
 * fault, a model with no profile never downloads, and files from an earlier
 * install never outlive their archive. The real extractor runs on small
 * archives built in memory; its seams count calls and bend one failure.
 */
class ModelInstallerUnpackTest : ModelInstallerUnpackFixture() {

    /** Runs an install that must be refused for the unpack and checks everything a refusal promises. */
    private fun assertUnpackRefusal(
        label: String,
        archive: ByteArray,
        seams: Seams,
        refusal: ModelInstaller.Refusal,
        sentence: String,
        reason: ExtractionReason,
    ) {
        val store = newStore()
        val sink = ArrayList<String>()
        val refused = refusedOf(run(archive, store, seams, sink))
        assertEquals("$label: refusal", refusal, refused.refusal)
        assertEquals("$label: sentence", sentence, refused.detail)
        assertFalse("$label: the delete worked, so nothing is reported", refused.leftOnDisk)
        assertFalse("$label: the model directory must be gone", store.directoryFor("tiny").exists())
        assertFalse("$label: the work directory must be gone", store.extractionWorkDirectory("tiny").exists())
        assertEquals("$label: one sink call", 1, sink.size)
        val expectedStart = "install refused (${refusal.name}): ${reason.name}: "
        assertTrue("$label: the sink must name the refusal and the reason: $sink", sink.single().startsWith(expectedStart))
    }

    @Test
    fun `install unpacks after the second check and reports Installed with the files in place`() {
        val store = newStore()
        var renames = 0
        val seen = ArrayList<String>()
        val observing = ModelExtractor(
            usableSpace = { Long.MAX_VALUE },
            rename = { from, to ->
                seen.add("digest=${store.lastVerifiedDigest("tiny")}")
                seen.add("checksums=${store.storedChecksums("tiny") != null}")
                seen.add("extracted=${store.isExtracted("tiny")}")
                renames++
                from.renameTo(to)
            },
        )
        val fetcher = ArchiveFetcher(tinyBytes, "model.archive\t$tinyPin")
        val result = ModelInstaller(store, fetcher, extractor = observing).install(entry())
        assertEquals(ModelInstaller.InstallResult.Installed("tiny", tinyPin), result)
        assertFilesHold(store, TarFixtures.TINY_FILES) { TarFixtures.contentOf(it, 48) }
        assertTrue(store.isExtracted("tiny"))
        assertTrue("the archive stays for the load check", store.isInstalled("tiny"))
        assertFalse("the work directory is gone", store.extractionWorkDirectory("tiny").exists())
        assertEquals("one move into place", 1, renames)
        assertEquals(
            "at the move the metadata is written and the files are not yet visible",
            listOf("digest=$tinyPin", "checksums=true", "extracted=false"),
            seen,
        )
    }

    @Test
    fun `an installer built with no extractor unpacks a real archive`() {
        // The default extractor asks the real free space of the temporary folder (80 MiB for this model).
        val store = newStore()
        val fetcher = ArchiveFetcher(tinyBytes, "model.archive\t$tinyPin")
        val result = ModelInstaller(store, fetcher).install(entry())
        assertEquals(ModelInstaller.InstallResult.Installed("tiny", tinyPin), result)
        assertFilesHold(store, TarFixtures.TINY_FILES) { TarFixtures.contentOf(it, 48) }
    }

    @Test
    fun `the small model installs with its own file names`() {
        val store = newStore()
        val result = run(TarFixtures.smallArchive(), store, id = "small")
        assertEquals("small", installedOf(result).modelId)
        assertEquals(TarFixtures.SMALL_FILES.sorted(), store.extractedDirectory("small").list()!!.sorted())
        assertTrue(store.isExtracted("small"))
    }

    @Test
    fun `the second check refusing means no extraction was attempted`() {
        val store = newStore()
        val seams = Seams()
        val swapsOnMove: (File) -> File = { real ->
            object : File(real.path) {
                override fun renameTo(dest: File): Boolean {
                    dest.writeBytes("different model bytes".toByteArray())
                    return true
                }
            }
        }
        val refused = refusedOf(run(tinyBytes, store, seams, wrap = swapsOnMove))
        assertEquals(ModelInstaller.Refusal.DIGEST_REFUSED, refused.refusal)
        assertEquals("the archive was never opened", 0, seams.opens)
        assertEquals("free space was never asked", 0, seams.spaceAsks)
        assertEquals("nothing was moved into place", 0, seams.renames)

        // Control: the same fixture without the swap reaches the extractor.
        val control = Seams()
        installedOf(run(tinyBytes, newStore(), control))
        assertEquals(1, control.opens)
        assertEquals(1, control.spaceAsks)
        assertEquals(1, control.renames)
    }

    @Test
    fun `an archive refused for content deletes the model and gives the unpack sentence`() {
        val link = TarFixtures.bz2(TarFixtures.archiveOf(TarFixtures.dir("top/"), TarFixtures.symlink("top/tokens.txt", "../../x")))
        val noFiles = TarFixtures.bz2(TarFixtures.archiveOf(TarFixtures.dir("top/"), TarFixtures.file("top/README.md", ByteArray(8) { 1 })))
        val cases = listOf(
            Triple("not a bzip2 stream", TarFixtures.notBzip2(), ExtractionReason.STREAM_ERROR),
            Triple("a symbolic link", link, ExtractionReason.SYMLINK),
            Triple("no file the engine needs", noFiles, ExtractionReason.MISSING_FILE),
        )
        for ((label, archive, reason) in cases) {
            assertUnpackRefusal(
                label, archive, Seams(), ModelInstaller.Refusal.EXTRACT_REFUSED,
                "The model file could not be unpacked safely. Download it again.", reason,
            )
        }
    }

    @Test
    fun `an unpack that fails on the phone gives the unpack-failed sentence and deletes the model`() {
        val sentence = "The model could not be unpacked on the phone."
        assertUnpackRefusal(
            "the move into place fails", tinyBytes, Seams(renameWorks = false),
            ModelInstaller.Refusal.EXTRACT_FAILED, sentence, ExtractionReason.COMMIT_FAILED,
        )
        assertUnpackRefusal(
            "a file cannot be written", tinyBytes, Seams(failOutput = true),
            ModelInstaller.Refusal.EXTRACT_FAILED, sentence, ExtractionReason.WRITE_ERROR,
        )
    }

    @Test
    fun `too little space gives the space sentence and deletes the model`() {
        assertUnpackRefusal(
            "no free space", tinyBytes, Seams(space = 0L),
            ModelInstaller.Refusal.NO_SPACE, "There is not enough free space to unpack the model.", ExtractionReason.NO_SPACE,
        )
    }

    @Test
    fun `a refusal whose delete fails keeps the refusal and says the file could not be deleted`() {
        val cases = listOf(
            Triple(TarFixtures.notBzip2(), Seams(), ModelInstaller.Refusal.EXTRACT_REFUSED to ModelMessages.UNPACK_REFUSED),
            Triple(tinyBytes, Seams(renameWorks = false), ModelInstaller.Refusal.EXTRACT_FAILED to ModelMessages.UNPACK_FAILED),
            Triple(tinyBytes, Seams(space = 0L), ModelInstaller.Refusal.NO_SPACE to ModelMessages.UNPACK_NO_SPACE),
        )
        var checked = 0
        for ((archive, seams, expected) in cases) {
            val store = LocalModelStore(tmp.newFolder(), remove = { false })
            val sink = ArrayList<String>()
            val refused = refusedOf(run(archive, store, seams, sink))
            assertEquals(expected.first, refused.refusal)
            assertTrue("${expected.first}: the model is still on disk", refused.leftOnDisk)
            assertEquals(expected.second + " The file could not be deleted.", refused.detail)
            assertTrue("${expected.first}: the model directory stays", store.directoryFor("tiny").isDirectory)
            assertTrue("${expected.first}: the sink notes the failed delete: $sink", sink.single().endsWith("; delete failed"))
            checked++
        }
        assertEquals(3, checked)
        assertEquals(
            "The model file could not be unpacked safely. Download it again. The file could not be deleted.",
            ModelMessages.UNPACK_REFUSED + ModelMessages.COULD_NOT_DELETE,
        )
    }

    @Test
    fun `a refusal asks the store to delete the model directory exactly once`() {
        val asked = ArrayList<File>()
        val store = LocalModelStore(tmp.newFolder(), remove = { asked.add(it); it.deleteRecursively() })
        refusedOf(run(TarFixtures.notBzip2(), store))
        assertEquals(listOf(store.directoryFor("tiny")), asked)
    }

    @Test
    fun `a model with no profile is refused before any fetch`() {
        val whisper = ModelEntry("base", ModelFamily.WHISPER, "https://example.com/model", tinyPin, 1, "MIT", false)
        val store = newStore()
        val seams = Seams()
        val sink = ArrayList<String>()
        val fetcher = ArchiveFetcher(tinyBytes, "model.archive\t$tinyPin")
        val refused = refusedOf(ModelInstaller(store, fetcher, ModelDebugSink { sink.add(it) }, seams.extractor()).install(whisper))
        assertEquals(ModelInstaller.Refusal.UNSUPPORTED_MODEL, refused.refusal)
        assertEquals(ModelMessages.MODEL_WRONG_FAMILY, refused.detail)
        assertFalse(refused.leftOnDisk)
        assertEquals("no checksum fetch may start", 0, fetcher.checksumFetches)
        assertEquals("no model fetch may start", 0, fetcher.modelFetches)
        assertEquals(0, seams.opens)
        assertFalse(store.directoryFor("base").exists())
        assertEquals(listOf("install refused (UNSUPPORTED_MODEL): no unpack profile for 'base'"), sink)

        // Control: a model with a profile, on the same fetcher, does fetch.
        installedOf(ModelInstaller(newStore(), fetcher, extractor = Seams().extractor()).install(entry()))
        assertEquals(1, fetcher.checksumFetches)
        assertEquals(1, fetcher.modelFetches)
    }

    @Test
    fun `every registry model without a profile is refused before any fetch and the others are fetched`() {
        var refusedBefore = 0
        var fetched = 0
        for (model in ModelRegistry.ALL) {
            val fetcher = ArchiveFetcher(tinyBytes, "model.archive\t$tinyPin")
            val result = refusedOf(ModelInstaller(newStore(), fetcher, extractor = Seams().extractor()).install(model))
            if (model.id in setOf("small", "tiny")) {
                assertEquals("${model.id}: a model with a profile is fetched", 1, fetcher.modelFetches)
                fetched++
            } else {
                assertEquals("${model.id}: refusal", ModelInstaller.Refusal.UNSUPPORTED_MODEL, result.refusal)
                assertEquals("${model.id}: no fetch", 0, fetcher.checksumFetches + fetcher.modelFetches)
                refusedBefore++
            }
        }
        assertTrue("the registry must hold a model with no profile", refusedBefore > 0)
        assertTrue("the registry must hold a model with a profile", fetched > 0)
    }

    @Test
    fun `no unpack refusal detail holds a path separator or an exception name`() {
        val sinkOfWrite = ArrayList<String>()
        val results = listOf(
            refusedOf(run(TarFixtures.notBzip2())),
            refusedOf(run(tinyBytes, seams = Seams(renameWorks = false))),
            refusedOf(run(tinyBytes, seams = Seams(space = 0L))),
            refusedOf(run(tinyBytes, seams = Seams(failOutput = true), sink = sinkOfWrite)),
        )
        val allowed = setOf(ModelMessages.UNPACK_REFUSED, ModelMessages.UNPACK_FAILED, ModelMessages.UNPACK_NO_SPACE)
        // Control: the technical text does name an exception, so a leak would show.
        assertTrue(sinkOfWrite.toString(), sinkOfWrite.single().contains("Exception"))
        var scanned = 0
        for (refused in results) {
            assertTrue("not a fixed unpack sentence: ${refused.detail}", refused.detail in allowed)
            for (forbidden in listOf("/", "\\", "Exception", "Error")) {
                assertFalse("${refused.refusal} detail holds '$forbidden'", refused.detail.contains(forbidden))
            }
            scanned++
        }
        assertEquals(4, scanned)
    }
}
