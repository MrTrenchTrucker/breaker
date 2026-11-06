package dev.breaker.server.syncapi.transcriptions

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
}
