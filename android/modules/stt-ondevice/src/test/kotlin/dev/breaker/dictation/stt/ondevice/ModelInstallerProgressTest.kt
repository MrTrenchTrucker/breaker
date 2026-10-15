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
 * The progress callback of the installer: what it receives, what a throwing callback
 * costs the install, and which fetch it is handed to.
 */
class ModelInstallerProgressTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: LocalModelStore

    @Before
    fun setUp() {
        store = LocalModelStore(tmp.root)
    }

    /** A real archive, so an Installed result can unpack it with the default extractor. */
    private val bytes: ByteArray = TarFixtures.tinyArchive()
    private val digest: String = Fixtures.independentSha256(bytes)
    private val entry = ModelEntry("tiny", ModelFamily.SHERPA_ONNX, "https://example.com/model", digest, 1, "Apache-2.0", false)
    private val checksums = "model.archive\t$digest"

    /** Overrides both forms; the three-argument one hands the scripted (received, total) pairs to the callback in order. */
    private class ScriptedFetcher(
        private val modelBytes: ByteArray,
        private val checksumsText: String,
        private val script: List<Pair<Long, Long>>,
    ) : ModelFetcher {
        /** Reports handed to a callback so far, counted by this fake before each call. */
        var reportsSent: Int = 0
            private set

        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result =
            fetchModel(entry, stagingDir) { _, _ -> }

        override fun fetchModel(entry: ModelEntry, stagingDir: File, onProgress: (Long, Long) -> Unit): ModelFetcher.Result {
            for ((received, total) in script) {
                reportsSent += 1
                onProgress(received, total)
            }
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(file)
        }

        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "checksums.txt")
            file.writeText(checksumsText)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    /** Knows only the two-argument form, as the neighbour fakes do. */
    private class TwoArgFetcher(
        private val modelBytes: ByteArray,
        private val checksumsText: String,
    ) : ModelFetcher {
        override fun fetchModel(entry: ModelEntry, stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "model.archive")
            file.writeBytes(modelBytes)
            return ModelFetcher.Result.Fetched(file)
        }

        override fun fetchChecksums(stagingDir: File): ModelFetcher.Result {
            val file = File(stagingDir, "checksums.txt")
            file.writeText(checksumsText)
            return ModelFetcher.Result.Fetched(file)
        }
    }

    @Test
    fun `the callback gets what the model fetch reports, in order`() {
        val script = listOf(10L to 100L, 50L to 100L, 100L to 100L)
        val seen = ArrayList<Pair<Long, Long>>()
        val result = ModelInstaller(store, ScriptedFetcher(bytes, checksums, script))
            .install(entry) { received, total -> seen.add(received to total) }
        assertEquals("the callback saw the scripted reports in order", script, seen)
        assertTrue("the install is Installed: $result", result.isInstalled)
    }

    @Test
    fun `a callback that throws does not fail the install`() {
        val fetcher = ScriptedFetcher(bytes, checksums, listOf(10L to 100L, 50L to 100L, 100L to 100L))
        val result = ModelInstaller(store, fetcher).install(entry) { _, _ -> throw IllegalStateException("callback-detail") }
        assertEquals("the fake still made every scripted report", 3, fetcher.reportsSent)
        assertTrue("the install is Installed: $result", result.isInstalled)
    }

    @Test
    fun `a callback that throws is logged to the debug sink once`() {
        val sunk = ArrayList<String>()
        val fetcher = ScriptedFetcher(bytes, checksums, listOf(10L to 100L, 50L to 100L, 100L to 100L))
        ModelInstaller(store, fetcher, ModelDebugSink { sunk.add(it) })
            .install(entry) { _, _ -> throw IllegalStateException("secret-detail") }
        val lines = sunk.filter { it.contains("progress listener failed") }
        assertEquals("one progress failure line for three throws: $sunk", 1, lines.size)
        assertTrue("the line names the exception class: ${lines.single()}", lines.single().contains("IllegalStateException"))
        assertFalse("the line carries no exception message: ${lines.single()}", lines.single().contains("secret-detail"))
    }

    @Test
    fun `a callback that fails once and then works keeps receiving reports`() {
        var calls = 0
        val recorded = ArrayList<Long>()
        val result = ModelInstaller(store, ScriptedFetcher(bytes, checksums, listOf(10L to 100L, 50L to 100L, 100L to 100L)))
            .install(entry) { received, _ ->
                calls += 1
                if (calls == 1) throw IllegalStateException("first-only")
                recorded.add(received)
            }
        assertEquals("the reports after the throw reached the callback", listOf(50L, 100L), recorded)
        assertTrue("the install is Installed: $result", result.isInstalled)
    }

    @Test
    fun `install without a callback still works`() {
        val result = ModelInstaller(store, ScriptedFetcher(bytes, checksums, listOf(10L to 100L))).install(entry)
        assertTrue("the one-argument install is Installed: $result", result.isInstalled)
    }

    @Test
    fun `a fetcher that only knows the two-argument form still installs`() {
        val seen = ArrayList<Pair<Long, Long>>()
        val result = ModelInstaller(store, TwoArgFetcher(bytes, checksums))
            .install(entry) { received, total -> seen.add(received to total) }
        assertTrue("the install is Installed: $result", result.isInstalled)
        assertTrue("the callback was never called: $seen", seen.isEmpty())
    }
}
