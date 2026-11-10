package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.PrevKeyRule
import java.sql.Connection

// Purges stored transcriptions of one account, on the caller's connection, so the caller can run it
// inside its own transaction. deleteAllOn deletes every row and writes no change number and no
// tombstone: the caller sets purged_through, so every old cursor gets a full resync. purgeDeleteRecordsOn
// deletes only the delete records at or below a floor and raises purged_through to the highest number
// it removed; live rows are never touched.
internal object TranscriptionPurge {

    /** Deletes all rows of [accountId] and returns how many were deleted. */
    fun deleteAllOn(connection: Connection, accountId: Long): Int =
        connection.prepareStatement(DELETE_ALL_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }

    /**
     * Removes the delete records of [accountId] whose change number is at or below [floor], then
     * raises purged_through of the account to the highest number removed. Returns that number, or 0
     * when nothing was removed, in which case nothing is written. Runs on the caller's connection.
     */
    fun purgeDeleteRecordsOn(connection: Connection, accountId: Long, floor: Long): Long {
        // A device whose number is the floor has seen the record at the floor, so the record is
        // removed; live rows stay.
        val highest = highestDeleteNumberOn(connection, accountId, floor) ?: return 0L
        connection.prepareStatement(DELETE_DELETE_RECORDS_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, floor)
            statement.executeUpdate()
        }
        connection.prepareStatement(RAISE_PURGED_THROUGH_SQL).use { statement ->
            statement.setLong(1, highest)
            statement.setLong(2, accountId)
            statement.setLong(3, highest)
            statement.executeUpdate()
        }
        return highest
    }

    // Null when the account has no delete record at or below the floor.
    private fun highestDeleteNumberOn(connection: Connection, accountId: Long, floor: Long): Long? =
        connection.prepareStatement(SELECT_HIGHEST_DELETE_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, floor)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api: MAX over a purge range returned no row" }
                val value = rows.getLong(1)
                if (rows.wasNull()) null else value
            }
        }

    private const val DELETE_ALL_SQL = "DELETE FROM transcriptions WHERE account_id = ?"

    private const val SELECT_HIGHEST_DELETE_SQL =
        "SELECT MAX(change_no) FROM transcriptions WHERE account_id = ? AND deleted = 1 AND change_no <= ?"

    private const val DELETE_DELETE_RECORDS_SQL =
        "DELETE FROM transcriptions WHERE account_id = ? AND deleted = 1 AND change_no <= ?"

    // Only ever raises the number: the lower-than test keeps a later purge from lowering it.
    private const val RAISE_PURGED_THROUGH_SQL =
        "UPDATE accounts SET purged_through = ? WHERE id = ? AND purged_through < ?"

    // 90 days in milliseconds (ADR-010 precise rule: 90 x 24 h, UTC instants, no time zone).
    const val RETENTION_TTL_MS: Long = 90L * 24 * 60 * 60 * 1000  // 7_776_000_000L

    private const val EXPIRE_TTL_SQL =
        "SELECT client_id FROM transcriptions WHERE account_id = ? AND deleted = 0 AND stored_at_ms < ? ORDER BY stored_at_ms, id LIMIT ?"

    /**
     * Turns live rows of [accountId] whose stored_at_ms is older than the TTL into delete records,
     * on [store]'s connection inside the caller's transaction. Oldest first, at most [batchCap]
     * rows, returns how many it did. A row exactly at the cutoff (stored_at_ms == nowMs - ttlMs) is
     * kept (strict <). Each row becomes a delete record through store.markDeleted: a new change
     * number, body cleared, deleted set, stored_at_ms = nowMs. PrevKeyRule.clearIfNoOldRows is
     * called once for the batch (like clearAll at TranscriptionStore.kt:99-113), never raises
     * purged_through, never touches already-deleted rows or other accounts.
     */
    internal fun expireDueOn(
        store: TranscriptionStore,
        connection: Connection,
        accountId: Long,
        ttlMs: Long,
        nowMs: Long,
        batchCap: Int,
    ): Int {
        require(batchCap >= 1) { "sync-api: batch cap must be >= 1, got $batchCap" }
        require(ttlMs > 0) { "sync-api: ttl must be > 0, got $ttlMs" }
        val cutoff = nowMs - ttlMs
        val rows = connection.prepareStatement(EXPIRE_TTL_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setLong(2, cutoff)
            statement.setInt(3, batchCap)
            statement.executeQuery().use { rs ->
                val result = ArrayList<String>()
                while (rs.next()) {
                    result.add(rs.getString(1))
                }
                result
            }
        }
        var marked = 0
        for (clientId in rows) {
            val changeNo = store.bumpChangeSeq(connection, accountId)
            store.markDeleted(connection, accountId, clientId, nowMs, changeNo)
            marked++
        }
        if (marked > 0) {
            PrevKeyRule.clearIfNoOldRows(connection, accountId)
        }
        return marked
    }
}
