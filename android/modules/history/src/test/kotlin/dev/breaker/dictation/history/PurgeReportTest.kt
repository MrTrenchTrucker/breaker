package dev.breaker.dictation.history

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a purge report says, and what it refuses to be built with.
 *
 * [PurgeReport] is public, so anything can build one. The counts it carries are
 * what a caller checks after a cleanup, and [PurgeReport.isConsistent] is how it
 * says a purge left the server holding rows the phone no longer has. Both the
 * true and the false answer are pinned here, because a report that can only say
 * "fine" is not a report.
 */
class PurgeReportTest {

    private val cutoff = Instant.parse("2026-04-01T12:00:00Z").toEpochMilli()

    private fun refusal(build: () -> PurgeReport): Throwable? = runCatching { build() }.exceptionOrNull()

    @Test
    fun `a purge that removed rows it did not tombstone is not consistent`() {
        assertFalse(PurgeReport(purged = 2, tombstoned = 1, cutoff = cutoff).isConsistent)
    }

    @Test
    fun `a purge that tombstoned rows it did not remove is not consistent`() {
        assertFalse(PurgeReport(purged = 1, tombstoned = 2, cutoff = cutoff).isConsistent)
    }

    @Test
    fun `a purge that tombstoned every row it removed is consistent`() {
        assertTrue(PurgeReport(purged = 1, tombstoned = 1, cutoff = cutoff).isConsistent)
    }

    @Test
    fun `a purge that removed nothing is consistent`() {
        val report = PurgeReport(purged = 0, tombstoned = 0, cutoff = cutoff)

        assertTrue(report.isConsistent)
        assertEquals(cutoff, report.cutoff)
    }

    @Test
    fun `a negative purged count is refused`() {
        val failure = refusal { PurgeReport(purged = -1, tombstoned = 0, cutoff = cutoff) }

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("purged"))
    }

    @Test
    fun `a negative tombstoned count is refused`() {
        val failure = refusal { PurgeReport(purged = 0, tombstoned = -1, cutoff = cutoff) }

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("tombstoned"))
    }
}
