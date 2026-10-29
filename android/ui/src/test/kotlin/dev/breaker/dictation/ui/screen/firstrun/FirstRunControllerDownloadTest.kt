package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupNotice
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The download page: the app is asked to start the download only when the tracker allows it,
 * a running download is never started again, and the first run ends once the model is ready
 * while the download page is shown.
 */
class FirstRunControllerDownloadTest {
    /** A controller over the fakes; the tracker may be shared with another controller. */
    private class Rig(val tracker: DownloadTracker = DownloadTracker()) {
        val platform = FakeSetupPlatform(
            PlatformStatus(
                overlay = false,
                microphone = false,
                notifications = false,
                accessibility = false,
                sdkInt = 36,
            ),
        )
        val switch = FakeBreakerSwitch(false)
        var modelReady = false
        var downloadLine: String? = null
        var throwOnDownload = false
        var downloads = 0
        var finished = 0
        val handler = SetupIntentHandler(platform, switch, { false }, { null }, { 0 }, { }, SetupScreen())
        val controller = FirstRunController(
            handler = handler,
            modelReady = { modelReady },
            downloadLine = { downloadLine },
            onDownloadModel = {
                downloads++
                if (throwOnDownload) throw IllegalStateException("start failed")
            },
            onFinished = { finished++ },
            tracker = tracker,
        )
    }

    private fun Rig.toDownloadPage() {
        repeat(6) { controller.advance() }
    }

    @Test
    fun `a tap on an idle download page calls the app exactly once`() {
        val r = Rig()
        r.controller.tapDownload()
        assertEquals(1, r.downloads)
        assertEquals(DownloadPhase.RUNNING, r.controller.shown().download.phase)
    }

    @Test
    fun `a second tap, refreshes and progress followed by a tap never start the download again`() {
        val r = Rig()
        r.controller.tapDownload()
        r.controller.tapDownload()
        assertEquals(1, r.downloads)
        repeat(3) { r.controller.refresh() }
        assertEquals(1, r.downloads)
        r.controller.downloadProgress(7, 365)
        r.controller.tapDownload()
        assertEquals(1, r.downloads)
        assertEquals(DownloadPhase.RUNNING, r.controller.shown().download.phase)
    }

    @Test
    fun `a controller built while progress is arriving refuses the tap and shows the running download`() {
        val tracker = DownloadTracker()
        tracker.onProgress(7, 365)
        val r = Rig(tracker)
        r.controller.tapDownload()
        assertEquals(0, r.downloads)
        val download = r.controller.shown().download
        assertEquals(DownloadPhase.RUNNING, download.phase)
        assertEquals(7, download.doneMb)
        assertEquals(365, download.totalMb)
    }

    @Test
    fun `a second controller over the same tracker never starts a download the first one started`() {
        val tracker = DownloadTracker()
        val first = Rig(tracker)
        first.controller.tapDownload()
        assertEquals(1, first.downloads)
        val second = Rig(tracker)
        second.controller.tapDownload()
        assertEquals(0, second.downloads)
        assertEquals(DownloadPhase.RUNNING, second.controller.shown().download.phase)
    }

    @Test
    fun `after a stop with a failure line and no model the phase is failed and a tap starts again`() {
        val r = Rig()
        r.controller.tapDownload()
        r.downloadLine = "The download stopped."
        r.controller.downloadStopped()
        assertEquals(DownloadPhase.FAILED, r.controller.shown().download.phase)
        assertEquals("The download stopped.", r.controller.shown().download.line)
        r.controller.tapDownload()
        assertEquals(2, r.downloads)
    }

    @Test
    fun `a download start that throws shows the could not download text, goes back to idle and the next tap tries again`() {
        val r = Rig()
        r.throwOnDownload = true
        r.controller.tapDownload()
        assertEquals(1, r.downloads)
        assertEquals(SetupNotice.COULD_NOT_DOWNLOAD.text, r.controller.shown().notice)
        assertEquals(DownloadPhase.IDLE, r.controller.shown().download.phase)
        r.throwOnDownload = false
        r.controller.tapDownload()
        assertEquals(2, r.downloads)
        assertEquals(DownloadPhase.RUNNING, r.controller.shown().download.phase)
    }

    @Test
    fun `checking shows the checking phase with the numbers kept`() {
        val r = Rig()
        r.controller.tapDownload()
        r.controller.downloadProgress(7, 365)
        r.controller.downloadChecking()
        val download = r.controller.shown().download
        assertEquals(DownloadPhase.CHECKING, download.phase)
        assertEquals(7, download.doneMb)
        assertEquals(365, download.totalMb)
    }

    @Test
    fun `the download line shown is the provider's answer as last read`() {
        val r = Rig()
        r.downloadLine = "Fetching the model."
        r.controller.refresh()
        assertEquals("Fetching the model.", r.controller.shown().download.line)
        r.downloadLine = "Checking the file."
        r.controller.refresh()
        assertEquals("Checking the file.", r.controller.shown().download.line)
    }

    @Test
    fun `onFinished is called once on the download page when the model is ready, even after more refreshes`() {
        val r = Rig()
        r.toDownloadPage()
        r.modelReady = true
        r.controller.refresh()
        assertEquals(1, r.finished)
        repeat(2) { r.controller.refresh() }
        assertEquals(1, r.finished)
        assertEquals(DownloadPhase.READY, r.controller.shown().download.phase)
        assertEquals(0, r.downloads)
    }

    @Test
    fun `onFinished is not called on any other page even when the model is ready`() {
        val r = Rig()
        r.modelReady = true
        r.controller.refresh()
        assertEquals(0, r.finished)
        r.controller.advance()
        r.controller.advance()
        r.controller.advance()
        r.controller.advance()
        assertEquals(FirstRunPage.ACCESSIBILITY, r.controller.page)
        r.controller.refresh()
        assertEquals(0, r.finished)
    }

    @Test
    fun `onFinished is not called while the model is not ready`() {
        val r = Rig()
        r.toDownloadPage()
        r.controller.refresh()
        r.controller.refresh()
        assertEquals(FirstRunPage.DOWNLOAD, r.controller.page)
        assertEquals(0, r.finished)
    }
}
