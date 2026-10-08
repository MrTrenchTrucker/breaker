package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The commit service on top of the real hop, with a poster and a deadline that
 * the test steers by hand.
 *
 * The probe counts a seam call as outside the hop whenever the hop is not the
 * inline test double, so its list is only read where the block never started.
 */
internal class PostedMainThreadServiceTest {

    /**
     * A hop call that does not return leaves the test thread blocked inside the call, so no
     * assertion can run. This limit turns that into a failure of the named test. It is a
     * safety net, never what a test measures: every test here finishes in a small fraction
     * of it, on one core as in parallel.
     */
    @get:Rule
    val hopTimeLimit: Timeout = Timeout.seconds(10)

    private val notResponding: String = "The screen was not responding, so the text was not sent."

    private fun serviceOn(rig: Rig, hop: PostedMainThread): CommitService =
        CommitService(rig.focus, rig.clipboard, rig.notice, hop, 32)

    private fun assertNothingTouched(rig: Rig, moment: String) {
        assertEquals("commit: the focus was read $moment", 0, rig.focus.reads)
        assertTrue("commit: the field received text $moment: ${rig.field.received.size}", rig.field.received.isEmpty())
        assertTrue("commit: the clipboard was written $moment: ${rig.clipboard.writes.size}", rig.clipboard.writes.isEmpty())
        assertEquals("commit: the notice was shown $moment", 0, rig.notice.shown)
        assertTrue(
            "commit: a platform call was made outside the hop $moment: ${rig.probe.outsideCalls}",
            rig.probe.outsideCalls.isEmpty(),
        )
    }

    @Test
    fun `a deadline that wins fails as not responding and the late task touches nothing`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val poster = ManualPoster()
        val deadline = FiredDeadline()

        val result: CommitOutcomeResult = serviceOn(rig, PostedMainThread(poster, { false }, deadline)).commit(Texts.request())

        assertEquals("commit: a passed deadline must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertFalse("commit: the detail must not hold the text", (result.detail ?: "").contains(Texts.SECRET))
        assertEquals("commit: the task must still be waiting in the main thread", 1, poster.tasks.size)
        assertEquals("commit: the deadline must have been awaited once", 1, deadline.awaited)
        assertNothingTouched(rig, "before the late task ran")

        poster.runStored()

        assertNothingTouched(rig, "after the late task ran")
    }

    @Test
    fun `a block that ran before the deadline reports a committed text`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val deadline = FiredDeadline()

        val result: CommitOutcomeResult =
            serviceOn(rig, PostedMainThread(InlinePoster(), { false }, deadline)).commit(Texts.request())

        assertEquals("commit: a block that ran must report COMMITTED", CommitOutcome.COMMITTED, result.outcome)
        assertNull("commit: a clean commit must carry no detail", result.detail)
        assertEquals("commit: the field must receive the text once", listOf(Texts.SECRET), rig.field.received)
        assertTrue("commit: nothing may be copied after an accepted commit", rig.clipboard.writes.isEmpty())
        assertEquals("commit: no notice after an accepted commit", 0, rig.notice.shown)
        assertEquals("commit: the passed deadline must have been awaited, so it really lost the race", 1, deadline.awaited)
    }

    @Test
    fun `a block that ran before the deadline with no field reports a sensitive copy`() {
        val rig = Rig(sdkInt = 32, withField = false)
        val deadline = FiredDeadline()

        val result: CommitOutcomeResult =
            serviceOn(rig, PostedMainThread(InlinePoster(), { false }, deadline)).commit(Texts.request())

        assertEquals("commit: a block that ran must report COPIED", CommitOutcome.COPIED, result.outcome)
        assertNull("commit: a clean copy must carry no detail", result.detail)
        assertEquals("commit: the clipboard must get the text once, marked sensitive", listOf(Pair(Texts.SECRET, true)), rig.clipboard.writes)
        assertEquals("commit: the notice must be shown once on sdk 32", 1, rig.notice.shown)
        assertEquals("commit: the passed deadline must have been awaited, so it really lost the race", 1, deadline.awaited)
    }

    @Test
    fun `a refused post fails as not responding and touches nothing`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val poster = RefusingPoster()
        val deadline = ForbiddenDeadline()

        val result: CommitOutcomeResult = serviceOn(rig, PostedMainThread(poster, { false }, deadline)).commit(Texts.request())

        assertEquals("commit: a refused post must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertEquals("commit: the post must have been tried once", 1, poster.posts)
        assertEquals("commit: a refused post must not await the deadline", 0, deadline.awaited)
        assertNothingTouched(rig, "after a refused post")
    }

    @Test
    fun `an interrupted wait fails as not responding, keeps the flag and touches nothing`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val poster = InterruptingPoster()
        val service: CommitService = serviceOn(rig, PostedMainThread(poster, { false }, NeverDeadline()))

        val (result: CommitOutcomeResult, flag: Boolean) = withFlag { service.commit(Texts.request()) }
        poster.runStored()

        assertEquals("commit: an interrupted wait must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertTrue("commit: the interrupt flag must be set after an interrupted wait", flag)
        assertNothingTouched(rig, "after the late task ran")
    }
}
