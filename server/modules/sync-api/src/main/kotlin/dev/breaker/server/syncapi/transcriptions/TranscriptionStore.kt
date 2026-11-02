package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.SqliteDatabase
import java.sql.Connection
import java.time.Clock

internal class TranscriptionStore(
    private val db: SqliteDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Stores one transcription of [accountId], or decides it is already stored. The whole decision
     * runs in one transaction: the account's key version and the row for the pair are read, then
     * one row is inserted or replaced, or nothing is written.
     *
     * An account id with no row is not a version conflict: the store throws, and the caller decides
     * what the client sees.
     */
    suspend fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String): PushResult {
        val nowMs = clock.millis()
        return db.transaction<PushResult> { connection ->
            val accountKeyVersion: Int = readAccountKeyVersion(connection, accountId)
                ?: throw IllegalStateException("sync-api: a transcription push names an account with no row")
            // A client cannot be ahead of its account, whatever rows it already holds. This is
            // checked before the row lookup, so a stored row cannot be replaced by a push above
            // the account's key version.
            if (keyVersion > accountKeyVersion) {
                PushResult.RefusedAboveAccount
            } else {
                val storedKeyVersion: Int? = readStoredKeyVersion(connection, accountId, clientId)
                if (storedKeyVersion == null) {
                    if (keyVersion < accountKeyVersion) {
                        PushResult.RefusedLower
                    } else {
                        insertRow(connection, accountId, clientId, keyVersion, encrypted, nowMs)
                        PushResult.Stored
                    }
                } else if (storedKeyVersion == keyVersion) {
                    PushResult.NoOp
                } else if (storedKeyVersion > keyVersion) {
                    PushResult.RefusedLower
                } else {
                    replaceRow(connection, accountId, clientId, keyVersion, encrypted, nowMs)
                    PushResult.Replaced
                }
            }
        }
    }

    /** The rows of [accountId], oldest first by insert order. A replaced row keeps its place. */
    suspend fun list(accountId: Long): List<TranscriptionRow> =
        db.transaction<List<TranscriptionRow>> { connection -> readRows(connection, accountId) }

    private fun readAccountKeyVersion(connection: Connection, accountId: Long): Int? =
        connection.prepareStatement(SELECT_ACCOUNT_KEY_VERSION_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) else null
            }
        }

    private fun readStoredKeyVersion(connection: Connection, accountId: Long, clientId: String): Int? =
        connection.prepareStatement(SELECT_STORED_KEY_VERSION_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) else null
            }
        }

    private fun insertRow(
        connection: Connection,
        accountId: Long,
        clientId: String,
        keyVersion: Int,
        encrypted: String,
        storedAtMs: Long,
    ) {
        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.setInt(3, keyVersion)
            statement.setString(4, encrypted)
            statement.setLong(5, storedAtMs)
            statement.executeUpdate()
        }
    }

    // An UPDATE of the same row: the id, and so the place in the list, does not change.
    private fun replaceRow(
        connection: Connection,
        accountId: Long,
        clientId: String,
        keyVersion: Int,
        encrypted: String,
        storedAtMs: Long,
    ) {
        connection.prepareStatement(REPLACE_SQL).use { statement ->
            statement.setInt(1, keyVersion)
            statement.setString(2, encrypted)
            statement.setLong(3, storedAtMs)
            statement.setLong(4, accountId)
            statement.setString(5, clientId)
            statement.executeUpdate()
        }
    }

    private fun readRows(connection: Connection, accountId: Long): List<TranscriptionRow> =
        connection.prepareStatement(SELECT_ROWS_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                val result = ArrayList<TranscriptionRow>()
                while (rows.next()) {
                    result.add(TranscriptionRow(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getLong(4)))
                }
                result
            }
        }

    private companion object {
        const val SELECT_ACCOUNT_KEY_VERSION_SQL = "SELECT key_version FROM accounts WHERE id = ?"

        const val SELECT_STORED_KEY_VERSION_SQL =
            "SELECT key_version FROM transcriptions WHERE account_id = ? AND client_id = ?"

        const val INSERT_SQL =
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms) " +
                "VALUES (?, ?, ?, ?, ?)"

        const val REPLACE_SQL =
            "UPDATE transcriptions SET key_version = ?, encrypted = ?, stored_at_ms = ? " +
                "WHERE account_id = ? AND client_id = ?"

        const val SELECT_ROWS_SQL =
            "SELECT client_id, key_version, encrypted, stored_at_ms FROM transcriptions " +
                "WHERE account_id = ? ORDER BY id"
    }
}
