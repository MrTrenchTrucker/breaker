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
import java.io.FileOutputStream
import java.io.IOException

/** A fetcher that serves one archive and a checksum list, counts its calls, and can wrap the staged file. */
internal class ArchiveFetcher(
    private val archive: ByteArray,
    private val checksumsText: String,
    private val wrap: (File) -> File = { it },
) : ModelFetcher {
    var checksumFetches = 0
    var modelFetches = 0

    override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
        modelFetches++
        val file = File(stagingDir, "model.archive")
        file.writeBytes(archive)
        return ModelFetcher.Result.Fetched(wrap(file))
    }

    override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
        checksumFetches++
        val file = File(stagingDir, "checksums.txt")
        file.writeText(checksumsText)
        return ModelFetcher.Result.Fetched(file)
    }
}

/** Counts the calls on the seams of the real extractor and bends one of them. */
internal class Seams(
    var space: Long = Long.MAX_VALUE,
    var failOutput: Boolean = false,
    var renameWorks: Boolean = true,
) {
    var opens = 0
    var spaceAsks = 0
    var renames = 0

    fun extractor() = ModelExtractor(
        usableSpace = { spaceAsks++; space },
        open = { opens++; it.inputStream() },
        openOutput = { if (failOutput) throw IOException("disk full") else FileOutputStream(it) },
        rename = { from, to -> renames++; renameWorks && from.renameTo(to) },
    )
}

/**
 * The shared fixture of the installer unpack tests: the temporary folder, the tiny
 * archive and its pin, one install of an archive with the real extractor and
 * counting seams, and the result checks. Both installer unpack test classes extend it.
 */
abstract class ModelInstallerUnpackFixture {

    @get:Rule
    val tmp = TemporaryFolder()

    internal val tinyBytes = TarFixtures.tinyArchive()
    internal val tinyPin = Fixtures.independentSha256(tinyBytes)

    internal fun entry(id: String = "tiny", pin: String = tinyPin) =
        ModelEntry(id, ModelFamily.SHERPA_ONNX, "https://example.com/model", pin, 1, "Apache-2.0", false)

    internal fun newStore() = LocalModelStore(tmp.newFolder())

    /** One install of [archive] (its own digest is the pin) on [store]; the sink text lands in [sink]. */
    internal fun run(
        archive: ByteArray,
        store: LocalModelStore = newStore(),
        seams: Seams = Seams(),
        sink: MutableList<String> = ArrayList(),
        wrap: (File) -> File = { it },
        id: String = "tiny",
    ): ModelInstaller.InstallResult {
        val pin = Fixtures.independentSha256(archive)
        val fetcher = ArchiveFetcher(archive, "model.archive\t$pin", wrap)
        return ModelInstaller(store, fetcher, ModelDebugSink { sink.add(it) }, seams.extractor()).install(entry(id, pin))
    }

    internal fun installedOf(result: ModelInstaller.InstallResult): ModelInstaller.InstallResult.Installed {
        assertTrue("expected an install but got $result", result is ModelInstaller.InstallResult.Installed)
        return result as ModelInstaller.InstallResult.Installed
    }

    internal fun refusedOf(result: ModelInstaller.InstallResult): ModelInstaller.InstallResult.Refused {
        assertTrue("expected a refusal but got $result", result is ModelInstaller.InstallResult.Refused)
        return result as ModelInstaller.InstallResult.Refused
    }

    internal fun assertFilesHold(store: LocalModelStore, names: List<String>, contentOf: (String) -> ByteArray) {
        assertEquals(names.sorted(), store.extractedDirectory("tiny").list()!!.sorted())
        for (name in names) {
            assertArrayEquals("bytes of $name", contentOf(name), File(store.extractedDirectory("tiny"), name).readBytes())
        }
    }
}

/**
 * Installs over an earlier install and over a half-finished one: files from the
 * earlier install never outlive their archive, a removal that fails refuses the
 * install and keeps the earlier one, and a model left without files is healed
 * by installing again.
 */
class ModelInstallerUnpackReinstallTest : ModelInstallerUnpackFixture() {

    private object IdleRecognizer : SherpaRecognizer {
        override fun decode(pcm: FloatArray, sampleRateHz: Int): SherpaTranscript = SherpaTranscript("", emptyList(), "en")

        override fun release() {}
    }

    @Test
    fun `a first install never asks the store to remove anything`() {
        val asked = ArrayList<File>()
        val store = LocalModelStore(tmp.newFolder(), remove = { asked.add(it); it.deleteRecursively() })
        installedOf(run(tinyBytes, store))
        assertTrue("remove was asked for $asked", asked.isEmpty())
    }

    @Test
    fun `a re-install replaces the unpacked files including names the new archive no longer has`() {
        val store = newStore()
        installedOf(run(tinyBytes, store))
        File(store.extractedDirectory("tiny"), "old-name.onnx").writeBytes(byteArrayOf(1, 2, 3))
        val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }
        assertFalse("fixture: the second archive differs", second.contentEquals(tinyBytes))

