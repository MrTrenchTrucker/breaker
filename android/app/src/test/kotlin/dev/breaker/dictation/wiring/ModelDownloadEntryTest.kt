package dev.breaker.dictation.wiring

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Protects the one entry point of the model download. The background worker and the main-thread
 * post are queues the test runs by hand, so every order is exact and nothing waits on a clock.
 */
internal class ModelDownloadEntryTest {

    private val background = ModelQueueBackground()
    private val main = ModelQueueMain()
    private val notice = ModelRecordingNotice()
    private var selected = "small"
    private val outcomes: MutableList<DownloadOutcome> = ArrayList()
    private val looked: MutableList<String> = ArrayList()
    private val entry = modelEntryFor("fake-model")

    private fun newDownloader(
        installer: ModelInstallPort,
        lookup: (String) -> ModelEntry? = ModelRegistry::byId,
    ): ModelDownloader = ModelDownloader(
        installer = installer,
        selectedId = { selected },
        lookup = lookup,
        background = background,
        main = main,
        notice = notice,
    )

    private fun request(downloader: ModelDownloader) {
        downloader.requestDownload { outcomes.add(it) }
    }

    private fun finishEverything() {
        background.runAll()
        main.runAll()
    }

    @Test
    fun `the install runs on the background worker and the answer comes back on the main post`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        request(downloader)
        assertEquals("app: the notice must be shown at the request", listOf("downloading"), notice.log)
        assertEquals("app: nothing installs before the background worker runs", 0, installer.entries.size)
        background.runAll()
        assertEquals("app: the install must have run on the worker", 1, installer.entries.size)
        assertEquals("app: the answer waits for the main post", emptyList<DownloadOutcome>(), outcomes)
        assertEquals("app: the done notice waits for the main post", listOf("downloading"), notice.log)
        main.runAll()
        assertEquals("app: the notices end in done", listOf("downloading", "done"), notice.log)
        assertEquals("app: the answer names the selected model", listOf<DownloadOutcome>(DownloadOutcome.Done("small")), outcomes)
    }

    @Test
    fun `the address and checksum come from the registry entry for the selected id`() {
        val installer = ModelFakeInstaller()
        request(newDownloader(installer))
        finishEverything()
        assertSame("app: the install must get the registry's own small entry", ModelRegistry.SMALL, installer.entries.single())
    }

    @Test
    fun `an entry from the lookup is handed to the installer as it is`() {
        val installer = ModelFakeInstaller()
        request(newDownloader(installer, lookup = { id -> looked.add(id); entry }))
        finishEverything()
        assertEquals("app: the lookup must be asked for the selected id", listOf("small"), looked)
        assertSame("app: the installer must get the very entry the lookup answered", entry, installer.entries.single())
        assertEquals("app: the answer carries the entry's id", listOf<DownloadOutcome>(DownloadOutcome.Done("fake-model")), outcomes)
    }

    @Test
    fun `the selected id is read at each request`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        request(downloader)
        finishEverything()
        selected = "tiny"
        request(downloader)
        finishEverything()
        assertEquals("app: each request must use the id selected at that time", listOf(ModelRegistry.SMALL, ModelRegistry.TINY), installer.entries)
    }

    @Test
    fun `an unknown id fails with the download sentence and nothing is installed or queued`() {
        selected = "no-such-model"
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        request(downloader)
        assertEquals("app: only the failure is shown", listOf("failed:${ModelSentences.DOWNLOAD_FAILED}"), notice.log)
        assertEquals("app: the answer is the failure", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), outcomes)
        assertEquals("app: nothing was queued", 0, background.submitted)
        assertEquals("app: nothing was installed", 0, installer.entries.size)
        selected = "small"
        request(downloader)
        finishEverything()
        assertEquals("app: the guard was released, so the next request installs", 1, installer.entries.size)
    }

    @Test
    fun `a setting that cannot be read fails the request without a crash`() {
        val installer = ModelFakeInstaller()
        val downloader = ModelDownloader(
            installer = installer,
            selectedId = { throw IllegalStateException("settings unreadable") },
            background = background,
            main = main,
            notice = notice,
        )
        request(downloader)
        assertEquals("app: the failure is the answer", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), outcomes)
        assertEquals("app: nothing was installed", 0, installer.entries.size)
    }

    @Test
    fun `a refused install fails with the download sentence and the same call tries again and succeeds`() {
        val installer = ModelFakeInstaller(InstallOutcome.Refused("The model could not be downloaded."), InstallOutcome.Installed)
        val downloader = newDownloader(installer)
        request(downloader)
        finishEverything()
        assertEquals(
            "app: the first answer is the failure with the fixed sentence",
            listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)),
            outcomes,
        )
        assertEquals("app: the notices end in failed", listOf("downloading", "failed:${ModelSentences.DOWNLOAD_FAILED}"), notice.log)
        request(downloader)
        finishEverything()
        assertEquals("app: the same call installed the second time", DownloadOutcome.Done("small"), outcomes.last())
        assertEquals("app: two installs in all", 2, installer.entries.size)
        assertEquals(
            "app: the notices of the retry follow",
            listOf("downloading", "failed:${ModelSentences.DOWNLOAD_FAILED}", "downloading", "done"),
            notice.log,
        )
    }

    @Test
    fun `every installer refusal shows the one download sentence, whatever its reason`() {
        val reason = "The checksum of the model did not match."
        val installer = ModelFakeInstaller(InstallOutcome.Refused(reason))
        request(newDownloader(installer))
        finishEverything()
        assertEquals("app: the answer is the one sentence", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), outcomes)
        assertEquals("app: the notice shows the one sentence", listOf("downloading", "failed:${ModelSentences.DOWNLOAD_FAILED}"), notice.log)
        assertFalse("app: the installer's reason is not shown", notice.log.any { it.contains(reason) })
    }

    @Test
    fun `an installer that throws fails the request and frees it`() {
        val installer = ModelFakeInstaller()
        installer.failure = IllegalStateException("installer broke")
        val downloader = newDownloader(installer)
        request(downloader)
        finishEverything()
        assertEquals("app: a throwing installer is a failure", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), outcomes)
        installer.failure = null
        request(downloader)
        finishEverything()
        assertEquals("app: the request is free again", DownloadOutcome.Done("small"), outcomes.last())
    }

    @Test
    fun `a second request while one runs does nothing and answers AlreadyRunning`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        request(downloader)
        request(downloader)
        assertEquals("app: the second request is answered at once", listOf<DownloadOutcome>(DownloadOutcome.AlreadyRunning), outcomes)
        finishEverything()
        assertEquals("app: only one install ran", 1, installer.entries.size)
        assertEquals("app: only one downloading notice", listOf("downloading", "done"), notice.log)
        assertEquals("app: the first request still ended in done", listOf(DownloadOutcome.AlreadyRunning, DownloadOutcome.Done("small")), outcomes)
    }

    @Test
    fun `the download stays running until the main post has delivered the answer`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        request(downloader)
        background.runAll()
        request(downloader)
        assertEquals("app: the install is over but the answer is not delivered, so it is still running", listOf<DownloadOutcome>(DownloadOutcome.AlreadyRunning), outcomes)
        main.runAll()
        request(downloader)
        finishEverything()
        assertEquals("app: after the answer a new request installs again", 2, installer.entries.size)
    }

    @Test
    fun `a worker that refuses the job fails the request and frees it`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        background.refuse = true
        request(downloader)
        assertEquals("app: the refusal is a failure", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), outcomes)
        assertEquals("app: the notices show the start and the failure", listOf("downloading", "failed:${ModelSentences.DOWNLOAD_FAILED}"), notice.log)
        background.refuse = false
        request(downloader)
        finishEverything()
        assertEquals("app: the request is free again", DownloadOutcome.Done("small"), outcomes.last())
    }

    @Test
    fun `a main post that refuses the answer frees the request`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        request(downloader)
        main.refuse = true
        background.runAll()
        main.refuse = false
        request(downloader)
        finishEverything()
        assertEquals("app: the request is free again after a lost answer", 2, installer.entries.size)
    }

    @Test
    fun `a notice that throws never breaks the download`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        notice.downloadingThrows = true
        request(downloader)
        assertEquals("app: a failing start notice is a failure", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), outcomes)
        assertEquals("app: nothing was queued", 0, background.submitted)
        notice.downloadingThrows = false
        notice.doneThrows = true
        request(downloader)
        finishEverything()
        assertEquals("app: a failing done notice still delivers the answer", DownloadOutcome.Done("small"), outcomes.last())
        notice.doneThrows = false
        request(downloader)
        finishEverything()
        assertEquals("app: and the request was freed", 2, installer.entries.size)
    }

    @Test
    fun `a callback that throws never breaks the download`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        downloader.requestDownload { throw IllegalStateException("callback broke") }
        downloader.requestDownload { throw IllegalStateException("callback broke") }
        finishEverything()
        request(downloader)
        finishEverything()
        assertEquals("app: the request is free after a failing callback", 2, installer.entries.size)
    }

    @Test
    fun `the request needs no callback`() {
        val installer = ModelFakeInstaller()
        val downloader = newDownloader(installer)
        downloader.requestDownload()
        finishEverything()
        assertEquals("app: the button calls it with no arguments", listOf("downloading", "done"), notice.log)
    }

    @Test
    fun `requestDownload is the one public way in`() {
        val names = ModelDownloader::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
        assertEquals("app: the downloader must have one public method", listOf("requestDownload"), names)
    }
}
