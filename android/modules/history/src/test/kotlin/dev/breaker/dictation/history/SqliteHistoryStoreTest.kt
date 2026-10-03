package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.HistoryStore
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module's invariants, exercised through the store.
 *
 * Each test here corresponds to a line on the module card, and each is written
 * so that the behaviour it protects is the *only* thing that could make it
 * fail. Where a boundary exists, the row is placed on the correct side of it by
 * an explicit millisecond.
 */

internal class SqliteHistoryStoreTest : SqliteHistoryStoreTestBase() {

    // ── the store is the port the domain expects ─────────────────────────

    @Test
    fun `the store can be used through the core HistoryStore port`() {
        val port: HistoryStore = store
        port.save(aTranscription())
        assertEquals(listOf("load the hay"), port.list(10).map { it.text })
        assertTrue(port.delete("t-1"))
        assertEquals(emptyList<Transcription>(), port.list(10))
    }

    // ── F7: every transcription is saved, whatever produced it ───────────

    @Test
    fun `a local transcription is saved`() {
        store.save(aTranscription(id = "local-1", source = TranscriptionSource.LOCAL))
        assertEquals(listOf("local-1"), store.list(10).map { it.id })
    }

    @Test
    fun `a server transcription is saved too`() {
        store.save(aTranscription(id = "server-1", source = TranscriptionSource.SERVER))
        assertEquals(listOf("server-1"), store.list(10).map { it.id })
    }

    @Test
    fun `local and server transcriptions sit in the same history, neither dropped`() {
        store.save(aTranscription(id = "local-1", source = TranscriptionSource.LOCAL))
        store.save(aTranscription(id = "server-1", source = TranscriptionSource.SERVER))
        store.save(aTranscription(id = "local-2", source = TranscriptionSource.LOCAL))

        assertEquals(3, typed().count())
        assertEquals(
            setOf("local-1", "server-1", "local-2"),
            store.list(10).map { it.id }.toSet(),
        )
    }

    @Test
    fun `the source is recorded and survives the round trip`() {
        store.save(aTranscription(id = "s-1", source = TranscriptionSource.SERVER))
        assertEquals(TranscriptionSource.SERVER, store.list(10).single().source)

        store.save(aTranscription(id = "l-1", source = TranscriptionSource.LOCAL))
        assertEquals(
            TranscriptionSource.LOCAL,
            store.list(10).first { it.id == "l-1" }.source,
        )
    }

    @Test
    fun `switching engines does not make a dictation disappear`() {
        // The same sentence, dictated once per engine. Both must be on file.
        store.save(aTranscription(id = "one", text = "check the load", source = TranscriptionSource.LOCAL))
        store.save(aTranscription(id = "two", text = "check the load", source = TranscriptionSource.SERVER))
        assertEquals(2, typed().count())
        assertEquals(
            listOf("check the load", "check the load"),
            store.list(10).map { it.text },
        )
    }

    @Test
    fun `a blank transcription is kept rather than silently dropped`() {
        // Recording silence transcribes to nothing. Whether that is worth
        // keeping is the domain's call, not this module's — dropping it here
        // would hide that a dictation happened at all.
        store.save(aTranscription(id = "silent", text = ""))
        assertEquals(1, typed().count())
        assertEquals("", store.list(10).single().text)
    }

    @Test
    fun `saving the same id twice updates the row rather than duplicating it`() {
        store.save(aTranscription(id = "t-1", text = "first try"))
        store.save(aTranscription(id = "t-1", text = "second try"))

        assertEquals("A re-sent dictation must not become two rows", 1, typed().count())
        assertEquals("second try", store.list(10).single().text)
    }

    @Test
    fun `a row saved again keeps its original creation time`() {
        // The re-save is the same transcription, so it must not jump to the top
        // of a history the user is scrolling.
        val original = nowMillis - 60_000
        store.save(aTranscription(id = "t-1", createdAt = original))
        store.save(aTranscription(id = "t-1", text = "edited", createdAt = original))
        assertEquals(original, store.list(10).single().createdAt)
    }

    // ── tap-to-copy and delete ───────────────────────────────────────────

    @Test
    fun `history reads back the exact text the user dictated`() {
        // Tap-to-copy copies what is on file, so what is on file must be the
        // text, character for character.
        val text = "  Mixed CASE, punctuation — and  double  spaces.  "
        store.save(aTranscription(id = "t-1", text = text))
        assertEquals(text, store.list(10).single().text)
    }

