package dev.breaker.server.whisper.jobs

// Every statement names its columns. Only the audio query selects the audio column,
// so a poll or a metadata read never drags a large value off its overflow pages.
// Status values are bound, never spelled in the SQL, so they cannot drift from JobStatus.
internal object JobSql {
    private const val META_COLUMNS =
        "id, owner_account_id, status, attempts, error, created_at_ms, started_at_ms, finished_at_ms"

    const val INSERT =
        "INSERT INTO jobs (owner_account_id, status, attempts, created_at_ms, audio) VALUES (?, ?, 0, ?, ?)"

    const val SELECT_ANY_WITH_STATUS = "SELECT 1 FROM jobs WHERE status = ? LIMIT 1"

    const val SELECT_OLDEST_ID_WITH_STATUS = "SELECT id FROM jobs WHERE status = ? ORDER BY id LIMIT 1"

    const val SELECT_META = "SELECT $META_COLUMNS FROM jobs WHERE id = ?"

    const val SELECT_AUDIO = "SELECT audio FROM jobs WHERE id = ?"

    const val SELECT_STATE = "SELECT status, attempts FROM jobs WHERE id = ?"

    const val SELECT_FOR_FETCH =
        "SELECT status, error, result, finished_at_ms FROM jobs WHERE id = ? AND owner_account_id = ?"

    const val START_ATTEMPT =
        "UPDATE jobs SET status = ?, attempts = attempts + 1, started_at_ms = ? WHERE id = ? AND status = ?"

    const val NEXT_ATTEMPT = "UPDATE jobs SET attempts = attempts + 1 WHERE id = ? AND status = ?"

    const val FINISH_DONE =
        "UPDATE jobs SET status = ?, result = ?, finished_at_ms = ?, audio = NULL WHERE id = ? AND status = ?"

    const val FINISH_FAILED =
        "UPDATE jobs SET status = ?, error = ?, finished_at_ms = ?, audio = NULL WHERE id = ? AND status = ?"

    // Must run before REQUEUE_INTERRUPTED: what is left in processing afterwards has
    // attempts left, which is what REQUEUE_INTERRUPTED relies on.
    const val FAIL_EXHAUSTED =
        "UPDATE jobs SET status = ?, error = ?, finished_at_ms = ?, audio = NULL " +
            "WHERE status = ? AND attempts >= ?"

    const val REQUEUE_INTERRUPTED = "UPDATE jobs SET status = ?, started_at_ms = NULL WHERE status = ?"

    const val CLEAR_RESULT = "UPDATE jobs SET result = NULL WHERE id = ?"

    const val PURGE_EXPIRED =
        "UPDATE jobs SET result = NULL WHERE status = ? AND result IS NOT NULL AND finished_at_ms <= ?"
}
