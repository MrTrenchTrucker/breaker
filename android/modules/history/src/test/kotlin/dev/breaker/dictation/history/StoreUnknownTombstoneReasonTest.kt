package dev.breaker.dictation.history

import dev.breaker.dictation.core.port.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored tombstone whose reason is neither `user`, `retention` nor `remote` must not stop the pending
 * list from loading.
 *
 * `SqliteHistoryStore.pendingTombstones` maps every stored row it reads. On the unfixed code one row with
 * an unknown reason made the whole read throw `MappingFailure`, so the tombstones behind it — the deletes
 * the server still had to learn about — went with it, and a tombstone dropped on read is a delete the
 * server never hears of. These tests pin the other two halves of the contract as well: the bad row is
 * left on file, and it is reported rather than silently dropped.
 *
 * The rows are planted straight into the column, so the stored word can be one this module does not know;
 * the store is built with a capturing report hook, because the default is `android.util.Log` and a JVM test
 * may not take it.
 */
class StoreUnknownTombstoneReasonTest {

    private val sqlite = DesktopSqlite()
    private val database = JdbcHistoryDatabase(sqlite)
    private val clock = Clock { 1_700_000_000_000 }
    private val reported = mutableListOf<String>()
    private val store = SqliteHistoryStore(database, clock, report = { reported.add(it) })

    @After
    fun closeDatabase() = sqlite.close()

    /** Write a tombstone row straight to the column, so the stored word can be one this module does not know. */
    private fun plant(id: String, deletedAt: Long, reason: String) {
        sqlite.exec(HistorySql.INSERT_OR_REPLACE_TOMBSTONE, id, deletedAt, reason)
    }

    /** The stored words, id and reason, as the column holds them. */
    private fun storedWords(): List<List<Any?>> =
        sqlite.query("SELECT id, reason FROM ${HistorySql.TABLE_TOMBSTONES} ORDER BY id")

    @Test
    fun `two good tombstones with one unknown reason in between return the two good ones and do not throw`() {
        plant("good-1", 3_000L, "user")
        plant("weird-1", 2_000L, "weird")
        plant("good-2", 1_000L, "remote")

        val pending = store.pendingTombstones(10)

        assertEquals(listOf("good-1", "good-2"), pending.map { it.id })
    }

    @Test
    fun `the unknown tombstone is left on file and reported with the row id and the stored word`() {
        plant("good-1", 3_000L, "user")
        plant("weird-1", 2_000L, "weird")

        store.pendingTombstones(10)

        assertEquals(listOf(listOf("good-1", "user"), listOf("weird-1", "weird")), storedWords())
        assertEquals(
            "history tombstone weird-1 has an unknown reason 'weird'; it stays stored",
            reported.single(),
        )
    }

    @Test
    fun `the unknown tombstone is still there on a later read, and is reported again`() {
        plant("weird-1", 2_000L, "weird")
        plant("good-1", 1_000L, "retention")

        val first = store.pendingTombstones(10)
        val second = store.pendingTombstones(10)

        assertEquals(listOf("good-1"), first.map { it.id })
        assertEquals(listOf("good-1"), second.map { it.id })
        assertEquals(
            "a read that deletes the row it cannot map loses the delete it stands for: " +
                "${storedWords()} after two reads",
            listOf(listOf("good-1", "retention"), listOf("weird-1", "weird")),
            storedWords(),
        )
        assertEquals(2, reported.size)
    }

    @Test
    fun `a history of nothing but unknown tombstones returns an empty list and throws nothing`() {
        plant("weird-1", 2_000L, "weird")
        plant("other-1", 1_000L, "other")

        val pending = store.pendingTombstones(10)

        assertEquals(emptyList<String>(), pending.map { it.id })
        assertEquals(2, storedWords().size)
        assertEquals(2, reported.size)
    }

    @Test
    fun `every known reason still reads back as its own value`() {
        plant("a-user", 1_000L, "user")
        plant("b-retention", 2_000L, "retention")
        plant("c-remote", 3_000L, "remote")

        val pending = store.pendingTombstones(10)

        assertEquals(
            mapOf(
                "a-user" to Tombstone.Reason.USER,
                "b-retention" to Tombstone.Reason.RETENTION,
                "c-remote" to Tombstone.Reason.REMOTE,
            ),
            pending.associate { it.id to it.reason },
        )
        assertTrue("a readable tombstone is not a row to report: $reported", reported.isEmpty())
    }

    @Test
    fun `the row mapping itself is still strict for a caller that wants the failure`() {
        val row = Tombstone.StoredRow(id = "weird-1", deletedAt = 2_000L, reason = "weird")

        val failure = assertThrows(MappingFailure::class.java) { row.toTombstone() }

        assertEquals("Tombstone weird-1 has an unknown reason 'weird'", failure.message)
    }

    @Test
    fun `the tolerant sibling answers null for an unknown word and the tombstone for a known one`() {
        val unknown = Tombstone.StoredRow(id = "weird-1", deletedAt = 2_000L, reason = "weird")
        val known = Tombstone.StoredRow(id = "a-user", deletedAt = 1_000L, reason = "user")

        assertEquals(null, unknown.toTombstoneOrNull())
        assertEquals(Tombstone.Reason.USER, known.toTombstoneOrNull()?.reason)
    }
}
