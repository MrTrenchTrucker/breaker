package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `id` tiebreak, checked in both places the module applies it.
 *
 * [HistorySql.SELECT_NEWEST] orders a `created_at` tie by `id DESC`, and the
 * phone's SQLite compares those ids by their UTF-8 bytes (the default BINARY
 * collation). The in-memory double the store is tested against breaks the same
 * tie with a Kotlin `String` comparison, which walks UTF-16 code units. For ids
 * that fit in one code unit the two orders agree; for an id that holds a
 * character above the basic multilingual plane they can name different
 * winners for the same two rows.
 *
 * [HistorySql.SELECT_NEWEST] carries the promise that the tiebreak is a stable
 * order — two dictations in the same millisecond "must not swap places between
 * two `list()` calls". This module ships two implementations of that order, and
 * a suite that ran the store against only one of them would stay green while the
 * other quietly listed the same two rows in the opposite order. So this test
 * runs the store against both, with the same rows, and holds the two orders to
 * each other.
 */
class HistoryTiebreakAgreementTest {

    private val now = 1_700_000_000_000L

    // Two ids that tie on created_at and name different winners under the two
    // collations: one holds U+1F600 (a surrogate pair in UTF-16, four bytes in
    // UTF-8), the other holds U+FFFF (one unit in UTF-16, three bytes in UTF-8).
    private val idPair = "a😀"
    private val idBmpMax = "a\uffff"

    private fun aTranscription(id: String, createdAt: Long) = Transcription(
        id = id,
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    @Test
    fun `the in-memory double and real SQL break a same-millisecond tie the same way for ids above the basic plane`() {
        val doubleStore: HistoryStore = SqliteHistoryStore(InMemoryHistoryDatabase(), Clock { now })
        val sqlite = DesktopSqlite()
        try {
            val jdbcStore: HistoryStore = SqliteHistoryStore(JdbcHistoryDatabase(sqlite), Clock { now })

            // Same two rows, same millisecond, on both implementations.
            doubleStore.save(aTranscription(idPair, now))
            doubleStore.save(aTranscription(idBmpMax, now))
            jdbcStore.save(aTranscription(idPair, now))
            jdbcStore.save(aTranscription(idBmpMax, now))

            val doubleOrder = doubleStore.list(10).map { it.id }
            val sqliteOrder = jdbcStore.list(10).map { it.id }

            assertEquals(
                "The id tiebreak must be one order: the in-memory double and real " +
                    "SQL listed the same two same-millisecond rows in different orders " +
                    "(double=$doubleOrder, sqlite=$sqliteOrder). The KDoc on " +
                    "SELECT_NEWEST promises the tie is a stable order, but the module " +
                    "ships two implementations of it that name different winners for " +
                    "ids above the basic multilingual plane.",
                sqliteOrder,
                doubleOrder,
            )
        } finally {
            sqlite.close()
        }
    }
}
