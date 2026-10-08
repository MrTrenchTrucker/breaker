package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An interrupted seam is contained like any other failure, and the interrupt
 * flag goes back to the calling thread.
 *
 * Every test clears the flag in a finally block, so no other test can see it.
 */
internal class CommitServiceInterruptTest {

    private val refusalDetail: String = "The field did not accept the text, so it was copied instead."
    private val nowhereDetail: String = "The text could not be put anywhere."
    private val notResponding: String = "The screen was not responding, so the text was not sent."

    /** Runs [block] with a clean flag; returns its value and the flag as the block left it. */
    private fun <T> runReadingFlag(block: () -> T): Pair<T, Boolean> {
        Thread.interrupted()
        try {
            val value: T = block()
            return Pair(value, Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interrupted field falls back to the clipboard and re-arms the flag`() {
        val rig = Rig(sdkInt = 32, withField = true)
        rig.field.failure = InterruptedException("test: field interrupted")

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: an interrupted field must still give COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: an interrupted field counts as a refusal", refusalDetail, result.detail)
        assertEquals("commit: the clipboard must still be written once", 1, rig.clipboard.writes.size)
        assertTrue("commit: the interrupt flag must be set after an interrupted field", flag)
    }

    @Test
    fun `an interrupted clipboard fails the commit and re-arms the flag`() {
        val rig = Rig(sdkInt = 32, withField = false)
        rig.clipboard.failure = InterruptedException("test: clipboard interrupted")

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: an interrupted clipboard must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must say nothing took the text", nowhereDetail, result.detail)
        assertEquals("commit: no notice may follow a failed copy", 0, rig.notice.shown)
        assertTrue("commit: the interrupt flag must be set after an interrupted clipboard", flag)
    }

    @Test
    fun `an interrupted notice keeps the copy and re-arms the flag`() {
        val rig = Rig(sdkInt = 32, withField = false)
        rig.notice.failure = InterruptedException("test: notice interrupted")

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: an interrupted notice must still give COPIED", CommitOutcome.COPIED, result.outcome)
        assertNull("commit: a clean copy must carry no detail", result.detail)
        assertEquals("commit: the clipboard must have been written once", 1, rig.clipboard.writes.size)
        assertEquals("commit: the notice must have been tried once", 1, rig.notice.shown)
        assertTrue("commit: the interrupt flag must be set after an interrupted notice", flag)
    }

    @Test
    fun `an interrupted hop fails as not responding and re-arms the flag`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val hop = FailingMainThread(InterruptedException("test: hop interrupted"))

        val (result: CommitOutcomeResult, flag: Boolean) =
            runReadingFlag { rig.serviceWith(hop, 32).commit(Texts.request()) }

        assertEquals("commit: an interrupted hop must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the detail must be the constant", notResponding, result.detail)
        assertTrue("commit: the interrupt flag must be set after an interrupted hop", flag)
        assertTrue("commit: nothing may be touched by an interrupted hop", rig.field.received.isEmpty())
    }

    @Test
    fun `a committed text leaves the flag clear`() {
        val rig = Rig(sdkInt = 32, withField = true)

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: an accepting field must give COMMITTED", CommitOutcome.COMMITTED, result.outcome)
        assertFalse("commit: a clean commit must not set the interrupt flag", flag)
    }

    @Test
    fun `a clean copy leaves the flag clear`() {
        val rig = Rig(sdkInt = 32, withField = false)

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: no field must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertFalse("commit: a clean copy must not set the interrupt flag", flag)
    }

    @Test
    fun `a failed copy leaves the flag clear`() {
        val rig = Rig(sdkInt = 32, withField = false)
        rig.clipboard.result = false

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: an unavailable clipboard must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertFalse("commit: a plain failure must not set the interrupt flag", flag)
    }

    @Test
    fun `seams that throw other exceptions leave the flag clear`() {
        val rig = Rig(sdkInt = 32, withField = true)
        rig.field.failure = IllegalStateException("test: field broke")
        rig.notice.failure = IllegalStateException("test: notice broke")

        val (result: CommitOutcomeResult, flag: Boolean) = runReadingFlag { rig.service.commit(Texts.request()) }

        assertEquals("commit: other exceptions must still give COPIED", CommitOutcome.COPIED, result.outcome)
        assertFalse("commit: a non-interrupt exception must not set the interrupt flag", flag)
    }

    @Test
    fun `a hop that throws another exception leaves the flag clear`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val hop = FailingMainThread(MainThreadUnavailable("test: no main thread"))

        val (result: CommitOutcomeResult, flag: Boolean) =
            runReadingFlag { rig.serviceWith(hop, 32).commit(Texts.request()) }

        assertEquals("commit: an unavailable hop must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertFalse("commit: an unavailable hop must not set the interrupt flag", flag)
    }
}
