package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.SqliteDatabase
import io.ktor.server.application.ApplicationCall
import java.sql.Connection
import java.time.Clock

/** The result of checking one address against one fixed-window limit. */
internal sealed class RateLimitResult {
    /** Allowed: the address is counted in its window and the request may proceed. */
    object Allowed : RateLimitResult()
    /** Refused: the address is over its cap in the current window. */
    data class Refused(val retryAfterSeconds: Int) : RateLimitResult()
}

/**
 * Persisted per-(route, address) fixed-window counters.
 *
 * One row per (route, address). The global backstop shares the same table under
 * address "*". Fixed [WINDOW_MS] window starting at the first counted request of
 * the key; once [cap] requests are counted inside the window the next is refused.
 * A refused request is not added to the count and does not move the window start.
 * When [WINDOW_MS] have passed since the window start the next request opens a
 * new window with count 1.
 *
 * Windows older than a minute are dropped by the sweep so the table cannot grow
 * with traffic (see runRateLimitCounterSweep).
 *
 * Mirrors [SigninAttemptStore]: row cap [DEFAULT_MAX_ROWS] with oldest-first
 * eviction; a full table of live counters answers for a new key without counting
 * it (never refuses a new key because the table is full).
 *
 * All of one transaction on the single database lane. The clock is injected:
 * tests pass their own fake [Clock] and never sleep.
 *
 * Source for the numbers: ADR-041
 * (register 10/60s + global 120/60s; salt 60/60s + global 1200/60s; fixed 60 s window).
 */
internal class RateLimitCounterStore(
    private val db: SqliteDatabase,
    private val clock: Clock,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    // The seam for the source address: by default the socket peer. A test passes its own to play several callers.
    private val peerOf: (ApplicationCall) -> String = { call -> call.request.local.remoteAddress },
) {
    companion object {
        // One fixed 60 000 ms window for every limit.
        const val WINDOW_MS: Long = 60_000
        const val DEFAULT_MAX_ROWS: Int = 10_000
        // The marker address under which the global backstop counter lives.
        const val GLOBAL_ADDRESS: String = "*"
    }

    /** The source address of [call]: the socket peer, never a header (the seam [peerOf] aside). */
    internal fun peerAddress(call: ApplicationCall): String = peerOf(call)

    /** One check of the single key ([route], [address]) against [cap]: counts it, or refuses it. */
    suspend fun check(route: String, address: String, cap: Int): RateLimitResult =
        attempt(route, listOf(Key(address, cap)))

    /**
     * One transaction that reads the per-address row and the global backstop row, refuses if either cap
     * would be exceeded, and otherwise counts both. A refused request never touches any counter.
     */
    internal suspend fun checkPair(route: String, address: String, perAddressCap: Int, globalCap: Int): RateLimitResult =
        attempt(route, listOf(Key(address, perAddressCap), Key(GLOBAL_ADDRESS, globalCap)).distinctBy { it.address })

    private class Key(val address: String, val cap: Int)

    private suspend fun attempt(route: String, keys: List<Key>): RateLimitResult {
        val nowMs = clock.millis()
        return db.transaction<RateLimitResult> { connection ->
            val rows = keys.map { readRow(connection, route, it.address) }
            for (index in keys.indices) {
                val refused = checkCaps(nowMs, rows[index], keys[index].cap)
                if (refused != null) return@transaction refused
            }
            for (index in keys.indices) {
                writeOrUpdate(connection, route, keys[index].address, rows[index], nowMs)
            }
            RateLimitResult.Allowed
        }
    }

    /**
     * Returns a Refused if [existing] would exceed [cap] in the current window,
     * else null. An absent or expired row never refuses.
     */
    private fun checkCaps(
        nowMs: Long,
        existing: Pair<Long, Long>?,
        cap: Int,
    ): RateLimitResult.Refused? {
        if (existing == null) return null
        val (count, windowStart) = existing
        if (nowMs - windowStart >= WINDOW_MS) return null // expired, treat as absent
        if (count + 1 > cap.toLong()) {
            val timeLeftMs = WINDOW_MS - (nowMs - windowStart)
            val retryAfter = ((timeLeftMs + 999) / 1000).coerceAtLeast(1)
            return RateLimitResult.Refused(retryAfter.toInt())
        }
        return null
    }

    private fun readRow(connection: Connection, route: String, address: String): Pair<Long, Long>? =
        connection.prepareStatement(
            "SELECT count, window_start_ms FROM rate_limit_counters WHERE route = ? AND address = ?",
        ).use { statement ->
            statement.setString(1, route)
            statement.setString(2, address)
            statement.executeQuery().use { rows ->
                if (rows.next()) Pair(rows.getLong(1), rows.getLong(2)) else null
            }
        }

    private fun writeOrUpdate(
        connection: Connection,
        route: String,
        address: String,
        existing: Pair<Long, Long>?,
        nowMs: Long,
    ) {
        if (existing == null) {
            // Enforce the row cap before inserting a brand-new key.
            val rowCount = connection.prepareStatement("SELECT count(*) FROM rate_limit_counters").use { statement ->
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1) else 0L
                }
            }
            if (rowCount >= maxRows) return // answer without counting, never refuse a new key
            connection.prepareStatement(
                "INSERT INTO rate_limit_counters (route, address, count, window_start_ms) VALUES (?, ?, 1, ?)",
            ).use { statement ->
                statement.setString(1, route)
                statement.setString(2, address)
                statement.setLong(3, nowMs)
                statement.executeUpdate()
            }
        } else {
            val (count, windowStart) = existing
            if (nowMs - windowStart >= WINDOW_MS) {
                connection.prepareStatement(
                    "UPDATE rate_limit_counters SET count = 1, window_start_ms = ? WHERE route = ? AND address = ?",
                ).use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setString(2, route)
                    statement.setString(3, address)
                    statement.executeUpdate()
                }
            } else {
                connection.prepareStatement(
                    "UPDATE rate_limit_counters SET count = ? WHERE route = ? AND address = ?",
                ).use { statement ->
                    statement.setLong(1, count + 1)
                    statement.setString(2, route)
                    statement.setString(3, address)
                    statement.executeUpdate()
                }
            }
        }
    }

    /** Deletes counter rows whose fixed window has expired (older than WINDOW_MS). */
    internal suspend fun deleteStale() {
        val cutoff = clock.millis() - WINDOW_MS
        db.transaction<Unit> { connection ->
            connection.prepareStatement(
                "DELETE FROM rate_limit_counters WHERE window_start_ms <= ?",
            ).use { statement ->
                statement.setLong(1, cutoff)
                statement.executeUpdate()
            }
        }
    }
}
