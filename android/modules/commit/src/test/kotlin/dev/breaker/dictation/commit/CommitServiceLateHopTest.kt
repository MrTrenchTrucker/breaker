package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hop that gave up must leave the main thread nothing to do when it finally
 * gets to the block: the field, clipboard and notice all stay untouched.
 */
internal class CommitServiceLateHopTest {

    private val notResponding: String = "The screen was not responding, so the text was not sent."

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
    fun `a late hop with a focused field fails as not responding and the late block types nothing`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val late = LateMainThread(rig.probe)

        val result: CommitOutcomeResult = rig.serviceWith(late, 32).commit(Texts.request())

        assertEquals("commit: a late hop must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the late-hop detail must be the constant", notResponding, result.detail)
        assertEquals("commit: the block must have been handed to the hop once", 1, late.pending.size)
        assertNothingTouched(rig, "before the late block ran")

        late.runLate()

        assertNothingTouched(rig, "after the late block ran")
    }

    @Test
    fun `a late hop with no field fails as not responding and the late block copies nothing`() {
        val rig = Rig(sdkInt = 32, withField = false)
        val late = LateMainThread(rig.probe)

        val result: CommitOutcomeResult = rig.serviceWith(late, 32).commit(Texts.request())

        assertEquals("commit: a late hop must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the late-hop detail must be the constant", notResponding, result.detail)
        assertEquals("commit: the block must have been handed to the hop once", 1, late.pending.size)
        assertNothingTouched(rig, "before the late block ran")

        late.runLate()

        assertNothingTouched(rig, "after the late block ran")
    }

    @Test
    fun `a late block on an old sdk shows no notice`() {
        val rig = Rig(sdkInt = 30, withField = false)
        val late = LateMainThread(rig.probe)

        rig.serviceWith(late, 30).commit(Texts.request())
        late.runLate()

        assertEquals("commit: no notice may be shown by a late block on sdk 30", 0, rig.notice.shown)
        assertTrue("commit: the late block must not copy", rig.clipboard.writes.isEmpty())
    }

    @Test
    fun `an unavailable main thread fails as not responding and touches nothing`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val hop = FailingMainThread(MainThreadUnavailable("test: no main thread"))

        val result: CommitOutcomeResult = rig.serviceWith(hop, 32).commit(Texts.request())

        assertEquals("commit: an unavailable main thread must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertNothingTouched(rig, "when the main thread was unavailable")
    }

    @Test
    fun `a hop that throws a message with the marker still gives the constant detail`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val hop = FailingMainThread(RuntimeException(Texts.LEAK_MARKER))

        val result: CommitOutcomeResult = rig.serviceWith(hop, 32).commit(Texts.request())

        assertEquals("commit: a failing hop must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertFalse(
            "commit: the exception message reached the detail",
            (result.detail ?: "").contains(Texts.LEAK_MARKER),
        )
        assertFalse(
            "commit: the exception message reached the result text",
            result.toString().contains(Texts.LEAK_MARKER),
        )
        assertNothingTouched(rig, "when the hop failed")
    }

    @Test
    fun `a hop that fails with an error type that is not a runtime exception still gives the constant detail`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val hop = FailingMainThread(java.io.IOException(Texts.LEAK_MARKER))

        val result: CommitOutcomeResult = rig.serviceWith(hop, 32).commit(Texts.request())

        assertEquals("commit: a failing hop must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertNothingTouched(rig, "when the hop failed")
    }

    @Test
    fun `a hop that ran normally gives no not-responding detail`() {
        val rig = Rig(sdkInt = 32, withField = true)

        val result: CommitOutcomeResult = rig.service.commit(Texts.request())

        assertEquals("commit: a normal hop must give COMMITTED", CommitOutcome.COMMITTED, result.outcome)
        assertNull("commit: a committed text must carry no detail", result.detail)
    }
}
