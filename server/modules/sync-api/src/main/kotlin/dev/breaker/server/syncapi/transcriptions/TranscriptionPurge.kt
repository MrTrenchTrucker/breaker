package dev.breaker.server.syncapi.transcriptions

import java.sql.Connection

// Deletes every stored transcription of one account, on the caller's connection, so the caller can
// run it inside its own transaction. It writes no change number and no tombstone: the caller sets
// purged_through, so every old cursor gets a full resync.
internal object TranscriptionPurge {

    /** Deletes all rows of [accountId] and returns how many were deleted. */
    fun deleteAllOn(connection: Connection, accountId: Long): Int =
        connection.prepareStatement(DELETE_ALL_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }

    private const val DELETE_ALL_SQL = "DELETE FROM transcriptions WHERE account_id = ?"
}
