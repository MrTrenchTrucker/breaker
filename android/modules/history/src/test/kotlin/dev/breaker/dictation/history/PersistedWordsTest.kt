package dev.breaker.dictation.history

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The words the module writes into the database, written out here as literals.
 *
 * A tombstone's reason is stored as a word and read back by that word. The words are on the user's disk, so
 * a rename in [Tombstone.Reason] would leave every row already written unreadable, and the round trip through
 * the module's own code would not show it: it writes and reads the same new word. These tests hold the words
 * to the literals, on the enum and on the column.
 */
class PersistedWordsTest {

    private val sqlite = DesktopSqlite()

    @After
    fun closeDatabase() = sqlite.close()

    @Test
    fun `the user reason is stored as the word user`() {
        assertEquals("user", Tombstone.Reason.USER.stored)
        assertSame(Tombstone.Reason.USER, Tombstone.Reason.fromStored("user"))
    }

    @Test
    fun `the retention reason is stored as the word retention`() {
        assertEquals("retention", Tombstone.Reason.RETENTION.stored)
        assertSame(Tombstone.Reason.RETENTION, Tombstone.Reason.fromStored("retention"))
    }

    @Test
    fun `the remote reason is stored as the word remote`() {
        assertEquals("remote", Tombstone.Reason.REMOTE.stored)
        assertSame(Tombstone.Reason.REMOTE, Tombstone.Reason.fromStored("remote"))
    }

    @Test
    fun `a tombstone is written to the column as those words`() {
        val database = JdbcHistoryDatabase(sqlite)
        database.putTombstone(Tombstone("a-user", 1_000L, Tombstone.Reason.USER))
        database.putTombstone(Tombstone("b-retention", 2_000L, Tombstone.Reason.RETENTION))
        database.putTombstone(Tombstone("c-remote", 3_000L, Tombstone.Reason.REMOTE))

        val stored = sqlite.query("SELECT id, reason FROM ${HistorySql.TABLE_TOMBSTONES} ORDER BY id")

        assertEquals(
            listOf(listOf("a-user", "user"), listOf("b-retention", "retention"), listOf("c-remote", "remote")),
            stored,
        )
    }

    @Test
    fun `a tombstone row holding those words is read back as the reasons`() {
        val database = JdbcHistoryDatabase(sqlite)
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, "a-user", 1_000L, "user")
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, "b-retention", 2_000L, "retention")
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, "c-remote", 3_000L, "remote")

        val reasons = database.newestTombstones(10).associate { it.id to it.reason }

        assertEquals(
            mapOf(
                "a-user" to Tombstone.Reason.USER,
                "b-retention" to Tombstone.Reason.RETENTION,
                "c-remote" to Tombstone.Reason.REMOTE,
            ),
            reasons,
        )
    }
}
