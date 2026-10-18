package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Our own "copied" notice is shown only on Android versions up to 12L. From 13
 * on the system shows its own confirmation, so ours would be a duplicate.
 *
 * The limit (32) and its neighbour (33) are written as literals on purpose.
 */
internal class NoticeGatingTest {

    /** A clean copy: no field is focused, the clipboard takes the text. */
    private fun noticesForCleanCopy(sdk: Int): Int {
        val rig = Rig(sdkInt = sdk, withField = false)
        val result: CommitOutcomeResult = rig.service.commit(Texts.request())
        assertEquals("commit: a clean copy on sdk $sdk must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: the text must be copied once on sdk $sdk", 1, rig.clipboard.writes.size)
        return rig.notice.shown
    }

    /** A copy after the field refused the text. */
    private fun noticesForRefusalCopy(sdk: Int): Int {
        val rig = Rig(sdkInt = sdk, withField = true)
        rig.field.answer = FieldCommit.REFUSED
        val result: CommitOutcomeResult = rig.service.commit(Texts.request())
        assertEquals("commit: a refusal copy on sdk $sdk must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertEquals("commit: the text must be copied once on sdk $sdk", 1, rig.clipboard.writes.size)
        return rig.notice.shown
    }

    private fun noticesForCommitted(sdk: Int): Int {
        val rig = Rig(sdkInt = sdk, withField = true)
        val result: CommitOutcomeResult = rig.service.commit(Texts.request())
        assertEquals("commit: an accepting field on sdk $sdk must give COMMITTED", CommitOutcome.COMMITTED, result.outcome)
        return rig.notice.shown
    }

    private fun noticesForFailed(sdk: Int): Int {
        val rig = Rig(sdkInt = sdk, withField = false)
        rig.clipboard.result = false
        val result: CommitOutcomeResult = rig.service.commit(Texts.request())
        assertEquals("commit: an unavailable clipboard on sdk $sdk must give FAILED", CommitOutcome.FAILED, result.outcome)
        return rig.notice.shown
    }

    @Test
    fun `a clean copy on sdk 30 shows one notice`() {
        assertEquals("commit: sdk 30 must show our notice once", 1, noticesForCleanCopy(30))
    }

    @Test
    fun `a clean copy on sdk 31 shows one notice`() {
        assertEquals("commit: sdk 31 must show our notice once", 1, noticesForCleanCopy(31))
    }

    @Test
    fun `a clean copy on sdk 32 shows one notice`() {
        assertEquals("commit: sdk 32 is the last one that needs our notice", 1, noticesForCleanCopy(32))
    }

    @Test
    fun `a clean copy on sdk 33 shows no notice`() {
        assertEquals("commit: sdk 33 has a system confirmation, so no notice of ours", 0, noticesForCleanCopy(33))
    }

    @Test
    fun `a clean copy on sdk 34 shows no notice`() {
        assertEquals("commit: sdk 34 must show no notice of ours", 0, noticesForCleanCopy(34))
    }

    @Test
    fun `a clean copy on sdk 36 shows no notice`() {
        assertEquals("commit: sdk 36 must show no notice of ours", 0, noticesForCleanCopy(36))
    }

    @Test
    fun `a copy after a refusal on sdk 30 shows one notice`() {
        assertEquals("commit: sdk 30 must show our notice once", 1, noticesForRefusalCopy(30))
    }

    @Test
    fun `a copy after a refusal on sdk 31 shows one notice`() {
        assertEquals("commit: sdk 31 must show our notice once", 1, noticesForRefusalCopy(31))
    }

    @Test
    fun `a copy after a refusal on sdk 32 shows one notice`() {
        assertEquals("commit: sdk 32 is the last one that needs our notice", 1, noticesForRefusalCopy(32))
    }

    @Test
    fun `a copy after a refusal on sdk 33 shows no notice`() {
        assertEquals("commit: sdk 33 has a system confirmation, so no notice of ours", 0, noticesForRefusalCopy(33))
    }

    @Test
    fun `a copy after a refusal on sdk 34 shows no notice`() {
        assertEquals("commit: sdk 34 must show no notice of ours", 0, noticesForRefusalCopy(34))
    }

    @Test
    fun `a copy after a refusal on sdk 36 shows no notice`() {
        assertEquals("commit: sdk 36 must show no notice of ours", 0, noticesForRefusalCopy(36))
    }

    @Test
    fun `a committed text shows no notice on any sdk`() {
        val sdks: List<Int> = listOf(30, 31, 32, 33, 34, 36)
        for (sdk in sdks) {
            assertEquals("commit: a committed text must never show a notice on sdk $sdk", 0, noticesForCommitted(sdk))
        }
    }

    @Test
    fun `a failed commit shows no notice on any sdk`() {
        val sdks: List<Int> = listOf(30, 31, 32, 33, 34, 36)
        for (sdk in sdks) {
            assertEquals("commit: a failed commit must never show a notice on sdk $sdk", 0, noticesForFailed(sdk))
        }
    }
}
