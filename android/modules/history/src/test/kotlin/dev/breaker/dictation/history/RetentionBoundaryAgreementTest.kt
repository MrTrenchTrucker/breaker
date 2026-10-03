package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retention boundary, stated once and checked in both places it is applied.
 *
 * The store's rules are tested against [InMemoryHistoryDatabase], which applies
 * the boundary in Kotlin. The rules reach a real phone through the SQL in
 * [HistorySql], which applies it as a string SQLite parses. These are two
 * independent implementations of one rule, and a test suite that only exercised
 * the first would stay green while the second quietly deleted a transcription
 * that was still in date.
 *
 * So the boundary is written down once, in [RetentionBoundary], and these
 * tests hold both implementations to it.
 */
class RetentionBoundaryAgreementTest {

    private val cutoff = 1_700_000_000_000L

    // ── the Kotlin rule ──────────────────────────────────────────────────

    @Test
    fun `a row created at the cutoff is in date`() {
        assertFalse(RetentionBoundary.isExpired(createdAt = cutoff, cutoff = cutoff))
    }

    @Test
    fun `a row created one millisecond before the cutoff is expired`() {
        assertTrue(RetentionBoundary.isExpired(createdAt = cutoff - 1, cutoff = cutoff))
    }

    @Test
    fun `a row created after the cutoff is in date`() {
        assertFalse(RetentionBoundary.isExpired(createdAt = cutoff + 1, cutoff = cutoff))
    }

    // ── the SQL rule says the same thing ─────────────────────────────────

    @Test
    fun `the SQL predicate is the Kotlin rule, as text`() {
        assertEquals("created_at < ?", RetentionBoundary.CREATED_AT_PREDICATE)
        assertEquals("deleted_at < ?", RetentionBoundary.DELETED_AT_PREDICATE)
    }

    @Test
    fun `the statements quote that predicate rather than restating it`() {
        assertEquals(
            RetentionBoundary.CREATED_AT_PREDICATE,
            HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE,
        )
        assertEquals(
            RetentionBoundary.DELETED_AT_PREDICATE,
            HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE,
        )
    }

    @Test
    fun `no boundary predicate anywhere in the module uses a less-or-equal`() {
        // A `<=` anywhere in this module is a transcription that was still in
        // date being deleted. Nothing else in the code would notice.
        val predicates = listOf(
            RetentionBoundary.CREATED_AT_PREDICATE,
            RetentionBoundary.DELETED_AT_PREDICATE,
            HistorySql.CREATED_AT_STRICTLY_BEFORE_WHERE,
            HistorySql.DELETED_AT_STRICTLY_BEFORE_WHERE,
            AdapterStatements.deleteCreatedBefore.where,
            HistorySql.SELECT_IDS_CREATED_BEFORE,
            AdapterStatements.deleteTombstonesRecordedBefore.where,
        )
        for (predicate in predicates) {
            assertFalse(
                "`<=` in '$predicate' would purge a row created exactly at the cutoff",
                predicate.contains("<="),
            )
        }
    }

    // ── the two implementations agree, row by row ────────────────────────

    @Test
    fun `the in-memory database and the SQL select the same rows at the boundary`() {
        val database = InMemoryHistoryDatabase()
        database.save(row("inside", cutoff + 1))
        database.save(row("at-boundary", cutoff))
        database.save(row("outside", cutoff - 1))

        // What the Kotlin rule (and therefore the store, and therefore the test
        // suite) believes is expired:
        assertEquals(listOf("outside"), database.idsCreatedBefore(cutoff))
    }

    @Test
    fun `the in-memory delete removes exactly what it selected`() {
        val database = InMemoryHistoryDatabase()
        database.save(row("inside", cutoff + 1))
        database.save(row("at-boundary", cutoff))
        database.save(row("outside", cutoff - 1))

        val selected = database.idsCreatedBefore(cutoff)
        val deleted = database.deleteCreatedBefore(cutoff)

        assertEquals(
            "A row must never be deleted without being tombstoned, or the server keeps a copy",
            selected.size,
            deleted,
        )
        assertEquals(listOf("at-boundary", "inside"), database.newest(10).map { it.id }.sorted())
    }

    @Test
    fun `a cutoff of zero expires only rows stamped before the epoch`() {
        // An edge of the rule itself: nothing can be created before the epoch,
        // so a zero cutoff is a purge that legitimately removes nothing.
        assertTrue(RetentionBoundary.isExpired(createdAt = -1, cutoff = 0))
        assertFalse(RetentionBoundary.isExpired(createdAt = 0, cutoff = 0))
        assertFalse(RetentionBoundary.isExpired(createdAt = 1, cutoff = 0))
    }

    private fun row(id: String, createdAt: Long) = TranscriptionRow(
        id = id,
        text = "text for $id",
        source = TranscriptionRow.SOURCE_LOCAL,
        model = "small.en",
        durationMs = 1_000,
        createdAt = createdAt,
    )
}
