package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.updater.InstallReasons
import dev.breaker.dictation.updater.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The About install row over the fakes of [AboutControlWiringTest]. A held release is offered only with
 * nothing running, the install runs on the background and never on the calling thread, the updater's
 * answer reaches the row through the holder, and a throwing install lands on the failure row.
 */
class AboutInstallWiringTest {

    private fun release(): ReleaseInfo = ReleaseInfo("9.9.9", "https://example.test/breaker.apk", "aa", "bb")

    /** Runs one check that offers [offer], so an install has a release to take. The screen must be bound first. */
    private fun checkOffering(h: AboutControlWiringTest.Harness, offer: ReleaseInfo) {
        h.checker.result = UpdateCheckResult.Available(offer)
        h.wiring.onCheck()
    }

    @Test
    fun `the install row is offered only with a release held and nothing running`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        assertFalse("no release is held before a check", h.last.installReady)
        checkOffering(h, release())
        assertTrue("a held release with nothing running offers the install row", h.last.installReady)
        h.background.hold = true
        h.control.onInstall!!.invoke()
        assertFalse("a running install hides the install row", h.last.installReady)
        assertTrue("a running install is drawn as installing", h.last.installing)
    }

    @Test
    fun `the install runs on the background and never on the main thread`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        val offer = release()
        checkOffering(h, offer)
        val posts: Int = h.main.posts
        h.background.hold = true
        h.control.onInstall!!.invoke()
        assertEquals("the tap submitted one install to the background", 2, h.background.submissions)
        assertEquals("the tap ran no install on the calling thread", 0, h.checker.installed.size)
        assertEquals("the tap posted nothing back before the install ran", posts, h.main.posts)
        h.background.releaseNext()
        assertEquals("the background block ran the install once", 1, h.checker.installed.size)
        assertTrue("the install was handed the held release", h.checker.installed[0] === offer)
        assertEquals("a successful install posts nothing back", posts, h.main.posts)
    }

    @Test
    fun `a refused answer lands on failed and frees the install row`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        assertTrue("the install is running after the tap", h.last.installing)
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        assertTrue("a refusal is drawn as a failure", h.last.failed)
        assertFalse("a refusal frees the install row", h.last.installing)
    }

    @Test
    fun `a started answer keeps the row installing until a new check`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Started)
        assertTrue("a started install keeps the row installing", h.last.installing)
        assertFalse("a started install is not a failure", h.last.failed)
    }

    @Test
    fun `a second tap while an install runs starts no second install`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.background.hold = true
        h.control.onInstall!!.invoke()
        val before: Int = h.background.submissions
        h.control.onInstall!!.invoke()
        assertEquals("the second tap submitted nothing", before, h.background.submissions)
    }

    @Test
    fun `a new check clears the held release and the installing flag`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Started)
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        assertFalse("the new check frees the install row", h.last.installing)
        assertFalse("the new check clears the held release", h.last.installReady)
        assertFalse("the new check clears the offer", h.last.updateAvailable)
        h.control.onInstall!!.invoke()
        assertEquals("a tap after the new check runs no install", 1, h.checker.installed.size)
    }

    @Test
    fun `a throwing install lands on the failed row and frees the install row`() {
        val h = AboutControlWiringTest.Harness()
        h.checker.throwOnInstall = true
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        assertEquals("the throwing install was attempted once", 1, h.checker.installed.size)
        assertFalse("a throwing install frees the install row", h.last.installing)
        assertTrue("a throwing install is drawn as a failure", h.last.failed)
    }

    @Test
    fun `a non-admin draw carries admin false`() {
        val h = AboutControlWiringTest.Harness()
        h.admin = false
        h.wiring.bind()
        checkOffering(h, release())
        assertFalse("a non-admin draw carries admin false for the screen to hide the row", h.last.admin)
    }

    @Test
    fun `an install before bind does nothing`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.onInstall()
        assertEquals("no draw before bind", 0, h.shows.size)
        assertEquals("no install before bind", 0, h.checker.installed.size)
    }

    @Test
    fun `an answer after release is dropped`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        h.wiring.release()
        val drawn: Int = h.shows.size
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        assertEquals("an answer after release draws nothing", drawn, h.shows.size)
        assertFalse("an answer after release sets no failure", h.last.failed)
    }

    @Test
    fun `a successful finish draws nothing and keeps the row installing`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        val drawn: Int = h.shows.size
        h.wiring.onInstallFinished(0)
        assertEquals("a successful finish draws nothing", drawn, h.shows.size)
        assertTrue("a successful finish leaves the row installing", h.last.installing)
        assertFalse("a successful finish is not a failure", h.last.failed)
    }

    @Test
    fun `a cancelled install frees the install row and offers it again silently`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        val drawn: Int = h.shows.size
        h.wiring.onInstallFinished(3)
        assertEquals("a cancelled install draws the row once", drawn + 1, h.shows.size)
        assertFalse("a cancelled install frees the install row", h.last.installing)
        assertTrue("a cancelled install offers the install row again", h.last.installReady)
        assertFalse("a cancelled install is not a failure", h.last.failed)
    }

    @Test
    fun `a cancelled install clears an earlier failure`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        assertTrue("the refusal left a failure", h.last.failed)
        h.control.onInstall!!.invoke()
        assertTrue("the second install is running", h.last.installing)
        h.wiring.onInstallFinished(3)
        assertFalse("the cancel clears the earlier failure", h.last.failed)
        assertFalse("the cancel frees the install row", h.last.installing)
    }

    @Test
    fun `other finish codes land on failed and free the install row`() {
        for (code in listOf(1, 2, 4, 5)) {
            val h = AboutControlWiringTest.Harness()
            h.wiring.bind()
            checkOffering(h, release())
            h.control.onInstall!!.invoke()
            h.wiring.onInstallFinished(code)
            assertTrue("another finish code is drawn as a failure", h.last.failed)
            assertFalse("another finish code frees the install row", h.last.installing)
        }
    }

    @Test
    fun `a finish while nothing runs is ignored`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        val drawn: Int = h.shows.size
        h.wiring.onInstallFinished(3)
        h.wiring.onInstallFinished(1)
        assertEquals("a finish with no install running draws nothing", drawn, h.shows.size)
        assertFalse("a finish with no install running sets no failure", h.last.failed)
    }

    @Test
    fun `a finished session through the answer holder reaches the wiring`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        h.answers.reportFinished(3)
        assertFalse("the cancel from the holder frees the install row", h.last.installing)
        assertTrue("the cancel from the holder offers the install row again", h.last.installReady)
    }

    @Test
    fun `a finish after release is dropped`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        checkOffering(h, release())
        h.control.onInstall!!.invoke()
        h.wiring.release()
        val drawn: Int = h.shows.size
        h.answers.reportFinished(3)
        assertEquals("a finish after release draws nothing", drawn, h.shows.size)
        assertTrue("a finish after release leaves the install running", h.last.installing)
    }

    @Test
    fun `after a cancelled install a new tap on Install runs the install again`() {
        val h = AboutControlWiringTest.Harness()
        h.wiring.bind()
        val offer = release()
        checkOffering(h, offer)
        h.control.onInstall!!.invoke()
        h.wiring.onInstallFinished(3)
        h.control.onInstall!!.invoke()
        assertEquals("the new tap ran the install a second time", 2, h.checker.installed.size)
        assertTrue("the second install was handed the held release", h.checker.installed[1] === offer)
    }
}
