package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a tombstone and a tombstone sweep report refuse to be built with.
 *
 * A tombstone is the only record that a delete exists, so one with no id names
 * nothing to delete, and one with a negative time would sort before every real
 * delete and be swept first. Each refusal is pinned next to a value the class
 * accepts, so a check that refuses everything cannot pass for one that refuses
 * the right thing.
 */
class TombstoneTest {

    private fun refusal(build: () -> Any): Throwable? = runCatching { build() }.exceptionOrNull()

    @Test
    fun `a valid tombstone is accepted and keeps what it was built with`() {
        val tombstone = Tombstone(id = "t-1", deletedAt = 1_000, reason = Tombstone.Reason.REMOTE)

        assertEquals("t-1", tombstone.id)
        assertEquals(1_000L, tombstone.deletedAt)
        assertEquals(Tombstone.Reason.REMOTE, tombstone.reason)
    }

    @Test
    fun `an empty id is refused`() {
        val failure = refusal { Tombstone(id = "", deletedAt = 1_000, reason = Tombstone.Reason.USER) }

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("non-blank id"))
    }

    @Test
    fun `an id of only spaces is refused`() {
        val failure = refusal { Tombstone(id = "  ", deletedAt = 1_000, reason = Tombstone.Reason.USER) }

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("non-blank id"))
    }

    @Test
    fun `a negative deletion time is refused`() {
        val failure = refusal { Tombstone(id = "t-1", deletedAt = -1, reason = Tombstone.Reason.USER) }

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("deletedAt"))
    }

    @Test
    fun `a deletion time of zero is accepted`() {
        assertEquals(0L, Tombstone(id = "t-1", deletedAt = 0, reason = Tombstone.Reason.USER).deletedAt)
    }

    @Test
    fun `a sweep report keeps what it was built with, and a count of zero is accepted`() {
        val report = TombstonePurgeReport(dropped = 0, cutoff = 5_000)

        assertEquals(0, report.dropped)
        assertEquals(5_000L, report.cutoff)
    }

    @Test
    fun `a negative dropped count is refused`() {
        val failure = refusal { TombstonePurgeReport(dropped = -1, cutoff = 5_000) }

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("dropped"))
    }
}
