package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The free-space check runs before anything is downloaded, and it is sized from the entry's
 * pinned archive size plus the most the unpack profile lets the unpack write. The fetcher fake
 * records every call, so "no request" is asserted on the recorded list, not inferred.
 */
class ModelInstallerPreDownloadSpaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    private val mib = 1_048_576L

    /** Records every model fetch; answers each with a failure so a run that gets past the check stops there. */
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

    private fun entry(id: String, sizeBytes: Long) = ModelEntry(
        id,
        ModelFamily.SHERPA_ONNX,
        "https://api.github.com/repos/example/example/releases/assets/1",
        "0".repeat(64),
        1,
        "Apache-2.0",
        false,
        sizeBytes = sizeBytes,
    )

    private fun install(fetcher: RecordingFetcher, entry: ModelEntry, freeBytes: Long): ModelInstaller.InstallResult =
        ModelInstaller(store, fetcher, usableSpace = { freeBytes }).install(entry)

    private fun refusal(result: ModelInstaller.InstallResult): ModelInstaller.InstallResult.Refused =
        result as? ModelInstaller.InstallResult.Refused ?: throw AssertionError("expected a refusal but got $result")

    @Test
    fun `one byte short of archive plus unpack allowance refuses as no space and makes no request`() {
        val small = entry("small", 365_748_162L)
        val need = 365_748_162L + 160L * mib // small profile: maxWrittenBytes = 160 MiB
        val fetcher = RecordingFetcher()
        val result = refusal(install(fetcher, small, freeBytes = need - 1L))
        assertEquals(ModelInstaller.Refusal.NO_SPACE, result.refusal)
        assertEquals(
            "There is not enough free space on the phone to download the model.",
            result.detail,
        )
        assertEquals("no request of any kind was recorded", emptyList<String>(), fetcher.requestedIds)
        assertFalse("nothing was installed", store.isInstalled("small"))
        assertFalse("no model directory was created", store.directoryFor("small").exists())
    }

    @Test
    fun `exactly the archive plus unpack allowance passes the check and reaches the fetch`() {
        val small = entry("small", 365_748_162L)
        val need = 365_748_162L + 160L * mib
        val fetcher = RecordingFetcher()
        val result = refusal(install(fetcher, small, freeBytes = need))
        assertEquals("the check passed, the scripted fetch failure is what refused", ModelInstaller.Refusal.FETCH_FAILED, result.refusal)
        assertEquals(listOf("small"), fetcher.requestedIds)
    }

    @Test
    fun `room for the archive alone is not enough`() {
        val small = entry("small", 365_748_162L)
        val fetcher = RecordingFetcher()
        val result = refusal(install(fetcher, small, freeBytes = 365_748_162L + 1L))
        assertEquals(ModelInstaller.Refusal.NO_SPACE, result.refusal)
        assertEquals(emptyList<String>(), fetcher.requestedIds)
    }

    @Test
    fun `the unpack allowance comes from the model's own profile`() {
        // tiny's profile allows 80 MiB, small's 160 MiB: a constant would serve one of them wrong.
        val tiny = entry("tiny", 127_887_156L)
        val need = 127_887_156L + 80L * mib
        val short = refusal(install(RecordingFetcher(), tiny, freeBytes = need - 1L))
        assertEquals(ModelInstaller.Refusal.NO_SPACE, short.refusal)
        val fetcher = RecordingFetcher()
        val enough = refusal(install(fetcher, tiny, freeBytes = need))
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, enough.refusal)
        assertEquals(listOf("tiny"), fetcher.requestedIds)
    }

    @Test
    fun `the real parakeet case needs 487211443 plus 704 MiB, about 1 point 14 GiB`() {
        val parakeet = entry("parakeet", 487_211_443L)
        val need = 487_211_443L + 738_197_504L // archive + parakeet profile maxWrittenBytes (704 MiB), written out by hand
        assertEquals(1_225_408_947L, need)
        val short = refusal(install(RecordingFetcher(), parakeet, freeBytes = need - 1L))
        assertEquals(ModelInstaller.Refusal.NO_SPACE, short.refusal)
        val fetcher = RecordingFetcher()
        val enough = refusal(install(fetcher, parakeet, freeBytes = need))
        assertEquals(ModelInstaller.Refusal.FETCH_FAILED, enough.refusal)
        assertEquals(listOf("parakeet"), fetcher.requestedIds)
    }
}
