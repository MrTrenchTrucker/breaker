package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.port.UpdateChecker
import dev.breaker.dictation.ui.AboutControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The About check wiring over fakes: a control that records every show, a background that runs the
 * submitted block at once, a main post that runs at once and a scripted checker. No Android type is
 * named, so the test runs on a plain JVM. The fake background drops a block that throws, as the real
 * one-shot worker drops one, so the catch around the checker is load-bearing.
 */
class AboutControlWiringTest {

    /** One `show` call's seven facts. */
    class Show(
        val admin: Boolean,
        val updateAvailable: Boolean,
        val installReady: Boolean,
        val checking: Boolean,
        val installing: Boolean,
        val failed: Boolean,
        val upToDate: Boolean,
    )

    /** A fake [AboutControl] that records every show and holds the two callbacks it is given. */
    private class RecordingControl : AboutControl {
        val shows: MutableList<Show> = ArrayList()
        var bound: Boolean = false
        var onCheck: (() -> Unit)? = null
        var onInstall: (() -> Unit)? = null

        override fun bind(onCheck: () -> Unit, onInstall: () -> Unit) {
            bound = true
            this.onCheck = onCheck
            this.onInstall = onInstall
        }

        override fun show(admin: Boolean, updateAvailable: Boolean, installReady: Boolean, checking: Boolean, installing: Boolean, failed: Boolean, upToDate: Boolean) {
            shows.add(Show(admin, updateAvailable, installReady, checking, installing, failed, upToDate))
        }

        override fun refresh() {}
    }

    /** Runs the submitted block at once; a block that throws is dropped, as the real worker drops one. */
    private class InlineBackground : Background {
        var submissions: Int = 0
        var hold: Boolean = false
        var throwOnSubmit: Boolean = false
        val held: MutableList<() -> Unit> = ArrayList()

        override fun submit(block: () -> Unit) {
            submissions += 1
            if (throwOnSubmit) throw IllegalStateException("the worker thread cannot start")
            if (hold) {
                held.add(block)
            } else {
                try {
                    block()
                } catch (e: Exception) {
                    // dropped, as the real worker drops a throwing block
                }
            }
        }

        fun releaseNext() {
            assertTrue("wiring: no held block to release", held.isNotEmpty())
            held.removeAt(0)()
        }
    }

    /** Runs every posted block at once on the calling thread. */
    private class InlineMainPost : MainPost {
        var posts: Int = 0

        override fun post(block: () -> Unit) {
            posts += 1
            block()
        }
    }

    /** A scripted [UpdateChecker]: the result it returns, or a throw, and the force flag it was given. */
    private class ScriptedChecker : UpdateChecker {
        var result: UpdateCheckResult? = null
        var throwOn: Boolean = false
        var calls: Int = 0
        var lastForce: Boolean? = null

        override fun check(force: Boolean): UpdateCheckResult? {
            calls += 1
            lastForce = force
            if (throwOn) throw IllegalStateException("the checker refused")
            return result
        }

        override fun install(release: ReleaseInfo) {}

        override fun rollback() {}
    }

    private class Harness {
        val control: RecordingControl = RecordingControl()
        val background: InlineBackground = InlineBackground()
        val main: InlineMainPost = InlineMainPost()
        val checker: ScriptedChecker = ScriptedChecker()
        var admin: Boolean = true
        val wiring: AboutControlWiring = AboutControlWiring(control, background, main, { checker }, { admin })

        val shows: List<Show> get() = control.shows
        val last: Show get() = control.shows.last()
    }

    private fun release(): ReleaseInfo = ReleaseInfo("9.9.9", "https://example.test/breaker.apk", "aa", "bb")

    @Test
    fun `nothing is drawn and no check starts before bind`() {
        val h = Harness()
        h.wiring.onCheck()
        assertEquals("nothing is drawn before bind", 0, h.shows.size)
        assertEquals("no check is submitted before bind", 0, h.background.submissions)
        assertEquals("the checker is not called before bind", 0, h.checker.calls)
        assertFalse("the control is not bound", h.control.bound)
    }

    @Test
    fun `bind registers the two callbacks and draws every fact false`() {
        val h = Harness()
        h.wiring.bind()
        assertTrue("bind holds the two callbacks", h.control.bound && h.control.onCheck != null && h.control.onInstall != null)
        assertEquals("bind draws exactly once", 1, h.shows.size)
        val first = h.last
        assertTrue("the admin fact is read at show time", first.admin)
        assertFalse("nothing is offered at the first draw", first.updateAvailable)
        assertFalse("nothing failed, is up to date or is checking at the first draw", first.failed || first.upToDate || first.checking)
        assertFalse("no unwired row at the first draw", first.installReady || first.installing)
    }

    @Test
    fun `a tap draws checking and then the up to date row`() {
        val h = Harness()
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.bind()
        h.wiring.onCheck()
        assertEquals("one check was submitted", 1, h.background.submissions)
        assertEquals("the checker was called once", 1, h.checker.calls)
        assertEquals("the check was forced", true, h.checker.lastForce == true)
        assertEquals("one result was posted back to the main thread", 1, h.main.posts)
        assertEquals("the tap drew three times: bind, checking and the result", 3, h.shows.size)
        assertTrue("the tap's draw says a check is running", h.shows[1].checking)
        assertFalse("the tap's draw offers nothing and reports no failure", h.shows[1].updateAvailable || h.shows[1].failed)
        val done = h.last
        assertFalse("the row is free again", done.checking)
        assertTrue("the row says up to date and nothing else", done.upToDate && !done.failed && !done.updateAvailable)
        assertFalse("no unwired row", done.installReady || done.installing)
    }