        var filesAtMove: Boolean? = null
        val observing: (File) -> File = { real ->
            object : File(real.path) {
                override fun renameTo(dest: File): Boolean {
                    filesAtMove = store.isExtracted("tiny")
                    return super.renameTo(dest)
                }
            }
        }
        installedOf(run(second, store, wrap = observing))
        assertEquals("the old files are removed before the new archive is moved in", false, filesAtMove)
        assertFilesHold(store, TarFixtures.TINY_FILES) { TarFixtures.contentOf("v2-$it", 48) }
        assertArrayEquals(second, store.archiveFile("tiny").readBytes())
    }

    @Test
    fun `a re-install that cannot remove the old unpacked files is refused with MOVE_FAILED and the staged file is discarded`() {
        val asked = ArrayList<File>()
        val store = LocalModelStore(tmp.newFolder(), remove = { asked.add(it); false })
        installedOf(run(tinyBytes, store))
        assertTrue("fixture: a first install asks for nothing", asked.isEmpty())
        val archiveBefore = store.archiveFile("tiny").readBytes()
        val second = TarFixtures.tinyArchive { name -> TarFixtures.contentOf("v2-$name", 48) }

        val sink = ArrayList<String>()
        val refused = refusedOf(run(second, store, sink = sink))
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
        assertEquals(ModelMessages.SAVE_FAILED, refused.detail)
        assertFalse("the staged file was discarded", refused.leftOnDisk)
        assertFalse(File(store.stagingDirectory(), "model.archive").exists())
        assertEquals(listOf(store.extractedDirectory("tiny")), asked)
        assertTrue(sink.single(), sink.single().contains("cannot remove the earlier unpacked files"))
        assertArrayEquals("the earlier archive is untouched", archiveBefore, store.archiveFile("tiny").readBytes())
        assertFilesHold(store, TarFixtures.TINY_FILES) { TarFixtures.contentOf(it, 48) }

        // The staged file that cannot be discarded either is reported.
        val stuck = LocalModelStore(tmp.newFolder(), remove = { false }, discard = { false })
        installedOf(run(tinyBytes, stuck))
        val both = refusedOf(run(second, stuck))
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, both.refusal)
        assertTrue(both.leftOnDisk)
        assertEquals(ModelMessages.SAVE_FAILED + ModelMessages.COULD_NOT_DELETE, both.detail)
    }

    @Test
    fun `a model installed without files is healed by installing again`() {
        val store = newStore()
        Fixtures.writeBytes(store.archiveFile("tiny"), tinyBytes)
        store.markVerified("tiny", tinyPin)
        store.storeChecksums("tiny", "model.archive\t$tinyPin")
        val created = ArrayList<SherpaModel>()
        val factory = SherpaRecognizerFactory { model -> created.add(model); IdleRecognizer }
        val loader = ModelLoader(store, factory, { if (it == "tiny") entry() else null })

        assertTrue("fixture: the half state looks installed by its archive", store.isInstalled("tiny"))
        val before = loader.load("tiny")
        assertTrue("before: $before", before is ModelLoader.LoadResult.Refused)
        assertEquals(ModelLoader.Refusal.NOT_INSTALLED, (before as ModelLoader.LoadResult.Refused).refusal)
        assertTrue(created.isEmpty())

        installedOf(run(tinyBytes, store))
        val after = loader.load("tiny")
        assertTrue("after: $after", after is ModelLoader.LoadResult.Ready)
        assertEquals(store.extractedDirectory("tiny"), (after as ModelLoader.LoadResult.Ready).model.directory)
        assertEquals(1, created.size)
    }

    @Test
    fun `a directory in the place of the archive is refused before the unpacked files are touched`() {
        val asked = ArrayList<File>()
        val store = LocalModelStore(tmp.newFolder(), remove = { asked.add(it); it.deleteRecursively() })
        installedOf(run(tinyBytes, store))
        assertTrue("fixture: a first install asks for nothing", asked.isEmpty())
        assertTrue(store.archiveFile("tiny").delete())
        assertTrue(store.archiveFile("tiny").mkdir())
        assertTrue("fixture: unpacked files and a directory at the archive path", store.isExtracted("tiny"))

        val sink = ArrayList<String>()
        val refused = refusedOf(run(tinyBytes, store, sink = sink))
        assertEquals(ModelInstaller.Refusal.MOVE_FAILED, refused.refusal)
        assertTrue(sink.single(), sink.single().contains("destination is not a file"))
        assertTrue("the unpacked files must not be removed first: $asked", asked.isEmpty())
        assertFilesHold(store, TarFixtures.TINY_FILES) { TarFixtures.contentOf(it, 48) }
        assertTrue("the directory at the archive path stays", store.archiveFile("tiny").isDirectory)
    }
}
