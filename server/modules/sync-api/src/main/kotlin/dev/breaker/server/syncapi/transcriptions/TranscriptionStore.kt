package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.SqliteDatabase
import java.sql.Connection
import java.time.Clock

/**
 * The account's current key version. Every account is at 0 today because no code path
 * can raise it, so a push above this value is refused.
 */
internal object KeyVersions {
    const val CURRENT: Int = 0
}

/**
 * Stores transcriptions per account, one row per (account, client id). Every statement
 * names the account column, so one account never reads or writes another account's rows.
 */
internal class TranscriptionStore(
    private val db: SqliteDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Stores one row unless the account already holds the pair; a row that is already held is
     * left as it was. A key version above [KeyVersions.CURRENT] is refused with nothing written.
     * Input the database refuses (a client id that is not 36 characters, a negative key version,
     * an empty text, an account that does not exist) throws a SQLException and writes nothing.
     * The stored time is the clock's time cut to the whole second, read once before the transaction.
     */
    suspend fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String): PushResult {
        val nowMs = (clock.millis() / 1000) * 1000
        return db.transaction<PushResult> { connection ->
            if (keyVersion > KeyVersions.CURRENT) {
                PushResult.VersionRefused
            } else if (rowExists(connection, accountId, clientId)) {
                PushResult.AlreadyHeld
            } else {
                insertRow(connection, accountId, clientId, keyVersion, encrypted, nowMs)
                PushResult.Stored
            }
        }
    }

    /** Every row of this account, oldest first; rows with one stored time are ordered by client id. */
    suspend fun list(accountId: Long): List<StoredTranscription> =
        db.transaction<List<StoredTranscription>> { connection -> selectRows(connection, accountId) }

    private fun rowExists(connection: Connection, accountId: Long, clientId: String): Boolean =
        connection.prepareStatement(EXISTS_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.executeQuery().use { rows -> rows.next() }
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

    private fun selectRows(connection: Connection, accountId: Long): List<StoredTranscription> =
        connection.prepareStatement(LIST_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                val found = ArrayList<StoredTranscription>()
                while (rows.next()) {
                    found.add(
                        StoredTranscription(
                            clientId = rows.getString(1),
                            keyVersion = rows.getInt(2),
                            encrypted = rows.getString(3),
                            storedAtMs = rows.getLong(4),
                        ),
                    )
                }
                found
            }
        }

    private companion object {
        const val EXISTS_SQL = "SELECT 1 FROM transcriptions WHERE account_id = ? AND client_id = ?"

        const val INSERT_SQL =
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms) " +
                "VALUES (?, ?, ?, ?, ?)"

        const val LIST_SQL =
            "SELECT client_id, key_version, encrypted, stored_at_ms FROM transcriptions " +
                "WHERE account_id = ? ORDER BY stored_at_ms ASC, client_id ASC"
    }
}
