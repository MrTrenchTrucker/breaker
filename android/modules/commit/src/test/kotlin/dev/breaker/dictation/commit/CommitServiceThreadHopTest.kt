package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every platform call of one commit happens inside exactly one main-thread hop.
 *
 * Each test also checks that the seam it cares about was really called, so an
 * empty "outside" list can never come from a path that touched nothing.
 */
internal class CommitServiceThreadHopTest {

    private fun assertOneHopOnly(rig: Rig, what: String) {
        assertEquals("commit: $what must use exactly one main-thread hop", 1, rig.hop.calls)
        assertTrue(
            "commit: $what made a platform call outside the hop: ${rig.probe.outsideCalls}",
            rig.probe.outsideCalls.isEmpty(),
        )
    }

    @Test
    fun `a text typed into the field uses one hop and keeps every platform call inside it`() {
        val rig = Rig(sdkInt = 32, withField = true)

        val result: CommitOutcomeResult = rig.service.commit(Texts.request())

        assertEquals("commit: an accepting field must give COMMITTED", CommitOutcome.COMMITTED, result.outcome)
        assertEquals("commit: the focus must be read once", 1, rig.focus.reads)
        assertEquals("commit: the field must get the text once", 1, rig.field.received.size)
        assertOneHopOnly(rig, "a committed text")
    }

    @Test
    fun `a text copied because no field is focused uses one hop and keeps every platform call inside it`() {
        val rig = Rig(sdkInt = 32, withField = false)

        val result: CommitOutcomeResult = rig.service.commit(Texts.request())

        assertEquals("commit: no field must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: the focus must be read once", 1, rig.focus.reads)
        assertEquals("commit: the clipboard must be written once", 1, rig.clipboard.writes.size)
        assertEquals("commit: the notice must be shown once on sdk 32", 1, rig.notice.shown)
        assertOneHopOnly(rig, "a copied text")
    }

    @Test
    fun `a text copied after the field refused uses one hop and keeps every platform call inside it`() {
        val rig = Rig(sdkInt = 32, withField = true)
        rig.field.answer = FieldCommit.REFUSED

        val result: CommitOutcomeResult = rig.service.commit(Texts.request())

        assertEquals("commit: a refusing field must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: the field must be asked once", 1, rig.field.received.size)
        assertEquals("commit: the clipboard must be written once", 1, rig.clipboard.writes.size)
        assertEquals("commit: the notice must be shown once on sdk 32", 1, rig.notice.shown)
        assertOneHopOnly(rig, "a copy after a refusal")
    }

    @Test
    fun `a text copied after the field threw uses one hop and keeps every platform call inside it`() {
        val rig = Rig(sdkInt = 32, withField = true)
        rig.field.failure = IllegalStateException("test: field broke")

        val result: CommitOutcomeResult = rig.service.commit(Texts.request())

        assertEquals("commit: a throwing field must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: the field must be asked once", 1, rig.field.received.size)
        assertEquals("commit: the clipboard must be written once", 1, rig.clipboard.writes.size)
        assertOneHopOnly(rig, "a copy after a field failure")
    }

    @Test
    fun `a text that went nowhere uses one hop and keeps every platform call inside it`() {
        val rig = Rig(sdkInt = 32, withField = false)
        rig.clipboard.result = false

        val result: CommitOutcomeResult = rig.service.commit(Texts.request())

        assertEquals("commit: an unavailable clipboard must give FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the clipboard must be tried once", 1, rig.clipboard.writes.size)
        assertEquals("commit: no notice may be shown for a failure", 0, rig.notice.shown)
        assertOneHopOnly(rig, "a failed commit")
    }

    @Test
    fun `two commits on one service use one hop each`() {
        val rig = Rig(sdkInt = 32, withField = true)

        rig.service.commit(Texts.request("first text"))
        rig.service.commit(Texts.request("second text"))

        assertEquals("commit: two commits must use two hops in all", 2, rig.hop.calls)
        assertTrue(
            "commit: a platform call was made outside the hop: ${rig.probe.outsideCalls}",
            rig.probe.outsideCalls.isEmpty(),
        )
    }
}
