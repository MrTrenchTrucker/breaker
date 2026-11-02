package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.SqliteDatabase
import java.time.Clock

/** Holds one row per folded username: wrong count, lock expiry, last attempt time. */
internal class SigninAttemptStore(
    private val db: SqliteDatabase,
    private val clock: Clock,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) {
    // Fifteen minutes in milliseconds.
    companion object {
        const val LOCK_WAIT_MS: Long = 15 * 60 * 1000
        const val DEFAULT_MAX_ROWS: Int = 10_000

        /** Whole seconds left in the lock, rounded UP, never less than 1. Pure. */
        fun retryAfterSeconds(lockedUntilMs: Long, nowMs: Long): Long {
            val remaining = lockedUntilMs - nowMs
            return Math.max(1, (remaining + 999) / 1000)
        }
    }

    // Records one wrong attempt for [foldedUsername]. Three cases:
    //   1. A row locked now (locked_until_ms in the future) changes nothing.
    //   2. An expired lock (locked_until_ms > 0 and not in the future) is treated
    //      as no row: the count restarts at 1.
    //   3. A row with locked_until_ms == 0 whose last_attempt_ms is more than one
    //      wait period before now counts as zero: the count restarts at 1.
    // Otherwise wrong_count increments by one; when it reaches 5, locked_until_ms
    // is set to now + LOCK_WAIT_MS.
    // Row cap: when the SELECT finds no row at all for the name (a brand new name),
    // count the rows; if count >= maxRows, evict the oldest unlocked row
    // (locked_until_ms <= now, smallest last_attempt_ms). If every row is locked,
    // return without inserting (nothing throws, the name is not counted). Then insert.
    // One transaction of its own (read, delete, write) on the single write lane;
    // never nested inside another transaction. The delete-then-insert replaces the
    // old row with the new one, both inside the same transaction.
    suspend fun recordWrong(foldedUsername: String) {
        val nowMs = clock.millis()
        db.transaction<Unit> { connection ->
            val existing = connection.prepareStatement(
                "SELECT wrong_count, locked_until_ms, last_attempt_ms FROM signin_attempts WHERE username_lower = ?",
            ).use { statement ->
                statement.setString(1, foldedUsername)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        Triple(rows.getLong(1), rows.getLong(2), rows.getLong(3))
                    } else {
                        null
                    }
                }
            }

            val (count, lockedUntil, lastAttempt) = when (existing) {
                null -> {
                    // Brand new name: enforce the row cap before inserting.
                    val rowCount = connection.prepareStatement(
                        "SELECT count(*) FROM signin_attempts",
                    ).use { statement ->
                        statement.executeQuery().use { rows ->
                            if (rows.next()) rows.getLong(1) else 0L
                        }
                    }
                    if (rowCount >= maxRows) {
                        val lockedCount = connection.prepareStatement(
                            "SELECT count(*) FROM signin_attempts WHERE locked_until_ms > ?",
                        ).use { statement ->
                            statement.setLong(1, nowMs)
                            statement.executeQuery().use { rows ->
                                if (rows.next()) rows.getLong(1) else 0L
                            }
                        }
                        if (lockedCount >= rowCount) {
                            // Every row is locked: do not insert, nothing throws.
                            return@transaction
                        }
                        connection.prepareStatement(
                            "DELETE FROM signin_attempts WHERE username_lower = (" +
                                "SELECT username_lower FROM signin_attempts " +
                                "WHERE locked_until_ms <= ? ORDER BY last_attempt_ms ASC LIMIT 1)",
                        ).use { statement ->
                            statement.setLong(1, nowMs)
                            statement.executeUpdate()
                        }
                    }
                    Triple(1L, 0L, nowMs)
                }
                else -> {
                    val (c, l, la) = existing
                    when {
                        l > nowMs -> {
                            // Locked now: change nothing. Do not even write.
                            return@transaction
                        }
                        l > 0 || la + LOCK_WAIT_MS < nowMs -> {
                            // Expired lock or stale partial count (more than one wait period ago): restart at 1.
                            Triple(1L, 0L, nowMs)
                        }
                        else -> {
                            val next = c + 1L
                            val locked = if (next == 5L) nowMs + LOCK_WAIT_MS else 0L
                            Triple(next, locked, nowMs)
                        }
                    }
                }
            }

            // Replace the old row with the new one: delete first, then insert the
            // updated row. Both statements run in the same transaction so the row
            // never briefly disappears.
            connection.prepareStatement("DELETE FROM signin_attempts WHERE username_lower = ?").use { statement ->
                statement.setString(1, foldedUsername)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, foldedUsername)
                statement.setLong(2, count)
                statement.setLong(3, lockedUntil)
                statement.setLong(4, lastAttempt)
                statement.executeUpdate()
            }
        }
    }

    // Deletes the attempts row for [foldedName]: used on success, password change, admin reset.
    suspend fun clear(foldedName: String) {
        db.transaction<Unit> { connection ->
            connection.prepareStatement("DELETE FROM signin_attempts WHERE username_lower = ?").use { statement ->
                statement.setString(1, foldedName)
                statement.executeUpdate()
            }
        }
    }

    // Returns the current row's count and lock, or null when no row exists. Read only,
    // no rule applied.
    suspend fun attemptOf(foldedName: String): AttemptInfo? {
        return db.transaction<AttemptInfo?> { connection ->
            connection.prepareStatement(
                "SELECT wrong_count, locked_until_ms, last_attempt_ms FROM signin_attempts WHERE username_lower = ?",
            ).use { statement ->
                statement.setString(1, foldedName)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        AttemptInfo(rows.getLong(1), rows.getLong(2), rows.getLong(3))
                    } else {
                        null
                    }
                }
            }
        }
    }

    /** The stored locked_until_ms when the row exists and is greater than now,
     * else 0L. One read in its own transaction. */
    suspend fun lockedUntil(foldedName: String): Long {
        return db.transaction<Long> { connection ->
            connection.prepareStatement(
                "SELECT locked_until_ms FROM signin_attempts WHERE username_lower = ?",
            ).use { statement ->
                statement.setString(1, foldedName)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        val until = rows.getLong(1)
                        if (until > clock.millis()) until else 0L
                    } else {
                        0L
                    }
                }
            }
        }
    }

    /** A read-only snapshot of one attempts row. */
    data class AttemptInfo(
        val wrongCount: Long,
        val lockedUntilMs: Long,
        val lastAttemptMs: Long,
    )
}
