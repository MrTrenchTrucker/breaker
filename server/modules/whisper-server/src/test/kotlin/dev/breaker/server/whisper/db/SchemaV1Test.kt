package dev.breaker.server.whisper.db

import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

internal class SchemaV1Test : TempDatabaseTest() {

    private lateinit var temp: TempDatabase

    @Before
    fun openDatabase() {
        temp = openTemp()
    }

    private fun insert(row: Map<String, Any?>) {
        inTransaction(temp) { connection -> TestDatabases.insertJobRow(connection, row) }
    }

    private fun rowCount(): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT count(*) FROM jobs").single() }

    private fun processingRow(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base: Map<String, Any?> = mapOf("status" to "processing", "attempts" to 1, "started_at_ms" to 5L)
        return TestDatabases.validJobRow(base + extra)
    }

    private fun doneRow(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base: Map<String, Any?> = mapOf(
            "status" to "done",
            "attempts" to 1,
            "started_at_ms" to 5L,
            "finished_at_ms" to 9L,
            "result" to "text",
            "audio" to null,
        )
        return TestDatabases.validJobRow(base + extra)
    }

    private fun failedRow(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val base: Map<String, Any?> = mapOf(
            "status" to "failed",
            "attempts" to 1,
            "started_at_ms" to 5L,
            "finished_at_ms" to 9L,
            "error" to "boom",
            "audio" to null,
        )
        return TestDatabases.validJobRow(base + extra)
    }

    private fun assertAccepted(case: String, row: Map<String, Any?>) {
        val before = rowCount()
        try {
            insert(row)
        } catch (failure: SQLException) {
            fail("whisper-server: the database refused a valid row ($case): ${failure.message}")
        }
        assertEquals("whisper-server: the accepted row ($case) was not stored", before + 1, rowCount())
    }

    // The fragment is the text of the one CHECK the row breaks, so a refusal by some
    // other rule cannot stand in for the rule under test.
    private fun assertRefused(case: String, row: Map<String, Any?>, checkFragment: String) {
        val before = rowCount()
        val refusal: SQLException? = try {
            insert(row)
            null
        } catch (failure: SQLException) {
            failure
        }
        assertNotNull("whisper-server: the database accepted a row with $case", refusal)
        val message = refusal?.message.orEmpty()
        assertTrue(
            "whisper-server: $case was refused by another rule than the one meant ($checkFragment): $message",
            message.contains(checkFragment),
        )
        assertEquals("whisper-server: a refused row ($case) was stored", before, rowCount())
    }

    private val audioRule = "(status IN ('queued','processing')) = (audio IS NOT NULL)"
    private val finishedRule = "(status IN ('done','failed')) = (finished_at_ms IS NOT NULL)"

    @Test
    fun `the schema has a jobs table and the sequence table of its autoincrement key`() {
        val tables = inTransaction(temp) { connection -> TestDatabases.tableNames(connection) }
        assertEquals("whisper-server: tables after the migration", listOf("jobs", "sqlite_sequence"), tables)
    }

    @Test
    fun `the columns are in storage order with the audio last`() {
        val columns = inTransaction(temp) { connection -> TestDatabases.columnNames(connection, "jobs") }
        assertEquals(
            "whisper-server: jobs columns and their order",
            listOf(
                "id",
                "owner_account_id",
                "status",
                "attempts",
                "error",
                "result",
                "created_at_ms",
                "started_at_ms",
                "finished_at_ms",
                "audio",
            ),
            columns,
        )
    }

    @Test
    fun `the status and id index exists on exactly those columns in that order`() {
        val indexed = inTransaction(temp) { connection ->
            TestDatabases.strings(
                connection,
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'jobs_status_id'",
            ) + TestDatabases.strings(connection, "SELECT name FROM pragma_index_info('jobs_status_id') ORDER BY seqno")
        }
        assertEquals("whisper-server: index jobs_status_id and its columns", listOf("jobs_status_id", "status", "id"), indexed)
    }

    @Test
    fun `a queued job with audio is accepted`() {
        assertAccepted("queued with audio", TestDatabases.validJobRow())
    }

    @Test
    fun `a processing job with audio and a start time is accepted`() {
        assertAccepted("processing with audio and started_at_ms", processingRow())
    }

    @Test
    fun `a done job with a result and a finish time and no audio is accepted`() {
        assertAccepted("done with a result and no audio", doneRow())
    }

    @Test
    fun `a done job whose result was already fetched is accepted`() {
        assertAccepted("done with a NULL result", doneRow(mapOf("result" to null)))
    }

