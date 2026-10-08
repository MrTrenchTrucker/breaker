package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text goes into the focused field (ADR-022), and nowhere else. */
internal class CommitServiceFocusedFieldTest {

    private fun commitInto(rig: Rig, text: String): CommitOutcomeResult =
        rig.service.commit(Texts.request(text))

    private fun assertTypedAndNothingElse(rig: Rig, text: String, result: CommitOutcomeResult) {
        assertEquals("commit: a field that accepts must give COMMITTED", CommitOutcome.COMMITTED, result.outcome)
        assertNull("commit: a committed text has no detail", result.detail)
        assertEquals("commit: the field must get exactly one call with the exact text", listOf(text), rig.field.received)
        assertTrue("commit: the clipboard must stay untouched when the field accepted", rig.clipboard.writes.isEmpty())
        assertEquals("commit: no notice when the field accepted", 0, rig.notice.shown)
    }

    @Test
    fun `a focused field that accepts the text is committed and the clipboard is not written`() {
        val rig = Rig(sdkInt = 32)

        val result = commitInto(rig, Texts.SECRET)

        assertTypedAndNothingElse(rig, Texts.SECRET, result)
    }

    @Test
    fun `a focused field that accepts is committed with no notice on the last version that would show one`() {
        val rig = Rig(sdkInt = 32)

        commitInto(rig, Texts.SECRET)

        assertEquals("commit: sdk 32 with an accepting field must show no notice", 0, rig.notice.shown)
        assertTrue("commit: sdk 32 with an accepting field must not touch the clipboard", rig.clipboard.writes.isEmpty())
    }

    @Test
    fun `a focused field that accepts is committed the same way on the first version with a system confirmation`() {
        val rig = Rig(sdkInt = 33)

        val result = commitInto(rig, Texts.SECRET)

        assertTypedAndNothingElse(rig, Texts.SECRET, result)
    }

    @Test
    fun `the field gets the text exactly as given, with line breaks, trailing spaces and an accented letter`() {
        val rig = Rig()
        val text = "first line  \nsecond line \ncaf\u00e9 \n"

        val result = commitInto(rig, text)

        assertTypedAndNothingElse(rig, text, result)
        assertEquals("commit: the text must reach the field unchanged", text, rig.field.received[0])
    }

    @Test
    fun `the focus source is read once and the field is called inside the main thread hop`() {
        val rig = Rig()

        commitInto(rig, Texts.SECRET)

        assertEquals("commit: the focus source must be read exactly once", 1, rig.focus.reads)
        assertEquals("commit: exactly one main thread hop per commit", 1, rig.hop.calls)
        assertTrue(
            "commit: no seam may be called outside the hop, saw ${rig.probe.outsideCalls}",
            rig.probe.outsideCalls.isEmpty(),
        )
        assertEquals("commit: the field must have been called", 1, rig.field.received.size)
    }

    @Test
    fun `each commit gives the field its own text and reads the focus source again`() {
        val rig = Rig()

        commitInto(rig, "one")
        commitInto(rig, "two")

        assertEquals("commit: each commit gives the field its own text, in order", listOf("one", "two"), rig.field.received)
        assertEquals("commit: each commit reads the focus source once", 2, rig.focus.reads)
        assertTrue("commit: the clipboard must stay untouched for both", rig.clipboard.writes.isEmpty())
    }
}