    @Test
    fun `every field survives the round trip through the database`() {
        val original = Transcription(
            id = "t-42",
            text = "deliver to dock four",
            source = TranscriptionSource.SERVER,
            model = "large.en",
            durationMs = 4_321,
            createdAt = 1_700_000_000_000,
        )
        store.save(original)
        assertEquals(original, store.list(10).single())
    }

    @Test
    fun `history is listed newest first`() {
        store.save(aTranscription(id = "old", createdAt = nowMillis - 3_000))
        store.save(aTranscription(id = "new", createdAt = nowMillis - 1_000))
        store.save(aTranscription(id = "middle", createdAt = nowMillis - 2_000))
        assertEquals(listOf("new", "middle", "old"), store.list(10).map { it.id })
    }

    @Test
    fun `listing is stable when two dictations share a millisecond`() {
        store.save(aTranscription(id = "b", createdAt = nowMillis))
        store.save(aTranscription(id = "a", createdAt = nowMillis))
        val first = store.list(10).map { it.id }
        val second = store.list(10).map { it.id }
        assertEquals("Two reads must agree or the user's list moves under them", first, second)
        assertEquals(listOf("b", "a"), first)
    }

    @Test
    fun `a limit returns only that many, still newest first`() {
        store.save(aTranscription(id = "old", createdAt = nowMillis - 3_000))
        store.save(aTranscription(id = "new", createdAt = nowMillis - 1_000))
        store.save(aTranscription(id = "middle", createdAt = nowMillis - 2_000))
        assertEquals(listOf("new"), store.list(1).map { it.id })
    }

    @Test
    fun `limit zero returns an empty list`() {
        // Zero is answered by the store before it asks the database, but that
        // shortcut changes nothing a caller can see: `take(0)` here and
        // `LIMIT 0` in SQL are both empty. So this pins the empty result, not the
        // shortcut, and there is no separate zero case on real SQL.
        store.save(aTranscription())
        assertEquals(emptyList<Transcription>(), store.list(0))
    }

