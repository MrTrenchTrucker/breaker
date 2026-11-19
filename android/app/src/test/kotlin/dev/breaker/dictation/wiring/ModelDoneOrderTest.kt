package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.shared.models.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the end of a first-run download, composed on the app side: the id goes to the real downloader with
 * the real selection write, the install puts the model on the phone, and the done notice runs the screen's
 * refresh. The install must be saved as the model in use before that refresh, so the refresh reads the new
 * model and the page finishes without a second tap. The notice here performs the refresh: its done() reads
 * the model in use from the settings and asks whether that model is installed. The queues are run by hand,
 * so the order is exact.
 */
internal class ModelDoneOrderTest {

    private class OrderSettings : SettingsStore {
        var stored: AppSettings = AppSettings()

        override fun load(): AppSettings = stored

        override fun save(settings: AppSettings) {
            stored = settings
        }
    }

    /** A notice whose done() performs the refresh: the page is finished when the model in use is installed. */
    private class RefreshingNotice(
        private val settings: SettingsStore,
        private val installed: Set<String>,
    ) : ModelDownloadNotice {
        var finished: Boolean = false
        val failures: MutableList<String> = ArrayList()

        override fun downloading(sentence: String) {
        }

        override fun done() {
            finished = installed.contains(settings.load().modelSize)
        }

        override fun failed(sentence: String) {
            failures.add(sentence)
        }
    }

    /** One first-run download on fresh queues: an install that puts the model on the phone, and the refreshing notice. */
    private class Rig(startInstalled: Set<String>, outcome: InstallOutcome = InstallOutcome.Installed) {
        val settings = OrderSettings()
        val installed: MutableSet<String> = startInstalled.toMutableSet()
        val background = ModelQueueBackground()
        val main = ModelQueueMain()
        val notice = RefreshingNotice(settings, installed)
        val writer = ModelSelectWriter(settings) { id -> installed.contains(id) }
        private val installer = object : ModelInstallPort {
            override fun install(entry: ModelEntry): InstallOutcome {
                if (outcome is InstallOutcome.Installed) installed.add(entry.id)
                return outcome
            }
        }
        val downloader = ModelDownloader(
            installer = installer,
            selectedId = { settings.load().modelSize },
            lookup = { modelEntryFor(it) },
            background = background,
            main = main,
            notice = notice,
        )

        fun runAll() {
            background.runAll()
            main.runAll()
        }
    }

    @Test
    fun `the install of a first-run download is saved as the model in use before the done notice reads it`() {
        val rig = Rig(startInstalled = emptySet())
        rig.downloader.requestDownload("parakeet", selectOnDone(rig.writer))
        rig.runAll()
        assertEquals("app: the install is saved as the model in use", "parakeet", rig.settings.load().modelSize)
        assertTrue("app: the done notice must read the installed model as in use, so the page finishes", rig.notice.finished)
    }

    @Test
    fun `a failed first-run download changes no model in use and finishes nothing`() {
        val rig = Rig(startInstalled = setOf("parakeet"), outcome = InstallOutcome.Refused("The model could not be downloaded."))
        rig.downloader.requestDownload("tiny", selectOnDone(rig.writer))
        rig.runAll()
        assertEquals("app: a failed download keeps the model in use", "small", rig.settings.load().modelSize)
        assertFalse("app: a failed download does not finish the page", rig.notice.finished)
        assertEquals("app: the failure sentence is shown once", listOf(ModelSentences.DOWNLOAD_FAILED), rig.notice.failures)
    }

    @Test
    fun `a finished first-run download is answered once`() {
        val rig = Rig(startInstalled = emptySet())
        val answers: MutableList<DownloadOutcome> = ArrayList()
        rig.downloader.requestDownload("parakeet") { outcome ->
            answers.add(outcome)
            selectOnDone(rig.writer)(outcome)
        }
        rig.runAll()
        assertEquals("app: the answer is given once and names the installed id", listOf<DownloadOutcome>(DownloadOutcome.Done("parakeet")), answers)
    }
}
