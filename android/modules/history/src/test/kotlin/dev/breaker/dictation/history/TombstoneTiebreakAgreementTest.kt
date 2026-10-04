package dev.breaker.dictation.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tombstone `id` tiebreak, held to the same order as the shipped SQL.
 *
 * [HistorySql.SELECT_NEWEST_TOMBSTONES] breaks a `deleted_at` tie with `id
 * DESC`, and BINARY collation reads that as an unsigned comparison of the two
 * ids' UTF-8 bytes. The in-memory double breaks the same tie with a Kotlin
 * `String` comparison, which walks UTF-16 code units. Ids made only of
 * characters below U+0800 order the same way in both; an id holding a character
 * above the basic multilingual plane does not, and the two then name different
 * winners for the same two tombstones.
 *
 * So this runs the two implementations over the same two tombstones, recorded
 * in the same millisecond, and holds one order to the other.
 */
class TombstoneTiebreakAgreementTest {

    private val deletedAt = 1_700_000_000_000L

    // UTF-8: `a` F0 9F 98 80 against `a` EF BF BF, so this id sorts second.
    // UTF-16: `a` D83D DE00 against `a` FFFF, so it sorts first. Two orders,
    // one pair, opposite winners.
    private val idPair = "a\uD83D\uDE00"
    private val idBmpMax = "a\uFFFF"

    @Test
    fun `the in-memory double and real SQL break a same-millisecond tombstone tie the same way`() {
        val double = InMemoryHistoryDatabase()
        val sqlite = DesktopSqlite()
        try {
            val jdbc = JdbcHistoryDatabase(sqlite)
            for (id in listOf(idPair, idBmpMax)) {
                double.putTombstone(tombstone(id))
                jdbc.putTombstone(tombstone(id))
            }

            val doubleOrder = double.newestTombstones(10).map { it.id }
            val sqliteOrder = jdbc.newestTombstones(10).map { it.id }

            assertEquals(
                "Both tombstones must be stored and listed, or the tiebreak is never " +
                    "reached and this test proves nothing: double=$doubleOrder, " +
                    "sqlite=$sqliteOrder",
                listOf(idPair, idBmpMax).sorted(),
                doubleOrder.sorted(),
            )
            assertEquals(
                "The tombstone id tiebreak must be one order: the in-memory double and real " +
                    "SQL listed the same two same-millisecond tombstones in different orders " +
                    "(double=$doubleOrder, sqlite=$sqliteOrder). A tiebreak that names a " +
                    "different winner per implementation is not a stable order, and " +
                    "SELECT_NEWEST_TOMBSTONES promises it is.",
                sqliteOrder,
                doubleOrder,
            )
        } finally {
            sqlite.close()
        }
    }

    private fun tombstone(id: String) = Tombstone(
        id = id,
        deletedAt = deletedAt,
        reason = Tombstone.Reason.REMOTE,
    )
}
