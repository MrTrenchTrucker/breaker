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

/** The store, its clock and its policy: the state every test here starts from. */
internal abstract class SqliteHistoryStoreTestBase {
    protected val database = InMemoryHistoryDatabase()
    protected var nowMillis = Instant.parse("2026-06-30T12:00:00Z").toEpochMilli()
    protected val clock = Clock { nowMillis }
    protected val policy = RetentionPolicy()
    protected val store: HistoryStore = SqliteHistoryStore(database, clock)

    protected fun typed(): SqliteHistoryStore = store as SqliteHistoryStore

    protected fun aTranscription(
        id: String = "t-1",
        text: String = "load the hay",
        source: TranscriptionSource = TranscriptionSource.LOCAL,
        createdAt: Long = nowMillis,
    ) = Transcription(
        id = id,
        text = text,
        source = source,
        model = "small.en",
        durationMs = 1_200,
        createdAt = createdAt,
    )

}
