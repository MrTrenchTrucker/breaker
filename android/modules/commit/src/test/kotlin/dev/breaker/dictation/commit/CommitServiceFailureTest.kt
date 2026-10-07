package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What happens when the field, the clipboard or the notice does not do its part. */
internal class CommitServiceFailureTest {

    private class Fatal : Error()

    private class ThrowingField : FocusedField {
        override fun commitText(text: String): FieldCommit = throw Fatal()
    }

    private val refusalDetail = "The field did not accept the text, so it was copied instead."
    private val nowhereDetail = "The text could not be put anywhere."

    private fun assertCopiedAfterRefusal(rig: Rig, result: CommitOutcomeResult) {
        assertEquals("commit: a refused text must end as COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: the refusal detail must be the exact sentence", refusalDetail, result.detail)
        assertEquals("commit: the clipboard must be written once", 1, rig.clipboard.writes.size)
        assertEquals("commit: the clipboard must get the text", Texts.SECRET, rig.clipboard.writes[0].first)
        assertEquals("commit: the field must be called once", 1, rig.field.received.size)
    }

    private fun assertFailedNowhere(result: CommitOutcomeResult) {
        assertEquals("commit: nowhere to put the text must be FAILED", CommitOutcome.FAILED, result.outcome)
        assertEquals("commit: the nowhere detail must be the exact sentence", nowhereDetail, result.detail)
    }

    private fun assertFatalEscapes(label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Fatal) {
            return
        }
        fail("commit: an Error from $label must not be contained")
    }

    @Test
    fun `a field that refuses sends the text to the clipboard with the refusal detail`() {
        val rig = Rig()
        rig.field.answer = FieldCommit.REFUSED

        val result = rig.service.commit(Texts.request())

        assertCopiedAfterRefusal(rig, result)
    }

    @Test
    fun `a field that throws counts as a refusal`() {
        val rig = Rig()
        rig.field.failure = IllegalStateException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertCopiedAfterRefusal(rig, result)
    }

    @Test
    fun `a field that throws a checked exception counts as a refusal too`() {
        val rig = Rig()
        rig.field.failure = IOException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertCopiedAfterRefusal(rig, result)
    }

    @Test
    fun `a clipboard that says no gives FAILED with the nowhere detail`() {
        val rig = Rig(withField = false)
        rig.clipboard.result = false

        val result = rig.service.commit(Texts.request())

        assertFailedNowhere(result)
        assertEquals("commit: the clipboard must have been tried once", 1, rig.clipboard.writes.size)
    }

    @Test
    fun `a clipboard that throws gives FAILED with the nowhere detail`() {
        val rig = Rig(withField = false)
        rig.clipboard.failure = IllegalStateException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertFailedNowhere(result)
    }

    @Test
    fun `a field that refuses and a clipboard that says no is FAILED, not COPIED`() {
        val rig = Rig()
        rig.field.answer = FieldCommit.REFUSED
        rig.clipboard.result = false

        val result = rig.service.commit(Texts.request())

        assertFailedNowhere(result)
        assertEquals("commit: the field was tried once", 1, rig.field.received.size)
        assertEquals("commit: the clipboard was tried once", 1, rig.clipboard.writes.size)
    }

    @Test
    fun `a field that throws and a clipboard that throws is FAILED`() {
        val rig = Rig()
        rig.field.failure = IllegalStateException(Texts.LEAK_MARKER)
        rig.clipboard.failure = IllegalStateException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertFailedNowhere(result)
    }

    @Test
    fun `a notice that throws leaves a plain copy as COPIED`() {
        val rig = Rig(sdkInt = 32, withField = false)
        rig.notice.failure = IllegalStateException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertEquals("commit: a failing notice must not change the outcome", CommitOutcome.COPIED, result.outcome)
        assertNull("commit: a failing notice adds no detail to a plain copy", result.detail)
        assertEquals("commit: the notice was tried once", 1, rig.notice.shown)
        assertEquals("commit: the text is on the clipboard", 1, rig.clipboard.writes.size)
    }

    @Test
    fun `a notice that throws leaves a copy after a refusal as COPIED with the refusal detail`() {
        val rig = Rig(sdkInt = 32)
        rig.field.answer = FieldCommit.REFUSED
        rig.notice.failure = IllegalStateException(Texts.LEAK_MARKER)

        val result = rig.service.commit(Texts.request())

        assertCopiedAfterRefusal(rig, result)
    }

    @Test
    fun `the notice is never called when the copy failed`() {
        val refusedAndNo = Rig(sdkInt = 32)
        refusedAndNo.field.answer = FieldCommit.REFUSED
        refusedAndNo.clipboard.result = false
        val noField = Rig(sdkInt = 32, withField = false)
        noField.clipboard.failure = IllegalStateException(Texts.LEAK_MARKER)

        refusedAndNo.service.commit(Texts.request())
        noField.service.commit(Texts.request())

        assertEquals("commit: no notice after a clipboard that said no", 0, refusedAndNo.notice.shown)
        assertEquals("commit: no notice after a clipboard that threw", 0, noField.notice.shown)
    }

    @Test
    fun `an Error from the field is not contained and the clipboard stays untouched`() {
        val rig = Rig()
        val service = CommitService(
            focus = FocusedFieldSource { ThrowingField() },
            clipboard = rig.clipboard,
            notice = rig.notice,
            mainThread = rig.hop,
            sdkInt = 32,
        )

        assertFatalEscapes("the field") { service.commit(Texts.request()) }

        assertTrue("commit: nothing may be copied after an Error", rig.clipboard.writes.isEmpty())
    }

    @Test
    fun `an Error from the clipboard is not contained`() {
        val rig = Rig(withField = false)
        val service = CommitService(
            focus = rig.focus,
            clipboard = ClipboardWriter { _, _ -> throw Fatal() },
            notice = rig.notice,
            mainThread = rig.hop,
            sdkInt = 32,
        )

        assertFatalEscapes("the clipboard") { service.commit(Texts.request()) }
    }

    @Test
    fun `an Error from the notice is not contained`() {
        val rig = Rig(sdkInt = 32, withField = false)
        val service = CommitService(
            focus = rig.focus,
            clipboard = rig.clipboard,
            notice = UserNotice { throw Fatal() },
            mainThread = rig.hop,
            sdkInt = 32,
        )

        assertFatalEscapes("the notice") { service.commit(Texts.request()) }
    }

    @Test
    fun `an Error from the main thread hop is not contained`() {
        val rig = Rig()
        val failing = object : MainThread {
            override fun <T> call(block: () -> T): T = throw Fatal()
        }
        val service = rig.serviceWith(failing)

        assertFatalEscapes("the hop") { service.commit(Texts.request()) }
    }
}