    @Test
    fun `a failed or null check draws the failed row`() {
        for (result in listOf(UpdateCheckResult.Failed("no network"), null)) {
            val h = Harness()
            h.checker.result = result
            h.wiring.bind()
            h.wiring.onCheck()
            val done = h.last
            assertFalse("the row is free again", done.checking)
            assertTrue("the row says the check failed", done.failed)
            assertFalse("a failure is not an offer and not up to date", done.updateAvailable || done.upToDate)
        }
    }

    @Test
    fun `an available check draws updateAvailable and no install row`() {
        val h = Harness()
        h.checker.result = UpdateCheckResult.Available(release())
        h.wiring.bind()
        h.wiring.onCheck()
        val done = h.last
        assertFalse("the row is free again", done.checking)
        assertTrue("the row says an update is available", done.updateAvailable)
        assertFalse("the install row is not offered before the install path lands", done.installReady)
        assertFalse("an available update is not a failure and not up to date", done.failed || done.upToDate)
    }

    @Test
    fun `a second tap while a check runs starts no second check`() {
        val h = Harness()
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.bind()
        h.background.hold = true
        h.wiring.onCheck()
        assertEquals("the first tap submitted one check", 1, h.background.submissions)
        h.wiring.onCheck()
        assertEquals("the second tap submitted nothing", 1, h.background.submissions)
        assertEquals("the checker never ran while the block was held", 0, h.checker.calls)
        assertTrue("the row still says a check is running", h.last.checking)
        h.background.releaseNext()
        assertFalse("the row is free once the check settles and its result is drawn", h.last.checking || !h.last.upToDate)
    }

    @Test
    fun `a throwing checker lands on failed and frees the row, and a second tap starts a new check`() {
        val h = Harness()
        h.checker.throwOn = true
        h.wiring.bind()
        h.wiring.onCheck()
        assertEquals("the throwing check was submitted once", 1, h.background.submissions)
        val failed = h.last
        assertFalse("the row is freed after a throwing checker", failed.checking)
        assertTrue("a throwing checker is drawn as a failure", failed.failed)
        assertFalse("a throwing checker offers nothing and is not up to date", failed.updateAvailable || failed.upToDate)

        h.checker.throwOn = false
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        assertEquals("a second tap after the failure starts a new check", 2, h.checker.calls)
        assertEquals("a second tap submits a second check", 2, h.background.submissions)
        val retried = h.last
        assertFalse("the retry frees the row", retried.checking)
        assertTrue("the retry draws up to date and clears the failure", retried.upToDate && !retried.failed && !retried.updateAvailable)
    }

    @Test
    fun `a submit that throws lands on failed and clears checking`() {
        val h = Harness()
        h.background.throwOnSubmit = true
        h.wiring.bind()
        h.wiring.onCheck()
        assertEquals("the submit was attempted once", 1, h.background.submissions)
        assertEquals("bind drew once and the tap drew twice: checking then the failure", 3, h.shows.size)
        assertTrue("the tap's draw says a check is running", h.shows[1].checking)
        val done = h.last
        assertFalse("the row is freed when the submit throws", done.checking)
        assertTrue("a refused submit is drawn as a failure", done.failed)
        assertEquals("the checker was never reached and nothing was posted back", 0, h.checker.calls + h.main.posts)
    }

    @Test
    fun `admin follows isAdmin on every show`() {
        val h = Harness()
        h.admin = true
        h.wiring.bind()
        assertTrue("the first draw reads admin true", h.shows[0].admin)
        h.admin = false
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        assertFalse("the checking draw and the result draw read admin false", h.shows[1].admin || h.shows[2].admin)
    }

    @Test
    fun `a following up to date clears updateAvailable`() {
        val h = Harness()
        h.wiring.bind()
        h.checker.result = UpdateCheckResult.Available(release())
        h.wiring.onCheck()
        assertTrue("the first check offers an update", h.last.updateAvailable)
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        assertFalse("the second check clears the offer", h.last.updateAvailable)
        assertTrue("the second check says up to date and did not fail", h.last.upToDate && !h.last.failed)
    }

    @Test
    fun `a new tap clears updateAvailable while checking`() {
        val h = Harness()
        h.wiring.bind()
        h.checker.result = UpdateCheckResult.Available(release())
        h.wiring.onCheck()
        assertTrue("the first check offers an update", h.last.updateAvailable)
        h.checker.result = UpdateCheckResult.UpToDate("1.0.0")
        h.wiring.onCheck()
        val checking = h.shows[h.shows.size - 2]
        assertTrue("the new tap's draw says a check is running", checking.checking)
        assertFalse("the new tap's draw clears the earlier offer and offers no install row", checking.updateAvailable || checking.installReady)
    }

    @Test
    fun `the install callback is held and does nothing`() {
        val h = Harness()
        h.wiring.bind()
        h.control.onInstall!!.invoke()
        assertEquals("the install callback draws nothing", 1, h.shows.size)
        assertEquals("the install callback submits no check", 0, h.background.submissions)
    }
}
