package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.ui.AboutControl
import dev.breaker.dictation.updater.InstallReasons
import dev.breaker.dictation.updater.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The About install row's refusal line over fakes. The control keeps one ordered log: a set of the
 * refusal flag, then each draw, so a test sees which flag each draw carried. The background, main post,
 * checker and show record are the nested fakes of [AboutControlWiringTest]. The three behaviours are a
 * refusal, any other failure and a cancel at the system installer; each has a positive and a negative test.
 */
class AboutInstallRefusalWiringTest {

    /** A fake [AboutControl] with one ordered log: a refusal-flag set entry, then a show entry for each draw. */
    internal class OrderedControl : AboutControl {
        val calls: MutableList<String> = ArrayList()
        val shows: MutableList<AboutControlWiringTest.Show> = ArrayList()
        var onCheck: (() -> Unit)? = null
        var onInstall: (() -> Unit)? = null

        override fun bind(onCheck: () -> Unit, onInstall: () -> Unit) {
            this.onCheck = onCheck
            this.onInstall = onInstall
        }

        override fun setInstallRefused(refused: Boolean) {
            calls.add("set $refused")
        }

        override fun show(admin: Boolean, updateAvailable: Boolean, installReady: Boolean, checking: Boolean, installing: Boolean, failed: Boolean, upToDate: Boolean) {
            calls.add("show")
            shows.add(AboutControlWiringTest.Show(admin, updateAvailable, installReady, checking, installing, failed, upToDate))
        }

        override fun refresh() {}
    }

    /** One screen: the wiring over [OrderedControl] and the nested fakes of [AboutControlWiringTest]. */
    internal class Harness {
        val control: OrderedControl = OrderedControl()
        val background: AboutControlWiringTest.InlineBackground = AboutControlWiringTest.InlineBackground()
        val main: AboutControlWiringTest.InlineMainPost = AboutControlWiringTest.InlineMainPost()
        val checker: AboutControlWiringTest.ScriptedChecker = AboutControlWiringTest.ScriptedChecker()
        val answers: InstallAnswerRelay = InstallAnswerRelay(main)
        val wiring: AboutControlWiring = AboutControlWiring(control, background, main, { checker }, { true }, answers)

        val last: AboutControlWiringTest.Show get() = control.shows.last()

        /** The refusal flag each draw carried, in draw order: the value of the set entry before it. */
        fun flagsBeforeDraws(): List<Boolean> = control.calls.filter { it.startsWith("set ") }.map { it == "set true" }

        /** The refusal flag the last draw carried. */
        fun lastFlag(): Boolean = flagsBeforeDraws().last()
    }

    private fun release(): ReleaseInfo = ReleaseInfo("9.9.9", "https://example.test/breaker.apk", "aa", "bb")

    /** Binds the screen and runs one check that offers a release, so that an install has one to take. */
    private fun offering(h: Harness) {
        h.wiring.bind()
        h.checker.result = UpdateCheckResult.Available(release())
        h.wiring.onCheck()
    }

    /** Every draw is one set entry then one show entry, and each set entry carries the flag in [expected]. */
    private fun assertSetBeforeEachDraw(h: Harness, expected: List<Boolean>) {
        assertEquals("order: two log entries per draw", expected.size * 2, h.control.calls.size)
        assertEquals("order: one draw per expected flag", expected.size, h.control.shows.size)
        for (i in expected.indices) {
            assertEquals("order: draw $i is preceded by a set carrying ${expected[i]}", "set ${expected[i]}", h.control.calls[2 * i])
            assertEquals("order: draw $i follows its set", "show", h.control.calls[2 * i + 1])
        }
    }

    /** No draw carries the refusal flag and the failure together. */
    private fun assertNeverBoth(h: Harness) {
        val flags: List<Boolean> = h.flagsBeforeDraws()
        assertEquals("never both: one flag per draw", h.control.shows.size, flags.size)
        for (i in flags.indices) {
            assertFalse("never both: draw $i does not show the refusal and the failure together", flags[i] && h.control.shows[i].failed)
        }
    }

