package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelFiles
import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelLimits
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The installer reads support and the free-space figure from the entry it is given, when that
 * entry carries its own files and limits, not from the registry entry of the same id.
 * The fetcher records every request, so "no request" is asserted on the recorded list.
 */
class ModelInstallerEntryProfileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    private val mib = 1_048_576L
    private val archiveBytes = 1_000L
    private val ownWritten = 10L * mib

    private class RecordingFetcher : ModelFetcher {
        val requestedIds = ArrayList<String>()

        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            requestedIds.add(entry.id)
            return ModelFetcher.Result.Failed("scripted")
        }
    }

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.newFolder("models"))
    }

    private fun entry(id: String, modelType: String = "") = ModelEntry(
        id,
        ModelFamily.SHERPA_ONNX,
        "https://example.com/model",
        "0".repeat(64),
        1,
        "Apache-2.0",
        false,
        kind = ModelKind.STREAMING,
        sizeBytes = archiveBytes,
        modelType = modelType,
        files = ModelFiles("e.onnx", "d.onnx", "j.onnx", "tokens.txt"),
        limits = ModelLimits(
            maxEntryBytes = 8L * mib,
            maxWrittenBytes = ownWritten,
            maxStreamBytes = 16L * mib,
            maxEntries = 8,
            maxDepth = 2,
        ),
        language = "en",
    )

    private fun install(fetcher: RecordingFetcher, entry: ModelEntry, freeBytes: Long) =
        ModelInstaller(store, fetcher, usableSpace = { freeBytes }).install(entry)
            as? ModelInstaller.InstallResult.Refused ?: throw AssertionError("expected a refusal")

    @Test
    fun `an entry with its own limits and an id the registry lacks is sized from its own limits`() {
        val own = entry("custom-model")
        val need = archiveBytes + ownWritten
        val short = RecordingFetcher()
        assertEquals(ModelInstaller.Refusal.NO_SPACE, install(short, own, need - 1L).refusal)
        assertEquals("no request was made", emptyList<String>(), short.requestedIds)
        val enough = RecordingFetcher()
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, install(enough, own, need).refusal)
        assertEquals(listOf("custom-model"), enough.requestedIds)
    }

    @Test
    fun `an entry reusing a registry id is sized from its own limits, not the registry's`() {
        // The registry's small profile allows 160 MiB; this entry allows 10 MiB.
        val own = entry("small")
        val fetcher = RecordingFetcher()
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, install(fetcher, own, archiveBytes + ownWritten).refusal)
        assertEquals(listOf("small"), fetcher.requestedIds)
    }

    @Test
    fun `an entry whose type is unsupported is refused before any request even under a supported id`() {
        val fetcher = RecordingFetcher()
        val refused = install(fetcher, entry("small", modelType = "whisper"), Long.MAX_VALUE)
        assertEquals(ModelInstaller.Refusal.UNSUPPORTED_MODEL, refused.refusal)
        assertEquals(emptyList<String>(), fetcher.requestedIds)
    }
}
