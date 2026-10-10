package dev.breaker.dictation.wiring

import dev.breaker.dictation.gates.AppSourceFiles
import dev.breaker.dictation.stt.ondevice.LocalModelStore
import dev.breaker.dictation.stt.ondevice.ModelFetcher
import dev.breaker.dictation.stt.ondevice.ModelInstaller
import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Protects the pieces the model download stands on: the three model sentences, the check that the
 * selected model is installed and unpacked, the mapping of the installer's answer, and the rule that
 * the file holds no address and no checksum of its own (they come from the registry entry).
 */
internal class ModelSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val root: File
        get() = File(tmp.root, "models")

    private fun archive(id: String) = File(root, "$id/${LocalModelStore.ARCHIVE_NAME}")

    private fun unpackedFile(id: String, name: String) = File(root, "$id/${LocalModelStore.EXTRACTED_DIR}/$name")

    /** The four file names the module unpacks for a model that has a profile; any other id has none. */
    private fun profileFiles(id: String): List<String> {
        val joiner = when (id) {
            "small" -> "joiner-epoch-99-avg-1.int8.onnx"
            "tiny" -> "joiner-epoch-99-avg-1.onnx"
            else -> return listOf("tokens.txt")
        }
        return listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", joiner, "tokens.txt")
    }

    /** Writes every file of the model's unpack profile, each non-empty. */
    private fun unpack(id: String) {
        for (name in profileFiles(id)) write(unpackedFile(id, name))
    }

    private fun write(file: File) {
        file.parentFile.mkdirs()
        file.writeText("x")
    }

    @Test
    fun `the no-model sentence is the fixed one`() {
        assertEquals("app: the no-model sentence", "Download the speech model in Breaker first.", ModelSentences.NO_MODEL)
        assertEquals("app: the no-model sentence length", 43, ModelSentences.NO_MODEL.length)
    }

    @Test
    fun `every model sentence is short plain ASCII without the words the notification texts avoid`() {
        val forbidden = Regex("""\b(?:accessibility|overlay|foreground|service|permission|debug)\w*|\b(?:api|sdk)s?\b""", RegexOption.IGNORE_CASE)
        val all = listOf(ModelSentences.NO_MODEL, ModelSentences.DOWNLOADING, ModelSentences.DOWNLOAD_FAILED)
        for (sentence in all) {
            assertTrue("app: '$sentence' is over 60 characters", sentence.length <= 60)
            assertTrue("app: '$sentence' is not ASCII", sentence.all { it.code in 32..126 })
            assertFalse("app: '$sentence' holds a word the notification texts avoid", forbidden.containsMatchIn(sentence))
            assertTrue("app: '$sentence' must end like a sentence", sentence.endsWith("."))
        }
        assertEquals("app: the three sentences are different", 3, all.toSet().size)
    }

    @Test
    fun `the failed and the ready sentences have their agreed words`() {
        assertEquals(
            "app: the download-failed sentence changed",
            "Could not download the speech model. Open Breaker to retry.",
            ModelSentences.DOWNLOAD_FAILED,
        )
        val ready = AppSourceFiles.strip(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/service/ModelNotifications.kt")).literals
        assertTrue(
            "app: the model-ready notice must say 'The speech model is ready. Tap the tile to start dictating.'",
            "The speech model is ready. Tap the tile to start dictating." in ready,
        )
        assertFalse("app: the old model-ready text is still in the notice file", "The speech model is ready." in ready)
    }

    @Test
    fun `the model is ready only when installed and unpacked`() {
        val ready = StoreModelReady(LocalModelStore(root)) { "small" }
        assertFalse("app: nothing on disk is not ready", ready.isReady())
        write(archive("small"))
        assertFalse("app: an archive with no unpacked files is not ready", ready.isReady())
        write(unpackedFile("small", "tokens.txt"))
        assertFalse("app: an archive with one of four unpacked files is not ready", ready.isReady())
        unpack("small")
        assertTrue("app: archive and all unpacked files are ready", ready.isReady())
    }

    @Test
    fun `unpacked files without the archive are not ready`() {
        unpack("small")
        assertFalse("app: unpacked files alone are not an installed model", StoreModelReady(LocalModelStore(root)) { "small" }.isReady())
    }

    @Test
    fun `a model with only half of its files is not ready while another model is ready`() {
        write(archive("small"))
        unpack("small")
        write(archive("tiny"))
        assertFalse("app: tiny with only the archive is not ready", StoreModelReady(LocalModelStore(root)) { "tiny" }.isReady())
        for (name in profileFiles("tiny").dropLast(1)) write(unpackedFile("tiny", name))
        assertFalse("app: tiny with three of four files is not ready", StoreModelReady(LocalModelStore(root)) { "tiny" }.isReady())
        unpackedFile("tiny", "tokens.txt").apply { parentFile.mkdirs() }.writeText("")
        assertFalse("app: tiny with an empty fourth file is not ready", StoreModelReady(LocalModelStore(root)) { "tiny" }.isReady())
        assertTrue("app: small is ready", StoreModelReady(LocalModelStore(root)) { "small" }.isReady())
        write(archive("base"))
        unpack("base")
        assertFalse("app: base has no unpack profile, so it is not ready", StoreModelReady(LocalModelStore(root)) { "base" }.isReady())
    }

    @Test
    fun `the selected id is read each time it is asked`() {
        write(archive("small"))
        unpack("small")
        var selected = "small"
        val ready = StoreModelReady(LocalModelStore(root)) { selected }
        assertTrue("app: small is ready", ready.isReady())
        selected = "tiny"
        assertFalse("app: tiny is not on disk, so after the setting changed it is not ready", ready.isReady())
        write(archive("tiny"))
        unpack("tiny")
        assertTrue("app: tiny is ready once it is on disk", ready.isReady())
    }

    @Test
    fun `an unsafe id and a setting that cannot be read are not ready and do not throw`() {
        assertFalse("app: an unsafe id", StoreModelReady(LocalModelStore(root)) { "../small" }.isReady())
        assertFalse("app: an empty id", StoreModelReady(LocalModelStore(root)) { "" }.isReady())
        assertFalse(
            "app: a setting that throws",
            StoreModelReady(LocalModelStore(root)) { throw IllegalStateException("settings unreadable") }.isReady(),
        )
    }

    @Test
    fun `the installer's answer is mapped and the entry is handed on as it is`() {
        val entry = modelEntryFor("probe")
        val handed: MutableList<ModelEntry> = ArrayList()
        val installed = InstallerPort { e ->
            handed.add(e)
            ModelInstaller.InstallResult.Installed(e.id, "digest")
        }
        assertSame("app: Installed maps to Installed", InstallOutcome.Installed, installed.install(entry))
        val refused = InstallerPort { e ->
            handed.add(e)
            ModelInstaller.InstallResult.Refused(ModelInstaller.Refusal.FETCH_FAILED, "The model could not be downloaded.", leftOnDisk = true)
        }
        assertEquals(
            "app: Refused maps to Refused with the installer's sentence",
            InstallOutcome.Refused("The model could not be downloaded."),
            refused.install(entry),
        )
        assertEquals("app: both installers got the very same entry", listOf(entry, entry), handed)
        assertSame("app: the first got the same object", entry, handed[0])
        assertSame("app: the second got the same object", entry, handed[1])
    }

    @Test
    fun `the real install port runs the module's installer over the given store and fetcher`() {
        val asked: MutableList<String> = ArrayList()
        val fetcher = object : ModelFetcher {
            override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
                asked.add("model ${entry.id} in ${stagingDir.path}")
                return ModelFetcher.Result.Failed("offline")
            }

            override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
                asked.add("checksums in ${stagingDir.path}")
                return ModelFetcher.Result.Failed("offline")
            }
        }
        val store = LocalModelStore(root)
        val outcome = modelInstallPortFor(store, fetcher).install(ModelRegistry.SMALL)
        assertTrue("app: a fetch that fails must come back as a refusal, got $outcome", outcome is InstallOutcome.Refused)
        assertEquals(
            "app: the installer must have asked the given fetcher for the checksum list in the given store's staging folder",
            listOf("checksums in ${store.stagingDirectory().path}"),
            asked,
        )
        assertTrue("app: the installer prepares the staging folder of the given store", store.stagingDirectory().isDirectory)
    }

    @Test
    fun `the download file holds no address and no checksum of its own`() {
        val stripped = AppSourceFiles.strip(AppSourceFiles.mainFile("kotlin/dev/breaker/dictation/wiring/ModelSupport.kt"))
        assertTrue("app: the file was read as empty", stripped.code.isNotBlank())
        val hex = Regex("""[0-9a-fA-F]{64}""")
        for (literal in stripped.literals) {
            assertFalse("app: a string literal holds an address: '$literal'", literal.contains("http", ignoreCase = true))
            assertFalse("app: a string literal holds a checksum: '$literal'", hex.containsMatchIn(literal))
        }
        assertFalse("app: the file reads the entry's address from nowhere but the entry", Regex("""\.url\b|\.sha256\b""").containsMatchIn(stripped.code))
    }
}
