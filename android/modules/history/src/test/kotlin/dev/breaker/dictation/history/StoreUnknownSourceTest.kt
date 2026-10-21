package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored row whose `source` is neither `local` nor `server` must not stop the list from loading.
 *
 * `SqliteHistoryStore.list` maps every row it reads. On the unfixed code one row with an unknown
 * source made the whole `map` throw `MappingFailure`, so the History screen died at launch and
 * every readable dictation went with it. These tests pin the other two halves of the contract as
 * well: the bad row is left on file, and it is reported rather than silently dropped.
 */
class StoreUnknownSourceTest {

    private val database = InMemoryHistoryDatabase()
    private val clock = Clock { 1_700_000_000_000 }
    private val reported = mutableListOf<String>()
    private val store = SqliteHistoryStore(database, clock, report = { reported.add(it) })

    private fun aRow(id: String, source: String, createdAt: Long) = TranscriptionRow(
        id = id,
        text = "load the hay",
        source = source,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

    @Test
    fun `two good rows with one unknown source in between return the two good rows and do not throw`() {
        database.save(aRow("good-1", "local", createdAt = 3_000))
        database.save(aRow("weird-1", "weird", createdAt = 2_000))
        database.save(aRow("good-2", "server", createdAt = 1_000))

        val listed = store.list(10)

        assertEquals(listOf("good-1", "good-2"), listed.map { it.id })
    }

    @Test
    fun `the unknown row is left on file and reported, not deleted and not swallowed`() {
        database.save(aRow("good-1", "local", createdAt = 3_000))
        database.save(aRow("weird-1", "weird", createdAt = 2_000))

        store.list(10)

        assertEquals(listOf("good-1", "weird-1"), database.newest(10).map { it.id })
        assertTrue("the unknown row is reported: $reported", reported.single().contains("weird-1"))
    }

    @Test
    fun `a history of nothing but unknown rows returns an empty list and throws nothing`() {
        database.save(aRow("weird-1", "weird", createdAt = 2_000))
        database.save(aRow("other-1", "other", createdAt = 1_000))

        val listed = store.list(10)

        assertEquals(emptyList<String>(), listed.map { it.id })
        assertEquals(2, database.newest(10).size)
    }

    @Test
    fun `both known sources still map to the right source value`() {
        database.save(aRow("local-1", "local", createdAt = 2_000))
        database.save(aRow("server-1", "server", createdAt = 1_000))

        val listed = store.list(10)

        assertEquals(TranscriptionSource.LOCAL, listed.single { it.id == "local-1" }.source)
        assertEquals(TranscriptionSource.SERVER, listed.single { it.id == "server-1" }.source)
    }

    @Test
    fun `the row mapping itself is still strict for a caller that wants the failure`() {
        val failure = runCatching { aRow("weird-1", "weird", createdAt = 2_000).toTranscription() }

        assertTrue("toTranscription must still throw: $failure", failure.isFailure)
    }
}
