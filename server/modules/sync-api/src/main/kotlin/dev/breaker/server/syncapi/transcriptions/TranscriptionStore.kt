package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.PrevKeyRule
import dev.breaker.server.syncapi.db.SqliteDatabase
import dev.breaker.server.syncapi.tokens.TokenStore
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
    suspend fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String, resave: Boolean = false): PushResult {
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
                val stored = readStoredRow(connection, accountId, clientId)
                if (stored == null) {
                    if (keyVersion < accountKeyVersion) {
                        PushResult.RefusedLower
                    } else {
                        val changeNo = bumpChangeSeq(connection, accountId)
                        insertRow(connection, accountId, clientId, keyVersion, encrypted, nowMs, changeNo)
                        PushResult.Stored
                    }
                } else if (stored.deleted == 1) {
                    // A deleted stored row: without the mark it is a NoOp and takes no number.
                    // With the mark: key-version order applies like an insert - above is refused
                    // first (already handled above the row lookup), then below is refused lower
                    // and writes nothing, then at-or-above reanimates with a fresh number.
                    if (!resave) {
                        PushResult.NoOp
                    } else if (keyVersion < accountKeyVersion) {
                        PushResult.RefusedLower
                    } else {
                        val changeNo = bumpChangeSeq(connection, accountId)
                        reanimateRow(connection, accountId, clientId, keyVersion, encrypted, nowMs, changeNo)
                        PushResult.Stored
                    }
                } else if (stored.keyVersion == keyVersion) {
                    PushResult.NoOp
                } else if (stored.keyVersion > keyVersion) {
                    PushResult.RefusedLower
                } else {
                    val changeNo = bumpChangeSeq(connection, accountId)
                    replaceRow(connection, accountId, clientId, keyVersion, encrypted, nowMs, changeNo)
                    PrevKeyRule.clearIfNoOldRows(connection, accountId)
                    PushResult.Replaced
                }
            }
        }
    }

    /**
     * Deletes one transcription of [accountId]. An applied delete takes a new change number,
     * clears the body and sets deleted. A repeat or unknown delete writes nothing and takes no
     * change number. Runs in one transaction.
     */
    suspend fun delete(accountId: Long, clientId: String): DeleteResult {
        return db.transaction<DeleteResult> { connection ->
            readAccountKeyVersion(connection, accountId)
                ?: throw IllegalStateException("sync-api: a transcription delete names an account with no row")
            val stored = readStoredRow(connection, accountId, clientId)
            if (stored == null) {
                DeleteResult.NoOp
            } else if (stored.deleted == 1) {
                DeleteResult.NoOp
            } else {
                val nowMs = clock.millis()
                val changeNo = bumpChangeSeq(connection, accountId)
                markDeleted(connection, accountId, clientId, nowMs, changeNo)
                PrevKeyRule.clearIfNoOldRows(connection, accountId)
                DeleteResult.Deleted
            }
        }
    }

    private fun markDeleted(
        connection: Connection,
        accountId: Long,
        clientId: String,
        storedAtMs: Long,
        changeNo: Long,
    ) {
        connection.prepareStatement(DELETE_SQL).use { statement ->
            statement.setLong(1, storedAtMs)
            statement.setLong(2, changeNo)
            statement.setLong(3, accountId)
            statement.setString(4, clientId)
            statement.executeUpdate()
        }
    }

    /** The rows of [accountId], oldest first by insert order. A replaced row keeps its place. */
    suspend fun list(accountId: Long): List<TranscriptionRow> =
        db.transaction<List<TranscriptionRow>> { connection -> readRows(connection, accountId) }

    /** One page of the account's feed, ordered by change number ascending (see TranscriptionPageReader). */
    suspend fun page(accountId: Long, since: Long?, listHead: Long? = null): PageResult =
        db.transaction<PageResult> { connection ->
            TranscriptionPageReader.read(connection, accountId, since, listHead)
        }

    /**
     * One pull of the feed for a user session, in one transaction, in this order: the page is read;
     * a Gone page returns at once and writes nothing; with a since, the session's number is set (the
     * store clamps it to the head); the purge then runs at the floor of the live sessions, when there
     * is one. The page read first is what the caller gets. One transaction matters: when the purge
     * fails, the number set just before it is rolled back as well, so no session claims a point whose
     * purge did not happen.
     */
    suspend fun pull(
        tokens: TokenStore, accountId: Long, tokenId: Long, since: Long?, listHead: Long? = null,
    ): PageResult =
        db.transaction<PageResult> { connection ->
            val result = TranscriptionPageReader.read(connection, accountId, since, listHead)
            if (result is PageResult.Gone) {
                result
            } else {
                if (listHead != null) {
                    // A list stores the first-page head: min(listHead, head now), never above it.
                    tokens.setPulledChangeOn(connection, tokenId, accountId, listHead)
                } else if (since != null) {
                    tokens.setPulledChangeOn(connection, tokenId, accountId, since)
                }
                val floor = tokens.pullFloorOn(connection, accountId)
                if (floor != null) TranscriptionPurge.purgeDeleteRecordsOn(connection, accountId, floor)
                result
            }
        }

    private fun readAccountKeyVersion(connection: Connection, accountId: Long): Int? =
        connection.prepareStatement(SELECT_ACCOUNT_KEY_VERSION_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) else null
            }
        }

    private fun readStoredRow(connection: Connection, accountId: Long, clientId: String): StoredRow? =
        connection.prepareStatement(SELECT_STORED_ROW_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else StoredRow(rows.getInt(1), rows.getInt(2))
            }
        }

    private fun bumpChangeSeq(connection: Connection, accountId: Long): Long {
        val updated = connection.prepareStatement(BUMP_CHANGE_SEQ_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeUpdate()
        }
        if (updated != 1) {
            throw IllegalStateException("sync-api: change_seq bump for account $accountId touched $updated rows, expected 1")
        }
        return connection.prepareStatement(SELECT_CHANGE_SEQ_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw IllegalStateException("sync-api: account $accountId vanished after change_seq bump")
                rows.getLong(1)
            }
        }
    }

    private fun insertRow(
        connection: Connection,
        accountId: Long,
        clientId: String,
        keyVersion: Int,
        encrypted: String,
        storedAtMs: Long,
        changeNo: Long,
    ) {
        connection.prepareStatement(INSERT_SQL).use { statement ->
            statement.setLong(1, accountId)
            statement.setString(2, clientId)
            statement.setInt(3, keyVersion)
            statement.setString(4, encrypted)
            statement.setLong(5, storedAtMs)
            statement.setLong(6, changeNo)
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
        changeNo: Long,
    ) {
        connection.prepareStatement(REPLACE_SQL).use { statement ->
            statement.setInt(1, keyVersion)
            statement.setString(2, encrypted)
            statement.setLong(3, storedAtMs)
            statement.setLong(4, changeNo)
            statement.setLong(5, accountId)
            statement.setString(6, clientId)
            statement.executeUpdate()
        }
    }

    // Bring a deleted row back to life: same id and place, new key version, body, stored time,
    // deleted = 0, new change number.
    private fun reanimateRow(
        connection: Connection,
        accountId: Long,
        clientId: String,
        keyVersion: Int,
        encrypted: String,
        storedAtMs: Long,
        changeNo: Long,
    ) {
        connection.prepareStatement(REANIMATE_SQL).use { statement ->
            statement.setInt(1, keyVersion)
            statement.setString(2, encrypted)
            statement.setLong(3, storedAtMs)
            statement.setLong(4, changeNo)
            statement.setLong(5, accountId)
            statement.setString(6, clientId)
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

        const val SELECT_STORED_ROW_SQL =
            "SELECT key_version, deleted FROM transcriptions WHERE account_id = ? AND client_id = ?"

        const val BUMP_CHANGE_SEQ_SQL = "UPDATE accounts SET change_seq = change_seq + 1 WHERE id = ?"

        const val SELECT_CHANGE_SEQ_SQL = "SELECT change_seq FROM accounts WHERE id = ?"

        // All rows of one account, oldest first. A replaced row keeps its place.
        const val SELECT_ROWS_SQL =
            "SELECT client_id, key_version, encrypted, stored_at_ms FROM transcriptions " +
                "WHERE account_id = ? AND deleted = 0 ORDER BY id"

        const val DELETE_SQL =
            "UPDATE transcriptions SET encrypted = '-', stored_at_ms = ?, change_no = ?, deleted = 1 " +
                "WHERE account_id = ? AND client_id = ?"

        const val INSERT_SQL =
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms, change_no) " +
                "VALUES (?, ?, ?, ?, ?, ?)"

        const val REPLACE_SQL =
            "UPDATE transcriptions SET key_version = ?, encrypted = ?, stored_at_ms = ?, change_no = ? " +
                "WHERE account_id = ? AND client_id = ?"

        const val REANIMATE_SQL =
            "UPDATE transcriptions SET key_version = ?, encrypted = ?, stored_at_ms = ?, change_no = ?, deleted = 0 " +
                "WHERE account_id = ? AND client_id = ?"
    }
}
