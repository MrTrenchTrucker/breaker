package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The purge order's `id` tiebreak, held to the same order as the shipped SQL.
 *
 * [HistorySql.SELECT_IDS_CREATED_BEFORE] is the one statement in the module
 * that orders ascending, `ORDER BY created_at ASC, id ASC`, because a purge
 * tombstones the rows in the order they aged out. Ascending is the direction
 * that is easy to get wrong here: a `DESC` tiebreak comparator applied to this
 * statement would return the same *set* of ids and a plausible-looking report,
 * with every tombstone written back to front.
 *
 * The collation is the same disagreement as in the two newest-first sites: BINARY
 * compares UTF-8 bytes unsigned, a Kotlin `String` comparison walks UTF-16 code
 * units, and for an id holding a character above the basic multilingual plane the
 * two name different winners. The pair below is chosen so they disagree in the
 * ascending direction as well, so this test fails on a collation that has gone
 * wrong and not only on a direction that has.
 */
class PurgeOrderTiebreakAgreementTest {

    private val cutoff = 1_700_000_000_000L

    // Both rows are stamped a millisecond before the cutoff, so both pass the
    // `created_at < ?` filter and tie exactly on `created_at`, which is the only
    // way the `id` tiebreak is reached.
    private val createdAt = cutoff - 1

    // UTF-8 ascending: `a` EF BF BF sorts before `a` F0 9F 98 80.
    // UTF-16 ascending: `a` FFFF sorts before `a` D83D DE00. Opposite.
    private val idBmpMax = "a\uFFFF"
    private val idPair = "a\uD83D\uDE00"

    @Test
    fun `the in-memory double and real SQL purge the same tied rows in the same order`() {
        val double = InMemoryHistoryDatabase()
        val sqlite = DesktopSqlite()
        try {
            val jdbc = JdbcHistoryDatabase(sqlite)
            for (id in listOf(idBmpMax, idPair)) {
                double.save(row(id, createdAt))
                jdbc.save(row(id, createdAt))
            }

            val doubleOrder = double.idsCreatedBefore(cutoff)
            val sqliteOrder = jdbc.idsCreatedBefore(cutoff)

            assertEquals(
                "Both rows must pass the boundary filter, or the tiebreak is never " +
                    "reached and this test proves nothing: double=$doubleOrder, " +
                    "sqlite=$sqliteOrder",
                listOf(idBmpMax, idPair).sorted(),
                sqliteOrder.sorted(),
            )
            assertEquals(
                "The id tiebreak must be one order, and it must be ascending: the " +
                    "in-memory double and real SQL named the same two tied rows in " +
                    "different orders (double=$doubleOrder, sqlite=$sqliteOrder). " +
                    "SELECT_IDS_CREATED_BEFORE orders by `id ASC`, so every tombstone " +
                    "this purge writes comes out back to front if the two disagree.",
                sqliteOrder,
                doubleOrder,
            )
        } finally {
            sqlite.close()
        }
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
