package dev.breaker.server.whisper.jobs

import dev.breaker.server.whisper.db.SqliteDatabase
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

internal class JobStore(
    private val db: SqliteDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class State(val status: JobStatus, val attempts: Int)

    // cleared tells the caller, after the transaction, whether a result was removed.
    private class FetchOutcome(val poll: JobPoll, val cleared: Boolean)

    private class Recovery(val failed: Int, val requeued: Int)

    suspend fun enqueue(ownerAccountId: Long, audio: ByteArray): Long {
        require(audio.isNotEmpty()) { "whisper-server: the audio is empty" }
        val nowMs = clock.millis()
        return db.transaction<Long> { connection ->
            connection.prepareStatement(JobSql.INSERT).use { statement ->
                statement.setLong(1, ownerAccountId)
                statement.setString(2, JobStatus.QUEUED.dbValue)
                statement.setLong(3, nowMs)
                statement.setBytes(4, audio)
                statement.executeUpdate()
            }
            readLastInsertId(connection)
        }
    }

    suspend fun claimNext(): ClaimedJob? {
        val nowMs = clock.millis()
        return db.transaction<ClaimedJob?> { connection ->
            // One processing row blocks every claim: this is what keeps a second
            // worker from running a second job at the same time.
            if (existsWithStatus(connection, JobStatus.PROCESSING)) {
                null
            } else {
                val id = readOldestQueuedId(connection)
                if (id == null) null else startAttempt(connection, id, nowMs)
            }
        }
    }

    suspend fun beginRetryAttempt(jobId: Long): Int =
        db.transaction<Int> { connection ->
            val state = readState(connection, jobId)
            if (state == null || state.status != JobStatus.PROCESSING) {
                throw IllegalJobTransitionException("whisper-server: job $jobId is not processing")
            }
            if (state.attempts >= JobPolicy.MAX_ATTEMPTS) {
                throw IllegalJobTransitionException("whisper-server: job $jobId has no attempts left")
            }
            connection.prepareStatement(JobSql.NEXT_ATTEMPT).use { statement ->
                statement.setLong(1, jobId)
                statement.setString(2, JobStatus.PROCESSING.dbValue)
                statement.executeUpdate()
            }
            state.attempts + 1
        }

    suspend fun complete(jobId: Long, result: String) {
        require(result.isNotBlank()) { "whisper-server: the result is blank" }
        val nowMs = clock.millis()
        db.transaction<Unit> { connection ->
            requireProcessing(connection, jobId)
            connection.prepareStatement(JobSql.FINISH_DONE).use { statement ->
                statement.setString(1, JobStatus.DONE.dbValue)
                statement.setString(2, result)
                statement.setLong(3, nowMs)
                statement.setLong(4, jobId)
                statement.setString(5, JobStatus.PROCESSING.dbValue)
                statement.executeUpdate()
            }
        }
        // Outside the transaction: the pragma cannot run inside one, and only after the
        // commit is the audio gone from the log file too.
        db.checkpointTruncate()
    }

    suspend fun fail(jobId: Long, error: String) {
        require(error.isNotBlank()) { "whisper-server: the error text is blank" }
        val nowMs = clock.millis()
        db.transaction<Unit> { connection ->
            requireProcessing(connection, jobId)
            connection.prepareStatement(JobSql.FINISH_FAILED).use { statement ->
                statement.setString(1, JobStatus.FAILED.dbValue)
                statement.setString(2, error)
                statement.setLong(3, nowMs)
                statement.setLong(4, jobId)
                statement.setString(5, JobStatus.PROCESSING.dbValue)
                statement.executeUpdate()
            }
        }
        db.checkpointTruncate()
    }

    suspend fun recoverInterrupted(): Int {
        val nowMs = clock.millis()
        val recovery = db.transaction<Recovery> { connection ->
            val failed = failExhausted(connection, nowMs)
            val requeued = connection.prepareStatement(JobSql.REQUEUE_INTERRUPTED).use { statement ->
                statement.setString(1, JobStatus.QUEUED.dbValue)
                statement.setString(2, JobStatus.PROCESSING.dbValue)
                statement.executeUpdate()
            }
            Recovery(failed, requeued)
        }
        // Only a failed row dropped audio; a requeued row keeps its audio on purpose.
        if (recovery.failed > 0) db.checkpointTruncate()
        return recovery.failed + recovery.requeued
    }

    suspend fun fetch(jobId: Long, ownerAccountId: Long): JobPoll {
        val nowMs = clock.millis()
        // Read and clear share one transaction: that is what makes "the first fetch
        // gets the result" happen exactly once, however many callers race for it.
        val outcome = db.transaction<FetchOutcome> { connection ->
            fetchInTransaction(connection, jobId, ownerAccountId, nowMs)
        }
        if (outcome.cleared) db.checkpointTruncate()
        return outcome.poll
    }

    suspend fun purgeExpired(): Int {
        val nowMs = clock.millis()
        val cleared = db.transaction<Int> { connection ->
            connection.prepareStatement(JobSql.PURGE_EXPIRED).use { statement ->
                statement.setString(1, JobStatus.DONE.dbValue)
                statement.setLong(2, JobPolicy.expiryCutoffMs(nowMs))
                statement.executeUpdate()
            }
        }
        if (cleared > 0) db.checkpointTruncate()
        return cleared
    }

    suspend fun get(jobId: Long): Job? = db.transaction<Job?> { connection -> readJob(connection, jobId) }

    private fun fetchInTransaction(
        connection: Connection,
        jobId: Long,
        ownerAccountId: Long,
        nowMs: Long,
    ): FetchOutcome =
        connection.prepareStatement(JobSql.SELECT_FOR_FETCH).use { statement ->
            statement.setLong(1, jobId)
            statement.setLong(2, ownerAccountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) answerFor(connection, jobId, rows, nowMs) else FetchOutcome(JobPoll.NotFound, false)
            }
        }

    private fun answerFor(connection: Connection, jobId: Long, rows: ResultSet, nowMs: Long): FetchOutcome {
        val status = JobStatus.fromDb(rows.getString(1))
        if (status == JobStatus.QUEUED || status == JobStatus.PROCESSING) {
            return FetchOutcome(JobPoll.Pending(status), false)
        }
        if (status == JobStatus.FAILED) {
            val error: String? = rows.getString(2)
            val reason = checkNotNull(error) { "whisper-server: failed job without an error" }
            return FetchOutcome(JobPoll.Failed(reason), false)
        }
        val text: String? = rows.getString(3)
        if (text == null) return FetchOutcome(JobPoll.ResultGone, false)
        return takeResult(connection, jobId, text, rows.getLong(4), nowMs)
    }

    // An expired result is cleared and reported gone even when no purge has run yet.
    private fun takeResult(
        connection: Connection,
        jobId: Long,
        text: String,
        finishedAtMs: Long,
        nowMs: Long,
    ): FetchOutcome {
        connection.prepareStatement(JobSql.CLEAR_RESULT).use { statement ->
            statement.setLong(1, jobId)
            statement.executeUpdate()
        }
        val expired = finishedAtMs <= JobPolicy.expiryCutoffMs(nowMs)
        return FetchOutcome(if (expired) JobPoll.ResultGone else JobPoll.Result(text), true)
    }

    private fun startAttempt(connection: Connection, id: Long, nowMs: Long): ClaimedJob {
        connection.prepareStatement(JobSql.START_ATTEMPT).use { statement ->
            statement.setString(1, JobStatus.PROCESSING.dbValue)
            statement.setLong(2, nowMs)
            statement.setLong(3, id)
            statement.setString(4, JobStatus.QUEUED.dbValue)
            check(statement.executeUpdate() == 1) { "whisper-server: the queued job could not be claimed" }
        }
        val job = checkNotNull(readJob(connection, id)) { "whisper-server: the claimed job could not be read back" }
        return ClaimedJob(job, readAudio(connection, id))
    }

    private fun failExhausted(connection: Connection, nowMs: Long): Int =
        connection.prepareStatement(JobSql.FAIL_EXHAUSTED).use { statement ->
            statement.setString(1, JobStatus.FAILED.dbValue)
            statement.setString(2, INTERRUPTED_ERROR)
            statement.setLong(3, nowMs)
            statement.setString(4, JobStatus.PROCESSING.dbValue)
            statement.setInt(5, JobPolicy.MAX_ATTEMPTS)
            statement.executeUpdate()
        }

    private fun requireProcessing(connection: Connection, jobId: Long) {
        val state = readState(connection, jobId)
        if (state == null) {
            throw IllegalJobTransitionException("whisper-server: job $jobId does not exist")
        }
        if (state.status != JobStatus.PROCESSING) {
            throw IllegalJobTransitionException(
                "whisper-server: job $jobId is not processing (status ${state.status.dbValue})",
            )
        }
    }

    private fun existsWithStatus(connection: Connection, status: JobStatus): Boolean =
        connection.prepareStatement(JobSql.SELECT_ANY_WITH_STATUS).use { statement ->
            statement.setString(1, status.dbValue)
            statement.executeQuery().use { rows -> rows.next() }
        }

    private fun readOldestQueuedId(connection: Connection): Long? =
        connection.prepareStatement(JobSql.SELECT_OLDEST_ID_WITH_STATUS).use { statement ->
            statement.setString(1, JobStatus.QUEUED.dbValue)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    private fun readState(connection: Connection, jobId: Long): State? =
        connection.prepareStatement(JobSql.SELECT_STATE).use { statement ->
            statement.setLong(1, jobId)
            statement.executeQuery().use { rows ->
                if (rows.next()) State(JobStatus.fromDb(rows.getString(1)), rows.getInt(2)) else null
            }
        }

    private fun readJob(connection: Connection, jobId: Long): Job? =
        connection.prepareStatement(JobSql.SELECT_META).use { statement ->
            statement.setLong(1, jobId)
            statement.executeQuery().use { rows -> if (rows.next()) toJob(rows) else null }
        }

    private fun readAudio(connection: Connection, jobId: Long): ByteArray =
        connection.prepareStatement(JobSql.SELECT_AUDIO).use { statement ->
            statement.setLong(1, jobId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "whisper-server: the claimed job has no row" }
                val audio: ByteArray? = rows.getBytes(1)
                checkNotNull(audio) { "whisper-server: the claimed job has no audio" }
            }
        }

    private fun readLastInsertId(connection: Connection): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
                check(rows.next()) { "whisper-server: the new job row could not be read back" }
                rows.getLong(1)
            }
        }

    private fun toJob(rows: ResultSet): Job {
        val error: String? = rows.getString(5)
        return Job(
            id = rows.getLong(1),
            ownerAccountId = rows.getLong(2),
            status = JobStatus.fromDb(rows.getString(3)),
            attempts = rows.getInt(4),
            error = error,
            createdAt = Instant.ofEpochMilli(rows.getLong(6)),
            startedAt = optionalInstant(rows, 7),
            finishedAt = optionalInstant(rows, 8),
        )
    }

    // getLong answers 0 for NULL, so wasNull must be asked right after it.
    private fun optionalInstant(rows: ResultSet, column: Int): Instant? {
        val millis = rows.getLong(column)
        return if (rows.wasNull()) null else Instant.ofEpochMilli(millis)
    }

    private companion object {
        const val INTERRUPTED_ERROR = "the service call was interrupted by a restart and no retries are left"
    }
}
