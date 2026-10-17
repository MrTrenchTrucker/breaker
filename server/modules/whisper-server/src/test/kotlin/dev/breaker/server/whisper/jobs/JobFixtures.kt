package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.TempDatabase
import dev.breaker.server.whisper.db.TestDatabases
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** A clock the test moves by hand. Single-coroutine use only: the field is not synchronised. */
internal class MutableClock(start: Instant) : Clock() {
    var current: Instant = start

    fun advance(by: Duration) {
        current = current.plus(by)
    }

    fun set(instant: Instant) {
        current = instant
    }

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

internal object JobFixtures {
    val START: Instant = Instant.ofEpochMilli(1_800_000_000_000L)

    // A pattern that cannot occur by chance in a database file, so finding it in a
    // file means the audio is there.
    private val MARKER_PATTERN: ByteArray =
        byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0xC3.toByte(), 0x3C.toByte())

    /** 4096 bytes: the four byte pattern 0xA5 0x5A 0xC3 0x3C repeated. */
    val MARKER_AUDIO: ByteArray = ByteArray(4096) { index -> MARKER_PATTERN[index % MARKER_PATTERN.size] }

    /** The first 64 bytes of [MARKER_AUDIO]: what a search of a file looks for. */
    val MARKER_PROBE: ByteArray = MARKER_AUDIO.copyOf(64)

    /** Exactly 96 ASCII characters: the words "result-marker-" repeated and cut. */
    const val MARKER_RESULT: String =
        "result-marker-result-marker-result-marker-result-marker-result-marker-result-marker-result-marke"

    // One line per row with every column, the audio in hex, so two snapshots are equal
    // only when no column of any row changed.
    private const val SNAPSHOT_SQL: String =
        "SELECT id || '|' || owner_account_id || '|' || status || '|' || attempts || '|' || " +
            "ifnull(error, '-') || '|' || ifnull(result, '-') || '|' || created_at_ms || '|' || " +
            "ifnull(started_at_ms, '-') || '|' || ifnull(finished_at_ms, '-') || '|' || " +
            "ifnull(hex(audio), '-') FROM jobs ORDER BY id"

    fun store(temp: TempDatabase, clock: Clock): JobStore = JobStore(temp.db, clock)

    /** Every row of the jobs table as one text line, read with plain SQL. */
    fun snapshot(connection: Connection): List<String> = TestDatabases.strings(connection, SNAPSHOT_SQL)

    /** Deterministic, never empty; two seeds give two different arrays. */
    fun audio(seed: Int, size: Int = 16): ByteArray =
        ByteArray(size) { index -> ((seed * 31 + index) and 0x7F or 0x01).toByte() }

    /** Enqueues a job and claims it. Nothing may be processing when this is called. */
    suspend fun claimed(store: JobStore, owner: Long = 1L, audio: ByteArray = JobFixtures.audio(1)): ClaimedJob {
        val id = store.enqueue(owner, audio)
        val claim: ClaimedJob? = store.claimNext()
        assertNotNull("whisper-server: the fixture could not claim the job it just enqueued", claim)
        assertEquals("whisper-server: the fixture claimed a different job than the one it enqueued", id, claim!!.job.id)
        assertTrue("whisper-server: the fixture claim returned other audio than was enqueued", audio.contentEquals(claim.audio))
        return claim
    }

    /** Enqueues, claims and completes one job; returns its id. */
    suspend fun doneJob(store: JobStore, owner: Long = 1L, result: String = "result-text"): Long {
        val claim = claimed(store, owner)
        store.complete(claim.job.id, result)
        return claim.job.id
    }

    /** Enqueues, claims and fails one job; returns its id. */
    suspend fun failedJob(store: JobStore, owner: Long = 1L, error: String = "error-text"): Long {
        val claim = claimed(store, owner)
        store.fail(claim.job.id, error)
        return claim.job.id
    }
}
