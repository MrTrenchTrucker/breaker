package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.CommitRequest
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** No detail, string form or exception message of a commit may carry the text. */
internal class CommitServiceRedactionTest {

    private val refusalDetail = "The field did not accept the text, so it was copied instead."
    private val nowhereDetail = "The text could not be put anywhere."
    private val notRespondingDetail = "The screen was not responding, so the text was not sent."

    private fun assertRedacted(
        request: CommitRequest,
        result: CommitOutcomeResult,
        outcome: CommitOutcome,
        detail: String?,
    ) {
        assertEquals("commit: wrong outcome", outcome, result.outcome)
        assertEquals("commit: the detail must be exactly its fixed sentence", detail, result.detail)
        val shown: List<String> = listOf(result.detail ?: "", result.toString(), request.toString())
        for (candidate in shown) {
            assertFalse("commit: the text leaked into: $candidate", candidate.contains(Texts.SECRET))
            assertFalse("commit: an exception message leaked into: $candidate", candidate.contains(Texts.LEAK_MARKER))
        }
    }

    @Test
    fun `a committed text shows no text`() {
        val rig = Rig()
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.COMMITTED, null)
    }

    @Test
    fun `a plain copy shows no text`() {
        val rig = Rig(withField = false)
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.COPIED, null)
    }

    @Test
    fun `a refusal detail shows no text`() {
        val rig = Rig()
        rig.field.answer = FieldCommit.REFUSED
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.COPIED, refusalDetail)
    }

    @Test
    fun `a field that throws shows neither the text nor its message`() {
        val rig = Rig()
        rig.field.failure = IllegalStateException(Texts.LEAK_MARKER + " " + Texts.SECRET)
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.COPIED, refusalDetail)
    }

    @Test
    fun `a clipboard that says no shows no text`() {
        val rig = Rig(withField = false)
        rig.clipboard.result = false
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.FAILED, nowhereDetail)
    }

    @Test
    fun `a clipboard that throws shows neither the text nor its message`() {
        val rig = Rig(withField = false)
        rig.clipboard.failure = IllegalStateException(Texts.LEAK_MARKER + " " + Texts.SECRET)
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.FAILED, nowhereDetail)
    }

    @Test
    fun `a field and a clipboard that both throw show neither the text nor their messages`() {
        val rig = Rig()
        rig.field.failure = IllegalStateException(Texts.LEAK_MARKER)
        rig.clipboard.failure = IllegalStateException(Texts.LEAK_MARKER + " " + Texts.SECRET)
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.FAILED, nowhereDetail)
    }

    @Test
    fun `a notice that throws shows neither the text nor its message`() {
        val rig = Rig(sdkInt = 32, withField = false)
        rig.notice.failure = IllegalStateException(Texts.LEAK_MARKER)
        val request = Texts.request()

        assertRedacted(request, rig.service.commit(request), CommitOutcome.COPIED, null)
    }

    @Test
    fun `a hop that fails shows neither the text nor the failure message`() {
        val rig = Rig()
        val service = rig.serviceWith(FailingMainThread(IllegalStateException(Texts.LEAK_MARKER + " " + Texts.SECRET)))
        val request = Texts.request()

        assertRedacted(request, service.commit(request), CommitOutcome.FAILED, notRespondingDetail)
    }

    @Test
    fun `a hop that is unavailable shows no text and calls no seam`() {
        val rig = Rig()
        val service = rig.serviceWith(FailingMainThread(MainThreadUnavailable("test: unavailable")))
        val request = Texts.request()

        assertRedacted(request, service.commit(request), CommitOutcome.FAILED, notRespondingDetail)
        assertEquals("commit: no seam may run when the hop never ran", 0, rig.focus.reads)
    }
}
