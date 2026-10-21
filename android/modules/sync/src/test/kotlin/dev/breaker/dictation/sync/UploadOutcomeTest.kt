package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The answer type the uploader returns: an outcome type with exactly three answers, in a fixed order.
 *
 * An outcome type with a fourth answer is one the drain has no rule for.
 */
class UploadOutcomeTest {

    /** A failure here means the uploader can be answered with a value the drain has no rule for. */
    @Test
    fun `UploadOutcome has exactly the three outcomes in order`() {
        assertEquals(
            "android_sync: UploadOutcome must be CONFIRMED, UNREACHABLE, FAILED in this order",
            listOf(UploadOutcome.CONFIRMED, UploadOutcome.UNREACHABLE, UploadOutcome.FAILED),
            UploadOutcome.entries.toList(),
        )
        assertEquals(
            "android_sync: the names must be exactly these three",
            listOf("CONFIRMED", "UNREACHABLE", "FAILED"),
            UploadOutcome.entries.map { it.name },
        )
    }
}
