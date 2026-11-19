package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the id form of the download request: the id it names is the one fetched and installed, one
 * download runs at a time, and the id being fetched is seen until the download ends. The queues are
 * run by hand, so the order is exact.
 */
internal class ModelIdDownloadTest {

    private val background = ModelQueueBackground()
    private val main = ModelQueueMain()
    private val notice = ModelRecordingNotice()
    private val installer = ModelFakeInstaller(InstallOutcome.Installed)
    private val outcomes: MutableList<DownloadOutcome> = ArrayList()

    private fun downloader(): ModelDownloader = ModelDownloader(
        installer = installer,
        selectedId = { "small" },
        lookup = { id -> modelEntryFor(id) },
        background = background,
        main = main,
        notice = notice,
    )

    @Test
    fun `a download by id installs the id it names and not the selected one`() {
        downloader().requestDownload("parakeet") { outcomes.add(it) }
        background.runAll()
        main.runAll()
        assertEquals("app: the installer is given the named id", listOf("parakeet"), installer.entries.map { it.id })
        assertEquals("app: the answer names the installed id", listOf<DownloadOutcome>(DownloadOutcome.Done("parakeet")), outcomes)
    }

    @Test
    fun `a second download by id while one runs is refused at once`() {
        val downloader = downloader()
        downloader.requestDownload("parakeet") { outcomes.add(it) }
        downloader.requestDownload("tiny") { outcomes.add(it) }
        assertEquals("app: the second request answers at once", listOf<DownloadOutcome>(DownloadOutcome.AlreadyRunning), outcomes)
        background.runAll()
        main.runAll()
        assertEquals("app: only the first id is installed", listOf("parakeet"), installer.entries.map { it.id })
    }

    @Test
    fun `the downloading id is seen while the fetch runs and clears when it ends`() {
        val downloader = downloader()
        assertNull(downloader.downloadingId())
        downloader.requestDownload("parakeet") { outcomes.add(it) }
        assertEquals("parakeet", downloader.downloadingId())
        background.runAll()
        main.runAll()
        assertNull(downloader.downloadingId())
    }

    @Test
    fun `a download with no id still fetches the selected id`() {
        downloader().requestDownload { outcomes.add(it) }
        background.runAll()
        main.runAll()
        assertEquals("app: the selected id is fetched", listOf("small"), installer.entries.map { it.id })
    }
}
