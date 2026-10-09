package dev.breaker.dictation.wiring

import dev.breaker.dictation.BreakerCompositionRoot
import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.LaunchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The composition root opens the history only when the history is used. */
class LazyHistoryRootTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store = RecordingHistoryStore()
    private var supplierCalls = 0
    private val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher(LaunchResult.Launched))

    private fun root() = BreakerCompositionRoot(
        filesDir = tmp.root,
        history = { supplierCalls += 1; store },
        serviceController = controller,
        committer = FakeCommitter(),
    )

    private fun row(id: String) = Transcription(id, "text", TranscriptionSource.LOCAL, "small", 10L, 1_000L)

    @Test
    fun `reading the settings and the keystore and saving settings never builds the history`() {
        val root = root()
        root.settingsStore.save(AppSettings().copy(mode = SttMode.LOCAL, apiKeyRef = "ref-1"))
        assertEquals("app: the settings should read back", SttMode.LOCAL, root.settingsStore.load().mode)
        assertEquals("app: the keystore should hold the saved reference", "ref-1", root.keystore.activeRef())
        assertEquals("app: settings and keystore work must not build the history", 0, supplierCalls)
    }

    @Test
    fun `building the dictation parts does not build the history either`() {
        val root = root()
        assertSame("app: the dictation parts should be built once", root.dictation, root.dictation)
        assertEquals("app: building the dictation parts must not build the history", 0, supplierCalls)
    }

    @Test
    fun `the first use of the history builds it once`() {
        val root = root()
        root.historyStore.save(row("a"))
        root.historyStore.list(5)
        root.historyStore.delete("a")
        assertEquals("app: the history should be built exactly once", 1, supplierCalls)
        assertEquals("app: the built store should have received every call", listOf("save", "list", "delete"), store.calls)
    }

    @Test
    fun `the history the root exposes over a supplier is the lazy store`() {
        assertTrue("app: a supplier should be wrapped in the lazy store", root().historyStore is LazyHistoryStore)
    }

    @Test
    fun `a history store handed in as a value is exposed as it is and the older constructor works`() {
        val handed: HistoryStore = RecordingHistoryStore()
        val root = BreakerCompositionRoot(
            filesDir = tmp.root,
            historyStore = handed,
            committer = FakeCommitter(),
        )
        assertSame("app: a store handed in as a value should be exposed unchanged", handed, root.historyStore)
        assertFalse("app: the older constructor must not wrap the store", root.historyStore is LazyHistoryStore)
        root.settingsStore.save(AppSettings().copy(apiKeyRef = "ref-2"))
        assertEquals("app: the older constructor should still wire the settings", "ref-2", root.settingsStore.load().apiKeyRef)
    }
}
