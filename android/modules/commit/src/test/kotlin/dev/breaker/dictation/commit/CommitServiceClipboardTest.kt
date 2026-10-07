package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** With no focused field the text goes to the clipboard, marked sensitive. */
internal class CommitServiceClipboardTest {

    @Test
    fun `with no focused field the text is copied and the outcome carries no detail`() {
        val rig = Rig(sdkInt = 32, withField = false)

        val result = rig.service.commit(Texts.request())

        assertEquals("commit: no field must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertNull("commit: a plain copy has no detail", result.detail)
        assertEquals("commit: exactly one clipboard write", 1, rig.clipboard.writes.size)
    }

    @Test
    fun `the copy is marked sensitive`() {
        val rig = Rig(withField = false)

        rig.service.commit(Texts.request())

        assertEquals(
            "commit: the clipboard must get the exact text with the sensitive flag set",
            listOf(Pair(Texts.SECRET, true)),
            rig.clipboard.writes,
        )
        assertTrue("commit: the sensitive argument must be true", rig.clipboard.writes[0].second)
    }

    @Test
    fun `the copied text is exactly the text given, with line breaks, trailing spaces and an accented letter`() {
        val rig = Rig(withField = false)
        val text = "first line  \nsecond line \ncaf\u00e9 \n"

        rig.service.commit(Texts.request(text))

        assertEquals("commit: the clipboard must get the unchanged text", listOf(Pair(text, true)), rig.clipboard.writes)
    }

    @Test
    fun `with no focused field no field is called and the focus source is read once inside the hop`() {
        val rig = Rig(withField = false)

        rig.service.commit(Texts.request())

        assertTrue("commit: the field must never be called when there is none", rig.field.received.isEmpty())
        assertEquals("commit: the focus source must be read once", 1, rig.focus.reads)
        assertTrue(
            "commit: no seam may be called outside the hop, saw ${rig.probe.outsideCalls}",
            rig.probe.outsideCalls.isEmpty(),
        )
    }

    @Test
    fun `on the last version without a system confirmation the user gets our notice once`() {
        val rig = Rig(sdkInt = 32, withField = false)

        rig.service.commit(Texts.request())

        assertEquals("commit: sdk 32 must show our notice exactly once", 1, rig.notice.shown)
    }

    @Test
    fun `on the first version with a system confirmation there is no notice of ours`() {
        val rig = Rig(sdkInt = 33, withField = false)

        val result = rig.service.commit(Texts.request())

        assertEquals("commit: sdk 33 must still copy", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: sdk 33 must show no notice", 0, rig.notice.shown)
        assertEquals("commit: sdk 33 must still write the clipboard once", 1, rig.clipboard.writes.size)
    }

    @Test
    fun `on an old version the user gets our notice once`() {
        val rig = Rig(sdkInt = 26, withField = false)

        rig.service.commit(Texts.request())

        assertEquals("commit: an old sdk must show our notice exactly once", 1, rig.notice.shown)
    }

    @Test
    fun `on a newer version there is no notice of ours`() {
        val rig = Rig(sdkInt = 36, withField = false)

        rig.service.commit(Texts.request())

        assertEquals("commit: a newer sdk must show no notice", 0, rig.notice.shown)
    }

    @Test
    fun `a clipboard commit makes a single hop and calls nothing outside it`() {
        val rig = Rig(withField = false)

        rig.service.commit(Texts.request())

        assertEquals("commit: exactly one hop", 1, rig.hop.calls)
        assertTrue("commit: nothing outside the hop, saw ${rig.probe.outsideCalls}", rig.probe.outsideCalls.isEmpty())
    }

    @Test
    fun `a copy after a refusal is also marked sensitive`() {
        val rig = Rig(sdkInt = 32)
        rig.field.answer = FieldCommit.REFUSED

        val result = rig.service.commit(Texts.request())

        assertEquals("commit: a refusal must still end as COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals(
            "commit: the copy after a refusal must carry the sensitive flag",
            listOf(Pair(Texts.SECRET, true)),
            rig.clipboard.writes,
        )
    }

    @Test
    fun `a copy after a field that threw is also marked sensitive`() {
        val rig = Rig(sdkInt = 32)
        rig.field.failure = IllegalStateException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertEquals("commit: a throwing field must still end as COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals(
            "commit: the copy after a throwing field must carry the sensitive flag",
            listOf(Pair(Texts.SECRET, true)),
            rig.clipboard.writes,
        )
    }

    @Test
    fun `the notice is shown only after the clipboard was written`() {
        val rig = Rig(sdkInt = 32, withField = false)
        val seen = ArrayList<Int>()
        val service = CommitService(
            rig.focus,
            rig.clipboard,
            UserNotice { seen.add(rig.clipboard.writes.size) },
            rig.hop,
            32,
        )

        service.commit(Texts.request())

        assertEquals("commit: the clipboard must hold one write when the notice is shown", listOf(1), seen)
    }
}
