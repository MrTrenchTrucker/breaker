package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.ModelInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protects the download answer when the phone has too little free space. The installer's no-space
 * refusal must reach the user as its own sentence, both in the notification and in the launcher
 * line, and the installer's technical detail must never reach the user. The background worker and
 * the main post are queues the test runs by hand, so nothing waits on a clock.
 */
internal class ModelNoSpaceTest {

    private val noSpaceSentence = "Not enough free space for the speech model. Free up some space."
    private val technicalDetail = "zqxw technical detail for the debug log only"
    private val entry = modelEntryFor("fake-model")

    /** A port that answers [refusal] from the installer, with the technical detail attached. */
    private fun refusing(refusal: ModelInstaller.Refusal): ModelInstallPort =
        InstallerPort { ModelInstaller.InstallResult.Refused(refusal, technicalDetail) }

    /** Runs one request to the end on fresh queues, showing its notices on [sink]; answers what the caller got. */
    private fun runWith(port: ModelInstallPort, sink: ModelDownloadNotice): List<DownloadOutcome> {
        val background = ModelQueueBackground()
        val main = ModelQueueMain()
        val answers: MutableList<DownloadOutcome> = ArrayList()
        val downloader = ModelDownloader(
            installer = port,
            selectedId = { "fake-model" },
            lookup = { entry },
            background = background,
            main = main,
            notice = sink,
        )
        downloader.requestDownload { answers.add(it) }
        background.runAll()
        main.runAll()
        return answers
    }

    @Test
    fun `a no space refusal answers the no space sentence and the notice ends with it`() {
        val notice = ModelRecordingNotice()
        val answers = runWith(refusing(ModelInstaller.Refusal.NO_SPACE), notice)
        assertEquals("app: the answer is the no space sentence", listOf<DownloadOutcome>(DownloadOutcome.Failed(noSpaceSentence)), answers)
        assertEquals("app: the notice ends with the no space sentence", "failed:$noSpaceSentence", notice.log.last())
    }

    @Test
    fun `a no space refusal reaches the notification and the launcher listener`() {
        val notifications = ModelRecordingNotice()
        val listener = ModelRecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        runWith(refusing(ModelInstaller.Refusal.NO_SPACE), forwarder)
        assertEquals("app: the notification ends with the no space sentence", "failed:$noSpaceSentence", notifications.log.last())
        assertEquals("app: the launcher listener ends with the no space sentence", "failed:$noSpaceSentence", listener.log.last())
    }

    @Test
    fun `every other refusal reason answers the download sentence in every log`() {
        var checked = 0
        for (refusal in ModelInstaller.Refusal.values()) {
            if (refusal == ModelInstaller.Refusal.NO_SPACE) continue
            val notifications = ModelRecordingNotice()
            val listener = ModelRecordingNotice()
            val forwarder = ForwardingDownloadNotice(notifications)
            forwarder.setListener(listener)
            val answers = runWith(refusing(refusal), forwarder)
            assertEquals("app: $refusal answers the download sentence", listOf<DownloadOutcome>(DownloadOutcome.Failed(ModelSentences.DOWNLOAD_FAILED)), answers)
            assertEquals("app: $refusal shows the download sentence in the notification", "failed:${ModelSentences.DOWNLOAD_FAILED}", notifications.log.last())
            assertEquals("app: $refusal shows the download sentence in the launcher line", "failed:${ModelSentences.DOWNLOAD_FAILED}", listener.log.last())
            checked += 1
        }
        assertEquals("app: every refusal but no space was checked", ModelInstaller.Refusal.values().size - 1, checked)
        assertTrue("app: at least nine other refusals were checked", checked >= 9)
    }

    @Test
    fun `the installer technical detail of a no space refusal is never shown`() {
        val notifications = ModelRecordingNotice()
        val listener = ModelRecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        val answers = runWith(refusing(ModelInstaller.Refusal.NO_SPACE), forwarder)
        assertEquals("app: the answer is the no space sentence", listOf<DownloadOutcome>(DownloadOutcome.Failed(noSpaceSentence)), answers)
        assertFalse("app: no notification line holds the technical detail", notifications.log.any { it.contains("zqxw") })
        assertFalse("app: no launcher line holds the technical detail", listener.log.any { it.contains("zqxw") })
        assertFalse("app: no answer holds the technical detail", answers.any { it.toString().contains("zqxw") })
    }

    @Test
    fun `the request after a no space failure installs on the same call`() {
        val background = ModelQueueBackground()
        val main = ModelQueueMain()
        val notifications = ModelRecordingNotice()
        var calls = 0
        val port = InstallerPort { installed ->
            calls += 1
            if (calls == 1) {
                ModelInstaller.InstallResult.Refused(ModelInstaller.Refusal.NO_SPACE, technicalDetail)
            } else {
                ModelInstaller.InstallResult.Installed(installed.id, "digest")
            }
        }
        val downloader = ModelDownloader(
            installer = port,
            selectedId = { "fake-model" },
            lookup = { entry },
            background = background,
            main = main,
            notice = notifications,
        )
        val answers: MutableList<DownloadOutcome> = ArrayList()
        downloader.requestDownload { answers.add(it) }
        background.runAll()
        main.runAll()
        downloader.requestDownload { answers.add(it) }
        background.runAll()
        main.runAll()
        assertEquals("app: the first answer is the no space sentence", DownloadOutcome.Failed(noSpaceSentence), answers.first())
        assertEquals("app: the second request installs and ends in done", DownloadOutcome.Done("fake-model"), answers.last())
        assertEquals("app: two installs ran", 2, calls)
    }
}
