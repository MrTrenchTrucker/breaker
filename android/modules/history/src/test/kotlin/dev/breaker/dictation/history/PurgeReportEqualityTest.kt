package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PurgeReport] is a public data class, so callers compare reports and copy them. Equality and `copy` are
 * part of what it offers, and these tests hold it to them. The refusal of a negative count must also hold
 * for a copy, because `copy` builds a new report through the same constructor.
 */
class PurgeReportEqualityTest {

    private val cutoff = 1_775_044_800_000L

    private fun report() = PurgeReport(purged = 2, tombstoned = 2, cutoff = cutoff)

    @Test
    fun `two reports with the same counts and cutoff are equal and hash alike`() {
        val first = report()
        val second = report()

        assertEquals(first, second)
        assertEquals(second, first)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `a report that differs in purged, tombstoned or cutoff is a different report`() {
        assertNotEquals(report(), PurgeReport(purged = 3, tombstoned = 2, cutoff = cutoff))
        assertNotEquals(report(), PurgeReport(purged = 2, tombstoned = 3, cutoff = cutoff))
        assertNotEquals(report(), PurgeReport(purged = 2, tombstoned = 2, cutoff = cutoff + 1))
    }

    @Test
    fun `a report is not equal to a value that is not a report`() {
        assertFalse(report().equals(null))
        assertFalse(report().equals("PurgeReport"))
    }

    @Test
    fun `a copy changes exactly the field it is given`() {
        val original = report()

        assertEquals(PurgeReport(purged = 5, tombstoned = 2, cutoff = cutoff), original.copy(purged = 5))
        assertEquals(PurgeReport(purged = 2, tombstoned = 5, cutoff = cutoff), original.copy(tombstoned = 5))
        assertEquals(PurgeReport(purged = 2, tombstoned = 2, cutoff = 7L), original.copy(cutoff = 7L))
        assertEquals("a copy with nothing changed is equal", original, original.copy())
    }

    @Test
    fun `whether a copy is consistent follows its counts`() {
        val original = report()

        assertTrue(original.isConsistent)
        assertFalse(original.copy(purged = 3).isConsistent)
        assertFalse(original.copy(tombstoned = 1).isConsistent)
        assertTrue(original.copy(purged = 3, tombstoned = 3).isConsistent)
    }

    @Test
    fun `a copy with a negative count is refused like a new report`() {
        val failure = runCatching { report().copy(purged = -1) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("purged"))
    }
}