    @Test
    fun `a negative limit is refused`() {
        val failure = runCatching { store.list(-1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a negative limit is refused by the store's own check, in the store's own words`() {
        // The in-memory double refuses a negative count too, from its own `take`,
        // with a different message. The store's message is what shows the refusal
        // came from the store, which is the only thing that stops `LIMIT -1` from
        // returning every row on real SQL.
        store.save(aTranscription())

        val failure = runCatching { store.list(-1) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("limit cannot be negative: -1", failure?.message)
    }

    @Test
    fun `a negative limit on the tombstone list is refused in the store's own words`() {
        typed().applyRemoteDelete("t-1", deletedAt = nowMillis)

        val failure = runCatching { typed().pendingTombstones(-1) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("limit cannot be negative: -1", failure?.message)
    }

    @Test
    fun `listing an empty history is an empty list, not an error`() {
        assertEquals(emptyList<Transcription>(), store.list(10))
    }

    @Test
    fun `delete removes the row and reports that it did`() {
        store.save(aTranscription(id = "t-1"))
        assertTrue(store.delete("t-1"))
        assertEquals(0, typed().count())
        assertEquals(emptyList<Transcription>(), store.list(10))
    }

    @Test
    fun `delete leaves the other rows alone`() {
        store.save(aTranscription(id = "keep-1", createdAt = nowMillis - 2_000))
        store.save(aTranscription(id = "drop-1", createdAt = nowMillis - 1_000))
        store.save(aTranscription(id = "keep-2", createdAt = nowMillis))

        assertTrue(store.delete("drop-1"))
        assertEquals(listOf("keep-2", "keep-1"), store.list(10).map { it.id })
    }

    @Test
    fun `deleting an id this device never had removes nothing and says so`() {
        store.save(aTranscription(id = "t-1"))
        assertFalse(store.delete("never-existed"))
        assertEquals("The rows that were there must survive a failed delete", 1, typed().count())
    }

    @Test
    fun `deleting an id that was already deleted reports false the second time`() {
        store.save(aTranscription(id = "t-1"))
        assertTrue(store.delete("t-1"))
        assertFalse(store.delete("t-1"))
    }

    @Test
    fun `a delete is recorded as a tombstone so the server learns of it`() {
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")

        val tombstone = typed().pendingTombstones(10).single()
        assertEquals("t-1", tombstone.id)
        assertEquals(Tombstone.Reason.USER, tombstone.reason)
    }

    @Test
    fun `a failed delete leaves no tombstone behind`() {
        // Nothing was deleted, so nothing should be announced as deleted. A
        // tombstone here would tell the server to remove a transcription the
        // user never asked it to.
        store.save(aTranscription(id = "t-1"))
        assertFalse(store.delete("nope"))
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
    }

    @Test
    fun `a delete by a server transcription tombstones it the same way`() {
        store.save(aTranscription(id = "t-1", source = TranscriptionSource.SERVER))
        store.delete("t-1")
        assertEquals("t-1", typed().pendingTombstones(10).single().id)
    }

    @Test
    fun `a delete that arrives from elsewhere removes the row and keeps a tombstone`() {
        // F34: a delete made on the web front end reaches this device, and this
        // device still has to tell the other devices about it.
        store.save(aTranscription(id = "t-1"))

        assertTrue(typed().applyRemoteDelete("t-1", deletedAt = nowMillis))
        assertEquals(0, typed().count())
        assertEquals(Tombstone.Reason.REMOTE, typed().pendingTombstones(10).single().reason)
    }

    @Test
    fun `a delete for text this device never had is still tombstoned`() {
        // The user deleted it on another device. This device holding no row does
        // not make the delete any less real.
        assertFalse(typed().applyRemoteDelete("never-here", deletedAt = nowMillis))
        assertEquals("never-here", typed().pendingTombstones(10).single().id)
    }

    @Test
    fun `tombstones are listed newest first`() {
        store.save(aTranscription(id = "a"))
        store.save(aTranscription(id = "b"))
        store.save(aTranscription(id = "c"))

        nowMillis += 1_000
        store.delete("a")
        store.delete("b")
        nowMillis += 1_000
        store.delete("c")

        assertEquals(listOf("c", "b", "a"), typed().pendingTombstones(10).map { it.id })
    }

    @Test
    fun `a tombstone sweep leaves the transcriptions alone`() {
        // Two separate clocks. Sweeping tombstones is not a retention purge and
        // must not take a single transcription with it.
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")

        val oldEnough = nowMillis + java.time.Duration.ofDays(400).toMillis()
        val report = typed().purgeExpiredTombstones(oldEnough)

        assertEquals(1, report.dropped)
        assertEquals(0, typed().count())
    }

    @Test
    fun `a tombstone sweep keeps a tombstone that is still in date`() {
        store.save(aTranscription(id = "t-1"))
        store.delete("t-1")

        val report = typed().purgeExpiredTombstones(nowMillis)

        assertEquals("A delete that has not reached the server yet must keep its tombstone", 0, report.dropped)
        assertEquals(1, typed().pendingTombstones(10).size)
    }

    @Test
    fun `a tombstone sweep leaves a live transcription alone, however old it is`() {
        // The live row is 500 days old, so a sweep that reached the transcriptions
        // by age would expire it. The tombstone is past the window on its own.
        store.save(aTranscription(id = "live", createdAt = nowMillis - Duration.ofDays(500).toMillis()))
        typed().applyRemoteDelete("gone", deletedAt = nowMillis - Duration.ofDays(120).toMillis())
        assertEquals(1, typed().count())
        assertEquals(listOf("gone"), typed().pendingTombstones(10).map { it.id })

        val report = typed().purgeExpiredTombstones(nowMillis)

        assertEquals(1, report.dropped)
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
        assertEquals(listOf("live"), store.list(10).map { it.id })
        assertEquals(1, typed().count())
    }

    @Test
    fun `a tombstone sweep drops the tombstone of a row saved again, and only the tombstone`() {
        // Deleted, then dictated again under the same id: a tombstone past the
        // window and a live row now share one id. The live row is recent, so only
        // a sweep that matched rows to tombstones by id could take it.
        val firstDictated = nowMillis - Duration.ofDays(200).toMillis()
        store.save(aTranscription(id = "again", text = "first time", createdAt = firstDictated))
        assertTrue(typed().applyRemoteDelete("again", deletedAt = nowMillis - Duration.ofDays(120).toMillis()))
        store.save(aTranscription(id = "again", text = "second time"))
        assertEquals(listOf("again"), typed().pendingTombstones(10).map { it.id })
        assertEquals(listOf("second time"), store.list(10).map { it.text })

        val report = typed().purgeExpiredTombstones(nowMillis)

        assertEquals(1, report.dropped)
        assertEquals(emptyList<Tombstone>(), typed().pendingTombstones(10))
        assertEquals(listOf("second time"), store.list(10).map { it.text })
        assertEquals(1, typed().count())
    }

}
