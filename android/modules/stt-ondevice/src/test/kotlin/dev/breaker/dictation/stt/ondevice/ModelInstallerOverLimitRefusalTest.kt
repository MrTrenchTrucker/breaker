package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelFiles
import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An archive that permanently exceeds the unpack profile's bounds is not asked for again:
 * the registry pin fixes those bytes, so a fresh download is the same archive and the same
 * refusal. The refusal is EXTRACT_FAILED with the no-retry sentence; the technical reason
 * goes to the debug sink only. A damaged archive keeps the download-again sentence as before.
 */
class ModelInstallerOverLimitRefusalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = ModelFiles("e.onnx", "d.onnx", "j.onnx", "toks.txt")

    /** A profile whose per-entry bound the archive breaks by declaration alone. */
    private val limits = ModelLimits(
        maxEntryBytes = 2_000_000L,
        maxWrittenBytes = 8_000_000L,
        maxStreamBytes = 16_000_000L,
        maxEntries = 16,
        maxDepth = 3,
    )

    /** A real bzip2 tar whose second entry declares 2.5 MB, over the 2 MB bound. */
    private val archive: ByteArray = TarFixtures.zerosArchive(zeroBytes = 2_500_000L, name = "top/extra.bin")
    private val pin: String = Fixtures.independentSha256(archive)

    private fun entry() = ModelEntry(
        "over-limit",
        ModelFamily.SHERPA_ONNX,
        "https://example.com/model",
        pin,
        1,
        "Apache-2.0",
        false,
        kind = ModelKind.STREAMING,
        sizeBytes = archive.size.toLong(),
        modelType = "",
        files = names,
        limits = limits,
        language = "en",
    )

    /** Serves one archive into the staging directory and counts nothing else. */
    private class ArchiveFetcher(private val bytes: ByteArray) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "model.archive")
            file.writeBytes(bytes)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    @Test
    fun `an over-limit archive is refused without asking for another download`() {
        val store = LocalModelStore(tmp.newFolder())
        val sunk = ArrayList<String>()
        val result = ModelInstaller(
            store,
            ArchiveFetcher(archive),
            ModelDebugSink { sunk.add(it) },
            ModelExtractor(usableSpace = { Long.MAX_VALUE }),
            usableSpace = { Long.MAX_VALUE },
        ).install(entry())
        assertTrue("expected a refusal but got $result", result is ModelInstaller.InstallResult.Refused)
        val refused = result as ModelInstaller.InstallResult.Refused
        assertEquals("an over-limit archive is not an unsafe one", ModelInstaller.Refusal.EXTRACT_FAILED, refused.refusal)
        assertEquals("the sentence carries no retry wording", ModelMessages.UNPACK_FAILED, refused.detail)
        assertFalse("the sentence never says 'again'", refused.detail.contains("again"))
        assertFalse("nothing is left installed", store.isInstalled("over-limit"))
        assertTrue("the sink names the reason: $sunk", sunk.single().contains("ENTRY_TOO_LARGE"))
    }

    @Test
    fun `a damaged archive still keeps the download-again sentence`() {
        val broken = TarFixtures.notBzip2()
        val brokenPin = Fixtures.independentSha256(broken)
        val store = LocalModelStore(tmp.newFolder())
        val result = ModelInstaller(
            store,
            ArchiveFetcher(broken),
            extractor = ModelExtractor(usableSpace = { Long.MAX_VALUE }),
            usableSpace = { Long.MAX_VALUE },
        ).install(entry().copy(sha256 = brokenPin, sizeBytes = broken.size.toLong()))
        val refused = result as? ModelInstaller.InstallResult.Refused
        assertEquals(ModelInstaller.Refusal.EXTRACT_REFUSED, refused?.refusal)
        assertEquals(ModelMessages.UNPACK_REFUSED, refused?.detail)
    }
}