    @Test
    fun `a failed job with an error and a finish time and no audio is accepted`() {
        assertAccepted("failed with an error and no audio", failedRow())
    }

    @Test
    fun `an unknown status is refused`() {
        // No audio and no timestamps, so the status list is the only rule this row can break.
        assertRefused(
            "status waiting",
            TestDatabases.validJobRow(mapOf("status" to "waiting", "audio" to null)),
            "status IN ('queued','processing','done','failed')",
        )
    }

    @Test
    fun `a negative attempt count is refused`() {
        assertRefused("attempts -1", TestDatabases.validJobRow(mapOf("attempts" to -1)), "attempts >= 0")
    }

    @Test
    fun `a queued job without audio is refused`() {
        assertRefused("a queued job without audio", TestDatabases.validJobRow(mapOf("audio" to null)), audioRule)
    }

    @Test
    fun `a processing job without audio is refused`() {
        assertRefused("a processing job without audio", processingRow(mapOf("audio" to null)), audioRule)
    }

    @Test
    fun `a done job that still holds audio is refused`() {
        assertRefused("a done job with audio", doneRow(mapOf("audio" to ByteArray(8) { 1 })), audioRule)
    }

    @Test
    fun `a failed job that still holds audio is refused`() {
        assertRefused("a failed job with audio", failedRow(mapOf("audio" to ByteArray(8) { 1 })), audioRule)
    }

    @Test
    fun `a queued job with zero length audio is refused`() {
        // SQL spells the empty value so the test does not depend on how the driver binds an empty array.
        val before = rowCount()
        val refusal = TestDatabases.expectFailure<SQLException>("zero length audio") {
            inTransaction(temp) { connection ->
                connection.executeStatement(
                    "INSERT INTO jobs (owner_account_id, status, attempts, created_at_ms, audio) " +
                        "VALUES (1, 'queued', 0, 0, zeroblob(0))",
                )
            }
        }
        assertTrue(
            "whisper-server: zero length audio was refused by another rule: ${refusal.message}",
            refusal.message.orEmpty().contains("audio IS NULL OR length(audio) > 0"),
        )
        assertEquals("whisper-server: a zero length audio row was stored", before, rowCount())
    }

    @Test
    fun `a queued job with a result is refused`() {
        assertRefused(
            "a queued job with a result",
            TestDatabases.validJobRow(mapOf("result" to "text")),
            "result IS NULL OR status = 'done'",
        )
    }

    @Test
    fun `a failed job with a result is refused`() {
        assertRefused(
            "a failed job with a result",
            failedRow(mapOf("result" to "text")),
            "result IS NULL OR status = 'done'",
        )
    }

    @Test
    fun `a done job with an error is refused`() {
        assertRefused(
            "a done job with an error",
            doneRow(mapOf("error" to "boom")),
            "error IS NULL OR status = 'failed'",
        )
    }

    @Test
    fun `a failed job without an error is refused`() {
        assertRefused(
            "a failed job without an error",
            failedRow(mapOf("error" to null)),
            "status <> 'failed' OR error IS NOT NULL",
        )
    }

    @Test
    fun `a done job without a finish time is refused`() {
        assertRefused("a done job without finished_at_ms", doneRow(mapOf("finished_at_ms" to null)), finishedRule)
    }

    @Test
    fun `a queued job with a finish time is refused`() {
        assertRefused("a queued job with finished_at_ms", TestDatabases.validJobRow(mapOf("finished_at_ms" to 9L)), finishedRule)
    }

    @Test
    fun `a queued job with a start time is refused`() {
        assertRefused(
            "a queued job with started_at_ms",
            TestDatabases.validJobRow(mapOf("started_at_ms" to 5L)),
            "status <> 'queued' OR started_at_ms IS NULL",
        )
    }

    @Test
    fun `a processing job without a start time is refused`() {
        assertRefused(
            "a processing job without started_at_ms",
            processingRow(mapOf("started_at_ms" to null)),
            "status <> 'processing' OR started_at_ms IS NOT NULL",
        )
    }

    @Test
    fun `an id is never reused after the newest job is deleted`() {
        insert(TestDatabases.validJobRow())
        insert(TestDatabases.validJobRow())
        inTransaction(temp) { connection -> connection.executeStatement("DELETE FROM jobs WHERE id = 2") }
        insert(TestDatabases.validJobRow())
        val ids = inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT id FROM jobs ORDER BY id") }
        assertEquals("whisper-server: a deleted id was handed out again", listOf(1L, 3L), ids)
    }
}