    @Test
    fun `refusal positive a refused answer sets the refusal flag, shows no failure and draws after the set`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_STARTED))
        assertEquals("refusal positive: the flag is set just before the draw", "set true", h.control.calls[h.control.calls.size - 2])
        assertFalse("refusal positive: the refused answer shows no failure", h.last.failed)
        assertFalse("refusal positive: the row is free again", h.last.installing)
        assertTrue("refusal positive: the install row stays offered", h.last.installReady)
    }

    @Test
    fun `a refusal with the not allowed reason gives the refusal flag and no failure`() {
        // The settings page is opened by the installer adapter before it answers (UpdateInstallerAndroid.kt),
        // not by this wiring, which matches only the type of the answer and never reads its reason.
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        val drawn: Int = h.control.calls.size
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        assertEquals("refusal reason: the answer draws one set and one show", drawn + 2, h.control.calls.size)
        assertTrue("refusal reason: the unknown apps refusal sets the refusal flag", h.lastFlag())
        assertFalse("refusal reason: the unknown apps refusal shows no failure", h.last.failed)
    }

    @Test
    fun `refusal negative a started answer sets no refusal flag and no failure`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Started)
        assertTrue("refusal negative: a started install keeps the row installing", h.last.installing)
        assertFalse("refusal negative: a started install sets no refusal flag", h.lastFlag())
        assertFalse("refusal negative: a started install is not a failure", h.last.failed)
    }

    @Test
    fun `failure positive another installer status draws failed and no refusal flag`() {
        for (code in listOf(1, 2, 4, 5)) {
            val h = Harness()
            offering(h)
            h.control.onInstall!!.invoke()
            h.wiring.onInstallFinished(code)
            assertTrue("failure positive: status $code draws failed", h.last.failed)
            assertFalse("failure positive: status $code frees the install row", h.last.installing)
            assertFalse("failure positive: status $code sets no refusal flag", h.lastFlag())
        }
    }

    @Test
    fun `failure positive a thrown install draws failed and no refusal flag`() {
        val h = Harness()
        h.checker.throwOnInstall = true
        offering(h)
        h.control.onInstall!!.invoke()
        assertEquals("failure positive thrown: the install ran once", 1, h.checker.installed.size)
        assertTrue("failure positive thrown: a thrown install draws failed", h.last.failed)
        assertFalse("failure positive thrown: a thrown install frees the install row", h.last.installing)
        assertFalse("failure positive thrown: a thrown install sets no refusal flag", h.lastFlag())
    }

    @Test
    fun `failure positive a failed start draws failed and no refusal flag`() {
        val h = Harness()
        offering(h)
        h.background.throwOnSubmit = true
        h.control.onInstall!!.invoke()
        assertEquals("failure positive start: the install never ran", 0, h.checker.installed.size)
        assertTrue("failure positive start: a failed start draws failed", h.last.failed)
        assertFalse("failure positive start: a failed start frees the install row", h.last.installing)
        assertFalse("failure positive start: a failed start sets no refusal flag", h.lastFlag())
    }

    @Test
    fun `cancel positive a cancel at the system installer draws neither failed nor refusal and offers the row again`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.reportFinished(3)
        assertFalse("cancel positive: a cancel shows no failure", h.last.failed)
        assertFalse("cancel positive: a cancel sets no refusal flag", h.lastFlag())
        assertTrue("cancel positive: no draw carried the refusal flag", h.flagsBeforeDraws().none { it })
        assertFalse("cancel positive: a cancel frees the install row", h.last.installing)
        assertTrue("cancel positive: a cancel offers the install row again", h.last.installReady)
    }

    @Test
    fun `cancel negative an installer status one is a failure and not a cancel`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.reportFinished(1)
        assertTrue("cancel negative: status one is drawn as a failure", h.last.failed)
        assertFalse("cancel negative: status one sets no refusal flag", h.lastFlag())
    }

    @Test
    fun `success draws nothing new and sets no refusal flag`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        val drawn: Int = h.control.calls.size
        h.answers.reportFinished(0)
        assertEquals("success: a successful finish draws nothing and sets nothing", drawn, h.control.calls.size)
        assertTrue("success: the row stays installing until the process is replaced", h.last.installing)
        assertFalse("success: a successful finish is not a failure", h.last.failed)
    }

    @Test
    fun `order every draw is preceded by exactly one set call that carries the flag held at that draw`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        assertSetBeforeEachDraw(h, listOf(false, false, false, false, true, false, false))
    }

    @Test
    fun `clear a new check clears the refusal flag and shows the up to date row`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_STARTED))
        assertTrue("clear new check: the refusal left the flag set", h.lastFlag())
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        assertFalse("clear new check: the new check's draw carries no refusal flag", h.lastFlag())
        assertTrue("clear new check: the result is up to date", h.last.upToDate)
        assertFalse("clear new check: the result shows no failure", h.last.failed)
    }

    @Test
    fun `clear a cancel after a refusal clears the refusal flag and offers the row again`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        h.control.onInstall!!.invoke()
        assertTrue("clear cancel: the second install is running", h.last.installing)
        h.answers.reportFinished(3)
        assertFalse("clear cancel: the cancel clears the refusal flag", h.lastFlag())
        assertFalse("clear cancel: the cancel shows no failure", h.last.failed)
        assertTrue("clear cancel: the row is offered again", h.last.installReady)
    }

    @Test
    fun `clear a later generic failure after a refusal shows failed, clears the refusal flag and never both`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        h.control.onInstall!!.invoke()
        h.wiring.onInstallFinished(1)
        assertTrue("clear generic: the later failure is drawn as failed", h.last.failed)
        assertFalse("clear generic: the later failure clears the refusal flag", h.lastFlag())
        assertNeverBoth(h)
    }

    @Test
    fun `clear a later refusal after a generic failure clears the failure and sets the refusal flag, never both`() {
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.wiring.onInstallFinished(2)
        assertTrue("clear refusal: the generic failure left failed set", h.last.failed)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        assertFalse("clear refusal: the later refusal clears the failure", h.last.failed)
        assertTrue("clear refusal: the later refusal sets the refusal flag", h.lastFlag())
        assertNeverBoth(h)
    }

    @Test
    fun `second tap after a refusal is accepted and the row stays offered, and no failure is drawn while it runs`() {
        // The refusal flag stays held while the second install runs; the About text puts installing first,
        // so the refusal line is not drawn then. This test checks the facts the control is given.
        val h = Harness()
        offering(h)
        h.control.onInstall!!.invoke()
        h.answers.report(InstallResult.Refused(InstallReasons.NOT_ALLOWED))
        assertTrue("second tap: the row stays offered after the refusal", h.last.installReady)
        h.control.onInstall!!.invoke()
        assertEquals("second tap: the second install was handed the release", 2, h.checker.installed.size)
        assertTrue("second tap: the second install is running", h.last.installing)
        assertFalse("second tap: no failure is drawn while it runs", h.last.failed)
        assertFalse("second tap: the row is not offered while the install runs", h.last.installReady)
    }
}
